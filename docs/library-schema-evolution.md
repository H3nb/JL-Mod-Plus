# Library database schema evolution

The Library database is workdir-scoped and contains both reconstructible catalog data and user-owned state. Schema evolution must therefore be explicit and lossless by default.

## Data ownership

- `converted/`, `configs/`, and `data/` remain authoritative for installed app payload/config/save files.
- The Room database is authoritative for Library-only state such as Favorites, custom metadata, Collections, play statistics, and reconciliation receipts.
- Deleting the database file is **not** a migration strategy. A missing database can rebuild the install catalog from the workdir, but Library-only state cannot be recovered from the filesystem unless a separate backup/export exists.
- Never mutate or delete app/config/save files to repair a Room schema problem.

## Versioning contract

1. Keep every exported schema snapshot under `app/schemas/`.
2. Increment `LibraryDatabase.SCHEMA_VERSION` exactly once for each logical schema revision.
3. Add an adjacent migration `N -> N+1` to `LibraryMigrations.ALL`.
4. Never remove a historical migration while a build containing that schema may still exist.
5. Do not enable `fallbackToDestructiveMigration()` for the Library database.
6. CI/unit tests must validate every committed historical schema against the latest schema.

Schema v2 is intentionally identical in table shape to v1. It is the migration-baseline checkpoint: existing v1 databases exercise the real migration path before later structural changes are introduced.

## Adding data

For a nullable column, adding the column directly is usually sufficient. For a non-null column, define a deterministic default/backfill for existing rows before the new constraint becomes authoritative.

New feature tables should have clear ownership and foreign-key deletion behavior. Do not make unrelated user state depend on a feature table that may later be removed.

## Renaming, removing, or merging columns/features

The production database uses Android's platform SQLite driver and supports old Android versions. Do not assume modern SQLite `DROP COLUMN`/`RENAME COLUMN` support is available everywhere.

For destructive structural edits, prefer a transactional table rebuild:

1. create the replacement table with the target schema;
2. copy/transform the fields that remain meaningful;
3. validate or normalize merged values deterministically;
4. drop the old table;
5. rename the replacement table;
6. recreate indexes and foreign keys required by the target schema.

Room executes a `Migration` inside its migration transaction, so a failed migration should leave the previous database intact rather than half-migrated.

When a feature is removed, only data owned exclusively by that removed feature may be discarded. If another active feature consumes the same meaning, migrate/merge the data instead of dropping it.

## Testing requirements

`LibraryMigrationTest` enforces the adjacent migration chain and uses Room 3 `MigrationTestHelper` with the committed schema snapshots.

For every schema revision:

- add a migration test that starts from the immediately previous schema and asserts transformed values;
- keep the generic every-historical-schema-to-latest validation green;
- cover user-owned state touched by the migration (Favorites, metadata, Collections, stats, receipts, or future equivalents);
- verify a fresh database still boots and indexes the workdir correctly.

A schema version bump without its migration should fail tests before it can become a runtime upgrade problem.
