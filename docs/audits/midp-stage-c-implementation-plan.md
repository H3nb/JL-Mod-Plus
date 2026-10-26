# MIDP Stage C — implementation and validation plan

**Status (2026-10-26): historical Stage C implementation and validation plan, not an active task list.** The `alpha` branch contains TextField/TextBox, PushRegistry, and platform-request implementations with focused JVM tests. This does not establish that every Android-device qualification listed below was executed or remains current. Consult [Java ME compatibility](../java-me-compatibility.md), the current source, tests, and PR evidence before identifying outstanding work. The original scope excluded RMS changes.

## Contract-first constraints

- Treat MIDP 2.0 JSR-118 as normative; distinguish 2.1 maintenance clarifications. Do not use upstream J2ME Loader behavior as normative.
- Preserve a single authoritative guest-visible text value. Distinguish programmatic updates from user input, and do not send user-originated ItemStateListener callbacks for programmatic changes.
- Validate TextField/TextBox maxSize, constraints, mutation bounds, and exception semantics before touching Android views. Account for composing IME text, cursor/selection, password privacy, and multiline TextBox.
- Unsupported PushRegistry operations must not report fake success; preserve correct argument validation and exception categories.
- platformRequest returns whether the suite must exit, not whether Android startActivity returned normally. Preserve foreground handoff ownership and cancellation; test Android 10+ background activity restrictions and JAD/JAR installation paths.
- Do not add unrelated refactors, a new lifecycle framework, a save migration, or permanent CI solely for this task.

## Proposed implementation commits

1. `fix(midp): enforce TextField and TextBox text contracts` — focused JVM contract tests.
2. `fix(midp): reconcile Android text editing and guest callbacks` — Android instrumentation for IME, focus, multiline, privacy, and view reuse.
3. `fix(midp): report unsupported PushRegistry operations correctly` — JVM contract tests.
4. `fix(midp): correct platformRequest handoff and cancellation` — Android lifecycle/intent instrumentation.

## Required evidence before ready-for-review

- Contract boundary tests for invalid inputs, maximum size, truncation, constraints, and all editing methods.
- Test that API-originated changes do not trigger ItemStateListener, while user edits do and respect event ordering.
- Exercise Android IME composition, negative/decimal intermediate input, paste, cursor placement, multiline, and password modes.
- Exercise platformRequest success, no handler, empty URL, external browser foreground, and app resume on an Android emulator.
- Inspect effective diff against latest alpha, not just each incremental commit.
- If temporary virtual Android CI is introduced, remove it after successful validation and before merge.

## Safety

RMS implementation, file names, storage formats, and migrations are explicitly out of scope. A passing build alone is not evidence of runtime correctness.
