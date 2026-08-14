# UI ownership and protected boundaries

This document records the post-migration UI audit for JL-Mod Plus. It is a
repository contract for future UI work: a component is not a migration target
just because it still uses a `View`.

Audit baseline: `alpha` at `e01e7007`.

## How the audit was performed

- searched Java/Kotlin, manifests, menus, and resources for every remaining
  layout, drawable, style, helper, and adapter consumer;
- followed generated ViewBinding imports because binding consumers do not
  mention the XML filename directly;
- checked the current Android dependency declarations against imports and
  runtime/library boundaries;
- treated reflection, Android resource lookup, file-picker result contracts,
  and Java ME runtime code as protected until their consumers were proven
  absent.

## Ownership map

| Surface | Current owner | Classification | Compatibility reason / next step |
| --- | --- | --- | --- |
| Library list/grid, search, actions, menus, host help/about/licenses | `LibraryComposeBridge.kt`, hosted by `MainActivity`/`AppsListFragment` | Compose-owned presentation; transitional Java/Fragment host | Room/AppListModel, LiveData/Rx, file picker, installer intents, and process/runtime calls remain in the host. |
| Profiles list, create/rename/delete/default actions | `ProfilesComposeBridge.kt`, hosted by `ProfilesActivity` | Compose-owned presentation; transitional Activity host | Existing profile files, persistence, and activity-result editing remain in Java. |
| Installer progress, confirmation, overwrite, failure, cancellation, guest launch | `InstallerComposeBridge.kt`, hosted by `InstallerDialog` | Compose-owned presentation; transitional `DialogFragment` host | AppInstaller Rx sequencing, temporary-file cleanup, repository sync, and guest launch remain unchanged. |
| Settings | `SettingsComposeBridge.kt`, hosted by `SettingsActivity` | Compose-owned presentation; transitional Activity host | SharedPreferences, locale application, raw-path picker, and relaunch behavior remain in Java. |
| Configuration form, color picker, screen presets | `ConfigComposeBridge.kt`, hosted by `ConfigActivity` | Compose-owned presentation; transitional Activity host | Stored formats, validation/defaults, activity results, native pickers, profile dialogs, and guest launch remain in Java. |
| Key mapper keypad and mapping prompt | `KeyMapperComposeBridge.kt`, hosted by `KeyMapperActivity` | Compose-owned presentation; transitional Activity host | Activity-level key/touch dispatch, key codes/order, mapping serialization, and cancellation remain in Java. |
| Crash report list/details | `CrashReportsComposeBridge.kt`, hosted by crash Activities | Compose-owned presentation; transitional Activity host | Diagnostic storage, process-isolation and share/export contracts remain in Java. |
| Runtime host toolbar and options menu | `RuntimeMenuCompose.kt`, hosted by `MicroActivity` | Compose-owned Material 3 presentation inside the View runtime shell | Toolbar overflow, Android Back, and legacy menu-key paths share one modal popup. Popup Back only dismisses it; explicit host Exit, a MIDlet Exit command, and system task removal remain the separate termination paths. Existing callbacks remain owned by `MicroActivity`; no MIDP `Command` or input dispatch moves into Compose. |
| Main host container | `activity_main.xml` and `MainActivity` | Transitional View host | The container is still the Fragment host for the library state machine and exported import/install intents. |
| File picker browsing, search, sort, directory creation, and selection | `FilteredFilePickerActivity.kt`, `FilePickerController.kt`, `FilePickerCompose.kt`, `FilePickerModel.kt` | Compose-owned presentation; transitional Activity/result host | The implementation is app-owned and clean-room. It preserves raw-path `file://` results, JAR/JAD/KJX filtering, storage permissions, start paths, directory mode, cancellation, and work-directory/import callers without a picker dependency. |
| MIDlet shell and rendering | `MicroActivity`, `RuntimeHostView`, `OverlayView`, `CanvasView`/`GlesView`, native C/C++ renderer | Permanent programmatic View boundary with Compose-owned host chrome | The former XML hierarchy is reproduced directly to preserve Surface/overlay geometry. Java ME rendering, lifecycle, orientation, IME, and runtime input remain compatibility-sensitive. |
| Runtime FPS-limit dialog | `RuntimeMenuCompose.kt`, callback to `Canvas.setLimitFps()` | Compose-owned Material 3 presentation | Digits-only input, unlimited value `0`, and reset value `-1` remain unchanged. This dialog was not the MIDP TextBox/TextField editor. |
| Java ME Screen soft keys | `ScreenSoftBarCompose.kt` and `ScreenSoftBarPresentation.kt`, hosted by `ScreenSoftBar` | Compose-owned Material 3 presentation over a protected LCDUI event boundary | MIDP/vendor placement policy prefers `OK` for middle and `BACK`/`EXIT` for right, with remaining commands in the left menu; `Display.postEvent(CommandActionEvent)` dispatch remains unchanged. Canvas layer soft keys remain native and close/rebuild stale popups on command updates. |
| Guest/configuration compatibility dialogs | `LoadProfileAlert`, `SaveProfileAlert`, `ShaderTuneAlert`, `Alert`, and platform `AlertDialog` calls | Intentional transitional View boundary | These dialogs still carry persistence, validation, shader, or guest-runtime contracts; migrate only with focused characterization coverage. |

## Resources removed only after consumer audit

The following files had no source, manifest, menu, binding, or resource
consumer in the current tree and were removed by PR 7:

- obsolete mapper/settings vectors: `ic_arrow_down.xml`, `ic_arrow_up.xml`,
  `ic_baseline_tune_24.xml`, and `ic_setting_*.xml`;
- the unused local copy of `simple_list_item_multiple_choice.xml` (LCDUI uses
  `android.R.layout.simple_list_item_multiple_choice`);
- the unused `ViewUtils` measurement helper;
- the unused `TextViewVendor` style (including its `values-ldrtl` override),
  `fab_material_red_500`, and stale About links for 4PDA, Crowdin, and XDA.

The following are deliberately retained despite looking legacy:
`bg_button.xml`/`ButtonStyle` (the AppTheme default button style), the remaining
profile/shader dialog layouts with active binding consumers, Material Symbols
used by Compose, and every generated binding still imported by Java. The
runtime host/menu, FPS-input, and Screen soft-key XML files were removed only
after their replacements preserved geometry, values, command ordering, and
callback dispatch. The forked picker rows, theme, and colors were removed only
after the app-owned replacement compiled and its contract/UI tests were added.

## Dependency decisions

| Dependency family | Current consumers | Decision |
| --- | --- | --- |
| `androidx.activity` | Activity Result APIs and back-press dispatch in host, picker, settings, key mapper, and guest shell | Retain |
| `androidx.appcompat` | All host Activities, guest shell, AppCompat widgets/dialogs, locale service, and themes | Retain |
| `androidx.fragment` | Library Fragment, installer and compatibility `DialogFragment`s | Retain |
| `androidx.preference` | SharedPreferences access and locale/profile/config persistence | Retain |
| `androidx.lifecycle` | AppListModel/ViewModel, LiveData/Rx repository, installer and guest lifecycle observers | Retain |
| `androidx.room` | App database/entity/DAO/repository | Retain |
| Compose Material 3/runtime/foundation/UI | All migrated app-owned surfaces and screenshot tests | Retain |

The file-picker and direct RecyclerView dependencies are removed because the
new picker owns its state and Compose list. Every other retained dependency
still has a concrete consumer. The app continues to use the shared debug
keystore; this
audit does not change signing, min/target SDK, NDK, or Java ME runtime
configuration.

## Validation and manual gates

The Compose screenshot suite and host contract tests cover migrated states and
edge-to-edge roots. File-picker tests additionally cover filtering, search,
sorting (including modified time), directory navigation, and result state. The
final debug matrix additionally compiles/assembles the host and Android-test
artifacts and runs lint/unit tests. A connected emulator or device is still
required for the final smoke pass over exported JAR/JAD/KJX intents, raw-path/
file-picker results, permission recovery, install/overwrite/cancel/error
cleanup, guest launch, hardware key/touch dispatch, rotation, and IME behavior.

Navigation 3 and adaptive UI remain intentionally deferred: no current screen
requires multiple back stacks, list-detail navigation, or a product-specific
window-size adaptation prerequisite.
