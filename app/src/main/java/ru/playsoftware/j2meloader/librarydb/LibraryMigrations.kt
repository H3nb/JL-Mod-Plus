/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Single registry for every supported Library schema transition.
 *
 * Keep migrations adjacent (N -> N+1) and never delete a historical migration while that schema
 * can exist in a released/debug build. Structural changes must preserve user-owned Library state;
 * the workdir scanner is recovery for reconstructible catalog data, not a destructive migration.
 */
object LibraryMigrations {
    const val FIRST_SUPPORTED_VERSION = 1

    /**
     * Migration-baseline checkpoint. Schema v2 intentionally has the same table shape as v1 so
     * already-created v1 databases exercise the real migration plumbing before later schema edits.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) = Unit
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /** Fails tests immediately when SCHEMA_VERSION is bumped without a complete adjacent chain. */
    internal fun missingAdjacentTransitions(targetVersion: Int): List<Pair<Int, Int>> {
        if (targetVersion <= FIRST_SUPPORTED_VERSION) return emptyList()
        val available = ALL.map { it.startVersion to it.endVersion }.toSet()
        return (FIRST_SUPPORTED_VERSION until targetVersion)
            .map { it to it + 1 }
            .filterNot(available::contains)
    }
}
