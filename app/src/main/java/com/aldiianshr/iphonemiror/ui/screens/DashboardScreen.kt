package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.domain.model.CompatibilityChecker
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.ui.AppScreen
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.EmptyState
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.PrimaryButton
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel

@Composable
fun DashboardScreen(vm: ReceiverViewModel, onNavigate: (AppScreen) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val adv by vm.advertiserStatus.collectAsStateWithLifecycle()
    val running = state.status != ReceiverStatus.STOPPED && state.status != ReceiverStatus.ERROR

    Page("Dashboard", "Cerminkan layar iPhone ke tablet melalui AirPlay di jaringan Wi-Fi yang sama.") {
        // Hero
        Panel(Modifier.fillMaxWidth().testTag("dashboard_hero")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigher),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Cast, null, tint = Palette.Text, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) state.receiverName else "Penerima belum aktif",
                        style = MaterialTheme.typography.headlineSmall, color = Palette.Text,
                    )
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(state.status.label(), state.status.color())
                        if (running) Chip(
                            when (adv.state) {
                                com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser.State.REGISTERED -> "Terlihat di jaringan"
                                com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser.State.REGISTERING -> "Mengiklankan…"
                                com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser.State.FAILED -> "mDNS gagal"
                                else -> "Tidak diiklankan"
                            },
                            when (adv.state) {
                                com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser.State.REGISTERED -> Palette.Ok
                                com.aldiianshr.iphonemiror.discovery.AirPlayAdvertiser.State.FAILED -> Palette.Bad
                                else -> Palette.Warn
                            },
                        )
                    }
                }
                if (running) {
                    SecondaryButton("Matikan", Icons.Outlined.PowerSettingsNew) { vm.stopReceiver() }
                } else {
                    PrimaryButton("Nyalakan penerima", Icons.Outlined.PowerSettingsNew, modifier = Modifier.testTag("start_receiver")) { vm.startReceiver() }
                }
            }
            Spacer(Modifier.size(16.dp))
            HorizontalDivider(color = Palette.Outline)
            Spacer(Modifier.size(12.dp))
            if (running) {
                InfoNote(
                    "Di iPhone: buka Pusat Kontrol → Pencerminan Layar → pilih \"${state.receiverName}\". " +
                        "iPhone dan tablet harus berada di Wi-Fi yang sama.",
                    icon = Icons.Outlined.Wifi,
                )
                Spacer(Modifier.size(8.dp))
                KeyValue("Alamat", state.ipAddresses.joinToString { "$it:${state.port}" }.ifEmpty { null }, mono = true)
            } else {
                InfoNote("Nyalakan penerima agar tablet muncul sebagai tujuan AirPlay di iPhone.", icon = Icons.Outlined.Wifi)
            }
            state.lastError?.let {
                Spacer(Modifier.size(8.dp))
                InfoNote("Terakhir: $it", color = Palette.Warn)
            }
        }

        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth(), title = "iPhone terhubung") {
                    val d = state.device
                    if (d == null) {
                        EmptyState(Icons.Outlined.PhoneIphone, "Belum ada iPhone", "Data perangkat muncul setelah iPhone memulai sesi.")
                    } else {
                        KeyValue("Nama", d.name)
                        KeyValue("Model", d.marketingName?.let { "$it (${d.model})" } ?: d.model)
                        KeyValue("Versi iOS", d.osVersion?.let { v -> listOfNotNull(v, d.osBuild?.let { "($it)" }).joinToString(" ") })
                        KeyValue("Koneksi", d.connectionType.label)
                        KeyValue("Status", state.status.label())
                        KeyValue("Pairing", if (state.pairVerified) "Terverifikasi (Ed25519/Curve25519)" else "Belum")
                        Spacer(Modifier.size(6.dp))
                        InfoNote(CompatibilityChecker.evaluate(d.osVersion).message)
                    }
                    Spacer(Modifier.size(12.dp))
                    PrimaryButton(
                        "Buka Mirroring", Icons.Outlined.PlayArrow,
                        enabled = state.status == ReceiverStatus.MIRRORING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { onNavigate(AppScreen.MIRRORING) }
                    if (state.status == ReceiverStatus.MIRRORING || state.status == ReceiverStatus.PAIRED) {
                        Spacer(Modifier.size(8.dp))
                        SecondaryButton("Putuskan", Icons.Outlined.Stop, modifier = Modifier.fillMaxWidth()) { vm.disconnect() }
                    }
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "Stream", subtitle = "Diukur dari data yang benar-benar diterima") {
                    KeyValue("Resolusi", if (stats.hasVideo) "${stats.width} × ${stats.height}" else null)
                    KeyValue("Frame rate", if (stats.hasVideo) "%.1f fps".format(stats.fps) else null)
                    KeyValue("Bitrate", if (stats.hasVideo) "${stats.bitrateKbps} kbps" else null)
                    KeyValue("Decoder", stats.decoderName)
                    KeyValue("Audio", stats.audioCodec?.let { if (stats.audioActive) "$it (aktif)" else it })
                    KeyValue("Kualitas", quality.label)
                }
                Panel(Modifier.fillMaxWidth(), title = "Perangkat terakhir", trailing = {
                    SecondaryButton("Riwayat", Icons.Outlined.History) { onNavigate(AppScreen.HISTORY) }
                }) {
                    if (recent.isEmpty()) {
                        Text("Belum ada koneksi tercatat.", color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        recent.forEach { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.PhoneIphone, null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.deviceName ?: e.model ?: e.deviceId, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(e.osVersion?.let { "iOS $it" }, e.connectionType.label).joinToString(" · "),
                                        color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(relativeTime(e.startedAt), color = Palette.TextTertiary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
        )
    }
}
