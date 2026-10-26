# Audio, soundbanks, and camera

Java ME games do not all play sound or use a camera in the same way. Some generate MIDI music, others play prerecorded audio; some use the standard MMAPI capture interfaces, while others rely on phone-specific APIs. JL-Mod Plus supports a defined subset of these paths, so a feature working in one game does not guarantee it works in another.

## Choose a MIDI soundbank

A **soundbank** supplies instrument definitions and samples for supported MIDI synthesis. Changing it may affect the tone of a game's melodies without changing its notes or timing.

Open the MIDlet's configuration and choose **Media → MIDI soundbank**. You can keep the default Android option, select an imported bank, or use the same control to add, rename, or remove a bank.

The importer accepts **SoundFont 2 (`.sf2`)** and **DLS (`.dls`)** files. A valid filename extension is not enough: the file must have the expected basic format and be usable by the synth.

Imported soundbank files are **shared assets**, while the choice of which bank to use belongs to the MIDlet or its applied profile. This lets several games use the same file without keeping separate copies. Removing a shared bank may affect every game selecting it.

### What a soundbank will not change

A soundbank is not a system-wide sound enhancer. It does not transform WAV files, prerecorded soundtracks, speech, or every vendor-specific audio path into a different instrument set.

If a game uses sampled audio instead of MIDI, changing soundbanks may have no audible effect at all. The correct first check is whether the game actually sends music through the MIDI synthesizer.

## Camera: preview and still pictures

JL-Mod Plus implements a **JSR-135/MMAPI camera capture subset** for compatible MIDlets. Games can request a preview and take still images; actual operation depends on Android camera hardware and permission.

Supported capture locators include a general video source and named front/rear directions. In JL-Mod Plus, `capture://devcam0` represents the rear camera and `capture://devcam1` the front camera. These are **emulated names**, not Android hardware camera IDs. The mapping is an emulator policy, not a guarantee made by every Java ME device.

The runtime's camera action can override the requested direction for the current session. Snapshot output supports **JPEG** and **PNG** within the implemented encoding and resolution limits.

A MIDlet that shows a camera preview may still handle snapshots incorrectly if it assumes an unsupported pixel format, resolution, or device behavior. A preview working is useful evidence, but it does not prove that every camera API the game calls is implemented.

## Recording: supported formats and limits

The implemented MMAPI recording subset includes:

| Requested capture | Output |
| --- | --- |
| Audio | Mono WAV recording |
| Video | MP4 without microphone audio |
| Audio and video | MP4 with microphone audio |

Audio recording needs Android microphone permission. Video-only recording does not. Where supported, the MIDlet controls when recording starts, pauses, and is committed to its destination.

Video recording shares the camera hardware with preview and snapshots. While recording is active, **switching front/rear cameras and taking a separate snapshot are unavailable**. Recordings do not continue in the background, and an interrupted or uncommitted capture may be discarded.

These capabilities come from the implemented **JSR-135/MMAPI** subset. They do not mean that every optional JSR-135 format is available, or that advanced **JSR-234** controls such as full focus/exposure control are implemented.

## Diagnose the symptom, not just the feature name

**MIDI is silent:** compare the default soundbank with an imported one. Check whether the affected track actually uses MIDI. A wrong or unsupported bank is only one possible explanation.

**An SF2/DLS file won't import:** confirm the original file format and integrity. Simply renaming a different file type does not make it a soundbank.

**Camera preview is blank:** inspect the Android camera permission and whether another app is using the camera. Test the same MIDlet again with default camera settings if possible.

**Preview works, but the picture is rotated or cropped incorrectly:** note the MIDlet's virtual orientation, device rotation, front/rear camera choice, and requested snapshot size. These details help separate guest-screen geometry from Android camera output.

**A recording fails after starting:** check microphone permission when applicable, remaining storage, foreground state, and whether the MIDlet closes the recording player. Include the output type and the step where it fails.

**Technical references:** [Audio runtime](../audio-runtime.md) · [Camera capture](../mmapi-camera.md) · [MMAPI recording](../mmapi-recording.md)
