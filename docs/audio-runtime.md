# Audio runtime and qualification

Sonivox supplies synthesis for MMAPI and the retained vendor APIs that delegate
to MMAPI. The pinned upstream source and local core changes are recorded in
[the native provenance file](../app/src/main/cpp/sonivox/UPSTREAM.md). Adapter
ownership, stream management, and Android focus policy live outside that core.

## Contract and implementation authority

Guest-visible MMAPI/JSR semantics, focus and host-lifecycle behavior, media-time
rules, supported-format claims, real-time callback safety, bounded
resource/failure behavior, and stale-generation isolation are durable contracts.
Class ownership, thread topology, queue/ring geometry, mixer sample rate, backend
library, retry constants, and other tuning parameters describe the current
implementation unless a specification or explicit project compatibility rule
depends on them.

Future work may replace or tune those mechanisms when evidence shows the same
contracts are preserved with lower total complexity, better correctness, or
meaningful efficiency. Prefer one authoritative path for each responsibility;
after compatibility is demonstrated, remove superseded implementations rather
than retaining parallel fallbacks without a current requirement.

## Ownership and playback policy

The details in this section describe the current architecture used to satisfy
the contracts above; they are not a requirement to preserve a particular class
or native backend.

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
output and media progression. Host visibility loss and transient focus loss
(including duck requests) suspend audio. Opening the translucent Memory Editor
pauses its host Activity without hiding it, so playback continues underneath;
stopping the host still suspends it. Eligible foreground return or focus gain
resumes only the same Player request. Guest stop, close, deallocation, or
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

SMAF sequence synthesis is not part of the shared synthesis path: recognized MMMD sources
retain legacy `MicroPlayer`/Android playback. Their existing ADPCM waveform
conversion uses the same pinned FFmpeg libraries through a bounded native helper,
without FFmpegKit or a command-line frontend. It preserves channels and writes
U8 WAV at 16 kHz into an owned empty temporary file; the source publishes that
file only after complete decoding, resampler drain and successful output close.
Unsupported/non-ADPCM input or conversion failure retains the original source
and deletes the unused temporary output. Conversion limits input and output to
64 MiB, channels to two, and work to ten seconds. The Java input cache applies
the same 64 MiB bound to MMMD and every other cached format before conversion.
Legacy duration is `TIME_UNKNOWN` until Android has loaded the media on start.
There is no second sampled output or whole-file PCM conversion for the retained
formats in the shared mixer. Recipe, pins, ABI and
license details are in
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
`com.siemens.mp.media.Manager` remains an unimplemented stub and is not
advertised as supported. Additional synthesis or codec support beyond the
formats documented here is not implied by this contract.

The built-in bank and custom SF2/DLS collections use the same context isolation.
SF2 support is not complete. In particular, the Nokia controller modulator
`0x028a -> 0x0011` remains unsupported. Bank playback success does not establish
reference-device timbre, every preset or drum key, acoustic fidelity, or seamless
looping.

## File video and presentation clock

Cached ISO-BMFF input with a video track uses `VideoLibrary` subresources under
the same `AudioPlayer`. MediaExtractor/MediaCodec decode MPEG-4 Part 2, H.263 or
H.264 when the device supports the actual format. One video track, zero or one
audio track, up to sixteen container tracks, 1920x1080 pictures and bounded codec
configuration are accepted; the existing 64 MiB cache limit still applies.
Unsupported video fails explicitly.
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
