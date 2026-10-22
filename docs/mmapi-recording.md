# MMAPI recording subset

JSR-135 recording is intentionally limited to a small number of usable formats.

| Locator | Output content type | Recorder |
| --- | --- | --- |
| \`capture://audio\` | \`audio/wav\` | Mono 16 kHz PCM16 through AudioRecord |
| \`capture://video\` and \`capture://devcam0/1\` | \`video/mp4\` | CameraX silent video |
| \`capture://audio_video\` | \`video/mp4\` | CameraX video with microphone audio |

\`Player.getContentType()\` describes the live source; \`RecordControl.getContentType()\`
describes encoded output. Microphone access requests Android permission only when
audio is required. Video-only does not request microphone permission.

The Player STARTED state and \`RecordControl\` capture state are independent.
Calling \`startRecord()\` while Player is stopped arms the capture. Player stop
pauses without disarming. \`stopRecord()\` pauses until another \`startRecord()\`.
\`commit()\` finalizes temporary media and copies it to the selected Java ME
stream or FileConnection destination. \`reset()\` discards data and retains the
destination, while closing the Player discards uncommitted media.

CameraX uses the existing CameraRuntime as its hardware owner. VideoCapture
temporarily replaces ImageCapture during recording, avoiding three simultaneous
camera use cases. The camera cannot swap lenses or take another snapshot while
video is recording. Foreground camera loss is treated as recording failure.

The recorder uses at most 64 MiB of private temporary storage per capture.
\`setRecordSizeLimit()\` is an optional JSR-135 facility and raises
\`MediaException\` in this minimal subset; it must not silently succeed.
No H.263 transcoder, JSR-234 controls, or background recording is added.

## Required qualification

Unit/instrumentation: arm-before-start, Player stop/restart, RecordControl
stop/restart, reset, commit, close, output errors and size limits.

Physical Android: microphone grant/denial; WAV RIFF header and audio samples;
silent MP4; MP4 with microphone track; video pause/resume; front/rear framing;
background/permission loss; temporary-file cleanup. Re-run the PR #175 photo
preview/snapshot regression cases. CI assembly does not establish physical capture.
