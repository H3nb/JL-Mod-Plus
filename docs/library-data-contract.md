# Library data and schema contracts

This document records durable data-ownership and migration invariants for the workdir-scoped Room Library. Current schema classes and migration mechanics may evolve; these data-safety rules remain the boundary.

## Data ownership

- `converted/`, `configs/`, and `data/` own installed payload, configuration, and save/runtime filesystem state.
- The Room Library database owns Library-only state that is not fully reconstructible from those directories, including user metadata and feature state such as Favorites, Collections, play statistics, and reconciliation records.
- A missing database may rebuild reconstructible catalog information from the workdir, but database deletion is not a migration or repair strategy for Library-owned state.
- Never delete or rewrite installed/config/save files merely to repair a Room schema problem.

## Schema evolution

- Do not enable destructive migration for the Library database.
- Keep committed historical schema snapshots needed by supported upgrades and maintain an explicit migration chain to the current schema.
- Each logical schema revision increments the schema version and supplies the required adjacent migration. Preserve meaningful user-owned state deterministically when columns, tables, or features change.
- Account for the project's minimum Android/platform SQLite behavior when writing migration SQL; do not assume newer SQLite DDL is universally available.
- Remove a historical migration only when the project intentionally drops every upgrade path that can originate from that schema.

## Validation

- Cover the immediately previous schema to current migration and keep historical-schema-to-current validation for the supported chain.
- Assert preservation or deterministic transformation of every Library-owned field affected by a migration.
- Use Android instrumentation when the migration depends on platform SQLite behavior that bundled/local tests cannot establish.
- A schema change is not complete merely because a fresh database opens successfully.

See `LibraryDatabase.kt`, `LibraryMigrations.kt`, and `LibraryMigrationTest.kt` for the current implementation and executable migration coverage.
