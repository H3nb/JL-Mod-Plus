# Saved Variables — R&D reference

**Status (2026-11-02): GO to start production implementation on a fresh branch from current `alpha`.**
**Do not merge PR #215; it is a disposable Draft research branch.** It is *not* a production release and its 100+ commits, experimental CI, and service classes are not the production architecture.

## Start here

**Read [PRODUCTION_HANDOFF.md](PRODUCTION_HANDOFF.md) before copying any R&D code.** It is the canonical *current* research decision and production transfer checklist, including nonnegotiable invariants, known prototype mistakes, scope, and acceptance gates.

The old 415-line chronological README has been preserved unchanged in substance as [EVIDENCE_ARCHIVE.md](EVIDENCE_ARCHIVE.md). Its historical claims are **not normative**: several early sections were superseded by direct Saved bindings, exact obfuscated field matching, corrected normal-exit tests, and later user testing. Do not use `EVIDENCE_ARCHIVE.md` as the first source for architecture decisions.

Read the *current* `alpha` `AGENTS.md` and applicable `docs/` contracts for implementation. Research does not replace production ownership, installed identity, Memory Editor, localization or test policies.

## What has been established

- A versioned **typed root-relative structural locator**, not owner handles, values, reflection field slots or display strings, can resolve supported memory fields in a new MIDlet runtime without Search.
- Direct, session-only **Saved** binding and user-requested checked **Read/Edit** can work without Watch. The user's manual testing found multiple games working across sessions.
- Field lookup must include declaring class, field name, exact type and staticness. The old `Class.getDeclaredField(name)` lookup failed on obfuscated duplicate-name fields; real testing confirmed the corrected implementation fixed that issue.
- Live owner/root-to-field ancestry must be verified before a mutation; when a referenced instance or array is replaced, the old binding cannot silently follow it.
- Per-install config persistence can be fenced by captured installed identity + canonical workdir, source-JAR SHA-256, chosen MIDlet entry, one main-process writer and atomic file publication.
- Converted-JAR/JVM/Android API 23 and 35 experiments and physical device reports provide substantial feasibility evidence, **not a production guarantee or a semantic-identity proof**.

**Supported research examples:** direct static primitive fields, rooted primitive instance fields; narrow direct-static primitive array elements and rooted `Object[]` intermediate hops that were already exercised. The maintained scope is **not** generic persistent array/heap scanning. Defer any new untraced/multidimensional array support.

## Design constraints / mistakes to avoid

- **No Watch dependency** for Saved. The early `researchBindFieldLocator` Watch route was superseded by direct Saved bindings; don't copy both.
- **No value-based rebind** or selection by matching field name alone, ordinal owner number, array #ID, or JVM reflection enumeration order.
- **No silent retarget** within a runtime, even if the new object has the same shape/value. Cross-session structural matching also does not guarantee the intended in-game character if party order changes.
- **No auto-edit or auto-freeze** on load, resolve or bundle import.
- **No second memory scanner, new Room table, redundant service, or generic abstraction** without evidence. Existing main process owns persistent data; guest process owns live objects; controller owns UI state.
- **No blind prototype copy.** The R&D service uses JSON-over-AIDL, the controller can leave stale callbacks, runtime bindings are not released by Delete, and tab open can make up to 64 per-record Binder calls. Production must close these observed gaps.
- **No automatic inclusion in selectable Export Bundle** yet. Existing full config exports may incidentally contain R&D files. Future component-only import requires explicit destination association and conflict handling.
- **No complete source merge/cherry-pick from R&D.** Reimplement only coherent verified pieces against current `alpha`, review effective diff, and retain a small set of tests for distinct failure modes.

## New production implementation direction

1. Create a new feature branch/PR from current `alpha` when authorized. Keep #215 Draft as reference, not a merge target.
2. Define a typed versioned on-disk locator and clearly owned repository/authority. Choose explicit preservation/migration behavior for R&D `saved-variables-prototype.json`; never silently discard unrelated user game/config data.
3. Implement Capture, Resolve, bind, Read/Edit, invalidation/unbind using existing Managed Memory Engine and IPC. Preserve existing Search/Refine/Watch/Freeze/Inspector semantics.
4. Add Saved tab with correct statuses, direct Edit, Save/Rename/Delete, localized copy, adaptive layout, and fenced async state.
5. Verify exact field lookup, stale owner, installation identity, lifecycle (normal exit and process death), and representative game flows. Use the smallest meaningful JVM/Android tests and real device qualification.

**Decision:** architectural R&D can end here. Resume R&D only if implementation evidence presents an uncertainty capable of materially changing this design. The outcome of the next stage is a maintainable production implementation, **not** more experimental tests or files.

## Navigation

- **Current handoff:** [PRODUCTION_HANDOFF.md](PRODUCTION_HANDOFF.md)
- **Historical results / raw evidence:** [EVIDENCE_ARCHIVE.md](EVIDENCE_ARCHIVE.md)
- **Experimental PR:** [#215](https://github.com/H3nb/JL-Mod-Plus/pull/215)
- **Normal-exit lifecycle evidence:** [API 23/35 run 41369006482](https://github.com/H3nb/JL-Mod-Plus/actions/runs/41369006482)
- **Real memory engine unit evidence:** `app/src/test/java/io/github/h3nb/jlmodplus/memory/SavedLocatorEngineResearchTest.java`
- **Active production contracts (read on current `alpha`):** `docs/memory-editor-identity.md`, `docs/preset-config-contract.md`, `docs/library-data-contract.md`, `docs/testing.md`, `docs/app-ui-development.md`, `docs/localization.md`.
