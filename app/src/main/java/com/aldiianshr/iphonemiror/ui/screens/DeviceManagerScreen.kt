package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.domain.model.AppleModels
import com.aldiianshr.iphonemiror.domain.model.CompatibilityChecker
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.EmptyState
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.SettingsViewModel

@Composable
fun DeviceManagerScreen(receiverVm: ReceiverViewModel) {
    val settingsVm: SettingsViewModel = hiltViewModel()
    val state by receiverVm.state.collectAsStateWithLifecycle()
    val known by settingsVm.knownDevices.collectAsStateWithLifecycle()

    Page("Device Manager", "Perangkat aktif dan perangkat yang pernah terhubung.") {
        Panel(Modifier.fillMaxWidth(), title = "Perangkat aktif", trailing = { Chip(state.status.label(), state.status.color()) }) {
            val d = state.device
            if (d == null) {
                EmptyState(Icons.Outlined.PhoneIphone, "Tidak ada perangkat aktif", "Mulai Pencerminan Layar dari iPhone untuk melihat detailnya di sini.")
            } else {
                KeyValue("Nama perangkat", d.name)
                KeyValue("Model", d.marketingName ?: d.model)
                KeyValue("Identifier", d.model, mono = true)
                KeyValue("Sistem", d.osName)
                KeyValue("Versi iOS", d.osVersion)
                KeyValue("Build", d.osBuild, mono = true)
                KeyValue("Tipe koneksi", d.connectionType.label)
                KeyValue("Alamat", d.address, mono = true)
                KeyValue("Device ID", d.id, mono = true)
                KeyValue("Status", state.status.label())
                KeyValue("Pairing", if (state.pairVerified) "Terverifikasi" else "Belum")
                KeyValue("Enkripsi stream", if (state.encrypted) "FairPlay (AES-128 CTR/CBC)" else null)
                Spacer(Modifier.size(8.dp))
                InfoNote(CompatibilityChecker.evaluate(d.osVersion).message)
                Spacer(Modifier.size(10.dp))
                SecondaryButton(
                    "Disconnect", Icons.Outlined.LinkOff,
                    enabled = state.status == ReceiverStatus.MIRRORING || state.status == ReceiverStatus.PAIRED,
                ) { receiverVm.disconnect() }
            }
        }

        Panel(Modifier.fillMaxWidth(), title = "Perangkat dikenal", subtitle = "Izinkan untuk melewati konfirmasi, blokir untuk menolak otomatis.") {
            if (known.isEmpty()) {
                Text("Belum ada perangkat yang tercatat.", color = Palette.TextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            known.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = Palette.Outline)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhoneIphone, null, tint = Palette.TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(k.name ?: k.model ?: k.deviceId, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(k.model?.let { AppleModels.marketingName(it) ?: it }, k.osVersion?.let { "iOS $it" }, "terakhir ${relativeTime(k.lastSeen)}")
                                .joinToString(" · "),
                            color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Chip(
                        when { k.allowed -> "Diizinkan"; k.blocked -> "Diblokir"; else -> "Tanya" },
                        when { k.allowed -> Palette.Ok; k.blocked -> Palette.Bad; else -> Palette.TextTertiary },
                    )
                    IconButton(onClick = { settingsVm.setAllowed(k.deviceId, !k.allowed) }) {
                        Icon(Icons.Outlined.CheckCircle, "Izinkan", tint = if (k.allowed) Palette.Ok else Palette.TextSecondary)
                    }
                    IconButton(onClick = { settingsVm.setBlocked(k.deviceId, !k.blocked) }) {
                        Icon(Icons.Outlined.Block, "Blokir", tint = if (k.blocked) Palette.Bad else Palette.TextSecondary)
                    }
                    IconButton(onClick = { settingsVm.forget(k.deviceId) }) {
                        Icon(Icons.Outlined.Delete, "Lupakan", tint = Palette.TextSecondary)
                    }
                }
            }
        }
    }
}
