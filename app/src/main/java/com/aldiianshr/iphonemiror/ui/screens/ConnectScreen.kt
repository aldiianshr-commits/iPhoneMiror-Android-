package com.aldiianshr.iphonemiror.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.discovery.BluetoothAppleScanner
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.input.BluetoothHidController
import com.aldiianshr.iphonemiror.ui.AppScreen
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.PrimaryButton
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ConnectViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel

@Composable
fun ConnectScreen(receiverVm: ReceiverViewModel, onNavigate: (AppScreen) -> Unit) {
    val vm: ConnectViewModel = hiltViewModel()
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    Page("Hubungkan iPhone", "Wi-Fi untuk layar, Bluetooth untuk kontrol, USB untuk identifikasi perangkat.") {
        ResponsiveColumns(
            left = {
                WifiPanel(receiverVm, vm)
                UsbPanel(vm)
            },
            right = {
                BluetoothPanel(vm)
                HidPanel(vm, onNavigate)
            },
        )
    }
}

@Composable
private fun WifiPanel(receiverVm: ReceiverViewModel, vm: ConnectViewModel) {
    val state by receiverVm.state.collectAsStateWithLifecycle()
    val found by vm.networkDevices.collectAsStateWithLifecycle()
    val browsing by vm.browsing.collectAsStateWithLifecycle()
    val running = state.status != ReceiverStatus.STOPPED && state.status != ReceiverStatus.ERROR
    Panel(Modifier.fillMaxWidth(), title = "Wi-Fi · AirPlay", subtitle = "Jalur video & audio", trailing = {
        Chip(state.status.label(), state.status.color())
    }) {
        KeyValue("Nama penerima", state.receiverName.ifBlank { null })
        KeyValue("Alamat IP", state.ipAddresses.joinToString().ifEmpty { null }, mono = true)
        KeyValue("Port kontrol", state.port.takeIf { it > 0 }?.toString(), mono = true)
        KeyValue("Pairing", when {
            state.pairVerified -> "Terverifikasi"
            state.status == ReceiverStatus.CONNECTING -> "Sedang berlangsung"
            else -> "Belum ada"
        })
        Spacer(Modifier.size(10.dp))
        if (!running) {
            PrimaryButton("Nyalakan penerima", Icons.Outlined.PowerSettingsNew, modifier = Modifier.fillMaxWidth()) { receiverVm.startReceiver() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step(1, "Sambungkan iPhone ke Wi-Fi yang sama dengan tablet.")
                Step(2, "Buka Pusat Kontrol (geser dari pojok kanan atas).")
                Step(3, "Ketuk Pencerminan Layar, lalu pilih \"${state.receiverName}\".")
                Step(4, "Setujui permintaan di tablet bila diminta.")
            }
        }
        Spacer(Modifier.size(14.dp))
        HorizontalDivider(color = Palette.Outline)
        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Perangkat Apple di jaringan", style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
            if (browsing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.TextSecondary)
        }
        Spacer(Modifier.size(6.dp))
        if (found.isEmpty()) {
            Text("Belum terdeteksi. iPhone biasanya mengumumkan dirinya saat layar menyala.", color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
        } else {
            found.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhoneIphone, null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.serviceName, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
                        Text(d.serviceTypes.joinToString(), color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        InfoNote("Mirroring selalu dimulai dari iPhone. Daftar di atas hanya menunjukkan perangkat Apple yang terlihat di Wi-Fi ini.")
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$n", style = MaterialTheme.typography.labelLarge, color = Palette.Text, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}

@Composable
private fun UsbPanel(vm: ConnectViewModel) {
    val usb by vm.usbStatus.collectAsStateWithLifecycle()
    Panel(Modifier.fillMaxWidth(), title = "USB OTG", subtitle = "Deteksi & identifikasi", trailing = {
        SecondaryButton("Pindai", Icons.Outlined.Refresh) { vm.refreshUsb() }
    }) {
        KeyValue("USB Host", if (usb.hostSupported) "Didukung" else "Tidak didukung")
        if (usb.devices.isEmpty()) {
            Text("Tidak ada perangkat Apple terpasang di port USB.", color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        usb.devices.forEach { d ->
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Usb, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(d.product ?: d.kind, style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
                Chip(if (d.hasPermission) "Diizinkan" else "Perlu izin", if (d.hasPermission) Palette.Ok else Palette.Warn)
            }
            KeyValue("Produsen", d.manufacturer)
            KeyValue("Vendor/Product", "0x%04X / 0x%04X".format(d.vendorId, d.productId), mono = true)
            KeyValue("UDID / Serial", d.serial, mono = true)
            KeyValue("Antarmuka usbmux", if (d.hasUsbmuxInterface) "Ada" else "Tidak terlihat")
            if (!d.hasPermission) {
                Spacer(Modifier.size(6.dp))
                PrimaryButton("Minta izin USB", Icons.Outlined.Lock, modifier = Modifier.fillMaxWidth()) { vm.requestUsbPermission(d.deviceName) }
            }
        }
        usb.lastError?.let { Spacer(Modifier.size(6.dp)); InfoNote(it, color = Palette.Warn) }
        Spacer(Modifier.size(8.dp))
        InfoNote(
            "Mirroring lewat USB belum tersedia: Apple tidak menyediakan protokol layar USB publik untuk Android. " +
                "USB saat ini dipakai untuk mendeteksi dan mengidentifikasi iPhone. Lihat docs/CONNECTION.md.",
        )
    }
}

@Composable
private fun BluetoothPanel(vm: ConnectViewModel) {
    val bt by vm.bleStatus.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshBluetooth() }
    Panel(Modifier.fillMaxWidth(), title = "Bluetooth", subtitle = "Discovery perangkat Apple (BLE)", trailing = {
        Chip(if (!bt.supported) "Tidak ada" else if (bt.enabled) "Aktif" else "Mati", if (bt.enabled) Palette.Ok else Palette.Warn)
    }) {
        if (!bt.permissionGranted) {
            InfoNote("Izin \"Perangkat di sekitar\" dibutuhkan untuk memindai dan memakai Bluetooth. Tidak memakai lokasi.")
            Spacer(Modifier.size(8.dp))
            PrimaryButton("Berikan izin Bluetooth", Icons.Outlined.Bluetooth, modifier = Modifier.fillMaxWidth()) {
                permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if (bt.scanning) "Memindai…" else "Pindai Apple di sekitar", Icons.Outlined.BluetoothSearching, enabled = !bt.scanning && bt.enabled) { vm.scanBluetooth() }
            }
            Spacer(Modifier.size(8.dp))
            if (bt.nearby.isEmpty()) {
                Text(if (bt.scanning) "Mencari iklan BLE Apple (ID 0x004C)…" else "Belum ada hasil.", color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
            bt.nearby.take(8).forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.name ?: "Perangkat Apple", color = Palette.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(BluetoothAppleScanner.continuityLabel(n.continuityType), color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    Text("${n.rssi} dBm", color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        bt.error?.let { Spacer(Modifier.size(6.dp)); InfoNote(it, color = Palette.Warn) }
        Spacer(Modifier.size(8.dp))
        InfoNote("Alamat BLE iPhone diacak demi privasi, jadi hasil pindaian tidak dapat dikaitkan ke iPhone tertentu. Bluetooth tidak dipakai untuk video.")
    }
}

@Composable
private fun HidPanel(vm: ConnectViewModel, onNavigate: (AppScreen) -> Unit) {
    val hid by vm.hidState.collectAsStateWithLifecycle()
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    Panel(Modifier.fillMaxWidth(), title = "Kontrol (Bluetooth HID)", subtitle = "Tablet sebagai keyboard & mouse untuk iPhone", trailing = {
        Chip(
            when (hid.link) {
                BluetoothHidController.Link.CONNECTED -> "Terhubung"
                BluetoothHidController.Link.CONNECTING -> "Menghubungkan"
                else -> if (hid.registered) "Siap" else "Nonaktif"
            },
            when (hid.link) {
                BluetoothHidController.Link.CONNECTED -> Palette.Ok
                BluetoothHidController.Link.CONNECTING -> Palette.Info
                else -> Palette.TextTertiary
            },
        )
    }) {
        KeyValue("Profil HID", if (!hid.supported) "Tidak didukung" else if (hid.registered) "Terdaftar" else "Belum aktif")
        KeyValue("Host", hid.host)
        Spacer(Modifier.size(8.dp))
        if (!hid.registered) {
            PrimaryButton("Aktifkan kontrol Bluetooth", Icons.Outlined.Keyboard, enabled = hid.permissionGranted, modifier = Modifier.fillMaxWidth()) { vm.startHid() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Buat terlihat (120 dtk)", Icons.Outlined.Bluetooth) {
                    discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                }
                SecondaryButton("Matikan") { vm.stopHid() }
            }
            if (hid.link != BluetoothHidController.Link.CONNECTED && hid.bondedHosts.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Text("Perangkat yang sudah dipasangkan", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                hid.bondedHosts.forEach { (name, address) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Palette.TextSecondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        SecondaryButton("Hubungkan") { vm.connectHid(address) }
                    }
                }
            }
            if (hid.link == BluetoothHidController.Link.CONNECTED) {
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Kontrol di layar Mirroring") { onNavigate(AppScreen.MIRRORING) }
                    SecondaryButton("Putuskan") { vm.disconnectHid() }
                }
            }
        }
        if (!hid.permissionGranted) { Spacer(Modifier.size(6.dp)); InfoNote("Berikan izin Bluetooth di panel Bluetooth terlebih dahulu.") }
        hid.error?.let { Spacer(Modifier.size(6.dp)); InfoNote(it, color = Palette.Warn) }
        Spacer(Modifier.size(8.dp))
        InfoNote(
            "AirPlay hanya mengirim gambar; ia tidak menyediakan kontrol balik. Kontrol dilakukan dengan cara resmi: " +
                "pasangkan tablet di iPhone (Pengaturan → Bluetooth), lalu aktifkan AssistiveTouch " +
                "(Pengaturan → Aksesibilitas → Sentuh → AssistiveTouch) agar pointer mouse muncul. Keyboard bekerja tanpa AssistiveTouch.",
        )
    }
}
