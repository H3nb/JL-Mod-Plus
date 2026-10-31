# Profiles

A profile is a reusable setup for MIDlets that share display, controls, or other configuration choices. It saves you from adjusting several games individually, but it also introduces an important choice: should a MIDlet continue following the shared profile, or keep its own settings?

## Reusable setups versus local changes

Open **Profiles** in the Library to create or manage reusable configurations. You can edit, rename, and delete supported profiles. A profile can also be chosen as the **default for new MIDlets**.

The built-in **JL-Mod Defaults** is the baseline configuration. If a previously selected default cannot be read or applied, the app can fall back to this built-in setup rather than inventing a new one.

Profiles are especially useful when several games need the same screen size, keypad arrangement, or control behavior. For games with unique needs, a local configuration may be more appropriate.

## What happens when you apply a profile?

The options are deliberately different:

| Option | Effect |
| --- | --- |
| **Entire profile** | Replaces settings and virtual-control layout, and can keep following profile updates |
| **MIDlet settings only** | Applies settings but keeps that MIDlet's existing layout |
| **Virtual controls layout only** | Applies the layout without replacing other MIDlet settings |

If you apply only one part, the result is a custom configuration. It is not equivalent to making the MIDlet fully follow a shared profile.

This matters when you edit a profile later. Linked MIDlets can receive subsequent updates, but games with independent configurations do not have to follow them. If one game appears unchanged, inspect its profile association before copying settings repeatedly.

## Updating or removing a profile

Changes to a shared profile are intended to affect the MIDlets still linked to it. Review an update with that scope in mind; a convenient edit for one game may also affect others.

Deleting a profile does not mean deleting every game that used it. Those MIDlets keep their current settings, but no longer receive future updates from the deleted profile.

Saved virtual-control layouts and reusable MIDlet settings appear in related management surfaces. A layout-only preset should not be mistaken for an entire application configuration.

## How skins fit in

A profile may select a **virtual-control skin set**, but the set's images are stored as shared assets. Editing the set changes its artwork for every MIDlet that uses it at the next launch. Applying a layout and selecting a skin are therefore related but distinct operations.

For example, you can have two MIDlets with the same button positions but different artwork, or two MIDlets that share a skin while using different touch layouts.

See [Appearance and controls](customization.md) for that distinction.

## Troubleshooting

**A game did not change after editing a profile.** Check whether it still follows that profile or was changed to a local configuration.

**A layout changed but the game's other options did not.** The profile may have been applied as *Virtual controls layout only*.

**A profile is unavailable or unreadable.** Check the selected work directory and displayed warning before trying to repair or recreate data. Avoid deleting the emulator's config files as a first response.

**Technical reference:** [Preset, configuration, and installed-identity contracts](../preset-config-contract.md)
