# UI ownership and protected boundaries

This document records the post-migration UI audit for JL-Mod Plus. It is a
repository contract for future UI work: a component is not a migration target
just because it still uses a `View`.

Audit baseline: `alpha` at `8b16918dda43f01af4d16d6bfb23b43efbe7d486`.

## How the audit was performed

- searched Java/Kotlin, manifests, menus, and resources for every remaining
  layout, drawable, style, helper, and adapter consumer;
- followed generated ViewBinding imports because binding consumers do not
  mention the XML filename directly;
- checked the current Android dependency declarations against imports and
  runtime/library boundaries;
- treated reflection, Android resource lookup, external file-picker layouts,
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
| Main host container | `activity_main.xml` and `MainActivity` | Transitional View host | The container is still the Fragment host for the library state machine and exported import/install intents. |
| File picker | `FilteredFilePickerActivity`/`FilteredFilePickerFragment`, `listitem_dir.xml`, `listitem_checkable.xml`, `FilePickerTheme` | Intentional View/platform boundary | The forked FilePicker contract owns RecyclerView holders, raw-path selection, permission results, and external picker behavior. |
| MIDlet shell and rendering | `MicroActivity`, `activity_micro.xml`, `OverlayView`, `CanvasView`/`GlesView`, native C/C++ renderer | Permanent native/View boundary | Surface/overlay geometry, Java ME rendering, lifecycle, orientation, IME, and runtime input are compatibility-sensitive. |
| MIDlet text input and limit-FPS dialog | `dialog_input.xml`, `DialogInputBinding`, Material `TextInputLayout` | Permanent native/View boundary | The guest runtime owns this input path and its keyboard/focus semantics. |
| Java ME soft keys | `soft_button_bar.xml`, `ScreenSoftBar`, LCDUI command classes | Permanent native/View boundary | Soft-key hit regions and command dispatch are part of Java ME behavior. |
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

The following are deliberately retained despite looking legacy: all layouts
listed in the ownership map, `bg_button.xml`/`ButtonStyle` (the AppTheme
default button style), file-picker colors/styles, menu icons used by
`midlet_displayable.xml`, and every generated binding still imported by Java.

## Dependency decisions

| Dependency family | Current consumers | Decision |
| --- | --- | --- |
| `androidx.activity` | Activity Result APIs and back-press dispatch in host, picker, settings, key mapper, and guest shell | Retain |
| `androidx.appcompat` | All host Activities, guest shell, AppCompat widgets/dialogs, locale service, and themes | Retain |
| `androidx.fragment` | Library Fragment, installer and compatibility `DialogFragment`s | Retain |
| `androidx.preference` | SharedPreferences access and locale/profile/config persistence | Retain |
| `androidx.recyclerview` | FilePicker RecyclerView holders | Retain |
| `androidx.lifecycle` | AppListModel/ViewModel, LiveData/Rx repository, installer and guest lifecycle observers | Retain |
| `androidx.room` | App database/entity/DAO/repository | Retain |
| `com.google.android.material:material` | `dialog_input.xml` TextInputLayout/EditText and guest input dialog | Retain |
| Compose Material 3/runtime/foundation/UI | All migrated app-owned surfaces and screenshot tests | Retain |
| ConstraintLayout | No current direct declaration or source XML consumer; `androidx.constraintlayout:constraintlayout:2.0.1` is still pulled transitively by Material Components 1.11.0 | Do not add a direct alias; keep the transitive artifact and its notice coverage |

No remaining direct dependency was removed merely because a Compose surface no
longer uses Views: every retained View/platform boundary above still has a
concrete consumer. The app continues to use the shared debug keystore; this
audit does not change signing, min/target SDK, NDK, or Java ME runtime
configuration.

## Validation and manual gates

The Compose screenshot suite and host contract tests cover migrated states and
edge-to-edge roots. The final debug matrix additionally compiles/assembles the
host and Android-test artifacts and runs lint/unit tests. A connected emulator
or device is still required for the final smoke pass over exported JAR/JAD/KJX
intents, raw-path/file-picker results, install/overwrite/cancel/error cleanup,
guest launch, hardware key/touch dispatch, rotation, and IME behavior.

Navigation 3 and adaptive UI remain intentionally deferred: no current screen
requires multiple back stacks, list-detail navigation, or a product-specific
window-size adaptation prerequisite.
