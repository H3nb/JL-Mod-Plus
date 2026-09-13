/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.config

/** Stable persisted identifiers for the host background strategy. */
object BackgroundMode {
    const val CUSTOM = 0
    const val THEME = 1
    const val IMMERSIVE = 2

    @JvmStatic
    fun sanitize(mode: Int): Int = when (mode) {
        THEME, IMMERSIVE -> mode
        else -> CUSTOM
    }
}
