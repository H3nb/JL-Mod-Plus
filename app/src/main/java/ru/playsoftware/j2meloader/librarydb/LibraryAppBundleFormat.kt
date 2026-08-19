/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

/**
 * Versioned envelope for exported app bundles. Writers always emit [CURRENT_VERSION]; readers keep
 * unversioned PR2 preview bundles readable only as the legacy v0 compatibility format.
 */
internal object LibraryAppBundleFormat {
    const val MANIFEST_ENTRY = "bundle.json"
    const val CURRENT_VERSION = 1

    fun manifestBytes(): ByteArray =
        "{\"formatVersion\":$CURRENT_VERSION}\n".toByteArray(Charsets.UTF_8)
}
