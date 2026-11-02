package com.aldiianshr.iphonemiror.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldiianshr.iphonemiror.ui.components.Chip
import com.aldiianshr.iphonemiror.ui.components.InfoNote
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.components.SecondaryButton
import com.aldiianshr.iphonemiror.ui.theme.Palette
import com.aldiianshr.iphonemiror.ui.viewmodel.ReceiverViewModel
import com.aldiianshr.iphonemiror.ui.viewmodel.SettingsViewModel

/**
 * Apple Account integration status — reported honestly. There is no official Apple API for
 * an Android app to sign in to an Apple Account for screen mirroring, so no login form is
 * shown and no password is ever requested.
 */
@Composable
fun AppleAccountScreen(receiverVm: ReceiverViewModel) {
    val vm: SettingsViewModel = hiltViewModel()
    val state by receiverVm.state.collectAsStateWithLifecycle()
    val known by vm.knownDevices.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val pk = vm.publicKey()
    val created = vm.identityCreatedAt()

    Page("Akun Apple", "Status integrasi akun dan informasi pairing.") {
        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth(), title = "Integrasi Apple Account", trailing = { Chip("Tidak tersedia", Palette.TextTertiary) }) {
                    KeyValue("Status integrasi", "Tidak terhubung")
                    KeyValue("Autentikasi", "Tidak digunakan")
                    Spacer(Modifier.size(8.dp))
                    InfoNote(
                        "Apple tidak menyediakan mekanisme autentikasi resmi bagi aplikasi Android untuk masuk ke Apple Account " +
                            "demi pencerminan layar. Karena itu aplikasi ini tidak menampilkan formulir login dan tidak pernah meminta " +
                            "kata sandi Apple. Pencerminan AirPlay juga tidak memerlukan Apple Account: izin diberikan per perangkat " +
                            "melalui pairing dan konfirmasi di tablet ini.",
                        icon = Icons.Outlined.Shield,
                    )
                    Spacer(Modifier.size(8.dp))
                    InfoNote("Memakai Apple Account yang sama di dua perangkat tidak otomatis memberi izin mirroring.")
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "Informasi pairing", subtitle = "Identitas penerima AirPlay di tablet ini") {
                    KeyValue("Status sesi", if (state.pairVerified) "Pair-verify berhasil" else "Tidak ada sesi")
                    KeyValue("Receiver ID", vm.receiverId().ifBlank { null }, mono = true)
                    KeyValue("Kunci publik", pk?.let { it.take(16) + "…" + it.takeLast(8) }, mono = true)
                    KeyValue("Dibuat", created?.let { relativeTime(it) })
                    KeyValue("Perangkat diizinkan", known.count { it.allowed }.toString())
                    KeyValue("Penyimpanan kunci", "Android Keystore (AES-GCM)")
                    Spacer(Modifier.size(10.dp))
                    Row {
                        SecondaryButton("Sign out / reset pairing", Icons.AutoMirrored.Outlined.Logout) { confirmReset = true }
                    }
                    Spacer(Modifier.size(8.dp))
                    InfoNote(
                        "Reset membuat kunci pairing baru dan menghapus daftar perangkat yang diizinkan. iPhone akan melihat tablet sebagai penerima baru.",
                        icon = Icons.Outlined.Key,
                    )
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset pairing?") },
            text = { Text("Semua sesi diputus, kunci pairing diganti, dan perangkat yang diizinkan dilupakan.") },
            confirmButton = { TextButton(onClick = { vm.resetIdentity(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Batal") } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}
