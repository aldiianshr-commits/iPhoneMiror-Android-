package com.aldiianshr.iphonemiror.ui.screens

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.input.HidReports
import com.aldiianshr.iphonemiror.input.KeyMap
import com.aldiianshr.iphonemiror.input.touch.FeedbackStatus
import com.aldiianshr.iphonemiror.input.touch.NPoint
import com.aldiianshr.iphonemiror.input.touch.Rect
import com.aldiianshr.iphonemiror.input.touch.ScaleMode
import com.aldiianshr.iphonemiror.input.touch.TouchEvent
import com.aldiianshr.iphonemiror.input.touch.TouchMapper
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.formatDuration
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Mono
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.TouchViewModel
import kotlinx.coroutines.delay


fun FeedbackStatus.color(): Color = when (this) {
    FeedbackStatus.CONFIRMED, FeedbackStatus.SCREEN_RESPONSE -> Palette.Ok
    FeedbackStatus.SENT, FeedbackStatus.SENDING -> Palette.Info
    FeedbackStatus.NO_SCREEN_CHANGE -> Palette.Warn
    FeedbackStatus.UNSUPPORTED, FeedbackStatus.FAILED -> Palette.Bad
}

@Composable
fun MirroringScreen(vm: ReceiverViewModel, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val touchVm: TouchViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val touch by touchVm.state.collectAsStateWithLifecycle()
    val livePoints by touchVm.livePoints.collectAsStateWithLifecycle()
    val settings by touchVm.settings.collectAsStateWithLifecycle()

    var scaleMode by rememberSaveable { mutableStateOf(ScaleMode.ASPECT) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var toolbarHeightPx by remember { mutableStateOf(0) }

    val streaming = state.status == ReceiverStatus.MIRRORING && stats.hasVideo
    val selfTest = state.device?.model == ReceiverController.SELF_TEST_MODEL
    // The mirrored screen is directly interactive as soon as the touch link is up:
    // the finger on the tablet is the finger on the iPhone. Otherwise it is view-only.
    val touchActive = streaming && touch.supported
    LaunchedEffect(touchActive) { if (touchActive) { zoom = 1f; pan = Offset.Zero } }

    LaunchedEffect(fullscreen, chromeVisible) {
        if (fullscreen && chromeVisible) { delay(3_500); chromeVisible = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("mirroring_root")) {
        // Outside fullscreen the iPhone picture sits below the toolbar, so the top of the iPhone
        // screen (status bar, tabs) is visible and touchable instead of hidden under the tools.
        val topInset = if (fullscreen) 0.dp else with(LocalDensity.current) { toolbarHeightPx.toDp() }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = topInset).clipToBounds()) {
            val density = LocalDensity.current
            val cw = with(density) { maxWidth.toPx() }
            val ch = with(density) { maxHeight.toPx() }
            val vw = if (stats.width > 0) stats.width else 1179
            val vh = if (stats.height > 0) stats.height else 2556
            val content = TouchMapper.contentRect(cw, ch, vw, vh, scaleMode)
            val displayed = TouchMapper.displayedRect(content, zoom, pan.x, pan.y)

            // Video placed with exactly the rect the touch mapper uses.
            Box(Modifier.placeAt(displayed)) { VideoSurface(vm, Modifier.fillMaxSize()) }

            if (touchActive) {
                TouchInputLayer(displayed, touchVm::onTouch, Modifier.fillMaxSize()) { chromeVisible = true }
            } else {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, p, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 5f)
                            pan = if (zoom == 1f) Offset.Zero else pan + p
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero },
                        )
                    },
                )
            }

            if (settings.showTouchIndicators && streaming) {
                TouchIndicators(displayed, livePoints, null)
            }
        }

        if (!streaming) WaitingOverlay(state.status, state.receiverName, state.device?.displayName)

        if (selfTest && streaming) {
            Text(
                "SELF-TEST — bukan iPhone (uji jalur penerima lokal)",
                style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Palette.Warn).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(chromeVisible || !fullscreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().onSizeChanged { toolbarHeightPx = it.height }
                    .background(Color(0xCC0B0B0C)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.device?.displayName ?: "Mirroring", style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Chip(state.status.label(), state.status.color())
                        if (stats.hasVideo) Chip("${stats.width}×${stats.height} · %.0f fps".format(stats.fps), Palette.TextSecondary)
                        Chip("Koneksi: ${quality.label}", quality.color())
                    }
                }
                ToolButton(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Layar penuh", "btn_fullscreen") { onFullscreen(!fullscreen) }
                ToolButton(Icons.Outlined.AspectRatio, "Skala: ${scaleMode.label}", "btn_scale") {
                    scaleMode = ScaleMode.entries[(scaleMode.ordinal + 1) % ScaleMode.entries.size]
                }
                ToolButton(Icons.Outlined.ZoomOutMap, "Reset zoom") { zoom = 1f; pan = Offset.Zero }
                ToolButton(Icons.Outlined.Home, "Home iPhone") { touchVm.home() }
                ToolButton(Icons.Outlined.Keyboard, "Keyboard", active = showKeyboard) { showKeyboard = !showKeyboard }
                ToolButton(Icons.Outlined.TouchApp, "Status sentuhan", "btn_touch_status", active = settings.showTouchStatusBar) {
                    touchVm.update { it.copy(showTouchStatusBar = !it.showTouchStatusBar) }
                }
                ToolButton(Icons.Outlined.QueryStats, "Statistik", active = showStats) { showStats = !showStats }
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { vm.disconnect(); onFullscreen(false) },
                    enabled = state.status == ReceiverStatus.MIRRORING || state.status == ReceiverStatus.PAIRED,
                ) {
                    Icon(Icons.Outlined.Stop, null, tint = Palette.Bad)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", color = Palette.Bad)
                }
            }
        }

        // Fullscreen Touch: all touches go to the iPhone, so keep one small handle for the tools.
        if (fullscreen && !chromeVisible) {
            FilledTonalIconButton(
                onClick = { chromeVisible = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0x99141416), contentColor = Palette.Text),
            ) { Icon(Icons.Outlined.MoreHoriz, "Tampilkan kontrol") }
        }

        // Touch status: a small pill only while streaming; hidden with the rest of the chrome in
        // fullscreen, and can be switched off entirely (toolbar button / Kontrol Sentuh page).
        if (streaming && settings.showTouchStatusBar && (!fullscreen || chromeVisible)) {
            TouchStatusBar(
                touch = touch,
                onHide = { touchVm.update { it.copy(showTouchStatusBar = false) } },
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }

        if (showStats) {
            StatsPanel(stats, state.sessionStartedAt, touch, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }

        if (showKeyboard) {
            KeyboardPanel(touchVm, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp, start = 16.dp, end = 16.dp))
        }
    }
}

/** Places a child at an arbitrary rect (may exceed the parent, e.g. FILL/zoom), in px. */
private fun Modifier.placeAt(r: Rect): Modifier = layout { measurable, constraints ->
    val w = r.width.toInt().coerceAtLeast(1)
    val h = r.height.toInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.toInt(), r.top.toInt()) }
}

/**
 * Raw Android MotionEvent handling (all pointers) → mapped iPhone coordinates. Pointers
 * that start outside the mirrored picture (letterbox bars) are ignored; pointers that
 * started inside are clamped to the edge if they slide out, like a finger leaving a screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TouchInputLayer(displayed: Rect, onTouch: (TouchEvent) -> Unit, modifier: Modifier, onActivity: () -> Unit) {
    val rect by rememberUpdatedState(displayed)
    val accepted = remember { HashSet<Int>() }
    Box(
        modifier.testTag("touch_layer").pointerInteropFilter { ev ->
            val t = ev.eventTime
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = ev.actionIndex
                    val p = TouchMapper.toPhone(ev.getX(i), ev.getY(i), rect)
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) { accepted.clear(); onActivity() }
                    if (p != null) {
                        val id = ev.getPointerId(i)
                        accepted += id
                        onTouch(TouchEvent.Down(id, p, t))
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val pts = HashMap<Int, NPoint>()
                    for (i in 0 until ev.pointerCount) {
                        val id = ev.getPointerId(i)
                        if (id in accepted) TouchMapper.toPhone(ev.getX(i), ev.getY(i), rect, clamp = true)?.let { pts[id] = it }
                    }
                    if (pts.isNotEmpty()) onTouch(TouchEvent.Move(pts, t))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = ev.actionIndex
                    val id = ev.getPointerId(i)
                    if (accepted.remove(id)) {
                        TouchMapper.toPhone(ev.getX(i), ev.getY(i), rect, clamp = true)?.let { onTouch(TouchEvent.Up(id, it, t)) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (accepted.isNotEmpty()) onTouch(TouchEvent.Cancel(t))
                    accepted.clear()
                }
            }
            true
        },
    )
}

/** Local feedback only: where the finger is, and the status of the last forwarded gesture. */
@Composable
private fun TouchIndicators(displayed: Rect, live: Map<Int, NPoint>, last: com.aldiianshr.iphonemiror.input.touch.TouchFeedback?) {
    Canvas(Modifier.fillMaxSize()) {
        for (p in live.values) {
            val (x, y) = TouchMapper.toView(p, displayed)
            drawCircle(Color(0x55FFFFFF), radius = 34f, center = Offset(x, y))
            drawCircle(Color.White, radius = 34f, center = Offset(x, y), style = Stroke(width = 3f))
        }
        if (live.isEmpty() && last != null) {
            for (p in last.points) {
                val (x, y) = TouchMapper.toView(p, displayed)
                drawCircle(last.status.color(), radius = 40f, center = Offset(x, y), style = Stroke(width = 5f))
            }
        }
    }
}

@Composable
private fun TouchStatusBar(
    touch: com.aldiianshr.iphonemiror.input.touch.TouchControlState,
    onHide: () -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = touch.supported
    val label = if (active) "Kontrol langsung aktif" else "Hanya tampilan"
    val color = if (active) Palette.Ok else Palette.Warn

    Column(
        modifier.widthIn(max = 520.dp)
            .clip(RoundedCornerShape(if (expanded) 12.dp else 50.dp))
            .background(Color(0xB3141416))
            .border(1.dp, Palette.Outline, RoundedCornerShape(if (expanded) 12.dp else 50.dp))
            .testTag("touch_status"),
    ) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1)
            if (active) touch.averageResponseMs?.let {
                Text(" · respon $it ms", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, maxLines = 1)
            }
            androidx.compose.material3.IconButton(onClick = onHide, modifier = Modifier.size(26.dp).testTag("touch_status_hide")) {
                Icon(Icons.Outlined.Close, "Sembunyikan", tint = Palette.TextSecondary, modifier = Modifier.size(14.dp))
            }
        }
        if (expanded) {
            Text(
                if (active) "Sentuh dan geser layar ini seperti layar iPhone. Satu jari; pinch tidak tersedia lewat Bluetooth. " +
                    "Titik abu-abu di iPhone adalah penanda AssistiveTouch — atur Auto-Hide agar tak terlihat (lihat Kontrol Sentuh)."
                else "Sambungkan kontrol di halaman Kontrol Sentuh agar layar ini bisa disentuh. " + (touch.unsupportedReason ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun StatsPanel(
    stats: com.aldiianshr.iphonemiror.domain.model.StreamStats,
    startedAt: Long?,
    touch: com.aldiianshr.iphonemiror.input.touch.TouchControlState,
    modifier: Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE0141416))
            .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        listOf(
            "resolusi    ${if (stats.hasVideo) "${stats.width}x${stats.height}" else "-"}",
            "fps         %.1f".format(stats.fps),
            "bitrate     ${stats.bitrateKbps} kbps",
            "frame       ${stats.framesDecoded}/${stats.framesReceived}",
            "galat       ${stats.decodeErrors}",
            "decoder     ${stats.decoderName ?: "-"}${if (stats.lowLatency) " (LL)" else ""}",
            "audio       ${stats.audioCodec ?: "-"}",
            "frame akhir ${if (stats.lastFrameAgeMs >= 0) "${stats.lastFrameAgeMs} ms" else "-"}",
            "durasi      ${startedAt?.let { formatDuration(System.currentTimeMillis() - it) } ?: "-"}",
            "input       ${touch.activeBackend?.label ?: "unsupported"}",
            "gesture     ${touch.gesturesSent} dikirim",
            "respon      ${touch.averageResponseMs?.let { "$it ms" } ?: "-"}",
        ).forEach { Text(it, style = Mono, color = Palette.Text) }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, tag: String? = null, active: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 3.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) Palette.Accent else Palette.SurfaceHigher,
            contentColor = if (active) Palette.OnAccent else Palette.Text,
        ),
    ) { Icon(icon, description) }
}

@Composable
private fun VideoSurface(vm: ReceiverViewModel, modifier: Modifier) {
    val currentVm by rememberUpdatedState(vm)
    var surface by remember { mutableStateOf<Surface?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        val s = Surface(st)
                        surface = s
                        currentVm.attachSurface(s)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        surface?.let { currentVm.detachSurface(it); it.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { surface?.let { currentVm.detachSurface(it) } }
    }
}

@Composable
private fun WaitingOverlay(status: ReceiverStatus, receiverName: String, device: String?) {
    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Cast, null, tint = Palette.TextSecondary, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.size(18.dp))
            val title = when (status) {
                ReceiverStatus.STOPPED -> "Penerima mati"
                ReceiverStatus.NO_NETWORK -> "Tidak terhubung ke Wi-Fi"
                ReceiverStatus.CONNECTING, ReceiverStatus.PAIRED -> "Menghubungkan ${device ?: "iPhone"}…"
                ReceiverStatus.MIRRORING -> "Menunggu frame pertama…"
                else -> "Menunggu iPhone"
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.size(8.dp))
            Text(
                when (status) {
                    ReceiverStatus.STOPPED -> "Nyalakan penerima dari Dashboard."
                    ReceiverStatus.NO_NETWORK -> "Sambungkan tablet ke Wi-Fi yang sama dengan iPhone."
                    else -> "Di iPhone buka Pusat Kontrol → Pencerminan Layar → \"$receiverName\". Tidak ada video yang ditampilkan sampai iPhone benar-benar mengirim layar."
                },
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KeyboardPanel(input: TouchViewModel, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier.widthIn(max = 800.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Color(0xF0141416))
            .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Ketik teks untuk dikirim ke iPhone (layout US)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { input.type(text); text = "" }) { Icon(Icons.Outlined.Keyboard, "Kirim") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyChip("Esc") { input.key(KeyMap.ESCAPE) }
            KeyChip("Tab") { input.key(KeyMap.TAB) }
            KeyChip("←") { input.key(KeyMap.LEFT) }
            KeyChip("↑") { input.key(KeyMap.UP) }
            KeyChip("↓") { input.key(KeyMap.DOWN) }
            KeyChip("→") { input.key(KeyMap.RIGHT) }
            KeyChip("⌘ Space") { input.key(KeyMap.SPACE, HidReports.MOD_LGUI) }
            KeyChip("Vol −") { input.consumer(HidReports.CONSUMER_VOLUME_DOWN) }
            KeyChip("Vol +") { input.consumer(HidReports.CONSUMER_VOLUME_UP) }
            KeyChip("⏯") { input.consumer(HidReports.CONSUMER_PLAY_PAUSE) }
            FilledTonalIconButton(onClick = { input.key(KeyMap.BACKSPACE) }) { Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace") }
            FilledTonalIconButton(onClick = { input.key(KeyMap.ENTER) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Enter") }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = Palette.Text,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigher)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
