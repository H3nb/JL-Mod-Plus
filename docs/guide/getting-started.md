# Getting started

JL-Mod Plus runs Java ME applications (MIDlets) on Android. It is an emulator and compatibility environment, not a game store: you provide your own JAR, JAD, or other supported MIDlet packages.

## Get the right APK

Download JL-Mod Plus from [GitHub Releases](https://github.com/H3nb/JL-Mod-Plus/releases). Choose the APK for your device's architecture, or use **universal** if you're unsure. The minimum supported Android version is **6.0**.

Check whether the release is marked as a preview. Features described on the latest source branch may not be present in an older release.

## Install your first game

1. Open the **Library** and choose **Install → Files**.
2. Select a MIDlet package, typically a `.jar`. If you have a `.jad` and matching `.jar`, keep them together.
3. Review the detected title and version. Confirm installation.
4. Open the installed MIDlet from the Library.

You can select multiple files, a folder, or a ZIP containing several games. JL-Mod Plus discovers the installable applications and switches to a batch review when needed. It does not install every file found in a folder automatically. For package differences, ZIP handling, update safety, and failed installs, read [Installing MIDlets](installing-midlets.md).

## Configure one game before changing everything

Open a MIDlet's settings in the Library to adjust its **Display**, **Controls**, or **Media** options. Start with defaults when checking whether an older game runs correctly; change one setting at a time so you can tell which adjustment affects the result.

A **profile** can share a reusable configuration with other MIDlets, but it is optional. A screen skin changes the frame around a game; a virtual-control skin changes the appearance of on-screen buttons. Neither is required to run an ordinary JAR.

Once a game is running, the emulator's runtime controls may offer [Emulation Speed](emulation-speed.md), [Memory Editor](memory-editor.md), and other tools. A game's ability to use those features depends on its timing model and runtime behavior.

## Know where your data lives

Installing a MIDlet, editing its Library presentation, and changing its saved game data are different operations. A Collection membership or custom Library icon does not rewrite the original JAR. Reinstalling or updating a running MIDlet can stop that game, so save your progress first.

If you encounter an error, read the displayed message before repeating the operation. Avoid clearing the emulator work directory as a troubleshooting shortcut: it may contain installed apps, profiles, and game saves.

**Next:** [Installing MIDlets](installing-midlets.md) · [Library and metadata](library.md) · [Troubleshooting](troubleshooting.md)
