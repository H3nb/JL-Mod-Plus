# Third-party provenance and license inventory

This file is the canonical provenance ledger for third-party software currently bundled by JL-Mod Plus. It was audited against `alpha` at `cf7542e602c280b2caa677a0cbbc2b37c8bdc08a` and the `emulatorDebugRuntimeClasspath` captured by Android CI #130.

The app-facing copy is `app/src/main/assets/licenses.html`, reachable from **About -> Licenses**. Source-file copyright headers, published artifact metadata, and upstream license files remain authoritative when they are more specific than this summary.

This ledger is a provenance/notice inventory for the current project state, not a legal certification. Release packaging should be re-audited when a bundled source component, runtime coordinate, or native binary package changes.

## Bundled and inherited source

| Component | Local evidence / path | Traceable origin | License / redistribution note |
| --- | --- | --- | --- |
| JL-Mod / J2ME Loader lineage | Main application/emulator sources; representative `javax.microedition.shell` files retain upstream authorship | `https://github.com/woesss/JL-Mod` and inherited J2ME Loader history | Apache-2.0 unless a file carries a more specific notice |
| MicroEmulator subset | `app/src/main/java/org/microemu/**` | MicroEmulator; source headers identify the project and Bartek Teodorczyk | Source offers LGPL-2.1-or-later **OR** Apache-2.0; JL-Mod Plus relies on the Apache-2.0 alternative for redistribution |
| Android dx/dex | `dexlib/src/main/java/com/android/dx/**`, `dexlib/src/main/java/com/android/dex/**` | Android Open Source Project | Apache-2.0 |
| Nokia M3G / JSR-184 native reference code | `app/src/main/cpp/m3g/src/**`; source headers identify Nokia Corporation | Nokia M3G reference implementation lineage carried by JL-Mod | EPL-1.0; source is available in the public JL-Mod Plus repository at the local path shown |
| SoniVox EAS | `app/src/main/cpp/sonivox/**`; source headers identify Sonic Network Inc. | Android/SoniVox Embedded Audio Synthesis code lineage | Apache-2.0 |
| Mascot Capsule Micro3D implementation | `app/src/main/java/com/mascotcapsule/micro3d/**` and `app/src/main/cpp/micro3d/**`; current files identify JL-Mod/Yury Kharchenko/woesss authorship | JL-Mod implementation currently in this tree | Apache-2.0 where stated by the source files |
| TinySoundFont | Git submodule `app/src/main/cpp/mmapi_tsf/TinySoundFont` pinned at `0d10306120037ce049a7699f9eaa5314d5b888f8` | `https://github.com/schellingb/TinySoundFont` | MIT |
| TinyMidiLoader | `tml.h` in the same pinned TinySoundFont submodule | `https://github.com/schellingb/TinySoundFont` | Zlib |

## Material Symbols assets

The Crash Reports Compose pilot uses four official Material Symbols Android VectorDrawable assets. They
were downloaded with `scripts/material-symbols.py` as developer-time inputs and are committed locally;
Gradle and CI do not access the network to obtain them.

| Local resources | Source | Variant | Revision / SHA-256 | License |
| --- | --- | --- | --- | --- |
| `ic_arrow_back.xml`, `ic_content_copy.xml`, `ic_share.xml`, `ic_delete_report.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | `50f0603134ce7b70b2d71b686cc13e8b57ccb74c`; arrow back `cd1f5a1109c07c79ac3e52a9a1ae8ab14be7a3bff7f7bc3559f7088d60eca3aa`; content copy `f83b9c4f3f51b5a64365e6222498934cbccaead05fd720d4cabcb175eb50d2d4`; share `e68cc51976886c8395e3317d8bea0b8324e98d365ed8a15fc8c67767c2241931`; delete `8589dfd0ab9c15182ba5698bd38c4502ad8f476dbd983e840a709a24281211fa` | Apache-2.0 |

### `third_party/` audit

The current repository tree does **not** contain a `third_party/` directory. The active external native checkout is the TinySoundFont submodule at `app/src/main/cpp/mmapi_tsf/TinySoundFont`, and `.gitmodules` points to its upstream repository directly. Historical notices for `third_party/minimp3`, `third_party/stb`, Mesa-derived code, or other absent paths must not be treated as current shipped provenance.

## Runtime dependency families

The table below covers direct runtime dependencies and license-significant transitives observed in the current Gradle runtime graph. AndroidX modules are grouped by origin rather than enumerated one by one; exact resolved versions remain available from Gradle/CI.

| Runtime component / coordinates | Origin | License / notice |
| --- | --- | --- |
| `androidx.*` (Activity, Core, AppCompat, Fragment, Lifecycle, Room, Preference, RecyclerView, ConstraintLayout, Transition and transitives) | Android Open Source Project / AndroidX | Apache-2.0 |
| Kotlin stdlib and `kotlinx-coroutines-*`, plus `org.jetbrains:annotations` | JetBrains Kotlin projects | Apache-2.0 |
| `com.google.android.material:material` | Material Components for Android | Apache-2.0 |
| `com.google.code.gson:gson` | Google Gson | Apache-2.0 |
| `com.google.oboe:oboe` | Google Oboe | Apache-2.0 |
| `ch.acra:acra-core` | ACRA | Apache-2.0 |
| `org.jspecify:jspecify` | JSpecify | Apache-2.0 |
| `org.checkerframework:checker-qual` | Checker Framework | MIT |
| `io.github.nikita36078:ffmpeg-kit:6.0.LTS` | Maven SCM / source: `https://github.com/nikita36078/ffmpeg-kit` | Published POM declares LGPL-3.0. FFmpegKit documentation notes GPL-3.0 applies when GPL libraries are enabled; FFmpeg and bundled external libraries retain their own upstream terms. |
| `com.arthenica:smart-exception-java:0.2.1`, `smart-exception-common:0.2.1` | `https://github.com/tanersener/smart-exception` | BSD-3-Clause |
| `com.github.woesss:filepicker:4.4.0` | `https://github.com/woesss/filepicker`, fork of NoNonsense-FilePicker | MPL-2.0; source for the distributed fork is available at the origin URL |
| `com.github.nikita36078:pngj:2.2.3` | `https://github.com/nikita36078/pngj`, fork of `leonbloy/pngj` | Apache-2.0 |
| `junit:junit:4.12` (runtime transitive of current PNGJ artifact) | JUnit 4, `https://github.com/junit-team/junit4` | EPL-1.0; source is available at the origin URL |
| `org.hamcrest:hamcrest-core:1.3` (runtime transitive of JUnit 4.12) | Hamcrest | BSD-3-Clause |
| `io.reactivex.rxjava2:rxjava`, `io.reactivex.rxjava2:rxandroid` | ReactiveX | Apache-2.0 |
| `org.reactivestreams:reactive-streams` | Reactive Streams JVM API | MIT-0 |
| `net.lingala.zip4j:zip4j:2.11.6` | `https://github.com/srikanth-lingala/zip4j` | Apache-2.0 |
| `org.ow2.asm:asm:9.6` | OW2 ASM | BSD-3-Clause |

## Source availability for reciprocal-license components

The following public source locations are recorded so recipients can trace the source corresponding to reciprocal-license components in the current distribution:

- Nokia M3G / JSR-184 EPL-1.0 source: `https://github.com/H3nb/JL-Mod-Plus/tree/alpha/app/src/main/cpp/m3g/src`
- FFmpegKit 6.0.LTS LGPL-3.0 source/SCM: `https://github.com/nikita36078/ffmpeg-kit`
- FilePicker MPL-2.0 source: `https://github.com/woesss/filepicker`
- JUnit 4 EPL-1.0 source: `https://github.com/junit-team/junit4`

The exact Maven coordinate or pinned submodule revision in this ledger identifies the artifact/source snapshot used by JL-Mod Plus where such a pin exists. These source links are notice/provenance pointers; they do not replace the upstream license terms or any additional redistribution obligations those licenses may impose.

The Gradle runtime graph is the authority for what resolves into the current APK configuration. If a dependency is added, removed, or changes license/package composition, this ledger and the in-app notice must be reviewed in the same change or immediately before release.

## Repository-only vendored material

`.agents/skills/**` contains selected Android Skills reference material used for development/agent guidance, not application runtime code. Its provenance is already pinned in `.agents/PROVENANCE.md`, with the corresponding Apache-2.0 terms in `.agents/LICENSE.txt`. It is intentionally excluded from the app-facing Licenses screen because it is not shipped as emulator runtime content.

## Audit corrections from the legacy in-app notice

The previous `licenses.html` mixed historical and current components. This audit makes the following corrections:

- removes Volley and Android Donations Lib because they are not part of the current runtime graph/project dependency set;
- replaces the old MobileFFmpeg label with the actual `io.github.nikita36078:ffmpeg-kit:6.0.LTS` artifact and its published LGPL-3.0 metadata;
- replaces the ambiguous `Symbian OS` label with the actual Nokia M3G / JSR-184 native source attribution and EPL-1.0;
- removes the old FreeJ2ME M3D(O) attribution because current Mascot Capsule/Micro3D source in this tree carries JL-Mod/Yury Kharchenko/woesss provenance instead;
- removes the old Aha-Soft launcher attribution because the JL-Mod Plus launcher assets were replaced in the project-foundation change and are not the inherited upstream launcher blobs;
- adds TinyMidiLoader separately from TinySoundFont because its source header uses the Zlib license rather than TinySoundFont's MIT license;
- records JUnit 4.12 and Hamcrest Core 1.3 because the current PNGJ fork places them on `emulatorDebugRuntimeClasspath`, even though JL-Mod Plus does not declare them directly.

## License references

Canonical license identifiers used above:

- Apache-2.0 — `https://www.apache.org/licenses/LICENSE-2.0`
- BSD-3-Clause — `https://opensource.org/license/bsd-3-clause`
- EPL-1.0 — `https://www.eclipse.org/legal/epl-v10.html`
- LGPL-3.0 — `https://www.gnu.org/licenses/lgpl-3.0.html`
- MIT — `https://opensource.org/license/mit`
- MIT-0 — `https://opensource.org/license/mit-0`
- MPL-2.0 — `https://www.mozilla.org/MPL/2.0/`
- Zlib — `https://www.zlib.net/zlib_license.html`

This inventory documents provenance and notice coverage; it does not override or replace the license text distributed by each upstream component.
