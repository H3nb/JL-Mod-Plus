# Documentation map

This is a navigation index, not an additional source of behavior or architecture rules. Start with [AGENTS.md](../AGENTS.md) for repository-wide agent guidance; read only documents relevant to the task. When a dated investigation and current implementation disagree, check the owning contract, source, and tests rather than applying a historical proposal.

## Maintained development guidance

- [Build, tests, and validation](development.md); [agent workflow](agent-workflow.md); [Android skill routing](android-skills.md).
- [App-owned UI development](app-ui-development.md); [UI ownership boundaries](ui-ownership-map.md); [UI copy and presentation](ui-copy-style.md).
- [Localization contract](localization.md) and [translation workflow](localization-workflow.md).

## Current behavior and data contracts

- **Library/config:** [Library data and migrations](library-data-contract.md); [preset, configuration, and installed identity](preset-config-contract.md).
- **Runtime and input:** [runtime UI](runtime-ui.md); [virtual control skin sets](control-skin-sets.md); [static SVG artwork](svg-artwork.md); [emulation speed](emulation-speed.md); [performance overlay](performance-overlay.md); [Memory Editor candidate identity](memory-editor-identity.md).
- **Guest APIs/media:** [Java ME compatibility](java-me-compatibility.md); [audio runtime](audio-runtime.md); [MMAPI camera](mmapi-camera.md); [MMAPI recording](mmapi-recording.md).
- **Diagnostics:** [diagnostic bundles](diagnostic-bundles.md).

## Historical research, implementation records, and plans

These preserve measurements, rejected alternatives, migration rationale, or prior validation. They are not automatically current instructions; their own status and subsequent outcomes determine how to use them.

- **Performance (2026-10-08):** [app-wide proposal](app-performance-proposal-2026-10-08.md); [app UI investigation](app-ui-performance-2026-10-08.md); [Settings qualification](settings-performance-2026-10-08.md); [interaction analysis](interaction-performance-analysis-2026-10-08.md); [proposed interaction design](interaction-performance-design-2026-10-08.md); [trial outcomes and accepted/rejected candidates](interaction-performance-trials-2026-10-08.md).
- **Camera:** [Java ME camera research and decision rationale](camera-java-me-history.md); [PR 175 device investigation](camera-pr175-device-investigation.md). Use the active [MMAPI camera contract](mmapi-camera.md) for implementation.
- **Migration and prior implementation:** [namespace porting](UPSTREAM_PORTING.md); [VFS/permissions implementation plan and later review decisions](virtual-filesystem-permissions-plan.md); [MIDP Stage C plan](audits/midp-stage-c-implementation-plan.md). Check present source and relevant tests before treating old acceptance gates as unfinished work.
