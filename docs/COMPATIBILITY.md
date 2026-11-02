# Kompatibilitas

> **Belum ada klaim dukungan versi iOS.** Tabel di bawah hanya diisi setelah pengujian
> pada perangkat nyata. Aplikasi menampilkan status "target, belum diverifikasi" untuk iOS 26/27.

## Tablet

| Perangkat | Android | Status | Catatan |
|---|---|---|---|
| Emulator Pixel Tablet (CI) | 14 (API 34, x86_64) | ✅ Lulus tes otomatis | Self-test AirPlay loopback: pair-verify OK, 167/167 frame terdekode, 0 galat (`c2.goldfish.h264.decoder`). PSS ±104–114 MB, heap Java tidak bertambah setelah 2 sesi. Tes UI navigasi 8 halaman lulus. APK rilis (R8) berjalan tanpa crash. |
| Xiaomi Pad 6 (SD 870, Adreno 650) | 13–15 (HyperOS) | ⏳ Belum diuji | Target utama. Jalankan *Pengaturan → Uji mandiri penerima* lalu laporkan. |
| Tablet MediaTek | 13+ | ⏳ Belum diuji | Decoder hardware MediaTek dipilih otomatis; ekstensi low-latency vendor hanya untuk Qualcomm. |

## iPhone

| iPhone | iOS | Mirroring | Audio | Kontrol HID | Diuji oleh / tanggal |
|---|---|---|---|---|---|
| — | 26.x | ⏳ belum diuji | ⏳ | ⏳ | — |
| — | 27.x | ⏳ belum diuji | ⏳ | ⏳ | — |

### Ekspektasi teknis (bukan klaim)
- Jalur legacy pairing + FairPlay SAP v3 adalah yang dipakai penerima AirPlay open-source aktif
  (java-airplay, UxPlay) untuk iOS terbaru per 2026. Bila Apple mewajibkan pairing HomeKit untuk
  penerima pihak ketiga di versi iOS mendatang, mirroring akan gagal di tahap `pair-verify` dan
  log diagnostik akan menunjukkannya.
- Data perangkat (nama, model, versi iOS) hanya ditampilkan bila iPhone mengirimkannya di `SETUP`.

## Cara melaporkan hasil

1. Aktifkan **Pengaturan → Diagnostik → Log debug**.
2. Jalankan **Uji mandiri penerima** (memastikan sisi tablet bekerja).
3. Cerminkan dari iPhone; catat hasil video, audio, kontrol.
4. **Bagikan log** dan buka issue dengan model + versi kedua perangkat.
