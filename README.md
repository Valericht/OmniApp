# Omni-3X Monitor (APK)

Android (Kotlin, minSdk 26) untuk memonitor dan mengontrol robot Omni-3X lewat WebSocket `ws://192.168.4.1:81/ws`.
Firmware tidak diubah; protokol: `omni3x/src/webui.cpp`, PRD: `omni3x/prd_app.md`.

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
