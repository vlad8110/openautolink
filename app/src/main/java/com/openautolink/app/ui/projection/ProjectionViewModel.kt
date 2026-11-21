package com.openautolink.app.ui.projection

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.input.KeyRemapParser
import com.openautolink.app.input.SteeringWheelController
import com.openautolink.app.input.TouchForwarder
import com.openautolink.app.input.TouchForwarderImpl
import com.openautolink.app.input.TouchCoordinateSpace
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.video.VideoStats
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.FileLogWriter
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadFailureCode
import com.openautolink.app.diagnostics.UploadResult
import com.openautolink.app.diagnostics.LogcatCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ProjectionUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val statusMessage: String = "Ready",
    val phoneName: String? = null,
    val videoStats: VideoStats = VideoStats(),
    val audioStats: AudioStats = AudioStats(),
    val showStats: Boolean = false,
    val maneuver: ManeuverState? = null,
    val phoneBatteryLevel: Int? = null,
    val phoneBatteryCritical: Boolean = false,
    val voiceSessionActive: Boolean = false,
    val phoneSignalStrength: Int? = null,
    val wifiFrequencyMhz: Int = 0,
    val displayMode: String = AppPreferences.DEFAULT_DISPLAY_MODE,
    val safeAreaTop: Int = AppPreferences.DEFAULT_SAFE_AREA_TOP,
    val safeAreaBottom: Int = AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
    val safeAreaLeft: Int = AppPreferences.DEFAULT_SAFE_AREA_LEFT,
    val safeAreaRight: Int = AppPreferences.DEFAULT_SAFE_AREA_RIGHT,
    val videoScalingMode: String = AppPreferences.DEFAULT_VIDEO_SCALING_MODE,
    val aaPixelAspect: Int = -1,
    val aaDpi: Int = 160,
    /** Base density calculated from the saved resolution.
     *  Native auto negotiation derives the actual density independently for
     *  every advertised tier. 0 until the first session is built. */
    val effectiveDpi: Int = 0,
    val aaWidthMargin: Int = 0,
    val aaHeightMargin: Int = 0,
    val aaAutoMargins: Boolean = AppPreferences.DEFAULT_AA_AUTO_MARGINS,
    val fileLoggingActive: Boolean = false,
    val fileLoggingEnabled: Boolean = false,
    val uploadEnabled: Boolean = false,
    val uploadState: LogUploadState = LogUploadState.IDLE,
    val uploadStatusMessage: String? = null,
    /**
     * Non-zero while the session is retrying after a drop.
     *
     * The idle screen showed a bare "Disconnected", which reads as "gave up"
     * when the app is in fact retrying every few seconds — exactly the state the
     * car sits in after an ignition cycle while it waits for the phone.
     */
    val reconnectAttempt: Int = 0,
    /** Shows the floating "simulate ignition cycle" button. Maintainer tool. */
    val simulateIgnitionButton: Boolean = false,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ProjectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ProjectionViewModel"
    }

    private val preferences = AppPreferences.getInstance(application)
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val sessionManager = SessionManager.getInstance(viewModelScope, application, audioManager)
    private val _carPlayState = MutableStateFlow(com.openautolink.app.carplay.CarPlayUiState())
    val carPlayState = _carPlayState.asStateFlow()
    val carPlayEnabled: StateFlow<Boolean> = preferences.effectiveCarPlaySettings.map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val carPlaySurface = MutableStateFlow<Surface?>(null)
    private val _carPlayDisplay = MutableStateFlow(com.openautolink.carplaylab.media.CarPlayDisplayPreferences())
    val carPlayDisplay = _carPlayDisplay.asStateFlow()
    @Volatile private var usbCarPlayRuntime: com.openautolink.app.carplay.OalUsbCarPlayRuntime? = null
    private var unifiedUsbManager: com.openautolink.app.transport.usb.UsbConnectionManager? = null
    private val usbConsent = com.openautolink.app.carplay.UsbHotPlugConsent()
    private val usbHandoff = com.openautolink.app.carplay.UsbProjectionHandoff { stage ->
        OalLog.i(TAG, "USB handoff stage=${stage.name}")
        DiagnosticLog.i("usb_handoff", "stage=${stage.name}")
    }
    val usbSwitchFailure = usbHandoff.failure
    private val usbCaptureClaims = mutableListOf<com.shilapi.xcertplay.media.MicrophoneCaptureFence.Lease>()
    private fun releaseUsbCaptureClaims() { usbCaptureClaims.forEach { it.release() }; usbCaptureClaims.clear() }
    private val usbDispatchMutex = kotlinx.coroutines.sync.Mutex()
    private val usbDispatchEpoch = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var carPlayRuntime: com.openautolink.app.carplay.OalCarPlayRuntime? = null
    private val carPlayCoordinator = com.openautolink.app.carplay.CarPlayCoordinator(
        sessionLeases = com.openautolink.app.carplay.CarPlaySessionLeases.forContext(application),
        mediaLeases = com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.forContext(application),
        retireAndroidAuto = {
            kotlinx.coroutines.runBlocking {
                // Stop and await the advertiser BEFORE SessionManager clears its WPP owner.
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.retireForCarPlay()
                sessionManager.stop()
            }
            DiagnosticLog.i("carplay", "AA resource retirement acknowledged; CarPlay admission reserved")
        },
        cancelRetainedClusterHost = { com.openautolink.app.secondary.SecondaryOutputRuntime.get(application).cancelRetainedHost() },
        captureRetainedClusterHostCancellation = {
            com.openautolink.app.secondary.SecondaryOutputRuntime.get(application).captureRetainedHostCancellation()
        },
        retireAndroidAutoRetainingHost = {
            kotlinx.coroutines.runBlocking {
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.retireForCarPlay()
                sessionManager.stopForExplicitReconnect()
            }
        },
        onState = { state ->
            val previousStatus = _carPlayState.value.status
            _carPlayState.value = state
            if (previousStatus != state.status) DiagnosticLog.i("carplay", state.status)
        },
    )

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                carPlayCoordinator.refreshTelemetry()
            }
        }
    }

    private val projectionLaunchPolicy = ProjectionLaunchPolicy()
    private val _carPlayStartupGuidance = MutableStateFlow<String?>(null)
    val carPlayStartupGuidance = _carPlayStartupGuidance.asStateFlow()

    /** One automatic attempt per retained ViewModel, using disk-backed preferences, not UI seeds. */
    fun startProjectionOnce() {
        viewModelScope.launch {
            preferences.projectionConnectionPolicy.state.first { it.loaded }
            val saved = preferences.effectiveCarPlaySettings.first()
            val aaNormalTransport = preferences.effectiveDirectTransport.first()
            startUnifiedUsb()
            val wirelessCarPlay = usbSwitchPolicy.wirelessCarPlay(saved.enabled, saved.connectionMode)
            _carPlayAutomaticResolution.value = saved.usesWindowGeometry
            val issue = if (wirelessCarPlay) saved.videoError() ?: carPlayStartupIssue(saved) else null
            if (wirelessCarPlay && issue == null) _carPlayDisplay.value = saved.displayRequest()
            val ignitionOff = com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()
            if (usbOverrideGeneration != null) return@launch
            projectionLaunchPolicy.request(wirelessCarPlay, issue == null, ignitionOff,
                connectAndroidAuto = { if (aaNormalTransport != AppPreferences.DIRECT_TRANSPORT_USB) connect() },
                connectCarPlay = {
                    _carPlayStartupGuidance.value = null
                    carPlayCoordinator.connectWithContext(true, ::createCarPlayRuntime)
                },
                showSetup = { _carPlayStartupGuidance.value = issue ?: "CarPlay is paused while ignition is off. Retry when the car is ready." },
            )
        }
    }

    /** Wait for exact CarPlay retirement before handing the cable to AA or a new attempt. */
    private suspend fun releaseCarPlayUsbOwner(preserveFailure: Boolean = false, preserveClusterHostForReconnect: Boolean = false) {
        if (!_carPlayState.value.selected) return
        kotlinx.coroutines.withTimeout(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                carPlayCoordinator.returnToAndroidAuto(preserveFailure = preserveFailure, preserveClusterHostForReconnect = preserveClusterHostForReconnect) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
            }
        }
        carPlayRuntime = null
        usbCarPlayRuntime = null
        synchronized(connectLock) { hasConnected = false; connectInFlight = false }
    }

    private suspend fun stopUnifiedUsb() {
        usbDispatchEpoch.incrementAndGet()
        usbPolicyEpoch.incrementAndGet()
        _usbHotPlugPrompt.value = null
        unifiedUsbManager?.stop()
        unifiedUsbManager = null
        retireUsbProjection()
        sessionManager.externalUsbTransport = false
        usbHandoff.release()
        usbSwitchPolicy.clearRetired()
        releaseUsbCaptureClaims()
    }

    private suspend fun restartUnifiedUsb() {
        usbDispatchMutex.lock()
        try {
            usbHandoff.dismissFailure()
            usbPolicyEpoch.incrementAndGet()
            _usbHotPlugPrompt.value = null
            retireUsbProjection()
            unifiedUsbManager?.failedAdmission()
            sessionManager.externalUsbTransport = false
            usbHandoff.release()
            usbSwitchPolicy.clearRetired()
            releaseUsbCaptureClaims()
            _usbHotPlugPrompt.value = null
            synchronized(connectLock) { hasConnected = false; connectInFlight = false }
            restoreNormalProjection()
            unifiedUsbManager?.retryCandidates()
        } catch (failure: Exception) {
            usbHandoff.failed()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        } finally { usbDispatchMutex.unlock() }
        startUnifiedUsb()
    }

    /** Observe USB concurrently with normal AA/WPP; observation is not projection ownership. */
    private suspend fun startUnifiedUsb() {
        if (com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused()) return
        usbDispatchMutex.lock()
        try {
            if (unifiedUsbManager != null) return
            val dispatchEpoch = usbDispatchEpoch.incrementAndGet()
            val manager = com.openautolink.app.transport.usb.UsbConnectionManager(
                context = getApplication(), scope = viewModelScope,
                consent = usbConsent,
                onCandidate = { generation, device ->
                    viewModelScope.launch {
                        val saved = preferences.effectiveCarPlaySettings.first()
                        if (dispatchEpoch != usbDispatchEpoch.get()) return@launch
                        if (device.vendorId == 0x05ac && (!saved.enabled || saved.videoError() != null)) {
                            unifiedUsbManager?.stay(generation)
                            return@launch
                        }
                        if (unifiedUsbManager?.pendingDevice(generation) == null) return@launch
                        if (presentBlockedUsbCarPlayFailure(device)) {
                            unifiedUsbManager?.stay(generation)
                            return@launch
                        }
                        val busy = usbSwitchPolicy.shouldPrompt(
                            sessionManager.sessionState.value != SessionState.IDLE,
                            synchronized(connectLock) { connectInFlight }, _carPlayState.value.selected)
                        if (busy) _usbHotPlugPrompt.value = com.openautolink.app.carplay.UsbHotPlugPrompt(
                            generation, if (device.vendorId == 0x05ac) "CarPlay" else "Android Auto")
                        else acceptUsbHotPlug(generation, automatic = true)
                    }
                },
                onCandidateDetached = { generation ->
                    if (_usbHotPlugPrompt.value?.generation == generation) _usbHotPlugPrompt.value = null
                },
                onAdmissionEnded = { generation ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        finishUsbOverride(dispatchEpoch, generation)
                    }
                },
                onTransportReady = { pipe ->
                    val generation = usbOverrideGeneration
                    val selectedManager = unifiedUsbManager
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        usbDispatchMutex.lock()
                        try {
                            if (dispatchEpoch != usbDispatchEpoch.get() || generation == null ||
                                usbOverrideGeneration != generation || selectedManager?.ownsTransport(pipe, generation) != true ||
                                !sessionManager.externalUsbTransport) {
                                pipe.close()
                                return@launch
                            }
                            doConnect(usbAdmission = true)
                            if (!sessionManager.acceptUsbTransport(pipe)) {
                                sessionManager.stop()
                                selectedManager.failedAdmission(generation)
                            } else usbAaStartedGeneration = generation
                        } catch (_: Exception) {
                            pipe.close()
                            sessionManager.stop()
                            if (generation != null) selectedManager?.failedAdmission(generation)
                        } finally { usbDispatchMutex.unlock() }
                    }
                },
                onTransportDetached = { pipe, retired -> sessionManager.detachUsbTransport(pipe, retired) },
                onIphoneSelected = { device ->
                    val selectedManager = unifiedUsbManager
                    val generation = usbOverrideGeneration
                    val transition = selectedManager?.iphoneReenumerationCallbacks(device)
                        ?: Pair({ false }, { _: android.hardware.usb.UsbDevice -> false })
                    val retired = selectedManager?.iphoneRetirementAcknowledgement(device) ?: {}
                    val permission = selectedManager?.iphonePermissionCallbacks(device) ?: Pair({ false }, { false })
                    val saved = kotlinx.coroutines.runBlocking { preferences.effectiveCarPlaySettings.first() }
                    if (!saved.enabled || usbOverrideGeneration == null) false else {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            usbDispatchMutex.lock()
                            try {
                                if (dispatchEpoch != usbDispatchEpoch.get() || generation == null || usbOverrideGeneration != generation ||
                                    !preferences.effectiveCarPlaySettings.first().enabled) {
                                    retired()
                                    return@launch
                                }
                                val accepted = carPlayCoordinator.connectWithContext(true) { attemptContext, report ->
                                    try {
                                        check(dispatchEpoch == usbDispatchEpoch.get() && usbOverrideGeneration == generation)
                                        createProjectionCarPlayRuntime(attemptContext, report, device, transition.first, transition.second, retired,
                                            permission.first, permission.second)
                                    } catch (failure: Exception) { retired(); throw failure }
                                }
                                if (!accepted) retired()
                            } finally { usbDispatchMutex.unlock() }
                        }
                        true
                    }
                },
                onIphoneDetached = { _, retired ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        usbDispatchMutex.lock()
                        try {
                            if (dispatchEpoch == usbDispatchEpoch.get()) releaseCarPlayUsbOwner()
                            retired()
                        } finally { usbDispatchMutex.unlock() }
                    }
                },
            )
            unifiedUsbManager = manager
            manager.start()
        } finally { usbDispatchMutex.unlock() }
    }

    private val _usbHotPlugPrompt = MutableStateFlow<com.openautolink.app.carplay.UsbHotPlugPrompt?>(null)
    val usbHotPlugPrompt: StateFlow<com.openautolink.app.carplay.UsbHotPlugPrompt?> = _usbHotPlugPrompt.asStateFlow()
    private val usbSwitchPolicy = com.openautolink.app.carplay.UsbHotPlugSwitchPolicy()
    private val usbOverrideGeneration: Long? get() = usbSwitchPolicy.generation
    @Volatile private var usbAaStartedGeneration: Long? = null
    private val usbPolicyEpoch = java.util.concurrent.atomic.AtomicLong()

    fun stayOnCurrentProjection(generation: Long) {
        unifiedUsbManager?.stay(generation)
        if (_usbHotPlugPrompt.value?.generation == generation) _usbHotPlugPrompt.value = null
    }

    fun acceptUsbHotPlug(generation: Long) = acceptUsbHotPlug(generation, automatic = false)

    private fun acceptUsbHotPlug(generation: Long, automatic: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            usbDispatchMutex.lock()
            try {
                val manager = unifiedUsbManager ?: return@launch
                var promptInstead = false
                val previousGeneration = usbOverrideGeneration
                if (usbCaptureClaims.size >= 2) return@launch // failed retirement: explicit Stop must clear exact debt
                val captureClaim = com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryProjectionClaim() ?: return@launch
                usbCaptureClaims.add(captureClaim) // before claimed/retire/admit; failed retirement retains it
                val accepted = usbSwitchPolicy.accept(generation,
                    resolve = {
                        manager.pendingDevice(generation)?.let {
                            if (it.vendorId == 0x05ac) com.openautolink.app.carplay.UsbHotPlugSwitchPolicy.Protocol.CARPLAY
                            else com.openautolink.app.carplay.UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO
                        }
                    },
                    eligible = { protocol ->
                        val saved = preferences.effectiveCarPlaySettings.first()
                        // Revalidate before claiming/retiring projection, including an old Switch dialog.
                        val blocked = manager.pendingDevice(generation)?.let(::presentBlockedUsbCarPlayFailure) == true
                        if (!blocked && automatic && usbOverrideGeneration == null && manager.pendingDevice(generation) != null && usbSwitchPolicy.shouldPrompt(
                                sessionManager.sessionState.value != SessionState.IDLE,
                                synchronized(connectLock) { connectInFlight }, _carPlayState.value.selected)) {
                            promptInstead = true
                            _usbHotPlugPrompt.value = com.openautolink.app.carplay.UsbHotPlugPrompt(generation,
                                if (protocol == com.openautolink.app.carplay.UsbHotPlugSwitchPolicy.Protocol.CARPLAY) "CarPlay" else "Android Auto")
                        }
                        !com.openautolink.app.input.IgnitionMonitor.isOffOrLocked() &&
                            !promptInstead && !blocked &&
                            (protocol == com.openautolink.app.carplay.UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO ||
                                saved.enabled && saved.videoError() == null)
                    },
                    claimed = {
                        // Revoke queued wireless starts BEFORE awaited teardown; never write a preference.
                        usbHandoff.claim(generation)
                        carPlayCoordinator.dismissFailure()
                        _carPlayStartupGuidance.value = null
                        projectionLaunchPolicy.suppress()
                        usbPolicyEpoch.incrementAndGet()
                        usbAaStartedGeneration = null
                        _usbHotPlugPrompt.value = null
                    },
                    retire = {
                        retireUsbProjection()
                        // The new pending claim stays live through dispatch; retire only prior claims.
                        usbCaptureClaims.filter { it !== captureClaim }.forEach { it.release() }
                        usbCaptureClaims.retainAll { it === captureClaim }
                        if (previousGeneration != null) manager.failedAdmission(previousGeneration)
                        synchronized(connectLock) { hasConnected = false; connectInFlight = false }
                    },
                    admit = {
                        sessionManager.externalUsbTransport = true
                        manager.accept(generation, explicitUserSelection = !automatic).also { if (it) usbHandoff.admitted() }
                    },
                    cleared = {
                        manager.failedAdmission(generation)
                        manager.stay(generation)
                        clearUsbOverrideState()
                    },
                    restore = { restoreNormalProjection() },
                    replaceCurrent = !automatic,
                )
                if (!accepted && usbOverrideGeneration == previousGeneration) {
                    usbCaptureClaims.remove(captureClaim); captureClaim.release()
                }
                if (!accepted && !promptInstead && usbOverrideGeneration == null) stayOnCurrentProjection(generation)
            } catch (failure: Exception) {
                // Failed retirement retains the transient generation and blocks every successor.
                usbHandoff.failed()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
            } finally { usbDispatchMutex.unlock() }
        }
    }

    private fun clearUsbOverrideState() {
        releaseUsbCaptureClaims()
        usbHandoff.release()
        _carPlayStartupGuidance.value = null
        usbAaStartedGeneration = null
        sessionManager.externalUsbTransport = false
        usbPolicyEpoch.incrementAndGet()
        synchronized(connectLock) { hasConnected = false; connectInFlight = false }
    }

    private suspend fun finishUsbOverride(dispatchEpoch: Long, generation: Long) {
        usbDispatchMutex.lock()
        try {
            if (dispatchEpoch != usbDispatchEpoch.get()) return
            usbSwitchPolicy.finish(generation,
                retire = { retireUsbProjection(preserveCarPlayFailure = true) },
                cleared = { clearUsbOverrideState() },
                restore = { restoreNormalProjection() })
        } catch (failure: Exception) {
            usbHandoff.failed()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        } finally { usbDispatchMutex.unlock() }
    }

    private suspend fun retireUsbProjection(preserveCarPlayFailure: Boolean = false,
        preserveClusterHostForReconnect: Boolean = false) = usbHandoff.retire(
        releaseCarPlay = { if (_carPlayState.value.selected) releaseCarPlayUsbOwner(preserveCarPlayFailure, preserveClusterHostForReconnect) },
        stopAndroidAuto = { if (preserveClusterHostForReconnect) sessionManager.stopForExplicitReconnect() else sessionManager.stop() },
    )

    fun retryUsbSwitch() {
        projectionLaunchPolicy.suppress()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { restartUnifiedUsb() }
    }
    fun dismissUsbSwitchFailure() = usbHandoff.dismissFailure()

    private fun presentBlockedUsbCarPlayFailure(device: android.hardware.usb.UsbDevice): Boolean {
        if (device.vendorId != 0x05ac) return false
        val failure = com.openautolink.app.carplay.OalUsbCarPlayRuntime
            .blockedAttachmentFailure(getApplication(), device) ?: return false
        carPlayCoordinator.presentFailure(failure)
        return true
    }

    /** Visibility only; exact picker receipts and runtime permission consent still gate admission. */
    fun isUsbCarPlayRecoveryBlocked(candidate: com.openautolink.app.transport.usb.UsbDeviceCandidate): Boolean {
        if (candidate.vendorId != 0x05ac) return false
        val usb = getApplication<Application>().getSystemService(android.hardware.usb.UsbManager::class.java) ?: return false
        val device = usb.deviceList[candidate.deviceName] ?: return false
        if (device.deviceId != candidate.deviceId || device.vendorId != candidate.vendorId ||
            device.productId != candidate.productId) return false
        return com.openautolink.app.carplay.OalUsbCarPlayRuntime.blockedAttachmentFailure(getApplication(), device) != null
    }

    private suspend fun restoreNormalProjection() {
        if (!preferences.projectionConnectionPolicy.wirelessAllowed) return
        if (com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused()) return
        _carPlayStartupGuidance.value = null
        val saved = preferences.effectiveCarPlaySettings.first()
        if (com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()) return
        // Wired CarPlay enablement never suppresses normal Android Auto WPP / GM phone priority.
        if (usbSwitchPolicy.wirelessCarPlay(saved.enabled, saved.connectionMode)) {
            if (prepareCarPlayStart(saved)) carPlayCoordinator.connectWithContext(true, ::createCarPlayRuntime)
        } else if (preferences.effectiveDirectTransport.first() != AppPreferences.DIRECT_TRANSPORT_USB) connect()
    }

    /** Retry is explicit; preference collection and recomposition never spin a retry loop. */
    fun connectCarPlay() = reconnectCarPlay()

    @Suppress("MissingPermission")
    private suspend fun carPlayStartupIssue(saved: com.openautolink.app.data.CarPlaySettings): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val required = listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
            if (required.any { app.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED })
                return@withContext "Allow Bluetooth access to start CarPlay."
            try {
                val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                if (adapter?.isEnabled != true) return@withContext "Turn on Bluetooth, then retry CarPlay."
                val phones = adapter.bondedDevices.map { com.openautolink.app.data.CarPlayPairedPhone(it.address, "") }
                // Runtime installs/validates bundled identity and validates persisted trust. No probe starts it here.
                val action = com.openautolink.app.data.CarPlaySettingsPolicy.requiredAction(saved, phones,
                    identityReady = true,
                    passwordSaved = com.openautolink.carplaylab.SavedWifiPassword(app).load().isNotEmpty())
                if (action.startsWith("Ready for") || action.startsWith("AP connection requires")) null else action
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Check CarPlay connection setup and Bluetooth access, then retry."
            }
        }
    private fun createCarPlayRuntime(attemptContext: com.openautolink.app.carplay.CarPlayAttemptContext, report: (String) -> Unit): com.openautolink.app.carplay.CarPlayRuntime =
        createProjectionCarPlayRuntime(attemptContext, report, null)

    private fun createProjectionCarPlayRuntime(
        attemptContext: com.openautolink.app.carplay.CarPlayAttemptContext,
        report: (String) -> Unit, usbDevice: android.hardware.usb.UsbDevice?,
        beginTransition: () -> Boolean = { false },
        acceptReplacement: (android.hardware.usb.UsbDevice) -> Boolean = { false },
        retired: () -> Unit = {},
        initialPermissionOwner: () -> Boolean = { true },
        consumeExplicitPermissionRetry: () -> Boolean = { false },
    ): com.openautolink.app.carplay.CarPlayRuntime =
        kotlinx.coroutines.runBlocking {
            // A late factory built from an older snapshot cannot dismiss a newer successful Save.
            val wirelessFactoryTicket = preferences.projectionConnectionPolicy.wirelessTicket()
            val noticeRevision = synchronized(settingsSaveNoticeLock) { settingsSaveNoticeRevision }
            val saved = preferences.effectiveCarPlaySettings.first()
            val policyInventory = com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.get(getApplication())
            val gm = policyInventory.snapshots.value.displays.singleOrNull {
                it.route == com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED
            }
            val secondaryPreferences = if (gm != null) preferences.migrateSecondaryClusterPreference(gm.id)
                else preferences.getSecondaryDisplayPreferences()
            check(saved.enabled) { "CarPlay disabled before bootstrap" }
            check(!com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()) { "Ignition off before bootstrap" }
            val surface = kotlinx.coroutines.withTimeout(5_000) {
                carPlaySurface.first { it?.isValid == true }!!
            }
            val savedDisplayMode = preferences.displayMode.first()
            val viewport = if (saved.autoScale || saved.autoResolution || saved.windowDetail || saved.hasSafeCalibration) {
                // A display-mode switch can reuse the Surface while its measured bounds are pending.
                // Do not use a fullscreen receipt for a system-UI-visible receiver (or vice versa).
                kotlinx.coroutines.withTimeoutOrNull(1_500) {
                    carPlayViewport.first { it?.displayMode == savedDisplayMode }?.input
                }
            } else null
            val display = saved.qualifiedDisplay(viewport)
            val video = checkNotNull(display.qualifiedVideoSelection)
            report("CarPlay video · ${video.width} × ${video.height} · ${video.fps} maximum FPS · " +
                "${video.selectionReason} · H.264${if (video.hevcOffered) " + qualified HEVC" else ""}")
            val size = display.physicalSize
            val scaleBasis = when (size.basis) {
                com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Basis.MANUAL -> "manual"
                com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Basis.HARDWARE_ESTIMATE -> "hardware estimate"
                com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED -> "calibration needed"
            }
            DiagnosticLog.i("carplay", "CarPlay scale: $scaleBasis widthMm=${size.widthMm} heightMm=${size.heightMm}; next-session snapshot")
            report("CarPlay scale · $scaleBasis · ${size.widthMm} × ${size.heightMm} mm")
            val preparedOutput = com.openautolink.app.secondary.SecondaryOutputRuntime.get(getApplication())
                .prepareCarPlay(System.nanoTime().coerceAtLeast(0), secondaryPreferences)
            val clusterSelection = preparedOutput.legacySelection // effective GM opt-outs from the same frozen snapshot
            val clusterOutput = com.openautolink.app.carplay.OalCarPlayClusterOutput.fromPrepared(preparedOutput)
            try {
                val nextRuntime = if (usbDevice != null) {
                    check(usbOverrideGeneration != null) { "Transient USB selection retired" }
                    val runtime = com.openautolink.app.carplay.OalUsbCarPlayRuntime(getApplication(), usbDevice, display, surface, report,
                        attemptContext = attemptContext,
                        clusterOutput = clusterOutput, clusterSelection = clusterSelection,
                        onDisplayQualified = { _carPlayDisplay.value = it },
                        onReenumerationStarted = beginTransition, onReplacementAccepted = acceptReplacement,
                        initialPermissionOwner = initialPermissionOwner,
                        consumeExplicitPermissionRetry = consumeExplicitPermissionRetry,
                        restrictionPolicy = com.openautolink.app.carplay.CarPlayRestrictionPolicy.from(saved),
                        receiverAddress = saved.receiverAddress)
                    // The captured valid surface qualified this session's geometry only. The
                    // coordinator installs the latest presentation after factory completion.
                    runtime.detachSurface(surface)
                    usbCarPlayRuntime = runtime
                    object : com.openautolink.app.carplay.CarPlayRuntime by runtime {
                        override fun close() { runtime.close(); retired() }
                    }
                } else {
                    val config = com.openautolink.app.data.CarPlaySettingsPolicy.runtimeConfig(saved, display)
                    check(preferences.projectionConnectionPolicy.admits(wirelessFactoryTicket) && saved.connectionMode == "wireless" && usbOverrideGeneration == null) { "Wireless CarPlay not opted in" }
                    com.openautolink.app.carplay.OalCarPlayRuntime(getApplication(), config, surface, report,
                        wirelessStartAdmission = { preferences.projectionConnectionPolicy.admits(wirelessFactoryTicket) },
                        attemptContext = attemptContext,
                        clusterOutput = clusterOutput, clusterSelection = clusterSelection,
                        onDisplayQualified = { _carPlayDisplay.value = it })
                        .also { runtime -> runtime.detachSurface(surface); carPlayRuntime = runtime }
                }
                nextRuntime.also { clearSettingsSaveMessage(noticeRevision) }
            } catch (failure: Exception) {
                clusterOutput.revoke(); clusterOutput.retire()
                throw failure
            }
        }
    fun reconnectCarPlay() {
        projectionLaunchPolicy.suppress()
        viewModelScope.launch {
            if (!reconcileWiredOnly()) return@launch
            val saved = preferences.effectiveCarPlaySettings.first()
            if (!saved.enabled) return@launch
            if (saved.connectionMode == "usb") {
                val usb = getApplication<Application>().getSystemService(android.hardware.usb.UsbManager::class.java)
                val device = usb?.deviceList?.values?.singleOrNull { it.vendorId == 0x05ac }
                if (device != null && presentBlockedUsbCarPlayFailure(device)) return@launch
                restartUnifiedUsb()
                return@launch
            }
            usbDispatchMutex.lock()
            try {
                if (usbOverrideGeneration != null || !prepareCarPlayStart(saved)) return@launch
                com.openautolink.app.secondary.SecondaryOutputRuntime.get(getApplication()).retainForExplicitReconnect()
                if (_carPlayState.value.selected) carPlayCoordinator.reconnectWithContext(true, ::createCarPlayRuntime)
                else carPlayCoordinator.connectForExplicitReconnect(true, ::createCarPlayRuntime)
            } finally { usbDispatchMutex.unlock() }
        }
    }

    private suspend fun prepareCarPlayStart(saved: com.openautolink.app.data.CarPlaySettings): Boolean {
        if (!preferences.projectionConnectionPolicy.wirelessAllowed) return false
        _carPlayAutomaticResolution.value = saved.usesWindowGeometry
        val issue = saved.videoError() ?: carPlayStartupIssue(saved)
        if (issue == null) _carPlayDisplay.value = saved.displayRequest()
        val off = com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()
        _carPlayStartupGuidance.value = issue ?: if (off) "CarPlay is paused while ignition is off. Retry when the car is ready." else null
        return issue == null && !off
    }

    private val _settingsSaveMessage = MutableStateFlow<String?>(null)
    val settingsSaveMessage = _settingsSaveMessage.asStateFlow()
    private val _settingsSaveNoticeExpanded = MutableStateFlow(true)
    val settingsSaveNoticeExpanded = _settingsSaveNoticeExpanded.asStateFlow()
    fun expandSettingsSaveNotice(expanded: Boolean) { _settingsSaveNoticeExpanded.value = expanded }
    private val settingsSaveNoticeLock = Any()
    private var settingsSaveNoticeRevision = 0L
    private var settingsSaveNoticeJob: Job? = null

    private fun clearSettingsSaveMessage(expectedRevision: Long? = null) {
        synchronized(settingsSaveNoticeLock) {
            if (expectedRevision != null && settingsSaveNoticeRevision != expectedRevision) return
            settingsSaveNoticeRevision++
            settingsSaveNoticeJob?.cancel()
            settingsSaveNoticeJob = null
            _settingsSaveMessage.value = null
        }
    }

    private fun showSettingsSaveMessage(message: String) {
        // Observe an owner-qualified receipt, not IDLE/STREAMING edges (StateFlow may conflate them).
        // Same-owner native retries still hold their old settings and must retain pending guidance.
        val epochs = sessionManager.settingsSessionEpoch
        synchronized(settingsSaveNoticeLock) {
            val revision = ++settingsSaveNoticeRevision
            val savedUnderEpoch = epochs.value
            settingsSaveNoticeJob?.cancel()
            _settingsSaveNoticeExpanded.value = true
            _settingsSaveMessage.value = message
            settingsSaveNoticeJob = viewModelScope.launch {
                epochs.first { it != savedUnderEpoch }
                clearSettingsSaveMessage(revision)
            }
        }
    }

    /** Explicit overlay AA Reconnect; keep its existing minimal transport/settings restart. */
    fun reconnectAndroidAutoWithLiveHost(reconnectAndroidAuto: () -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (preferences.wiredOnly.first()) {
                if (reconcileWiredOnly()) restartUnifiedUsb()
                return@launch
            }
            usbDispatchMutex.lock()
            try {
                val active = sessionManager.sessionState.value != SessionState.IDLE
                val wired = usbOverrideGeneration != null || sessionManager.externalUsbTransport ||
                    if (active) sessionManager.transportMode.value == AppPreferences.DIRECT_TRANSPORT_USB
                    else preferences.effectiveDirectTransport.first() == AppPreferences.DIRECT_TRANSPORT_USB
                if (!wired) com.openautolink.app.secondary.SecondaryOutputRuntime.get(getApplication()).retainForExplicitReconnect()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { reconnectAndroidAuto() }
            } finally { usbDispatchMutex.unlock() }
        }
    }

    private var reconciledConnectionRevision = -1L
    private var normalRearmRevision = -1L
    @Volatile private var wiredOnlyWasApplied = false
    /** Policy retirement precedes USB/call Save deferrals. Failed close keeps admission closed. */
    private suspend fun reconcileWiredOnly(): Boolean {
        val policy = preferences.projectionConnectionPolicy.state.first { it.loaded }
        if (!policy.retirementRequired) return true
        usbDispatchMutex.lock()
        try {
            if (!preferences.projectionConnectionPolicy.state.value.retirementRequired) return true
            usbPolicyEpoch.incrementAndGet()
            phoneDiscovery.stop()
            sessionManager.stopWirelessPresence()
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl.retireForWiredOnly()
            val wirelessAa = sessionManager.transportMode.value != AppPreferences.DIRECT_TRANSPORT_USB &&
                (sessionManager.sessionState.value != SessionState.IDLE || sessionManager.hasCurrentWppOwner())
            val wirelessCp = _carPlayState.value.selected && usbCarPlayRuntime == null
            if ((wirelessAa || wirelessCp || sessionManager.hasPendingWirelessRetirement()) &&
                (sessionManager.callState?.value?.let { it != com.openautolink.app.audio.CallState.IDLE } == true ||
                 com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(getApplication()).hasAdmittedCall)) {
                showSettingsSaveMessage("Saved. Wireless starts are blocked. Reconnect after the call to finish wired-only mode.")
                return false
            }
            if (wirelessCp) releaseCarPlayUsbOwner()
            if (wirelessAa) {
                sessionManager.stop()
                synchronized(connectLock) { hasConnected = false; connectInFlight = false }
            }
            // IDLE and a cleared WPP token precede native stop acknowledgement.
            // Retry that exact retained wireless owner without stopping an admitted USB owner.
            sessionManager.retryPendingWirelessRetirement()
            if (usbOverrideGeneration == null && !sessionManager.externalUsbTransport && usbCarPlayRuntime == null) {
                synchronized(connectLock) { hasConnected = false; connectInFlight = false }
            }
            wiredOnlyWasApplied = true
            reconciledConnectionRevision = policy.revision
            preferences.projectionConnectionPolicy.retired(policy.revision)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            showSettingsSaveMessage("Saved. Wireless starts are blocked. Retry Save to finish wireless retirement.")
            return false
        } finally { usbDispatchMutex.unlock() }
        startUnifiedUsb()
        return true
    }

    private suspend fun rearmNormalConnectionOnce() {
        val policy = preferences.projectionConnectionPolicy.state.value
        if (!policy.wirelessAllowed || !wiredOnlyWasApplied) return
        usbDispatchMutex.lock()
        try {
            if (normalRearmRevision == policy.revision) return
            normalRearmRevision = policy.revision
            if (usbOverrideGeneration == null && !sessionManager.externalUsbTransport && usbCarPlayRuntime == null) {
                restoreNormalProjection()
            }
        } finally { usbDispatchMutex.unlock() }
    }

    /** The shared real Save callback uses this only when this transaction changed the override. */
    fun applySavedWiredOnlyPolicy() {
        projectionLaunchPolicy.suppress()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!reconcileWiredOnly()) return@launch
            rearmNormalConnectionOnce()
            showSettingsSaveMessage(if (preferences.projectionConnectionPolicy.state.value.wiredOnly)
                "Saved. Wired-only projection is USB-ready. Reconnect USB if already attached."
                else "Saved. Normal connections restored; an active USB connection is unchanged.")
        }
    }

    /** Called only AFTER awaited commit success. Read DataStore, never a captured selected/enabled UI seed. */
    fun reconnectAfterSettingsSave(reconnectAndroidAuto: () -> Unit) {
        projectionLaunchPolicy.suppress()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!reconcileWiredOnly()) return@launch
            usbDispatchMutex.lock()
            try {
                val saved = preferences.effectiveCarPlaySettings.first()
                // The cable can temporarily own either protocol while normal modes remain wireless.
                // Conversely, a newly saved USB preference does not turn an active wireless session wired.
                val aaActive = !_carPlayState.value.selected && sessionManager.sessionState.value.let {
                    it == SessionState.CONNECTING || it == SessionState.CONNECTED || it == SessionState.STREAMING
                }
                val actualUsb = usbOverrideGeneration != null || sessionManager.externalUsbTransport || usbCarPlayRuntime != null ||
                    if (_carPlayState.value.selected) false
                    else if (aaActive) sessionManager.transportMode.value == AppPreferences.DIRECT_TRANSPORT_USB
                    else if (saved.enabled) saved.connectionMode == "usb"
                    else preferences.effectiveDirectTransport.first() == AppPreferences.DIRECT_TRANSPORT_USB
                if (actualUsb) {
                    showSettingsSaveMessage("Saved. Unplug and reconnect USB to apply.")
                    return@launch // Persisted intent is consumed at the next physical connection.
                }
                if (sessionManager.callState?.value?.let { it != com.openautolink.app.audio.CallState.IDLE } == true ||
                    com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(getApplication()).hasAdmittedCall) {
                    showSettingsSaveMessage("Saved. Reconnect after the call to apply.")
                    return@launch
                }
                clearSettingsSaveMessage()
                val retainedClusterHost = com.openautolink.app.secondary.SecondaryOutputRuntime.get(getApplication()).retainForExplicitReconnect()
                usbPolicyEpoch.incrementAndGet()
                // Ordinary live AA Save must reach SessionManager.reconnect with its session/audio
                // intact for graceful ByeBye and the minimal restart. Only a protocol switch (or
                // no live AA owner) needs the full USB/projection retirement path first.
                if (!aaActive || usbSwitchPolicy.wirelessCarPlay(saved.enabled, saved.connectionMode)) {
                    retireUsbProjection(preserveClusterHostForReconnect = retainedClusterHost)
                    unifiedUsbManager?.failedAdmission()
                    usbHandoff.release()
                    usbSwitchPolicy.clearRetired()
                    releaseUsbCaptureClaims()
                    sessionManager.externalUsbTransport = false
                    synchronized(connectLock) { hasConnected = false; connectInFlight = false }
                }
                if (usbSwitchPolicy.wirelessCarPlay(saved.enabled, saved.connectionMode)) {
                    if (prepareCarPlayStart(saved)) carPlayCoordinator.connectForExplicitReconnect(true, ::createCarPlayRuntime)
                } else {
                    _carPlayStartupGuidance.value = null
                    if (aaActive || preferences.effectiveDirectTransport.first() != AppPreferences.DIRECT_TRANSPORT_USB) reconnectAndroidAuto()
                }
                unifiedUsbManager?.retryCandidates()
            } finally { usbDispatchMutex.unlock() }
            startUnifiedUsb()
        }
    }
    fun stopCarPlay() { carPlayCoordinator.stop() }
    fun dismissCarPlayFailure() { carPlayCoordinator.dismissFailure() }
    fun returnToAndroidAuto() {
        // A wired failure already restored AA after exact retirement. Do not reconnect it again.
        if (!_carPlayState.value.selected) { dismissCarPlayFailure(); return }
        carPlayCoordinator.returnToAndroidAuto {
            viewModelScope.launch {
                carPlayRuntime = null
                synchronized(connectLock) { hasConnected = false; connectInFlight = false }
                connect()
            }
        }
    }
    // One atomic mode-qualified receipt; never pair a new mode with an old viewport input.
    private data class CarPlayViewportReceipt(
        val displayMode: String,
        val input: com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input,
    )
    private val carPlayViewport = kotlinx.coroutines.flow.MutableStateFlow<CarPlayViewportReceipt?>(null)
    private val _carPlayAutomaticResolution = MutableStateFlow(false)
    val carPlayAutomaticResolution = _carPlayAutomaticResolution.asStateFlow()

    /** Parent SurfaceChanged callback supplies the measured viewport and its owning Display.
     * Resize only updates the NEXT receiver snapshot; no active protocol/decoder renegotiation.
     */
    @Suppress("DEPRECATION")
    fun onCarPlayViewportChanged(width: Int, height: Int, display: android.view.Display?, displayMode: String? = null) {
        if (displayMode == null || width <= 0 || height <= 0) {
            carPlayViewport.value = null
            return
        }
        val metrics = android.util.DisplayMetrics()
        val detected = runCatching {
            check(display != null && display.isValid)
            display.getRealMetrics(metrics)
        }.isSuccess
        carPlayViewport.value = CarPlayViewportReceipt(displayMode,
            com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
                width, height, if (detected) metrics.widthPixels else 0,
                if (detected) metrics.heightPixels else 0,
                if (detected) metrics.xdpi.toDouble() else Double.NaN,
                if (detected) metrics.ydpi.toDouble() else Double.NaN,
                if (detected) metrics.densityDpi else 0,
            ),
        )
    }
    fun onCarPlaySurfaceAvailable(surface: Surface) {
        carPlaySurface.value = surface
        carPlayCoordinator.attachSurface(surface)
    }
    fun onCarPlaySurfaceDestroyed(surface: Surface) {
        if (carPlaySurface.value !== surface) return
        carPlaySurface.value = null
        carPlayViewport.value = null
        carPlayCoordinator.detachSurface(surface)
    }
    fun onCarPlayTouch(event: MotionEvent, width: Int, height: Int) {
        val pointers = (0 until event.pointerCount).map { index ->
            com.openautolink.app.carplay.CarPlayPointer(event.getPointerId(index), event.getX(index), event.getY(index))
        }
        carPlayRuntime?.touch(event.actionMasked, event.actionIndex, pointers, width, height)
        usbCarPlayRuntime?.touch(event.actionMasked, event.actionIndex, pointers, width, height)
    }
    @Volatile private var selectedNetworkInterfaceName: String = ""
    @Volatile private var lastTransportNetworkEventAt: Long = 0L
    private val trackedTransportNetworks = mutableSetOf<Long>()

    /** Suppress config_echo DataStore writes while Settings is open. */
    fun setSettingsOpen(open: Boolean) {
    }

    /**
     * Push the OS-reported safe-area insets (system bars ∪ display cutouts)
     * down to SessionManager so the next start()/reconnect() can use them
     * as fallback for AA `content_insets` when the user hasn't manually
     * overridden them in settings.
     */
    fun setSystemInsets(top: Int, bottom: Int, left: Int, right: Int) {
        sessionManager.setSystemInsets(top, bottom, left, right)
    }

    /**
     * Push the live render-rect dims (Compose Box content area after
     * displayMode padding) and the panel's reported DPI down to
     * SessionManager so auto-DPI math can pick a density that produces
     * native-AAOS-equivalent UI sizes regardless of displayMode.
     */
    fun setRenderRect(widthPx: Int, heightPx: Int, panelDpi: Int, displayMode: String? = null) {
        sessionManager.setRenderRect(widthPx, heightPx, panelDpi, displayMode)
    }


    private val steeringWheelController = SteeringWheelController(
        sendMessage = { buttonMessage ->
            viewModelScope.launch {
                sessionManager.sendControlMessage(buttonMessage)
            }
        },
        audioManager = audioManager
    )

    private val _phoneName = MutableStateFlow<String?>(null)
    private val _videoStats = MutableStateFlow(VideoStats())
    private val _audioStats = MutableStateFlow(AudioStats())
    private var videoStatsJob: Job? = null
    private var audioStatsJob: Job? = null
    private val _showStats = MutableStateFlow(false)

    private val _fileLoggingActive = MutableStateFlow(false)
    private var fileLogWriter: FileLogWriter? = null
    private var logcatCapture: LogcatCapture? = null

    // Upload feedback belongs to the ViewModel, not the disposable menu.
    private val logUploadAction = LogUploadAction()

    // Pending surface — stored when surfaceCreated fires before decoder exists.
    // Attached to decoder on session start or when decoder becomes available.
    /** Rate-limit for the "no codec dimensions, dropping touch" warning. */
    private var lastNoTouchSizeWarnAt = 0L
    /** Rate-limit the positive touch-space mapping marker in uploaded logs. */
    private var lastTouchMappingLogAt = 0L

    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var surfaceDebounceJob: kotlinx.coroutines.Job? = null

    private val transportNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleTransportNetworkUpdate(network, "available")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleTransportNetworkUpdate(network, "capabilities")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            handleTransportNetworkUpdate(network, "link")
        }

        override fun onLost(network: Network) {
            val handle = network.networkHandle
            val wasTracked = synchronized(trackedTransportNetworks) {
                trackedTransportNetworks.remove(handle)
            }
            if (!wasTracked) return
            requestTransportReconnect("lost")
        }
    }

    val uiState: StateFlow<ProjectionUiState> = combine(
        sessionManager.sessionState,
        sessionManager.statusMessage,
        _phoneName,
        _videoStats,
        _audioStats,
        _showStats,
        sessionManager.currentManeuver,
        sessionManager.phoneBatteryLevel,
        sessionManager.phoneBatteryCritical,
        sessionManager.voiceSessionActive,
        preferences.displayMode,
        preferences.safeAreaTop,
        preferences.safeAreaBottom,
        preferences.safeAreaLeft,
        preferences.safeAreaRight,
        sessionManager.phoneSignalStrength,
        preferences.videoScalingMode,
        sessionManager.wifiFrequencyMhz,
        preferences.aaDpi,
        sessionManager.effectiveDpi,
        preferences.aaPixelAspect,
        preferences.aaWidthMargin,
        preferences.aaHeightMargin,
        preferences.aaAutoMargins,
        _fileLoggingActive,
        preferences.fileLoggingEnabled,
        preferences.logUploadEnabled,
        logUploadAction.feedback,
        preferences.simulateIgnitionButton,
        sessionManager.reconnectAttempt,
    ) { values ->
        ProjectionUiState(
            sessionState = values[0] as SessionState,
            statusMessage = values[1] as String,
            phoneName = values[2] as? String,
            videoStats = values[3] as VideoStats,
            audioStats = values[4] as AudioStats,
            showStats = values[5] as Boolean,
            maneuver = values[6] as? ManeuverState,
            phoneBatteryLevel = values[7] as? Int,
            phoneBatteryCritical = values[8] as Boolean,
            voiceSessionActive = values[9] as Boolean,
            displayMode = values[10] as String,
            safeAreaTop = values[11] as Int,
            safeAreaBottom = values[12] as Int,
            safeAreaLeft = values[13] as Int,
            safeAreaRight = values[14] as Int,
            phoneSignalStrength = values[15] as? Int,
            videoScalingMode = values[16] as String,
            wifiFrequencyMhz = values[17] as Int,
            aaDpi = values[18] as Int,
            effectiveDpi = values[19] as Int,
            aaPixelAspect = values[20] as Int,
            aaWidthMargin = values[21] as Int,
            aaHeightMargin = values[22] as Int,
            aaAutoMargins = values[23] as Boolean,
            fileLoggingActive = values[24] as Boolean,
            fileLoggingEnabled = values[25] as Boolean,
            uploadEnabled = values[26] as Boolean,
            uploadState = (values[27] as LogUploadFeedback).state,
            uploadStatusMessage = (values[27] as LogUploadFeedback).message,
            simulateIgnitionButton = values[28] as Boolean,
            reconnectAttempt = values[29] as Int,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProjectionUiState()
    )

    // Guards file-logging start/stop. Declared HERE (before the init blocks) and
    // eagerly initialized — NOT `by lazy` — because the always-log collector in
    // init { } can touch it synchronously during construction (DataStore emits
    // its cached value immediately), which raced the lazy delegate's own
    // initialization and NPE'd in the ViewModel constructor on the main thread
    // (launch-time crash, issue #44). Eager + early declaration removes the race.
    private val fileLogToggleLock = Any()

    init {
        registerTransportNetworkCallback()
        viewModelScope.launch {
            com.openautolink.app.input.IgnitionMonitor.ignitionState.collect {
                if (com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()) carPlayCoordinator.stop()
            }
        }
        viewModelScope.launch {
            preferences.effectiveCarPlaySettings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled && _carPlayState.value.selected && usbOverrideGeneration == null && usbCarPlayRuntime == null &&
                    sessionManager.callState?.value?.let { it != com.openautolink.app.audio.CallState.IDLE } != true &&
                    !com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(getApplication()).hasAdmittedCall) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        usbDispatchMutex.lock()
                        try {
                            releaseCarPlayUsbOwner()
                            if (usbOverrideGeneration == null) restoreNormalProjection()
                        } finally { usbDispatchMutex.unlock() }
                    }
                }
            }
        }

        // Key remaps belong to this process-scoped input controller, not to an
        // individual AA protocol session. Keep it bound to DataStore so an
        // assignment replaces the live map immediately; Save & Reconnect only
        // restarts the AA protocol session and does not recreate this ViewModel.
        viewModelScope.launch {
            preferences.keyRemap.distinctUntilChanged()
                .collect { serialized ->
                    val map = KeyRemapParser.parse(serialized)
                    steeringWheelController.customKeyMap = map
                    OalLog.i(
                        "input",
                        "Applied custom key map update: count=${map.size} mappings=$map",
                    )
                }
        }

        // Attach the surface as soon as a decoder exists, in addition to the
        // state-change path below. Either one alone can miss: the state observer
        // needs a transition it may not see, and onSurfaceAvailable silently does
        // nothing when the decoder has not been created yet.
        // Hop to the ViewModel scope: the decoder is created on whichever thread
        // starts the session, and surface attach must not run there.
        sessionManager.onDecoderCreated = {
            viewModelScope.launch {
                attachPendingSurface()
                bindCurrentStatsCollectors()
            }
        }

        // Collect video and audio stats when streaming
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                val usbGeneration = usbAaStartedGeneration
                if (state == SessionState.IDLE && usbGeneration != null) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        usbDispatchMutex.lock()
                        try {
                            if (usbOverrideGeneration == usbGeneration && usbAaStartedGeneration == usbGeneration) {
                                sessionManager.stop()
                                unifiedUsbManager?.failedAdmission(usbGeneration)
                            }
                        } finally { usbDispatchMutex.unlock() }
                    }
                }
                // Attach pending surface when decoder becomes available
                if (state == SessionState.CONNECTED ||
                    state == SessionState.STREAMING) {
                    attachPendingSurface()
                }
                if (state == SessionState.STREAMING) {
                    bindCurrentStatsCollectors()
                }
            }
        }
    }

    /** Bind the overlay to the current decoder/player, never a retired one. */
    private fun bindCurrentStatsCollectors() {
        videoStatsJob?.cancel()
        audioStatsJob?.cancel()
        _videoStats.value = VideoStats()
        _audioStats.value = AudioStats()
        sessionManager.videoStats?.let { statsFlow ->
            videoStatsJob = viewModelScope.launch {
                statsFlow.collect { stats -> _videoStats.value = stats }
            }
        }
        sessionManager.audioStats?.let { statsFlow ->
            audioStatsJob = viewModelScope.launch {
                statsFlow.collect { stats -> _audioStats.value = stats }
            }
        }
        DiagnosticLog.i(
            "video",
            "Stats collectors rebound: video=${sessionManager.videoStats != null} " +
                "audio=${sessionManager.audioStats != null}",
        )
    }

    @Volatile private var hasConnected = false
    /**
     * Set while a [connect] coroutine is between claiming the slot and
     * handing off to [SessionManager.start]. Prevents the parallel-connect
     * storm we saw in early logs (21 simultaneous connect() invocations
     * each running the full resolve pipeline).
     */
    @Volatile private var connectInFlight = false
    /** WPP idle companion-probe cadence (UDP broadcast, then /24 sweep if empty). */
    private val IDLE_SWEEP_INTERVAL_MS = 15_000L
    /**
     * Minimum wake gap that should force an auto-reconnect re-arm. Short
     * pause/resume blips (sub-second) shouldn't trigger; anything that looks
     * like the head unit actually suspended should.
     */
    private val WAKE_AUTO_RECONNECT_MIN_GAP_MS = 3_000L

    /**
     * Cooldown held after a successful [doConnect] before [connectInFlight]
     * is released. sessionState transitions from IDLE → CONNECTING are not
     * always synchronous with start(); without this guard, the auto-reconnect
     * collector can fire a second [connect] mid-handshake and abort the
     * in-flight aasdk session with `AASDK Error 30` (OPERATION_ABORTED).
     */
    private val CONNECT_SETTLE_MS = 2_000L
    private val connectLock = Any()

    fun connect() {
        connect(wppRearmSource = null)
    }

    private fun connectForWppRearm(source: String) {
        connect(wppRearmSource = source)
    }

    private fun connect(wppRearmSource: String?) {
        if (com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused()) return
        if (usbOverrideGeneration != null) return
        if (preferences.projectionConnectionPolicy.state.value.wiredOnly) {
            viewModelScope.launch { startUnifiedUsb() }; return
        }
        val policyEpoch = usbPolicyEpoch.get()
        val connectionRevision = preferences.projectionConnectionPolicy.state.value.revision
        if (com.openautolink.app.carplay.CarPlayOwnership.process.blocksAndroidAuto) return
        fun logWppRearmRejected(reason: String) {
            wppRearmSource?.let { source ->
                OalLog.i(TAG, "WPP rearm rejected: source=$source reason=$reason")
            }
        }

        // Suppress auto-connect when ignition is known to be OFF/LOCK.
        // AAOS dispatches a "ghost wake" (onCreate → onPause → onStop)
        // ~2 minutes after ignition off; without this gate we burn the
        // full 45s "no IPv4 interface — awaiting WiFi" timeout into a
        // dead AP. Null state == unknown == don't block (covers genuine
        // cold starts before the Car API has reported a value).
        if (com.openautolink.app.input.IgnitionMonitor.isOffOrLocked()) {
            OalLog.i(
                TAG,
                "Auto-connect suppressed — ignition state = ${com.openautolink.app.input.IgnitionMonitor.ignitionState.value} " +
                    "(off ${com.openautolink.app.input.IgnitionMonitor.msSinceIgnitionOff()}ms ago)",
            )
            logWppRearmRejected("ignition-off")
            return
        }
        // Acquire the in-flight slot synchronously before suspending so
        // concurrent connect() callers don't all race past the gate.
        synchronized(connectLock) {
            if (hasConnected && sessionManager.sessionState.value != SessionState.IDLE) {
                sessionManager.ensureClusterAlive()
                logWppRearmRejected("session-not-idle")
                return
            }
            if (connectInFlight) {
                OalLog.d(TAG, "connect() ignored — another connect coroutine is already in flight")
                logWppRearmRejected("connect-in-flight")
                return
            }
            connectInFlight = true
            hasConnected = true
        }
        // IO: this block reaches SessionManager.start(), which does a
        // runBlocking DataStore read and brings up the whole session. On
        // Dispatchers.Main that is a UI-thread stall waiting on disk.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var settle = false
            try {
                val transport = preferences.effectiveDirectTransport.first()
                if (wppRearmSource != null &&
                    transport != AppPreferences.DIRECT_TRANSPORT_WPP
                ) {
                    OalLog.i(TAG, "WPP rearm rejected: source=$wppRearmSource " +
                        "reason=transport-changed current=$transport")
                    return@launch
                }
                if (transport == AppPreferences.DIRECT_TRANSPORT_USB) {
                    startUnifiedUsb()
                    return@launch
                }
                doConnect(wppRearmSource = wppRearmSource, policyEpoch = policyEpoch, connectionRevision = connectionRevision)
                settle = true
            } catch (e: Exception) {
                wppRearmSource?.let { source ->
                    OalLog.e(TAG, "WPP rearm outcome: source=$source " +
                        "ownerInstalled=false error=${e.message}")
                }
                OalLog.e(TAG, "connect() failed: ${e.message}")
            } finally {
                // Hold the in-flight slot briefly so sessionState has
                // time to transition out of IDLE — otherwise a second
                // auto-reconnect edge (mDNS resolved + sweep result)
                // can race past the state guard mid-handshake and tear
                // down the in-flight session with AASDK Error 30.
                if (settle) kotlinx.coroutines.delay(CONNECT_SETTLE_MS)
                connectInFlight = false
            }
        }
    }

    /**
     * Inner connect — assumes the in-flight slot is already claimed. Reads all remaining settings and
     * hands off to SessionManager.start.
     */
    private suspend fun doConnect(
        wppRearmSource: String? = null,
        usbAdmission: Boolean = false,
        policyEpoch: Long = usbPolicyEpoch.get(),
        connectionRevision: Long = preferences.projectionConnectionPolicy.state.value.revision,
    ) {
            if (policyEpoch != usbPolicyEpoch.get() || (!usbAdmission && usbOverrideGeneration != null)) return
            val c = preferences.aaSessionConfig()
            val directTransport = if (usbAdmission) AppPreferences.DIRECT_TRANSPORT_USB else c.directTransport
            if (!usbAdmission && directTransport == AppPreferences.DIRECT_TRANSPORT_USB) {
                startUnifiedUsb(); return
            }
            val loadedPolicy = preferences.projectionConnectionPolicy.state.first { it.loaded }
            if (!usbAdmission && loadedPolicy.revision != (if (connectionRevision == 0L) 1L else connectionRevision)) return
            val wirelessTicket = preferences.projectionConnectionPolicy.wirelessTicket()
            if (directTransport == AppPreferences.DIRECT_TRANSPORT_WPP) {
                phoneDiscovery.setInterfaceConstraint(c.wppInterfaceName)
            } else {
                phoneDiscovery.setInterfaceConstraint(null)
            }
            wppRearmSource?.let {
                val rejection = WppWakeReconnectPolicy.preStartRejection(
                    wppSelectedNow = directTransport == AppPreferences.DIRECT_TRANSPORT_WPP,
                    ignitionOff = com.openautolink.app.input.IgnitionMonitor.isOffOrLocked(),
                    sessionIdle = sessionManager.sessionState.value == SessionState.IDLE,
                    currentWppOwnerPresent = sessionManager.hasCurrentWppOwner(),
                )
                if (rejection != null) {
                    OalLog.i(TAG, "WPP rearm rejected: source=$wppRearmSource reason=$rejection")
                    return
                }
            }
            OalLog.i(TAG, "Connect with aaDpi=${c.aaDpi} (auto=${c.aaAutoDpi}) aaRes=${c.aaResolution} codec=${c.codecPreference} autoNeg=${c.videoAutoNegotiate}")

            // Load default phone name for auto-connect
            val defaultPhone = preferences.defaultPhoneName.first()
            sessionManager.setDefaultPhoneName(defaultPhone)

            // Auto-DPI needs the renderer's measured size. Starting before
            // Compose has measured ships the user's MANUAL dpi to the phone
            // instead, and the projected UI comes up at the wrong scale until
            // something forces a rebuild — measured 2026-08-11, dpi=175 on the
            // first two sessions after launch and 131 on every one after.
            if (c.aaAutoDpi && !sessionManager.awaitRenderRect()) {
                OalLog.w(TAG, "Renderer never reported a size — starting with " +
                        "manual DPI; scaling may be wrong until reconnect")
            }

            sessionManager.start(
                admissionCheck = { policyEpoch == usbPolicyEpoch.get() &&
                    (usbAdmission || preferences.projectionConnectionPolicy.admits(wirelessTicket)) &&
                    (if (usbAdmission) usbOverrideGeneration != null && sessionManager.externalUsbTransport
                     else usbOverrideGeneration == null) },
                codecPreference = c.codecPreference,
                micSourcePreference = c.micSourcePreference,
                scalingMode = c.scalingMode,
                directTransport = directTransport,
                wppInterfaceName = c.wppInterfaceName,
                hotspotSsid = c.hotspotSsid,
                hotspotPassword = c.hotspotPassword,
                videoAutoNegotiate = c.videoAutoNegotiate,
                aaResolution = c.aaResolution,
                aaDpi = c.aaDpi,
                aaAutoDpi = c.aaAutoDpi,
                aaWidthMargin = c.aaWidthMargin,
                aaHeightMargin = c.aaHeightMargin,
                aaPixelAspect = c.aaPixelAspect,
                aaTargetLayoutWidthDp = c.aaTargetLayoutWidthDp,
                aaViewingDistanceMm = c.aaViewingDistanceMm,
                aaDecoderAdditionalDepth = c.aaDecoderAdditionalDepth,
                aaAutoMargins = c.aaAutoMargins,
                videoFps = c.videoFps,
                driveSide = c.driveSide,
                hideClock = c.hideClock,
                hideSignal = c.hideSignal,
                hideBattery = c.hideBattery,
                volumeOffsetMedia = c.volumeOffsetMedia,
                volumeOffsetNavigation = c.volumeOffsetNavigation,
                volumeOffsetAssistant = c.volumeOffsetAssistant,
                manualIpAddress = c.manualIpAddress,
                safeAreaTop = c.safeAreaTop,
                safeAreaBottom = c.safeAreaBottom,
                safeAreaLeft = c.safeAreaLeft,
                safeAreaRight = c.safeAreaRight,
                gpsForwarding = c.gpsForwarding,
                galVersion = c.galVersion,
                wppRearmSource = wppRearmSource,
                seedIdrThresholds = c.seedIdrThresholds,
            )
    }

    /**
     * Force reconnect — used by "Save & Connect" button in Settings and
     * other manual reconnect triggers.
     *
     * Stops cleanly and re-runs the full connect pipeline.
     */
    fun reconnect() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!reconcileWiredOnly()) return@launch
            if (usbOverrideGeneration != null || preferences.effectiveDirectTransport.first() == AppPreferences.DIRECT_TRANSPORT_USB) {
                restartUnifiedUsb()
                return@launch
            }
            OalLog.i(TAG, "reconnect(): tearing down current session")
            val retainedClusterHost = com.openautolink.app.secondary.SecondaryOutputRuntime.get(getApplication()).retainForExplicitReconnect()
            if (retainedClusterHost) sessionManager.stopForExplicitReconnect() else sessionManager.stop()
            hasConnected = false
            connect()
        }
    }

    @Volatile private var diagnosticProjectionStopped = false
    @Volatile var diagnosticProjectionStatus = "Stop projection before comparing"
        private set

    /** Explicit opt-in pause. Do not change saved protocol or Bluetooth state. */
    fun stopProjectionForMicrophoneDiagnostic() {
        com.shilapi.xcertplay.media.MicrophoneCaptureFence.pauseProjection()
        diagnosticProjectionStopped = false
        diagnosticProjectionStatus = "Stopping projection · awaiting exact retirement"
        projectionLaunchPolicy.suppress()
        usbPolicyEpoch.incrementAndGet()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            usbDispatchMutex.lock()
            try {
                com.openautolink.app.transport.bluetooth.AaWirelessBtControl.retireForMicrophoneDiagnostic()
                stopUnifiedUsb()
                releaseCarPlayUsbOwner()
                sessionManager.stop()
                synchronized(connectLock) { hasConnected = false; connectInFlight = false }
                diagnosticProjectionStopped = true
                diagnosticProjectionStatus = "Projection stopped · automatic starts paused until Return"
            } catch (_: Exception) {
                diagnosticProjectionStatus = "Projection retirement pending/failed · retry Stop; capture blocked"
            } finally { usbDispatchMutex.unlock() }
        }
    }
    fun returnFromMicrophoneDiagnostic() {
        if (!com.shilapi.xcertplay.media.MicrophoneCaptureFence.resumeProjection()) {
            diagnosticProjectionStatus = "Waiting for diagnostic close + join; restart required if close failed"
            return
        }
        diagnosticProjectionStopped = false
        diagnosticProjectionStatus = "Projection restored"
        viewModelScope.launch { startUnifiedUsb(); restoreNormalProjection() }
    }
    fun parkedMicrophoneDiagnosticEligible(): Boolean = diagnosticProjectionStopped &&
        com.shilapi.xcertplay.media.MicrophoneCaptureFence.projectionPaused() && microphoneDiagnosticEligible()

    /** Fast policy only; real claims are independently atomic in MicrophoneCaptureFence. */
    fun microphoneDiagnosticEligible(): Boolean =
        usbOverrideGeneration == null && !connectInFlight && !_carPlayState.value.busy &&
            sessionManager.sessionState.value == SessionState.IDLE &&
            _usbHotPlugPrompt.value == null

    fun disconnect() {
        // stop() closes the native transport, which blocks until the native side
        // releases its lock. viewModelScope is Dispatchers.Main, so this was the
        // UI thread sitting inside JNI teardown — five archived ANRs have
        // "JniTransport stopping" as their last main-thread line.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            usbDispatchMutex.lock()
            try {
                stopUnifiedUsb()
                sessionManager.stop()
            } finally { usbDispatchMutex.unlock() }
        }
    }

    // --- WPP companion discovery ---

    /** Shared discovery; in WPP it publishes the companion address to the Bluetooth advertiser. */
    private val phoneDiscovery = com.openautolink.app.transport.PhoneDiscovery.getInstance(application)

    init {
        viewModelScope.launch {
            preferences.projectionConnectionPolicy.state.collect { policy ->
                if (!policy.loaded) return@collect
                if (policy.retirementRequired) reconcileWiredOnly()
                else if (policy.wirelessAllowed && wiredOnlyWasApplied) rearmNormalConnectionOnce()
            }
        }
        // WPP: bind the inbound listener as soon as the transport is selected.
        //
        // Every other transport has an event that starts the session — discovery
        // resolving a phone (hotspot) or a cable attach (USB). WPP has neither:
        // the phone connects INBOUND to the {ip, port} we advertised over
        // Bluetooth, so the socket must already be listening before there is
        // anything to react to. Waiting for a trigger here is circular, and is
        // why an earlier revision advertised a port with nothing bound to it.
        //
        // So treat "transport == wpp" itself as the trigger: start the session,
        // which binds the listener and then idles until the phone arrives.
        viewModelScope.launch {
            preferences.effectiveDirectTransport
                .map { it == AppPreferences.DIRECT_TRANSPORT_WPP }
                .distinctUntilChanged()
                .collect { isWpp ->
                    if (!isWpp || (wiredOnlyWasApplied && normalRearmRevision != preferences.projectionConnectionPolicy.state.value.revision) ||
                        !preferences.projectionConnectionPolicy.wirelessAllowed) return@collect
                    if (preferences.effectiveCarPlaySettings.first().let { it.enabled && it.connectionMode == "wireless" }) return@collect
                    if (sessionManager.sessionState.value != SessionState.IDLE) return@collect
                    if (connectInFlight) return@collect
                    OalLog.i(TAG, "WPP transport selected — binding inbound listener")
                    hasConnected = false
                    connect()
                }
        }
        // Debug aid for emulator testing: when manualIpEnabled is on, inject
        // a synthetic resolved phone into PhoneDiscovery so the picker /
        // auto-reconnect / IP-cache flows exercise the same code paths as
        // the car. The AVD's 10.0.2.0/24 NAT prevents real discovery from
        // ever surfacing a phone on the host's WiFi, but outbound TCP to a
        // home-WiFi IP still works (NAT'd through the host). On real
        // hardware the user shouldn't have manualIpEnabled on, so this is
        // a no-op there.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.manualIpEnabled,
                preferences.manualIpAddress,
            ) { enabled, ip -> if (enabled && ip.isNotBlank()) ip else "" }
                .distinctUntilChanged()
                .collect { ip ->
                    if (ip.isNotBlank()) {
                        phoneDiscovery.injectDebugPhone(host = ip)
                    }
                }
        }
        // Wake: re-arm the WPP session owner. WPP is independent of IP discovery:
        // the Bluetooth dial-back cannot happen until this car binds its listener.
        viewModelScope.launch {
            sessionManager.wakeEvents.collect { event ->
                val wppSelected = preferences.effectiveDirectTransport.first() ==
                    AppPreferences.DIRECT_TRANSPORT_WPP
                if (event.gapMs >= WAKE_AUTO_RECONNECT_MIN_GAP_MS &&
                    WppWakeReconnectPolicy.shouldKickWake(
                        wppSelected = wppSelected,
                        connectInFlight = connectInFlight,
                        sessionIdle = sessionManager.sessionState.value == SessionState.IDLE,
                        currentWppOwnerPresent = sessionManager.hasCurrentWppOwner(),
                    )) {
                    connectForWppRearm(source = "wake")
                }
            }
        }
        // Ignition ON edge: the authoritative "car is alive now" event.
        viewModelScope.launch {
            com.openautolink.app.input.IgnitionMonitor.ignitionState
                .collect { state ->
                    val on = state == 4 || state == 5
                    if (!on) return@collect
                    val wppSelected = preferences.effectiveDirectTransport.first() ==
                        AppPreferences.DIRECT_TRANSPORT_WPP
                    if (WppWakeReconnectPolicy.shouldKickIgnition(
                            wppSelected = wppSelected,
                            connectInFlight = connectInFlight,
                            sessionIdle = sessionManager.sessionState.value == SessionState.IDLE,
                            currentWppOwnerPresent = sessionManager.hasCurrentWppOwner(),
                        )) {
                        connectForWppRearm(source = "ignition")
                    }
                }
        }
        // Ignition OFF edge: tell the phone we're going away.
        //
        // Previously the head unit just stopped — the socket died with the
        // process and the phone only noticed when its ~9s ping watchdog fired.
        // From the phone's point of view that is indistinguishable from driving
        // out of range, so it burns the full timeout and then flails through a
        // reconnect storm. Sending the protocol ByeBye turns an ambiguous
        // disappearance into a clean, expected teardown.
        //
        // Deliberately narrow: only fires on a real ON -> OFF/LOCK transition
        // while a session is actually up. The initial null -> OFF read on a cold
        // start must not count, or we would ByeBye a session we never had.
        viewModelScope.launch {
            var wasOn = false
            com.openautolink.app.input.IgnitionMonitor.ignitionState
                .collect { state ->
                    if (state == null) return@collect
                    val on = state == 4 || state == 5
                    if (on) {
                        // Re-arm the advertiser on every ignition ON. Bluetooth
                        // cycles with the car, and a socket created before that
                        // is dead — but nothing else notices, because the
                        // advertiser normally only starts on a preference
                        // change. Idempotent: a healthy advertiser is left alone.
                        com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                            .ensureAdvertising()
                        wasOn = true
                        return@collect
                    }
                    // OFF (2) or LOCK (1), and we previously saw ON.
                    if (!wasOn) return@collect
                    if (state != 1 && state != 2) return@collect
                    wasOn = false
                    // Clear what we learned about the phone's whereabouts. The
                    // telematics AP comes back on a different subnet with a
                    // different phone address, and the companion reopens its
                    // proxy on a different port, so every cached value is known
                    // to be wrong before we even try it.
                    //
                    // Runs BEFORE the IDLE check and unconditionally: the reset
                    // matters most when there is no live session, and gating it
                    // behind the ByeBye meant an ignition cycle from an idle
                    // state kept stale addresses and never told the companion.
                    com.openautolink.app.transport.bluetooth.AaWirelessBtControl
                        .resetForNextIgnition()
                    if (sessionManager.sessionState.value == SessionState.IDLE) return@collect
                    OalLog.i(TAG, "Ignition OFF — sending ByeBye before teardown")
                    sessionManager.shutdownGracefully("ignition_off")
                }
        }
        // WPP periodic idle probe: the sweep is how we learn the companion's address,
        // which the Bluetooth advertiser needs to ask for its AA proxy port. The
        // sweep itself never dials; the Bluetooth side proves which phone owns
        // an address, so no default phone is needed.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(IDLE_SWEEP_INTERVAL_MS)
                try {
                    if (preferences.effectiveDirectTransport.first() != AppPreferences.DIRECT_TRANSPORT_WPP) continue
                    if (sessionManager.sessionState.value != SessionState.IDLE) continue
                    if (connectInFlight) continue
                    if (phoneDiscovery.isSweeping.value) continue
                    OalLog.d(TAG, "Periodic idle probe (WPP, no session)")
                    // Cheap-first: a single UDP broadcast costs ~1 packet
                    // and ~600ms wall time. Only escalate to the full /24
                    // sweep if broadcast comes back empty.
                    val wppInterface = preferences.wppApInterface.first()
                    val hits = try {
                        phoneDiscovery.udpBroadcastOnInterface(wppInterface, listenWindowMs = 600L)
                    } catch (_: Exception) { 0 }
                    if (hits == 0) phoneDiscovery.startSweep(forcedInterfaceName = wppInterface)
                } catch (_: Exception) { /* keep loop alive */ }
            }
        }

        // Auto-start file logging on USB whenever the pref is on AND a USB
        // drive is mounted. Watch both the pref and storage-volume mount
        // events so attaching a stick mid-session also starts logging.
        registerUsbStorageReceiver()
        viewModelScope.launch {
            preferences.fileLoggingAutoStartUsb.collect { enabled ->
                evaluateAutoUsbLogging(enabled, "pref-change")
            }
        }
        // Maintainer "always log" mode: when the persisted pref is on, start
        // file logging as soon as the projection screen comes up so the whole
        // drive is captured without a manual toggle. Observe the pref so
        // enabling it mid-session starts logging immediately too. Idempotent —
        // startFileLoggingLocked no-ops when already active (e.g. USB-autostart
        // or a manual toggle already started it).
        viewModelScope.launch {
            preferences.logPersistEnabled.collect { persist ->
                if (persist) {
                    synchronized(fileLogToggleLock) {
                        if (!_fileLoggingActive.value) {
                            OalLog.i(TAG, "Always-log mode on — starting file logging")
                            startFileLoggingLocked(requireRemovable = false)
                        }
                    }
                }
            }
        }
    }

    /** Active transport ("hotspot" or "usb") — used by the projection screen
     *  to decide whether to render the USB device picker. */
    val transportMode: StateFlow<String> = sessionManager.transportMode

    /**
     * Resolve a saved network interface name to an [android.net.Network] for socket binding.
     * Uses two-tier lookup: first by interface name via ConnectivityManager, then falls back
     * to default routing if not found. Skips binding for loopback addresses.
     *
     * When no interface is configured (empty string), auto-selects eth0 if available.
     * On GM AAOS head units, a USB NIC always appears as eth0.
     */
    private fun resolveNetwork(interfaceName: String): Network? {
        val targetName = interfaceName.ifBlank {
            // Auto-select: prefer eth0 (USB NIC on GM AAOS), then any Ethernet interface
            val autoName = findDefaultEthernetInterface()
            if (autoName != null) {
                Log.i(TAG, "Auto-selected network interface: $autoName")
                com.openautolink.app.diagnostics.DiagnosticLog.i("transport",
                    "Auto-selected network interface: $autoName")
            }
            autoName ?: return null // no ethernet found — default routing
        }
        try {
            for (network in connectivityManager.allNetworks) {
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                if (linkProps.interfaceName == targetName) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    val transport = when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true -> "USB"
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                        else -> "other"
                    }
                    Log.i(TAG, "Bound to interface '$targetName' ($transport) handle=${network.networkHandle}")
                    com.openautolink.app.diagnostics.DiagnosticLog.i("transport",
                        "Bound to interface '$targetName' ($transport)")
                    return network
                }
            }
            Log.w(TAG, "Interface '$targetName' not found in ConnectivityManager — default routing")
            com.openautolink.app.diagnostics.DiagnosticLog.w("transport",
                "Interface '$targetName' not found — default routing")
        } catch (e: Exception) {
            Log.w(TAG, "Network resolution failed: ${e.message}")
        }
        return null
    }

    /**
     * Find the best default Ethernet interface for bridge communication.
     * Prefers eth0 (USB NIC on GM AAOS), then any other Ethernet/USB transport interface.
     */
    private fun findDefaultEthernetInterface(): String? {
        var fallback: String? = null
        try {
            for (network in connectivityManager.allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                val name = linkProps.interfaceName ?: continue
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                if (!isEthernet) continue
                if (name == "eth0") return name // preferred — GM AAOS USB NIC
                if (fallback == null) fallback = name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ethernet interface scan failed: ${e.message}")
        }
        return fallback
    }

    fun toggleStats() {
        _showStats.value = !_showStats.value
    }

    /** True while the auto-start-on-USB pref owns the current file-logger session.
     *  When the user manually stops via the overlay record button we clear this
     *  so the auto-start observer won't immediately restart it on the next USB
     *  mount event \u2014 they'll have to toggle the pref off/on or restart the app. */
    @Volatile private var autoUsbLoggingActive = false

    /** Maintainer-only manual upload; the action owns status independently of the menu. */
    fun uploadLogsNow() {
        logUploadAction.upload(viewModelScope) {
            if (!preferences.logUploadEnabled.first()) {
                return@upload UploadResult.Failure("upload disabled", UploadFailureCode.DISABLED)
            }
            val url = preferences.logUploadUrl.first()
            val token = preferences.logUploadToken.first()
            val label = preferences.logUploadDeviceLabel.first()
            if (url.isBlank() || token.isBlank()) {
                OalLog.w(TAG, "Upload tapped but URL/token not configured")
                return@upload UploadResult.Failure("upload not configured", UploadFailureCode.NOT_CONFIGURED)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LogUploader(getApplication()).upload(url, token, label)
            }
        }
    }

    fun toggleFileLogging() {
        synchronized(fileLogToggleLock) {
            if (_fileLoggingActive.value) {
                stopFileLoggingLocked()
                autoUsbLoggingActive = false
            } else {
                startFileLoggingLocked(requireRemovable = false)
            }
        }
    }

    /**
     * Internal start helper \u2014 must be called under [fileLogToggleLock].
     * Returns true if logging actually started.
     */
    private fun startFileLoggingLocked(requireRemovable: Boolean): Boolean {
        if (_fileLoggingActive.value) return true
        val writer = FileLogWriter(getApplication())
        val path = writer.start(requireRemovable) ?: return false
        fileLogWriter = writer
        DiagnosticLog.fileLogWriter = writer
        java.io.File(path).parentFile?.let {
            com.openautolink.app.diagnostics.EvTelemetryRecorder.instance.enable(it)
        }
        _fileLoggingActive.value = true
        // Write existing ring buffer entries so we have context
        writer.writeExistingLogs(DiagnosticLog.localLogs.value)

        // Optionally start logcat capture if enabled in settings
        viewModelScope.launch {
            val captureEnabled = preferences.logcatCaptureEnabled.first()
            if (captureEnabled) {
                val logDir = java.io.File(path).parentFile
                // Re-check under the lock: a fast stop (or stop+start) must not orphan a logcat process.
                if (logDir != null) synchronized(fileLogToggleLock) {
                    if (fileLogWriter === writer && logcatCapture == null) {
                        val capture = LogcatCapture()
                        capture.start(logDir)
                        logcatCapture = capture
                    }
                }
            }
        }
        return true
    }

    /** Internal stop helper — must be called under [fileLogToggleLock]. */
    private fun stopFileLoggingLocked() {
        com.openautolink.app.diagnostics.EvTelemetryRecorder.instance.disable()
        logcatCapture?.stop()
        logcatCapture = null
        fileLogWriter?.stop()
        DiagnosticLog.fileLogWriter = null
        fileLogWriter = null
        _fileLoggingActive.value = false
    }

    /** Receiver for USB storage mount/unmount so auto-USB logging can react
     *  to a stick being plugged in or yanked mid-session. */
    private var usbStorageReceiver: android.content.BroadcastReceiver? = null

    private fun registerUsbStorageReceiver() {
        if (usbStorageReceiver != null) return
        val ctx = getApplication<Application>()
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                viewModelScope.launch {
                    val enabled = preferences.fileLoggingAutoStartUsb.first()
                    evaluateAutoUsbLogging(enabled, action)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            usbStorageReceiver = r
        } catch (e: Exception) {
            OalLog.w(TAG, "USB storage receiver registration failed: ${e.message}")
        }
    }

    private fun unregisterUsbStorageReceiver() {
        val ctx = getApplication<Application>()
        usbStorageReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        usbStorageReceiver = null
    }

    /**
     * Apply the auto-USB-logging pref against current storage state.
     *
     * - Pref on + USB present + not currently logging → start (USB-only).
     * - Pref on + USB present + manually-stopped session → don't restart
     *   (user explicitly stopped via overlay button this app-run).
     * - Pref off + auto-owned session active → stop.
     * - USB removed while auto-owned session active → stop.
     */
    private fun evaluateAutoUsbLogging(prefEnabled: Boolean, reason: String) {
        synchronized(fileLogToggleLock) {
            if (!prefEnabled) {
                if (autoUsbLoggingActive && _fileLoggingActive.value) {
                    OalLog.i(TAG, "USB auto-logging: stopping (pref off, reason=$reason)")
                    stopFileLoggingLocked()
                }
                autoUsbLoggingActive = false
                return
            }
            val probe = FileLogWriter(getApplication())
            val hasUsb = probe.hasRemovableStorage()
            if (!hasUsb) {
                if (autoUsbLoggingActive && _fileLoggingActive.value) {
                    OalLog.i(TAG, "USB auto-logging: stopping (USB removed, reason=$reason)")
                    stopFileLoggingLocked()
                }
                // Keep autoUsbLoggingActive flag so we restart on next mount.
                autoUsbLoggingActive = true
                return
            }
            if (_fileLoggingActive.value) {
                autoUsbLoggingActive = true
                return
            }
            val started = startFileLoggingLocked(requireRemovable = true)
            if (started) {
                autoUsbLoggingActive = true
                OalLog.i(TAG, "USB auto-logging: started (reason=$reason)")
            }
        }
    }

    /**
     * Forward a touch event from the projection surface to the bridge.
     *
     * In Crop margin-zoom rendering, the SurfaceView is INFLATED beyond the
     * panel and anchored top-left, so codec [0..innerW]x[0..innerH] maps
     * directly to the visible panel rect. Touch coords from the SurfaceView's
     * MotionEvent come in inflated-view space, but only the visible portion
     * (the panel rect) is reachable.
     *
     * Map by: codec_xy = event_xy / scale, where scale was the inflate factor
     * the renderer chose. Equivalently: codec_xy = event_xy * innerW /
     * panelW. We pass [innerW]/[innerH] explicitly so the touch math doesn't
     * have to re-derive margins from prefs.
     */
    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
        innerW: Int = 0,
        innerH: Int = 0,
        panelW: Int = 0,
        panelH: Int = 0,
    ) {
        val stats = _videoStats.value
        // Gearhead injects coordinates in the selected video tier, not the
        // touchscreen capability's first-offer dimensions. Wait for Start.
        val target = sessionManager.captureTouchTarget() ?: return
        val protocolW = target.width
        val protocolH = target.height
        val videoW = if (stats.width > 0) stats.width else protocolW
        val videoH = if (stats.height > 0) stats.height else protocolH
        if (protocolW <= 0 || protocolH <= 0) {
            // The other way touch dies silently: video stats only start being
            // collected on SessionState.STREAMING, so a session that comes up via
            // a recovery path without publishing that state leaves these at zero
            // and every touch returns here. touchWidth/Height default to 1920x1080
            // so this should be unreachable — say so if it ever is not.
            val now = System.currentTimeMillis()
            if (now - lastNoTouchSizeWarnAt > 5_000L) {
                lastNoTouchSizeWarnAt = now
                com.openautolink.app.diagnostics.DiagnosticLog.w("input",
                    "Dropping touch — no advertised touchscreen dimensions yet " +
                        "(${protocolW}x${protocolH})")
            }
            return
        }
        // When inner+panel are provided, pretend the view is the panel rect
        // and the codec is the inner rect — this maps edge-of-panel to
        // edge-of-AA-UI (codec col innerW, row innerH) regardless of how the
        // SurfaceView is sized/clipped.
        val effSurfW: Int
        val effSurfH: Int
        val effCodecW: Int
        val effCodecH: Int
        if (innerW > 0 && innerH > 0 && panelW > 0 && panelH > 0) {
            effSurfW = panelW
            effSurfH = panelH
            val protocolInner = TouchCoordinateSpace.innerForProtocol(
                protocolWidth = protocolW,
                protocolHeight = protocolH,
                videoWidth = videoW,
                videoHeight = videoH,
                videoInnerWidth = innerW,
                videoInnerHeight = innerH,
            )
            effCodecW = protocolInner.first
            effCodecH = protocolInner.second
        } else {
            effSurfW = surfaceWidth
            effSurfH = surfaceHeight
            effCodecW = protocolW
            effCodecH = protocolH
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val sx = event.x * effCodecW / effSurfW
            val sy = event.y * effCodecH / effSurfH
            Log.d("TouchDebug", "viewSurf=${surfaceWidth}x${surfaceHeight} " +
                "video=${videoW}x${videoH} protocol=${protocolW}x${protocolH} " +
                "effSurf=${effSurfW}x${effSurfH} effTouch=${effCodecW}x${effCodecH} " +
                "raw=(${event.x.toInt()},${event.y.toInt()}) scaled=(${sx.toInt()},${sy.toInt()})")
            val now = System.currentTimeMillis()
            if (now - lastTouchMappingLogAt > 5_000L) {
                lastTouchMappingLogAt = now
                com.openautolink.app.diagnostics.DiagnosticLog.i(
                    "input",
                    "Touch mapping active: video=${videoW}x${videoH} " +
                        "protocol=${protocolW}x${protocolH} " +
                        "panel=${effSurfW}x${effSurfH} " +
                        "innerTouch=${effCodecW}x${effCodecH}",
                )
            }
        }
        // Capture before coroutine enqueue, not when the delayed sender runs.
        TouchForwarderImpl { message ->
            viewModelScope.launch { sessionManager.sendSelectedTouch(message, target) }
        }.onTouch(event, effSurfW, effSurfH, effCodecW, effCodecH)
    }

    /** Handle a steering wheel key event. Returns true if consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (_carPlayState.value.selected) return false // No invented CarPlay steering/HFP mapping.
        return steeringWheelController.onKeyEvent(event)
    }

    /**
     * Run a simulated ignition cycle: shutdown, Bluetooth loss, then reconnect.
     *
     * Exists so the sequence where every recent failure has occurred can be
     * exercised in the driveway, repeatedly, instead of once per drive.
     */
    fun simulateIgnitionCycle() {
        sessionManager.debugSimulateIgnitionCycle()
    }

    /** Called when the SurfaceView surface is created or changed. */
    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingSurface = surface
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height

        // Debounce surface changes — AAOS animates surface size on launch (788→864 in ~30 steps).
        // Without debounce, each step resets the codec, losing the codec config frame.
        surfaceDebounceJob?.cancel()
        surfaceDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            Log.d(TAG, "Surface stabilized at ${width}x${height}")
            com.openautolink.app.diagnostics.DiagnosticLog.i("video",
                "Surface stabilized: ${width}x${height}")
            // Publish to the SessionManager so the surface survives decoder
            // recreation regardless of which lifecycle moves first.
            sessionManager.publishSurface(surface, width, height)
            val decoder = sessionManager.videoDecoder
            if (decoder == null) {
                // Say so. This is a silent no-op that produced a black screen
                // with a perfectly healthy 41fps stream behind it: the surface
                // was ready 42s before the decoder existed, and nothing later
                // handed it over. The pending surface is kept and attached when
                // a decoder appears — the log line matters because "Surface
                // stabilized" above makes it look like something happened.
                com.openautolink.app.diagnostics.DiagnosticLog.i("video",
                    "Surface ready but no decoder yet — will attach when one exists")
                return@launch
            }
            decoder.attach(surface, width, height)
            // Surface may have attached after the bridge's SPS/PPS+IDR replay arrived,
            // meaning the IDR was dropped (codec wasn't configured yet). Request a
            // fresh keyframe so the bridge sends a new IDR now that the codec is ready.
            sessionManager.requestKeyframe()
        }
    }

    /** Called when the SurfaceView surface is destroyed. */
    fun onSurfaceDestroyed() {
        pendingSurface = null
        pendingSurfaceWidth = 0
        pendingSurfaceHeight = 0
        sessionManager.publishSurface(null, 0, 0)
        // detach() touches MediaCodec, which blocks. This is a SurfaceView
        // callback, so it arrives on the main thread.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sessionManager.videoDecoder?.detach()
        }
    }

    /** Attach pending surface to a newly created decoder. Called by session observer. */
    internal fun attachPendingSurface() {
        val s = pendingSurface
        val d = sessionManager.videoDecoder
        if (s == null || d == null) {
            // Both silent returns produced a black screen with a healthy stream
            // behind it, and neither left a trace. Say which half was missing.
            com.openautolink.app.diagnostics.DiagnosticLog.w("video",
                "Cannot attach surface: pendingSurface=${s != null} decoder=${d != null}")
            return
        }
        d.attach(s, pendingSurfaceWidth, pendingSurfaceHeight)
    }

    private fun registerTransportNetworkCallback() {
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                transportNetworkCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register transport network callback: ${e.message}")
        }
    }

    private fun handleTransportNetworkUpdate(network: Network, reason: String) {
        if (!isTransportNetwork(network)) return
        synchronized(trackedTransportNetworks) {
            trackedTransportNetworks.add(network.networkHandle)
        }
        requestTransportReconnect(reason)
    }

    private fun isTransportNetwork(network: Network): Boolean {
        val linkProps = connectivityManager.getLinkProperties(network)
        val interfaceName = linkProps?.interfaceName
        if (selectedNetworkInterfaceName.isNotBlank() && interfaceName == selectedNetworkInterfaceName) {
            return true
        }

        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
    }

    private fun requestTransportReconnect(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTransportNetworkEventAt < 750L) return
        lastTransportNetworkEventAt = now
        Log.i(TAG, "Transport network event: $reason")
    }

    override fun onCleared() {
        usbDispatchEpoch.incrementAndGet()
        unifiedUsbManager?.stop()
        unifiedUsbManager = null
        sessionManager.externalUsbTransport = false
        try {
            connectivityManager.unregisterNetworkCallback(transportNetworkCallback)
        } catch (_: Exception) {}
        sessionManager.onDecoderCreated = null
        videoStatsJob?.cancel()
        audioStatsJob?.cancel()
        // Stop file logging if active
        com.openautolink.app.diagnostics.EvTelemetryRecorder.instance.disable()
        logcatCapture?.stop()
        fileLogWriter?.stop()
        DiagnosticLog.fileLogWriter = null
        unregisterUsbStorageReceiver()
        // onCleared() runs on the main thread. Hand the native teardown to a
        // plain thread — viewModelScope is already cancelled by this point, so a
        // coroutine here would never run.
        Thread({
            try {
                kotlinx.coroutines.runBlocking {
                    usbDispatchMutex.lock()
                    try {
                        retireUsbProjection()
                        usbHandoff.release()
                        usbSwitchPolicy.clearRetired()
                    } finally { usbDispatchMutex.unlock() }
                }
            } catch (_: Exception) {
                // Keep a failed exact retirement fenced; never reopen WPP on an unproved close.
                OalLog.e(TAG, "USB handoff stage=FAILED")
            } finally { carPlayCoordinator.close() }
        }, "oal-session-stop").start()
        super.onCleared()
    }
}
