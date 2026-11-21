package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.MotionEvent
import android.view.Surface
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.platform.AudioConfig
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.AdapterDriver
import com.carlink.protocol.AudioCommand
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.AudioRoutingState
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.Message
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhaseMessage
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.StreamPurpose
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoStreamingSignal
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.usb.UsbConstants
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Carlinkit CPC200-CCPA CarPlay adapter, owned from USB attach to detach. The adapter
 * holds Apple's authentication chip and does the CarPlay handshake with the iPhone itself;
 * we feed its H.264 to a decoder, play its PCM, and send touch and microphone back.
 *
 * It runs in the background and claims nothing until an iPhone connects ([onIphone] true);
 * only then does OAL start a CarPlay attempt with a [DongleCarPlayRuntime] bound to it.
 * Android phones are refused, so Android Auto stays on OAL's own transports.
 * Protocol and media code is lifted from lvalen91/carlink (public domain).
 */
class CarlinkitAdapter(
    private val context: Context,
    private val device: UsbDevice,
    private val onIphone: (connected: Boolean) -> Unit,
) {
    /** Set by the bound runtime: status sink and microphone admission. Null while no attempt owns the screen. */
    class Binding(val report: (String) -> Unit, val captureAdmission: () -> Boolean)
    @Volatile var binding: Binding? = null
    @Volatile var iphoneConnected = false
        private set
    @Volatile var rendered = false
        private set
    /** The user returned to Android Auto with the iPhone still on the adapter: keep it off until replug. */
    @Volatile private var parked = false

    val deviceName: String get() = device.deviceName
    private val closed = AtomicBoolean(false)
    @Volatile private var sessionEnded = CountDownLatch(1)
    @Volatile private var surface: Surface? = null
    @Volatile private var driver: AdapterDriver? = null
    private val log = LogCallback { OalLog.i(TAG, it) }
    private val renderer = H264Renderer(VIDEO_WIDTH, VIDEO_HEIGHT, null, log, AppExecutors(), null)
    private val audio = DualStreamAudioManager(context, log, AudioConfig.forPlatform())
    private val mic = MicrophoneCaptureManager(context, log)
    private val lock = Any()
    private var keyframeTimer: Timer? = null
    private var micTimer: Timer? = null
    private var micDecodeType = 0
    private var lastIncomingDecodeType = 5
    @Volatile private var siriActive = false
    @Volatile private var callActive = false
    @Volatile private var alertActive = false
    private val worker = Thread(::runLoop, "CarlinkitAdapter").apply { isDaemon = true }

    init {
        renderer.setKeyframeRequestCallback { driver?.sendCommand(CommandMapping.FRAME) }
    }

    fun start() = worker.start()

    fun close() {
        if (closed.getAndSet(true)) return
        sessionEnded.countDown()
        worker.interrupt()
    }

    private fun runLoop() {
        val usb = context.getSystemService(UsbManager::class.java)
        try {
            if (!usb.hasPermission(device) &&
                UsbCarPlayPermission.await(context, usb, device) { closed.get() } != UsbCarPlayPermission.Result.GRANTED) {
                OalLog.w(TAG, "USB permission for the CarPlay adapter was not granted")
                return
            }
            while (!closed.get()) {
                if (!session(usb)) return
                if (!closed.get()) Thread.sleep(RESTART_DELAY_MS)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            OalLog.e(TAG, "Adapter loop failed: $e")
        } finally {
            setIphone(false)
            renderer.stop()
            audio.release()
            mic.release()
        }
    }

    /** One adapter session. Returns false when the adapter cannot be opened. */
    private fun session(usb: UsbManager): Boolean {
        val wrapper = UsbDeviceWrapper(context, usb, device) { OalLog.d(TAG, it) }
        if (!wrapper.open()) {
            OalLog.w(TAG, "Could not open the CarPlay adapter")
            return false
        }
        sessionEnded = CountDownLatch(1)
        try {
            // The adapter keeps session state across reconnects; clear any stale one first.
            wrapper.write(MessageSerializer.serializeDisconnectPhone())
            wrapper.write(MessageSerializer.serializeCloseDongle())
            Thread.sleep(STALE_CLEAR_MS)
            if (closed.get()) return true
            audio.initialize()
            val adapter = AdapterDriver(
                usbDevice = wrapper,
                messageHandler = ::onMessage,
                errorHandler = { OalLog.w(TAG, "Adapter error: $it"); sessionEnded.countDown() },
                logCallback = { OalLog.d(TAG, it) },
                videoProcessor = videoProcessor,
            )
            driver = adapter
            val metrics = context.resources.displayMetrics
            val config = AdapterConfig(
                width = metrics.widthPixels and 1.inv(),
                height = metrics.heightPixels and 1.inv(),
                dpi = metrics.densityDpi,
                androidWorkMode = false,
                boxName = "SierraLink",
            )
            adapter.start(config, "FULL", emptySet(), config.width, config.height)
            OalLog.i(TAG, "CarPlay adapter ready, waiting for an iPhone")
            sessionEnded.await()
            if (closed.get()) adapter.sendGracefulTeardown()
            return true
        } finally {
            endPhone()
            driver?.stop()
            driver = null
            audio.release()
            wrapper.close()
        }
    }

    private val videoProcessor = object : UsbDeviceWrapper.VideoDataProcessor {
        override fun processVideoDirect(data: ByteArray, dataLength: Int, sourcePtsMs: Int) {
            // 20-byte adapter video header precedes the Annex-B stream.
            if (dataLength > VIDEO_HEADER) renderer.feedDirect(data, VIDEO_HEADER, dataLength - VIDEO_HEADER)
        }
    }

    private fun onMessage(message: Message) {
        when (message) {
            is PluggedMessage -> {
                if (message.phoneType == PhoneType.ANDROID_AUTO) {
                    OalLog.w(TAG, "Android phone on the adapter; Android Auto stays on OAL's own transport")
                    driver?.send(MessageSerializer.serializeDisconnectPhone())
                    return
                }
                if (parked) {
                    driver?.send(MessageSerializer.serializeDisconnectPhone())
                    return
                }
                surface?.let(renderer::resume)
                setIphone(true)
            }
            VideoStreamingSignal -> if (!rendered && iphoneConnected) {
                rendered = true
                binding?.report?.invoke("media stage=video-first-frame-confirmed count=1")
                scheduleKeyframes()
            }
            is UnpluggedMessage -> sessionEnded.countDown()
            is PhaseMessage -> if (message.phase == 0 && iphoneConnected) sessionEnded.countDown()
            is AudioDataMessage -> onAudio(message)
            else -> Unit
        }
    }

    private fun setIphone(connected: Boolean) {
        if (iphoneConnected == connected) return
        iphoneConnected = connected
        OalLog.i(TAG, if (connected) "iPhone connected to the adapter" else "iPhone left the adapter")
        onIphone(connected)
    }

    private fun onAudio(message: AudioDataMessage) {
        message.command?.let { onAudioCommand(it, message.decodeType); return }
        if (message.volumeDuration != null) { audio.setDucking(message.volume); return }
        val data = message.data ?: return
        lastIncomingDecodeType = message.decodeType
        audio.writeAudio(data, message.audioDataOffset, message.audioDataLength, message.audioType,
            message.decodeType, AudioRoutingState(siriActive, callActive, alertActive))
    }

    private fun onAudioCommand(command: AudioCommand, decodeType: Int) {
        when (command) {
            AudioCommand.AUDIO_TBT_START, AudioCommand.AUDIO_NAVI_START -> audio.onNavStarted()
            AudioCommand.AUDIO_NAVI_STOP -> audio.onNavStopped()
            AudioCommand.AUDIO_NAVI_COMPLETE -> audio.stopNavTrack()
            AudioCommand.AUDIO_SIRI_START -> {
                siriActive = true
                audio.onPurposeChanged(StreamPurpose.SIRI)
                startMic(5)
            }
            AudioCommand.AUDIO_PHONECALL_START -> {
                callActive = true
                audio.onPurposeEnded(StreamPurpose.RINGTONE)
                audio.onPurposeChanged(StreamPurpose.PHONE_CALL)
                startMic(lastIncomingDecodeType)
            }
            AudioCommand.AUDIO_SIRI_STOP -> {
                siriActive = false
                audio.stopSiriTrack()
                if (!callActive) { audio.onPurposeEnded(StreamPurpose.SIRI); stopMic() }
            }
            AudioCommand.AUDIO_PHONECALL_STOP -> {
                callActive = false
                audio.stopPhoneCallTrack()
                audio.onPurposeEnded(StreamPurpose.PHONE_CALL)
                stopMic()
            }
            AudioCommand.AUDIO_MEDIA_START -> {
                audio.setDucking(1.0f)
                audio.onPurposeEnded(StreamPurpose.RINGTONE)
                audio.onPurposeChanged(StreamPurpose.MEDIA)
            }
            AudioCommand.AUDIO_MEDIA_STOP -> audio.onPurposeEnded(StreamPurpose.MEDIA)
            AudioCommand.AUDIO_ALERT_START -> { alertActive = true; audio.onPurposeChanged(StreamPurpose.ALERT) }
            AudioCommand.AUDIO_ALERT_STOP -> {
                alertActive = false
                audio.onPurposeEnded(StreamPurpose.ALERT)
                audio.onPurposeEnded(StreamPurpose.RINGTONE)
            }
            AudioCommand.AUDIO_INPUT_CONFIG -> {
                lastIncomingDecodeType = decodeType
                if (micDecodeType != 0 && micDecodeType != decodeType) startMic(decodeType)
            }
            else -> Unit
        }
    }

    private fun startMic(decodeType: Int) = synchronized(lock) {
        if (micDecodeType == decodeType) return@synchronized
        stopMicLocked()
        if (binding?.captureAdmission?.invoke() != true || !mic.start(decodeType)) {
            OalLog.w(TAG, "Microphone not admitted or failed to start")
            return@synchronized
        }
        micDecodeType = decodeType
        val chunk = if (decodeType == 3) 320 else 640 // 20 ms at 8 kHz or 16 kHz mono
        micTimer = Timer("CarlinkitMic").apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val pcm = mic.readChunk(maxBytes = chunk) ?: return
                    if (pcm.isNotEmpty()) driver?.sendAudio(pcm, decodeType, MIC_AUDIO_TYPE)
                }
            }, 0, 20)
        }
    }

    private fun stopMic() = synchronized(lock) { stopMicLocked() }

    private fun stopMicLocked() {
        micTimer?.cancel(); micTimer = null
        if (micDecodeType != 0) mic.stop()
        micDecodeType = 0
    }

    /** CarPlay only: one keyframe 2.5 s in, then every 30 s; tuned for GM's Intel decoder going stale. */
    private fun scheduleKeyframes() = synchronized(lock) {
        keyframeTimer?.cancel()
        keyframeTimer = Timer("CarlinkitKeyframe").apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { driver?.sendCommand(CommandMapping.FRAME) }
            }, 2_500, 30_000)
        }
    }

    private fun endPhone() {
        synchronized(lock) { keyframeTimer?.cancel(); keyframeTimer = null; stopMicLocked() }
        siriActive = false; callActive = false; alertActive = false
        renderer.stop()
        rendered = false
        setIphone(false)
    }

    /** Drop the iPhone and ignore it until the adapter is replugged; the screen went back to Android Auto. */
    fun park() {
        if (!iphoneConnected) return
        parked = true
        driver?.send(MessageSerializer.serializeDisconnectPhone())
        sessionEnded.countDown()
    }

    fun touch(action: Int, actionIndex: Int, pointers: List<CarPlayPointer>, width: Int, height: Int) {
        val adapter = driver ?: return
        if (width <= 0 || height <= 0 || pointers.isEmpty()) return
        val points = pointers.mapIndexed { index, p ->
            val state = when {
                action == MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
                index != actionIndex -> MultiTouchAction.MOVE
                action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP -> MultiTouchAction.UP
                else -> MultiTouchAction.MOVE
            }
            MessageSerializer.TouchPoint((p.x / width).coerceIn(0f, 1f), (p.y / height).coerceIn(0f, 1f), state, p.id)
        }
        if (adapter.sendMultiTouch(points)) binding?.report?.invoke("media stage=touch-sent count=1")
    }

    fun attachSurface(surface: Surface) {
        this.surface = surface
        if (iphoneConnected) renderer.resume(surface)
    }

    fun detachSurface(surface: Surface) {
        if (this.surface !== surface) return
        this.surface = null
        renderer.stop()
    }

    companion object {
        private const val TAG = "CarlinkitAdapter"
        private const val VIDEO_HEADER = 20
        private const val MIC_AUDIO_TYPE = 3
        private const val STALE_CLEAR_MS = 200L
        private const val RESTART_DELAY_MS = 2_000L
        // Initial decoder size only; the decoder takes the real size from the stream.
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080

        /** Carlinkit CPC200-CCPA (it can switch between PIDs 0x1520 and 0x1521 at any time).
         * shortcut: the 0x08E4:0x01C0 alternate SKU is not recognised; add it if one shows up. */
        fun isAdapter(device: UsbDevice?): Boolean = device?.vendorId == UsbConstants.CARLINKIT_VID
    }
}

/** The CarPlay screen owner for an adapter whose iPhone is connected. Ends when the iPhone leaves. */
class DongleCarPlayRuntime(
    private val adapter: CarlinkitAdapter,
    private val report: (String) -> Unit,
    private val attemptContext: CarPlayAttemptContext,
) : CarPlayRuntime {
    private val done = CountDownLatch(1)

    override fun run() {
        adapter.binding = CarlinkitAdapter.Binding(report, attemptContext.captureAdmission)
        attemptContext.onInputAdvertised(true)
        try {
            if (!adapter.iphoneConnected) return
            report("media stage=runtime-authentication-accepted")
            if (adapter.rendered) report("media stage=video-first-frame-confirmed count=1")
            while (adapter.iphoneConnected && !done.await(POLL_MS, TimeUnit.MILLISECONDS)) Unit
        } finally {
            adapter.binding = null
            // Still connected means Stop/Return took the screen back: release the iPhone too.
            if (adapter.iphoneConnected) adapter.park()
            report("media stage=session-ended")
        }
    }

    fun touch(action: Int, actionIndex: Int, pointers: List<CarPlayPointer>, width: Int, height: Int) =
        adapter.touch(action, actionIndex, pointers, width, height)

    override fun attachSurface(surface: Surface) = adapter.attachSurface(surface)
    override fun detachSurface(surface: Surface) = adapter.detachSurface(surface)
    override fun telemetrySnapshot() =
        com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot(generation = 0, presentationRendered = adapter.rendered)

    override fun cancel() = done.countDown()
    override fun close() = cancel()

    private companion object { const val POLL_MS = 250L }
}
