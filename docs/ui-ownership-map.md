# UI ownership and protected boundaries

This document describes UI ownership in JL-Mod Plus. It is a
repository contract for UI work: a component is not a migration target
just because it still uses a `View`.

## Ownership map

| Surface | Current owner | Classification | Compatibility reason / next step |
| --- | --- | --- | --- |
| Library list/grid, search, actions, menus, host help/about/licenses | `LibraryComposeBridge.kt`, hosted by `MainActivity`/`AppsListFragment` | Compose-owned presentation; Java/Fragment host | `LibraryViewModel` exposes Room-backed state through StateFlow; file picker, installer intents, and process/runtime calls remain host-owned. |
| Profiles list, create/rename/delete/default actions | `ProfilesComposeBridge.kt`, hosted by `ProfilesActivity` | Compose-owned presentation; transitional Activity host | Existing profile files, persistence, and activity-result editing remain in Java. |
| Installer progress, confirmation, overwrite, failure, cancellation, guest launch | `InstallerComposeBridge.kt`, hosted by `InstallerDialog` | Compose-owned presentation; transitional `DialogFragment` host | AppInstaller Rx sequencing, temporary-file cleanup, repository sync, and guest launch remain unchanged. |
| Settings | `SettingsComposeBridge.kt`, hosted by `SettingsActivity` | Compose-owned presentation; transitional Activity host | SharedPreferences, locale application, raw-path picker, and relaunch behavior remain in Java. |
| Configuration form, color picker, screen presets, charset/profile/shader dialogs | `ConfigComposeBridge.kt`, `ConfigDialogComposeBridge.kt`, hosted by `ConfigActivity` | Compose-owned presentation; transitional Activity/DialogFragment host | Stored formats, validation/defaults, profile copy/overwrite semantics, shader values, activity results, and guest launch remain in Java. The DialogFragment shell is retained only as a lifecycle bridge. |
| Key mapper keypad, mapper menu, reset action, and missing-menu warning | `KeyMapperComposeBridge.kt`, hosted by `KeyMapperActivity` | Compose-owned presentation; transitional Activity host | Activity-level key/touch dispatch, key codes/order, mapping serialization, and cancellation remain in Java. |
| Crash report list/details | `CrashReportsComposeBridge.kt`, hosted by crash Activities | Compose-owned presentation; transitional Activity host | Diagnostic storage, process-isolation and share/export contracts remain in Java. |
| Runtime host toolbar, options menu, and host dialogs | `RuntimeMenuCompose.kt`, `RuntimeHostDialogs.kt`, hosted by `MicroActivity` | Compose-owned Material 3 presentation inside the View runtime shell | Toolbar overflow, Android Back, and legacy menu-key paths share one modal popup. Popup Back only dismisses it; explicit host Exit, a MIDlet Exit command, and system task removal remain the separate termination paths. Midlet selection, recovery, exit/settings, virtual-keyboard layout, and hide/save dialogs use stable callbacks; no MIDP `Command` or input dispatch moves into Compose. |
| Main host container | Programmatic `FrameLayout` + `FragmentContainerView` in `MainActivity` | Transitional programmatic host | The container is still the Fragment host for the library state machine and exported import/install intents; it has no XML visual tree. |
| File picker browsing, search, sort, directory creation, and selection | `FilteredFilePickerActivity.kt`, `FilePickerController.kt`, `FilePickerCompose.kt`, `FilePickerModel.kt` | Compose-owned presentation; transitional Activity/result host | The implementation is app-owned and clean-room. It preserves raw-path `file://` results, JAR/JAD/KJX filtering, storage permissions, start paths, directory mode, cancellation, and work-directory/import callers without a picker dependency. |
| MIDlet shell and rendering | `MicroActivity`, `RuntimeHostView`, `OverlayView`, `CanvasView`/`GlesView`, native C/C++ renderer | Permanent programmatic View boundary with Compose-owned host chrome | The former XML hierarchy is reproduced directly to preserve Surface/overlay geometry. Java ME rendering, lifecycle, orientation, IME, and runtime input remain compatibility-sensitive. |
| Runtime FPS-limit dialog | `RuntimeMenuCompose.kt`, callback to `Canvas.setLimitFps()` | Compose-owned Material 3 presentation | Digits-only input, unlimited value `0`, and reset value `-1` remain unchanged. This dialog was not the MIDP TextBox/TextField editor. |
| Java ME Screen soft keys | `ScreenSoftBarCompose.kt` and `ScreenSoftBarPresentation.kt`, hosted by `ScreenSoftBar` | Compose-owned Material 3 presentation over a protected LCDUI event boundary | `ScreenSoftBarPolicy` owns placement, including single-command cases; `Display.postEvent(CommandActionEvent)` owns dispatch. See [runtime UI](runtime-ui.md). Canvas layer soft keys remain native and close/rebuild stale popups on command updates. |
| Guest/configuration compatibility dialogs | `LoadProfileAlert`, `SaveProfileAlert`, `ShaderTuneAlert`, `Alert`, and platform `AlertDialog` calls | Mixed: Compose body with intentional DialogFragment/platform shells | Profile persistence, validation, shader, and guest-runtime callbacks remain host-owned. The migrated profile/shader bodies no longer inflate XML or use legacy `EditText`/`SeekBar` views. |

## Programmatic View boundaries

There are no remaining app-owned layout/menu XML files under `app/src/main/res`.
The remaining programmatic Views are deliberately bounded:

- `RuntimeHostView`, `FragmentContainerView`, Compose hosts, and
  `DialogFragment` windows are lifecycle/result shells. They do not implement
  Java ME rendering or command semantics. The installer `DialogFragment` keeps
  a platform `Dialog` shell only for lifecycle and cancellation ownership; its
  body is Compose-owned.
- `CanvasView`, `GlesView`, `OverlayView`, `VirtualKeyboard`, and the native
  Canvas/GL surface keep the renderer, pointer/key dispatch, IME connection,
  orientation, and overlay hit geometry intact.
- `TextBox`, `TextFieldImpl`, `Form`, `List`, `ChoiceGroup`, `DateField`,
  `Gauge`, `CustomItem`, and their list adapters are the Java ME/MIDP API
  implementation. Their Android Views are not app-owned host UI and must not
  be rewritten as Compose without a separate API/JSR compatibility review.
- `Alert`/`Display` remain native platform-dialog boundaries for the Java ME
  `Alert` contract; their asynchronous dismissal and `Command` dispatch are
  not app-owned host UI.
- `AbstractSoftKeysBar` remains the native Canvas command popup boundary;
  `ScreenSoftBar` uses Compose for presentation but still dispatches the same
  `CommandActionEvent` path. No `CommandListener` is invoked from Compose.

See [runtime UI](runtime-ui.md) for command, soft-key, IME, and specification
references. Before removing a resource or helper, check source imports,
generated bindings, manifests, resource references, reflection, and Android
resource lookup. Native-looking resources may still serve Java ME controls.

## Dependency decisions

| Dependency family | Current consumers | Decision |
| --- | --- | --- |
| `androidx.activity` | Activity Result APIs and back-press dispatch in host, picker, settings, key mapper, and guest shell | Retain |
| `androidx.appcompat` | Host Activities/themes, locale service, and the Java ME `Alert` platform-dialog boundary | Retain |
| `androidx.fragment` | Library Fragment, installer and compatibility `DialogFragment`s | Retain |
| `androidx.preference` | SharedPreferences access and locale/profile/config persistence | Retain |
| `androidx.lifecycle` | LibraryViewModel/StateFlow and host, installer, and guest lifecycle observers | Retain |
| `androidx.room3` | Library database/entity/DAO/repository | Retain |
| Compose Material 3/runtime/foundation/UI | All migrated app-owned surfaces and screenshot tests | Retain |

The app-owned file picker uses its controller and Compose list. Check
`app/build.gradle.kts` and `gradle/libs.versions.toml` for current dependency
declarations; this table explains ownership rather than pinning versions.

## Validation and manual gates

The Compose screenshot suite and host contract tests cover UI states and
edge-to-edge roots. File-picker tests additionally cover filtering, search,
sorting (including modified time), directory navigation, and result state. The
CI debug job compiles/assembles the host and Android-test artifacts and runs
lint/unit tests; it does not execute connected instrumentation tests. See
[Build and validation](development.md). A connected emulator or device is still
required for the final smoke pass over exported JAR/JAD/KJX intents, raw-path/
file-picker results, permission recovery, install/overwrite/cancel/error
cleanup, guest launch, hardware key/touch dispatch, rotation, and IME behavior.

Navigation 3 is deliberately limited to the Collections overview-to-members flow,
where distinct destinations and an adaptive Material 3 list-detail scene provide a
concrete benefit on wider windows. `LibraryNavigationState.selectedCollectionId`
remains the single route owner, while the collection members payload stays in the UI
store. File Picker navigation remains controller-owned because a second back stack
would only mirror its current directory; the Activity still owns results and root
exit policy. Adaptive UI is active where it has a concrete presentation benefit:
Library and Config choose bottom navigation or a rail from the available container
width, and Library, Collections, and File Picker use adaptive grids. Multiple back
stacks remain unnecessary until a verified independent-history requirement exists.
