package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.EmptyState
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.components.formatDuration
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen() {
    val vm: HistoryViewModel = hiltViewModel()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("EEE, d MMM yyyy · HH:mm", Locale("id"))
    Page("Riwayat Koneksi", "Setiap sesi AirPlay yang dimulai iPhone dicatat di tablet ini saja.", actions = {
        if (entries.isNotEmpty()) SecondaryButton("Hapus riwayat", Icons.Outlined.DeleteSweep) { vm.clear() }
    }) {
        Panel(Modifier.fillMaxWidth()) {
            if (entries.isEmpty()) {
                EmptyState(Icons.Outlined.History, "Riwayat kosong", "Sesi akan muncul di sini setelah iPhone terhubung.")
            }
            entries.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = Palette.Outline)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhoneIphone, null, tint = Palette.TextSecondary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.deviceName ?: e.model ?: e.deviceId, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                        Text(
                            listOfNotNull(e.model, e.osVersion?.let { "iOS $it" }, e.connectionType.label).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                        )
                        Text(
                            fmt.format(Date(e.startedAt)) + (e.endReason?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Chip(
                            if (e.endedAt == null) "Aktif" else if (e.mirrored) "Dicerminkan" else "Tidak streaming",
                            if (e.endedAt == null) Palette.Ok else if (e.mirrored) Palette.Info else Palette.TextTertiary,
                        )
                        e.endedAt?.let {
                            Text(formatDuration(it - e.startedAt), style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
