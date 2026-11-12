# Memory Editor

The Memory Editor lets you investigate values used by a **running** MIDlet. It can search for candidates, narrow them down as the game changes, inspect values, and apply supported edits or freezes.

It works with values reachable through the managed Java ME runtime. It is not a general-purpose native memory scanner, and a value displayed on the screen is not guaranteed to exist as one editable number.

## When Memory Editor is unavailable

A MIDlet can run from a converted DEX without the JL-Mod Plus bytecode transforms required for managed-memory discovery. In that case, the editor explains why searches are unavailable; gameplay remains usable. Close the game, then reconvert or reinstall the MIDlet using JL-Mod Plus. The normal launch flow can automatically reconvert compatible retained JAR sources, when available. A temporary engine connection failure is a different condition and does not mean the MIDlet needs reconversion. Newly converted MIDlets track the installed DEX fingerprint to detect replacement by another emulator. Older installations without that fingerprint are only flagged when the DEX contains clear evidence of missing transforms; an inconclusive result is not treated as proof of incompatibility.

## Start with an observation

Open a MIDlet, then launch **Memory Editor** from its runtime controls when available. The tabs are **Search**, **List**, and **Inspector**. Inspector is always visible but becomes available when one inspectable result or List entry is selected; it can also be opened from a row's action menu. The List shows editable values regardless of whether their underlying reference is session-only or kept across launches. Wide workspaces show values and locations together, while narrower ones stack the details. When results span multiple pages, the bottom pager moves between result ranges alongside the **Peek** control.

Closing and reopening the bubble editor during the same running MIDlet returns to the last area. Inspector rechecks its last target rather than displaying an old memory snapshot; if the target is unavailable, the editor returns to its source tab. A new MIDlet session starts at Search.

A good search begins with something you can reproduce in the game: a counter that increases, health that falls, or a value that stays constant while other numbers change. The editor cannot tell you which candidate is “health” merely from the number alone.

### Search for a known value

Use **Search known values** when you can read the current number on screen.

Suppose a game shows **50** coins. Search for 50 using the appropriate value type. Return to the game, spend five coins, and use **Next scan** to narrow candidates to the new value **45**, or to the relevant changed/decreased condition.

If several candidates remain, repeat the observation. A game may maintain a displayed value, a cached copy, and a separate internal count. Matching a number once is not proof of identity.

### Search for an unknown value

Use **Search unknown values** when the number is hidden or encoded, or when you can only observe its direction of change.

Start the search, then make a controlled change in the game. **Next scan** can compare candidates using conditions such as **Changed**, **Unchanged**, **Increased**, or **Decreased**. Some comparisons support more specific differences.

An effective sequence alternates one game action with one scan. Avoid making several unrelated changes between scans: that increases the number of possible explanations for a match.

Use **New search** when you want to investigate a different variable. Mixing observations from separate tasks can eliminate the candidate you actually want.

## Value types and unexpected results

Games can represent numbers using different primitive types, scales, or encodings. A visible “100” might be stored as 100, 1000, a floating-point value, or a value calculated from several fields.

If an exact-value search finds nothing, inspect the selected type and try a search method that follows the visible behavior rather than assuming a particular representation. Repeating the same scan unchanged will not uncover a different representation.

The editor reports **candidates**, not confirmed game variables. Treat them as hypotheses until an edit produces the expected in-game effect.

## Edit, List, Freeze, and Inspector

Select Search results and choose **Bookmark**, or bookmark a primitive field/array
element from Inspector. The List contains one persistent Bookmark collection per
installed MIDlet. Freeze automatically bookmarks its target before activation.
No separate Watch, Keep or Stop keeping action is required.

Tap an available Bookmark to edit, or use its menu to Inspect, Freeze/Unfreeze,
Rename, Relink or Remove. A missing or incompatible target stays in the List
without being editable. A Bookmark is never removed automatically when a game
changes scene, an object is collected or a MIDlet restarts.

## Finding the target again

A rooted field/index locator can be resolved again during bounded foreground
polling. An untraced variable can still be bookmarked and frozen in the current
session, but after relaunch it needs an explicit **Relink**. From its List row
choose Relink, select the corresponding Search result, then confirm. The old
Freeze is stopped before the new association is published. Matching names,
values, or diagnostic addresses are never proof of identity.

A locator identifies a structural game slot, not necessarily the same character,
item or game state on the next launch. Recheck the current value before editing.
No Bookmark automatically restores a memory value or starts a previous Freeze.

## Storage and transfers

Bookmarks use a single bounded AtomicFile catalog. Old Saved Variables schema-2/3
documents are read without data loss and upgraded on an authorized write to
schema 4. The file retains its original name for backward compatibility. Each
record contains an ID, label and optional rooted locator. Session candidate IDs,
guest object references and Freeze states are never saved. Invalid catalogs are
not overwritten.

Library bundle export/import exposes the existing component as **Bookmarks**.
Bookmark-only imports merge verified locators with local records taking
precedence. Untraced records carry stable IDs and may require relinking on a
different device. A configuration restore uses the existing recoverable
transaction and does not auto-enable Freeze.

## Why candidates sometimes disappear

A candidate belongs to one MIDlet runtime. Java objects may be collected or
replaced, and a new search can invalidate result IDs. The editor refuses to
retarget such an ID by guessing. If a Bookmark cannot verify its current target,
it remains listed and read-only until resolution or explicit relink.

Editing and Freeze may affect gameplay or crash an incompatible MIDlet. Try one
candidate at a time and verify its effect. Undo is not a substitute for a game
save.

**Boolean** can be explicitly selected for true/false field and array scanning. It supports Equal/Not equal known searches and Changed/Unchanged refinement. Boolean is deliberately excluded from numeric **Auto** scans to avoid excessive flag matches.

## If a search or edit doesn't work

**No candidates found:** verify the value type and consider an unknown-value search. The game may store the value outside the editor's supported memory domain.

**Too many candidates:** change the game state in one controlled way and refine. Avoid applying batch edits just to see what happens.

**A value immediately changes back:** the game may recompute it. Freeze can help in some cases but may cause other behavior to break.

**Watch stopped updating:** the original object may no longer be valid. Start a fresh search rather than assuming an old candidate points to the same game variable.

**The editor isn't available:** some runtime paths cannot expose the supported managed-memory engine. A successful game launch does not imply every value can be inspected.

**Technical reference:** [Managed Memory Editor candidate identity](../memory-editor-identity.md)
