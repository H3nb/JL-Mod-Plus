# Audio runtime and qualification

Sonivox supplies synthesis for MMAPI and the retained vendor APIs that delegate
to MMAPI. The pinned upstream source and local core changes are recorded in
[the native provenance file](../app/src/main/cpp/sonivox/UPSTREAM.md). Adapter
ownership, stream management, and Android focus policy live outside that core.

## Ownership and playback policy

Each synthesis Player owns its own EAS context, sequencer, voices, bank collection,
and Oboe output. The runtime owns one audio-focus coordinator shared with sampled
`MicroPlayer` audio. Streams are separate; there is no runtime PCM mixer. A bank
choice is captured from the runtime preset when its synthesis library is created.
Loading a new runtime does not mutate the bank in an existing Player.

The Java wrapper owns guest MMAPI state. Native events report rendering and
output facts through generation-fenced polling on the management path. Listeners
run outside the Player lock. `CLOSED` is terminal, source disconnection and the
final `CLOSED` event happen once, and the callback executor ends after final
delivery. Known media time and duration remain available after deallocation.
MIDI sending requires `PREFETCHED` or `STARTED`; optional bank queries remain
unsupported and do not advertise fictional bank contents.

Host suspension preserves guest playback intent and MMAPI state while freezing
output and media progression. Activity foreground loss and transient focus loss
(including duck requests) suspend audio. Eligible foreground return or focus
gain resumes only the same Player request. Guest stop, close, deallocation, or
runtime termination invalidate that request. Permanent focus loss revokes prior
intent; a fresh explicit playback request is required. Focus denial throws a
`MediaException` without a `STARTED` event or delayed autoplay request. This
foreground policy meets the Android API35+ focus restriction without introducing
a foreground service. It is separate from LCDUI display ownership and AMS
foreground selection.

This host suspension does not emit MMAPI `DEVICE_UNAVAILABLE`: that event would
require a transition to `REALIZED` and a later `DEVICE_AVAILABLE` or `ERROR`.
Recoverable Oboe disconnection reopens output on the management path, preserving
EAS voices, position, and staged PCM. Irrecoverable media/output failure closes
the Player and emits an explained `ERROR` followed by `CLOSED`.

## Content and compatibility boundaries

Manager locator, stream, and `DataSource` creation share backend selection.
Bounded content recognition takes precedence over MIME hints, including Nokia
OTA bytes labelled `audio/midi`. Recognized corrupt synthesis and bank failures
are surfaced instead of falling through to sampled playback or a default bank.
The source owner disconnects once after close or creation failure. Sampled codecs
remain separate from synthesis.

Retained synthesis entry points include MIDI files, `device://midi`,
`device://tone`, `ToneControl`, `Manager.playTone`, OTA, RTTTL, iMelody, and
delegation from Nokia `Sound`, Samsung `AudioClip`, Motorola `MidiPlayer`,
KDDI `MediaResource`, Sprint `Clip`, and Vodafone `Sound`. Siemens
`com.siemens.mp.media.Manager` was already an unimplemented stub; this migration
does not add that independent API. SMAF, MFi, and AMR codec expansion is outside
this change.

The built-in bank and custom SF2/DLS collections use the same context isolation.
SF2 support is not complete. In particular, the Nokia controller modulator
`0x028a -> 0x0011` remains unsupported. Bank playback success does not establish
reference-device timbre, every preset or drum key, acoustic fidelity, or seamless
looping.

## Reproducible local checks

Follow [build and validation](development.md) for the toolchain. Instrumentation
tests generate MIDI, ringtone, single-program SF2/DLS sine-wave, and WAV fixtures.
They require no user bank or game asset. `SynthPlayerContractTest` uses a fake
backend for shutdown, close, time, exception, fatal-error, and stale-event
contracts; `SonivoxRuntimeTest` exercises the production JNI and Manager paths.
`MicroPlayerRuntimeTest` covers the shared sampled/synthesis runtime boundary.
`AudioMidletRuntimeTest` launches a project-owned guest through the real
`MicroLoader`/`MidletThread` and isolated `MicroActivity`, including Home,
host return/recreation, guest stop, and runtime termination. It uses a private
fixture workdir with its own installed Library identity and restores the
qualification package's prior preferences.
Coordinator unit tests use a deterministic focus driver; actual device focus
callbacks remain a separate check.
The optional [distinct-UID focus companion](../app/src/test/focus-rival/README.md)
qualifies transient and permanent loss while the host Activity stays resumed.

The native test calls the production render callback directly without opening
an output stream. After an ARM64 debug assembly, run:

```powershell
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial>
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial> -Bank path/to/local-bank.sf2
```

It checks 257/511-frame buffers, held voices and staged PCM across suspension,
seek flushing, stale stream errors, MIDI backpressure, host cursors, and
real-time allocation attempts. It requires the configured SDK/NDK and current
debug `libsonivox.a`; its executable and optional bank copy are temporary
qualification inputs outside the APK.
See [native checks](../app/src/test/native/README.md) for the desktop allocation
failure sweep and optional bank inputs.

Use the init script to build a distinct qualification application ID without a
prototype library overlay:

```powershell
./gradlew.bat -I scripts/audio-qualification.init.gradle :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest
./gradlew.bat -I scripts/audio-qualification.init.gradle '-Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest,io.github.h3nb.jlmodplus.mmapi.synth.SonivoxRuntimeTest,javax.microedition.media.MicroPlayerRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.AudioMidletRuntimeTest' :app:connectedEmulatorDebugAndroidTest
```

The qualification target is `io.github.h3nb.jlmodplus.audioqualification.debug`;
the normal application's installation and presets are not the test target.
An available connected device and a visible qualification Activity are required
for real output. Select these classes rather than the unrelated full runtime
suite. Assembly alone does not execute tests. The optional local-bank method is
skipped unless a bank path is supplied; a suite pass does not mean that optional
qualification ran.
The optional bank loop reports context load time and retained native allocated
heap change after a warmup at cycles 1, 5, 10, and 20. These measurements describe
that bounded run; they do not establish the absence of heap leaks.
The whole-MIDlet tests also skip when run against the normal application ID;
their launch/preferences cleanup is deliberately scoped to the separate
qualification installation.

To qualify a private local bank, copy it into the qualification package's private
`files` directory and invoke only the optional method. For example, after the
separate qualification package has been installed:

```powershell
adb push path/to/local-bank.sf2 /data/local/tmp/jlmod-qualification-bank.sf2
adb shell run-as io.github.h3nb.jlmodplus.audioqualification.debug cp /data/local/tmp/jlmod-qualification-bank.sf2 files/qualification-bank.sf2
adb shell am instrument -w -r -e class 'io.github.h3nb.jlmodplus.mmapi.synth.SonivoxRuntimeTest#suppliedLocalSoundBankPlaysAndRepeatedResourcesClose' -e sonivoxBankPath /data/user/0/io.github.h3nb.jlmodplus.audioqualification.debug/files/qualification-bank.sf2 io.github.h3nb.jlmodplus.audioqualification.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class 'io.github.h3nb.jlmodplus.mmapi.synth.AudioMidletRuntimeTest#realMidletUsesSuppliedLocalBankSnapshot' -e sonivoxBankPath /data/user/0/io.github.h3nb.jlmodplus.audioqualification.debug/files/qualification-bank.sf2 io.github.h3nb.jlmodplus.audioqualification.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Keep banks, APKs containing private assets, captured output, dumps, and detailed
device reports outside the repository. Remove only the temporary copies created
for this qualification when no longer needed. Do not change OEM audio policy
from production code. Some devices can grant rival music focus without emitting
loss callbacks when an OEM ignore-focus option is enabled; report that policy
separately from coordinator correctness.

PCM counters describe data submitted to output, not a recording of the speaker
or Bluetooth endpoint. Zero live handles proves registry cleanup, not absence
of heap growth. ABI compilation does not prove physical playback. Extended game
sessions, bank cold loading, heap growth, process death, Bluetooth disconnection,
and acoustic comparisons require their own evidence before claiming them as
qualified.

## Qualification record, 3 October 2026

Production source was exercised through the separate qualification application
on a Xiaomi 25053PC47G, Android 16/API 36, ARM64. Both private banks passed
20 create/play/close cycles after warmup and a whole-MIDlet run covering Home,
return, Activity recreation, guest stop/start, and runtime termination.
The final default run listed 24 methods: 22 executed successfully and two
local-bank methods skipped as intended. Those two optional methods then passed
for each private bank. The two distinct-UID focus methods passed separately.
App JVM tests passed all 1,085 tests; app debug lint and dexlib debug lint passed.
Normal debug and release APK assembly passed, including R8 and release vital
lint. APK inspection verified that TinySoundFont/common libraries are absent,
release contains Sonivox for all four ABIs, and the debug guest fixture is absent
from release.
The pinned upstream snapshot retains inherited whitespace; whitespace checks
passed for the modified adapter, Java, fixtures, and documentation.

| Bank | First context load in process | Native allocated heap change after cycle 20 | Live handles after close |
| --- | --- | --- | --- |
| Sony Ericsson W580i, 8,611,996 bytes | 10.03 ms | +22,720 bytes | 0 |
| Nokia Series 40, 8,743,102 bytes | 14.04 ms | +19,712 bytes | 0 |

These are bounded process measurements, not OS cold-cache timing or a leak-free
claim. The native allocator fault sweep separately verified complete context
block reclamation across initialization, MIDI, and bank failures.

The optional physical focus tests passed transient and permanent loss with a
distinct rival UID while the host remained `RESUMED`. The device's verified
`key_ignore_music_focus_req` value was temporarily changed from 1 to 0 for this
controlled check and restored to exactly 1 in `finally`; the effective runtime
ignore-focus flag was verified before, during, and after. No production code
changes OEM policy.

The production native callback passed with the embedded bank, generated SF2/DLS,
and both private banks, including 257/511-frame staging, held-note suspension,
seek, events/loops, MIDI backpressure, stale stream errors, and zero real-time
allocation attempts. Four shipped ABIs compiled and linked; physical playback
was exercised only on ARM64. Physical Bluetooth disconnection, extended game
sessions, acoustic/reference-device timbre, and complete SF2 modulation remain
unqualified. User banks and detailed device logs are not committed.
