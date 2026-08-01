# JL-Mod Plus

[![CI](https://github.com/H3nb/JL-Mod-Plus/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/H3nb/JL-Mod-Plus/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

JL-Mod Plus is an independent, community-maintained Android J2ME emulator fork
by H3NB. It focuses on a stable experience on modern Android while preserving
the broad MIDlet compatibility inherited from JL-Mod and J2ME Loader.

The project is in early development. Back up games, save data, profiles, and
working directories before using development builds.

## Project status

- Initial JL-Mod Plus version: `0.1.0` (`versionCode` 1).
- Android application ID and namespace: `io.github.h3nb.jlmodplus`.
- Primary product flavor: `emulator`.
- Packaged native ABI: `arm64-v8a` only.
- Current SDK baseline: compile SDK 36, minimum SDK 23, target SDK 34.
- Required JDK: 21 (build runtime; Java/Kotlin bytecode targets remain 17).

JL-Mod Plus has its own application ID and must use its own signing key. Android
treats it as a separate application, so it can be installed beside JL-Mod or
J2ME Loader. Preferences and private app data are not migrated automatically.
The default shared-storage working directory is `/sdcard/JL-Mod Plus`.

## Capabilities

JL-Mod Plus retains the emulator core and major features inherited from JL-Mod,
including JAR/JAD installation, per-game profiles, virtual controls, shaders,
sound banks, and multiple vendor-specific J2ME APIs. Compatibility under the
new independent package is still being validated.

## Building

Requirements:

- JDK 21;
- Android SDK Platform 36;
- the NDK version declared in `build.gradle.kts`;
- the repository cloned with Git submodules.

Use the included Gradle Wrapper. Normal validation is intentionally limited to
the `emulator` flavor and ARM64 package:

```shell
./gradlew --no-daemon \
  :app:testEmulatorDebugUnitTest \
  :app:assembleEmulatorDebug
```

The inherited `midlet` flavor remains in source for compatibility work, but it
is not part of normal CI or release builds.

## Versioning and releases

The public version and Android version code live in `version.properties`.
Maintainers update both through the checked-in PowerShell helper:

```powershell
.\scripts\set-version.ps1 0.1.1
```

The helper validates Semantic Versioning and increments `VERSION_CODE` once.
Release tags use the matching `vMAJOR.MINOR.PATCH` form, for example `v0.1.0`.
CI rejects tags that do not match the tracked version.

Unsigned debug artifacts are produced by normal CI. Signed releases are built
only by the protected release workflow; signing keys and passwords must never
be committed to this repository.

## Reporting problems

Use the [JL-Mod Plus issue tracker](https://github.com/H3nb/JL-Mod-Plus/issues)
and include the app version, Android version, device model, affected MIDlet,
and reproducible steps. Remove personal data from logs before attaching them.

## Origins and acknowledgements

JL-Mod Plus is based on [JL-Mod](https://github.com/woesss/JL-Mod), maintained
by Yury Kharchenko, which is itself derived from
[J2ME Loader](https://github.com/nikita36078/J2ME-Loader), created by Nikita
Shakarun. Their work and the contributions of other upstream developers remain
fundamental to this project.

JL-Mod Plus is independently maintained and is not an official release of
either upstream project. Product branding has been changed to avoid confusion;
copyright and attribution for inherited work remain with the original authors.

## License

JL-Mod Plus is distributed under the [Apache License 2.0](LICENSE). Copyright
in inherited source remains with its respective authors. H3NB claims copyright
only over JL-Mod Plus modifications and original project assets. See
[NOTICE](NOTICE), dependency metadata, submodule licenses, and individual source
headers for additional attribution.
