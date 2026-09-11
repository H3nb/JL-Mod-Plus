# Namespace migration inventory

This artifact records the bounded namespace migration described by the v2 handoff. It is kept in the repository so the compatibility exceptions are reviewable without retaining a large search dump in the task conversation.

## Baseline and scope

- Status: Completed
- Audit snapshot: `d7498ebaf9614c481dd4e99d213fe47be571826c`
- Repository: `H3nb/JL-Mod-Plus`
- Verified base: `origin/alpha` at `0f2efca7d14bcb0435bffd41106d27688c50c51c`
- Implementation branch: `refactor/namespace`
- Moved app entries: 417 total — 312 Java/Kotlin/AIDL/test source entries and 105 PNG screenshot references.
- Moved entries by source set: `main` 199, `debug` 1, `test` 72, `androidTest` 30, `screenshotTest` 10, screenshot references 105.
- Ten screenshot references under `javax/microedition` remain unchanged because they belong to the guest compatibility API.
- No destination collisions were found across the participating source sets before the move.

## Approved mapping

| Boundary | Old namespace | Canonical namespace |
| --- | --- | --- |
| app host | `ru.playsoftware.j2meloader` | `io.github.h3nb.jlmodplus` |
| installer | `ru.woesss.j2me.installer` | `io.github.h3nb.jlmodplus.installer` |
| jar | `ru.woesss.j2me.jar` | `io.github.h3nb.jlmodplus.jar` |
| MMAPI | `ru.woesss.j2me.mmapi` | `io.github.h3nb.jlmodplus.mmapi` |
| Micro3D | `ru.woesss.j2me.micro3d` | `io.github.h3nb.jlmodplus.micro3d` |
| GLES utility | `ru.woesss.gles` | `io.github.h3nb.jlmodplus.gles` |

The implementation namespace is now declared at `app/build.gradle.kts:45`. Application IDs remain `io.github.h3nb.jlmodplus` (default), `io.github.h3nb.jlmodplus.debug` (emulator debug suffix), and the existing `com.example.androidlet.*` midlet flavor IDs. The midlet flavor remains disabled by `beforeVariants` at `app/build.gradle.kts:158`. The dexlib namespace at `dexlib/build.gradle.kts:9` and its local `ru.woesss.util.*` sources are intentionally outside the mapping.

## Compatibility exceptions

These old strings are retained deliberately and are not missed replacements:

- `app/src/main/AndroidManifest.xml:62-72,204-216,260-262`: explicit-only aliases preserve the old absolute component names for `LauncherActivity`, `MainActivity`, `config.ConfigActivity`, and `settings.SettingsActivity`; only the launcher alias owns the `MAIN`/`LAUNCHER` filter. The new target activities are declared before their aliases.
- `app/src/main/AndroidManifest.xml:294`: the FileProvider authority remains `${applicationId}.diagnostic-files`.
- `app/src/debug/AndroidManifest.xml:7`: the debug crash probe now references the canonical implementation class while its compatibility extra remains old.
- `app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEditorActivity.kt:99`: `ru.playsoftware.j2meloader.memory.extra.RUNTIME_TOKEN` remains a protocol key.
- `app/src/main/java/io/github/h3nb/jlmodplus/crashes/CrashReportDetailsActivity.java:39`: `ru.playsoftware.j2meloader.crashes.REPORT_ID` remains a protocol key.
- `app/src/debug/java/io/github/h3nb/jlmodplus/crashes/CrashRuntimeProbeActivity.java:23`: `ru.playsoftware.j2meloader.crashes.RUNTIME_PROBE_MODE` remains a debug protocol key.
- `app/src/main/java/io/github/h3nb/jlmodplus/crashes/LocalDiagnosticRepository.java:243-244` and `NativeTombstoneSummary.java:389-391`: both canonical and legacy host prefixes remain actionable; stored reports are not rewritten.
- Old diagnostic/process/package fixtures in `app/src/test/java/io/github/h3nb/jlmodplus/crashes/` retain their old values intentionally. New canonical fixtures were added alongside them.
- Historical Room snapshots remain under `app/schemas/ru.playsoftware.j2meloader.librarydb.LibraryDatabase/`; canonical byte-identical copies are under `app/schemas/io.github.h3nb.jlmodplus.librarydb.LibraryDatabase/`.

Unchanged package families are `com.*`, `javax.*`, `mmpp.*`, `org.*`, and the local dexlib `ru.woesss.util.*`. The guest loader allowlist was not expanded to include either host namespace.

## AIDL move

All four cross-process interfaces moved to `app/src/main/aidl/io/github/h3nb/jlmodplus/memory/` with the canonical package declaration:

- `IMemoryEngineCallback.aidl`
- `IMemoryEngineService.aidl`
- `IMemoryTargetBridge.aidl`
- `IMemoryTargetCallback.aidl`

Generated AIDL output was not edited. Java service/client references were updated to the moved interfaces.

## JNI symbol inventory

The following 38 exported Java-style entry points were renamed. GLES and Micro3D methods are static Java natives and therefore use `jclass`; EAS and TSF methods are instance Java natives and use `jobject`. Java signatures below are the declarations in the moved classes.

### GLES — `app/src/main/cpp/gles_utils/utils.cpp` and `utils.h`

| JNI symbol | Java declaration |
| --- | --- |
| `Java_io_github_h3nb_jlmodplus_gles_GLESUtils_blit` | `public static native void blit(int, int, int, int, Bitmap)` |
| `Java_io_github_h3nb_jlmodplus_gles_GLESUtils_blit2` | `public static native void blit2(int, int, int, int, Bitmap)` |

### Micro3D — `app/src/main/cpp/micro3d/src/utils.cpp`

| JNI symbol | Java declaration |
| --- | --- |
| `Java_io_github_h3nb_jlmodplus_micro3d_Utils_fillBuffer` | `static native void fillBuffer(FloatBuffer, FloatBuffer, int[])` |
| `Java_io_github_h3nb_jlmodplus_micro3d_Utils_glReadPixels` | `static native void glReadPixels(int, int, int, int, Bitmap)` |
| `Java_io_github_h3nb_jlmodplus_micro3d_Utils_transform` | `static native void transform(FloatBuffer, FloatBuffer, FloatBuffer, FloatBuffer, ByteBuffer, float[])` |

### EAS — `app/src/main/cpp/mmapi_eas/eas_player_jni.cpp`

| JNI symbol | Java declaration |
| --- | --- |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_loadSoundBank` | `public native void loadSoundBank(String)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_createPlayer` | `public native long createPlayer(String)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_finalize` | `public native void finalize(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_realize` | `public native void realize(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_prefetch` | `public native void prefetch(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_start` | `public native void start(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_pause` | `public native void pause(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_deallocate` | `public native void deallocate(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_close` | `public native void close(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_setMediaTime` | `public native long setMediaTime(long, long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_getMediaTime` | `public native long getMediaTime(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_setRepeat` | `public native void setRepeat(long, int)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_setVolume` | `public native void setVolume(long, float, float)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_getDuration` | `public native long getDuration(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_setListener` | `public native void setListener(long, Object)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_setDataSource` | `public native void setDataSource(long, byte[])` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_eas_LibEAS_writeMIDI` | `public native int writeMIDI(long, byte[], int, int)` |

### TSF — `app/src/main/cpp/mmapi_tsf/tsf_player_jni.cpp`

| JNI symbol | Java declaration |
| --- | --- |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_loadSoundBank` | `public native void loadSoundBank(String)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_createPlayer` | `public native long createPlayer(String)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_finalize` | `public native void finalize(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_realize` | `public native void realize(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_prefetch` | `public native void prefetch(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_start` | `public native void start(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_pause` | `public native void pause(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_deallocate` | `public native void deallocate(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_close` | `public native void close(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_setMediaTime` | `public native long setMediaTime(long, long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_getMediaTime` | `public native long getMediaTime(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_setRepeat` | `public native void setRepeat(long, int)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_setVolume` | `public native void setVolume(long, float, float)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_getDuration` | `public native long getDuration(long)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_setListener` | `public native void setListener(long, Object)` |
| `Java_io_github_h3nb_jlmodplus_mmapi_synth_tsf_LibTSF_setDataSource` | `public native void setDataSource(long, byte[])` |

`writeMIDI` remains a non-native TSF method. `System.loadLibrary` names (`c++_shared`, `oboe`, `mmapi_common`, `mmapi_eas`, `mmapi_tsf`, `gles_utils`, `micro3d`) were not changed.

## Room and screenshot artifacts

- `LibraryDatabase.FILE_NAME` remains `JL-Mod-library.db` and `SCHEMA_VERSION` remains 2 (`app/src/main/java/io/github/h3nb/jlmodplus/librarydb/LibraryDatabase.kt:39-40`).
- Both historical JSON snapshots retain identity hash `b9bfdc9e912275576a5800984be3d4a7`; old and canonical working-tree SHA-256 values match for v1 (`541030ce...`) and v2 (`bfaa147...`).
- `LibraryMigrationTest` retains v1 -> v2 coverage and adds v2-old-schema -> v2-new-schema coverage with state, `PRAGMA user_version`, and `room_master_table` identity assertions (`app/src/test/java/io/github/h3nb/jlmodplus/librarydb/LibraryMigrationTest.kt:109-184`).
- PNG bytes remain at their moved paths; the complete 105-entry hash record is [namespace-migration-screenshot-sha256.txt](namespace-migration-screenshot-sha256.txt).

## APK comparison checkpoint (namespace-only commit)

The pre-migration `emulatorDebug` APKs were captured before the first namespace edit. Their SHA-256 values are recorded here so the namespace-only package can be compared without relying on an uninstall or a clean build:

| ABI | Baseline APK SHA-256 | Namespace-migration APK SHA-256 |
| --- | --- | --- |
| arm64-v8a / universal | `a85878f291afa925de7f139e70d9045a83283bbb1e8b3e95e3ab7f978ab0b836` | `2277162cf617b39b6da97eb7e06d1e535dc0057537de60a42b626f923eee9150` |
| armeabi-v7a | `2902c07bd9ccb68a062ec587d021aee9057c9c563e694deaaf1aab16f9c7256a` | `d3dd64db596acd03de8668f0377e68801d4e7517f98539583dba964facc3cafc` |
| x86 | `a099d784d117f71ccc9d0ff115847a3f13a27bb70719d3797540a615be108b70` | `b190634a4004704e5fcd39b9129c75f71c4be0efae46aaf2c8c1a71a45a46e77` |
| x86_64 | `ffc21fe858b08ed40cfaa4ae28f4353a9f5c428e87d5961fade3a392cb8f844a` | `8cce49b589d172222bda77b845f157bca455264cb772e68f87406e83a960fc01` |

The baseline `aapt2 dump badging` reported package `io.github.h3nb.jlmodplus.debug`, `minSdkVersion 23`, `targetSdkVersion 36`, and launcher component `ru.playsoftware.j2meloader.LauncherActivity`. The namespace-migration manifest should retain the IDs, permissions, process names, and `${applicationId}.diagnostic-files` authority; the intentional differences are canonical implementation names plus the four legacy aliases.

The namespace-migration `aapt2 dump badging` reports the same debug package, min/target SDK, and `arm64-v8a` native ABI. The merged manifest contains 15 activities and 4 aliases, with exactly one `MAIN`/`LAUNCHER` filter on the legacy launcher alias and the debug FileProvider authority `io.github.h3nb.jlmodplus.debug.diagnostic-files`.

Post-build `llvm-nm -D --defined-only` on the stripped arm64 libraries found 2 canonical GLES, 3 Micro3D, 17 EAS, and 16 TSF JNI exports, with zero old `Java_ru_woesss_*` exports in those four libraries.

## Follow-up runtime validation

The follow-up fixes were validated on 2026-09-12 with the `codex-installer-api36` Android 16/API 36 x86_64 emulator. The debug package remained `io.github.h3nb.jlmodplus.debug`; the emulator test APK was installed over the existing package without uninstalling it or clearing application data.

- The 37 test cases that motivated this follow-up now pass.
- All 173 unique connected instrumentation tests pass across four bounded x86_64 shards: 56 + 39 + 51 + 32 executions, with five intentional `AdaptiveDialogComposeTest` overlap executions between shards.
- `:app:testEmulatorDebugUnitTest` passes.
- `:app:lintEmulatorDebug :dexlib:lintDebug` passes.
- `:app:validateEmulatorDebugScreenshotTest` passes after refreshing the affected text-only screenshot references.
- `:app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest` passes for the default arm64-v8a build; the current arm64 debug APK SHA-256 is `e6221ed4d79ce110d955356e6dc38b1ab79be000f06eaa11bbfa0ec9d5754b3c`.
- Focused runtime coverage includes `M3GRuntimeTest` (9/9) and `MemoryIpcRuntimeTest` (1/1), including exact-ABI native loading and service rebind/token retention.

The monolithic connected-test attempt reached 166/173 before Android 16's WindowManager watchdog killed the system process after a 72-second monitor stall. The bounded shard results above are the reliable connected-test evidence; the watchdog event was emulator infrastructure instability rather than an application assertion failure.

The install-over checkpoint above the namespace-only commit preserved the library database, custom metadata, favorites, collections, preferences/configuration, converted artifacts, legacy activity aliases, and an existing converted MIDlet launch. No ClassNotFoundException, NoClassDefFoundError, BadParcelable, fatal linker, or namespace errors were observed. A tunable shader was not available through the exercised UI, so `ShaderTuneAlert` state restoration remains an unexercised compatibility path rather than being covered by a test shim.

## Attribution

Inherited Apache-2.0 source retains its original copyright, author, year, license, and upstream attribution. Modified inherited files receive only the neutral `Modified for JL-Mod Plus.` notice when no suitable modification notice already existed. No root license, dependency identity, guest API, or persisted protocol value was normalized as part of this migration.
