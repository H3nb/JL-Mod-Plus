/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

/** Component selection shared by single- and multi-app bundle exports. */
data class LibraryBundleComponents(
    val configuration: Boolean = true,
    val saveData: Boolean = true,
    val savedVariables: Boolean = true,
    val layoutSkin: Boolean = false,
    val controlSkin: Boolean = false,
)
