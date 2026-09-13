/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.ui

/** App-owned background tokens shared by Compose and the MIDlet host. */
object AppBackgroundColors {
    const val LIGHT_RGB: Int = 0xFAFBFC
    const val DARK_RGB: Int = 0x000000

    @JvmStatic
    fun rgb(dark: Boolean): Int = if (dark) DARK_RGB else LIGHT_RGB

    @JvmStatic
    fun argb(dark: Boolean): Int = 0xFF000000.toInt() or rgb(dark)
}
