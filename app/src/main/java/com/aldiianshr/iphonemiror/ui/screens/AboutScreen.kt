package com.aldiianshr.iphonemiror.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aldiianshr.iphonemiror.BuildConfig
import com.aldiianshr.iphonemiror.mirroring.VideoDecoderEngine
import com.aldiianshr.iphonemiror.ui.components.KeyValue
import com.aldiianshr.iphonemiror.ui.components.Panel
import com.aldiianshr.iphonemiror.ui.theme.Palette

@Composable
fun AboutScreen() {
    val decoders = remember { runCatching { VideoDecoderEngine.availableDecoders() }.getOrDefault(emptyList()) }
    Page("Tentang", "iPhoneMiror — penerima AirPlay mirroring untuk tablet Android.") {
        ResponsiveColumns(
            left = {
                Panel(Modifier.fillMaxWidth(), title = "Aplikasi") {
                    KeyValue("Versi", BuildConfig.VERSION_NAME)
                    KeyValue("Build", "${BuildConfig.VERSION_CODE} · ${BuildConfig.GIT_SHA}", mono = true)
                    KeyValue("Paket", BuildConfig.APPLICATION_ID, mono = true)
                    KeyValue("Repository", "github.com/aldiianshr-commits/iPhoneMiror-Android-", mono = true)
                }
                Panel(Modifier.fillMaxWidth(), title = "Perangkat ini") {
                    KeyValue("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    KeyValue("SoC", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null)
                    KeyValue("ABI", Build.SUPPORTED_ABIS.joinToString(), mono = true)
                    KeyValue("Decoder H.264", decoders.joinToString("\n").ifEmpty { null }, mono = true)
                }
            },
            right = {
                Panel(Modifier.fillMaxWidth(), title = "Yang benar-benar didukung") {
                    Bullet("Layar iPhone via AirPlay Screen Mirroring (Wi-Fi yang sama), H.264 hardware decode.")
                    Bullet("Audio mirroring AAC-ELD.")
                    Bullet("Pairing legacy (Ed25519/Curve25519) + FairPlay SAP v3, stream terenkripsi.")
                    Bullet("Kontrol balik via Bluetooth HID (keyboard + mouse dengan AssistiveTouch).")
                    Bullet("Deteksi iPhone via USB OTG dan Bluetooth LE, riwayat koneksi lokal.")
                }
                Panel(Modifier.fillMaxWidth(), title = "Keterbatasan") {
                    Bullet("Mirroring lewat USB atau Bluetooth tidak tersedia.")
                    Bullet("Tidak ada integrasi Apple Account (tidak ada API resmi).")
                    Bullet("Sentuhan langsung absolut tidak didukung iOS; mode langsung bersifat eksperimental.")
                    Bullet("Kompatibilitas iOS 26/27 belum diverifikasi di perangkat nyata — lihat docs/COMPATIBILITY.md.")
                }
                Panel(Modifier.fillMaxWidth(), title = "Lisensi pihak ketiga") {
                    Text(
                        "Protokol AirPlay: subset dari serezhka/java-airplay (MIT). dd-plist (MIT), EdDSA-Java (CC0), " +
                            "X25519 sendiri (RFC 7748), AndroidX & Jetpack Compose (Apache 2.0), Dagger/Hilt (Apache 2.0). " +
                            "AirPlay dan iPhone adalah merek dagang Apple Inc.; proyek ini tidak berafiliasi dengan Apple.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    )
                }
            },
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    Spacer(Modifier.size(4.dp))
}
