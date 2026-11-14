# Memory Recipes v1 and v2 (experimental)

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
   script first stops its existing Freeze; if the stop cannot be confirmed,
   the catalog change is rejected. If a later storage write fails, the old
   script remains saved but its Freeze may already be stopped. A draft with
   unsaved changes prompts before being discarded.
   The top X **minimizes** the editor without discarding the script page,
   selected parameters, or an unsaved JSON draft. Reopen the editor during
   the same MIDlet session to continue; Back from the editor still asks
   before discarding a changed draft.

Scripts are stored in a separate, atomic `configs/<workdir>/memory-recipes.json`
file in the installed-game configuration directory, using the same installed
identity validation as the Bookmark catalog. There is no fixed script-count limit;
the current single-file catalog is capped at 256 KiB to keep full-list Binder
responses bounded. Individual documents can be up to 64 KiB. When the catalog
is full, existing scripts remain unchanged; remove unneeded scripts to free space.
No runtime Java object references or session Freeze handles are saved. Corrupt or unsupported
catalog versions are not overwritten. Bookmarks and Search histories
continue to use their own existing contracts. The script catalog is currently
local-only; do not assume backup/export of scripts is implemented.

The existing **Automation** action on a verified Saved variable remains a
simple generator: it builds a primitive `set` recipe, copies its JSON and
can run the edit. Advanced authors can paste that JSON into the Scripts editor
to retain a reusable document. Scripts never auto-run on application startup.

The optional top-level `"description"` field is available in both v1 and v2 recipes
for instructions, compatibility notes, and warnings. It is plain text (not
executable code or Markdown), supports multiple lines, and accepts up to 2,048
characters. The Scripts list shows a two-line preview and the detail page
shows the full description. A recipe without a description behaves as before.
Descriptions do not affect target resolution, validation of memory values, or
execution. The JSON document is persisted unchanged with the recipe.

A Memory Recipe is **not** a Lua program. JSON is a declarative format with
limited, built-in operators. Unknown functions, Java expressions, JavaScript
or arbitrary reflection calls are not evaluated. When asking an AI agent to
generate one, provide the original JAR and this guide; request JVM-internal
class names and exact field descriptors, and check its analysis before running.

Examples:

- [Furby Island: packed inventory slot](../examples/memory-recipes/furby-inventory.json)
- [Kingdoms & Lords: encoded coins](../examples/memory-recipes/kingdoms-coins.json)

## Build scripts from Bookmarks

In **Memory Editor → Scripts → Add script**, choose **Freeze Values**, **Value
Editor**, **Quick Actions**, or **JSON Editor**.

The visual builder supports multiple verified Bookmarks in one script:

- **Freeze Values** stores a fixed target value for each Bookmark. Enable or
  disable the resulting script using its Freeze switch. Disabling does not undo
  the last value written.
- **Value Editor** creates editable numeric fields. Opening the saved script
  reads the current values; **Apply changes** checks the read snapshot again
  before writing.
- **Quick Actions** offers **Set**, **Add**, and **Subtract** for each Bookmark.
  Press **Run** whenever the changes are needed. A Run does not keep Freeze
  active; Subtract compiles to a signed addition.

The builder uses the Bookmark's verified root-relative locator, not its current
value, session candidate ID, or label as an execution target. A Bookmark without
a locator must be relinked in the List tab before it can be added. Once generated,
the script contains its own locator snapshot: renaming or relinking the Bookmark
does not silently change an existing script's target.

**View JSON** shows the generated v2 document; **Edit JSON** opens the regular
JSON editor. **Export JSON** saves a file using the Android document picker.
**Save** adds the script to the current MIDlet's catalog without running it.
The builder retains its unsaved draft while the editor is minimized in the same
MIDlet session. Existing and advanced JSON recipes remain editable in JSON,
without an unreliable automatic conversion back to the visual builder.

Existing scripts produced by the builder can be reopened visually through **Edit**
while their original Bookmark locators are still available and unambiguous.
Unsupported or advanced documents continue in **JSON Editor**, without dropping
unknown fields. Value Editor fields can optionally set minimum and maximum input
bounds; leaving them empty uses the primitive type's full numeric range.
Freeze Values and Quick Actions start with no numeric amount, rather than
silently treating an unread Bookmark as zero.

The builder deliberately covers ordinary primitive operations. Bitfields,
encoded values, dynamic array selectors, and mirrored data layouts still
require the JSON editor.

## v1 document

```json
{
  "formatVersion": 1,
  "id": "example.recipe",
  "name": "Set health",
  "description": "Use only after loading a save.\\nAvoid applying this during combat.",
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
- At most 64 primitive writes per execution, 64 numeric input declarations
  and 64 KiB of UTF-8 document per request. Recipe Freezes share the existing
  32-slot global Freeze budget with ordinary Freeze. Several active Freezes
  can increase CPU usage; stop those you no longer need.
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

## Typed scripts (v2)

Recipe v2 retains the same bounded memory engine, paths, and codecs as v1.
Set `"formatVersion":2` and one mode: `"mode":"toggle"`,
`"mode":"form"`, or `"mode":"oneShot"`. Existing v1 scripts are not migrated
and keep the original Run/optional Freeze interface.

- **Toggle**: switch On executes the recipe with Freeze, Off stops it without
  reverting guest memory. The switch reflects the active Freeze, not a stored
  preference. All input declarations need defaults because a switch has no
  editable parameters. Toggle is off after the MIDlet session ends.
- **Form**: `valueInput` identifies editable numeric fields; `indexInput`
  identifies selectors such as a character slot. Fields can have optional
  human-readable input `label` metadata. The form automatically reads current
  values when first opened, and again when a selector changes. Refresh reads
  updated actual values without discarding proposed edits; changing a selector
  loads the newly selected target values. Apply requires the last successfully read, consistent
  snapshot and refuses to write if it changed since the read. Refresh also issues a
session-local ticket tied to weak references to the exact target owners;
Apply rejects replaced objects even if their numeric values still match.
Only the latest form read ticket remains valid in the target process.
- **One-shot**: Run applies the actions once and never starts Freeze.
  `"operation":"add"` may be used on an action to add a signed integer to its
  **decoded logical value**. It works for `set`, `bitfield`, and `encoded32le`
  codecs. Each relative action must declare a logical `field` string such
  as `"coins"`. All copies of the same field must agree before any write;
  inconsistent mirrors, overflow, and out-of-range values reject the operation.

The runner preflights targets before writing but writes are not atomic against
other MIDlet threads; partial writes are explicitly reported. Form comparison
also happens in the target process, so a stale UI snapshot cannot silently
overwrite a changed value. In either mode, a new deliberate Refresh or Run is
required after a conflict. The recipe limits are 64 numeric inputs and 64
primitive writes, including individual bytes in encoded slices.

The runtime currently accepts `set` on signed integer primitives (and
boolean/char), guarded bitfields up to 32 bits, and int32 little-endian encoded
byte slices. Arbitrary cryptography, checksums, string/object reference writes,
container queries, dynamic searches, script loops and custom computations
are intentionally not supported. They require evidence-driven extensions,
not speculative general-purpose scripting.

The Memory Editor's Saved locators remain a separate, backward-compatible
contract. Importing a recipe does not implicitly save, Watch or Freeze its
target. Data-only recipes must be explicitly invoked; no auto-run at import.
