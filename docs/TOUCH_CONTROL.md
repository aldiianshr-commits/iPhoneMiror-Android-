# Direct Touch Control

Tujuan: menyentuh tampilan iPhone di Xiaomi Pad 6 dan input itu **benar-benar** diterima iPhone.

## Hasil riset: apa yang diterima iOS

| Mekanisme | Membawa input ke iPhone? | Catatan |
|---|---|---|
| AirPlay Screen Mirroring | ❌ Tidak | Protokol satu arah (iPhone → penerima). Tidak ada kanal input balik. |
| **Bluetooth HID, pointer absolut** | ✅ Ya (iOS 18+) | Perlu **AssistiveTouch**. Klik = tap, tombol ditahan + gerak = swipe/drag. Posisi absolut tidak perlu kalibrasi. Terverifikasi oleh proyek open-source [esp32-iphone-agent](https://github.com/jinwon-int/esp32-iphone-agent) (MIT) pada iPhone fisik, termasuk lewat Bluetooth (Okt 2026). |
| Bluetooth HID, mouse relatif | ✅ Ya (iOS 13+) | Perlu AssistiveTouch; akselerasi pointer membuat posisi tidak presisi → dipakai sebagai mode trackpad. |
| HID Digitizer (layar sentuh multi-touch) | ❌ Tidak terbukti | Tidak ada laporan berhasil di iOS; tidak diimplementasikan. |
| **XCTest / WebDriverAgent** | ✅ Ya, multi-touch | Kerangka UI-testing resmi Apple. WDA (Appium, open-source) ditandatangani dengan Apple ID developer pengguna sendiri dan dijalankan di iPhone; menerima W3C Actions (banyak jari) lewat HTTP. Bukan jailbreak. |
| USB (usbmux) | ❌ Tidak untuk input | Tidak ada layanan input sentuh di usbmux/lockdown untuk perangkat non-developer. |

Kesimpulan: **tidak ada satu jalur yang memberi semuanya**. Aplikasi menyediakan dua backend
yang sah dan menampilkan *Unsupported* untuk apa pun yang tidak bisa dikirim.

## Arsitektur

```
MotionEvent (semua pointer)                     ┌─► BluetoothHidController ─► iPhone (AssistiveTouch)
   │ pointerInteropFilter                       │    (HidTouchTranslator: live, real-time)
   ▼                                            │
TouchMapper.toPhone()  ──► TouchEvent ──► TouchControlManager
 (rect video yang sama                          │
  dengan yang digambar)                         └─► WebDriverAgentClient ─► XCTest di iPhone
                                                     (GestureRecorder → W3cActions, replay)
                         ▲
   frame baru dari MediaCodec (lastDecodedNanos) ─┘  → konfirmasi "layar merespons"
```

### Pemetaan koordinat (`TouchMapper`)
1. `contentRect()` — posisi video di tampilan tablet dari resolusi stream iPhone (dari SPS/MediaCodec),
   ukuran tampilan tablet dan mode skala: **Rasio asli** (letterbox/pillarbox), **Isi** (crop),
   **Fit to screen** (regang).
2. `displayedRect()` — zoom & pan pengguna.
3. `toPhone()` — titik sentuh → koordinat ternormalisasi iPhone (0..1). Sentuhan di area hitam
   diabaikan; jari yang keluar tepi saat drag di-*clamp* ke tepi.
4. Target:
   - HID: `toHidAbsolute()` → 0..32767; untuk iPhone landscape tersedia opsi *Ikut layar /
     Portrait asli putar 90° / 270°* (pilih dengan **Uji pemetaan**, pointer bergerak tanpa klik).
   - XCTest: `toPoints()` → point UIKit memakai `window/size` dari WDA, sumbu ditukar otomatis bila
     orientasi berbeda.

Video digambar dengan rect yang sama persis (`Modifier.placeAt(rect)`), jadi gambar dan input tidak bisa bergeser satu sama lain.

### Backend Bluetooth HID (real-time)
- Descriptor: keyboard (dideklarasikan pertama), pointer absolut uint16 0..32767 + wheel + AC Pan,
  consumer 16-bit (Home 0x0040, volume, play/pause).
- Jari turun → pointer dipindah ke titik (tanpa klik) → jeda 30 ms (300 ms bila pointer idle >2 dtk
  karena iOS menyembunyikannya) → tombol ditekan → posisi dikirim tiap ≤8 ms selama jari bergerak →
  jari diangkat → tombol dilepas. Hasilnya tap, double tap, long press, swipe 4 arah, dan drag & drop
  direproduksi seperti yang dilakukan.
- Jari kedua dan seterusnya diabaikan (iOS hanya menerima satu pointer), sehingga perilakunya seperti satu jari di layar iPhone.

### Backend XCTest / WebDriverAgent (multi-touch)
- `GestureRecorder` merekam lintasan semua jari; saat semua jari terangkat, `W3cActions` menyusun
  satu sumber pointer per jari pada garis waktu tick yang sama (sinkron) lalu `POST /session/{id}/actions`.
- Mendukung pinch, multi-jari, drag, tap dengan koordinat point yang tepat; teks via `/wda/keys`; Home via `/wda/homescreen`.
- Tidak real-time: gesture diputar setelah jari diangkat (latensi = round-trip HTTP + durasi gesture).

## Umpan balik — tidak pernah mengklaim berhasil tanpa bukti

| Status | Arti |
|---|---|
| Terkirim, menunggu layar | Laporan HID diterima stack Bluetooth Android (belum bukti iPhone memproses). |
| **Layar iPhone merespons (N ms)** | Frame mirroring baru didekode setelah input dimulai. Bukti terkuat yang tersedia untuk HID. (Bila video sedang diputar di iPhone, frame juga datang tanpa input — label menyebut "frame baru diterima", bukan "sukses".) |
| Terkirim, layar tidak berubah | Tidak ada frame baru dalam 1,5 dtk. |
| **Dikonfirmasi iPhone (N ms)** | XCTest di iPhone menjawab sukses untuk gesture tersebut. |
| Tidak didukung / Gagal | Dengan alasannya (mis. pinch via HID, WDA tidak terjangkau). |

Indikator lingkaran menunjukkan posisi jari (lokal) dan warna status gesture terakhir.
Di layar Mirroring status ditampilkan sebagai **pil kecil** di kiri bawah (hanya saat streaming):
ketuk untuk detail, ✕ untuk menyembunyikan; tombol ✋ di toolbar atau halaman Kontrol Sentuh
untuk menampilkannya lagi. Status gesture hanya muncul ±2,5 detik.
Statistik menampilkan respon rata-rata (10 gesture terakhir) dan latensi koneksi WDA (ping `/status`).

## Tampilan
- Kontrol tersambung → layar mirroring langsung interaktif. Tidak tersambung → hanya tampilan (pinch untuk zoom lokal).
- **Layar penuh** — toolbar disembunyikan, tombol kecil ⋯ di pojok untuk memunculkannya.
- **Rasio asli / Isi / Fit to screen** — mode skala; pemetaan sentuhan mengikuti otomatis.

## Persiapan: hanya tablet + iPhone (Bluetooth HID — jalur utama)

Tidak butuh komputer, kabel, atau aplikasi di iPhone. Halaman **Kontrol Sentuh** memandu 6 langkah dengan tanda centang:

1. Izin Bluetooth di tablet.
2. Bluetooth tablet menyala.
3. **Aktifkan kontrol Bluetooth** (tersimpan; dinyalakan lagi otomatis saat aplikasi dibuka).
4. **Sambungkan iPhone** — buka *Pengaturan → Bluetooth* di iPhone dan biarkan terbuka, lalu di tablet
   *Cari iPhone* → *Pasangkan* → konfirmasi kode yang sama di kedua perangkat. (Alternatif: *Buat tablet
   terlihat* lalu pilih tablet dari iPhone.) iPhone yang berhasil diingat dan **tersambung ulang otomatis**
   setiap 10 detik bila terputus.
5. iPhone: *Pengaturan → Aksesibilitas → Sentuh → AssistiveTouch* → **aktif** (iOS 18+). Agar terasa seperti
   layar sentuh biasa: *Gaya Penanda → Sembunyikan Otomatis* aktif dengan waktu tersingkat, ukuran penanda
   terkecil, dan *Kekeburaman Saat Diam* paling rendah. iOS selalu memakai penanda AssistiveTouch untuk input
   dari perangkat Bluetooth; tidak ada cara lain tanpa komputer.
6. **Uji pemetaan** saat mirroring: pointer harus bergerak ke tengah & empat sudut.

Setelah itu layar Mirroring **langsung bisa disentuh seperti layar iPhone** — tidak ada mode atau
gesture khusus: jari menekan, bergerak dan diangkat, iPhone mengikuti secara real-time. iOS menerima
satu pointer dari perangkat Bluetooth, jadi jari kedua diabaikan dan pinch tidak tersedia.

## Opsi lanjutan: XCTest / WebDriverAgent (butuh komputer macOS)

Disembunyikan secara default (*Kontrol Sentuh → Opsi lanjutan*). Langkah:
1. Mac dengan Xcode, Apple ID (gratis cukup), *Mode Pengembang* aktif di iPhone.
2. Build & jalankan target `WebDriverAgentRunner` (repo `appium/WebDriverAgent`) ke iPhone.
3. Di tablet, *Kontrol Sentuh → URL WebDriverAgent* (otomatis dari koneksi AirPlay, mis.
   `http://192.168.1.20:8100` atau IPv6 link-local `http://[fe80::…%wlan0]:8100`) → *Hubungkan*.
4. Sertifikat Apple ID gratis kedaluwarsa tiap 7 hari; ulangi langkah 2.

## Status pengujian
- Unit test: pemetaan (letterbox, crop, regang, zoom/pan, rotasi), klasifikasi gesture, penerjemah HID
  (tap, tap cepat, swipe, scroll 2 jari, pinch unsupported, mode relatif), payload W3C, klien WDA terhadap server palsu.
- Emulator: halaman Kontrol Sentuh menampilkan **Unsupported** dengan alasan saat tidak ada host Bluetooth/WDA.
- **Belum diuji dengan iPhone fisik** — perilaku pointer absolut lewat Bluetooth *classic* (Android
  `BluetoothHidDevice`) diharapkan sama dengan BLE HID yang telah terbukti, tetapi harus diverifikasi.
