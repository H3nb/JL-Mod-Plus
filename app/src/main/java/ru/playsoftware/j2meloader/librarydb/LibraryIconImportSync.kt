/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Keep converted/icon.png consistent with the durable config-owned override after bundle restore. */
@Throws(IOException::class)
internal fun LibraryIconOverride.reapplyPersistedOverride(
    emulatorDir: File,
    storageKey: String,
): Long {
    val custom = persistedOverrideFile(emulatorDir, storageKey)
    if (!custom.isFile) return resetToOriginal(emulatorDir, storageKey)

    val target = effectiveIconFile(emulatorDir, storageKey)
    val parent = target.parentFile ?: throw IOException("Imported icon target has no parent directory")
    if (!parent.isDirectory && !parent.mkdirs()) {
        throw IOException("Unable to create imported icon directory: ${parent.absolutePath}")
    }
    val staging = File(parent, ".${target.name}.${UUID.randomUUID()}.import.tmp")
    val backup = File(parent, ".${target.name}.${UUID.randomUUID()}.import.bak")
    var movedExisting = false
    try {
        custom.inputStream().use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        if (target.exists()) {
            if (!target.renameTo(backup)) throw IOException("Unable to preserve existing effective icon")
            movedExisting = true
        }
        if (!staging.renameTo(target)) {
            if (movedExisting && backup.exists()) backup.renameTo(target)
            throw IOException("Unable to publish imported icon override")
        }
        if (backup.exists()) backup.delete()
        return LibraryIconRevision.fromFile(target)
    } catch (error: Throwable) {
        if (!target.exists() && movedExisting && backup.exists()) backup.renameTo(target)
        throw error
    } finally {
        staging.delete()
        if (target.exists()) backup.delete()
    }
}
