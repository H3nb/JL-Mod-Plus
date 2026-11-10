# Memory Editor

The Memory Editor lets you investigate values used by a **running** MIDlet. It can search for candidates, narrow them down as the game changes, inspect values, and apply supported edits or freezes.

It works with values reachable through the managed Java ME runtime. It is not a general-purpose native memory scanner, and a value displayed on the screen is not guaranteed to exist as one editable number.

## When Memory Editor is unavailable

A MIDlet can run from a converted DEX without the JL-Mod Plus bytecode transforms required for managed-memory discovery. In that case, the editor explains why searches are unavailable; gameplay remains usable. Close the game, then reconvert or reinstall the MIDlet using JL-Mod Plus. The normal launch flow can automatically reconvert compatible retained JAR sources, when available. A temporary engine connection failure is a different condition and does not mean the MIDlet needs reconversion. Newly converted MIDlets track the installed DEX fingerprint to detect replacement by another emulator. Older installations without that fingerprint are only flagged when the DEX contains clear evidence of missing transforms; an inconclusive result is not treated as proof of incompatibility.

## Start with an observation

Open a MIDlet, then launch **Memory Editor** from its runtime controls when available. The primary areas are **Search** and **List**. **Inspector** opens as a contextual detail from a result or a List entry. The List shows editable values regardless of whether their underlying reference is session-only or kept across launches. Wide workspaces show values and locations together, while narrower ones stack the details. When results span multiple pages, the bottom pager moves between result ranges alongside the **Peek** control.

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

Select a result and choose **Add to List** to track it while playing. Tap a List row to edit its value, or its lock icon to turn Freeze on or off. The row menu provides **Inspect**, **Rename**, **Keep for next launch**, and **Remove**. Long-press a List row to select multiple entries. Inspector is a detail view with a Back action, not a third tab.

Editing applies a value to the selected supported candidate or candidates. Batch actions can affect several values at once; verify the selection before using them. **Freeze** attempts to keep a value at the chosen level as the game runs. It may be inappropriate when the game expects to update that value itself.

Where available, **Undo** and revert actions help reverse recent edits. They do not replace the game's save system or guarantee recovery from arbitrary bad writes.

A practical approach is to change **one candidate at a time**, check the game, then decide whether to watch or freeze it. If the game crashes or behaves strangely, restart it and repeat the search more carefully.

## Keeping values across game launches

List entries are session-only unless you explicitly choose **Keep for next launch** from their row menu. Keep uses the same verified rooted field or array locator as the existing Saved Variables mechanism. Untraced, ambiguous or unsupported references cannot be kept safely; the current List entry remains available for the active session.

A kept entry appears in the same List, marked with a small save icon. On the next launch of the same installed MIDlet, opening List resolves its structural path and displays the current value without another search. Keep does **not** store or restore a previous game value, enable Freeze, or guarantee that a reused party/array slot still represents the same semantic character.

**Stop keeping** converts an available kept entry to a session-only List entry; it preserves an already-linked Watch. A kept target without an active Watch must be currently resolvable before it can be converted safely. **Remove** removes the List entry; for a kept entry, this deletes the definition and also stops its linked session Watch/Freeze. Storage and Watch operations have independent owners, so failures may leave a visible entry and are reported rather than silently discarded.

Inspector can open from a kept entry without repeating Search. Its verified sibling editing, Watch insertion, capture, and optional Freeze-after-edit remain available. An open kept edit pins its original target: owner replacement rejects the write instead of silently rebinding to another object. A **Target updated** hint after passive resolution denotes a structurally matching reference, not necessarily the same game entity.

Kept definitions remain portable typed paths owned by the installed game. The selective bundle exporter includes Saved Variables as a separate underlying component for compatibility, and the existing validated import/restore semantics are unchanged. Unsupported containers and fabricated roots are still not valid persistent locators.

## Why candidates sometimes disappear

A managed-memory candidate identifies a value within the current MIDlet execution. Objects can be collected, arrays can be replaced, and the runtime can restart. The editor deliberately does **not** guess that another object with the same type, field, or value is the one you previously selected.

As a result, a watch entry may become unavailable after a level change, game transition, or relaunch. This is safer than silently editing a different object.

Candidate identities and watches are **session-bound**. Saved definitions separately preserve checked structural locations; they are not save states and do not automatically restore values.

**Boolean** can be explicitly selected for true/false field and array scanning. It supports Equal/Not equal known searches and Changed/Unchanged refinement. Boolean is deliberately excluded from numeric **Auto** scans to avoid excessive flag matches.

## If a search or edit doesn't work

**No candidates found:** verify the value type and consider an unknown-value search. The game may store the value outside the editor's supported memory domain.

**Too many candidates:** change the game state in one controlled way and refine. Avoid applying batch edits just to see what happens.

**A value immediately changes back:** the game may recompute it. Freeze can help in some cases but may cause other behavior to break.

**Watch stopped updating:** the original object may no longer be valid. Start a fresh search rather than assuming an old candidate points to the same game variable.

**The editor isn't available:** some runtime paths cannot expose the supported managed-memory engine. A successful game launch does not imply every value can be inspected.

**Technical reference:** [Managed Memory Editor candidate identity](../memory-editor-identity.md)
