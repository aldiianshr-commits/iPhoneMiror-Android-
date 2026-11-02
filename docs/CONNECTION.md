# Koneksi

## Ringkasan per jalur

| Jalur | Status | Fungsi |
|---|---|---|
| **Wi-Fi (AirPlay)** | ✅ Diimplementasikan | Video + audio layar iPhone. Satu-satunya jalur mirroring. |
| **Bluetooth HID** | ✅ Diimplementasikan | Tablet = keyboard + mouse untuk iPhone (kontrol). |
| **Bluetooth LE** | ✅ Diimplementasikan | Discovery perangkat Apple di sekitar (company ID 0x004C). |
| **USB OTG** | ⚠️ Deteksi saja | Deteksi, izin USB, identifikasi (produk, UDID/serial, antarmuka). Tanpa video. |

## Wi-Fi — AirPlay Screen Mirroring

### Discovery & advertising
Tablet mendaftarkan dua layanan DNS-SD via `NsdManager`:

- `_airplay._tcp` — nama = nama penerima; TXT: `deviceid`, `features`, `srcvers=220.68`,
  `flags=0x44`, `model=AppleTV3,2`, `pk` (kunci publik Ed25519), `pw=false`, `vv=2`.
- `_raop._tcp` — nama `AABBCCDDEEFF@Nama`; TXT parameter audio.

`features` hanya memuat kemampuan yang benar-benar diimplementasikan: screen mirroring (bit 7),
audio (9), FairPlay SAP 2.5 (12), auth 4 (14), format audio (19–21), legacy pairing (27), RAOP (30).
Pemutaran URL video/HLS (bit 0, 4) **tidak** diiklankan karena tidak diimplementasikan.

Re-advertise otomatis saat jaringan berubah (callback `ConnectivityManager`). Saat Wi-Fi hilang,
status menjadi *Tidak ada Wi-Fi* dan iklan dicabut.

### Handshake (koneksi kontrol, port 7000)
| Langkah | Implementasi |
|---|---|
| `GET /info` | plist biner: nama, fitur, display (resolusi & maxFPS dari pengaturan), `pk` |
| `POST /pair-setup` | mengembalikan kunci publik Ed25519 penerima |
| `POST /pair-verify` (1) | ECDH X25519, tanda tangan Ed25519 terenkripsi AES-CTR |
| `POST /pair-verify` (2) | verifikasi tanda tangan iPhone |
| `POST /fp-setup` (2×) | FairPlay SAP v3 |
| `SETUP` (ekey/eiv) | kunci terenkripsi + info perangkat (nama, model, osVersion, build) → **persetujuan** |
| `SETUP` stream 110 | membuka port data mirror (TCP) |
| `SETUP` stream 96 | membuka port audio data + kontrol (UDP) |
| `RECORD`, `GET/SET_PARAMETER`, `FLUSH`, `POST /feedback` | dijawab sesuai protokol (volume diterapkan ke AudioTrack) |
| `TEARDOWN` | menutup stream tertentu atau seluruh sesi |

### Pemantauan & pemutusan
- Socket kontrol tertutup → sesi berakhir, riwayat ditutup, status kembali *Menunggu iPhone*.
- Watchdog: tidak ada data/feedback 20 detik selama mirroring → sesi diakhiri.
- iPhone sendiri menyambung ulang bila pengguna memilih tablet lagi; tablet tetap diiklankan.

### Sumber protokol
Protokol AirPlay tidak didokumentasikan Apple. Implementasi mengikuti hasil reverse-engineering
publik yang dipakai luas: [serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT, kode
FairPlay/pairing di-vendor ke `airplay-protocol/`), UxPlay, RPiPlay, dan catatan
"Unofficial AirPlay Protocol Specification". Tidak ada bagian protokol yang dikarang; perilaku
yang tidak diketahui dijawab netral dan dicatat di log diagnostik.

## Bluetooth — kontrol (HID)

AirPlay adalah protokol satu arah: iPhone **tidak** menerima input sentuh dari penerima AirPlay.
Cara resmi untuk mengendalikan iPhone dari perangkat lain adalah perangkat input Bluetooth:

1. Di tablet: **Hubungkan iPhone → Kontrol (Bluetooth HID) → Aktifkan**, lalu *Buat terlihat*.
2. Di iPhone: **Pengaturan → Bluetooth** → pilih tablet (muncul sebagai keyboard/mouse).
3. Untuk pointer: **Pengaturan → Aksesibilitas → Sentuh → AssistiveTouch → aktifkan**.
4. Di layar Mirroring, sentuhan diteruskan sebagai mouse:
   - Mode trackpad: geser = gerak pointer, ketuk = klik, tahan lalu geser = drag,
     dua jari geser = scroll, dua jari ketuk = klik sekunder (menu AssistiveTouch).
   - Mode sentuh langsung (eksperimental): pointer didorong ke pojok kiri-atas lalu ke posisi
     sentuhan. iOS memakai akselerasi pointer, jadi perlu kalibrasi *Kecepatan pointer*.
5. Keyboard: teks (layout US), Esc/Tab/panah/Enter/Backspace, ⌘Space (Spotlight), media & volume.

Descriptor HID: mouse relatif 3 tombol + wheel + AC Pan (report 1), keyboard boot (report 2),
consumer control (report 3). Beberapa ROM Android menonaktifkan profil *HID Device*; aplikasi
melaporkannya bila `getProfileProxy(HID_DEVICE)` gagal.

## Bluetooth LE — discovery
Pemindaian BLE mencari data produsen Apple (0x004C) dan menampilkan jenis pesan Continuity
(Nearby Info, Handoff, dsb.) dan RSSI. Alamat BLE iPhone diacak, jadi hasil tidak dapat dikaitkan
ke iPhone tertentu — ditampilkan apa adanya. Izin `BLUETOOTH_SCAN` dengan `neverForLocation`.

## USB OTG

Yang berfungsi:
- Deteksi iPhone/iPad (vendor Apple `0x05AC`) via USB Host API, intent `USB_DEVICE_ATTACHED`
  membuka aplikasi ke halaman Hubungkan.
- Dialog izin USB sistem (per perangkat), lalu pembacaan serial (UDID), produk, versi USB, dan
  keberadaan antarmuka **usbmux** (class 0xFF/0xFE/2).

Yang **tidak** tersedia dan alasannya:
- **Video lewat USB**: iOS hanya mengirim layar lewat USB ke macOS (QuickTime/CoreMediaIO),
  memakai protokol tidak terdokumentasi di atas usbmux. Tidak ada jalur publik/stabil untuk Android.
- **Versi iOS lewat USB**: membutuhkan sesi `lockdownd` di atas usbmux (protokol libimobiledevice)
  dan, untuk sebagian besar nilai, pairing "Percayai Komputer Ini". Direncanakan — lihat ROADMAP.

Catatan perangkat keras: iPhone akan mencoba mengisi daya dari tablet saat OTG.

## Apple Account
Tidak ada API resmi bagi aplikasi Android untuk memakai Apple Account sebagai otorisasi
mirroring. Halaman *Akun Apple* menampilkan status ini secara jujur, informasi pairing penerima,
dan tombol *Sign out / reset pairing* (mengganti kunci identitas dan menghapus perangkat diizinkan).
Tidak ada formulir login dan kata sandi tidak pernah diminta.
