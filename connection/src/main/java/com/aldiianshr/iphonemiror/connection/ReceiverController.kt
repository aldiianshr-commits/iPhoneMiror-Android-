package com.aldiianshr.iphonemiror.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.aldiianshr.iphonemiror.connection.airplay.AirPlayProfile
import com.aldiianshr.iphonemiror.connection.airplay.AirPlayReceiver
import com.aldiianshr.iphonemiror.connection.airplay.AirPlayReceiverConfig
import com.aldiianshr.iphonemiror.connection.airplay.SenderInfo
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.core.NetworkInfo
import com.aldiianshr.iphonemiror.core.toMacString
import com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.AppleDevice
import com.aldiianshr.iphonemiror.domain.model.ConnectionType
import com.aldiianshr.iphonemiror.domain.model.ReceiverState
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.domain.repository.HistoryRepository
import com.aldiianshr.iphonemiror.domain.repository.IdentityStore
import com.aldiianshr.iphonemiror.domain.repository.KnownDeviceRepository
import com.aldiianshr.iphonemiror.domain.stream.AudioFormatInfo
import com.aldiianshr.iphonemiror.domain.stream.AudioSink
import com.aldiianshr.iphonemiror.domain.stream.VideoSink
import com.aldiianshr.iphonemiror.domain.usecase.ApprovalDecision
import com.aldiianshr.iphonemiror.domain.usecase.EvaluateDeviceApproval
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Owns the AirPlay receiver lifecycle: identity, mDNS advertisement, network changes
 * (re-advertising automatically when Wi-Fi comes back), user approval of new senders and
 * connection history. Everything the UI shows comes from real protocol events.
 */
class ReceiverController(
    private val context: Context,
    private val advertiser: AirPlayAdvertiser,
    private val identityStore: IdentityStore,
    private val history: HistoryRepository,
    private val knownDevices: KnownDeviceRepository,
    private val videoSink: VideoSink,
    private val audioSink: AudioSink,
) {
    sealed interface Event {
        data class MirrorStarted(val device: AppleDevice?) : Event
        data object MirrorStopped : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val evaluateApproval = EvaluateDeviceApproval(knownDevices)
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()
    private val _audioFormat = MutableStateFlow<AudioFormatInfo?>(null)
    val audioFormat: StateFlow<AudioFormatInfo?> = _audioFormat.asStateFlow()

    private var receiver: AirPlayReceiver? = null
    private var settings: AppSettings = AppSettings()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val sessionDevices = ConcurrentHashMap<String, AppleDevice>()
    private val sessionHistoryIds = ConcurrentHashMap<String, Long>()
    private var identity: PairingIdentity? = null
    private var deviceId: String = ""

    val isRunning: Boolean get() = receiver?.isRunning == true

    /** Pairing public key (hex) — shown on the Apple Account / pairing page. */
    fun publicKeyHex(): String? = identity?.publicKeyHex()
    fun receiverDeviceId(): String = deviceId

    @Synchronized
    fun start(newSettings: AppSettings) {
        settings = newSettings
        if (receiver?.isRunning == true) return
        val id = identity ?: PairingIdentity.fromSeed(identityStore.loadOrCreateSeed()).also { identity = it }
        deviceId = identityStore.loadOrCreateDeviceId().toMacString()
        val config = AirPlayReceiverConfig(
            name = settings.receiverName.ifBlank { "iPhoneMiror" },
            deviceId = deviceId,
            width = settings.resolution.width,
            height = settings.resolution.height,
            maxFps = settings.maxFps,
            identity = id,
            audioEnabled = settings.audioEnabled,
        )
        val r = AirPlayReceiver(config, videoSink, audioSink, receiverEvents, approvalGate)
        val port = try {
            r.start()
        } catch (e: Exception) {
            AppLog.e(TAG, "Receiver failed to start", e)
            _state.value = ReceiverState(status = ReceiverStatus.ERROR, lastError = e.message)
            return
        }
        receiver = r
        _state.value = ReceiverState(
            status = ReceiverStatus.WAITING,
            receiverName = config.name,
            port = port,
        )
        registerNetworkCallback()
        advertise()
    }

    /** Applies new settings; restarts the receiver only when advertised values changed. */
    fun applySettings(newSettings: AppSettings) {
        val old = settings
        settings = newSettings
        val needsRestart = old.receiverName != newSettings.receiverName ||
            old.resolution != newSettings.resolution || old.maxFps != newSettings.maxFps ||
            old.audioEnabled != newSettings.audioEnabled
        if (isRunning && needsRestart && _state.value.status != ReceiverStatus.MIRRORING) {
            stop()
            start(newSettings)
        }
    }

    @Synchronized
    fun stop() {
        unregisterNetworkCallback()
        advertiser.unregister()
        receiver?.stop()
        receiver = null
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        _state.value = ReceiverState(status = ReceiverStatus.STOPPED)
    }

    fun disconnect() {
        receiver?.disconnectAll()
    }

    /** Called by the UI for a sender waiting in [ReceiverState.awaitingApproval]. */
    fun respondToApproval(allow: Boolean, remember: Boolean) {
        val device = _state.value.awaitingApproval ?: return
        scope.launch {
            if (remember) {
                knownDevices.upsertSeen(device, System.currentTimeMillis())
                if (allow) knownDevices.setAllowed(device.id, true) else knownDevices.setBlocked(device.id, true)
            }
        }
        pendingApprovals.remove(device.id)?.complete(allow)
        _state.update { it.copy(awaitingApproval = null) }
    }

    /** Clears the pairing identity; the iPhone will see a new receiver key next time. */
    fun resetIdentity() {
        val wasRunning = isRunning
        stop()
        identityStore.reset()
        identity = null
        if (wasRunning) start(settings)
    }

    private fun advertise() {
        val r = receiver ?: return
        val net = NetworkInfo.current(context)
        _state.update { it.copy(ipAddresses = net.ipv4Addresses) }
        if (!net.usable) {
            advertiser.unregister()
            _state.update { it.copy(status = ReceiverStatus.NO_NETWORK, advertised = false) }
            return
        }
        val name = settings.receiverName.ifBlank { "iPhoneMiror" }
        val pk = identity!!.publicKeyHex()
        advertiser.register(
            name = name,
            port = r.port,
            airplayTxt = AirPlayProfile.airplayTxt(deviceId, pk),
            raopName = AirPlayProfile.raopServiceName(deviceId, name),
            raopTxt = AirPlayProfile.raopTxt(pk),
        )
        _state.update {
            it.copy(
                advertised = true,
                status = if (it.status == ReceiverStatus.NO_NETWORK) ReceiverStatus.WAITING else it.status,
            )
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLog.i(TAG, "Network available — re-advertising")
                scope.launch { advertise() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ips = NetworkInfo.current(context).ipv4Addresses
                if (ips != _state.value.ipAddresses) {
                    AppLog.i(TAG, "IP changed to $ips — re-advertising")
                    scope.launch { advertise() }
                }
            }

            override fun onLost(network: Network) {
                AppLog.w(TAG, "Network lost")
                scope.launch {
                    if (!NetworkInfo.current(context).usable) {
                        advertiser.unregister()
                        _state.update { it.copy(status = ReceiverStatus.NO_NETWORK, advertised = false, ipAddresses = emptyList()) }
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        context.getSystemService(ConnectivityManager::class.java)?.let { runCatching { it.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private fun deviceFrom(info: SenderInfo, remote: String) = AppleDevice(
        id = info.deviceId ?: info.macAddress ?: remote,
        name = info.name,
        model = info.model,
        osName = info.osName,
        osVersion = info.osVersion,
        osBuild = info.osBuild,
        connectionType = ConnectionType.WIFI,
        address = remote,
    )

    private fun isSelfTest(info: SenderInfo, remote: String) =
        info.model == SELF_TEST_MODEL && (remote == "127.0.0.1" || remote == "::1" || remote.startsWith("0:0:0:0:0:0:0:1"))

    private val approvalGate = AirPlayReceiver.ApprovalGate { sessionId, info, remote ->
        val device = sessionDevices[sessionId] ?: deviceFrom(info, remote)
        val decision = if (isSelfTest(info, remote)) ApprovalDecision.ALLOW
        else runBlocking { evaluateApproval(device, settings.requireApproval) }
        when (decision) {
            ApprovalDecision.ALLOW -> true
            ApprovalDecision.DENY -> false
            ApprovalDecision.ASK -> {
                val future = CompletableFuture<Boolean>()
                pendingApprovals[device.id] = future
                _state.update { it.copy(awaitingApproval = device) }
                try {
                    future.get(APPROVAL_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    AppLog.w(TAG, "Approval for ${device.displayName} timed out")
                    false
                } finally {
                    pendingApprovals.remove(device.id)
                    _state.update { if (it.awaitingApproval?.id == device.id) it.copy(awaitingApproval = null) else it }
                }
            }
        }
    }

    private val receiverEvents = object : AirPlayReceiver.Events {
        override fun onClientConnected(sessionId: String, remote: String) {
            _state.update {
                if (it.status == ReceiverStatus.WAITING) it.copy(status = ReceiverStatus.CONNECTING) else it
            }
        }

        override fun onPairVerified(sessionId: String, verified: Boolean) {
            AppLog.i(TAG, "Pair-verify ${if (verified) "OK" else "FAILED"} for $sessionId")
            _state.update {
                it.copy(
                    pairVerified = verified,
                    encrypted = verified,
                    status = if (verified && it.status != ReceiverStatus.MIRRORING) ReceiverStatus.PAIRED else it.status,
                )
            }
        }

        override fun onSenderInfo(sessionId: String, info: SenderInfo, remote: String) {
            val device = deviceFrom(info, remote)
            val isNew = sessionDevices.put(sessionId, device) == null
            AppLog.i(TAG, "Sender: ${device.name} model=${device.model} ${device.osName} ${device.osVersion}")
            _state.update { it.copy(device = device) }
            if (isNew && !isSelfTest(info, remote)) scope.launch {
                knownDevices.upsertSeen(device, System.currentTimeMillis())
                sessionHistoryIds[sessionId] = history.start(device, System.currentTimeMillis())
            }
        }

        override fun onMirrorStarted(sessionId: String) {
            val device = sessionDevices[sessionId]
            _state.update {
                it.copy(
                    status = ReceiverStatus.MIRRORING,
                    sessionStartedAt = System.currentTimeMillis(),
                    encrypted = true,
                    device = device ?: it.device,
                )
            }
            sessionHistoryIds[sessionId]?.let { id -> scope.launch { history.markMirrored(id) } }
            _events.tryEmit(Event.MirrorStarted(device))
        }

        override fun onMirrorStopped(sessionId: String, error: Throwable?) {
            if (error != null) AppLog.w(TAG, "Mirror stream ended with error", error)
            _state.update {
                if (it.status == ReceiverStatus.MIRRORING) it.copy(status = ReceiverStatus.PAIRED, sessionStartedAt = null) else it
            }
            _events.tryEmit(Event.MirrorStopped)
        }

        override fun onAudioStarted(sessionId: String, format: AudioFormatInfo) {
            _audioFormat.value = format
        }

        override fun onSessionEnded(sessionId: String, reason: String) {
            sessionDevices.remove(sessionId)
            sessionHistoryIds.remove(sessionId)?.let { id ->
                scope.launch { history.finish(id, System.currentTimeMillis(), reason) }
            }
            _audioFormat.value = null
            _state.update {
                it.copy(
                    status = if (receiver?.isRunning == true) ReceiverStatus.WAITING else ReceiverStatus.STOPPED,
                    device = null,
                    pairVerified = false,
                    encrypted = false,
                    sessionStartedAt = null,
                    lastError = reason.takeUnless { r -> r.startsWith("Koneksi kontrol ditutup") },
                )
            }
            _events.tryEmit(Event.MirrorStopped)
        }
    }

    companion object {
        private const val TAG = "ReceiverController"
        const val APPROVAL_TIMEOUT_S = 30L
        const val SELF_TEST_MODEL = "SelfTest"
    }
}
