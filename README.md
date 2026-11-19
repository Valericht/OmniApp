# Omni-3X Monitor (APK)

Android (Kotlin, minSdk 26) untuk memonitor dan mengontrol robot Omni-3X lewat WebSocket `ws://192.168.4.1:81/ws`.
Protokol: `omni3x/src/webui.cpp`, PRD: `omni3x/prd_app.md`. Disesuaikan dengan firmware Lapangan v2 (PRD v2.1): telemetri `slf`, `as`, `re`, `smin`, `av`, `rst`, `arr`, waypoint 8 kolom (`wallF`), perintah `wp_sync`, fase VERIFY (`np` = 3), penolakan 7 dan 8. Firmware lama tetap terbaca (field baru hilang = nilai bawaan).

- Build: `./gradlew assembleDebug` (perlu Android SDK; `local.properties` berisi `sdk.dir=...`).
- Tanpa robot: `python3 tools/mock_ws.py` di PC, lalu di app: Lanjutan, Pengaturan app, host = IP PC (HP dan PC satu WiFi).
- Dengan robot: sambungkan HP ke WiFi `Omni3X` (sandi `omni3x1234`); app tersambung sendiri.

## Koneksi: WiFi dan USB
Mode di Lanjutan > Pengaturan app: **Otomatis** (USB dipakai bila hidup, selain itu WiFi), **WiFi**, atau **USB**.
USB memakai kabel OTG HP-ke-ESP32, serial 8N1 (baud bawaan 115200, ubah di pengaturan), satu pesan JSON per baris (`\n`),
isi sama dengan teks WebSocket. Firmware harus membaca/menulis JSON itu di `Serial` (lihat catatan di bawah).

## CI (GitHub Actions)
`.github/workflows/android.yml`: tiap push ke `main`/`master`, PR, atau jalan manual menjalankan `assembleDebug`;
APK diunggah sebagai artifact `OmniApp-debug-apk`.


Gripper (1 solenoid): langkah rute `G1` (ON/jepit) dan `G0` (OFF/lepas) di editor rute, mis. `1,2,G1,3,G0`; tombol Jepit / Lepas manual ada di Lanjutan > Rute preset. Protokol: kode 101/102 di `route_set`, perintah `grip`, telemetri `gp` dan `np` = 4.
# OmniApp
# OmniApp
# OmniApp
