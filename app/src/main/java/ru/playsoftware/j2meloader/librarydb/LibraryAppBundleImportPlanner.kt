/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.IOException

/** The import surface that should own the restore workflow for a parsed bundle. */
internal enum class LibraryBundleImportMode {
    Single,
    Batch,
}

internal data class LibraryBundleImportItem(
    val ordinal: Int,
    val app: BundleApp,
)

internal data class LibraryBundleImportPlan(
    val formatVersion: Int,
    val mode: LibraryBundleImportMode,
    val items: List<LibraryBundleImportItem>,
    val legacyAssurance: Boolean,
) {
    val isBatch: Boolean
        get() = mode == LibraryBundleImportMode.Batch
}

/**
 * Pure boundary between ZIP parsing and UI/install routing. Keeping this decision independent of
 * Android makes the one-app and multi-app paths use the same manifest contract and deterministic
 * ordering without opening or mutating user data.
 */
internal object LibraryAppBundleImportPlanner {
    @Throws(IOException::class)
    fun plan(parsed: ParsedBundle): LibraryBundleImportPlan {
        if (parsed.apps.isEmpty()) throw IOException("App bundle has no importable apps")
        val ordered = if (parsed.legacyAssurance) {
            parsed.apps
        } else {
            parsed.apps.sortedBy { it.bundleId }
        }
        return LibraryBundleImportPlan(
            formatVersion = parsed.formatVersion,
            mode = if (ordered.size == 1) {
                LibraryBundleImportMode.Single
            } else {
                LibraryBundleImportMode.Batch
            },
            items = ordered.mapIndexed { index, app ->
                LibraryBundleImportItem(ordinal = index, app = app)
            },
            legacyAssurance = parsed.legacyAssurance,
        )
    }
}
