# Arsitektur

```
┌──────────────────────────────── app (Presentation) ────────────────────────────────┐
│ Jetpack Compose UI (Sidebar · konten · panel status)   ViewModels (Hilt)          │
│ ReceiverService (foreground, connectedDevice)  SelfTestRunner  AppModule (DI)      │
└──────┬───────────────┬────────────────┬────────────────┬───────────────┬──────────┘
       │               │                │                │               │
┌──────▼─────┐  ┌──────▼──────┐  ┌──────▼───────┐  ┌─────▼──────┐  ┌─────▼─────┐
│ connection │  │  mirroring  │  │device-discov.│  │input-contr.│  │   data    │
│ AirPlay    │  │ MediaCodec  │  │ NSD advertise│  │ Bluetooth  │  │ Room      │
│ RTSP server│─▶│ H.264 → Surf│  │ Bonjour brow.│  │ HID device │  │ DataStore │
│ Mirror/Aud.│  │ AAC-ELD →   │  │ USB host     │  │ (kbd+mouse)│  │ Keystore  │
│ Receiver-  │  │ AudioTrack  │  │ BLE scanner  │  │            │  │ identity  │
│ Controller │  └─────────────┘  └──────────────┘  └────────────┘  └───────────┘
└─────┬──────┘
      │ uses
┌─────▼────────────┐   ┌────────────────────────────┐   ┌──────────────────┐
│ airplay-protocol │   │ domain (pure Kotlin/JVM)   │   │ core             │
│ FairPlay SAP v3, │   │ models, repository iface,  │   │ AppLog, bytes,   │
│ pairing, X25519  │   │ use cases, VideoSink/Audio │   │ network info     │
└──────────────────┘   └────────────────────────────┘   └──────────────────┘
```

## Lapisan

| Lapisan | Modul | Tanggung jawab |
|---|---|---|
| Presentation | `app` | Compose UI tablet, ViewModel, layanan latar depan, DI (Hilt) |
| Domain | `domain` | Model (`AppleDevice`, `ReceiverState`, `StreamStats`…), antarmuka repository, use case (`EvaluateDeviceApproval`), `CompatibilityChecker`, `LinkQualityCalculator` |
| Data | `data` | Room (riwayat, perangkat dikenal), DataStore (pengaturan), `KeystoreIdentityStore` |
| Connection Engine | `connection` | Server RTSP/AirPlay, sesi, kanal mirror (TCP) & audio (UDP), `ReceiverController` (lifecycle, jaringan, persetujuan) |
| Device Discovery | `device-discovery` | Iklan mDNS (`_airplay._tcp`, `_raop._tcp`), browse Bonjour Apple, USB OTG, BLE Apple |
| Mirroring Engine | `mirroring` | `VideoDecoderEngine` (MediaCodec → Surface), `AudioPlayerEngine` (AAC-ELD), `SpsParser` |
| Input Controller | `input-controller` | `BluetoothHidController`, descriptor HID, peta keyboard |
| Protokol | `airplay-protocol` | Subset java-airplay (MIT) + X25519 sendiri |

Modul fitur tidak memakai Hilt; mereka memakai konstruktor biasa sehingga bisa diuji tanpa DI.
`app/di/AppModule.kt` menyusun semuanya.

## Alur data mirroring

1. `ReceiverService` → `ReceiverController.start()` membuka server di port 7000 dan
   mendaftarkan layanan mDNS lewat `NsdManager`.
2. iPhone (Pusat Kontrol → Pencerminan Layar) membuka koneksi kontrol:
   `GET /info` → `POST /pair-setup` → `POST /pair-verify` ×2 → `POST /fp-setup` ×2 →
   `SETUP` (ekey/eiv + info perangkat) → **dialog persetujuan** → `SETUP` stream 110 (video) →
   `SETUP` stream 96 (audio) → `RECORD` → `POST /feedback` setiap ~2 dtk.
3. iPhone terhubung ke port data mirror; setiap paket = header 128 byte + payload.
   Tipe 1 = konfigurasi codec (SPS/PPS, plaintext). Tipe 0 = frame AVCC terenkripsi AES-CTR.
4. `MirrorChannel` mendekripsi, mengubah AVCC → Annex-B, dan memberi frame ke
   `VideoDecoderEngine` (antrian → MediaCodec → `TextureView` Surface, render segera tanpa
   penjadwalan PTS demi latensi minimal).
5. Saat tampilan Mirroring tidak terlihat, decoder tetap berjalan ke Surface dummy dengan
   render dimatikan, lalu `setOutputSurface()` saat tampilan kembali (tanpa menunggu keyframe).

## Optimasi untuk Xiaomi Pad 6 (Snapdragon 870 / Adreno 650)

- Decoder hardware dipilih otomatis (`c2.qti.avc.decoder` pada Snapdragon). `KEY_LOW_LATENCY`
  bila didukung, plus ekstensi vendor Qualcomm `vendor.qti-ext-dec-low-latency.enable` dan
  `vendor.qti-ext-dec-picture-order.enable`.
- Output langsung ke Surface (zero-copy, komposisi oleh GPU Adreno). Zoom/pan memakai
  `graphicsLayer` (transformasi GPU, tanpa re-decode).
- Thread decoder prioritas tinggi; antrian terbatas 240 frame dengan flush ke keyframe bila tertinggal.
- `WifiLock` mode `WIFI_MODE_FULL_LOW_LATENCY` hanya selama mirroring.
- Resolusi/fps tidak dipaksakan: tablet hanya mengiklankan batas atas (720p/1080p/1440p, 30/60);
  iPhone yang menentukan stream sebenarnya.
- APK tanpa library native (Kotlin/Java murni) → berjalan di ARM64 (dan x86_64 untuk emulator).

## Pengujian

| Jenis | Lokasi |
|---|---|
| Unit (JVM) | `*/src/test` — RTSP codec, profil AirPlay, parser SETUP, framing H.264, SPS parser, ASC AAC-ELD, HID descriptor, domain, X25519 (RFC 7748), FairPlay SAP |
| Integrasi (JVM, loopback) | `connection/.../ReceiverLoopbackIntegrationTest` — handshake lengkap + video & audio terenkripsi melalui socket nyata |
| Integrasi + memori (Android) | `app/src/androidTest/.../ReceiverPipelineInstrumentedTest` — self-test melalui MediaCodec di emulator |
| UI | `app/src/androidTest/.../NavigationUiTest` |
| Data (Robolectric) | `data/src/test/.../RepositoriesTest` |
