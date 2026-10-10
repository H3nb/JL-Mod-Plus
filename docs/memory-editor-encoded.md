# Memory Editor: encoded search implementation

This is a Draft PR. Currently implemented: scan storage accounting and isolated numeric codecs and correlation fixtures. Encoded Search is **not yet available in the app UI**.

## Product contract

- Opt-in at New Search only; subsequent refine operations inherit the session choice.
- No RMS, raw-address scanning or new guest-bytecode hooks.
- First numeric view is signed Int32 (LE/BE for reachable `byte[]`), with additive, subtractive and XOR transforms. Arithmetic matches J2ME modular 32-bit `int`.
- Two distinct visible observations are required to begin correlation; correlation does not establish authoritative gameplay state.
- Preserve all existing Known, Unknown, Group, Watch and Freeze behavior.

## Efficient implementation sequence

- [x] Remove repeated owner-bucket summation from `RevisionBuilder`.
- [x] Provide signed packed codec, overlap checks and limited transform correlation core with JVM tests.
- [ ] Add a single view-aware candidate identity to existing owner/revision storage, including GC/staleness handling.
- [ ] Add bounded snapshot capture and two/multi-observation correlation into managed target worker.
- [ ] Extend IPC and Compose dialog with per-session opt-in, explicit Int32 view and localized strings.
- [ ] Add inverse encoded write with optimistic preconditions and bounded revert journal; don't silently freeze packed data.
- [ ] Verify runtime IPC, cancellation, resource accounting, search undo and concurrent guest mutations.
- [ ] Acceptance: Kingdoms & Lords coin/diamond scan, write and gameplay effect; separate trials in Heroes Lore and Dungeon Hunter 3.
- [ ] Consider portable Saved Targets, linked edits and any additional encoding families only after game evidence and target identity contracts are reviewed.

Success statuses must distinguish matched transform, confirmed immediate readback and observed gameplay change. No requirement to persist game progress or modify RMS.

See [AGENTS.md](../AGENTS.md), [development.md](development.md) and [agent-workflow.md](agent-workflow.md).
