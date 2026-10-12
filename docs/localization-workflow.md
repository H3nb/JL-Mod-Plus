# AI-assisted localization workflow

Read [AGENTS.md](../AGENTS.md) for repository authority, the
[localization contract](localization.md) for product meaning and resource
ownership, and [UI copy style](ui-copy-style.md) for presentation. This guide
describes execution, not a replacement semantic contract.

## Sources and responsibilities

- Android `app/src/main/res/values/strings.xml` is canonical English; the
  existing `values-*/strings.xml` files are the accepted locale catalogs.
  English fallback is a safety net, not an indicator of translation quality.
- The XML files remain the only source of resource text consumed by Android.
  Do not add a runtime translation manager, parallel locale database, or
  committed spreadsheet copy.
- `scripts/localization_audit.py` owns resource inventory and structural
  findings. The matrix exporter reuses that inventory; it does not parse or
  rewrite the resource XML itself.
- Generated reports under `build/reports/localization/` are temporary and
  ignored by Git. An AI or spreadsheet can use them for side-by-side work.
- Existing reviewed strings are a stability baseline. Preserve them unless
  evidence supports a semantic, linguistic, accessibility, or consistency fix.

## Before translating

1. Run the deterministic inventory and identify missing keys, mismatched
   placeholders, resource types, and locale coverage. Do not equate coverage
   with quality or assume old translations are wrong.
2. Select a cohesive feature context, not arbitrary consecutive XML lines.
   Record the UI role, observable behavior, placeholder meanings, and any
   high-impact consequence once for all target languages. Consult relevant
   source/tests when those facts are ambiguous.
3. Review the English source before propagating it. Resolve a proven semantic
   defect at the source, not independently in every translation.
4. Choose relevant target locale tags, keeping script/region distinctions
   (`pt-BR` is not automatically `pt`; `zh-CN` is not `zh-TW`).
   Android legacy directory aliases such as `values-in` map to canonical
   application language identity `id`.

## Produce a side-by-side translation batch

From the repository root with Python 3.10+:

```sh
python -B scripts/localization_audit.py
python -B -m scripts.localization_matrix --prefix memory_editor_ --locale id --locale ru
python -B -m scripts.localization_matrix --keys-file /path/to/selected-keys.txt --locale fr --locale de --output build/reports/localization/settings-matrix.json
python -B -m unittest discover -s scripts/tests -p 'test_localization_*.py' -v
```

`--prefix` may be repeated to combine key families; `--keys-file` uses
one key per line and supports comments beginning with `#`. The two batch
selection modes are mutually exclusive. Without selection options, export
all translatable resources and all discovered locales. `--locale` is
repeatable; absent locale flags, all existing locale tags are included.

The JSON rows contain resource identity, source location, English text and
arguments, and each selected translation. A missing translation is `null`;
an existing empty string is not missing. Plurals retain each locale's actual
quantity categories. The data is an analysis view: flattened text is not
an XML round-trip representation, approval record, or safe import patch.

For large campaigns, parallelize independent **target languages or feature
batches** using the same established semantic context. Limit output to
cohesive batches so models do not omit rows or truncate responses. Do not
run automatic translation against game/MIDlet-authored labels, user-provided
names, stable machine identifiers, or diagnostic evidence.

## Translation quality

- Convey the established meaning faithfully, including conditions,
  negation, user-visible consequences, and parameter roles. Do not infer
  success or available recovery actions from wording alone.
- Write in the target locale's standard, grammatically correct language
  and natural modern-software style. Avoid literal English syntax, stiff
  formality, unexplained jargon, and awkward forced word substitutions.
- For Indonesian, consult KBBI VI for vocabulary and EYD V for spelling
  and usage when uncertain. A dictionary entry alone is not a command to
  replace a familiar technical term. Retain established foreign, borrowed,
  or product-specific terminology when it is clearer and more natural in
  actual software usage; avoid unnecessary English where a familiar
  Indonesian expression is equally precise.
- For other languages, follow their relevant linguistic standards and
  established UI conventions; do not project Indonesian or English
  capitalization, formal-register, plural, or word-order rules onto them.
- Preserve proper nouns, MIDlet-supplied content, symbols, links, escapes,
  markup, indexed formatter argument contracts, and technical notation.
  Translate whole messages rather than concatenated fragments. Plural
  categories depend on the target language and need not match English.
- Keep one concept consistent across a locale without enforcing
  word-for-word sameness across screens; terminology varies when meaning
  genuinely differs.

## Review and apply

1. Compare **horizontally**: does each language preserve the same
   semantic contract, including negation and side effects? Compare
   **vertically**: are phrasing, register, and terminology natural and
   consistent within each locale?
2. Run a targeted second language/semantic review for consequential
   dialogs, permissions, irreversible operations, errors, and technically
   ambiguous controls. AI is an accepted primary translator and reviewer,
   but a fluent-looking result or model confidence is not proof of quality.
3. Modify the affected `strings.xml` elements directly using a small,
   reviewable patch. Never feed the flattened matrix back as if it were
   lossless Android XML; retain markup, comments, resource order, and
   untouched translations. Re-read the current source before applying
   a batch, and stop if its English text/type/placeholder contract changed.
4. Run the inventory and relevant Python tests; inspect unresolved
   placeholder findings rather than treating all report-only warnings as
   definitive errors. Verify Android escaping, references, plural grammar,
   and translations with a relevant build and representative UI checks
   when resource files change. Use pseudo-locales/RTL/narrow screens when
   the affected UI makes them relevant.
5. Report the locales and keys reviewed, omissions and ambiguities,
   validation evidence, and any language-quality uncertainty. A complete
   catalog is not automatically a fully verified localization.

The existing Crowdin configuration can support future community
contributions, but a hosted translation management service is not required
for this workflow. Keep contributions reviewable in Git and follow
[CI discipline](agent-workflow.md#ci-discipline).
