package com.aldiianshr.iphonemiror.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.model.StreamResolution
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.PrimaryButton
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.theme.Mono
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(receiverVm: ReceiverViewModel) {
    val vm: SettingsViewModel = hiltViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val selfTest by receiverVm.selfTestState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by remember(s.receiverName) { mutableStateOf(s.receiverName) }

    Page("Pengaturan", "Kualitas stream, keamanan, kontrol, dan diagnostik.") {
        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth(), title = "Penerima") {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text("Nama yang terlihat di iPhone") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    SecondaryButton("Simpan nama", enabled = name.isNotBlank() && name != s.receiverName) {
                        vm.update { it.copy(receiverName = name.trim()) }
                    }
                    Spacer(Modifier.size(14.dp))
                    Label("Resolusi maksimum yang diiklankan")
                    ChoiceRow(StreamResolution.entries.map { it.label }, StreamResolution.entries.indexOf(s.resolution)) { i ->
                        vm.update { it.copy(resolution = StreamResolution.entries[i]) }
                    }
                    Spacer(Modifier.size(10.dp))
                    Label("Frame rate maksimum")
                    ChoiceRow(listOf("30 fps", "60 fps"), if (s.maxFps >= 60) 1 else 0) { i ->
                        vm.update { it.copy(maxFps = if (i == 1) 60 else 30) }
                    }
                    Spacer(Modifier.size(8.dp))
                    InfoNote("iPhone menentukan resolusi & fps akhir; nilai di atas hanya batas atas yang diumumkan. Perubahan berlaku saat tidak ada sesi aktif.")
                    Spacer(Modifier.size(8.dp))
                    ToggleRow("Putar audio iPhone", "AAC-ELD via MediaCodec", s.audioEnabled) { v -> vm.update { it.copy(audioEnabled = v) } }
                    ToggleRow("Decoder low-latency", "Mode latensi rendah MediaCodec / Qualcomm", s.lowLatencyDecoder) { v -> vm.update { it.copy(lowLatencyDecoder = v) } }
                    ToggleRow("Buka Mirroring otomatis", "Saat stream dimulai", s.autoOpenMirroring) { v -> vm.update { it.copy(autoOpenMirroring = v) } }
                    ToggleRow("Layar tetap menyala", "Selama mirroring", s.keepScreenOn) { v -> vm.update { it.copy(keepScreenOn = v) } }
                }
                Panel(Modifier.fillMaxWidth(), title = "Keamanan") {
                    ToggleRow("Konfirmasi perangkat baru", "Pairing tidak pernah diam-diam: iPhone baru harus disetujui di tablet", s.requireApproval) { v ->
                        vm.update { it.copy(requireApproval = v) }
                    }
                    Spacer(Modifier.size(6.dp))
                    InfoNote("Stream video dienkripsi FairPlay (AES-128) dengan kunci sesi hasil pair-verify Curve25519/Ed25519. Tidak ada data dikirim ke server mana pun.")
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "Kontrol sentuh") {
                    Text(
                        "Jalur input, format pointer HID, pemetaan orientasi dan WebDriverAgent diatur di halaman Kontrol Sentuh.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Label("Kecepatan pointer mode relatif: %.2f×".format(s.pointerSpeed))
                    Slider(
                        value = s.pointerSpeed, onValueChange = { v -> vm.update { it.copy(pointerSpeed = v) } },
                        valueRange = 0.3f..3f,
                    )
                }
                Panel(Modifier.fillMaxWidth(), title = "Uji mandiri penerima", subtitle = "Verifikasi jalur Wi-Fi → dekripsi → decoder tanpa iPhone") {
                    Text(
                        "Mengirim kartu uji yang dienkode tablet ini melalui handshake AirPlay lengkap ke penerima lokal. " +
                            "Videonya diberi label \"SELF-TEST — BUKAN IPHONE\".",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    )
                    Spacer(Modifier.size(10.dp))
                    PrimaryButton(if (selfTest.running) "Berjalan…" else "Jalankan self-test (10 dtk)", Icons.Outlined.PlayCircle, enabled = !selfTest.running) {
                        receiverVm.runSelfTest()
                    }
                    selfTest.message?.let {
                        Spacer(Modifier.size(8.dp))
                        InfoNote(it, color = when (selfTest.success) { true -> Palette.Ok; false -> Palette.Bad; null -> Palette.TextSecondary })
                    }
                }
                Panel(Modifier.fillMaxWidth(), title = "Diagnostik", subtitle = "${logs.size} baris log terbaru") {
                    ToggleRow("Log debug", "Catat detail protokol (untuk melaporkan masalah)", s.debugLogging) { v -> vm.update { it.copy(debugLogging = v) } }
                    Spacer(Modifier.size(8.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp))
                            .background(Palette.Background).verticalScroll(rememberScrollState()).padding(10.dp),
                    ) {
                        if (logs.isEmpty()) Text("(kosong)", style = Mono, color = Palette.TextTertiary)
                        logs.takeLast(120).forEach { e ->
                            Text(
                                e.format(), style = Mono,
                                color = when (e.level) {
                                    AppLog.Level.ERROR -> Palette.Bad
                                    AppLog.Level.WARN -> Palette.Warn
                                    else -> Palette.TextSecondary
                                },
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Bagikan log", Icons.Outlined.Share) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "iPhoneMiror diagnostics")
                                .putExtra(Intent.EXTRA_TEXT, AppLog.dump())
                            context.startActivity(Intent.createChooser(send, "Bagikan log"))
                        }
                        SecondaryButton("Bersihkan", Icons.Outlined.BugReport) { vm.clearLogs() }
                    }
                }
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected, onClick = { onSelect(i) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Accent, selectedLabelColor = Palette.OnAccent,
                    containerColor = Palette.SurfaceHigh, labelColor = Palette.Text,
                ),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent, checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextSecondary, uncheckedTrackColor = Palette.SurfaceHigher,
                uncheckedBorderColor = Palette.OutlineStrong,
            ),
        )
    }
}
