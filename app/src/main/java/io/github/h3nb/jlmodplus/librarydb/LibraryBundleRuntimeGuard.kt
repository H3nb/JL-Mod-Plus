/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.librarydb

import android.content.Context
import io.github.h3nb.jlmodplus.runtime.RuntimeStorageLease
import java.io.File
import java.io.IOException

/** Hold each runtime reservation for the complete snapshot or restore, not merely a preflight. */
internal object LibraryBundleRuntimeGuard {
    fun <T> withClosedMidlets(
        context: Context,
        apps: List<Pair<File, String>>,
        operation: () -> T,
    ): T {
        val leases = ArrayList<RuntimeStorageLease>(apps.size)
        try {
            apps.distinctBy { (root, key) -> root.canonicalPath to key }
                .sortedBy { (root, key) -> root.canonicalPath + "/" + key }
                .forEach { (root, key) ->
                    val converted = File(File(root, "converted"), key)
                    val lease = RuntimeStorageLease.acquire(context.filesDir, converted)
                        ?: throw IOException("Close the running MIDlet before exporting or importing its data")
                    leases += lease
                }
            return operation()
        } finally {
            leases.asReversed().forEach(RuntimeStorageLease::close)
        }
    }
}
