# UI copy and presentation style

The [Localization contract](localization.md) owns semantic copy quality,
translation, text ownership, and locale/resource identity. This document retains
ownership of capitalization, typography, presentation, and copy styling.

JL-Mod Plus uses Material 3 typography and component structure to distinguish
titles, sections, settings, supporting text, values, and actions. Capitalization
does not create that hierarchy.

App-owned copy follows the natural capitalization conventions of its target
language. Sentence case is the default product style for new or revised English
and Indonesian UI copy unless grammar, a proper noun, acronym, technical
identifier, API name, or established product name requires another form.
Existing untouched copy may be migrated incrementally during scoped UI/copy
work. Do not perform repository-wide casing churn merely for consistency.

## Rules

- Use sentence case by default for new or revised English and Indonesian titles,
  section headings, option names, buttons, menu actions, descriptions, statuses,
  and helper text. Follow the target language's natural orthography for other
  locales.
- Let the component and Material 3 typography role establish visual emphasis;
  do not capitalize every major word to make a label look more prominent.
- Keep descriptions, explanations, statuses, hints, and helper text in sentence
  case. Capitalization follows the target language's natural conventions:

  | Context | Example |
  | --- | --- |
  | Section/header title | `Application information` |
  | Field title | `Profile name` |
  | Field placeholder | `Enter a profile name` |
  | Option/checkbox name | `Save screen parameters on exit` |
  | Description beneath that option | `Save the current screen parameters when the game closes.` |
  | Progress status while reading a file | `Loading information…` |
  | Action button | `Save profile` |

  A progress status is not a section title merely because it is prominent.
  Review the actual placement and semantics. Keep a persistent field label when
  a placeholder disappears during input.
- Use normal sentence case for body and confirmation messages. Do not use ALL CAPS for ordinary rendered copy.
- Resource keys such as `START_CMD` and `CANCEL_CMD` are legacy identifiers and do not define rendered capitalization.
- Write full messages as normal sentences: capitalize the first word, use ordinary punctuation, and start a new sentence after a newline with a capital letter.
- Preserve proper nouns, product names, and technical abbreviations: `JL-Mod Plus`, `J2ME`, `MIDlet`, `JAR`, `JAD`, `KJX`, `GitHub`, `Android`, `Material 3`, and `H3NB`.
- Preserve the same semantic role across locales, but follow each target
  language's natural capitalization rather than mirroring English. Retain correct
  spelling, diacritics, acronyms, and technical notation.
- Store intended app-owned capitalization in resources; do not transform arbitrary
  content at runtime. Game names, user-created profile/collection names, source
  metadata, and identifiers retain their supplied form.
- Keep lowercase where it is technically required or grammatically embedded, such as units (`ms`), identifiers, URLs, or a word that intentionally continues a sentence.

## Typography

Compose surfaces use the Material 3 type scale instead of arbitrary `sp`
values. The following mapping is the review baseline, not a freeze on component
choices. Prefer the semantic defaults of the current Material 3 component; use a
different role when the actual hierarchy or readability requires it rather than
preserving a poor historical mapping for consistency.

- Top app bar titles use `titleLarge` or the component default.
- Standard setting/list headlines use `bodyLarge`; normal explanatory supporting
  text uses `bodyMedium`. When a setting renders a current value as a separate line
  beneath supporting text, keep those roles visually distinguishable; `labelLarge`
  with `onSurface` is the default baseline for the value while the description stays
  `bodyMedium` with `onSurfaceVariant`. Do not collapse semantically different
  adjacent text into the same type role, color, and emphasis.
- Prefer a semantic Material 3 typography role before adding an explicit font-weight
  override. Explicit weight is still appropriate when the component role cannot
  express a required hierarchy or state. Do not remove an existing hierarchy signal
  merely to eliminate a custom weight; inspect the resulting rendered hierarchy first.
- Section titles generally use `titleMedium`. Screen headings may use
  `headlineSmall`; alert/dialog headlines use `headlineSmall`.
- Dialog body text uses `bodyMedium`; actions use `labelLarge` or their Material
  component default.
- `bodySmall` is for genuinely compact secondary content such as metadata,
  timestamps, diagnostics, measurements, telemetry, or minor annotations.
- Field labels, units, and compact secondary annotations use `labelMedium` or
  `labelSmall`.
- Where Material 3 components such as `ListItem` already provide a semantic type
  hierarchy, prefer their defaults over recreating the same hierarchy with
  explicit type and font-weight overrides.
- A custom `sp` value needs a component-specific reason and verification of
  relevant font-scale, translation, and width risks. Reuse suitable coverage or
  inspect a targeted render; a custom size alone does not require a new golden.
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
- Use `AdaptiveAlertDialog` for short decisions and action menus when it fits
  the interaction. Custom platform-hosted Compose dialogs use the same bounds,
  type scale, and theme. Reuse the shared host when it satisfies the requirement;
  if a shared primitive is the source of a verified problem, fix the appropriate
  shared boundary rather than layering a one-off dialog-size policy on top.
- Keep inner spacing modest and consistent with the shared dialog defaults.
  Current hosts generally use about 20 dp horizontally and 16 dp vertically,
  with 16 dp horizontal and 12 dp vertical spacing in constrained custom hosts,
  plus 8–12 dp between distinct groups. Treat these as implementation defaults,
  not universal constants; change them when a concrete hierarchy or fit problem
  justifies it rather than to fill space.
- Give each body one scroll owner. Simple text/forms can use the shared body's
  scroll container; lazy lists and interactive content may own their scrolling
  within the measured body viewport. Avoid nested scroll containers with
  independent guessed height limits.
- Keep a visible themed scroll hint while content remains below the viewport.
  The hint should be distinct from the text beneath it and disappear at the end.
  A preview's initial frame is insufficient evidence: verify the hint and the
  last item/action after layout through existing behavioral coverage or a
  targeted interaction/manual check, following [Testing strategy](development.md#testing-strategy).
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
- Follow the [Localization contract](localization.md#ui-roles) for title, action,
  and error meaning. Keep technical diagnostics behind an explicit copy/details
  action.

## Presentation review

For interaction architecture, accessibility behavior, performance, and general UI testing policy, follow [App-owned UI development](app-ui-development.md) and [Testing strategy](development.md#testing-strategy). This document adds only copy and presentation-specific checks.

- When copy, typography, theme, or popup presentation changes materially, inspect the rendered contexts that can expose the change, such as a narrow width, long localization, large text, or light/dark theme. Choose cases from the actual risk rather than running a universal visual matrix.
- Follow [Visual verification](development.md#visual-verification-and-screenshot-references) for baseline selection and updates. A green comparison does not by itself establish that spacing, wrapping, hierarchy, or readability is correct.

## Review checklist

When adding or changing a string, check the rendered context rather than only the resource value:

1. Is the semantic role correct?
2. Is the appropriate Material 3 typography/component role used?
3. Is the text naturally capitalized for its locale?
4. Does supporting text add information rather than repeat the title?
5. Are proper nouns, acronyms, APIs, units, identifiers, and supplied content preserved?
6. Is the rendered result usable with narrow width, large text, and longer translations?
