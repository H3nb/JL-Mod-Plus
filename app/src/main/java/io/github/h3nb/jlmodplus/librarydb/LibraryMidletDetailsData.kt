/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

/** Optional installed-source information; absence is not inferred as an empty declaration. */
data class LibraryMidletDetailsData(
    val descriptorAttributes: Map<String, String>?,
    val retainedJarSizeBytes: Long?,
    val collectionNames: List<String>,
)
