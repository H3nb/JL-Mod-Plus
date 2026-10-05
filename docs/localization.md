# Localization and semantic UI copy contract

This document owns localization architecture and semantic copy quality for
JL-Mod Plus. [AGENTS.md](../AGENTS.md) governs project authority and scope;
[UI copy and presentation style](ui-copy-style.md) owns capitalization,
typography, color, and presentation. Apply both without duplicating their rules.

Phase 1 establishes this contract only. The rules below govern subsequent copy
and localization work; they do not assert that existing resources already comply.
Sections marked **Target** describe architecture to implement in later scoped
phases, not current runtime behavior.

## Meaning and evidence

The source of meaning is:

`implementation / specification / verified behavior` → `semantic contract` →
`canonical English copy` → `locale-specific natural rendering`.

Explicit project contracts define intended behavior; applicable specifications
define default compatibility except for deliberate, verified project exceptions.
Implementation and verified behavior establish what actually happens. Existing
strings are evidence to review, never authoritative proof of backend behavior.
If implementation conflicts with intended behavior, record the discrepancy;
do not promise intended behavior that the implementation does not establish.

Before auditing wording, inspect enough context to establish:

1. What triggers the text.
2. The relevant state, event, or result.
3. What the backend actually does, including failure and asynchronous paths.
4. The consequence observable or relevant to the user.
5. The UI role and surface.
6. The wording that faithfully represents that behavior.

If evidence cannot establish behavior confidently, return `NEEDS_CONTEXT` with
the missing evidence rather than inventing a rewrite. Record concise conclusions
and reviewable evidence, such as source paths/symbols, an applicable specification,
or a verified runtime observation; do not require or store private chain-of-thought.

## Copy quality priority

Apply this order when criteria compete:

1. Semantic fidelity.
2. Contextual clarity.
3. Information sufficiency.
4. Actionability when applicable.
5. Concision.
6. Terminology consistency.
7. Natural language quality.
8. Fit for the UI role/surface.

> Use the shortest wording that preserves all user-relevant meaning.

Establish semantic correctness before optimizing elegance or brevity. Keep a
longer sentence when its information materially affects understanding, decisions,
confidence, or recovery. Remove politeness, repetition, implementation details,
and filler that add no user-relevant information. Surface fit does not justify
omitting a material consequence; adjust presentation when necessary. Do not impose
arbitrary character limits.

## UI roles

The role determines the context required, not the length of the string. Examples
below illustrate contracts; they are not audited replacements for existing copy.
Follow [presentation style](ui-copy-style.md) for casing and visual treatment.

| Role | Required meaning |
| --- | --- |
| Action/button | Name the operation the user initiates with a concrete verb; do not imply its completion. Include the object when context does not identify it. |
| Menu action | Identify the operation or destination clearly among neighboring choices. |
| Screen/dialog title | Identify the subject or decision; put consequences and explanation in the body. |
| Section/field title | Name the grouped information or input; provide a persistent label when a placeholder disappears. |
| Setting title | Identify the controlled behavior or choice. |
| Setting summary | Explain the observable effect, relevant condition, or tradeoff instead of repeating the title. |
| Progress/status | Describe the current phase or pending request; do not report success before completion is established. |
| Success result | State the verified outcome and any relevant partial completion or remaining step. |
| Warning | Explain the relevant risk or limitation and its consequence; include an available mitigating action. |
| Confirmation | Identify the operation and affected object, material consequences, and decision actions before the operation. |
| Recoverable error | State what failed, a useful known cause, the effect on user data/result, and a supported recovery action. |
| Non-recoverable error | Explain what cannot continue and what remains usable or preserved when known; offer only actions that actually exist. |
| Hint/placeholder | Explain expected input or format; do not replace a necessary persistent field label. |
| Empty state | Distinguish no content from no matching results or unavailable content, and show a useful supported next step. |
| Diagnostic summary | Explain the user-relevant failure/outcome; separate it from raw technical evidence while retaining details needed for investigation. |

Examples, conditional on verified behavior:

- Dialog title `Delete Collection`; body `Delete %1$s? Games in this collection
  will remain in the Library.` The retention claim requires implementation evidence.
- Setting title `Keep Screen On`; summary `Keep the screen on while a game is
  running.` The summary identifies the effect and when it applies.
- Pending `Stopping game…`; completed `Game stopped.` A stop request alone
  cannot justify the completed message.
- User explanation `The report was saved, but the browser could not open. Open
  GitHub to submit it.` Raw exception/stack trace belongs in technical details.
  Use this explanation only if saving succeeded and that recovery is available.

## Languages and completeness

English is the canonical source language and ultimate Android fallback in the
unqualified resources. Its **target** physical catalog is
`app/src/main/res/values/strings.xml`. Canonical English expresses the established
semantic contract; it does not define backend behavior independently.

English must be natural product English, direct and concrete. Avoid unnecessary
passive constructions, backend jargon without user relevance, and vague errors
such as `An error occurred` when a useful cause is known. Never claim `saved`,
`deleted`, `stopped`, or `completed` unless the implementation establishes that
state. Setting summaries explain observable effects; asynchronous messages
distinguish requested/pending work from completed work.

Indonesian is a first-class required localization: use standard, neutral Bahasa
Indonesia suitable for modern software UI. Prefer natural Indonesian syntax over
literal English structure, and use sentence case by default for app-owned
Indonesian UI. Preserve technical terms when translation would reduce clarity.
Capitalization is language- and presentation-specific; it does not need to mirror
English. Preserve every user-visible semantic distinction of the canonical
source even when sentence structure or terminology differs.

> English and Indonesian are two language renderings of one semantic contract,
> not two independent sources of product meaning and not a literal translation pair.

**Target:** every locale declared supported has a complete, reviewed catalog.
Partial localization is acceptable during migration, not the desired permanent
architecture. English fallback is a runtime safety mechanism, not a reason to
leave supported locales intentionally incomplete. Review translations against
the established semantic contract and canonical English; resolve a source-copy
defect before propagating it to other languages.

## Resource and parameter contracts

### String identity

Use `<scope>_<concept>_<role>` for new or semantically redesigned keys, for example
`library_search_hint`, `library_delete_confirmation`, `installer_progress_status`,
`runtime_exit_title`, `memory_editor_results`, and `common_save_action`.

A key identifies meaning and UI role, not a particular English sentence. Wording
refinement alone does not require a new key. A fundamental semantic, role, or
parameter-contract change requires review of whether a new key is more correct,
including affected callers and translations. Do not mass-rename legacy keys for
naming consistency alone.

The active Config missing-application path uses
`config_missing_app_storage_named` and `config_missing_app_storage_generic`,
whose named form receives the raw storage-volume description. Legacy
`err_missing_app` retains its historical quoted-name/trailing-space formatter
contract as a dormant cleanup candidate so partial secondary locales are not
silently given a different argument contract.

### Complete messages and placeholders

Store user-facing prose as complete messages: `Delete %1$s?`, not translated
fragments assembled as `Delete` + filename + `?`. Use indexed placeholders where
another language may reorder arguments. Locales may change parameter order, but
must preserve argument meaning and compatible format types.

Placeholders are semantic inputs, not anonymous formatting slots. For a
non-obvious argument, use a concise XML comment or XLIFF metadata where useful:
is `%1$s` a filename, MIDlet name, profile name, path, error detail, product name,
or user-provided content? Document that distinction without altering supplied
content. Self-evident simple strings do not need verbose comments. This contract
does not require a repository-wide XLIFF migration.

### Plurals

Plural categories are locale-specific; translations need not reproduce English's
exact `one`, `other`, or other category set. Preserve semantic meaning, valid
target-locale plural structure (including `other`), and compatible argument
contracts. Let Android Resources select the locale's grammatical category; do
not implement custom plural morphology or use plural categories as business-state
conditions. If the count is also displayed, supply its formatting argument as well
as the selection quantity. See [Android string resources](https://developer.android.com/guide/topics/resources/string-resource).

### Language-invariant resources

Use `translatable="false"` only when identity genuinely does not vary with natural
language: machine identifiers, configuration persistence values, URLs, product
names, exact technical notation, or keypad symbols/digits where applicable.

Missing translation, technical appearance, or a partly invariant message is not
enough. **Target:** a phrase combining prose and invariant data uses localized
prose plus an invariant argument/value. Existing `translatable="false"` resources
need a separate semantic audit; Phase 1 changes none of them.

## Text ownership and resolution

| Owner | Contract and examples |
| --- | --- |
| App | Localize Library, Settings, installer/configuration UI, app-owned runtime menus/dialogs, Memory Editor, and diagnostic explanations. |
| MIDlet | Preserve supplied command labels, in-game text, names, and content. |
| User | Preserve collection/profile names, filenames, and game names unless the app itself owns the name. |
| Machine/protocol | Preserve stable persistence identifiers, enum/storage values, event codes, stack traces, and raw technical diagnostics. Localize the app-owned explanation surrounding them. |

A process boundary does not change ownership: an app-owned dialog in `:midlet`
is still localizable. Preserve supplied content rather than translating it,
normalizing its casing, or interpreting it as app-owned copy.

Honor explicit output-format contracts separately from localized UI. The
[diagnostic bundle contract](diagnostic-bundles.md#format-version-2) defines
`report.md` as canonical-English evidence; localize the app-owned explanation
around that export without translating the evidence format or raw diagnostics.

**Target:** backend/domain/IPC/persistence represent semantic state with stable
structured values, such as `StorageUnavailable`, rather than localized sentences
such as `"The working folder is unavailable."`. Resolve resource-backed localized
text as late as practical at the presentation/output boundary. This applies to
Compose, AppCompat/View UI, notifications, service-produced visible text, and
app-owned UI in secondary processes, including `:midlet` and `:memory_engine`.
It does not require a new localization layer or imply that current state/IPC
already follows this model.

Memory Editor operation replies now use the
[MemoryEngineContract](../app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEngineContract.java)
result codes, bounded reason codes, and independent write-outcome counts.
The app-owned presentation maps these values to Android resources. Bounded
backend diagnostic detail remains separate and is not used as UI copy.

Bulk Install preflight review likewise carries typed semantic reasons and raw
arguments separately from bounded source/parser diagnostics. Android presentation
resolves the reasons into resources; the planner does not construct UI prose.

## Locale identity and target architecture

Application locale identity uses canonical BCP-47-style language tags, such as
`id` for Indonesian, separately from physical Android resource-directory
compatibility. Treat equivalent legacy/modern codes as a general canonicalization
concern, preserving meaningful script/region distinctions rather than collapsing
all tags to a base language. Existing `values-in` may remain until a separately
tested migration proves a directory change safe; it is not the canonical
application identity. Do not rename locale directories in Phase 1.

**Target architecture:**

- One human-language `strings.xml` catalog per locale, containing both `<string>`
  and `<plurals>`. The English default is complete, Indonesian is required
  complete, and every supported locale eventually becomes complete and reviewed.
- Structural arrays may remain in `arrays.xml`. Visible labels reference localized
  strings where appropriate; machine array values stay stable and non-translatable.
- Android native Resources remain the localization engine. Do not add a
  `TranslationManager`, translation repository, custom runtime language database,
  or parallel framework unless a later concrete requirement proves it necessary.
- A dedicated runtime phase must establish the in-app supported-locale registry
  and harden its dependency on generated locale metadata. Catalog presence alone
  does not establish translation completeness or review quality.

Current implementation evidence:

- [resources.properties](../app/src/main/res/resources.properties) declares
  `unqualifiedResLocale=en`; [app Gradle configuration](../app/build.gradle.kts)
  enables `androidResources.generateLocaleConfig` and disables `MissingTranslation`.
- English [default resources](../app/src/main/res/values/strings.xml) and
  [Indonesian resources](../app/src/main/res/values-in/strings.xml) each use one
  `strings.xml` catalog for strings and plurals after Phase 3 physical
  consolidation, as do [Russian resources](../app/src/main/res/values-ru/strings.xml)
  and the other base locale catalogs. Structural resources and configuration-specific
  overrides remain in their own files/configurations. English and Indonesian have
  been reviewed by semantic domain, and Indonesian covers the full translatable
  source catalog. A follow-up canonical-English review rechecked the historical
  `NEEDS_CONTEXT` set against current app-owned UI consumers. Phase 5A.1 then
  corrected bounded active-copy terminology, parameter-contract, accessibility,
  and language-quality findings. Active canonical English and Indonesian UI copy
  has been reviewed and frozen. A secondary-locale-safe dead-resource cleanup then
  removed 189 orphaned string declarations (188 translatable and one invariant)
  plus five unused drawables from every applicable catalog without changing active
  resource consumers or runtime input implementation. The canonical translatable
  source catalog now contains 883 resources and 31 invariant text declarations.
  Russian covers the full translatable source catalog, has completed
  semantic and language review, and was realigned only for canonical semantics
  changed by Phase 5A.1. Its locale-appropriate plurals and current no-argument
  mapping-dialog contract remain intact. The remaining 37 secondary locales are
  still incomplete; catalog coverage alone does not establish semantic quality.
- [SettingsActivity.buildLanguageOptions](../app/src/main/java/io/github/h3nb/jlmodplus/settings/SettingsActivity.java)
  reads `_generated_res_locale_config`; unavailable/unreadable metadata leaves
  the system-language option. This requires later runtime hardening.
- [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) declares `:midlet`
  and `:memory_engine` components alongside default/main-process components.

These facts describe the current mechanisms, not permanent requirements. Phase 1
does not consolidate/rewrite catalogs, rename keys/directories, change the picker,
`generateLocaleConfig`, or `MissingTranslation`, add `locale_config.xml` or
pseudolocales, or refactor runtime state/IPC. It adds no translation platform,
AI API integration, validator, audit database, build task, or CI enforcement.
Later tooling must be documented only when it exists. Android's
[localization fallback](https://developer.android.com/guide/topics/resources/localization)
and [generated locale configuration](https://developer.android.com/guide/topics/resources/app-languages)
provide platform context; they do not certify catalog quality.

## Deterministic inventory (Phase 2)

[scripts/localization_audit.py](../scripts/localization_audit.py) inventories
repository-owned `app/src/main/res/values*/*.xml` declarations without modifying
resources. It records `<string>`, `<plurals>` with per-quantity placeholders, and
`<string-array>` reference/literal structure. Structural arrays are separate from
catalog fragmentation and translation coverage. This is not an inventory of
every string in the final APK: dependency/generated resources, variant-generated
`app_name`, other resource roots, and code literals are outside its scope.

The default English key set spans all unqualified `values/` XML files. Coverage
counts distinct `(resource type, key)` pairs, excludes default declarations marked
`translatable="false"`, and measures declaration presence, not translation quality.
Empty locale directories remain visible. Canonical identities retain physical
directory evidence; language/region and `b+language[+Script][+REGION]` forms are
recognized, with legacy `in`/`iw`/`ji` aliases. Other qualifiers, such as night/API
overrides, remain separate and do not inflate base locale coverage. This bounded
parser does not implement the full Android qualifier grammar.

The schema-versioned JSON records facts and report-only findings: coverage,
fragmentation, typed extras, duplicates within an exact configuration, type
differences, default invariants, localized invariant declarations, and ordinary
string argument mismatches. Indexed argument order may differ; clear comparisons
use argument index/type families, not width, precision, or occurrence count.
Unindexed multi-argument messages, unresolved resource references, disabled
formatting, and unparsed percent text are recorded conservatively rather than
declared clear mismatches. XML markup is flattened for lexical inspection;
Android string escaping and resource references are not resolved. This is not
full Java Formatter validation. Plural categories/signatures are inventoried
without requiring English category parity or evaluating plural grammar.

See [Build and validation](development.md#localization-inventory) for the actual
commands and ignored report paths. Findings do not fail the command; unreadable
input, malformed XML, unsupported locale forms, or execution/usage errors do.
The tool supplies no semantic/language/dead-resource verdicts, equality judgment,
AI dependency, resource repairs, or CI enforcement. Those decisions remain in
their separately evidenced phases under this contract.

## Initial semantic glossary

Preferred terms are tied to product meaning, not universal dictionary equivalents.
User/MIDlet-provided names keep their supplied form regardless of the glossary.
The table is an initial review baseline, not authorization to rewrite resources.

| Concept / preferred English | Product meaning | Preferred Indonesian | Do not confuse with | Normally untranslated? |
| --- | --- | --- | --- | --- |
| MIDlet | Java ME application executed by the emulator. | MIDlet | JL-Mod Plus itself or its runtime host. | Yes, technical term. |
| Profile | Named reusable MIDlet configuration, optionally including virtual-control layout. | Profil | Current installed configuration, built-in defaults, linked ownership, Java ME platform profiles, or controller calibration profiles. | No; preserve the supplied profile name. |
| Preset | Preconfigured option/set, such as controller mappings or font sizes. Named MIDlet presets are presented as Profile/Profil despite internal `preset_*` keys. | Prasetel for generic options; Profil for named MIDlet configuration. | Do not introduce a second visible name for the Profile concept or infer live linkage from the word. | No. |
| Virtual Controls | App-provided touch buttons, D-pad, and analog stick. | Kontrol virtual | Android IME, physical gamepad controls, guest input fields, or guest display. | No. |
| Layout | Arrangement/geometry of virtual controls when used in that context; qualify as Virtual Controls Layout when needed. | Tata letak; tata letak kontrol virtual when qualified. | Key-code layout is a separate concept; inspect which arrangement a surface controls. | No. |
| Collection | Named Library grouping whose membership references installed apps. | Koleksi | Filesystem folders, moving/deleting installed files, or Favorites. | No; preserve the supplied collection name. |
| Diagnostic Report | Retained diagnostic incident/evidence record, including qualifying nonfatal process exits. | Laporan Diagnostik | Derived public bundle, ordinary logs, GitHub issue, or crash-only classification. | No for UI labels; exported evidence follows its format contract. |
| Memory Editor | App-owned UI for searching, inspecting, modifying, watching, and freezing supported values in the active runtime. | Editor Memori | Configuration/file editing or unrestricted process-wide memory access; actual capabilities constrain available operations. | No. |
| Runtime | Active MIDlet execution environment/session, including host lifecycle, rendering, and input management. | Runtime | Installed app, persistent profile, host menu, or elapsed running time. | Yes, established technical usage. |
| Immediate Processing | Compatibility option that processes MIDlet events directly instead of queuing them on the normal Java ME event queue. | Pemrosesan langsung | Emulation Speed, Clock Mode, or ordinary asynchronous work. | No. |

Meaning is grounded in the [preset/configuration contracts](preset-config-contract.md),
[Library data contracts](library-data-contract.md), [runtime UI boundaries](runtime-ui.md),
[diagnostic contracts](diagnostic-bundles.md), and
[Memory Engine capabilities](../app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEngineContract.java).
Established vocabulary appears in the English/Indonesian configuration,
virtual-control, collection, diagnostic, and Memory Editor resources. These
wordings establish vocabulary only, not proof of behavior. For another use of a
term whose meaning is unclear, record a narrowly scoped TODO or `NEEDS_CONTEXT`
rather than extending the glossary by guesswork.

## AI-assisted workflow

AI assistance is optional and non-authoritative. Implementation, applicable
specifications, verified runtime behavior, and explicit project contracts remain
authoritative. Runtime correctness must not depend on a model, provider, API, or
orchestration mode.

Choose review roles by task requirements rather than model names or capability
tiers. One capable reviewer may cover multiple roles; split work only when a
bounded handoff materially improves the task.

| Role | Responsibility |
| --- | --- |
| Semantic reviewer | Resolve ambiguous or high-impact meaning from implementation/specification evidence; trace state/lifecycle/IPC-sensitive claims; decide whether source semantics or parameter contracts change; review difficult canonical-copy decisions. |
| Bounded batch assistant | Perform deterministic inventory/extraction, mechanical classification, repeated terminology checks, structured candidate batches, and straightforward translations after the semantic contract is established. Do not guess unresolved backend behavior; return uncertainty to semantic review. |
| Language reviewer | Judge target-language correctness, naturalness, terminology, and plural/grammar behavior without changing established product meaning. Use `NEEDS_LANGUAGE_REVIEW` when competent language judgment is unavailable. |

Establish semantics from relevant evidence before batch work. Prefer deterministic
tooling for inventory and validation. Increase review depth only for concrete
ambiguity or risk; do not require a fixed number of reviewers or passes. If work
is parallelized, keep scopes non-overlapping and define one explicit handoff;
reconcile conflicts against evidence rather than reviewer identity. Record concise
verdicts and supporting evidence, and revisit affected locale renderings whenever
source meaning, UI role, resource type, or parameter contracts change.

Execution-specific model, provider, reasoning-effort, or tool choices belong in
transient task handoffs rather than this repository contract. They may change
without documentation edits as long as the roles, evidence requirements, and
acceptance criteria above are preserved.

Review vocabulary:

| Audit | Verdict | Meaning |
| --- | --- | --- |
| Source copy | `KEEP` | Existing wording meets the established semantic and role contract. |
| Source copy | `REWRITE` | Evidence establishes meaning, but wording needs correction under the quality priority. |
| Source copy | `NEEDS_CONTEXT` | Behavior, role, or parameter meaning cannot yet be established; identify the missing evidence. |
| Source copy | `DEAD` | Current reference/usage evidence establishes that the resource is no longer used; removal remains a separate scoped change. |
| Localization | `ACCEPT` | Rendering preserves semantics/arguments and meets target-language quality. |
| Localization | `REVISE` | Semantics remain intact but wording, terminology, or role fit needs refinement. |
| Localization | `SEMANTIC_MISMATCH` | Rendering changes or omits a user-relevant meaning or argument contract. |
| Localization | `NEEDS_LANGUAGE_REVIEW` | Target-language correctness/naturalness cannot be confidently judged; defer acceptance to a competent language reviewer. |

These are workflow labels, not runtime resource metadata. Use them when an
explicit audit record is useful; they do not require an audit database, fixed
review sequence, or AI integration.
