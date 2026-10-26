# JL-Mod Plus

[![Latest Release](https://img.shields.io/github/v/release/H3nb/JL-Mod-Plus?include_prereleases&label=release)](https://github.com/H3nb/JL-Mod-Plus/releases)
[![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/H3nb/JL-Mod-Plus/releases)
[![Architecture](https://img.shields.io/badge/ABI-ARM%20%7C%20x86-blue)](https://github.com/H3nb/JL-Mod-Plus/releases)
[![License](https://img.shields.io/github/license/H3nb/JL-Mod-Plus)](LICENSE)

JL-Mod Plus is a fork of [JL-Mod](https://github.com/woesss/JL-Mod), an Android emulator for running Java ME (J2ME) applications and games.

It builds on JL-Mod with new features, UI changes, input improvements, and better support for modern Android versions.

## Download

**[Download JL-Mod Plus from GitHub Releases](https://github.com/H3nb/JL-Mod-Plus/releases)**

Open a release and download the APK from the **Assets** section.

Choose the APK that matches your device's architecture:

| APK | Architecture |
| --- | --- |
| `arm64-v8a` | 64-bit ARM |
| `armeabi-v7a` | 32-bit ARM |
| `x86` | 32-bit x86 |
| `x86_64` | 64-bit x86 |
| `universal` | All supported architectures |

**Not sure which APK to download?** Choose the `universal` APK. It supports all listed architectures, although the file is larger.

The app's **More → Updates** screen can check GitHub Releases manually or at Library startup.
Choose **Stable** for stable versions only or **Preview** to include pre-releases.
It opens the release page for manual APK installation; it does not install APKs silently.

> JL-Mod Plus is still under active development. Releases may be marked as pre-release and may contain unfinished or experimental changes.

### Requirements

- Android 6.0 or newer
- ARM (`arm64-v8a`, `armeabi-v7a`) or x86 (`x86`, `x86_64`) device

## Features

JL-Mod Plus includes the features of JL-Mod, with additional features and improvements such as:

- Modern Material 3 interface
- App library with favorites, collections (folders), search, sorting, MIDlet metadata editing, and more
- Emulation speed control (speedhack)
- Memory editor
- Analog input support converted to digital directional input
- Batch JAR/JAD installation
- Improved compatibility with modern Android versions


## Getting Started

1. Download and install JL-Mod Plus from [GitHub Releases](https://github.com/H3nb/JL-Mod-Plus/releases).
2. Open the app and import a Java ME application or game (`.jar`, `.jad`, or `.kjx`).
3. Select the installed application from your library to launch it.

You can customize controls, display settings, and other options for individual games.

JL-Mod Plus does not include Java ME games. You will need to provide your own application files.

## Community & Support

Have an idea, a question, or a problem to report? You're welcome to join the community!

- **[Ideas](https://github.com/H3nb/JL-Mod-Plus/discussions/categories/ideas)** — Suggest new features and improvements.
- **[Q&A](https://github.com/H3nb/JL-Mod-Plus/discussions/categories/q-a)** — Ask questions or get help.
- **[General Discussions](https://github.com/H3nb/JL-Mod-Plus/discussions/categories/general)** — Join general conversations about JL-Mod Plus.
- **[Bug Reports](https://github.com/H3nb/JL-Mod-Plus/issues/new/choose)** — Report bugs and compatibility issues.

For bug reports, please include your JL-Mod Plus version, device information, Android version, and steps to reproduce the issue. Logs or diagnostic reports are helpful when available.

## Contributing

Contributions are welcome, including those made with the assistance of AI tools.

Whether you write code manually or use AI, please review your changes, follow the project's existing conventions, and perform relevant validation before submitting a pull request.

AI-assisted contributions are held to the same quality standards as any other contribution.

For development setup and testing instructions, see [Build and validation](docs/development.md). For topic-specific guidance and historical records, see the [Documentation map](docs/README.md).

**Using an AI coding agent?** Have it follow [AGENTS.md](AGENTS.md), which contains project-specific instructions for coding agents.

## Credits

JL-Mod Plus is based on [JL-Mod](https://github.com/woesss/JL-Mod).

Thanks to the original project and its contributors.

## License

JL-Mod Plus is licensed under the [Apache License 2.0](LICENSE).

See [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for additional attribution and third-party licensing information.
