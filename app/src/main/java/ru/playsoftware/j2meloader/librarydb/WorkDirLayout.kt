/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.io.IOException

/** Paths are read-only; only the operation that needs a directory may create it. */
object WorkDirLayout {
    @JvmStatic fun converted(root: File): File = File(root, "converted")

    @JvmStatic fun requireStorageKey(key: String) {
        require(key.isNotBlank() && key != "." && key != ".." &&
            !key.contains('/') && !key.contains('\\') && !key.contains('\u0000') &&
            !LibraryInstallRecovery.isReservedStorageKey(key)) { "Unsafe storageKey: $key" }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun requireConverted(root: File) {
        val directory = converted(root)
        if (!directory.isDirectory || directory.list() == null) {
            throw IOException("Installed applications directory is unavailable: $directory")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareConverted(root: File, hasInstalledApps: Boolean): File {
        val directory = converted(root)
        if (hasInstalledApps) requireConverted(root)
        if (!root.isDirectory || !root.canWrite()) {
            throw IOException("Work directory is unavailable: $root")
        }
        if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) {
            throw IOException("Unable to create installed applications directory: $directory")
        }
        requireConverted(root)
        return directory
    }
}
