# Library data and schema contracts

This document records durable data-ownership and upgrade invariants for the workdir-scoped Library catalog database, currently implemented with Room. Storage technology and schema mechanics may evolve; the user-data safety contract remains the boundary.

## Data ownership

- `converted/`, `configs/`, and `data/` own installed payload, configuration, and save/runtime filesystem state.
- The Library catalog owns Library-only state that is not fully reconstructible from those directories, including user metadata and feature state such as Favorites, Collections, play statistics, and reconciliation records.
- A missing catalog may rebuild reconstructible catalog information from the workdir, but deleting or recreating the catalog is not an upgrade or repair strategy when it would discard Library-owned user state.
- Never delete or rewrite installed/config/save files merely to repair a catalog-schema problem.

## Schema evolution

- Do not use destructive schema reset/recreation as an upgrade fallback when it would discard Library-owned user state. With the current Room implementation, destructive migration fallback remains disabled.
- Keep committed historical schema snapshots needed by supported in-place upgrades and maintain an explicit migration path to the current schema.
- While Room in-place upgrades are supported, each logical schema revision increments the schema version and supplies the required migration path. Preserve meaningful user-owned state deterministically when columns, tables, or features change.
- Account for the project's minimum Android/platform SQLite behavior when writing migration SQL; do not assume newer SQLite DDL is universally available.
- Remove a historical migration only when the project intentionally drops every supported upgrade path that can originate from that schema.

## Validation

- Cover the immediately previous schema to current migration and keep historical-schema-to-current validation for the supported chain.
- Assert preservation or deterministic transformation of every Library-owned field affected by a migration.
- Use Android instrumentation when the migration depends on platform SQLite behavior that bundled/local tests cannot establish.
- A schema change is not complete merely because a fresh database opens successfully.

See `LibraryDatabase.kt`, `LibraryMigrations.kt`, and `LibraryMigrationTest.kt` for the current Room implementation and executable migration coverage.
