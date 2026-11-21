/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * System fallback for host feedback before a Compose notice host is available, or while an
 * Android dialog obscures it. Foreground app screens should use TransientNoticeHost instead.
 */
object ThemedToast {
    @JvmStatic
    fun show(context: Context, @StringRes messageRes: Int, duration: Int) {
        show(context, context.getString(messageRes), duration)
    }

    @JvmStatic
    fun show(context: Context, message: CharSequence, duration: Int) {
        Toast.makeText(context, message, duration).show()
    }
}
