# UI copy style

JL-Mod Plus uses a hierarchy-aware capitalization policy. One casing rule is
not applied to every piece of UI copy.

This is a product style decision, not a claim that Material 3 requires title
case everywhere. Current Android guidance recommends sentence case for button
labels, while Android Auto guidance permits either sentence case or title case
when the choice is applied consistently. We choose title case for compact
controls because it matches the product's visual language and must remain
consistent across the library, profiles, and installer surfaces.

## Rules

- Use Title Case for product, screen, and dialog titles: `JL-Mod Plus`, `MIDlet Installer`, and `Profile Actions`.
- Use Title Case (Capital Each Word) for buttons, menu items, toolbar actions, and other compact controls: `Pasang`, `Mulai`, `Batal`, `Coba Lagi`, and `Simpan Profil`.
- Keep longer field labels, statuses, hints, and helper text in sentence case: `Memuat info…`, `Masukkan nama`, and `Simpan parameter layar`.
- Use normal sentence case for body and confirmation messages. Do not use ALL CAPS for ordinary rendered copy.
- Resource keys such as `START_CMD` and `CANCEL_CMD` are legacy identifiers and do not define rendered capitalization.
- Write full messages as normal sentences: capitalize the first word, use ordinary punctuation, and start a new sentence after a newline with a capital letter.
- Preserve proper nouns, product names, and technical abbreviations: `JL-Mod Plus`, `J2ME`, `MIDlet`, `JAR`, `JAD`, `KJX`, `GitHub`, `Android`, `Material 3`, and `H3NB`.
- Apply the same rule to translated resources according to the target language's writing conventions. Do not mechanically capitalize every word in a translation.
- Keep lowercase where it is technically required or grammatically embedded, such as units (`ms`), identifiers, URLs, or a word that intentionally continues a sentence.

## Typography

Compose surfaces use the Material 3 type scale instead of arbitrary `sp`
values. The following mapping is the review baseline:

- Top app bar titles use `titleLarge`/the component default.
- Screen and dialog headings use `headlineSmall` or `titleLarge` when the
  available width is constrained.
- Primary list and card titles use `titleMedium` or `titleSmall`; supporting
  metadata uses `bodyMedium`/`bodySmall` with `onSurfaceVariant`.
- Field labels, units, and compact secondary annotations use `labelMedium` or
  `labelSmall`.
- `fontScale`, long translations, and landscape width must be checked before
  introducing a custom size. A custom `sp` value needs a component-specific
  reason and a screenshot/regression case.

Colors follow the same semantic rule: surfaces, text, controls, and icons use
`MaterialTheme.colorScheme`. Literal colors are allowed only for user-selected
content (for example, the color-picker preview), for fixed color-space
gradients (white/black HSV endpoints), or when `Color.Transparent` is needed
to let a component reveal its already-themed parent surface.

## Review checklist

When adding or changing a string, check the rendered context rather than only the resource value:

1. Is it a compact interactive control? Use Title Case (Capital Each Word).
2. Is it a longer field label, status, hint, or complete sentence? Use sentence case.
3. Is it a proper noun, acronym, unit, URL, or identifier? Preserve its established form.
4. Does the same key have a locale-specific translation? Review that locale instead of changing unrelated translations mechanically.

For example, the English installer title is `MIDlet Installer`, while Indonesian
installer actions are `Pasang`, `Mulai`, and `Batal`; a multi-word action is
`Pasang Ulang`, not `Pasang ulang`. `pasang` and `MULAI` are not the project
default forms for those action labels.

## References

- [Android Developers: Buttons](https://developer.android.com/design/ui/tv/guides/components/buttons) recommends sentence case for button label text.
- [Android Developers: Writing principles](https://developer.android.com/design/ui/cars/guides/foundations/writing-guidelines) allows sentence case or title case for buttons when the choice is consistent.
- [Material Design: Writing](https://m1.material.io/style/writing.html) documents sentence-style capitalization for titles, headings, labels, and menu items.
