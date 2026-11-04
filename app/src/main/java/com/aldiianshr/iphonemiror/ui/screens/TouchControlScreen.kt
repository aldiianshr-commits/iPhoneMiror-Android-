package com.aldiianshr.iphonemiror.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhoneIphone
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

    val btPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshBluetooth() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshBluetooth() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val connected = hid.link.name == "CONNECTED"

    Page("Kontrol Sentuh", "Layar mirroring dipakai seperti layar iPhone — cukup tablet dan iPhone, lewat Bluetooth.") {
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
        }

        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth().testTag("hid_setup"), title = "Setup: tablet + iPhone saja",
                    subtitle = "Tablet menjadi pointer & keyboard Bluetooth untuk iPhone. Tanpa komputer, tanpa kabel.", trailing = {
                        Chip(if (touch.hid.available) "Siap" else "Belum siap", if (touch.hid.available) Palette.Ok else Palette.Warn)
                    }) {
                    // 1. Permission
                    Step(1, "Izin Bluetooth di tablet", hid.permissionGranted) {
                        if (!hid.permissionGranted) SecondaryButton("Berikan izin", Icons.Outlined.Bluetooth) {
                            btPermissions.launch(arrayOf(
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_CONNECT,
                                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                            ))
                        }
                    }
                    // 2. Bluetooth on
                    Step(2, "Bluetooth tablet menyala", hid.bluetoothOn) {
                        if (hid.permissionGranted && !hid.bluetoothOn) SecondaryButton("Nyalakan Bluetooth") {
                            enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    }
                    // 3. Register HID
                    Step(3, "Aktifkan kontrol di tablet", hid.registered) {
                        if (!hid.registered) PrimaryButton("Aktifkan kontrol Bluetooth", Icons.Outlined.Bluetooth,
                            enabled = hid.permissionGranted && hid.bluetoothOn, modifier = Modifier.testTag("hid_enable")) { vm.setHidEnabled(true) }
                        else SecondaryButton("Matikan kontrol") { vm.setHidEnabled(false) }
                    }
                    // 4. Pair / connect iPhone
                    Step(4, if (connected) "iPhone tersambung: ${hid.host}" else "Sambungkan iPhone", connected) {
                        if (hid.registered && !connected) {
                            Text(
                                "Di iPhone buka Pengaturan → Bluetooth (biarkan halaman itu terbuka), lalu cari dari tablet dan ketuk nama iPhone. " +
                                    "Konfirmasi kode yang sama di kedua perangkat.",
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                            Spacer(Modifier.size(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton(if (hid.discovering) "Mencari…" else "Cari iPhone", Icons.Outlined.BluetoothSearching,
                                    enabled = !hid.discovering, modifier = Modifier.testTag("hid_search")) { vm.searchIphone() }
                                SecondaryButton("Buat tablet terlihat") {
                                    discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                                }
                            }
                            hid.pairingMessage?.let { Spacer(Modifier.size(6.dp)); InfoNote(it) }
                            if (hid.nearby.isNotEmpty()) {
                                Spacer(Modifier.size(6.dp))
                                hid.nearby.take(8).forEach { d ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (d.isPhone) Icons.Outlined.PhoneIphone else Icons.Outlined.Bluetooth, null,
                                            tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(d.name ?: d.address, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                                            Text(listOfNotNull(if (d.bonded) "sudah dipasangkan" else null, d.rssi?.let { "$it dBm" }).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                                        }
                                        SecondaryButton(if (d.bonded) "Sambungkan" else "Pasangkan",
                                            enabled = hid.pairingAddress == null) { vm.pair(d.address) }
                                    }
                                }
                            } else if (hid.discovering) {
                                Spacer(Modifier.size(4.dp))
                                Text("Mencari… pastikan halaman Bluetooth iPhone sedang terbuka.", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                            }
                            if (s.hidHostAddress.isNotBlank()) {
                                Spacer(Modifier.size(6.dp))
                                Text("Tersambung ulang otomatis ke ${s.hidHostName.ifBlank { s.hidHostAddress }} saat terjangkau.",
                                    style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                            }
                        } else if (connected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton("Putuskan") { vm.disconnectHid() }
                                SecondaryButton("Lupakan iPhone ini") { vm.forgetIphone() }
                            }
                        }
                    }
                    // 5. AssistiveTouch
                    Step(5, "Nyalakan AssistiveTouch di iPhone", null) {
                        Text(
                            "iPhone: Pengaturan → Aksesibilitas → Sentuh → AssistiveTouch → Aktif. Tanpa ini pointer bergerak tetapi ketukan tidak bekerja. " +
                                "Tablet tidak bisa mendeteksi pengaturan ini — gunakan uji di langkah 6.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        )
                    }
                    // 6. Verify
                    Step(6, "Uji: pointer harus muncul di layar mirroring", null) {
                        Text(
                            "Mirror iPhone ke tablet (Pusat Kontrol → Pencerminan Layar), lalu tekan Uji pemetaan: lingkaran pointer bergerak ke tengah dan ke empat sudut tanpa mengetuk.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        )
                        Spacer(Modifier.size(6.dp))
                        SecondaryButton("Uji pemetaan", Icons.Outlined.GridOn, enabled = touch.hid.available) { vm.mappingTest() }
                    }
                    Spacer(Modifier.size(6.dp))
                    Check(
                        "iOS 18+ untuk pointer absolut" + (receiver.device?.osVersion?.let { " — iPhone Anda iOS $it" } ?: ""),
                        iosMajor?.let { it >= 18 },
                    )
                    hid.error?.let { Spacer(Modifier.size(6.dp)); InfoNote(it, color = Palette.Warn) }
                    if (!hid.supported) InfoNote("Sistem tablet ini tidak menyediakan profil Bluetooth HID Device, jadi kontrol sentuh tidak tersedia.", color = Palette.Bad)
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "Pengaturan sentuhan") {
                    Toggle("Tampilkan titik sentuhan", "Titik kecil di posisi jari (bantu melihat posisi)", s.showTouchIndicators) { v ->
                        vm.update { it.copy(showTouchIndicators = v) }
                    }
                    Toggle("Bar status sentuhan di Mirroring", "Pil kecil di pojok kiri bawah; bisa disembunyikan dari layar Mirroring", s.showTouchStatusBar) { v ->
                        vm.update { it.copy(showTouchStatusBar = v) }
                    }
                                        Spacer(Modifier.size(6.dp))
                    Label("Format pointer")
                    Choice(HidPointerMode.entries.map { it.label }, HidPointerMode.entries.indexOf(s.hidPointerMode)) { i ->
                        vm.update { it.copy(hidPointerMode = HidPointerMode.entries[i]) }
                    }
                    Spacer(Modifier.size(4.dp))
                    InfoNote("Absolut = ketukan tepat di posisi jari (iOS 18+). Setelah mengganti format, lupakan tablet di iPhone (Pengaturan → Bluetooth) lalu pasangkan ulang.")
                    Spacer(Modifier.size(10.dp))
                    Label("Pemetaan saat iPhone landscape")
                    Choice(HidOrientation.entries.map { it.label }, HidOrientation.entries.indexOf(s.hidOrientation)) { i ->
                        vm.update { it.copy(hidOrientation = HidOrientation.entries[i]) }
                    }
                }
                Panel(Modifier.fillMaxWidth(), title = "Cara pakai") {
                    Text(
                        "Setelah langkah di kiri selesai dan layar iPhone muncul di Mirroring, layar itu langsung bisa disentuh: " +
                            "ketuk, tahan, geser, dan seret persis seperti di layar iPhone. Tidak ada mode atau gesture khusus.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Text,
                    )
                    Spacer(Modifier.size(8.dp))
                    InfoNote("iOS menerima satu jari dari perangkat Bluetooth, jadi jari kedua diabaikan dan pinch tidak tersedia. Untuk zoom, ketuk dua kali seperti biasa di iPhone.")
                }
                Panel(Modifier.fillMaxWidth(), title = "Opsi lanjutan") {
                    Toggle("XCTest / WebDriverAgent (butuh komputer macOS)", "Multi-touch & pinch, tetapi memerlukan komputer dengan Xcode yang terus berjalan",
                        s.showAdvancedTouch) { v -> vm.update { it.copy(showAdvancedTouch = v, touchBackend = if (v) it.touchBackend else TouchBackendPref.AUTO) } }
                }
                if (s.showAdvancedTouch) {
                    Panel(Modifier.fillMaxWidth(), title = "XCTest / WebDriverAgent — multi-touch", subtitle = "Butuh komputer macOS + Xcode", trailing = {
                        Chip(if (touch.xctest.available) "Terhubung" else "Tidak terhubung", if (touch.xctest.available) Palette.Ok else Palette.TextTertiary)
                    }) {
                        Label("Jalur input")
                        Choice(TouchBackendPref.entries.map { it.label }, TouchBackendPref.entries.indexOf(s.touchBackend)) { i ->
                            vm.update { it.copy(touchBackend = TouchBackendPref.entries[i]) }
                        }
                        Spacer(Modifier.size(8.dp))
                        WdaSection(vm, s.wdaUrl, receiver.device?.address, touch, wdaBusy, wdaMsg, probes)
                    }
                }
            },
        )
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(
                when (done) { true -> Palette.Ok; else -> Palette.SurfaceHigher },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done == true) Icon(Icons.Outlined.Check, null, tint = Palette.OnAccent, modifier = Modifier.size(16.dp))
            else Text("$n", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (done == true) Palette.TextSecondary else Palette.Text)
            content()
        }
    }
}

@Composable
private fun Matrix1(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
        Mark(ok, Modifier.width(40.dp))
    }
}

@Composable
private fun WdaSection(
    vm: TouchViewModel,
    savedUrl: String,
    deviceAddress: String?,
    touch: com.aldiianshr.iphonemiror.input.touch.TouchControlState,
    wdaBusy: Boolean,
    wdaMsg: String?,
    probes: List<WebDriverAgentClient.ProbeResult>,
) {
    var url by remember(savedUrl, deviceAddress) {
        mutableStateOf(
            savedUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { WebDriverAgentClient.parse(it).url }.getOrDefault(it) }
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
                modifier = Modifier.testTag("wda_auto")) { vm.autoConnectWda(url) }
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
    Spacer(Modifier.size(10.dp))
    WdaGuide()
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
