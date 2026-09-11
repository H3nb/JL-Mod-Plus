# Upstream porting notes

JL-Mod Plus uses the following canonical implementation namespaces. This is a package identity migration only; Android application IDs, guest Java ME APIs, persisted data, and native library names remain unchanged.

| Existing implementation namespace | JL-Mod Plus namespace |
| --- | --- |
| `ru.playsoftware.j2meloader` | `io.github.h3nb.jlmodplus` |
| `ru.woesss.j2me.installer` | `io.github.h3nb.jlmodplus.installer` |
| `ru.woesss.j2me.jar` | `io.github.h3nb.jlmodplus.jar` |
| `ru.woesss.j2me.mmapi` | `io.github.h3nb.jlmodplus.mmapi` |
| `ru.woesss.j2me.micro3d` | `io.github.h3nb.jlmodplus.micro3d` |
| `ru.woesss.gles` | `io.github.h3nb.jlmodplus.gles` |

The local `dexlib` implementation remains outside this mapping: `ru.woesss.util.*` and the dexlib namespace are intentionally unchanged. `com.*`, `javax.*`, `mmpp.*`, and `org.*` packages likewise remain unchanged because they are compatibility APIs, guest APIs, or unrelated dependencies.

Compatibility details retained during the move:

- The old absolute component names for `LauncherActivity`, `MainActivity`, `config.ConfigActivity`, and `settings.SettingsActivity` are explicit-only activity aliases targeting the new classes. Only the legacy launcher alias owns the `MAIN`/`LAUNCHER` filter.
- Diagnostic stack parsing accepts both the canonical and legacy host prefixes. Existing reports and diagnostic fixtures are not rewritten.
- The protocol extras `ru.playsoftware.j2meloader.memory.extra.RUNTIME_TOKEN`, `ru.playsoftware.j2meloader.crashes.REPORT_ID`, and the debug runtime-probe extra remain literal compatibility keys.
- The historical Room schema directory is preserved alongside byte-identical copies under the canonical qualified class name. Database filename, schema version, migrations, and identity hash are unchanged.
- GLES, Micro3D, EAS, and TSF JNI entry points use the canonical host names while their Java ME contracts and native library names remain unchanged.

Inherited Apache-2.0 source keeps its original copyright and attribution. Files modified by this migration use the neutral notice `Modified for JL-Mod Plus.` where an appropriate modification notice was not already present. The root `LICENSE` is unchanged.

The alias ordering and filter behavior follow the [Android `activity-alias` contract](https://developer.android.com/guide/topics/manifest/activity-alias-element): each target activity is declared before its alias, and the alias owns the legacy component name and launcher filter.
