# Audio runtime and qualification

Sonivox supplies synthesis for MMAPI and the retained vendor APIs that delegate
to MMAPI. The pinned upstream source and local core changes are recorded in
[the native provenance file](../app/src/main/cpp/sonivox/UPSTREAM.md). Adapter
ownership, stream management, and Android focus policy live outside that core.

## Ownership and playback policy

`AudioPlayer` supplies one MMAPI contract for synthesis, sampled audio and file video. Each
synthesis source owns its EAS context, sequencer, voices and bank collection;
each sampled source owns a native FFmpeg decoder/resampler and bounded PCM ring.
A native engine per runtime/session mixes both into one float stereo 44.1 kHz
bus and owns at most one active Oboe output. Java `RuntimeAudioCoordinator`
owns host/focus policy. A bank choice is captured from the runtime preset when
its synthesis library is created; existing sources retain their bank snapshot.

Gain and pan apply per source before summation. The bus uses hard clipping to
[-1, 1], with a clipped-sample diagnostic, and never normalizes by Player count.
Oboe performs hardware-rate conversion when needed. Sonivox renders directly
from its memory-backed source; sampled demux/decode/resample runs on a worker.
Each sampled ring has sixteen 1,024-frame stereo float slots (128 KiB), with at
most one worker per active source. Prefetch workers finish after filling the ring;
stop/suspension/deallocation join the worker. Underflow emits silence without
advancing media time or claiming EOS. Decoder, resampler and ring drain precede EOM.

The callback does no JNI, disk IO, compressed decoding, allocation, blocking lock
or object destruction. Management detaches a source and waits for its callback
hazard to clear before seek/context mutation/free; this never pauses a playing
peer. Media generation, output-instance epoch and runtime output group identity
are separate. An old output error cannot disable or poison its replacement.

The Java wrapper owns guest MMAPI state. Native events report rendering and
output facts through generation-fenced polling on the management path. Listeners
run outside the Player lock. `CLOSED` is terminal, source disconnection and the
final `CLOSED` event happen once, and the callback executor ends after final
delivery. Known media time and duration remain available after deallocation.
Duration observations from lifecycle calls, getters and management share one
cache/publication path. A changed known value emits `DURATION_UPDATED` with a
`Long` payload, including duration discovered at decoded EOF without a guest
getter. Equal values do not repeat across polling or loops; queued duration
notifications are invalidated by close or ToneControl source replacement.
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
EAS voices, sampled buffers, position and staged PCM. A media/decoder failure is
local to its source. Irrecoverable shared-output failure closes all sources in
that output group, including unrealized peers, with `ERROR` followed by `CLOSED`.
Queued failure delivery cannot close a later output group.
Callback errors belong to their output instance, including callbacks delayed
past close/reopen. Management only consumes the current instance's error.
Recovery is bounded to three attempts without healthy output; 4,410 rendered
frames (100 ms) on a replacement replenish the budget for a later episode.
Open/start success alone does not replenish it, and suspension preserves the
unrecovered episode until output can resume. Starting a short effect cannot
replenish another source's unhealthy recovery episode, including a pending loop.
A native policy gate immediately fences revoked requests before queued Java
callbacks run. Each activation carries its captured focus grant epoch; a late
suspension of an old source cannot halt fresh playback by another source.
Management polling also retires a gated source's worker before its queued Java
suspension callback arrives. Output replacement acknowledges the disabled old
callback before a new consumer starts. Sampled prefetch fills the bounded ring
or reaches EOF before start, rather than returning at the first PCM chunk.

## Content and compatibility boundaries

Manager locator, stream, and `DataSource` creation share backend selection.
Bounded content recognition takes precedence over MIME hints, including Nokia
OTA bytes labelled `audio/midi`. Recognized corrupt synthesis and bank failures
are surfaced instead of falling through to sampled playback or a default bank.
The source owner disconnects once after close or creation failure. Cached audio
input is bounded to 64 MiB; caller-owned InputStreams remain caller-owned.
Recognized WAV, AMR, MP4 and MPEG frame headers bind to the corresponding demuxer.
Codec capability is an explicit native whitelist rather than a MIME promise.
Capability queries filter by protocol and content type: interactive `device`
locators expose MIDI and tone sequences; retained file playback uses `file` and
`resource`. Unsupported protocols/types return an empty list, and HTTP playback
is not advertised. Recording remains on its existing separate implementation.

Retained sampled support is PCM WAV (U8, S16/S24/S32 little-endian, F32), G.711
A-law/mu-law, Microsoft GSM WAV, IMA ADPCM WAV, MP3, AAC/MP4 and AMR NB/WB.
OpenCORE supplies AMR NB/WB including the generated SID/DTX fixtures. Metadata
comes from the demuxer and crosses JNI as UTF-8. Container durations may be
estimates; final decoded timestamps replace compressed duration after drain,
including encoder priming/padding corrections. Known time/duration survive
stop and deallocation. The default TimeBase keeps ticking independently of the
media cursor; unsupported custom TimeBases throw the specified MediaException.

SMAF is entirely outside this migration: recognized MMMD sources retain legacy
`MicroPlayer`/Android output and FFmpegKit conversion, including their existing
cache policy. The FFmpegKit AAR retains its wrapper/resources/Java dependencies;
only its seven core FFmpeg libraries are removed and replaced by the one pinned
native build. There is no second sampled output or whole-file PCM conversion
for the retained formats in scope. Recipe, pins, ABI and license details are in
[the dependency instructions](../tools/audio/README.md).

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

## File video and presentation clock

Cached ISO-BMFF input with a video track uses `VideoLibrary` subresources under
the same `AudioPlayer`. MediaExtractor/MediaCodec decode MPEG-4 Part 2, H.263 or
H.264 when the device supports the actual format. One video track, up to sixteen
container tracks, 1920x1080 pictures and bounded codec configuration are accepted;
the existing 64 MiB cache limit still applies. Unsupported video fails explicitly.
Audio-only MP4 remains sampled audio. Capture, recording, DRM and snapshots are
outside this file-playback path; `getSnapshot` throws `MediaException` after its
normal initialization checks.

Legacy MPEG-4 SP clips can contain a level-0 header with pictures larger than
QCIF, or run below Android's reported 12 fps lower bound. Selection queries the
actual size, supported profile and sufficient decoding throughput rather than
the inconsistent declared level; codec configuration retains the original CSD
and packet timestamps. Other codecs retain their declared level constraints.

The soundtrack uses the existing FFmpeg/OpenCORE PCM source and shared Oboe
bus. Both demuxers preserve container presentation time zero, track offsets and
priming. Leading and internal forward PTS gaps contribute source-owned silence,
consumed incrementally without gap-sized allocation or callback decoding.
Continuous segments use accumulated output frames rather than rounded per-frame
PTS; a discontinuity drains the old resampler before resetting filter history.
Missing timestamps continue that clock; overlapping/backward timestamps trim
already-covered PCM and never rewind the container cursor.
Pure video creates no audio output and requests no audio focus. While audio is
active, its presentation cursor is the master. After its final presented PCM,
video can continue monotonically. If video ends first, audio drains before EOM.
There is one guest state, duration, loop count, seek transaction and EOM per
iteration. Native PCM never loops independently of the video controller.
Known video-track duration bounds its last frame, including variable frame
intervals; without it, the last decoded PTS and nominal interval define the end,
even if that frame was dropped. Seeking or restarting in a shorter soundtrack's
silent tail keeps its PCM source inactive while video continues.
If either track's duration is unknown, the whole-media duration stays unknown
until both ends can be determined; a shorter known soundtrack cannot clamp
seeks into the remaining video.
For a positive seek while the whole-media duration is unknown, an independent
bounded packet/PCM scan resolves the finite cached input before the seek
transaction. It runs outside the guest Player lock, leaves playback and peers
untouched, and cancels/fences publication on close. `setMediaTime` returns the
clamped actual time, including a valid zero-duration end. Requested targets and
scheduling horizons never establish track end or duration. The decoded bound is
retained across replay and deallocation.
If committing a seek invalidates the source and reopening the codec fails, the
Player reports `MediaException` and closes with one `ERROR` followed by `CLOSED`.
Healthy peers keep playing. A rejected seek preparation that leaves playback
untouched does not close the source.

A fixed native segment ring maps bus frames to source media timestamps, fenced
by source generation and output epoch. Management queries the current Oboe
timestamp under output lifetime serialization. If unavailable, the current
sink's buffer size/capacity and burst provide a bounded estimate with reported
uncertainty; a sink with neither timestamp nor bounded geometry fails the video
source. Underflow does not advance the source cursor. No timestamp query, JNI,
allocation or decoding runs in the audio callback.

The video worker holds at most one decoded output until within 2 ms of its due
time, then uses a monotonic timed release. Output later than one declared frame
interval (at least 20 ms) is dropped, including seek preroll. TextureView does
not promise SurfaceView-style future presentation, so submissions stay near
their deadlines. Diagnostics compare rendered timestamps with mapped audio
presentation, report skew buckets, drift, unmapped samples and sink uncertainty.
These are scheduling measurements, not acoustic or panel-latency proof.

`VideoControl` supports both LCDUI modes: direct Canvas defaults hidden and uses
guest coordinates clipped to the LCD viewport; GUI primitive defaults visible
and returns a real Item for Form ownership and scrolling. A separate TextureView
Surface sits above the Canvas/GL surface and below the existing host overlay.
Geometry and View ownership run on the main thread; codec teardown acknowledges
Surface destruction on its worker. Hiding video or changing LCDUI screens does
not stop playback. Host or transient focus suspension freezes A/V together;
permanent audio focus loss requires a fresh request. Pure video follows host
visibility while ignoring an audio peer's focus loss.

`VideoPlayerTest` covers codec selection, offsets, unequal track lengths,
pure video, seek/loops and peer isolation. `VideoMidletRuntimeTest` launches the
owned guest in direct Canvas and Item/Form modes, including input, commands,
scrolling, clipping, fullscreen, Home, recreation and rotation. Its physical
portrait command tap is a qualification gesture, not a portable UI golden.
Pass `-e videoGraphicsMode 1` to qualify GL. `SonivoxFocusRuntimeTest` includes
video with the optional distinct-UID companion. The generated inputs and
commands are in [the fixture notes](../app/src/androidTest/assets/video/README.md).
The opt-in `-e videoCorpus true` test reads three externally supplied files
(`0.3gp`, `4.3gp`, `6.3gp`) from the qualification package's `files/video-corpus`;
commercial assets are never packaged in the repository/APK.

## Reproducible local checks

Follow [build and validation](development.md) for the toolchain. Instrumentation
tests generate MIDI, ringtone, single-program SF2/DLS sine-wave, and WAV fixtures.
They require no user bank or game asset. `SynthPlayerContractTest` uses a fake
backend for shutdown, close, time, exception, fatal-error, and stale-event
contracts; `SonivoxRuntimeTest` exercises the production JNI and Manager paths.
`SampledAudioRuntimeTest` covers host policy and sampled lifecycle.
`UnifiedAudioRuntimeTest` checks retained formats, demuxer metadata, TimeBase and
corrupt-source isolation through the production Android bridge.
`AudioMidletRuntimeTest` launches a project-owned guest through the real
`MicroLoader`/`MidletThread` and isolated `MicroActivity`, including Home,
host return/recreation, guest stop, and runtime termination. It uses a private
fixture workdir with its own installed Library identity and restores the
qualification package's prior preferences.
Coordinator unit tests use a deterministic focus driver; actual device focus
callbacks remain a separate check.
The optional [distinct-UID focus companion](../app/src/test/focus-rival/README.md)
qualifies transient and permanent loss while the host Activity stays resumed.

The native test substitutes only the device opener and calls the production
engine callback with a deterministic stream. After an ARM64 debug assembly, run:

```powershell
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial>
./app/src/test/native/run-sonivox-runtime.ps1 -Device <adb-serial> -Bank path/to/local-bank.sf2
```

It checks 257/511-frame buffers, held voices and staged PCM across suspension,
seek flushing, stale stream errors, MIDI backpressure, host cursors, two-source
isolation, hazard acknowledgment, gain/clipping, mixed PCM/synthesis drain and
loops, and real-time allocation attempts. The standalone PCM adapter test adds
retained-codec decoding, SID/DTX, underflow, IO cancellation and repeated close. It requires the configured SDK/NDK and current
debug `libsonivox.a`; its executable and optional bank copy are temporary
qualification inputs outside the APK.
See [native checks](../app/src/test/native/README.md) for the desktop allocation
failure sweep and optional bank inputs.

Use the init script to build a distinct qualification application ID without a
prototype library overlay:

```powershell
./gradlew.bat -I scripts/audio-qualification.init.gradle :app:assembleEmulatorDebug :app:assembleEmulatorDebugAndroidTest
./gradlew.bat -I scripts/audio-qualification.init.gradle '-Pandroid.testInstrumentationRunnerArguments.class=io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest,io.github.h3nb.jlmodplus.mmapi.synth.SonivoxRuntimeTest,javax.microedition.media.SampledAudioRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.AudioMidletRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.MidiDeliveryRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.RmidRuntimeTest,io.github.h3nb.jlmodplus.mmapi.synth.UnifiedAudioRuntimeTest' :app:connectedEmulatorDebugAndroidTest
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
with eight melodic channels and percussion in four-second loops, plus looping
WAV and MP3 effects through the same output. It checks fresh synthesis/sampled
PCM, EOM, host return, stop/seek/start, process/session identity, and three live
sources while recording heap, threads, workers, clipping, callback duration,
output latency/device and xruns.
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

This historical record predates the unified mixer and describes commit
`325c479992e413828405fb626f45a48a26cb9d14`;
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

## Unified audio qualification, 3 October 2026

Retained synthesis and sampled audio share one native output in the qualified
runtime. The final ARM64 checks cover common MMAPI lifecycle, MIDI delivery,
retained decoder formats, UTF-8 metadata, TimeBase, RMID, corrupt-source isolation,
shared failure, real MIDlet lifecycle, and distinct-UID transient/permanent focus.
The native tests cover bit-exact peer isolation, clipping/gain, consumed clocks,
finite drain/loops, policy fences, source/output callback acknowledgment and
bounded recovery. A separate production JNI smoke test loads the retained
FFmpegKit wrapper with the single replacement library set.
The final device suite listed 38 methods: 35 executed successfully and three
optional bank/session methods skipped. JVM results list 1,100 cases, 1,099
executed and one pre-existing skip, with zero failures/errors.

Sony and Nokia each passed 20 create/play/close cycles and whole-MIDlet lifecycle,
then a final 120-second generated guest session with MIDI, WAV and MP3 together.
Three live sources shared one output; sampled workers ranged from zero to two.
Both sessions exercised host return/recreation and guest stop/seek/start while
preserving process/session identity. Sampled prefetch was changed to fill its
bounded ring or reach EOF before starting, after an earlier Nokia run counted
435 underflow frames. The final sessions below counted no sampled underflow.
These sessions use the final decoder/mixer code; the subsequent SAF wrapper ABI
repair is separately verified by the packaged export and production JNI checks.

| Final mixed session | Sony Ericsson W580i | Nokia Series 40 |
| --- | --- | --- |
| Observed duration | 120.011 s | 120.011 s |
| Fresh synthesis frames | 5,240,960 | 5,238,912 |
| Fresh combined sampled frames | 10,395,696 | 10,394,988 |
| EOM events during session | 21 | 25 |
| Active outputs / live sources | 1 / 3 | 1 / 3 |
| Native heap, first / last snapshot | 30,962,688 / 30,513,424 bytes | 31,085,888 / 30,419,024 bytes |
| Threads, first / last (observed range) | 41 / 41 (40..42) | 42 / 40 (39..42) |
| Largest mixer callback | 1.117 ms | 1.081 ms |
| Oboe latency estimate range | 28.435..31.575 ms | 28.998..32.321 ms |
| Xruns, first / last snapshot | 0 / 0 | 1 / 1 |
| Underflow frames / clipped samples / guest errors | 0 / 0 / 0 | 0 / 0 / 0 |

The route was device ID 3 at 44.1 kHz, with a 384-frame buffer. Nokia's one xrun
was already present in the first session snapshot; none were added during the
120-second interval. These counters and estimates are not acoustic recordings,
reference timbre comparisons, arbitrary-source-count benchmarks or proof of no
leaks. The generated guest is not commercial gameplay. Effective OEM ignore-focus
policy stayed false; the qualification did not modify device settings.

Final JVM, lint, debug/release/R8 and four-ABI checks are local validation.
The APK retains one seven-library FFmpeg set per ABI and the legacy wrapper;
all 353 required wrapper symbols are present, with matching public headers.
The recipe verifies SAF source patching and getter/setter exports instead of
trusting a patch stamp alone. New FFmpeg libraries and 64-bit runtime libraries
have 16 KiB ELF load alignment; existing 32-bit runtime builds retain 4 KiB.
License assets are packaged, and shipping libraries exclude the native test seam.

An earlier ultra-short MIDI loop timeout was not reproduced in ten separate cold
runs or subsequent full runs; diagnostics were added, but its initial cause is
unproved. Bluetooth remains explicitly skipped, and SF2 modulation/reference
timbre limitations remain. The unified path fixes the earlier sampled time and
duration loss after deallocation. Detailed evidence and private banks remain
outside the repository.
