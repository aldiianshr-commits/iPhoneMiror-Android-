# Roadmap

## Selesai (v1.0)
- [x] Penerima AirPlay Screen Mirroring (pairing legacy, FairPlay SAP v3, AES-CTR/CBC)
- [x] Decode H.264 hardware MediaCodec, mode low-latency + ekstensi Qualcomm
- [x] Audio AAC-ELD → AudioTrack low-latency, kontrol volume dari iPhone
- [x] Iklan mDNS otomatis + re-advertise saat jaringan berubah
- [x] Persetujuan perangkat baru, daftar izinkan/blokir, riwayat koneksi (Room)
- [x] Direct touch: pointer absolut Bluetooth HID real-time (iOS 18+) + XCTest/WDA multi-touch, pemetaan koordinat, umpan balik jujur
- [x] Keyboard, Home (consumer 0x0040), media lewat HID
- [x] Discovery: Bonjour Apple, BLE Apple, USB OTG + izin
- [x] UI tablet (sidebar, panel status kanan, landscape/portrait, fullscreen, zoom, rasio)
- [x] Self-test penerima di perangkat, log diagnostik yang bisa dibagikan
- [x] CI: build, unit + integrasi + UI + memori di emulator tablet, screenshot, rilis APK

## Berikutnya
- [ ] Verifikasi di Xiaomi Pad 6 dengan iPhone iOS 26.x / 27.x, isi `COMPATIBILITY.md`
- [ ] USB: klien usbmux + `lockdownd` (QueryType / GetValue) untuk membaca versi iOS & model
      lewat USB (perlu konfirmasi "Percayai Komputer Ini" di iPhone)
- [ ] Sinkronisasi audio/video berbasis NTP (timing port) — saat ini keduanya diputar secepatnya
- [ ] Verifikasi pointer absolut lewat Bluetooth classic (Android HID Device) di iPhone iOS 26/27
- [ ] Deteksi pointer AssistiveTouch di frame video untuk konfirmasi posisi otomatis
- [ ] Opsi PIN pairing (HomeKit-style) bila iOS mensyaratkannya
- [ ] Rekam layar mirroring ke MP4 (MediaMuxer, tanpa re-encode)

## Di luar cakupan (tidak mungkin secara sah)
- Mirroring lewat Bluetooth (bandwidth & tidak ada protokol)
- Login Apple Account pihak ketiga untuk otorisasi mirroring
- Kontrol sentuh tanpa AssistiveTouch, tanpa input Bluetooth, dan tanpa XCTest
- Multi-touch lewat Bluetooth HID (iOS tidak menerima HID digitizer multi-touch)
