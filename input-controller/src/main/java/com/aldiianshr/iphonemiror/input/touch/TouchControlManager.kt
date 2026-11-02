package com.aldiianshr.iphonemiror.input.touch

import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.model.AppSettings
import com.aldiianshr.iphonemiror.domain.model.HidPointerMode
import com.aldiianshr.iphonemiror.domain.model.TouchBackendPref
import com.aldiianshr.iphonemiror.input.BluetoothHidController
import com.aldiianshr.iphonemiror.input.HidReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Direct touch control: receives the mapped touch stream from the mirroring view and
 * forwards it to the iPhone through the selected legitimate backend.
 *
 * - Bluetooth HID (absolute pointer, iOS 18+, AssistiveTouch on): streamed live, so the
 *   iPhone follows the finger in real time. Single-pointer gestures + two-finger scroll.
 * - XCTest / WebDriverAgent: each gesture is replayed through Apple's UI-testing framework
 *   once the fingers lift — exact point coordinates and true multi-touch (pinch, 2+ fingers).
 *
 * When neither path is available the state says so (Unsupported) with the reason; touches
 * are then not forwarded and no success is ever shown.
 */
class TouchControlManager(
    private val hid: BluetoothHidController,
    /** nanoTime of the last decoded mirror frame (0 = none). */
    private val frameClock: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wdaDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val translator = HidTouchTranslator()
    private val recorder = GestureRecorder()
    private val gestureCounter = AtomicLong()
    private var settings = AppSettings()
    private var pressJob: Job? = null
    private var pingJob: Job? = null
    private var lastTap: Pair<NPoint, Long>? = null
    private var gestureStartNanos = 0L
    private var gestureBackend: BackendKind? = null
    private val latencies = ArrayDeque<Long>()

    private var videoW = 0
    private var videoH = 0
    private var phoneAddress: String? = null

    private var wda: WebDriverAgentClient? = null
    private var wdaSession: String? = null
    private var wdaWindow: Pair<Int, Int>? = null
    private var wdaOrientationLandscape: Boolean? = null

    private val _state = MutableStateFlow(TouchControlState())
    val state: StateFlow<TouchControlState> = _state.asStateFlow()

    /** Live touch points (normalized) for drawing indicators. */
    private val _livePoints = MutableStateFlow<Map<Int, NPoint>>(emptyMap())
    val livePoints: StateFlow<Map<Int, NPoint>> = _livePoints.asStateFlow()

    init {
        scope.launch { hid.state.collect { recompute() } }
    }

    fun configure(s: AppSettings) {
        settings = s
        translator.absolute = s.hidPointerMode == HidPointerMode.ABSOLUTE
        translator.orientation = s.hidOrientation
        translator.invertScroll = s.invertScroll
        translator.relativeSpeed = s.pointerSpeed
        hid.setPointerMode(s.hidPointerMode == HidPointerMode.ABSOLUTE)
        recompute()
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == videoW && h == videoH)) return
        val landscapeChanged = (w > h) != (videoW > videoH)
        videoW = w; videoH = h
        translator.videoLandscape = w > h
        translator.aspect = w.toFloat() / h
        if (landscapeChanged && wdaSession != null) scope.launch(wdaDispatcher) { refreshWdaWindow() }
    }

    /** IP address of the mirroring iPhone (from the AirPlay connection) for the default WDA URL. */
    fun setPhoneAddress(ip: String?) { phoneAddress = ip; recompute() }

    fun defaultWdaUrl(): String? = phoneAddress?.let { WebDriverAgentClient.urlFor(it) }

    // ---------------- Touch input ----------------

    fun onTouch(e: TouchEvent) {
        updateLive(e)
        val backend = _state.value.activeBackend
        if (e is TouchEvent.Down && recorder.isEmpty) {
            gestureStartNanos = System.nanoTime()
            gestureBackend = backend
            if (backend == null) {
                publish(FeedbackStatus.UNSUPPORTED, null, _state.value.unsupportedReason ?: "Kontrol sentuh tidak tersedia", null)
            }
        }
        recorder.onEvent(e)
        when (gestureBackend) {
            BackendKind.BLUETOOTH_HID -> hidEvent(e)
            BackendKind.XCTEST, null -> Unit
        }
        if (recorder.activeCount == 0 && !recorder.isEmpty) finishGesture()
    }

    private fun hidEvent(e: TouchEvent) {
        val out = translator.onEvent(e)
        hid.submit(out.commands)
        out.unsupported?.let { publish(FeedbackStatus.UNSUPPORTED, null, it, null) }
        out.pressAfterMs?.let { ms ->
            pressJob?.cancel()
            pressJob = scope.launch {
                delay(ms)
                hid.submit(translator.onPressDue())
            }
        }
    }

    private fun finishGesture() {
        val tracks = recorder.tracks()
        val aspect = if (videoH > 0) videoW.toFloat() / videoH else 9f / 19.5f
        val kind = GestureClassifier.classify(tracks, aspect, lastTap)
        if (kind == GestureKind.TAP) lastTap = tracks.single().start.point to tracks.single().start.timeMs
        val backend = gestureBackend
        val points = tracks.map { it.end.point }
        val started = gestureStartNanos
        recorder.reset()
        when (backend) {
            BackendKind.BLUETOOTH_HID -> {
                val unsupported = kind == GestureKind.PINCH_IN || kind == GestureKind.PINCH_OUT || kind == GestureKind.MULTI_FINGER
                if (unsupported) {
                    publish(FeedbackStatus.UNSUPPORTED, kind, "${kind?.label} butuh multi-touch; HID hanya satu pointer. Gunakan XCTest.", null, points)
                } else {
                    val id = publish(if (hid.lastSendOk) FeedbackStatus.SENT else FeedbackStatus.FAILED, kind,
                        if (hid.lastSendOk) "Dikirim lewat Bluetooth HID" else "Laporan HID ditolak oleh stack Bluetooth", null, points)
                    if (hid.lastSendOk) watchScreenResponse(id, kind, BackendKind.BLUETOOTH_HID, started, points)
                }
            }
            BackendKind.XCTEST -> replayWithXcTest(tracks, kind, points)
            null -> Unit
        }
        gestureBackend = null
    }

    /** Waits for a mirrored frame decoded after the input started: evidence the iPhone redrew. */
    private fun watchScreenResponse(id: Long, kind: GestureKind?, backend: BackendKind, startNanos: Long, points: List<NPoint>) {
        scope.launch {
            val deadline = System.nanoTime() + 1_500_000_000L
            while (isActive && System.nanoTime() < deadline) {
                val f = frameClock()
                if (f > startNanos) {
                    val ms = (f - startNanos) / 1_000_000
                    recordLatency(ms)
                    update(id, FeedbackStatus.SCREEN_RESPONSE, kind, backend,
                        "Frame baru diterima $ms ms setelah sentuhan", ms, points)
                    return@launch
                }
                delay(10)
            }
            update(id, FeedbackStatus.NO_SCREEN_CHANGE, kind, backend,
                "Tidak ada frame baru dalam 1,5 dtk (layar mungkin memang tidak berubah)", null, points)
        }
    }

    private fun replayWithXcTest(tracks: List<GestureRecorder.Track>, kind: GestureKind?, points: List<NPoint>) {
        val client = wda
        val session = wdaSession
        val window = wdaWindow
        if (client == null || session == null || window == null) {
            publish(FeedbackStatus.FAILED, kind, "Sesi WebDriverAgent tidak aktif", null, points)
            return
        }
        val landscape = videoW > videoH
        val actions = W3cActions.build(tracks) { TouchMapper.toPoints(it, window.first, window.second, landscape) }
        val id = publish(FeedbackStatus.SENDING, kind, "Memutar ulang gesture lewat XCTest…", null, points)
        scope.launch(wdaDispatcher) {
            val t0 = System.nanoTime()
            try {
                client.performActions(session, actions)
                val ms = (System.nanoTime() - t0) / 1_000_000
                recordLatency(ms)
                update(id, FeedbackStatus.CONFIRMED, kind, BackendKind.XCTEST, "XCTest menjalankan gesture ($ms ms)", ms, points)
            } catch (e: Exception) {
                AppLog.w(TAG, "WDA actions failed", e)
                update(id, FeedbackStatus.FAILED, kind, BackendKind.XCTEST, "WDA: ${e.message}", null, points)
                if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) dropWda("WDA tidak terjangkau: ${e.message}")
            }
        }
    }

    private fun updateLive(e: TouchEvent) {
        _livePoints.update { cur ->
            when (e) {
                is TouchEvent.Down -> cur + (e.id to e.point)
                is TouchEvent.Move -> cur + e.points.filterKeys { it in cur }
                is TouchEvent.Up -> cur - e.id
                is TouchEvent.Cancel -> emptyMap()
            }
        }
    }

    // ---------------- Keyboard & buttons ----------------

    fun typeText(text: String) {
        val session = wdaSession
        val client = wda
        if (_state.value.activeBackend == BackendKind.XCTEST && client != null && session != null) {
            scope.launch(wdaDispatcher) {
                runCatching { client.typeText(session, text) }
                    .onSuccess { publish(FeedbackStatus.CONFIRMED, null, "Teks dikirim lewat XCTest", null) }
                    .onFailure { publish(FeedbackStatus.FAILED, null, "WDA: ${it.message}", null) }
            }
        } else if (hid.state.value.canSend) {
            hid.typeText(text)
            publish(FeedbackStatus.SENT, null, "Teks dikirim sebagai keyboard Bluetooth", null)
        } else {
            publish(FeedbackStatus.UNSUPPORTED, null, "Tidak ada keyboard Bluetooth/XCTest yang terhubung", null)
        }
    }

    fun key(usage: Int, modifiers: Int = 0) {
        if (hid.state.value.canSend) hid.key(usage, modifiers)
        else publish(FeedbackStatus.UNSUPPORTED, null, "Tombol khusus butuh keyboard Bluetooth HID", null)
    }

    fun consumer(usage: Int) {
        if (hid.state.value.canSend) hid.consumer(usage)
        else publish(FeedbackStatus.UNSUPPORTED, null, "Tombol media butuh Bluetooth HID", null)
    }

    fun home() {
        val client = wda
        if (client != null && wdaSession != null) {
            scope.launch(wdaDispatcher) {
                runCatching { client.home() }
                    .onSuccess { publish(FeedbackStatus.CONFIRMED, null, "Home lewat XCTest", null) }
                    .onFailure { publish(FeedbackStatus.FAILED, null, "WDA: ${it.message}", null) }
            }
        } else if (hid.state.value.canSend) {
            hid.consumer(HidReports.CONSUMER_HOME)
            publish(FeedbackStatus.SENT, null, "Tombol Home (consumer 0x0040) dikirim", null)
        } else {
            publish(FeedbackStatus.UNSUPPORTED, null, "Home butuh Bluetooth HID atau XCTest", null)
        }
    }

    /**
     * Mapping check for the absolute HID pointer: hovers (no click) over the centre and the
     * four corners so the user can see on the mirrored picture whether the pointer lands
     * where expected, and pick the orientation option that matches.
     */
    fun runMappingTest() {
        if (!hid.state.value.canSend || !translator.absolute) {
            publish(FeedbackStatus.UNSUPPORTED, null, "Uji pemetaan butuh HID absolut yang terhubung", null)
            return
        }
        val pts = listOf(NPoint(0.5f, 0.5f), NPoint(0.1f, 0.1f), NPoint(0.9f, 0.1f), NPoint(0.9f, 0.9f), NPoint(0.1f, 0.9f), NPoint(0.5f, 0.5f))
        val cmds = ArrayList<HidCommand>()
        val landscape = videoW > videoH
        for (p in pts) {
            val (x, y) = TouchMapper.toHidAbsolute(p, landscape, settings.hidOrientation)
            cmds += HidCommand.Report(HidReports.ID_MOUSE, HidReports.absMouse(0, x, y))
            cmds += HidCommand.Delay(700)
        }
        hid.submit(cmds)
        publish(FeedbackStatus.SENT, null, "Pointer bergerak: tengah → kiri-atas → kanan-atas → kanan-bawah → kiri-bawah (tanpa klik)", null, pts)
    }

    // ---------------- WebDriverAgent ----------------

    suspend fun connectWda(url: String): Result<String> = kotlinx.coroutines.withContext(wdaDispatcher) {
        runCatching {
            val client = WebDriverAgentClient(url)
            val t0 = System.nanoTime()
            val status = client.status()
            val rtt = (System.nanoTime() - t0) / 1_000_000
            val session = client.createSession()
            wda = client
            wdaSession = session
            refreshWdaWindow()
            _state.update { it.copy(connectionLatencyMs = rtt) }
            startPing()
            recompute()
            "Terhubung ke WDA${status.osVersion?.let { v -> " (iOS $v)" } ?: ""}, sesi ${session.take(8)}…"
        }.onFailure { dropWda("Gagal terhubung: ${it.message}") }
    }

    fun disconnectWda() = dropWda("Diputus oleh pengguna")

    private fun refreshWdaWindow() {
        val c = wda ?: return
        val s = wdaSession ?: return
        runCatching { c.windowSize(s) }.onSuccess { wdaWindow = it; AppLog.i(TAG, "WDA window ${it.first}x${it.second}") }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch(wdaDispatcher) {
            while (isActive && wda != null) {
                delay(3_000)
                val c = wda ?: break
                val t0 = System.nanoTime()
                val ok = runCatching { c.status() }.isSuccess
                if (!ok) { dropWda("WDA berhenti merespons"); break }
                _state.update { it.copy(connectionLatencyMs = (System.nanoTime() - t0) / 1_000_000) }
            }
        }
    }

    private fun dropWda(reason: String) {
        pingJob?.cancel()
        wda = null; wdaSession = null; wdaWindow = null
        _state.update { it.copy(xctest = it.xctest.copy(available = false, reason = reason), connectionLatencyMs = null) }
        recompute()
    }

    // ---------------- State ----------------

    private fun recompute() {
        val h = hid.state.value
        val hidStatus = when {
            !h.supported -> BackendStatus(false, "Perangkat ini tidak mendukung profil Bluetooth HID Device", true, false)
            !h.permissionGranted -> BackendStatus(false, "Izin Bluetooth belum diberikan", true, false)
            !h.bluetoothOn -> BackendStatus(false, "Bluetooth mati", true, false)
            !h.registered -> BackendStatus(false, "Kontrol Bluetooth belum diaktifkan", true, false)
            h.link != BluetoothHidController.Link.CONNECTED -> BackendStatus(false, "iPhone belum terhubung sebagai host Bluetooth", true, false)
            else -> BackendStatus(true,
                if (translator.absolute) "Terhubung ke ${h.host} — pointer absolut (butuh iOS 18+ dan AssistiveTouch)"
                else "Terhubung ke ${h.host} — mode trackpad relatif (AssistiveTouch)", true, false)
        }
        val wdaOk = wda != null && wdaSession != null
        val xcStatus = if (wdaOk) BackendStatus(true, "Sesi XCTest aktif di ${wda?.baseUrl}", false, true)
        else _state.value.xctest.copy(available = false)
        val active = when (settings.touchBackend) {
            TouchBackendPref.BLUETOOTH_HID -> BackendKind.BLUETOOTH_HID.takeIf { hidStatus.available }
            TouchBackendPref.XCTEST -> BackendKind.XCTEST.takeIf { xcStatus.available }
            TouchBackendPref.AUTO -> when {
                hidStatus.available -> BackendKind.BLUETOOTH_HID
                xcStatus.available -> BackendKind.XCTEST
                else -> null
            }
        }
        val reason = if (active != null) null else buildString {
            append("AirPlay hanya mengirim gambar; iOS tidak menerima sentuhan lewat AirPlay. ")
            when (settings.touchBackend) {
                TouchBackendPref.BLUETOOTH_HID -> append("Bluetooth HID: ${hidStatus.reason}.")
                TouchBackendPref.XCTEST -> append("XCTest: ${xcStatus.reason}.")
                TouchBackendPref.AUTO -> append("Bluetooth HID: ${hidStatus.reason}. XCTest: ${xcStatus.reason}.")
            }
        }
        _state.update { it.copy(activeBackend = active, hid = hidStatus, xctest = xcStatus, unsupportedReason = reason) }
    }

    private fun publish(status: FeedbackStatus, kind: GestureKind?, msg: String?, latency: Long?, points: List<NPoint> = emptyList()): Long {
        val id = gestureCounter.incrementAndGet()
        _state.update {
            it.copy(
                lastFeedback = TouchFeedback(id, kind, it.activeBackend, status, msg, latency, points),
                gesturesSent = if (status == FeedbackStatus.UNSUPPORTED) it.gesturesSent else it.gesturesSent + 1,
            )
        }
        return id
    }

    private fun update(id: Long, status: FeedbackStatus, kind: GestureKind?, backend: BackendKind, msg: String, latency: Long?, points: List<NPoint>) {
        _state.update {
            if (it.lastFeedback?.gestureId != id) it
            else it.copy(lastFeedback = TouchFeedback(id, kind, backend, status, msg, latency, points), averageResponseMs = average())
        }
    }

    @Synchronized
    private fun recordLatency(ms: Long) {
        latencies.addLast(ms)
        while (latencies.size > 10) latencies.removeFirst()
    }

    @Synchronized
    private fun average(): Long? = if (latencies.isEmpty()) null else latencies.average().toLong()

    companion object { private const val TAG = "TouchControl" }
}
