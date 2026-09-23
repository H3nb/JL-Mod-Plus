# JL-Mod Plus Agent Guidelines

This file contains guidance every agent needs. Keep task-specific instructions and transient project state in dedicated documents; read only those relevant to the current task.

## Core priorities

- Follow the user's explicit task and constraints first. Carry forward authorization already given; ask only when a missing decision materially affects correctness, compatibility, scope, or authority.
- Preserve existing correct behavior and emulator compatibility unless the task requires a change.
- Treat current source, Gradle configuration, workflows, tests, and verified specifications as the source of truth. Use historical artifacts only when the task requires them.
- Keep changes focused on the problem. Do not add unrelated cleanup, dependencies, frameworks, or abstractions.
- Work CLI-first; Android Studio is not required unless the user asks for it.

## Think Before Coding. Simplify First.

Think deeply before changing code. Do not settle for the first plausible solution. Find the simplest correct model, then make the code reflect it.

- Understand the root cause, relevant invariants, state and behavior owners, lifecycle and concurrency boundaries, compatibility contracts, and affected behavior from current code and evidence.
- Compare plausible approaches at the depth the change warrants. Choose the technically strongest solution that meets the requirements without adding sophistication for its own sake.
- Reduce accidental complexity: independent states, sources of truth, special cases, abstractions, and moving parts. Simplicity is not measured by line count or diff size.
- Fix the underlying model or invariant rather than layering workarounds over symptoms. Restructure flawed code when necessary, while keeping the change within the task's scope.
- Challenge hidden assumptions, regressions, unnecessary state, duplicated responsibility, lifecycle or concurrency hazards, compatibility, and relevant edge cases.
- Review the result as if reviewing someone else's PR. Verify that it is correct, necessary, appropriately scoped, and simpler than reasonable alternatives.

## Working in this repository

- For analysis, review, or planning requests, inspect and report; implement only when requested. For change requests, complete the scoped work and relevant validation without pausing for routine local decisions.
- Before an action needing additional authorization, prepare the concrete, reviewable result. Do not infer permission for destructive operations, publishing, or merging from a request for local edits.
- Use the current integration branch as the base for unrelated work, develop on a dedicated branch, and integrate through a PR unless the user explicitly requests otherwise. Preserve unrelated work in the checkout.
- Use validation proportional to the change; avoid tests for reversible, low-impact edits that only mirror the implementation. Once relevant checks pass, inspect the final diff and report what passed and what remains unverified. Do not run `clean` routinely.
- Preserve inherited rights and attribution notices. Do not make ownership or licensing assumptions.
- Do not manually wrap Markdown prose; keep each paragraph and list item on one source line.
- When a repository skill matches the task, read its `SKILL.md` and relevant references. User instructions, this file, and verified project behavior take precedence over generic skill guidance. A skill prerequisite does not authorize unrelated work.

## Read when relevant

| Task | Guidance |
| --- | --- |
| Implementation decisions, history, source language, performance, licensing, Git, PRs, or CI | [Agent development workflow](docs/agent-workflow.md); use the relevant sections only |
| Build and test commands | [Build and validation](docs/development.md) |
| App-owned UI, adaptation, navigation, or UI migration | [App-owned UI development](docs/app-ui-development.md), [UI ownership](docs/ui-ownership-map.md), and [UI copy style](docs/ui-copy-style.md) |
| Java ME APIs, JSRs, vendor APIs, or guest compatibility | [Java ME compatibility](docs/java-me-compatibility.md); see [runtime UI](docs/runtime-ui.md) for host and LCDUI boundaries |
| Library Room schema changes | [Library database](app/src/main/java/io/github/h3nb/jlmodplus/librarydb/LibraryDatabase.kt), [migrations](app/src/main/java/io/github/h3nb/jlmodplus/librarydb/LibraryMigrations.kt), and [migration tests](app/src/test/java/io/github/h3nb/jlmodplus/librarydb/LibraryMigrationTest.kt) |
| Third-party provenance or notices | [Third-party notices](THIRD_PARTY_NOTICES.md) and [NOTICE](NOTICE) |
| Android task-specific skill | [Skill routing](docs/agent-workflow.md#repository-skills), the matching `.agents/skills/<name>/SKILL.md`, and [vendored skill provenance](.agents/UPSTREAM.md) when needed |
