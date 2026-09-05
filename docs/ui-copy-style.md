# UI copy, typography, and interaction style

JL-Mod Plus uses a hierarchy-aware capitalization policy. One casing rule is
not applied to every piece of UI copy.

This is a product style decision, not a claim that Material 3 requires title
case everywhere. Current Android guidance recommends sentence case for button
labels, while Android Auto guidance permits either sentence case or title case
when the choice is applied consistently. We choose Capitalize Each Word for
titles, labels, and compact controls because it matches the product's visual language and must remain
consistent across the library, profiles, and installer surfaces.

## Rules

- Use Capitalize Each Word for product/screen/dialog titles, section titles,
  header titles, option names, and field titles/names: `JL-Mod Plus`,
  `MIDlet Installer`, `Screen Orientation`, `Screen Size`, and `Profile Name`.
- Use Capitalize Each Word for buttons, menu items, toolbar actions, and other
  compact controls: `Install`, `Start`, `Cancel`, `Try Again`, and `Save Profile`.
- Keep descriptions, explanations, statuses, hints, and helper text in sentence
  case. Capitalization follows the text's role, not its length or wording:

  | Context | Example |
  | --- | --- |
  | Section/header title | `Application Information` |
  | Field title | `Profile Name` |
  | Field placeholder | `Enter a profile name` |
  | Option/checkbox name | `Save Screen Parameters On Exit` |
  | Description beneath that option | `Save the current screen parameters when the game closes.` |
  | Progress status while reading a file | `Loading information…` |
  | Action button | `Save Profile` |

  If a phrase is used as an option name, it uses Capitalize Each Word even when
  it reads like an instruction. A progress status is not a section title merely
  because it is prominent. Review the actual placement and semantics.
  For example, `Enter Profile Name` is valid as a dialog title, while
  `Enter a profile name` is a field hint. Prefer `Profile Name` for the field's
  persistent label. Here, Capitalize Each Word includes short words such as
  `On`, `Of`, and `The`; it is not editorial title case with lowercase exceptions.
- Use normal sentence case for body and confirmation messages. Do not use ALL CAPS for ordinary rendered copy.
- Resource keys such as `START_CMD` and `CANCEL_CMD` are legacy identifiers and do not define rendered capitalization.
- Write full messages as normal sentences: capitalize the first word, use ordinary punctuation, and start a new sentence after a newline with a capital letter.
- Preserve proper nouns, product names, and technical abbreviations: `JL-Mod Plus`, `J2ME`, `MIDlet`, `JAR`, `JAD`, `KJX`, `GitHub`, `Android`, `Material 3`, and `H3NB`.
- Apply the same role-based rule to translated app-owned labels, retaining
  correct spelling, diacritics, acronyms, and technical notation. Descriptions
  remain sentence case even when they are placed beside a title-cased option.
- Store the intended capitalization in resources; do not title-case arbitrary
  content at runtime. Game names, user-created profile/collection names, source
  metadata, and identifiers retain their supplied form.
- Keep lowercase where it is technically required or grammatically embedded, such as units (`ms`), identifiers, URLs, or a word that intentionally continues a sentence.

## Typography

Compose surfaces use the Material 3 type scale instead of arbitrary `sp`
values. The following mapping is the review baseline:

- Top app bar titles use `titleLarge`/the component default.
- Screen headings may use `headlineSmall`; popup/dialog titles use `titleLarge`.
- Popup body text uses `bodyMedium`; supporting metadata may use `bodySmall`
  with `onSurfaceVariant`. Actions use `labelLarge` or their Material component
  default. Keep the same role consistent across installer, Library, profiles,
  config, and app-owned runtime menus.
- Primary list and card titles use `titleMedium` or `titleSmall`; supporting
  metadata uses `bodyMedium`/`bodySmall` with `onSurfaceVariant`.
- Field labels, units, and compact secondary annotations use `labelMedium` or
  `labelSmall`.
- `fontScale`, long translations, and landscape width must be checked before
  introducing a custom size. A custom `sp` value needs a component-specific
  reason and a screenshot/regression case.
- Do not shrink text, reduce system font scaling, or change the typography
  hierarchy merely because the window is landscape or short. Reflow and scroll
  the content instead. Input fields and interactive lists retain the Material
  component defaults and accessible touch targets.
- These rules apply to app-owned UI, not the fonts or layout emulated for a
  Java ME application.

Colors follow the same semantic rule: surfaces, text, controls, and icons use
`MaterialTheme.colorScheme`. Literal colors are allowed only for user-selected
content (for example, the color-picker preview), for fixed color-space
gradients (white/black HSV endpoints), or when `Color.Transparent` is needed
to let a component reveal its already-themed parent surface.

## Popup layout and overflow

### Text alignment

- Use start alignment for descriptions, explanations, instructions, helper text,
  and multi-line error messages. In Compose this is `TextAlign.Start`: left for
  English/Indonesian and right for RTL text. Keep related headings and body text
  aligned to the same leading edge.
- Do not justify app-owned paragraphs. Uneven word spacing is especially
  distracting in narrow popups, large text, and long translations. Library app
  descriptions follow this rule too; their supplied wording stays unchanged.
- Center alignment is reserved for compact, standalone statuses, short empty
  states, and labels beneath centered icons. Do not center a paragraph merely
  because its popup is centered. Longer explanations use start alignment.
- Let text wrap naturally; do not insert spaces, manual line breaks, or custom
  layout calculations to force visual alignment.

This readability choice follows [W3C guidance on one-sided text alignment](https://www.w3.org/WAI/WCAG22/Techniques/general/G169).

### Sizing and scrolling

- Derive popup width from the available container, safe drawing area, and the
  shared `adaptiveDialogLayout()` margins and maximum width. Use constraints,
  not device names or portrait/landscape flags, to choose a multi-column layout.
- Height wraps the content up to the shared maximum inset from the safe window
  edges. A short message must not produce an almost empty tall window. Long
  content may use the available height before becoming scrollable.
- Measure title, body, and actions together. Do not reserve a guessed fixed
  120–200 dp for a title/footer or subtract their space twice. Omit the footer
  entirely when a menu has no footer actions.
- Prefer `AdaptiveAlertDialog` for short decisions and action menus. Custom
  platform-hosted Compose dialogs use the same bounds, type scale, and theme.
  Reuse the shared host rather than adding another dialog-size policy.
- Keep inner padding modest and consistent: usually 20 dp horizontally and
  16 dp vertically, with 16 dp horizontal padding in narrow custom hosts and
  12 dp vertical padding in short windows. Use 8–12 dp between distinct groups.
  Add larger gaps only when they clarify a real grouping, not to fill space.
- Give each body one scroll owner. Simple text/forms can use the shared body's
  scroll container; lazy lists and interactive content may own their scrolling
  within the measured body viewport. Avoid nested scroll containers with
  independent guessed height limits.
- Keep a visible themed scroll hint while content remains below the viewport.
  The hint should be distinct from the text beneath it and disappear at the end.
  A preview's initial frame is insufficient evidence: verify the hint and the
  last item/action after layout in an interaction test.
- Keep actions reachable. Wrap action rows when labels need more width, and
  allow a short-window fallback to scroll the complete custom popup when its
  title/actions cannot sensibly fit outside the body. Never clip an action
  permanently or rely on a hidden gesture to find it.

## Theme, accent, and readable content

- Resolve surface, text, icons, controls, links, selection, errors, and scroll
  hints from `MaterialTheme.colorScheme`. Use `primary` for actionable links and
  accents; reserve `error` for errors/destructive meaning rather than decoration.
- Preserve the selected light/dark theme and accent throughout a popup, including
  HTML-derived links and secondary surfaces. Neutral surfaces need not all be
  accent-colored. Avoid mixing fixed blue links with a different selected accent.
- Maintain readable foreground/background pairs. Do not paint app UI white or
  black merely to match one screenshot. Actual game colors and HSV picker
  gradients are content and retain their intended colors.
- Use short, concrete titles, clear action verbs, and helpful error messages.
  State what happened and the next useful action. Distinguish a failed operation
  from an operation whose files were saved but whose remaining step needs retry.
  Keep technical diagnostics behind an explicit copy/details action.

## Interaction, accessibility, and performance

- Preserve Back, outside-dismiss, confirmation, and navigation semantics when
  refactoring presentation. Explain an unavailable action or an operation waiting
  to stop; prevent duplicate submissions while it is running.
- Keep touch targets at least 48 dp where practical. Use one semantic target for
  a selectable row, with its indicator inert. Label icons and expose selection,
  busy/error state, and meaningful actions to accessibility services.
- Support 200% text, long/localized labels, keyboard focus, and the IME. Scroll
  focused fields into view and keep the confirm/cancel path usable.
- Keep file, database, network, parsing, and expensive image work off the UI
  thread. Use lazy lists with stable keys for potentially long collections;
  ordinary columns are sufficient for a few fixed fields or actions.
- Keep one owner for each interaction state. Remember expensive derived values
  with complete keys; avoid recomputing them or persisting settings on every
  frame. Reuse existing components and dependencies instead of introducing a
  framework for a small presentation change.
- Prefer direct feedback and inexpensive transitions. Decorative animation,
  layered effects, and artificial delays must not slow interaction or obscure
  progress, especially on low-end devices.

## Visual and interaction review

Check compact, medium, and expanded widths; short and tall windows; portrait and
landscape; light/dark themes; a non-default accent; and normal/200% text. Include
system bars and IME behavior where relevant. Review the actual rendered output
and verify overflow, the last action, dismissal, and accessibility semantics.

Compare reference, actual, and diff images before approving screenshot updates.
A green screenshot test does not by itself prove that spacing or interaction is
good. Do not accept a baseline only to silence CI, and do not relax comparisons
to hide a functional or layout regression.

## Review checklist

When adding or changing a string, check the rendered context rather than only the resource value:

1. Is it a title/header/section, option name, field title/name, or compact
   interactive control? Use Capitalize Each Word.
2. Otherwise, is it a description, explanation, status, or hint? Use sentence
   case. Sentence-like wording does not override an option or title's role.
3. Is it a proper noun, acronym, unit, URL, or identifier? Preserve its established form.
4. Does the same key have a locale-specific translation? Review that locale instead of changing unrelated translations mechanically.

For example, the installer title is `MIDlet Installer`; its actions are
`Install`, `Start`, and `Cancel`. A multi-word action is `Install Again`, not
`Install again`. Neither `install` nor `INSTALL` is the default form for that
action label. The status beneath a progress indicator remains `Installing application…`.

## References

- [Android Developers: Buttons](https://developer.android.com/design/ui/tv/guides/components/buttons) recommends sentence case for button label text.
- [Android Developers: Writing principles](https://developer.android.com/design/ui/cars/guides/foundations/writing-guidelines) allows sentence case or title case for buttons when the choice is consistent.
- [Material Design: Writing](https://m1.material.io/style/writing.html) documents sentence-style capitalization for titles, headings, labels, and menu items.
