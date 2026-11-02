# Roadmap

## Selesai (v1.0)
- [x] Penerima AirPlay Screen Mirroring (pairing legacy, FairPlay SAP v3, AES-CTR/CBC)
- [x] Decode H.264 hardware MediaCodec, mode low-latency + ekstensi Qualcomm
- [x] Audio AAC-ELD → AudioTrack low-latency, kontrol volume dari iPhone
- [x] Iklan mDNS otomatis + re-advertise saat jaringan berubah
- [x] Persetujuan perangkat baru, daftar izinkan/blokir, riwayat koneksi (Room)
- [x] Kontrol Bluetooth HID: mouse (trackpad/eksperimental langsung), keyboard, media
- [x] Discovery: Bonjour Apple, BLE Apple, USB OTG + izin
- [x] UI tablet (sidebar, panel status kanan, landscape/portrait, fullscreen, zoom, rasio)
- [x] Self-test penerima di perangkat, log diagnostik yang bisa dibagikan
- [x] CI: build, unit + integrasi + UI + memori di emulator tablet, screenshot, rilis APK

## Berikutnya
- [ ] Verifikasi di Xiaomi Pad 6 dengan iPhone iOS 26.x / 27.x, isi `COMPATIBILITY.md`
- [ ] USB: klien usbmux + `lockdownd` (QueryType / GetValue) untuk membaca versi iOS & model
      lewat USB (perlu konfirmasi "Percayai Komputer Ini" di iPhone)
- [ ] Sinkronisasi audio/video berbasis NTP (timing port) — saat ini keduanya diputar secepatnya
- [ ] Kalibrasi otomatis mode sentuh langsung (deteksi pointer AssistiveTouch di frame video)
- [ ] Opsi PIN pairing (HomeKit-style) bila iOS mensyaratkannya
- [ ] Rekam layar mirroring ke MP4 (MediaMuxer, tanpa re-encode)

## Di luar cakupan (tidak mungkin secara sah)
- Mirroring lewat Bluetooth (bandwidth & tidak ada protokol)
- Login Apple Account pihak ketiga untuk otorisasi mirroring
- Kontrol sentuh absolut tanpa AssistiveTouch / tanpa input Bluetooth
