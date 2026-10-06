# JL-Mod Plus Agent Guidelines

This file contains guidance every agent needs. Keep task-specific instructions and transient project state in dedicated documents; read only those relevant to the current task.

## Core priorities

- Follow the user's explicit task and constraints first. Infer routine implementation details from current evidence, carry forward authorization already given, and carry the requested work to completion. Ask only when a missing decision materially affects correctness, compatibility, user-visible behavior, scope, or authority and cannot be resolved from available evidence.
- Keep enduring repository guidance model- and provider-agnostic. State goals, evidence, constraints, authority, and success criteria; keep transient model names, reasoning settings, and tool-routing choices in task handoffs unless a repository contract genuinely depends on them.
- Preserve required behavior and emulator compatibility, not accidental implementation structure. Within the requested scope, refactor or replace flawed structure when that produces a simpler, more coherent model while meeting correctness, compatibility, data-safety, and relevant performance requirements.
- Do not treat the current architecture as a product requirement. If a materially better solution requires changing a durable product contract or substantially widening the authorized scope, first prepare a concrete, reviewable proposal with the problem evidence and material compatibility or migration impact, then obtain user approval. If the user's request already authorizes redesign or replacement, do not ask again merely because the solution is novel, broad internally, or different from the existing structure.
- User requirements and explicit project contracts define intended behavior. Applicable specifications define the default compatibility contract except where JL-Mod Plus deliberately preserves verified compatibility behavior. Current source, configuration, workflows, and tests describe the current implementation and provide evidence; they are not requirements merely because they exist. Treat history as context unless the task specifically requires it.
- Keep changes focused on the problem. Do not mix unrelated cleanup, dependency or toolchain churn, or speculative architecture into a scoped change.

## Engineering approach

Ground the solution in current evidence before committing to a production fix. Bounded, reversible experiments are appropriate when needed to establish the cause; remove temporary diagnostic changes before handoff unless they serve an ongoing requirement.

- Solve the observed problem and realistic failure modes implied by actual system boundaries. Do not add machinery for hypothetical scenarios without evidence unless the mitigation is simple, natural to the design, and materially reduces credible risk.
- Understand the relevant invariants, state and behavior owners, lifecycle and concurrency boundaries, persistence/data ownership, compatibility contracts, and affected behavior from current evidence.
- Compare plausible approaches only to the depth the change warrants. Choose the simplest design that meets the required contracts and operating constraints; explain material tradeoffs rather than seeking superiority on every dimension.
- Prefer fewer independent states, sources of truth, special cases, abstractions, and moving parts. An abstraction or new layer should pay for itself by enforcing a real boundary, removing meaningful duplication or complexity, or enabling required correctness.
- Simplicity is the result of resolving complexity, not omitting necessary behavior. Preserve required lifecycle, concurrency, persistence, failure, compatibility, and recovery paths; do not narrow an authorized outcome or skip difficult work merely to make the implementation smaller.
- Fix the underlying model or invariant rather than layering workarounds over symptoms. Restructure flawed code when necessary; do not preserve a bad boundary merely to minimize line count or diff size.
- Proceed when evidence supports the approach and no unresolved question is likely to change the design or its correctness. Further investigation or review should resolve a concrete uncertainty, not satisfy a quota of alternatives or review passes.
- Review the result as if reviewing someone else's PR. Check correctness, failure paths, scope, unnecessary complexity, and whether a reasonable simpler approach meets the same contracts. For nontrivial changes, briefly report the cause, design rationale, and validation evidence.

## Working in this repository

- For analysis, review, or planning requests, inspect and report; implement only when requested. For change, build, or fix requests, complete the scoped work and relevant non-destructive validation without pausing for routine local decisions.
- Before an action needing additional authorization, prepare the concrete, reviewable result. Do not infer permission for destructive operations, publishing, or merging from a request for local edits.
- Use the current integration branch as the base for unrelated work, develop on a dedicated branch, and integrate through a PR unless the user explicitly requests otherwise. Preserve unrelated work in the checkout.
- Use validation proportional to the change. When designing or adding tests, follow [Testing strategy](docs/development.md#testing-strategy). Once relevant checks pass, broaden or repeat validation only when new changes, failures, or unresolved risks justify it. Inspect the final diff and report what passed and what remains unverified. Do not run `clean` routinely.
- If screenshot comparison CI fails, review the exact `before` / `after` / `diff` candidate artifact. If the change is intentional, use the owner-only `/jlmod-promote-screenshots` path with the exact current-HEAD run/artifact provenance; never regenerate or accept screenshots blindly. Promotion must be HEAD-leased, and final Android CI must pass.
- Preserve inherited rights and attribution notices. Do not make ownership or licensing assumptions.
- Preserve the surrounding Markdown wrapping style and avoid reflow-only changes.
- Use only task-relevant repository skills. Project policy and the matching interpretation in `.agents/UPSTREAM.md` override conflicting generic skill recipes; follow [Skill routing](docs/agent-workflow.md#repository-skills).

## Read when relevant

| Task | Guidance |
| --- | --- |
| Repository history or provenance investigation; source-language choice; performance-sensitive code; licensing; Git, PR, CI, or versioning | Read only the matching section of [Agent development workflow](docs/agent-workflow.md) |
| Build and test commands | [Build and validation](docs/development.md) |
| App-owned UI architecture, adaptation, or Navigation 3 | [App-owned UI development](docs/app-ui-development.md) |
| View/Compose/Java ME ownership boundary or UI migration | [UI ownership](docs/ui-ownership-map.md) |
| Localization, semantic UI copy quality, translation, or locale/resource identity | [Localization contract](docs/localization.md) |
| UI copy styling, capitalization, typography, color, or popup/dialog presentation | [UI copy and presentation style](docs/ui-copy-style.md) |
| Runtime host UI, Java ME Screen soft keys, or host/LCDUI boundary | [Runtime UI](docs/runtime-ui.md) |
| Java ME APIs, JSRs, vendor APIs, or guest compatibility | [Java ME compatibility](docs/java-me-compatibility.md) |
| Preset/config ownership, installed identity, cross-process preset access, or runtime storage identity | [Preset, configuration, and installed-identity contracts](docs/preset-config-contract.md) |
| Library Room schema or Library-owned data changes | [Library data and schema contracts](docs/library-data-contract.md) |
| Third-party provenance or notices | [Third-party notices](THIRD_PARTY_NOTICES.md) and [NOTICE](NOTICE) |
