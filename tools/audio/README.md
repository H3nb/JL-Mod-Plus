# Native sampled decoder dependencies

The PCM adapter uses one FFmpeg n6.0 build. Its public headers and libraries
are built together; do not link against the small FFmpegKit AAR's headers or
mix that AAR's core libraries with this build.

- FFmpeg tag n6.0, commit `ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2`.
  Release archive SHA256:
  `57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082`.
- OpenCORE-AMR 0.1.6, commit `7dba8c32238418ce0b316a852b2224df586ca896`.
  Archive SHA256:
  `fc302cea3b65072f87d950d77ee5a7014347536039a7de7668da2891d386147e`.
  Only NB/WB decoders are included. The native FFmpeg AMR decoders are disabled;
  real generated SID/DTX fixtures fail there and succeed with OpenCORE.
- FFmpeg is configured with `--enable-version3` (LGPL-3.0-or-later), without GPL
  libraries. OpenCORE retains its Apache-2.0 license. The release sources contain
  their complete inherited notices and license texts.
- `ffmpegkit-saf-setters.patch` preserves the existing wrapper's four SAF
  setter/getter ABI symbols and upstream LGPL-3.0-or-later attribution. It does
  not enable a new protocol. The wrapper's existing SMAF conversion remains
  outside this migration's scope.

The explicit whitelist retains PCM WAV (unsigned 8-bit, signed 16/24/32-bit and
32-bit float), G.711 A-law/mu-law and Microsoft GSM WAV, IMA ADPCM WAV, MP3,
AAC/MP4 and the already advertised AMR NB/WB. The WAV subset follows the
[Android extractor](https://android.googlesource.com/platform/frameworks/av/+/67ab6c990ebd663df97260bfa432e83179484f10/media/extractors/wav/WAVExtractor.cpp)
previously used by MediaPlayer; IMA ADPCM was handled by the old conversion.
The original MMF/Yamaha ADPCM, PCM U8 WAV encoder,
file protocol, JNI and aresample filter are retained for the existing FFmpegKit
wrapper. The AC3 parser is an internal dependency of the n6.0 AAC parser; no AC3
decoder or public format support is added. Assembly is disabled in this bounded
decoder build; performance must be qualified with the mixed runtime workload.

Use PowerShell 7, JDK/SDK/NDK as described in [development](../../docs/development.md),
Git, and a POSIX shell with its standard utilities. On Windows, Git for Windows
provides the shell and the selected NDK provides GNU make; on Linux/macOS, use
the installed shell and make. No Android Studio or second compiler is required.
Keep the dependency cache and SDK paths free of spaces, as required by these
upstream configure/make recipes.

```powershell
./tools/audio/build-native-deps.ps1 -OutRoot ./app/build/audio-deps -Sdk $env:ANDROID_HOME -Abis arm64-v8a
./tools/audio/build-native-deps.ps1 -OutRoot ./app/build/audio-deps -Sdk $env:ANDROID_HOME -Abis arm64-v8a,armeabi-v7a,x86,x86_64
./app/src/test/native/run-pcm-decoder.ps1 -Device <adb-serial>
```

Each `install-<ABI>` contains seven shared libraries, matching public headers
including generated configuration headers, and SHA256 checksums. ARMv7 retains the `_neon`
filenames expected by the existing wrapper. All profiles use API 23 and 16 KiB
ELF alignment. A recipe/NDK/ABI stamp avoids repeating a completed build.
Windows GNU make can truncate long header-install commands, so the helper
copies the headers listed by upstream's `HEADERS`/`BUILT_HEADERS` individually.

The native test uses owned generated media in
`app/src/androidTest/assets/audio`, without output hardware, commercial game
files or external soundbanks. It checks exact decoded frame counts, consumed
timestamps, seek/flush, final drain, underflow, deallocate, bounded worker
ownership and close while a producer is active. ARM64 can execute on a device;
cross-compilation and export checks alone do not execute the other ABIs.
