# Library and metadata

The Library is more than a list of installed JARs. It keeps your presentation choices—such as titles, icons, Favorites, and Collections—separate from the files needed to run each MIDlet. That separation is intentional: organizing a game should not silently modify its original package or saved progress.

## List or grid?

Open the Library's display options to switch between **List** and **Grid**.

- **List** prioritizes names and supporting information. Enable **Show MIDlet descriptions** if you prefer to see descriptive text without opening an entry.
- **Grid** prioritizes artwork. You can change spacing and hide titles to give cover-style icons more room.
- The **icon aspect ratio** can be **1:1** (square) or **3:4** (portrait). This changes how Library artwork is presented, **not** the game's screen resolution.
- Grid spacing is a presentation choice. It does not change the icon file that a MIDlet contains.

A game may have only a square icon, or no suitable icon at all. The Library retains a usable presentation rather than requiring every game to provide portrait artwork.

## A separate 3:4 icon: when it matters

A square icon does not always look good in a portrait tile, particularly if it includes a border or a character near the edge. **Edit metadata** lets you supply an optional 3:4 image for that case.

The **Main** image remains the standard icon. When you choose the Library's 3:4 ratio, a MIDlet with a dedicated portrait icon uses that image; other MIDlets continue using their main icons. You do not need to create two icons for every game.

Image imports are treated as Library artwork. A supported SVG source is normalized into the stored icon format, unlike an SVG screen skin, whose source file is kept. See [Appearance and controls](customization.md) for that distinction.

## Edit metadata without changing the MIDlet

Open an installed MIDlet's actions and choose **Edit metadata**. You can edit:

- **Name** and **Vendor:** how the entry is identified in the Library.
- **Default description:** text used when a more specific description is not available.
- **Main and 3:4 icons:** the artwork shown for each supported Library presentation.
- **Other languages:** optional description overrides for particular locales.

The editor works with a draft. Review the result and choose **Save** to apply changes; Back can prompt you to discard an unfinished edit. **Reset to original** restores an applicable value from the installed source information, instead of requiring you to remember its old spelling or artwork.

These are Library overrides. They do not patch `MIDlet-Name`, `MIDlet-Vendor`, or other entries inside the original JAR or JAD.

### Default and localized descriptions

Some Java ME descriptors include `MIDlet-Description` and localized variants such as `MIDlet-Description-en`. JL-Mod Plus also lets you add or replace descriptions for specific languages in the Library, following that MIDP 3.0-inspired convention.

The principle is simple: use the most appropriate matching description; otherwise, use the default. For example, a specific language-and-region entry can take precedence over the more general language entry. A missing translation must not leave an otherwise described game without useful text.

Under **Other languages**, you can find an existing language, add a supported locale code, edit its text, or reset an override. Removing your edit restores the source description if one exists. These changes **do not translate the game** and are not a claim of complete MIDP 3.0 support.

If the displayed text seems wrong, check which locale is being used, whether a matching description exists, and which fallback text has been saved. Do not assume the MIDlet itself has changed.

## Collections, Favorites, and Recent

**Collections** are user-defined groups. You can create a Collection, give it a name, and add installed games through the Collection view or the games' Library actions. You can also select multiple games for supported bulk actions.

A MIDlet can be organized without moving its installation files. **Removing a game from a Collection** removes that membership, not the installed MIDlet. **Uninstall** is a separate action and may delete settings and game save data. Check which action you selected before confirming.

**Favorites** is useful for games you want to reach quickly; **Recent** helps you return to MIDlets you have launched. They serve different purposes from custom Collections and do not require duplicating installations.

## A few common misunderstandings

**“My 3:4 icon is not displayed.”** Select 3:4 in the Library's appearance settings. The additional portrait icon is not used while the Library is set to 1:1.

**“Changing the Library title did not rename the game internally.”** This is expected. Presentation metadata and the contents of the MIDlet are separate.

**“My edited description disappeared in another language.”** Check **Other languages** and the default description. Localized overrides apply only where their locale matches.

**“A Collection vanished after changing the work directory.”** The Library catalogue is associated with the chosen emulator work directory. Verify which directory is active before attempting repairs. Avoid deleting catalog files to rebuild the Library; some user-owned state cannot be reconstructed from installed packages.

**Technical reference:** [Library data and migrations](../library-data-contract.md) · [Preset and installed identity](../preset-config-contract.md) · [Static SVG artwork](../svg-artwork.md)
