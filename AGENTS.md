# JL-Mod Plus Agent Guidelines

This file contains guidance every agent needs. Keep task-specific instructions and transient project state in dedicated documents; read only those relevant to the current task.

## Core priorities

- Follow the user's explicit task and constraints first. Carry forward authorization already given; ask only when a missing decision materially affects correctness, compatibility, scope, or authority.
- Preserve required behavior and emulator compatibility, not accidental implementation structure. Within the requested scope, refactor or replace flawed structure when that produces a simpler, more coherent model without weakening correctness, compatibility, data safety, performance, or maintainability.
- User requirements and explicit project contracts define intended behavior. Applicable specifications define the default compatibility contract except where JL-Mod Plus deliberately preserves verified compatibility behavior. Current source, configuration, workflows, and tests describe the current implementation and provide evidence; they are not requirements merely because they exist. Treat history as context unless the task specifically requires it.
- Keep changes focused on the problem. Do not mix unrelated cleanup, dependency or toolchain churn, or speculative architecture into a scoped change.
- Work CLI-first; Android Studio is not required unless the user asks for it.

## Think Before Coding. Simplify First.

Establish the root cause and simplest correct model before changing code. Do not settle for the first plausible solution.

- Understand the relevant invariants, state and behavior owners, lifecycle and concurrency boundaries, persistence/data ownership, compatibility contracts, and affected behavior from current evidence.
- Compare plausible approaches only to the depth the change warrants. Choose the technically strongest solution that meets the requirements with the least accidental complexity.
- Prefer fewer independent states, sources of truth, special cases, abstractions, and moving parts. An abstraction or new layer should pay for itself by enforcing a real boundary, removing meaningful duplication or complexity, or enabling required correctness.
- Fix the underlying model or invariant rather than layering workarounds over symptoms. Restructure flawed code when necessary; do not preserve a bad boundary merely to minimize line count or diff size.
- Prefer the simpler design when correctness, compatibility, failure semantics, data safety, performance, and maintainability remain at least as strong.
- Review the result as if reviewing someone else's PR. Verify that it is correct, necessary, appropriately scoped, and simpler than reasonable alternatives.

## Working in this repository

- For analysis, review, or planning requests, inspect and report; implement only when requested. For change, build, or fix requests, complete the scoped work and relevant non-destructive validation without pausing for routine local decisions.
- Before an action needing additional authorization, prepare the concrete, reviewable result. Do not infer permission for destructive operations, publishing, or merging from a request for local edits.
- Use the current integration branch as the base for unrelated work, develop on a dedicated branch, and integrate through a PR unless the user explicitly requests otherwise. Preserve unrelated work in the checkout.
- Use validation proportional to the change. When designing or adding tests, follow [Testing strategy](docs/development.md#testing-strategy). Once relevant checks pass, broaden or repeat validation only when new changes, failures, or unresolved risks justify it. Inspect the final diff and report what passed and what remains unverified. Do not run `clean` routinely.
- Preserve inherited rights and attribution notices. Do not make ownership or licensing assumptions.
- Preserve the surrounding Markdown wrapping style and avoid reflow-only changes.
- Use only task-relevant repository skills; follow [Skill routing](docs/agent-workflow.md#repository-skills) for precedence, project interpretations, and prerequisites.

## Read when relevant

| Task | Guidance |
| --- | --- |
| Repository history or provenance investigation; source-language choice; performance-sensitive code; licensing; Git, PR, CI, or versioning | Read only the matching section of [Agent development workflow](docs/agent-workflow.md) |
| Build and test commands | [Build and validation](docs/development.md) |
| App-owned UI architecture, adaptation, or Navigation 3 | [App-owned UI development](docs/app-ui-development.md) |
| View/Compose/Java ME ownership boundary or UI migration | [UI ownership](docs/ui-ownership-map.md) |
| UI copy, localization, typography, color, or popup/dialog presentation | [UI copy and presentation style](docs/ui-copy-style.md) |
| Runtime host UI, Java ME Screen soft keys, or host/LCDUI boundary | [Runtime UI](docs/runtime-ui.md) |
| Java ME APIs, JSRs, vendor APIs, or guest compatibility | [Java ME compatibility](docs/java-me-compatibility.md) |
| Preset/config ownership, installed identity, cross-process preset access, or runtime storage identity | [Preset, configuration, and installed-identity contracts](docs/preset-config-contract.md) |
| Library Room schema or Library-owned data changes | [Library data and schema contracts](docs/library-data-contract.md) |
| Third-party provenance or notices | [Third-party notices](THIRD_PARTY_NOTICES.md) and [NOTICE](NOTICE) |
