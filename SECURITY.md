# Security

## Model keamanan

| Aspek | Implementasi |
|---|---|
| Pairing | Legacy AirPlay pair-setup/pair-verify: Ed25519 (identitas penerima) + X25519 (ECDH) per sesi. |
| Enkripsi stream | Video: AES-128-CTR (FairPlay SAP v3 + kunci turunan pair-verify). Audio: AES-128-CBC. |
| Penyimpanan identitas | Seed Ed25519 dienkripsi AES-256-GCM dengan kunci di **Android Keystore** (tidak dapat diekspor). Dikecualikan dari backup cloud. |
| Persetujuan perangkat | Default: setiap iPhone baru harus **disetujui di tablet** (tidak ada pairing diam-diam). Bisa diizinkan/diblokir permanen di Device Manager. |
| Apple Account | Tidak digunakan. Aplikasi **tidak pernah** meminta kata sandi Apple dan tidak menampilkan formulir login. |
| Data pribadi | Hanya nama/model/versi iOS yang dikirim iPhone sendiri, disimpan lokal (Room) untuk riwayat. Tidak ada analitik, tidak ada server. |
| Izin | Jaringan (AirPlay), notifikasi (layanan latar depan), Bluetooth "Perangkat di sekitar" (tanpa lokasi), USB per-perangkat via dialog sistem. |

## Keterbatasan yang perlu diketahui

- Penerima AirPlay mendengarkan di port TCP 7000 di jaringan lokal selama diaktifkan. Matikan saat tidak dipakai (tombol di Dashboard/notifikasi).
- Implementasi X25519 memakai `BigInteger` (tidak constant-time). Risiko timing attack di LAN dianggap rendah karena dipakai sekali per pairing, tetapi bukan kriptografi kelas server.
- Pairing legacy AirPlay tidak memakai PIN; perlindungan terhadap pengirim tak dikenal berasal dari dialog persetujuan di tablet.

## Kunci penandatanganan APK

`keystore/iphonemiror-public-test.jks` adalah **kunci uji publik** yang sengaja di-commit agar setiap build
(lokal dan CI) menghasilkan APK rilis yang bisa dipasang dan diperbarui. **Siapa pun dapat menandatangani
dengan kunci ini**, jadi jangan gunakan untuk distribusi publik. Untuk distribusi sungguhan, set secret
GitHub Actions berikut dan buat keystore Anda sendiri:

```
RELEASE_KEYSTORE_PATH, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
```

## Melaporkan kerentanan

Jangan buka issue publik untuk kerentanan. Gunakan *GitHub Security Advisories* (tab Security →
Report a vulnerability) di repository ini.
