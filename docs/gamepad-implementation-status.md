# Gamepad implementation checkpoint

Baseline: `alpha` / `054e1f667`; branch kerja: `feature/extend-gamepad-support`.
Scope: F01–F10 sesuai handoff v1.0 dan desain v1.2; F08–F10 sengaja wajib.
Perubahan lokal pengguna yang dilindungi tetap berupa tiga penghapusan di `docs/` (`memory-editor-kotlin-audit.md`, `namespace-migration-inventory.md`, `namespace-migration-screenshot-sha256.txt`); tidak dipulihkan, di-stage, atau diubah.

Status paket/gate:
- P0/G0 PASS: current-state audit, handoff/design, AGENTS, source/config/tests/workflows/docs, dan J2ME_Docs untuk Canvas/GameCanvas/Display.
- P1/G1 PASS: pure config, analog, calibration, capture, ownership/repeat/lifecycle, pointer lease/controller, dan GameCanvas alias.
- P2/G2 PARTIAL: router Android, Canvas/Screen boundary, lifecycle, keypad, host navigation, capture/calibration UI terkompilasi; connected instrumentation BLOCKED. Library navigation kini memiliki event hub lossless, focus restore/scroll, grid row-boundary navigation, modal isolation, dan raw-key bridge untuk dialog window. Config juga mengikuti device add/change/remove; selection mapping, capture, test, dan calibration nonaktif tanpa controller kompatibel, sementara editor/reset/help tetap tersedia untuk konfigurasi offline.
- P3/G3 PARTIAL: profile JSON/opaque preservation, atomic save/recovery, editor, capture, diagnosis, calibration memiliki unit evidence; live UI interaction belum dijalankan.
- P4/G4 PARTIAL: MainActivity/MicroActivity/ConfigActivity dan Screen wiring ada, screenshot runtime-menu serta Library AppActions valid; focus/Back/Screen live interaction belum terverifikasi. B pada root Library dibiarkan jatuh ke Android Back, sedangkan modal Library dan guest Screen mengonsumsi Back/EXIT sesuai konteks. Mapping Help dan Test Controller hanya tersedia di MIDlet Config; keduanya tidak lagi muncul pada menu Back runtime MIDlet, dan Mapping Help juga tidak dipicu dari host utama.
- P5/G5 PARTIAL: keypad tambahan 0–9/STAR/POUND/soft L/R dan A-hold terhubung ke ledger; device delivery belum terverifikasi.
- P6/G6 PARTIAL: proportional cursor, click/drag, physical takeover dan arbitration diuji pure; device pointer callback belum terverifikasi.
- P7/G7 PARTIAL: virtual touch joystick, radius clamp, center-before-release, visual state dan arbitration diuji pure; device gesture belum terverifikasi.
- P8/G8 PARTIAL: seluruh build/static checks pass, tetapi connected/hardware/JAR evidence belum tersedia. APK arm64 yang dipasang berisi seluruh 18 native library yang diharapkan.

Requirement → evidence aktual:
- F01–F07: `ControllerConfigTest`, `StickProcessorTest`, `ControllerInputRouterTest`, `KeyOwnershipLedgerTest`, `ControllerHostOwnershipLedgerTest`, `ControllerLifecycleGateTest`, `ControllerCaptureSessionTest`, `GamepadCalibrationSessionTest`, dan `ProfilesManagerAtomicSaveTest` lulus dalam full JVM suite.
- F08: `GameCanvasKeyStateTest` lulus; `VirtualKeyboard`/Canvas integration terkompilasi dan lint bersih, tetapi physical keypad runtime belum dijalankan.
- F09–F10: `PointerLeaseControllerTest` dan `PointerControllersTest` lulus; router/Canvas integration terkompilasi, tetapi device/hardware probe belum dijalankan.
- F06 host navigation: `LibraryControllerEventHubTest` dan `LibraryControllerNavigationTest` lulus; `LibraryComposeTest` telah ditambahkan untuk jalur Compose controller tetapi belum dapat dieksekusi tanpa emulator online.
- Full unit XML: 636 tests, 0 failures, 0 errors, 1 skipped (118 files).
- Localization: 169 key `config_gamepad*` pada `values/strings.xml` memiliki pasangan 169 key pada `values-in/strings.xml`; tidak ada key gamepad yang diberi `translatable="false"`. Copy mengikuti `docs/ui-copy-style.md`, dan section Gamepad tidak memakai highlight aksen; highlight tetap hanya pada preset/profile.
- Modality follow-up: indikator controller focus pada Library card, dialog aksi aplikasi, menu runtime, dan overflow command guest `Screen` kini tersembunyi pada tampilan touch-only; indikator baru muncul setelah navigasi keypad/keyboard atau gamepad, lalu disembunyikan kembali ketika gesture touch dimulai. `LibraryComposeTest`, `RuntimeMenuComposeTest`, `ScreenSoftBarComposeTest`, `NavigationInputModalityTest`, dan screenshot AppActions memberi evidence untuk state awal serta transisi controller; input device live tetap belum tersedia.

Authoritative implementation: `app/src/main/java/io/github/h3nb/jlmodplus/input/` (`ControllerConfig`, `ControllerInputRouter`, ledgers, lifecycle, capture, calibration, `PointerControllers`); `Canvas`, `PointerEvent`, `GameCanvas`, `VirtualKeyboard`; `MicroActivity`, `MainActivity`, `ConfigActivity`; `GamepadConfigCompose`, `ConfigFormState`, `ProfileModel`, `ProfilesManager`; runtime help/diagnosis/menu; tests dan checkpoint ini.

Keputusan/evidence penting:
- Host controller masuk melalui satu router; Canvas tetap mempertahankan keyboard/IME dan guest boundary lama. Guest pointer virtual memakai channel MIDP `0`; source/target/generation tetap terpisah secara internal.
- DOWN menyimpan binding/target/generation; UP, repeat, remap, modal, target change, focus loss, hide/show, disconnect, dan controller takeover ditutup melalui ledger/lifecycle barrier secara idempotent. Physical touch tetap lewat jalur MIDP existing agar tidak diduplikasi oleh router.
- Config editor memakai draft sampai Save; schema/enum/action future atau opaque dipertahankan dan hanya reset eksplisit yang menghapusnya. Save memakai temp + backup + rename/recovery portable tanpa dependency/toolchain baru.
- Pointer mode hanya aktif eksplisit; trigger axis+button di-union dan stick kiri/kanan diproses independen.

Command aktual terakhir:
- `gradlew.bat :app:compileEmulatorDebugKotlin :app:compileEmulatorDebugJavaWithJavac :app:compileEmulatorDebugAndroidTestKotlin --console=plain` → exit 0.
- `gradlew.bat :app:testEmulatorDebugUnitTest --console=plain` → exit 0; 636/0/0/1 seperti di atas.
- `gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug --console=plain` → exit 0; warning/hint informasional saja.
- `gradlew.bat :app:updateEmulatorDebugScreenshotTest --console=plain` → exit 0; reference yang terdampak copy, focus, dan menu diperbarui secara serial.
- `gradlew.bat :app:validateEmulatorDebugScreenshotTest --console=plain` → exit 0; 114 screenshot test.
- `gradlew.bat :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest --console=plain` → exit 0; APK debug dan Android-test APK terbentuk.
- `aapt2 dump badging app/build/outputs/apk/emulator/debug/app-emulator-arm64-v8a-debug.apk` serta perbandingan ZIP/intermediate native → exit 0; `native-code: arm64-v8a`, 18/18 library cocok.
- `adb -s adb-d38f39c7-GPq9BM._adb-tls-connect._tcp install -r app/build/outputs/apk/emulator/debug/app-emulator-arm64-v8a-debug.apk` (earlier completed build) → exit 0; package `io.github.h3nb.jlmodplus.debug`, version `0.1.0`, ABI perangkat `arm64-v8a`.
- `gradlew.bat :app:testEmulatorDebugUnitTest --console=plain` (follow-up modality) → exit 0; full JVM suite tetap lulus.
- `gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug --console=plain` (follow-up modality) → exit 0; warning/hint informasional saja.
- `gradlew.bat :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest --console=plain` (final follow-up build) → exit 0; APK dan Android-test APK terbentuk, termasuk native packaging verification.
- `gradlew.bat :app:lintEmulatorDebug :dexlib:lintDebug :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest --console=plain` (post guest Screen/runtime modality) → exit 0; lint, compile, Android-test packaging, dan native packaging verification lulus.
- `gradlew.bat :app:updateEmulatorDebugScreenshotTest --console=plain` dan `gradlew.bat :app:validateEmulatorDebugScreenshotTest --console=plain` (final follow-up) → exit 0; reference UI tersinkron dan validasi screenshot lulus.
- `gradlew.bat :app:testEmulatorDebugUnitTest --console=plain` (final follow-up) → exit 0; 636/0/0/1 dari 118 XML suite.
- `aapt2 dump badging .../app-emulator-arm64-v8a-debug.apk` serta perbandingan ZIP/intermediate native (final follow-up) → exit 0; `native-code: arm64-v8a`, `NATIVE_EXPECTED=18 NATIVE_APK=18 MISSING=0 EXTRA=0`.
- `adb devices -l` / `adb connect 192.168.69.214:5555` (follow-up install check) → perangkat fisik kini `unauthorized` dan emulator `emulator-5554` tetap `offline`; reinstall final belum dapat dilakukan tanpa otorisasi ulang pada perangkat.
- `git diff --check` → exit 0; hanya warning normal LF→CRLF dari Git.

Screenshot yang benar-benar diinspeksi: `ConfigGamepadUnavailableScreenshot` (large font, controller tidak tersedia), reference runtime-menu, dan reference Library AppActions yang terdampak update. Tidak ada screenshot hardware/gamepad live.
Hardware/JAR/probe: tidak ada controller fisik; emulator `emulator-5554` tetap offline/tidak dapat diidentifikasi (`Unknown API Level`, `0 compatible devices`, emulator console timeout); guest JAR probe belum dijalankan. APK sebelumnya berhasil diluncurkan di perangkat fisik via ADB tanpa temuan `UnsatisfiedLinkError`/`dlopen failed` pada logcat yang diperiksa; pada follow-up ini perangkat meminta autentikasi ulang, sehingga reinstall final dan input gamepad belum dapat diuji.

Komentar modifikasi untuk file upstream yang tersentuh memakai `Modified for JL-Mod Plus`; tidak ada komentar tahun-spesifik yang ditambahkan pada file baru.

Review diff terhadap checklist handoff §11:
- [x] Jalur controller terpusat pada `ControllerInputRouter`; dialog Library memakai raw-key bridge yang sama dan tidak membuat producer kedua.
- [x] Producer host/guest, DualKey, multi-contact VirtualKeyboard, repeat, cancel, dan Unicode tetap melalui ledger yang relevan; dibuktikan oleh unit tests input dan compile.
- [x] Snapshot binding/target/generation, timer, historical sample, remap, modal, takeover, dan disconnect memiliki guard/tes pure.
- [ ] Ordering hide/show/setCurrent terhadap antrean UI nyata belum dapat dibuktikan karena connected emulator tidak tersedia.
- [x] Alias GameCanvas diuji pada dua poll dan latch/rearm; guest JAR callback nyata masih belum teruji.
- [x] Pointer lease internal tidak sama dengan guest channel `0`; multi-touch existing tetap dipertahankan saat mode off; callback device belum teruji.
- [x] Indikator `selected/focused` presentation tidak dipaksa tampil pada touch-only; state modality dipisah dari indeks focus dan dibersihkan pada pointer-down.
- [ ] Setiap setting sudah mempunyai jalur model/runtime/save-load/reset/cancel atau preservation, tetapi interaksi UI live dan device delivery belum dapat diverifikasi.
- [x] Hasil, working branch, command aktual, screenshot yang diinspeksi, serta batas hardware/JAR dicatat di checkpoint ini.

FAIL/BLOCKED/NOT_RUN:
- BLOCKED: G2–G8 live Android/device portions, karena emulator tidak tersedia sehat setelah recovery non-destruktif.
- NOT_RUN: physical controller matrix, Screen/guest JAR probe, dan validasi lintas ukuran/device dengan input nyata.
- Tidak ada FAIL pada compile, full JVM test, lint, screenshot validation, atau APK assembly final. Lint sempat menemukan mismatch format `%s`/`int` pada diagnosis Indonesia; sudah diperbaiki menjadi `%d` pada kedua locale dan lint final lulus.

Maksimal langkah berikutnya:
1. Saat emulator sehat, jalankan `-PjlmodRuntimeTestAbi=x86_64 :app:connectedEmulatorDebugAndroidTest` dan dokumentasikan setiap case G2–G8.
2. Jalankan matrix controller fisik untuk F01–F10, termasuk focus loss, disconnect, capture, keypad, cursor, joystick, dan takeover.
3. Jalankan guest JAR probe untuk Screen/keypad/pointer serta review diff/PR setelah evidence runtime tersedia.
