/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.content.Context
import java.io.File

/**
 * Tiny cross-process state markers for the MIDlet menu and :memory_editor process.
 *
 * SharedPreferences are deliberately avoided because their in-memory caches are not coherent
 * across Android processes. Marker files make these two booleans observable without adding
 * another Binder service to the compatibility-sensitive MIDlet process.
 */
internal object MemoryEditorOverlayState {
    private const val ACTIVE = ".memory_editor_overlay_active"
    private const val VISIBLE = ".memory_editor_overlay_visible"

    fun isActive(context: Context): Boolean = marker(context, ACTIVE).isFile

    fun isVisible(context: Context): Boolean = marker(context, VISIBLE).isFile

    fun markActive(context: Context, active: Boolean) = set(marker(context, ACTIVE), active)

    fun markVisible(context: Context, visible: Boolean) = set(marker(context, VISIBLE), visible)

    private fun marker(context: Context, name: String): File = File(context.noBackupFilesDir, name)

    private fun set(file: File, enabled: Boolean) {
        if (enabled) {
            runCatching {
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
            }
        } else {
            runCatching { file.delete() }
        }
    }
}
