package com.aldiianshr.iphonemiror.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.connection.ReceiverController
import com.aldiianshr.iphonemiror.domain.model.InputMode
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.input.BluetoothHidController
import com.aldiianshr.iphonemiror.input.HidReports
import com.aldiianshr.iphonemiror.input.KeyMap
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.formatDuration
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Mono
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.InputViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import kotlinx.coroutines.delay

private enum class AspectMode(val label: String) { FIT("Pas"), FILL("Penuh"), STRETCH("Regang") }

@Composable
fun MirroringScreen(vm: ReceiverViewModel, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val input: InputViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val hid by input.hidState.collectAsStateWithLifecycle()
    val settings by input.settings.collectAsStateWithLifecycle()

    var aspect by rememberSaveable { mutableStateOf(AspectMode.FIT) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var controlEnabled by rememberSaveable { mutableStateOf(true) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val streaming = state.status == ReceiverStatus.MIRRORING && stats.hasVideo
    val selfTest = state.device?.model == ReceiverController.SELF_TEST_MODEL
    val canControl = hid.link == BluetoothHidController.Link.CONNECTED && controlEnabled

    LaunchedEffect(fullscreen, chromeVisible) {
        if (fullscreen && chromeVisible) { delay(3_500); chromeVisible = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("mirroring_root")) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val videoAspect = if (stats.width > 0 && stats.height > 0) stats.width.toFloat() / stats.height else 9f / 19.5f
            val containerAspect = maxWidth.value / maxHeight.value
            val videoModifier = when (aspect) {
                AspectMode.FIT -> Modifier.aspectRatio(videoAspect, matchHeightConstraintsFirst = videoAspect < containerAspect)
                AspectMode.STRETCH -> Modifier.fillMaxSize()
                AspectMode.FILL -> Modifier.aspectRatio(videoAspect, matchHeightConstraintsFirst = videoAspect >= containerAspect)
            }
            Box(
                videoModifier
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            ) {
                VideoSurface(vm, Modifier.fillMaxSize())
                if (canControl && streaming) {
                    ControlOverlay(input, settings.inputMode, Modifier.fillMaxSize()) { chromeVisible = true }
                } else {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { chromeVisible = !chromeVisible },
                                onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero },
                            )
                        },
                    )
                }
            }
        }

        if (!streaming) WaitingOverlay(state.status, state.receiverName, state.device?.displayName)

        if (selfTest && streaming) {
            Text(
                "SELF-TEST — bukan iPhone (uji jalur penerima lokal)",
                style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Palette.Warn).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(chromeVisible || !fullscreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xCC0B0B0C)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.device?.displayName ?: "Mirroring", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Chip(state.status.label(), state.status.color())
                        if (stats.hasVideo) Chip("${stats.width}×${stats.height} · %.0f fps".format(stats.fps), Palette.TextSecondary)
                        Chip("Koneksi: ${quality.label}", quality.color())
                        if (hid.link == BluetoothHidController.Link.CONNECTED) Chip("Kontrol BT", if (controlEnabled) Palette.Ok else Palette.TextTertiary)
                    }
                }
                ToolButton(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Layar penuh", "btn_fullscreen") { onFullscreen(!fullscreen) }
                ToolButton(Icons.Outlined.AspectRatio, "Rasio: ${aspect.label}") { aspect = AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size] }
                ToolButton(Icons.Outlined.ZoomOutMap, "Reset zoom") { scale = 1f; offset = Offset.Zero }
                if (hid.link == BluetoothHidController.Link.CONNECTED) {
                    ToolButton(Icons.Outlined.Mouse, if (controlEnabled) "Kontrol aktif" else "Kontrol mati", active = controlEnabled) { controlEnabled = !controlEnabled }
                    ToolButton(Icons.Outlined.Keyboard, "Keyboard", active = showKeyboard) { showKeyboard = !showKeyboard }
                }
                ToolButton(Icons.Outlined.QueryStats, "Statistik", active = showStats) { showStats = !showStats }
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { vm.disconnect(); onFullscreen(false) },
                    enabled = state.status == ReceiverStatus.MIRRORING || state.status == ReceiverStatus.PAIRED,
                ) {
                    Icon(Icons.Outlined.Stop, null, tint = Palette.Bad)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Mirroring", color = Palette.Bad)
                }
            }
        }

        if (showStats) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(16.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xE0141416))
                    .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                val lines = listOf(
                    "resolusi   ${if (stats.hasVideo) "${stats.width}x${stats.height}" else "-"}",
                    "fps        %.1f".format(stats.fps),
                    "bitrate    ${stats.bitrateKbps} kbps",
                    "frame      ${stats.framesDecoded} dekode / ${stats.framesReceived} diterima",
                    "galat      ${stats.decodeErrors}",
                    "decoder    ${stats.decoderName ?: "-"}${if (stats.lowLatency) " (low-latency)" else ""}",
                    "audio      ${stats.audioCodec ?: "-"}${if (stats.audioActive) " aktif" else ""}",
                    "frame akhir ${if (stats.lastFrameAgeMs >= 0) "${stats.lastFrameAgeMs} ms lalu" else "-"}",
                    "durasi     ${state.sessionStartedAt?.let { formatDuration(System.currentTimeMillis() - it) } ?: "-"}",
                )
                lines.forEach { Text(it, style = Mono, color = Palette.Text) }
            }
        }

        if (showKeyboard && canControl) {
            KeyboardPanel(input, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
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

/**
 * Touch → Bluetooth HID mouse. Trackpad mode: drag moves the pointer, tap = click, two-finger
 * drag = scroll, two-finger tap = secondary click, hold-then-drag = drag. Direct mode taps at
 * the touched position (experimental, depends on calibration).
 */
@Composable
private fun ControlOverlay(input: InputViewModel, mode: InputMode, modifier: Modifier, onActivity: () -> Unit) {
    Box(
        modifier.pointerInput(mode) {
            awaitEachGesture {
                val down = awaitFirstDown()
                onActivity()
                var moved = false
                var maxPointers = 1
                var dragging = false
                var lastTwoY: Float? = null
                val slop = viewConfiguration.touchSlop
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    maxPointers = maxOf(maxPointers, pressed.size)
                    if (pressed.size >= 2) {
                        val y = pressed.map { it.position.y }.average().toFloat()
                        lastTwoY?.let { prev -> val dy = y - prev; if (kotlin.math.abs(dy) > 0.5f) { input.scroll(-dy / 12f); moved = true } }
                        lastTwoY = y
                    } else if (pressed.size == 1 && mode == InputMode.TRACKPAD) {
                        val c = pressed[0]
                        if (!moved && (c.position - down.position).getDistance() > slop) {
                            moved = true
                            if (c.uptimeMillis - down.uptimeMillis > 450) { dragging = true; input.press() }
                        }
                        if (moved) {
                            val d = c.position - c.previousPosition
                            input.move(d.x * 0.6f, d.y * 0.6f)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                when {
                    dragging -> input.release()
                    moved -> Unit
                    maxPointers >= 2 -> input.rightClick()
                    mode == InputMode.DIRECT -> input.tapAt(down.position.x / size.width, down.position.y / size.height)
                    else -> input.click()
                }
            }
        },
    )
}

@Composable
private fun KeyboardPanel(input: InputViewModel, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier.widthIn(max = 760.dp).fillMaxWidth()
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
            KeyChip("⌘ H") { input.key(KeyMap.KEY_H, HidReports.MOD_LGUI) }
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
