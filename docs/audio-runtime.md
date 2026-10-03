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
At a valid prefetched state, `shortMidiEvent` delivery rejection is silent as
required by JSR135; `longMidiEvent` reports an error with -1. State/parameter
validation and VM errors still propagate. The bounded native queue rejects
whole writes and a full queue does not close a healthy Player.

Host suspension preserves guest playback intent and MMAPI state while freezing
output and media progression. Activity foreground loss and transient focus loss
(including duck requests) suspend audio. Eligible foreground return or focus
gain resumes only the same Player request. Guest stop, close, deallocation, or
runtime termination invalidate that request. Permanent focus loss revokes prior
intent; a fresh explicit playback request is required. Focus denial on
`Player.start` throws a `MediaException` without a `STARTED` event or delayed
autoplay request. MIDI delivery follows its own API failure contract. This
foreground policy meets the Android API35+ focus restriction without introducing
a foreground service. It is separate from LCDUI display ownership and AMS
foreground selection.

This host suspension does not emit MMAPI `DEVICE_UNAVAILABLE`: that event would
require a transition to `REALIZED` and a later `DEVICE_AVAILABLE` or `ERROR`.
Recoverable Oboe disconnection reopens output on the management path, preserving
EAS voices, position, and staged PCM. Irrecoverable media/output failure closes
the Player and emits an explained `ERROR` followed by `CLOSED`.
Callback errors belong to their output instance, including callbacks delayed
past close/reopen. Management only consumes the current instance's error.
Recovery is bounded to three attempts without healthy output; 4,410 rendered
frames (100 ms) on a replacement replenish the budget for a later episode.
Open/start success alone does not replenish it, and suspension preserves the
unrecovered episode until output can resume.

## Content and compatibility boundaries

Manager locator, stream, and `DataSource` creation share backend selection.
Bounded content recognition takes precedence over MIME hints, including Nokia
OTA bytes labelled `audio/midi`. Recognized corrupt synthesis and bank failures
are surfaced instead of falling through to sampled playback or a default bank.
The source owner disconnects once after close or creation failure. Sampled codecs
remain separate from synthesis.

RIFF/RMID containers use a bounded, validated extraction of their single MIDI
`data` chunk into the existing SMF parser, so duration, seek, resume, and loops
follow the SMF path. RIFF/chunk lengths, odd-byte padding, and nested RIFF/LIST
bounds are checked before playback; valid unknown and INFO chunks are accepted.
Missing or duplicate MIDI data, malformed containers, and nesting deeper than
32 levels fail clearly. Embedded DLS collections are explicitly unsupported
for RMID and rejected, including nested collections; they are never silently
discarded. External SF2/DLS preset banks retain their existing behavior.

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
./gradlew.bat -I scripts/audio-qualification.init.gradle '-Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest,io.github.h3nb.jlmodplus.mmapi.synth.SonivoxRuntimeTest,javax.microedition.media.MicroPlayerRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.AudioMidletRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.MidiDeliveryRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.RmidRuntimeTest' :app:connectedEmulatorDebugAndroidTest
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
adb shell am instrument -w -r -e class 'io.github.h3nb.jlmodplus.mmapi.synth.AudioMidletRuntimeTest#boundedLoopingMidletSession' -e sonivoxSessionSeconds 120 -e sonivoxBankPath /data/user/0/io.github.h3nb.jlmodplus.audioqualification.debug/files/qualification-bank.sf2 io.github.h3nb.jlmodplus.audioqualification.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The bounded session is optional (30..600 seconds), using a project-owned MIDlet
with eight melodic channels and percussion in four-second loops. It checks
fresh PCM, EOM, host return, stop/seek/start, process/session identity, and live
contexts while recording heap, thread, output-device and xrun observations.
It does not run a commercial game or compare acoustic output. The default run
skips this method unless `sonivoxSessionSeconds` is supplied.

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

## Baseline qualification record, 3 October 2026

This record describes commit `325c479992e413828405fb626f45a48a26cb9d14`;
follow-up repair qualification is recorded separately below.

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

## Audit repair qualification, 3 October 2026

The follow-up to the baseline fixes output-instance error publication,
recovery budgeting across healthy episodes, short MIDI delivery semantics,
and RMID routing. The same distinct qualification installation on ARM64 ran
31 default methods: 28 passed and three optional bank/session methods skipped.
Sony and Nokia then each passed three optional methods: 20 create/play/close
cycles, whole-MIDlet lifecycle, and a requested 120-second looping MIDlet session.
The two distinct-UID focus methods also passed. JVM results list 1,087 cases,
1,086 executed, one pre-existing skip, and zero failures/errors. Final debug,
release/R8, app lint, release vital lint and four-ABI compile/link checks passed.
The standalone fake-device opener symbol is absent from the shipping library.

The native test deterministically holds an old callback's error publication
until replacement is running, verifies seven healthy recovery episodes on one
request (including suspension/GAIN), bounds a storm without progress, rejects
reopen failure and fatal errors on GAIN, serializes close during a blocked
reopen, and prevents stopped-player autoplay.
It passed with default, generated SF2/DLS, Sony and Nokia banks, including SMF
and wrapped RMID duration/seek/loop facts and zero real-time allocation attempts.
Its fake device does not establish physical disconnect/reconnect or concurrent
Oboe data-callback join behavior. Production RMID tests passed all three Manager
source routes, nonzero PCM, duration, seek, stop/resume, loops, corrupt-container
cleanup and WAVE sampled routing. The desktop RMID bounds check also passed.

| Bounded MIDlet session | Sony Ericsson W580i | Nokia Series 40 |
| --- | --- | --- |
| Observed session duration | 120.012 s | 120.012 s |
| Fresh submitted frames | 5,234,048 | 5,242,176 |
| EOM events during session | 21 | 25 |
| Live contexts throughout | 1 | 1 |
| Native allocated heap, first / last snapshot | 29,803,264 / 29,195,712 bytes | 30,061,392 / 29,458,112 bytes |
| Threads, first / last snapshot | 38 / 37 | 39 / 39 |
| Observed xruns / ERROR / premature CLOSED | 0 / 0 / 0 | 0 / 0 / 0 |

These sessions used the project-owned multi-channel looping guest, not
City Bloxx or High Speed 3D gameplay. Both performed host suspension/return and
stop/seek/start without replacing the process/session, and emitted fresh PCM.
The observed output device ID was 3541 at 44.1 kHz; no physical route switch was
attempted. Heap snapshots include the fixture and host and do not prove the
absence of leaks. Frame/PCM/xrun counters are not an acoustic recording.
The 20-cycle native allocated heap deltas were +20,288 bytes (Sony) and
+37,488 bytes (Nokia), with zero registered handles after each close.

At the final focus check the OEM ignore-focus setting was 0/effective false;
that exact value and effective policy were verified again after the check.
This differs from the earlier baseline record's 1; current device policy was
read rather than inferred from that older result. Production never changes it.
Physical Bluetooth qualification was explicitly skipped at the user's request.
Commercial-game sessions, matched reference timbre, complete SF2 modulation,
and the pre-existing sampled-player time-after-deallocation issue remain
separate follow-ups. Detailed logs and private assets remain outside the repo.
