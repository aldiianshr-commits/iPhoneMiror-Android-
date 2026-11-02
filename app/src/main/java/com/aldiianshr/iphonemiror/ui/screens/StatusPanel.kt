package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.formatDuration
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel

/** Right-hand device status panel shown on wide (landscape tablet) layouts. */
@Composable
fun StatusPanel(vm: ReceiverViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    Column(
        modifier
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("STATUS PERANGKAT", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        Panel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Chip(state.status.label(), state.status.color())
            Spacer(Modifier.size(10.dp))
            KeyValue("iPhone", state.device?.displayName)
            KeyValue("iOS", state.device?.osVersion)
            KeyValue("Koneksi", state.device?.connectionType?.label)
            KeyValue("Enkripsi", if (state.encrypted) "FairPlay AES-CTR" else null)
            state.sessionStartedAt?.let { KeyValue("Durasi", formatDuration(System.currentTimeMillis() - it)) }
        }
        Panel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Chip("Kualitas: ${quality.label}", quality.color())
            Spacer(Modifier.size(10.dp))
            KeyValue("Video", if (stats.hasVideo) "${stats.width}×${stats.height}" else null)
            KeyValue("FPS", if (stats.hasVideo) "%.0f".format(stats.fps) else null)
            KeyValue("Bitrate", if (stats.hasVideo) "${stats.bitrateKbps} kbps" else null)
            KeyValue("Frame", if (stats.framesReceived > 0) "${stats.framesDecoded}/${stats.framesReceived}" else null)
        }
        Panel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            KeyValue("Penerima", state.receiverName.ifBlank { null })
            KeyValue("IP", state.ipAddresses.firstOrNull())
            KeyValue("Port", state.port.takeIf { it > 0 }?.toString())
        }
    }
}
