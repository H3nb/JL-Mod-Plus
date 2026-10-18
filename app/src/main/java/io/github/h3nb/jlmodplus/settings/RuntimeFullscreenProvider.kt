/* Licensed under the Apache License, Version 2.0 (the "License"); */
package io.github.h3nb.jlmodplus.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR

/** Owns the global preference in the main process; the runtime never writes its cached preferences. */
class RuntimeFullscreenProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Internal fullscreen preference only" }
        val owner = requireNotNull(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(owner)
        when (method) {
            READ -> Unit
            WRITE -> {
                require(extras?.containsKey(VALUE) == true)
                val oldToolbar = preferences.getBoolean(PREF_TOOLBAR, false)
                val toolbar = !extras.getBoolean(VALUE)
                if (oldToolbar != toolbar) {
                    if (!preferences.edit().putBoolean(PREF_TOOLBAR, toolbar).commit()) {
                        // commit updates the memory cache even on disk failure. Restore that cache
                        // before any following read can report the failed request as saved.
                        preferences.edit().putBoolean(PREF_TOOLBAR, oldToolbar).commit()
                        throw IllegalStateException("Could not persist fullscreen preference")
                    }
                    owner.contentResolver.notifyChange(uri(owner.packageName), null)
                }
            }
            else -> throw IllegalArgumentException("Unknown fullscreen method")
        }
        return Bundle().apply { putBoolean(VALUE, !preferences.getBoolean(PREF_TOOLBAR, false)) }
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
        const val WRITE = "write"
        const val VALUE = "fullscreen"
        fun uri(packageName: String): Uri = Uri.parse("content://$packageName.runtime-fullscreen")
    }
}
