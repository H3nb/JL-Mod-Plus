/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File

/** Lightweight invalidation token. Call from scanner/installer workers, never from composition. */
object LibraryIconRevision {
    @JvmStatic
    fun fromFile(iconFile: File): Long {
        if (!iconFile.isFile) return 0L
        return (iconFile.lastModified() * 31L) xor iconFile.length()
    }
}
