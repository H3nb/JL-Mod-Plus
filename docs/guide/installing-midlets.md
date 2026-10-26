# Installing MIDlets

JL-Mod Plus installs Java ME applications from JAR, JAD, and KJX files. It can also inspect ZIP files and folders containing multiple games.

The installer looks at **how many applications it discovers**, not just how many files you select. One selected file may contain several games; a JAD and its matching JAR are normally one game.

## Choose a source

In the Library, use the **Install** action and choose:

- **Files** to select one or more JAR, JAD, KJX, or ZIP files.
- **Folder** to scan a directory for supported files, including those in its subfolders.

For a single ordinary game, select its JAR or JAD and review the install confirmation. When discovery finds one installable MIDlet, JL-Mod Plus shows a focused **single-install** review rather than batch checkboxes. When it finds several, the **batch-install** review lets you choose which ones to process.

A game's filename does not determine its title in the Library. The installer reads the available MIDlet metadata, then prepares the application for JL-Mod Plus.

### JAR, JAD, and KJX: what's the difference?

**JAR** contains the application's classes and resources and usually includes its MIDlet manifest. If you only have a JAR, install that.

**JAD** is a small descriptor containing application information and a reference to the JAR. Keep the JAD and its companion JAR together when available. If they are selected or found together, the installer can pair them and avoid treating the same application as two installs. If the JAD references a missing JAR, the batch review reports the problem instead of silently installing an unrelated file.

**KJX** is another supported Java ME package format. It can be selected with the same install flow; the package still needs to contain a valid application.

Do not rename an arbitrary game archive to `.jar` just to make it appear installable. Use the ZIP handling described below.

## What a ZIP means

A ZIP may represent **one game** or **a collection of games**. These are different cases.

| ZIP contents | Installer behavior |
| --- | --- |
| One MIDlet with `META-INF/MANIFEST.MF` at the ZIP root | Treated as one MIDlet package, including a JAR renamed to `.zip` |
| Several JAR, JAD, or KJX files | Extracts supported candidates into temporary staging and reviews the discovered MIDlets |
| Folders containing games, including ZIPs within the archive | Scans supported entries and nested ZIPs, within safety limits |
| Ordinary ZIP with no installable Java ME files | Reports no supported applications found |

This distinction matters for older game collections. A file named `Games.zip` may contain many independent games, while `Bounce.zip` may itself be the original MIDlet archive. The installer examines the content rather than treating every ZIP alike.

The archive scanner supports nesting up to **two ZIP levels**, limits the number and expanded size of entries, and skips unsafe paths. It does not extract the collection beside your original ZIP. Files needed for the install are staged in temporary application storage. Broken or unsupported entries can be skipped while other games are still discovered; review any warnings rather than assuming every archived file was installed.

If a collection is too large or contains complicated nested archives, unpack it yourself into a local folder and use **Install → Folder**.

## Single install: one game, one decision

A single discovered application shows its name, vendor, version, and how it relates to any installed copy.

- **New:** installs an application not currently in the Library.
- **Update:** the package has a newer version of an existing app.
- **Already installed / reinstall:** you can explicitly confirm reinstalling the existing version.
- **Older version:** a downgrade is presented for confirmation; it is not a routine update.
- **Ambiguous match:** if several installed entries match, a separate-copy option avoids guessing which existing installation to replace.
- **JAD/JAR mismatch:** if the descriptor and archive identify different applications, inspect the files before proceeding. A JAR-only review may be offered when a separate JAR is available.

Read the confirmation before replacing anything. Updates and reinstalls are designed to retain existing settings and save data. However, **replacing a MIDlet that is currently running stops it first**. Progress that the game has not saved can be lost.

## Batch install: review before execution

Batch install is intended for several files, entire folders, and ZIP collections. After discovery, it shows each candidate's installation status so you can decide what to install.

**Recommended** selects new applications and ordinary updates. It does **not** silently select every reinstall, downgrade, duplicate, or uncertain match. Use **All**, **Selected**, and **Review** to inspect the list; select exceptional cases deliberately. **Clear selection** removes the pending choices.

A JAD and its companion JAR are normally grouped as one install item. The scanner also detects duplicate sources and competing versions or variants within the same batch. These are reasons to review a candidate, not invitations to install every copy.

Once you confirm, JL-Mod Plus processes selected items and reports results such as *Installed*, *Updated*, *Skipped*, or *Failed*. One recoverable source failure does not necessarily stop the other selected games. If the batch cannot safely continue, it leaves the remaining items unprocessed for review.

**Cancel** requests a stop at the next safe point. The installer may finish the current application first rather than interrupting a file replacement halfway through. Check the final results to see what actually completed.

### Why some JADs are skipped in a batch

Batch discovery expects the JAR referenced by a JAD to be **available locally**. It does not download a remote JAR linked through `http` or `https` while planning a batch. If the JAD points to a remote URL, obtain the matching JAR and retry with the local files. The individual installer has a download stage for applicable sources; do not assume every batch rule applies to an externally opened JAD.

## JL-Mod Plus app bundles are different from game collections

The Library also has **Export app bundle** and **Import app bundle** actions. An app bundle is a backup/transfer format that can carry a retained JAR **along with JL-Mod Plus settings and save data**. A universal bundle may contain more than one installed MIDlet.

Although these bundles can be ZIP archives, they are **not** interchangeable with an ordinary `Games.zip` collection containing loose JAR/JAD files. Use **Import app bundle** for a file exported by JL-Mod Plus rather than treating it as a folder of independent MIDlet packages.

Importing a bundle for an app that already exists can replace the corresponding existing settings or save data from the bundle. Read the import confirmation and keep a backup of anything you need before restoring. If application installation succeeds but restoring the bundled data fails, follow the specific retry or recovery prompt rather than assuming the entire import was undone.

## If an installation fails

**No supported apps found.** Confirm the ZIP actually contains MIDlet packages, rather than game data or a different platform's files. Try selecting a JAR directly.

**A JAD reports a missing JAR.** Keep the JAR beside the JAD with the referenced name where possible. Do not substitute a different version without checking metadata.

**Only some games were installed.** Open the batch results. Warnings about unreadable archives or skipped entries mean discovery was incomplete; a failed conversion is a different problem.

**The source changed after review.** Select the files again. The installer checks that sources have not changed between inspection and execution.

**The Library needs recovery or refresh.** Follow the on-screen recovery action before attempting the install again. Avoid clearing the working directory or deleting saves as a first step.

**A game installs but does not run.** Successful conversion means the app was installed, not that every Java ME API used by that game is compatible. Capture the launch behavior and consult [Troubleshooting](troubleshooting.md).

### Also useful

[Getting started](getting-started.md) covers first launch. [Library and metadata](library.md) covers organizing installed apps. For damaged or unsupported games, see [Troubleshooting](troubleshooting.md).
