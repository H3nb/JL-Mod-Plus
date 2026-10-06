# Native sampled decoder dependencies

The PCM adapter and legacy SMAF waveform converter use one FFmpeg n8.1.3 build. Its public headers and libraries
are built together; Gradle runs the recipe per requested ABI before NDK configuration.
There is no FFmpegKit AAR, CLI wrapper or second FFmpeg core set.

- FFmpeg tag n8.1.3, commit `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7`.
  Release archive SHA256:
  `7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3`.
  Verify release signatures against the upstream release key fingerprint
  `FCF986EA15E6E293A5644F10B4322F04D67658D8` when updating this pin.
- OpenCORE-AMR 0.1.6, commit `7dba8c32238418ce0b316a852b2224df586ca896`.
  Archive SHA256:
  `fc302cea3b65072f87d950d77ee5a7014347536039a7de7668da2891d386147e`.
  Only NB/WB decoders are included. The native FFmpeg AMR decoders are disabled;
  real generated SID/DTX fixtures fail there and succeed with OpenCORE.
- FFmpeg is configured with `--enable-version3` (LGPL-3.0-or-later), without GPL
  libraries. OpenCORE retains its Apache-2.0 license. The release sources contain
  their complete inherited notices and license texts.
- No local FFmpeg source patch is required. All file reads use bounded custom
  AVIO; protocols/network, command-line tools, muxers and encoders are disabled.
  The legacy converter writes its small PCM WAV header directly.

The explicit whitelist retains PCM WAV (unsigned 8-bit, signed 16/24/32-bit and
32-bit float), G.711 A-law/mu-law and Microsoft GSM WAV, IMA ADPCM WAV, MP3,
AAC/MP4 and the already advertised AMR NB/WB. The WAV subset follows the
[Android extractor](https://android.googlesource.com/platform/frameworks/av/+/67ab6c990ebd663df97260bfa432e83179484f10/media/extractors/wav/WAVExtractor.cpp)
previously used by MediaPlayer; IMA ADPCM was handled by the old conversion.
MMF/Yamaha ADPCM is retained solely for the existing legacy waveform conversion;
it does not expand the shared PCM Player whitelist or add SMAF sequence synthesis.
The AC3 parser is an internal dependency of the AAC parser; no AC3
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

Each `install-<ABI>` contains four shared libraries (`avformat`, `avcodec`,
`avutil`, `swresample`), matching public headers
including generated configuration headers, and SHA256 checksums. ARMv7 retains the `_neon`
filenames used by the native integration. All profiles use API 23 and 16 KiB
ELF alignment. Gradle owns reuse of compiled native dependency outputs through
its up-to-date checks and Build Cache. When native dependency work executes, the
shared preparation service verifies the pinned archives and rematerializes the
canonical FFmpeg/OpenCORE source trees at most once per Gradle invocation before
ABI compilation. Each executing ABI task then rebuilds its compiled output fresh;
downloaded archives may remain local, but extracted source trees and compiled
outputs are not trusted as a second cache layer. NDK packages the libraries once,
including the ARMv7 `_neon` names; no `pickFirst` or global native-library
exclusion hides collisions. The runtime decoder additionally caps streams to sixteen, codec
configuration to 64 KiB and decoded frame staging to 262,144 stereo frames.

Windows GNU make can truncate long header-install commands, so the helper
copies the headers listed by upstream's `HEADERS`/`BUILT_HEADERS` individually.

The native test uses owned generated media in
`app/src/androidTest/assets/audio`, without output hardware, commercial game
files or external soundbanks. It checks exact decoded frame counts, consumed
timestamps, seek/flush, final drain, underflow, deallocate, bounded worker
ownership and close while a producer is active. ARM64 can execute on a device;
cross-compilation and export checks alone do not execute the other ABIs.

The legacy converter preserves mono/stereo channels and produces U8 WAV at
16 kHz, using caller-owned temporary output. It caps regular-file input/output
at 64 MiB, codec configuration at 64 KiB, decode/resample frame expansion at
262,144 frames and conversion time at ten seconds. Unsupported/non-ADPCM MMMD
keeps its original playback route. `LegacySmafConversionTest` exercises the
production JNI/source boundary using the generated MMF fixtures, including
UTF-8 paths, publication, failure cleanup and nonempty-output rejection.
