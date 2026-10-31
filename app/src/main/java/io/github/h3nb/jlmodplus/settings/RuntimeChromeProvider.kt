/* Licensed under the Apache License, Version 2.0 (the "License"); */
package io.github.h3nb.jlmodplus.settings

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.util.Constants.PREF_STATUSBAR
import io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR

/** Main-process owner of global runtime chrome preferences. */
class RuntimeChromeProvider : ContentProvider() {
    override fun onCreate() = true

    // Calls run on a worker/Binder thread. Each write changes only its requested preference.
    @SuppressLint("ApplySharedPref", "UseKtx")
    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Internal runtime chrome preferences only" }
        val owner = requireNotNull(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(owner)
        val key = when (method) {
            READ -> null
            WRITE_FULLSCREEN -> PREF_TOOLBAR
            WRITE_STATUS_BAR -> PREF_STATUSBAR
            else -> throw IllegalArgumentException("Unknown runtime chrome method")
        }
        if (key != null) {
            val field = if (method == WRITE_FULLSCREEN) FULLSCREEN else SHOW_STATUS_BAR
            require(extras?.containsKey(field) == true)
            val requested = extras.getBoolean(field)
            val value = if (method == WRITE_FULLSCREEN) !requested else requested
            val oldValue = preferences.getBoolean(key, false)
            if (oldValue != value) {
                if (!preferences.edit().putBoolean(key, value).commit()) {
                    // commit can update the memory cache even on disk failure.
                    preferences.edit().putBoolean(key, oldValue).commit()
                    throw IllegalStateException("Could not persist runtime chrome preference")
                }
                owner.contentResolver.notifyChange(uri(owner.packageName), null)
            }
        }
        return Bundle().apply {
            putBoolean(FULLSCREEN, !preferences.getBoolean(PREF_TOOLBAR, false))
            putBoolean(SHOW_STATUS_BAR, preferences.getBoolean(PREF_STATUSBAR, false))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = 0

    internal companion object {
        const val READ = "read"
        const val WRITE_FULLSCREEN = "writeFullscreen"
        const val WRITE_STATUS_BAR = "writeStatusBar"
        const val FULLSCREEN = "fullscreen"
        const val SHOW_STATUS_BAR = "showStatusBar"
        fun uri(packageName: String): Uri = "content://$packageName.runtime-chrome".toUri()
    }
}
