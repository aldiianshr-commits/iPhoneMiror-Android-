package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.domain.model.HidOrientation
import com.aldiianshr.iphonemiror.domain.model.HidPointerMode
import com.aldiianshr.iphonemiror.domain.model.TouchBackendPref
import com.aldiianshr.iphonemiror.input.touch.WebDriverAgentClient
import com.aldiianshr.iphonemiror.ui.AppScreen
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.PrimaryButton
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.TouchViewModel

@Composable
fun TouchControlScreen(receiverVm: ReceiverViewModel, onNavigate: (AppScreen) -> Unit) {
    val vm: TouchViewModel = hiltViewModel()
    val touch by vm.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val receiver by receiverVm.state.collectAsStateWithLifecycle()
    val wdaMsg by vm.wdaMessage.collectAsStateWithLifecycle()
    val wdaBusy by vm.wdaBusy.collectAsStateWithLifecycle()
    val probes by vm.probes.collectAsStateWithLifecycle()
    val iosMajor = receiver.device?.osVersion?.substringBefore('.')?.toIntOrNull()

    Page("Kontrol Sentuh", "Sentuh layar iPhone langsung dari Xiaomi Pad 6 — lewat jalur input resmi yang benar-benar diterima iOS.") {
        Panel(Modifier.fillMaxWidth().testTag("touch_status_panel")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TouchApp, null, tint = Palette.Text, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (touch.supported) "Touch Control aktif: ${touch.activeBackend?.label}" else "Touch Control: Unsupported",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (touch.supported) Palette.Text else Palette.Bad,
                    )
                    Text(
                        touch.unsupportedReason ?: (if (touch.activeBackend?.name == "BLUETOOTH_HID") touch.hid.reason else touch.xctest.reason),
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                    )
                }
                PrimaryButton("Buka Mirroring", Icons.Outlined.TouchApp) { onNavigate(AppScreen.MIRRORING) }
            }
            Spacer(Modifier.size(12.dp))
            Text("Jalur input", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Spacer(Modifier.size(6.dp))
            Choice(TouchBackendPref.entries.map { it.label }, TouchBackendPref.entries.indexOf(s.touchBackend)) { i ->
                vm.update { it.copy(touchBackend = TouchBackendPref.entries[i]) }
            }
            Spacer(Modifier.size(8.dp))
            Toggle("Indikator sentuhan", "Lingkaran di posisi jari & warna status gesture terakhir", s.showTouchIndicators) { v ->
                vm.update { it.copy(showTouchIndicators = v) }
            }
            Toggle("Bar status sentuhan di Mirroring", "Pil kecil di pojok kiri bawah; bisa disembunyikan dari layar Mirroring", s.showTouchStatusBar) { v ->
                vm.update { it.copy(showTouchStatusBar = v) }
            }
        }

        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth(), title = "Bluetooth HID — real-time", subtitle = "Tablet menjadi pointer absolut untuk iPhone", trailing = {
                    Chip(if (touch.hid.available) "Siap" else "Tidak siap", if (touch.hid.available) Palette.Ok else Palette.Warn)
                }) {
                    Text(touch.hid.reason, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                    Spacer(Modifier.size(10.dp))
                    Check("Kontrol Bluetooth aktif di tablet", hid.registered)
                    Check("iPhone terhubung sebagai host (${hid.host ?: "—"})", hid.link.name == "CONNECTED")
                    Check(
                        "iOS 18 atau lebih baru untuk pointer absolut" + (receiver.device?.osVersion?.let { " (terdeteksi iOS $it)" } ?: " (versi belum terdeteksi)"),
                        iosMajor?.let { it >= 18 },
                    )
                    Check("AssistiveTouch aktif di iPhone (tidak dapat dideteksi dari tablet — periksa manual)", null)
                    Spacer(Modifier.size(10.dp))
                    if (!hid.registered) {
                        PrimaryButton("Aktifkan kontrol Bluetooth", Icons.Outlined.Bluetooth, enabled = hid.permissionGranted) { vm.startHid() }
                        if (!hid.permissionGranted) { Spacer(Modifier.size(6.dp)); InfoNote("Berikan izin Bluetooth di halaman Hubungkan iPhone.") }
                        Spacer(Modifier.size(10.dp))
                    }
                    Label("Format pointer")
                    Choice(HidPointerMode.entries.map { it.label }, HidPointerMode.entries.indexOf(s.hidPointerMode)) { i ->
                        vm.update { it.copy(hidPointerMode = HidPointerMode.entries[i]) }
                    }
                    Spacer(Modifier.size(4.dp))
                    InfoNote("Setelah mengganti format, lupakan tablet di iPhone (Pengaturan → Bluetooth) lalu pasangkan ulang — iOS menyimpan descriptor HID yang lama.")
                    Spacer(Modifier.size(10.dp))
                    Label("Pemetaan saat iPhone landscape")
                    Choice(HidOrientation.entries.map { it.label }, HidOrientation.entries.indexOf(s.hidOrientation)) { i ->
                        vm.update { it.copy(hidOrientation = HidOrientation.entries[i]) }
                    }
                    Spacer(Modifier.size(8.dp))
                    SecondaryButton("Uji pemetaan (pointer tanpa klik)", Icons.Outlined.GridOn, enabled = touch.hid.available) { vm.mappingTest() }
                    Spacer(Modifier.size(6.dp))
                    Toggle("Balik arah scroll 2 jari", "Jika scroll terasa terbalik (Natural Scrolling iPhone)", s.invertScroll) { v ->
                        vm.update { it.copy(invertScroll = v) }
                    }
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "XCTest / WebDriverAgent — multi-touch", subtitle = "Kerangka UI-testing resmi Apple", trailing = {
                    Chip(if (touch.xctest.available) "Terhubung" else "Tidak terhubung", if (touch.xctest.available) Palette.Ok else Palette.TextTertiary)
                }) {
                    var url by remember(s.wdaUrl, receiver.device?.address) {
                        mutableStateOf(
                            s.wdaUrl.takeIf { it.isNotBlank() }
                                ?.let { runCatching { com.aldiianshr.iphonemiror.input.touch.WebDriverAgentClient.parse(it).url }.getOrDefault(it) }
                                ?: vm.defaultWdaUrl() ?: "",
                        )
                    }
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text("URL WebDriverAgent") },
                        placeholder = { Text("http://<IP iPhone>:8100") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!touch.xctest.available) {
                            PrimaryButton(if (wdaBusy) "Mencari…" else "Cari & hubungkan otomatis", Icons.Outlined.Link, enabled = !wdaBusy,
                                modifier = Modifier.testTag("wda_auto")) {
                                vm.autoConnectWda(url)
                            }
                            SecondaryButton("Hubungkan URL ini", enabled = !wdaBusy && url.isNotBlank()) {
                                vm.update { it.copy(wdaUrl = url) }
                                vm.connectWda(url)
                            }
                        } else {
                            SecondaryButton("Putuskan", Icons.Outlined.LinkOff) { vm.disconnectWda() }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    KeyValue("Status", touch.xctest.reason)
                    KeyValue("Latensi", touch.connectionLatencyMs?.let { "$it ms (ping /status)" })
                    wdaMsg?.let { InfoNote(it) }
                    if (probes.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text("Hasil pencarian", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                        probes.forEach { pr ->
                            val ok = pr.outcome == WebDriverAgentClient.ProbeResult.Outcome.OK
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Close, null,
                                    tint = if (ok) Palette.Ok else Palette.Warn, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(pr.url, style = com.aldiianshr.iphonemiror.ui.theme.Mono, color = Palette.Text)
                                    Text(pr.detail + (pr.rttMs?.let { " ($it ms)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                                }
                            }
                        }
                    }
                    if (vm.onIphoneHotspot()) {
                        Spacer(Modifier.size(6.dp))
                        InfoNote("Tablet tersambung ke Hotspot Pribadi iPhone — alamat IPv4 iPhone 172.20.10.1 ikut dicoba otomatis.")
                    }
                    Spacer(Modifier.size(10.dp))
                    WdaGuide()
                }
                Panel(Modifier.fillMaxWidth(), title = "Dukungan gesture", subtitle = "Apa yang benar-benar dapat dikirim tiap jalur") {
                    MatrixHeader()
                    Matrix("Single tap / double tap", true, true)
                    Matrix("Long press", true, true)
                    Matrix("Swipe atas/bawah/kiri/kanan", true, true)
                    Matrix("Drag & drop", true, true)
                    Matrix("Scroll 2 jari", true, true)
                    Matrix("Pinch in / out", false, true)
                    Matrix("Gesture 3+ jari", false, true)
                    Matrix("Mengikuti jari secara langsung", true, false)
                    Matrix("Ketik teks", true, true)
                    Spacer(Modifier.size(6.dp))
                    InfoNote("HID: satu pointer yang mengikuti jari secara real-time. XCTest: gesture diputar ulang tepat (multi-touch) setelah jari diangkat. AirPlay sendiri tidak membawa input.")
                }
            },
        )
    }
}

/** Step-by-step WebDriverAgent setup, collapsible. Honest about needing a computer. */
@Composable
private fun WdaGuide() {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.testTag("wda_guide"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Panduan menjalankan WebDriverAgent", style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
            Text(if (open) "Tutup" else "Buka", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        }
        if (open) {
            Spacer(Modifier.size(8.dp))
            Text(
                "WebDriverAgent adalah aplikasi uji (XCTest) yang harus dipasang dan DIJALANKAN di iPhone dari komputer " +
                    "dengan Xcode (Mac atau Hackintosh). Komputer harus tetap menyala selama sesi, karena Xcode yang menjaga WDA tetap hidup. " +
                    "Tanpa komputer, gunakan jalur Bluetooth HID.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Warn,
            )
            Spacer(Modifier.size(8.dp))
            GUIDE_STEPS.forEachIndexed { i, (title, body) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Palette.Text, modifier = Modifier.width(22.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                }
            }
        }
    }
}

private val GUIDE_STEPS = listOf(
    "Siapkan komputer macOS + Xcode" to "Mac atau Hackintosh dengan Xcode terbaru (gratis di App Store). Masuk Apple ID di Xcode → Settings → Accounts.",
    "Aktifkan Mode Pengembang di iPhone" to "Sambungkan iPhone ke komputer dengan kabel, pilih Percayai. Lalu iPhone: Pengaturan → Privasi & Keamanan → Mode Pengembang → Aktif, restart.",
    "Unduh WebDriverAgent" to "Terminal: git clone https://github.com/appium/WebDriverAgent lalu buka WebDriverAgent.xcodeproj.",
    "Atur penandatanganan" to "Target WebDriverAgentRunner → Signing & Capabilities: pilih Team (Apple ID Anda) dan ubah Bundle Identifier menjadi unik, mis. com.namaanda.WebDriverAgentRunner.",
    "Jalankan WDA ke iPhone" to "Pilih iPhone sebagai tujuan, lalu Product → Test (⌘U). Pertama kali: iPhone → Pengaturan → Umum → VPN & Manajemen Perangkat → percayai sertifikat Anda, lalu ulangi ⌘U.",
    "Pastikan server berjalan" to "Di log Xcode muncul \"ServerURLHere->http://<IP iPhone>:8100<-ServerURLHere\". Biarkan Xcode tetap berjalan (jangan tekan Stop).",
    "Satu jaringan dengan tablet" to "Tablet dan iPhone di Wi-Fi yang sama, atau tablet tersambung ke Hotspot Pribadi iPhone (alamat iPhone 172.20.10.1).",
    "Hubungkan dari tablet" to "Tekan \"Cari & hubungkan otomatis\". Jika berhasil, status berubah \"Terhubung\" dan jalur input XCTest aktif.",
    "Catatan Apple ID gratis" to "Sertifikat berlaku 7 hari; setelah itu ulangi langkah 5. Jika Xcode dihentikan atau kabel/jaringan Xcode putus, WDA berhenti dan tablet menampilkan \"tidak ada jawaban\".",
)

@Composable
private fun Check(text: String, ok: Boolean?) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (ok) { true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.Close; null -> Icons.Outlined.TouchApp },
            null,
            tint = when (ok) { true -> Palette.Ok; false -> Palette.Bad; null -> Palette.TextTertiary },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}

@Composable
private fun MatrixHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Gesture", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        Text("HID", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        Text("XCTest", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
    }
    HorizontalDivider(color = Palette.Outline)
}

@Composable
private fun Matrix(label: String, hid: Boolean, xc: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Mark(hid, Modifier.width(56.dp))
        Mark(xc, Modifier.width(56.dp))
    }
}

@Composable
private fun Mark(ok: Boolean, modifier: Modifier) {
    Text(if (ok) "✓" else "—", modifier, color = if (ok) Palette.Ok else Palette.TextTertiary, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp),
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

@Suppress("unused")
private fun Modifier.panelBg() = background(Palette.Surface)
