# Memory Recipes v1 (experimental)

Memory Recipes are **data**, not Lua, JavaScript, or executable Java bytecode. The app
performs a bounded set of typed guest-memory operations. Advanced recipes may be
authored from the **original Java ME JAR** without knowing a runtime candidate ID.
The editor's Search, History, Watch and Saved data are never used as implicit
recipe targets or changed by recipe execution.

The experimental runtime is present on the `feature/memory-recipes-engine` branch.
This is not a stable public API until Android runtime acceptance testing is complete.

## Authoring from a JAR

1. Inspect the MIDlet class in `META-INF/MANIFEST.MF`; place its JVM internal
   name (slashes, not dots) in `game.mainClass`.
2. Inspect field declarations and bytecode to find a **rooted** path from the
   active MIDlet instance or an *already initialized* guest static class.
   Write each field's exact declaring owner, name and JVM descriptor.
   Names such as `a` are insufficient: an obfuscated class can have
   multiple fields of that name with different descriptors.
3. Record any array indexes as zero-based indexes. Use `indexInput` to bind
   one of the declared numeric inputs at runtime.
4. Choose the smallest supported action. Prefer `bitfield` to changing a
   packed primitive wholesale. For multi-field representations, use multiple
   ordered actions that are **all resolved before the first write**.
5. Verify the actual gameplay and save/reload behavior. Static analysis can
   establish field structure and codec, but not necessarily the active
   object instance, or whether the game recomputes the state later.

## Trying scripts in the draft app

1. Open Memory Editor and select the **Scripts** tab.
2. Select **Add script**, paste a **Memory Recipe v1 JSON** document into the
   full editor (long-press Paste or use the dedicated **Paste** action).
3. Select **Save**. This stores the document for the **current installed MIDlet**
   only; saving never changes guest memory or automatically starts a Freeze.
4. Select the saved script to open its detail page. Enter any parameters
   declared under `inputs`, then press **Refresh** to read the current
   **decoded logical value** (e.g. item quantity or coins) without writing.
   It is a manual snapshot, not continuous polling. Change an input such as
   inventory slot, then press Refresh again. If encoded mirrors disagree,
   they are shown separately with an inconsistency warning.
5. Select **Run** to make a one-time edit, or explicitly enable **Freeze** before
   running to keep the target values locked by the existing Memory Editor
   scheduler. When a recipe Freeze is active, **Stop Freeze** is available.
6. Use **Edit** to modify the JSON and save again. Editing or deleting a saved
   script stops its existing Freeze so an older recipe cannot keep running
   invisibly. A draft with unsaved changes prompts before being discarded.
   The top X **minimizes** the editor without discarding the script page,
   selected parameters, or an unsaved JSON draft. Reopen the editor during
   the same MIDlet session to continue; Back from the editor still asks
   before discarding a changed draft.

Scripts are stored in a separate, atomic `configs/<workdir>/memory-recipes.json`
file in the installed-game configuration directory, using the same installed
identity validation as Saved Variables. The catalog holds at most 12 scripts
and 256 KiB; individual documents are limited to 24 KiB. No runtime Java
object references or session Freeze handles are saved. Corrupt or unsupported
catalog versions are not overwritten. Saved Variables and Search histories
continue to use their own existing contracts. The script catalog is currently
local-only; do not assume backup/export of scripts is implemented.

The existing **Automation** action on a verified Saved variable remains a
simple generator: it builds a primitive `set` recipe, copies its JSON and
can run the edit. Advanced authors can paste that JSON into the Scripts editor
to retain a reusable document. Scripts never auto-run on application startup.

A Memory Recipe is **not** a Lua program. JSON is a declarative format with
limited, built-in operators. Unknown functions, Java expressions, JavaScript
or arbitrary reflection calls are not evaluated. When asking an AI agent to
generate one, provide the original JAR and this guide; request JVM-internal
class names and exact field descriptors, and check its analysis before running.

Examples:

- [Furby Island: packed inventory slot](../examples/memory-recipes/furby-inventory.json)
- [Kingdoms & Lords: encoded coins](../examples/memory-recipes/kingdoms-coins.json)

## v1 document

```json
{
  "formatVersion": 1,
  "id": "example.recipe",
  "name": "Description for a person",
  "game": { "mainClass": "com/example/GameMIDlet" },
  "inputs": { "value": { "min": 0, "max": 999 } },
  "root": { "kind": "midlet", "class": "com/example/GameMIDlet" },
  "actions": [
    {
      "kind": "set",
      "path": [
        { "kind": "field", "owner": "com/example/GameMIDlet",
          "name": "health", "descriptor": "I" }
      ],
      "valueInput": "value"
    }
  ]
}
```

All fields named `owner`, `class`, `descriptor`, etc. use the **original
classfile's JVM spelling**, not the Android runtime's reflection spelling.
A conversion mapping may change certain types (e.g. `java.util.Timer`);
such classes are not guaranteed to be usable recipe targets.

Root kinds are `midlet`, `displayable`, and `initializedStatic`.
The latter **must** be published by the Managed Java discovery bridge; recipe
resolution never initializes a guest class just to inspect it. Paths are
limited to 24 steps and use:

- `field`: exact `owner`, `name`, `descriptor`; staticness is inferred
  and checked from the current owner.
- `index`: `descriptor` of the array and exactly one of `index` or
  `indexInput`. Intermediate indexes must point to nonnull objects.
- `slice`: array `descriptor`, `start`, `length` (1–4); terminal only.

An action uses exactly one `valueInput` (declared numeric input) or `value`
(integer constant). Valid operations are:

| Operation | Terminal | Semantics |
| --- | --- | --- |
| `set` | Integral primitive field/array element | Checked scalar write |
| `bitfield` | Integral primitive element | Replace `width` bits at `shift`, keeping the other bits |
| `encoded32le` | 4 consecutive `byte[]` elements | Write `constant - value` as signed little-endian int32 |

`bitfield` optionally accepts `nonZeroMask` (nonempty-state guard) and
`identityMask` (stable bits checked on every Freeze tick; must not overlap
with the edited bitfield). Both masks accept decimal or `0x`-prefixed strings.
`arrayLength` guards the expected array length. Integer
arguments and constants may use decimal or `0x`-prefixed strings. Input
ranges are inclusive. Numeric inputs across the service interface are passed
as decimal string values in the parameter Bundle.

## Execution and Freeze

- Refresh resolves and reads each requested target, decoding `set`,
  `bitfield`, and `encoded32le` into logical values without any mutation.
  Related values that disagree are shown as inconsistent rather than
  collapsed into a misleading single amount.
- Execute resolves **all** action targets and validates types, bounds,
  duplicate writes and guards before writing any. Each write is checked by
  readback. Races with the game's own threads remain possible; partial writes
  are reported explicitly rather than claiming an atomic transaction.
- Hold/Freeze is opt-in and uses the existing Memory Editor 750 ms scheduler,
  **without an extra thread**. While Freeze exists the engine remains started
  even after the Memory Editor Activity is minimized; it stops when all
  Freezes are removed or the MIDlet session ends. It holds target owners
  weakly and stops if they disappear, a path retargets, or an `identityMask`
  changes. Ordinary gameplay value changes and transient readback races do
  **not** automatically stop Freeze; another tick can retry.
  This is best-effort periodic correction, not atomic interception of the
  game's writes; a purchase/save may observe a changed value between ticks.
  Freezes do not reactivate automatically on a new MIDlet session.
- At most 32 primitive writes per execution, 4 held recipes per session,
  8 numeric input declarations and 24 KiB of UTF-8 document per request.
- No filesystem/network APIs, native memory accesses, arbitrary method calls,
  generic expression interpreter, or reflection access outside the active
  guest classloader are exposed.

`sourceJarSha256` in example metadata is informational in v1. The runtime
checks the MIDlet entry-class identity and exact class/field descriptors;
it **does not yet verify the installed source JAR checksum**. Do not describe
that metadata field as an enforced security check.

## Known limitations

The recipe runner does not inspect guest local variables, stack values, native
memory, RMS storage, or non-rooted instance graphs. A path that cannot be proven
from known roots is unavailable. The same slot can represent a different game
entity after level changes, even if its structure still matches; use gameplay
checks and avoid applying destructive writes automatically.

The runtime currently accepts `set` on signed integer primitives (and
boolean/char), guarded bitfields up to 32 bits, and int32 little-endian encoded
byte slices. Arbitrary cryptography, checksums, string/object reference writes,
container queries, dynamic searches, script loops and custom computations
are intentionally not supported. They require evidence-driven extensions,
not speculative general-purpose scripting.

The Memory Editor's Saved locators remain a separate, backward-compatible
contract. Importing a recipe does not implicitly save, Watch or Freeze its
target. Data-only recipes must be explicitly invoked; no auto-run at import.
