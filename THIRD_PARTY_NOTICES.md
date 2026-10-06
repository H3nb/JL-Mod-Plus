# Third-party provenance and license inventory

This file is the provenance ledger for third-party software bundled by JL-Mod Plus. Its last full runtime-classpath audit used `alpha` at `8b16918dda43f01af4d16d6bfb23b43efbe7d486` on 2026-08-14; that historical audit is not verification of subsequent dependency changes.

The app-facing copy is `app/src/main/assets/licenses.html`, reachable from **About -> Licenses**. Source-file copyright headers, published artifact metadata, and upstream license files remain authoritative when they are more specific than this summary.

This ledger is a provenance/notice inventory for the current project state, not a legal certification. Release packaging should be re-audited when a bundled source component, runtime coordinate, or native binary package changes.

## Bundled and inherited source

| Component | Local evidence / path | Traceable origin | License / redistribution note |
| --- | --- | --- | --- |
| JL-Mod / J2ME Loader lineage | Main application/emulator sources; representative `javax.microedition.shell` files retain upstream authorship | `https://github.com/woesss/JL-Mod` and inherited J2ME Loader history | Apache-2.0 unless a file carries a more specific notice |
| MicroEmulator subset | `app/src/main/java/org/microemu/**` | MicroEmulator; source headers identify the project and Bartek Teodorczyk | Source offers LGPL-2.1-or-later **OR** Apache-2.0; JL-Mod Plus relies on the Apache-2.0 alternative for redistribution |
| Android dx/dex | `dexlib/src/main/java/com/android/dx/**`, `dexlib/src/main/java/com/android/dex/**` | Android Open Source Project | Apache-2.0 |
| Nokia M3G / JSR-184 native reference code | `app/src/main/cpp/m3g/src/**`; source headers identify Nokia Corporation | Nokia M3G reference implementation lineage carried by JL-Mod | EPL-1.0; source is available in the public JL-Mod Plus repository at the local path shown |
| SoniVox EAS | Vendored `app/src/main/cpp/sonivox/**`; local patches in `UPSTREAM.md`, upstream `NOTICE` and license retained | `https://github.com/EmbeddedSynth/sonivox`, v4.0.2 at `bb8668b91118318b0e6017aca59ce34e7bc93bf8`; Sonic Network Inc. / Android lineage | Apache-2.0 |
| Mascot Capsule Micro3D implementation | `app/src/main/java/com/mascotcapsule/micro3d/**` and `app/src/main/cpp/micro3d/**`; current files identify JL-Mod/Yury Kharchenko/woesss authorship | JL-Mod implementation currently in this tree | Apache-2.0 where stated by the source files |

## Material Symbols assets

The table below records the Material Symbols Android VectorDrawable assets bundled by JL-Mod Plus. They
were downloaded with `scripts/material-symbols.py` as developer-time inputs and are committed locally;
Gradle and CI do not access the network to obtain them.

| Local resources | Source | Variant | Revision / SHA-256 | License |
| --- | --- | --- | --- | --- |
| `ic_arrow_back.xml`, `ic_check.xml`, `ic_content_copy.xml`, `ic_send.xml`, `ic_share.xml`, `ic_delete_report.xml`, `ic_folder.xml`, `ic_file_picker_file.xml`, `ic_file_picker_storage.xml`, `ic_arrow_downward.xml`, `ic_arrow_upward.xml`, `ic_palette.xml`, `ic_file_download.xml`, `ic_file_upload.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | All assets are pinned to their recorded source revisions; check SHA-256 `b38c518aa15e88cb9f2eda91aa5617427530ae9359a1b2c8ab96f7d16bffabe7`, arrow downward `eac1ec84bb5251dfd1dec10a3db387363594eb20247bacaddf1c033ff954094d`, arrow upward `b398faf24ea12a63b2c4f708d8be24c8ae3714d277ea82da8f405cd066873d0a`, palette `77da392d239862f1c1a4f736d698debcffe9477eacb49947b41210360af23b92`, file download `0b928b12f6297196976a480ef6d6e10377df6fe8bdce4afb30e7ad2d99437008`, and file upload `5fdca5e4b9e758f9940254494ac614bc1cd66fa364a954319fa120fea9fc9bff`, send `44fc4e67b392577b6c059c4bd4f851fed079b321133dbcfbccad89d92b0c3853`, all revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_chevron_right.xml` (source symbol: `chevron_right`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Local asset SHA-256 `df1140e89ec16b6e4f6649929d427faaaafafac230d27f5eacdaa88fed80699e`; source `symbols/android/chevron_right/materialsymbolsoutlined/chevron_right_24px.xml` at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` uses the same path data | Apache-2.0 |
| `ic_bug_report.xml` (source symbol: `bug_report`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Local asset SHA-256 `51e1b97c5d3d2684dc2560a043b7d1cac26f843d26af71c978fe46861df464de`; source `symbols/android/bug_report/materialsymbolsoutlined/bug_report_24px.xml` at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` has SHA-256 `517479a5c927e84029edf4dd8b8ddba6220554b3545b9d33778719887c2270ce` | Apache-2.0 |
| `ic_action_keyboard.xml` (`keyboard`), `ic_action_screenshot.xml` (`screenshot_frame`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `2cf8f86d6ec092fcd93085a464fe0d27e6aa2bd5a914071cd73874247c8d92fc` and `1c84fad2e6a07601cadc47b860e10f3c1f34af93e207d91e4131291fb9790008`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_apps.xml`, `ic_star.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `6d16e921a41841590351fef37fb40fcba3b92a8846699ec6ab52d23e74e33fb3` and `49d8cf2a439f18bcafc0c9e765ac04cf52edc7105f82c637c50b4e8890fd00b2`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_star_filled.xml` | `https://github.com/google/material-design-icons` | rounded, fill 1, weight 500, grade 0, optical size 24 | SHA-256 `cd856fbefa8393b8b9d7dcf84ddbe37f506eadf282637d3fa3469e0a7049eed1`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_default_midlet.xml` (source symbol: `widgets`) | `https://github.com/google/material-design-icons` | rounded, fill 0, weight 500, grade 0, optical size 48 | SHA-256 `fba03b38b04bc32237fc12237df02795971b2d5cec1261cdc6dc18f32cf81f13`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_config_quick.xml` (`bolt`), `ic_config_graphics.xml` (`display_settings`), `ic_config_audio.xml` (`volume_up`), `ic_config_controls.xml` (`gamepad`), `ic_play.xml` (`play_arrow`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `143ab3540dd135c4d7637bd2220bcb04ff76a4b0858de2851c91678161cfbfae`, `cafcc7daff418ee18f900dd683e3ff3f1c5dbfb8215ce609ab7a9201c693028c`, `cf6148478dc7965dad942a5f35ab1a613450f56f991e7e75129c2049f687a4d4`, `4ece323d53aee9d233ffd0a067353d8580ccfcee0b9a7d499584a0493de5812d`, and `4cfe5685d7c22230c1263f85efd46dee0e06103b01402a9863c7f15ab0ea32c`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_runtime_fps.xml` (`timer`), `ic_speed.xml` (`speed`), `ic_runtime_memory.xml` (`memory`), `ic_runtime_virtual_keyboard.xml` (`dialpad`), `ic_runtime_done.xml` (`done`), `ic_swap_horiz.xml` (`swap_horiz`), `ic_runtime_hide.xml` (`visibility_off`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `4b2f36391129b187279128189ab02431588dee6111ebf3fbd3c7046fdbdfc0b6`, `3fa39ed5e53ecec86bf64c32b60632473878138cc342c97568c40ed4b38c6396`, `fb24f19cb38db3dc7ecab82eaa9faff129227190f202dbfeea125b8128ec68f5`, `8921c282acae7546e37207388395a8ff245510003d4f24f7d0b98608dc0aa2d6`, `b38c518aa15e88cb9f2eda91aa5617427530ae9359a1b2c8ab96f7d16bffabe7`, `1f07c145dc40e901ae12557005471f7c3bcae8196e0e123ab53dc8ab863d25b9`, and `24c67489b46f0bae2062ef24e0334ae3252f5d45fadff1ecfaedec43777730b7`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_license.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `04c5ae7ae82289583a80181c11cff9e06e27e686813a202f9d069cb9df8108e2`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |

| `ic_settings.xml` (`settings`), `ic_search.xml` (`search`), `ic_history.xml` (`history`), `ic_auto_awesome.xml` (`auto_awesome`), `ic_memory_editor_visibility.xml` (`visibility`), `ic_memory_editor_visibility_off.xml` (`visibility_off`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Matching official Android vectors at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |

## Phosphor Icons assets

`app/src/main/res/drawable/ic_memory_editor_close.xml`, `ic_profile_use.xml`,
`ic_profile_save_as.xml`, and `ic_profile_update.xml` are VectorDrawable conversions of
Phosphor Icons' regular `X`, `CheckCircle`, `FilePlus`, and `ArrowsClockwise` SVGs, pinned at
revision `3370cb1bc0a31ef3610367f3bd985462c2e201ea`.
Only these required static assets are vendored.

MIT License

Copyright (c) 2023 Phosphor Icons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### `third_party/` audit

The current repository tree does **not** contain a `third_party/` directory. Sonivox is a vendored, pinned snapshot rather than an external submodule; TinySoundFont and TinyMidiLoader were removed by the synthesis migration. Historical notices for `third_party/minimp3`, `third_party/stb`, Mesa-derived code, or other absent paths must not be treated as current shipped provenance.

## Runtime dependency families

The table below covers direct runtime dependencies and license-significant transitives observed in the current Gradle runtime graph. AndroidX modules are grouped by origin rather than enumerated one by one; exact resolved versions remain available from Gradle/CI.

| Runtime component / coordinates | Origin | License / notice |
| --- | --- | --- |
| `androidx.*` (Activity, Core, AppCompat, Compose, Fragment, Lifecycle, Navigation, Room, Preference, Transition and transitives) | Android Open Source Project / AndroidX | Apache-2.0 |
| Kotlin stdlib and `kotlinx-coroutines-*`, plus `org.jetbrains:annotations` | JetBrains Kotlin projects | Apache-2.0 |
| `com.google.code.gson:gson` | Google Gson | Apache-2.0 |
| `com.google.oboe:oboe` | Google Oboe | Apache-2.0 |
| `org.jspecify:jspecify` | JSpecify | Apache-2.0 |
| `org.checkerframework:checker-qual` | Checker Framework | MIT |
| FFmpeg n8.1.3 native libraries | `https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz`, revision `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7`; [recipe](tools/audio/README.md) | Configured LGPL-3.0-or-later with `--enable-version3`, without GPL libraries. |
| OpenCORE-AMR 0.1.6 NB/WB decoders | `https://github.com/arthenica/opencore-amr`, revision `7dba8c32238418ce0b316a852b2224df586ca896`; [recipe](tools/audio/README.md) | Apache-2.0; PacketVideo and Martin Storsjo notices retained. Static decoder libraries are linked into the one FFmpeg build. |
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
- FFmpeg n8.1.3 source archive: `https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz`; SHA256 `7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3`. The corresponding build configuration is in `tools/audio/`.
- OpenCORE-AMR 0.1.6 source: `https://github.com/arthenica/opencore-amr/tree/7dba8c32238418ce0b316a852b2224df586ca896`; archive SHA256 `fc302cea3b65072f87d950d77ee5a7014347536039a7de7668da2891d386147e`.
- JUnit 4 EPL-1.0 source: `https://github.com/junit-team/junit4`

The exact Maven coordinate or pinned submodule revision in this ledger identifies the artifact/source snapshot used by JL-Mod Plus where such a pin exists. These source links are notice/provenance pointers; they do not replace the upstream license terms or any additional redistribution obligations those licenses may impose.

The APK includes the applicable FFmpeg LGPL/GPL license texts and the complete
OpenCORE Apache license/upstream notice in `app/src/main/assets/audio-licenses/`,
linked from the Licenses screen. The complete upstream OpenCORE notice also
mentions portions outside the selected decoder build; it is preserved verbatim.

The Gradle runtime graph and native packaging are the authority for what resolves into the current APK configuration. If a dependency is added, removed, or changes license/package composition, this ledger and the in-app notice must be reviewed in the same change or immediately before release.

## Repository-only vendored material

`app/src/main/cpp/sonivox/lib_src/minimp3.h` is retained from the pinned upstream snapshot under its CC0 notice. Its optional decoder is disabled in the Android build and is not shipped as active runtime code.

`.agents/skills/**` contains selected Android Skills reference material used for development/agent guidance, not application runtime code. Its provenance is pinned in [.agents/UPSTREAM.md](.agents/UPSTREAM.md), with the corresponding Apache-2.0 terms in `.agents/LICENSE.txt`. It is intentionally excluded from the app-facing Licenses screen because it is not shipped as emulator runtime content.

## Audit corrections from the legacy in-app notice

The previous `licenses.html` mixed historical and current components. This audit makes the following corrections:

- removes Volley and Android Donations Lib because they are not part of the current runtime graph/project dependency set;
- records the pinned native FFmpeg/OpenCORE build; MobileFFmpeg and FFmpegKit are no longer shipped;
- replaces the ambiguous `Symbian OS` label with the actual Nokia M3G / JSR-184 native source attribution and EPL-1.0;
- removes the old FreeJ2ME M3D(O) attribution because current Mascot Capsule/Micro3D source in this tree carries JL-Mod/Yury Kharchenko/woesss provenance instead;
- removes the old Aha-Soft launcher attribution because the JL-Mod Plus launcher assets were replaced in the project-foundation change and are not the inherited upstream launcher blobs;
- the earlier inventory listed TinySoundFont and TinyMidiLoader separately; both have since been removed from source and APK packaging;
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
