# Memory Editor

The Memory Editor lets you investigate values used by a **running** MIDlet. It can search for candidates, narrow them down as the game changes, inspect values, and apply supported edits or freezes.

It works with values reachable through the managed Java ME runtime. It is not a general-purpose native memory scanner, and a value displayed on the screen is not guaranteed to exist as one editable number.

## When Memory Editor is unavailable

A MIDlet can run from a converted DEX without the JL-Mod Plus bytecode transforms required for managed-memory discovery. In that case, the editor explains why searches are unavailable; gameplay remains usable. Close the game, then reconvert or reinstall the MIDlet using JL-Mod Plus. The normal launch flow can automatically reconvert compatible retained JAR sources, when available. A temporary engine connection failure is a different condition and does not mean the MIDlet needs reconversion. Newly converted MIDlets track the installed DEX fingerprint to detect replacement by another emulator. Older installations without that fingerprint are only flagged when the DEX contains clear evidence of missing transforms; an inconclusive result is not treated as proof of incompatibility.

## Start with an observation

Open a MIDlet, then launch **Memory Editor** from its runtime controls when available. The main areas are **Search**, **Watch**, **Saved**, and **Inspector**. Wide workspaces show values and locations together, while narrower ones stack the details. When results span multiple pages, the bottom pager moves between result ranges alongside the **Peek** control.

Closing and reopening the bubble editor during the same running MIDlet returns to the last tab. Inspector rechecks its last target rather than displaying an old memory snapshot; if the target is unavailable, the editor returns to its source tab. A new MIDlet session starts at Search.

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

## Edit, Watch, Freeze, and Inspector

Select a candidate to open **Inspector** for additional information about the current value and its runtime context. **Watch** keeps selected candidates visible so you can observe how they change while playing.

Editing applies a value to the selected supported candidate or candidates. Batch actions can affect several values at once; verify the selection before using them. **Freeze** attempts to keep a value at the chosen level as the game runs. It may be inappropriate when the game expects to update that value itself.

Where available, **Undo** and revert actions help reverse recent edits. They do not replace the game's save system or guarantee recovery from arbitrary bad writes.

A practical approach is to change **one candidate at a time**, check the game, then decide whether to watch or freeze it. If the game crashes or behaves strangely, restart it and repeat the search more carefully.

## Saved Variables across sessions

After identifying a primitive Search candidate, select exactly one result and choose **Save variable**. Give the definition a meaningful name. Saving requires a bounded, currently verified path through an initialized static field, the MIDlet root, or the current Displayable root. Unsupported, ambiguous or untraced locations cannot be saved safely.

The **Saved** tab lists definitions independently from Watch. On a later launch of the same installed MIDlet, open Saved to resolve a recorded structural path and read the latest value **without running Search again**. Select Saved entries with the row checkboxes (or Select all) and use the contextual toolbar. **Edit** and **Inspector** require one resolved selection; **Rename** accepts one selection even when the game is offline, and **Delete** supports multiple definitions in one confirmed operation. While Saved remains open, unresolved or changed paths are retried automatically. Editing is manual; opening Saved never freezes, restores or writes a game value.

Inspector can open from a resolved Saved entry without repeating Search. Tap an editable primitive **Inspector row** to open the same keypad editor used by Search, including an optional **Freeze after edit** checkbox. The three-dot menu for each Inspector field offers **Add to Watch** and **Save variable**. When Inspector originates from Saved, editing, Watch insertion, and Freeze all check the pinned reference chain and the displayed snapshot value; stale or replaced owners are rejected. Save can create a separately named definition in Search, Watch, or Saved Inspector contexts. Only a verified rooted path can be saved.

The **Freeze** checkbox inside the Saved value editor explicitly creates or updates a Watch entry and starts its periodic lock at the edited value. No separate Saved toolbar Freeze action is needed. Freeze from Search likewise adds the target to Watch. A Watch entry originating from Saved pauses its Freeze if an owner or intermediate reference changes. Only enabling Freeze explicitly again after a new Saved binding moves it to the current target. Deleting a Saved definition releases its bookmark and runtime binding, but does **not** remove an existing Watch entry or stop its independently managed Freeze; use the Watch tab to manage those entries.

While the Saved tab is active, a changed reference can resolve to the current structurally matching target. A **Target updated** hint appears briefly after rebinding; it does not guarantee the same semantic character, particularly for indexed party arrays. A Saved edit that was already open remains pinned to the previous owner and rejects writing if that owner changes; reopen the editor to inspect the new target. **Not ready** means the root or target is not yet available, **Incompatible** means the locator cannot be verified, and **Failed** indicates another error.

Definitions belong to the installed game and are kept as portable typed paths, not installation addresses. The experimental `saved-variables-prototype.json` format is not migrated. The selective bundle exporter includes **Saved Variables** as a separate component. Importing a Saved-only bundle into an existing game adds new targets without removing local bookmarks; duplicates are skipped by locator. Importing a bundle with both Configuration and Saved Variables restores the Saved collection as part of the backup. Both flows validate the imported data before replacing installed state.

Supported paths include primitive static and rooted instance fields, numeric primitive array elements reached through trusted field or object-array paths, and multidimensional primitive array indexing. Untraced containers and arbitrary graph recovery remain unsupported. Opening Saved never restores values or activates Freeze automatically.

## Why candidates sometimes disappear

A managed-memory candidate identifies a value within the current MIDlet execution. Objects can be collected, arrays can be replaced, and the runtime can restart. The editor deliberately does **not** guess that another object with the same type, field, or value is the one you previously selected.

As a result, a watch entry may become unavailable after a level change, game transition, or relaunch. This is safer than silently editing a different object.

Candidate identities and watches are **session-bound**. Saved definitions separately preserve checked structural locations; they are not save states and do not automatically restore values.

## If a search or edit doesn't work

**No candidates found:** verify the value type and consider an unknown-value search. The game may store the value outside the editor's supported memory domain.

**Too many candidates:** change the game state in one controlled way and refine. Avoid applying batch edits just to see what happens.

**A value immediately changes back:** the game may recompute it. Freeze can help in some cases but may cause other behavior to break.

**Watch stopped updating:** the original object may no longer be valid. Start a fresh search rather than assuming an old candidate points to the same game variable.

**The editor isn't available:** some runtime paths cannot expose the supported managed-memory engine. A successful game launch does not imply every value can be inspected.

**Technical reference:** [Managed Memory Editor candidate identity](../memory-editor-identity.md)
