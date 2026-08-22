/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes

/** App-owned Toast surface so transient feedback follows the active light/dark theme. */
object ThemedToast {
    @JvmStatic
    fun show(context: Context, @StringRes messageRes: Int, duration: Int) {
        show(context, context.getString(messageRes), duration)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun show(context: Context, message: CharSequence, duration: Int) {
        val density = context.resources.displayMetrics.density
        val text = TextView(context).apply {
            this.text = message
            setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.WHITE))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(
                (16 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                (10 * density).toInt(),
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(resolveColor(context, android.R.attr.colorBackground, Color.DKGRAY))
                setStroke(
                    (1 * density).toInt().coerceAtLeast(1),
                    resolveColor(context, android.R.attr.textColorSecondary, Color.GRAY)
                        .withAlpha(0x66),
                )
            }
            elevation = 4 * density
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
        }
        Toast(context).apply {
            view = text
            this.duration = duration
        }.show()
    }

    private fun resolveColor(context: Context, attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) {
            when {
                value.resourceId != 0 -> runCatching {
                    context.getColor(value.resourceId)
                }.getOrDefault(fallback)
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
                    value.data
                else -> fallback
            }
        } else {
            fallback
        }
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
}
