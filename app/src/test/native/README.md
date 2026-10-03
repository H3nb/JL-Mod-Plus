# Sonivox production callback test

After `:app:assembleEmulatorDebug` builds ARM64, run from the repository root:

```powershell
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial>
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial> -Bank <local-bank-path>
```

Without `-Device`, the script only compiles. It uses the selected NDK and the
latest assembled ARM64 Sonivox archive, plus the current production Player and
memory-host source. Build again after changing the core before running the test.
An optional bank remains outside the repository; no proprietary asset is needed
for the embedded-bank test. The script writes only to a unique host temporary
directory and `/data/local/tmp/jl-sonivox-native-test` on the device. It does not
install an APK, open an audio output, or change application data/device settings.

The friend test runs the actual callback at 257 and 511 frames, checks guard
buffers and bit-exact PCM against 256-frame output, verifies held voices/staging
survive host suspension, and rejects real-time core allocation attempts. It
exercises atomic MIDI queue saturation, delayed old error publication after
replacement, seven healthy recovery episodes on one request (including host
resume), bounded disconnect storms, reopen failure and concurrent close during
a controlled blocked reopen, source cursor
duplication/bounds, SMF time/seek, PREFETCHED interactive
MIDI without advancing the sequencer, completed-media suspension with two loops,
guest-vs-host generation rules, and idempotent native resource shutdown.
Only this standalone build defines `JL_EAS_OUTPUT_TEST`: a fake Oboe device
opener permits deterministic interleavings while the actual Player management,
callback, and Sonivox core execute. The seam is absent from shipping builds.

This is deterministic callback/context evidence. It does not qualify physical
audio routing, focus eligibility, Bluetooth, timbre, acoustic timing, complete
SF2 modulation, sustained-session memory growth, or other ABIs. Android
instrumentation and whole-MIDlet tests cover the Java/Android integration.

The desktop allocation sweep compiles the exact production core source list and
memory host with a test-only fail-after allocator:

```powershell
./app/src/test/native/run-sonivox-allocation.ps1 -Banks <sf2-path>,<dls-path>
```

It requires a C11 GCC compiler on PATH (or `-Compiler <path>`), injects persistent
allocation failure at every allocation point until initialization, MIDI opening,
and each bank load succeeds, and verifies zero remaining host blocks after every
shutdown. It also rejects an incomplete bank header and verifies zero allocation
attempts during MIDI rendering. The allocator policy/counters are excluded from
shipping builds. No Android device is used by this sweep.

The bounded RMID container check compiles production `eas_file.cpp` on the host:

```powershell
./app/src/test/native/run-sonivox-rmid.ps1
```

It requires C++17 g++ on PATH (or `-Compiler <path>`). It checks root/chunk bounds,
padding, unknown INFO chunks, duplicate/missing data, nesting and the 16 MiB
limit, explicit embedded-DLS rejection, and unchanged raw SMF/WAVE bytes.
`RmidRuntimeTest` separately qualifies production Manager playback/metadata,
seek, stop/resume, loops, and source cleanup on Android.
