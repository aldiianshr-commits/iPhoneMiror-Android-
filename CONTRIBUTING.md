# Contributing

Terima kasih ingin berkontribusi ke iPhoneMiror.

## Aturan proyek

1. **Tidak ada fitur palsu.** Tombol, status, dan statistik harus berasal dari data nyata.
   Jika sebuah kemampuan tidak didukung protokol, tampilkan keterbatasannya — jangan disimulasikan.
2. **Tidak mengarang protokol Apple.** Implementasi protokol harus mengikuti spesifikasi publik
   atau hasil reverse-engineering yang terdokumentasi (sebutkan sumbernya di `docs/CONNECTION.md`).
3. **Tidak meminta kredensial Apple**, tidak ada halaman login tiruan, tidak ada pairing diam-diam.
4. **Klaim kompatibilitas** hanya setelah diuji pada perangkat nyata dan dicatat di
   `docs/COMPATIBILITY.md` (model iPhone, versi iOS, tablet, hasil).

## Struktur modul

Lihat `docs/ARCHITECTURE.md`. Modul fitur (`connection`, `mirroring`, `device-discovery`,
`input-controller`, `data`) tidak bergantung pada Hilt; semua wiring ada di `app/di`.

## Build & tes

```bash
./gradlew assembleDebug assembleRelease          # APK
./gradlew testDebugUnitTest :airplay-protocol:test :domain:test   # tes JVM
./gradlew connectedDebugAndroidTest               # tes instrumentasi (emulator/perangkat)
```

CI (`.github/workflows/android.yml`) menjalankan semuanya, termasuk emulator tablet API 34,
dan menerbitkan APK ke rilis `latest-build`.

## Melaporkan hasil uji di perangkat nyata

Buka issue dengan: model tablet, versi Android, model iPhone, versi iOS, hasil (berhasil/gagal),
dan log dari **Pengaturan → Diagnostik → Bagikan log** (aktifkan "Log debug" lebih dulu).

## Gaya kode

Kotlin official style, 4 spasi, komentar menjelaskan *mengapa*. Commit kecil dan deskriptif.
