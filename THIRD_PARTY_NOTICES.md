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
| `ic_arrow_back.xml`, `ic_check.xml`, `ic_content_copy.xml`, `ic_share.xml`, `ic_folder.xml`, `ic_file_picker_file.xml`, `ic_file_picker_storage.xml`, `ic_arrow_downward.xml`, `ic_arrow_upward.xml`, `ic_file_download.xml`, `ic_file_upload.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Pinned to revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e`; existing local hash records were retained historically but the revision is the canonical source pin | Apache-2.0 |
| `ic_chevron_right.xml` (source symbol: `chevron_right`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Local asset SHA-256 `df1140e89ec16b6e4f6649929d427faaaafafac230d27f5eacdaa88fed80699e`; source `symbols/android/chevron_right/materialsymbolsoutlined/chevron_right_24px.xml` at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` uses the same path data | Apache-2.0 |
| `ic_bug_report.xml` (source symbol: `bug_report`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | Local asset SHA-256 `51e1b97c5d3d2684dc2560a043b7d1cac26f843d26af71c978fe46861df464de`; source `symbols/android/bug_report/materialsymbolsoutlined/bug_report_24px.xml` at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` has SHA-256 `517479a5c927e84029edf4dd8b8ddba6220554b3545b9d33778719887c2270ce` | Apache-2.0 |
| `ic_action_keyboard.xml` (`keyboard`), `ic_action_screenshot.xml` (`screenshot_frame`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `2cf8f86d6ec092fcd93085a464fe0d27e6aa2bd5a914071cd73874247c8d92fc` and `1c84fad2e6a07601cadc47b860e10f3c1f34af93e207d91e4131291fb9790008`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_apps.xml`, `ic_star.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `6d16e921a41841590351fef37fb40fcba3b92a8846699ec6ab52d23e74e33fb3` and `49d8cf2a439f18bcafc0c9e765ac04cf52edc7105f82c637c50b4e8890fd00b2`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_star_filled.xml` | `https://github.com/google/material-design-icons` | rounded, fill 1, weight 500, grade 0, optical size 24 | SHA-256 `cd856fbefa8393b8b9d7dcf84ddbe37f506eadf282637d3fa3469e0a7049eed1`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_default_midlet.xml` (source symbol: `widgets`) | `https://github.com/google/material-design-icons` | rounded, fill 0, weight 500, grade 0, optical size 48 | SHA-256 `fba03b38b04bc32237fc12237df02795971b2d5cec1261cdc6dc18f32cf81f13`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_config_graphics.xml` (`display_settings`), `ic_config_audio.xml` (`volume_up`), `ic_config_controls.xml` (`gamepad`), `ic_play.xml` (`play_arrow`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_runtime_fps.xml` (`timer`), `ic_speed.xml` (`speed`), `ic_runtime_memory.xml` (`memory`), `ic_runtime_done.xml` (`done`), `ic_swap_horiz.xml` (`swap_horiz`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_license.xml` | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | SHA-256 `04c5ae7ae82289583a80181c11cff9e06e27e686813a202f9d069cb9df8108e2`; revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_settings.xml` (`settings`), `ic_search.xml` (`search`), `ic_history.xml` (`history`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | matching official Android vectors at revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_add.xml` (`add`), `ic_delete.xml` (`delete`), `ic_deselect.xml` (`deselect`), `ic_edit.xml` (`edit`), `ic_help.xml` (`help`), `ic_info.xml` (`info`), `ic_keyboard_arrow_down.xml` (`keyboard_arrow_down`), `ic_keyboard_arrow_up.xml` (`keyboard_arrow_up`), `ic_memory_editor_inspector.xml` (`data_object`), `ic_memory_editor_watch.xml` (`bookmark`), `ic_memory_editor_watch_add.xml` (`bookmark_add`), `ic_more_vert.xml` (`more_vert`), `ic_save.xml` (`save`), `ic_screen_lock_rotation.xml` (`screen_lock_rotation`), `ic_select_all.xml` (`select_all`), `ic_sort.xml` (`sort`), `ic_warning.xml` (`warning`) | `https://github.com/google/material-design-icons` | outlined Material Symbols / equivalent Android vectors | Existing local vectors retain their current geometry; explicit source comments and/or geometry trace them to the named Google Material Symbols. New and revised assets use the repository pin `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e`. | Apache-2.0 |
| `ic_install.xml` (`install_mobile`), `ic_recently_added.xml` (`calendar_add_on`), `ic_add_shortcut.xml` (`add_to_home_screen`), `ic_create_folder.xml` (`create_new_folder`), `ic_add_to_collection.xml` (`playlist_add`), `ic_remove_from_collection.xml` (`playlist_remove`), `ic_collections.xml` (`folder_copy`), `ic_more.xml` (`more_horiz`), `ic_remove.xml` (`remove`), `ic_open_external.xml` (`open_in_new`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_config_general.xml` (`tune`), `ic_check_circle.xml` (`check_circle`), `ic_save_as.xml` (`save_as`), `ic_sync.xml` (`sync`), `ic_refresh.xml` (`refresh`), `ic_reinstall.xml` (`restart_alt`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |
| `ic_lock.xml` (`lock`), `ic_unlock.xml` (`lock_open`), `ic_close.xml` (`close`), `ic_exit.xml` (`exit_to_app`), `ic_virtual_controls.xml` (`joystick`), `ic_control_layout.xml` (`dashboard_customize`), `ic_auto_mode.xml` (`auto_mode`), `ic_visibility.xml` (`visibility`), `ic_visibility_off.xml` (`visibility_off`) | `https://github.com/google/material-design-icons` | outlined, fill 0, weight 400, grade 0, optical size 24 | revision `e083cc60a0828fdd3b404cea0cb8a5b900e9c23e` | Apache-2.0 |


### Custom Material-style icon

`ic_memory_editor_search_unknown.xml` is a local composite used for the
Memory Editor's **Search unknown values** action. It combines the Material
Symbols search silhouette with a question-mark treatment so the unknown-value
search remains visually distinct from ordinary known-value search. The
Material-derived geometry is covered by Google's Apache-2.0 licensing; the
combined VectorDrawable is maintained locally because no canonical glyph
expresses this action as clearly.

### `third_party/` audit

The current repository tree does **not** contain a `third_party/` directory. Sonivox is a vendored, pinned snapshot rather than an external submodule; TinySoundFont and TinyMidiLoader were removed by the synthesis migration. Historical notices for `third_party/minimp3`, `third_party/stb`, Mesa-derived code, or other absent paths must not be treated as current shipped provenance.

## Runtime dependency families

The table below covers direct runtime dependencies and license-significant transitives observed in the current Gradle runtime graph. AndroidX modules are grouped by origin rather than enumerated one by one; exact resolved versions remain available from Gradle/CI.

| Runtime component / coordinates | Origin | License / notice |
| --- | --- | --- |
| `androidx.*` (Activity, Core, AppCompat, Compose, Fragment, Lifecycle, Room, Preference, Transition and transitives) | Android Open Source Project / AndroidX | Apache-2.0 |
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
