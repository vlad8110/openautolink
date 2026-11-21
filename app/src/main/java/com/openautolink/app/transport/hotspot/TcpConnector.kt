package com.openautolink.app.transport.hotspot

import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.HostUsability
import com.openautolink.app.transport.OalProtocol
import com.openautolink.app.transport.WppInterfacePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client that dials the companion app's proxy on the phone.
 *
 * Wireless (WPP) is the only network transport: the Bluetooth handshake locates
 * the companion and hands its address in as [manualIp]. Without an address there
 * is nothing to dial.
 */
class TcpConnector(
    private val scope: CoroutineScope,
    private val onSocketReady: (Socket) -> Unit,
    /** Called after each failed dial, so the session can count reconnect attempts. */
    private val onConnectFailure: (() -> Unit)? = null,
    private val admissionCheck: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "OAL-TcpConn"
        const val COMPANION_PORT = OalProtocol.AA_PORT
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RETRY_DELAY_MS = 3000L
    }

    /** When set, bind outbound sockets to this selected local IPv4. */
    var sourceIpv4: String? = null

    /** The companion address to dial: "host" or "host:port". */
    var manualIp: String? = null

    private var connectJob: Job? = null

    @Volatile
    private var isRunning = false

    /**
     * True while a dial attempt is in flight or connected.
     *
     * Exposed so a caller can tell a live connector from a spent one: treating
     * "a connector object exists" as "busy" made a dead connector permanent and
     * blocked every reconnect attempt.
     */
    val isActive: Boolean get() = isRunning

    fun start() {
        if (isRunning || !admissionCheck()) return
        // "host" or "host:port". A port overrides COMPANION_PORT — used by the
        // debug discovery-injection path (DEBUG_INJECT_PHONE).
        val raw = manualIp?.takeIf { it.isNotBlank() }
        if (raw == null) {
            OalLog.w(TAG, "No companion address to dial — waiting for the Bluetooth handshake")
            return
        }
        val colonIdx = raw.lastIndexOf(':')
        val parsedPort = if (colonIdx > 0) raw.substring(colonIdx + 1).toIntOrNull() else null
        val (host, port) = if (parsedPort != null && parsedPort in 1..65535 && !raw.contains(']')) {
            raw.substring(0, colonIdx) to parsedPort
        } else {
            raw to COMPANION_PORT
        }
        // Issue #48: an IPv6 literal is unusable on OAL's IPv4-only local link.
        if (HostUsability.isUnusable(host)) {
            OalLog.w(TAG, "Companion host $host is IPv6/unusable — not dialling it")
            return
        }
        isRunning = true
        OalLog.i(TAG, "Dialling companion at $host:$port")

        connectJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning && admissionCheck()) {
                if (tryConnect(host, port)) return@launch
                onConnectFailure?.invoke()
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun tryConnect(host: String, port: Int): Boolean {
        if (!isRunning || !admissionCheck()) return false
        val sourceIpv4 = sourceIpv4
        if (sourceIpv4 != null &&
            !WppInterfacePolicy.isPeerOnSelectedSubnet(sourceIpv4, host)
        ) {
            OalLog.i(TAG, "Skipping $host — outside selected WPP source subnet $sourceIpv4")
            return false
        }
        OalLog.i(TAG, "Connecting to $host:$port")
        val socket = Socket()
        return try {
            if (sourceIpv4 != null) {
                socket.bind(InetSocketAddress(sourceIpv4, 0))
                OalLog.i(TAG, "Binding TCP connector to selected WPP source $sourceIpv4")
            }
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // Socket.connect() is blocking and is not cancelled with connectJob.
            // A different Bluetooth phone can replace this connector while the
            // old dial is still inside connect(); never publish that late socket.
            if (!isRunning || !admissionCheck()) {
                OalLog.i(TAG, "Connector stopped while dialling $host — closing late socket")
                runCatching { socket.close() }
                return false
            }
            socket.tcpNoDelay = true
            // Detect dead phone within ~10s (kernel sends keepalive after idle, then
            // 3 probes 2s apart). Critical for sleep/wake and ungraceful disconnects.
            try {
                socket.keepAlive = true
                // Best-effort: low-level setsockopt for tighter timing on Android.
                // Falls back to OS defaults (~2h idle) if reflection fails.
                setKeepAliveParams(socket, idleSec = 5, intervalSec = 2, count = 3)
            } catch (e: Exception) {
                OalLog.d(TAG, "TCP keepalive tuning unavailable: ${e.message}")
            }
            if (sourceIpv4 != null) {
                OalLog.i(TAG, "Connected to companion at $host:$port from $sourceIpv4")
            } else {
                OalLog.i(TAG, "Connected to companion at $host:$port")
            }
            if (!admissionCheck()) { runCatching { socket.close() }; return false }
            // A successful dial is the strongest evidence of where the companion
            // is — better than any cache or scan. Publish it so the Bluetooth
            // handshake does not go looking for an address we are already
            // connected to. Observed: the resolver scanned blind and reported
            // "no companion" 0.85s after this exact connection succeeded.
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                .lastKnownPhoneIp = host
            isRunning = false
            onSocketReady(socket)
            true
        } catch (e: Exception) {
            OalLog.d(TAG, "Connection to $host:$port failed: ${e.message}")
            runCatching { socket.close() }
            false
        }
    }

    fun stop() {
        OalLog.i(TAG, "Stopping TCP connector")
        isRunning = false
        connectJob?.cancel()
        connectJob = null
    }
}
