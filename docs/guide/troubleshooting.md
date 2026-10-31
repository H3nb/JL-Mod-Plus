# Troubleshooting

A useful bug report starts with a reproducible observation, not a guess about which component is broken. Java ME games differ in their original device assumptions, while Android adds its own permissions, graphics, and media behavior. Similar symptoms can have unrelated causes.

## Before changing anything

Record the following:

1. **JL-Mod Plus version and source:** release number or commit/branch, if you're running a development build.
2. **Device:** Android version, device model, and relevant peripherals such as a gamepad.
3. **MIDlet:** game/application name, version, and package type (JAR, JAD, KJX, ZIP, or bundle).
4. **Reproduction:** the shortest sequence from launch to the problem, including orientation and settings that matter.
5. **Difference from defaults:** whether the behavior also occurs with an unmodified MIDlet configuration.

If a problem started after changing a setting, revert that one setting and compare. Avoid adjusting several things at once, which makes the result harder to interpret.

Do not delete the emulator working directory or Library database as a first repair attempt. It can contain installed apps, Library metadata, settings, profiles, and game saves. A corrupt catalog and a missing install source do not justify the same recovery procedure.

## Find the correct area

| Symptom | Where to start |
| --- | --- |
| File not found, ZIP has no games, JAD/JAR mismatch, or partial batch result | [Installing MIDlets](installing-midlets.md) |
| Library icon, text, locale, grid/list presentation, or missing Collection | [Library and metadata](library.md) |
| Shared settings changed unexpectedly, or a MIDlet ignores profile updates | [Profiles](profiles.md) |
| Skin image clipped, SVG renders incorrectly, or virtual key misses taps | [Appearance and controls](customization.md) |
| Memory scan finds too many/no candidates, or a watched value disappears | [Memory Editor](memory-editor.md) |
| Speed multiplier has little effect or makes timing irregular | [Emulation Speed](emulation-speed.md) |
| Silent MIDI, rejected SF2/DLS, camera black screen, or recording failure | [Audio and camera](media.md) |

### Installed is not the same as compatible

A successful install means the converter and installer accepted the package. It does **not** prove that every Java ME API, vendor extension, graphics effect, or media format required by the MIDlet will work. If a game fails only after launch, describe the exact in-game step and any message shown instead of reinstalling it repeatedly.

### A rendering problem is not always a skin problem

If the game's frame or custom buttons appear misplaced, first identify whether the issue affects the **guest screen**, **skin artwork**, or **touch mapping**. Screenshot the result with default and custom appearance when possible. Skin changes do not change the virtual-control hitboxes, so a key responding in the wrong place may need a layout investigation.

## Diagnostic reports and logs

**Diagnostic reports** contains incidents recorded by the app. **Report on GitHub** creates a diagnostic ZIP you can inspect and choose to share; it is **not automatically uploaded**. **Save log** captures a snapshot of the Android logs accessible to the app and may not contain a crash that happened long before the capture.

An exported bundle may contain stack traces and messages originating from the MIDlet. Redaction protects known categories of private paths and URIs but cannot guarantee that every arbitrary text field is harmless. Review the archive before posting it publicly.

For details about how reports are collected and how recovery cases differ, see [Diagnostic bundles](../diagnostic-bundles.md).

## A report others can reproduce

When opening an issue, include the **expected result**, **actual result**, exact steps, relevant settings, and any safe logs or screenshots. If the failure involves a particular package, state where it came from and whether sharing the file is permitted.

Use [GitHub Issues](https://github.com/H3nb/JL-Mod-Plus/issues/new/choose) for reproducible defects or [Discussions](https://github.com/H3nb/JL-Mod-Plus/discussions) for questions and compatibility experiences.

When asking an AI assistant to troubleshoot, include the app version, the appropriate guide, and—if code-level investigation is needed—the [technical documentation](../README.md). An explanation is not a confirmed diagnosis until it accounts for the evidence.
