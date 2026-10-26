# Testing strategy and quality standards

This is the **owning policy** for designing, reviewing, retaining, and removing tests in JL-Mod Plus. Read it when a change adds or changes tests, behavior contracts, screenshot references, or test infrastructure. For exact Gradle commands, source-set paths, CI modes, and screenshot candidate handling, use [Build and validation](development.md). For the behavior being protected, consult the relevant contract via [Documentation map](README.md). Do not duplicate these operational details or product contracts here.

## Purpose and authority

The goal is **useful regression confidence at proportionate cost**, not a quota for test count, assertions, coverage, screenshots, or mutation score. Tests are evidence about an implementation; their existence or success does not make an outdated expectation a product requirement. Determine intended behavior from explicit requirements, applicable specifications (particularly Java ME/JSRs), and the owning current contract, then corroborate with production paths and observed behavior.

Before adding a test, be able to answer, without writing a separate form for each case:

1. Which behavior, invariant, boundary, or credible regression does this protect?
2. What relevant defect would make it fail? Would a wrong implementation still pass?
3. Why is this the cheapest sufficiently faithful test layer?
4. Does existing coverage already protect that failure mode?

Prefer a small number of tests with distinct failure modes. Similar inputs at different boundaries are not necessarily redundant; identical assertions at the same boundary often are. A production change **does not** automatically require a new test.

## Selecting the test boundary

Choose the lowest layer that exercises the failure mode with adequate fidelity; add a higher-layer test only for a distinct integration, platform, or rendering risk.

| Contract / risk | Preferred evidence | Limits |
| --- | --- | --- |
| Pure parsing, transformations, calculations, validation, mappings | Focused JVM tests in `app/src/test/` or `dexlib/src/test/` | Do not test only that internal helpers call one another. |
| Metadata import/export, configuration, serialization, backward compatibility | Round-trip and boundary tests; real persistence/migration integration when needed | A mock repository cannot prove a SQLite migration or file durability. |
| Bytecode transformation and Java ME API behavior | JVM contract fixtures plus targeted guest/runtime tests where API semantics require it | External emulator behavior is comparative evidence, not the Java ME specification. |
| Compose state, actions, Back, navigation, rotation, intent delivery | Compose semantics/interaction or focused Android instrumentation in `app/src/androidTest/` | A static screenshot cannot prove clickability, persistence, lifecycle, or Android platform behavior. |
| Stable visual contract: layout, clipping, typography, skin rendering | Representative Compose Preview screenshot in `app/src/screenshotTest/` | Layoutlib previews do not prove native/OpenGL presentation or device-specific behavior. |
| Android process, camera, audio, storage/provider, JNI, native packaging, guest execution | Targeted instrumentation, emulator/physical-device trials, or appropriate release checks | Compiling an instrumentation APK does not execute tests; bitmap contract tests do not verify the final native path. |
| Performance, frame pacing, memory, startup | Reproducible measurement/profile on an appropriate configuration | Unit-test duration or green CI is not a production performance measurement. |

A single flow may need complementary assertions across layers when they protect **different** invariants; avoid repeating the same scenario everywhere. App and dexlib tests, screenshots, and opt-in Android/runtime suites are different kinds of evidence, not interchangeable checkmarks.

## Test design and quality

- Favor observable postconditions and stable contracts over private class layout, delegated calls, or incidental ordering. An interaction/order assertion is appropriate when the order **is** the contract (for example, persistence/recovery ordering).
- Include a meaningful normal case and realistic boundary/error cases. Use equivalence partitions and boundary values instead of an exhaustive matrix of variations with the same failure mode.
- For a reproducible bug, prefer a regression test that fails against the broken behavior and passes with the fix when practical. Do not modify or relax a test merely to make a failing build green.
- Control clock, random seeds, scheduler, locale, filesystem, shared preferences, external data, and global state as needed. Failures should be reproducible; do not paper over races with sleeps, unbounded retries, or inflated timeouts.
- Use minimal fixtures and fakes that retain the essential contract. Mocks, reflection, fault injection, and test-only hooks are justified for otherwise inaccessible lifecycle, concurrency, failure recovery, or compatibility boundaries, not as automatic architecture patterns.
- Treat broad test rewrites after behavior-preserving refactors as a signal of accidental implementation coupling. Test code is maintained code; simplify duplication when it makes ownership and diagnosis clearer, not just to shorten files.
- Keep tests deterministic and diagnostic. Prefer failures that identify the violated behavior rather than a large indirect snapshot of unrelated state.

### Higher-leverage techniques (optional, evidence-led)

Parameterized tables suit equivalence classes and boundary values, **not** every Cartesian product. Property-based tests suit strong invariants (such as decode/encode round trips), but require sound generators, shrinking/reproduction, and clearly specified valid domains. Metamorphic tests suit properties relating multiple runs when no complete output oracle exists. Targeted mutation testing or deliberate fault injection can reveal assertions that cannot detect a meaningful defect; equivalent/irrelevant mutants are not failures of the suite.

Use these selectively after comparing with simple example tests. Do **not** add a testing framework, a code-coverage gate, a mutation-score target, or generic fixture abstractions without demonstrated benefit.

## Visual verification and screenshot references

Keep golden images for **selected stable visual contracts** where unnoticed regressions matter. Choose preview states that expose distinct failures—e.g. narrow layout/long locale text, SVG crop, icon geometry, or an important dialog state—rather than every screen, theme, language, or font-scale combination. An old golden is not an authority over a changed UX requirement.

For a mismatch:
1. Confirm source revision, renderer/tool versions, preview inputs, and environment. Determine whether it is intended design change, production regression, or renderer drift.
2. Inspect the actual image, reference and diff. Correlate with the authorized UI design and any relevant interaction/semantics contracts.
3. Fix the implementation if wrong, or accept only the **original renderer-produced** new reference pixels after review. Never synthesize, repaint, or blindly auto-approve reference images.
4. When retiring a preview/golden, identify which visual risk no longer applies or remains covered elsewhere. Do not preserve obsolete baselines to keep test counts high.

Screenshot tests are static Layoutlib render checks, not UI-interaction, accessibility, guest-input, native graphics, or device-evidence substitutes. For Back, touch targets, action reachability, focus, orientation, IME, and lifecycle, use appropriately scoped behavior tests or direct runtime checks. Follow the [screenshot workflow](development.md#screenshot-updates-without-a-local-android-toolchain) for CI-rendered candidates and reproducible updates.

## Java ME and JL-Mod Plus-specific boundaries

- For MIDP/CLDC/JSR APIs, derive expected semantics from the applicable spec and [Java ME compatibility](java-me-compatibility.md). Keep deliberately verified compatibility exceptions explicit; don't encode an upstream emulator quirk as normative by default.
- Use real JAR/JAD/MIDlet fixtures when validating descriptor interpretation, installation, guest API calls, RMS/persistence, graphics/input, and device interoperability; test the boundary, not merely a host-side mock.
- For Library, metadata, skins, memory editor and virtual controls, protect their active data/state invariants and migration behavior. Avoid retaining tests for superseded auto-save, ownership, layout, or rendering mechanisms.
- For camera, audio, native components, or 16 KB page compatibility, choose the appropriate emulator/physical-device and artifact check. State clearly what is not exercised by JVM, preview, or no-native-build tests.

## Test lifecycle: add, update, consolidate, remove

When behavior or design changes, inspect affected tests **even if green**. Classify each with evidence:

| Action | When justified |
| --- | --- |
| Keep | Still enforces an active contract or independent failure mode. |
| Update | Expected behavior or relevant fixtures changed by an authorized requirement. |
| Consolidate | Equivalent protection is duplicated, and merging retains useful diagnostics and boundaries. |
| Remove | The tested mechanism/contract is obsolete or protection is fully supplied elsewhere. |
| Add | A material unprotected failure mode remains, and a focused test can detect it. |

Never delete by age, line count, author, or a superficial similarity between tests. Do not automatically update assertions/goldens to match new production behavior: a green test and production code may agree on the **same wrong assumption**. Conversely, do not preserve obsolete behavior as a requirement merely because a test passes. Make the rationale for non-obvious removals clear in the change review; no per-test compliance inventory is required.

## Efficient execution and trustworthy evidence

Use the narrowest relevant check during development and the required integration-branch/PR checks before merge; [Build and validation](development.md#select-the-relevant-check) owns commands and CI details. Test selection must follow actual dependencies and failure modes, not only file-name matching. Cache and parallel execution are performance aids: measure cold/warm and task/test costs before changing configuration, and check for shared-state races.

Report **what executed** and on which source revision. Distinguish compile-only, unit, preview screenshot, instrumented/device, and manual checks; identify skips, failures, and unverified boundaries. An uploaded APK or successful build does not prove runtime behavior. Re-run/broaden checks after material code changes or newly discovered risks, not to collect repeated green badges.

If a test is flaky, diagnose environment, shared data, nondeterministic events or race conditions before adding retries. Coverage is useful to find unexercised code but is not a quality target; mutation findings are diagnostic, not mandatory. Use measured feedback time, meaningful defect detection, flakiness, and maintenance cost to assess any test-suite optimization.

## References (background, not additional project authority)

- [Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies) and [what to test](https://developer.android.com/training/testing/fundamentals/what-to-test).
- [Android Compose semantics and UI testing](https://developer.android.com/develop/ui/compose/testing).
- [Android screenshot testing](https://developer.android.com/training/testing/ui-tests/screenshot) and [AGP test suites for screenshots](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites).
- [Gradle JVM test filtering and execution](https://docs.gradle.org/current/userguide/java_testing.html).
- [Change-detector tests considered harmful](https://testing.googleblog.com/2015/01/testing-on-toilet-change-detector-tests.html).
- [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html).
