# Generated audio fixtures

These are generated test signals, without recordings or commercial-game assets.
They are packaged only in the instrumentation APK.

`tagged.wav` copies `pcm.wav` and appends a RIFF LIST/INFO INAM chunk containing
the UTF-8 title `Nada 🟢` and its terminating zero. Chunk lengths include the
terminator, odd lengths receive zero padding, and the RIFF size is updated.
It checks demuxer metadata and supplementary UTF-8 characters across JNI.

The one-second sine fixtures use frequency 997 Hz and mono output:

```powershell
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=22050:duration=1 -ac 1 -c:a pcm_s16le pcm.wav
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=32000:duration=1 -ac 1 -c:a adpcm_ima_wav adpcm.wav
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=44100:duration=1 -ac 1 -c:a libmp3lame effect.mp3
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=48000:duration=1 -ac 1 -c:a aac effect.aac
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=8000:duration=1 -ac 1 -c:a pcm_alaw alaw.wav
```

The checked-in outputs were generated with FFmpeg
`N-123778-g3b55818764-win64-gpl`. The tool used to generate them is independent
of the production decoder. At 44.1 kHz, the decoded counts are 44,100 frames
for WAV/ADPCM/G.711/GSM/MP3 and 45,159 for AAC including its encoder delay/padding.

`app/src/test/native/generate_amr_fixture.c`, compiled against the pinned
OpenCORE 0.1.6 encoder, generates the NB fixtures:

- `generated-tone-dtx-nb.amr`: arguments `OUTPUT 50 tone`, 160 ms square wave
  then silence, 50 frames, five SID and 29 NO_DATA frames.
- `generated-dtx-nb.amr`: argument `OUTPUT`, four seconds of silence, 200 frames,
  25 SID and 168 NO_DATA frames.

`app/src/test/native/generate_gsm_fixture.ps1 -Output gsm.wav` generates one
second of synthetic Microsoft GSM at 8 kHz: 25 packed blocks, valid neutral LAR,
lag 40, gain/grid zero, and alternating pulse codes. It requires no GSM encoder.

The production OpenCORE build includes only decoders; enable the encoder only
when regenerating these test fixtures. `generated-sid-wb.awb` is a structurally
valid synthetic WB SID followed by three NO_DATA frames: header `#!AMR-WB\n`,
bytes `4c 00 00 00 00 00 7c 7c 7c`. It covers existing WB support rather than
adding a new format. Expected output is 3,528 stereo bus frames (80 ms).

`legacy-yamaha.mmf` and `legacy-yamaha-stereo.mmf` contain a generated 997 Hz
Yamaha ADPCM waveform, for the retained SMAF conversion path. Codec block
padding extends the one-second input to 1.024 seconds: conversion to U8 WAV at
16 kHz yields 16,384 frames with one or two channels respectively. They contain
no SMAF sequence or commercial recording. Generate them with host FFmpeg:

```powershell
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=8000:duration=1 -ac 1 -c:a adpcm_yamaha -f mmf legacy-yamaha.mmf
ffmpeg -f lavfi -i sine=frequency=997:sample_rate=8000:duration=1 -ac 2 -c:a adpcm_yamaha -strict -2 -f mmf legacy-yamaha-stereo.mmf
```

`LegacySmafConversionTest` checks PCM WAV headers/channel preservation, UTF-8
paths, source publication and fallback cleanup, and rejection of nonempty output.
