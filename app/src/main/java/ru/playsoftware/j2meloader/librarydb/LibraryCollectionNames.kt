/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.util.Locale

/** Stable collection-name identity; display casing/spacing remains stored separately in `name`. */
object LibraryCollectionNames {
    fun normalize(value: String): String = value
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}
