package com.aldiianshr.iphonemiror.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.domain.model.CompatibilityChecker
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.color
import com.aldiianshr.iphonemiror.ui.components.label
import com.aldiianshr.iphonemiror.ui.screens.AboutScreen
import com.aldiianshr.iphonemiror.ui.screens.AppleAccountScreen
import com.aldiianshr.iphonemiror.ui.screens.ConnectScreen
import com.aldiianshr.iphonemiror.ui.screens.DashboardScreen
import com.aldiianshr.iphonemiror.ui.screens.DeviceManagerScreen
import com.aldiianshr.iphonemiror.ui.screens.HistoryScreen
import com.aldiianshr.iphonemiror.ui.screens.MirroringScreen
import com.aldiianshr.iphonemiror.ui.screens.SettingsScreen
import com.aldiianshr.iphonemiror.ui.screens.StatusPanel
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel

enum class AppScreen(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Outlined.Dashboard),
    CONNECT("Hubungkan iPhone", Icons.Outlined.Link),
    DEVICES("Device Manager", Icons.Outlined.Devices),
    MIRRORING("Mirroring", Icons.Outlined.Cast),
    HISTORY("Riwayat Koneksi", Icons.Outlined.History),
    ACCOUNT("Akun Apple", Icons.Outlined.AccountCircle),
    SETTINGS("Pengaturan", Icons.Outlined.Settings),
    ABOUT("Tentang", Icons.Outlined.Info),
}

@Composable
fun AppRoot(
    screen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    receiverVm: ReceiverViewModel,
) {
    val state by receiverVm.state.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        val wide = maxWidth >= 1000.dp
        val showStatusPanel = maxWidth >= 1200.dp && screen != AppScreen.MIRRORING
        val content: @Composable (Modifier) -> Unit = { mod ->
            Crossfade(screen, label = "screen", modifier = mod) { s ->
                when (s) {
                    AppScreen.DASHBOARD -> DashboardScreen(receiverVm, onNavigate)
                    AppScreen.CONNECT -> ConnectScreen(receiverVm, onNavigate)
                    AppScreen.DEVICES -> DeviceManagerScreen(receiverVm)
                    AppScreen.MIRRORING -> MirroringScreen(receiverVm, fullscreen, onFullscreen)
                    AppScreen.HISTORY -> HistoryScreen()
                    AppScreen.ACCOUNT -> AppleAccountScreen(receiverVm)
                    AppScreen.SETTINGS -> SettingsScreen(receiverVm)
                    AppScreen.ABOUT -> AboutScreen()
                }
            }
        }
        if (fullscreen && screen == AppScreen.MIRRORING) {
            content(Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Sidebar(screen, expanded = wide, onNavigate = onNavigate, statusText = state.status.label(), statusColor = state.status.color())
                content(Modifier.weight(1f).fillMaxHeight())
                AnimatedVisibility(showStatusPanel) {
                    StatusPanel(receiverVm, Modifier.width(320.dp).fillMaxHeight())
                }
            }
        }
    }

    state.awaitingApproval?.let { device ->
        var remember by remember(device.id) { mutableStateOf(true) }
        val compat = CompatibilityChecker.evaluate(device.osVersion)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Izinkan ${device.displayName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Perangkat ini ingin mencerminkan layarnya ke tablet.", color = Palette.TextSecondary)
                    Text("Model: ${device.marketingName ?: device.model ?: "tidak dilaporkan"}")
                    Text("Sistem: ${listOfNotNull(device.osName, device.osVersion).joinToString(" ").ifEmpty { "tidak dilaporkan" }}")
                    Text("Alamat: ${device.address ?: "—"}")
                    Text(compat.message, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text("Ingat keputusan ini")
                    }
                    Text(
                        "Jika iPhone menampilkan \"gagal terhubung\" setelah menunggu, pilih tablet ini lagi.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { receiverVm.approve(true, remember) }) { Text("Izinkan") } },
            dismissButton = { TextButton(onClick = { receiverVm.approve(false, remember) }) { Text("Tolak") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

@Composable
private fun Sidebar(
    current: AppScreen,
    expanded: Boolean,
    onNavigate: (AppScreen) -> Unit,
    statusText: String,
    statusColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 248.dp else 84.dp)
            .background(Palette.Background)
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Cast, null, tint = Palette.OnAccent, modifier = Modifier.size(20.dp)) }
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("iPhoneMiror", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Text("AirPlay receiver", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        AppScreen.entries.forEach { item ->
            val selected = item == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Palette.SurfaceHigher else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, Palette.Outline, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onNavigate(item) }
                    .testTag("nav_${item.name}")
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
            ) {
                Icon(item.icon, item.title, tint = if (selected) Palette.Text else Palette.TextSecondary, modifier = Modifier.size(20.dp))
                if (expanded) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        item.title, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Palette.Text else Palette.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (expanded) Chip(statusText, statusColor, Modifier.padding(horizontal = 6.dp))
    }
}
