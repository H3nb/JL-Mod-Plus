/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import ru.playsoftware.j2meloader.config.Config
import ru.playsoftware.j2meloader.util.ZipUtils
import ru.woesss.j2me.jar.Descriptor

/**
 * Owns user-selected Library icon overrides without modifying the retained JAR or source descriptor.
 *
 * The normalized durable copy lives in configs/<storageKey>/icon.custom.png so reinstalling the
 * converted payload cannot erase it. converted/<storageKey>/icon.png remains the effective canonical
 * icon consumed by the existing Library/shortcut code and is synchronized at explicit edit/reset or
 * reinstall publish points.
 */
object LibraryIconOverride {
    private const val CONVERTED_DIR = "converted"
    private const val CONFIGS_DIR = "configs"
    private const val CUSTOM_ICON_FILE = "icon.custom.png"
    private const val MAX_DECODE_DIMENSION = 2048
    private const val MAX_STORED_DIMENSION = 1024

    /** Decode a user-picked image and materialize a bounded canonical PNG in app-private cache. */
    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, source: Uri): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(source)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: throw IOException("Unable to open selected icon")
        } catch (error: SecurityException) {
            throw IOException("Selected icon is no longer readable", error)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Selected file is not a decodable image")
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DECODE_DIMENSION
        ) {
            if (sampleSize >= 1 shl 20) break
            sampleSize *= 2
        }

        val decoded = try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(source)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IOException("Unable to reopen selected icon")
        } catch (error: SecurityException) {
            throw IOException("Selected icon is no longer readable", error)
        } catch (error: OutOfMemoryError) {
            throw IOException("Selected icon is too large to decode safely", error)
        } ?: throw IOException("Selected file is not a decodable image")

        var normalized: Bitmap = decoded
        try {
            val maxDimension = maxOf(decoded.width, decoded.height)
            if (maxDimension > MAX_STORED_DIMENSION) {
                val scale = MAX_STORED_DIMENSION.toFloat() / maxDimension.toFloat()
                val width = (decoded.width * scale).toInt().coerceAtLeast(1)
                val height = (decoded.height * scale).toInt().coerceAtLeast(1)
                normalized = try {
                    decoded.scale(width, height, true)
                } catch (error: OutOfMemoryError) {
                    throw IOException("Selected icon is too large to normalize safely", error)
                }
            }

            val prepared = File.createTempFile("library-icon-", ".png", context.cacheDir)
            try {
                FileOutputStream(prepared).use { output ->
                    val encoded = try {
                        normalized.compress(Bitmap.CompressFormat.PNG, 100, output)
                    } catch (error: OutOfMemoryError) {
                        throw IOException("Selected icon is too large to encode safely", error)
                    }
                    if (!encoded) throw IOException("Unable to encode selected icon as PNG")
                    output.fd.sync()
                }
                if (!prepared.isFile || prepared.length() <= 0L) {
                    throw IOException("Normalized icon is empty")
                }
                return prepared
            } catch (error: Throwable) {
                prepared.delete()
                throw error
            }
        } finally {
            if (normalized !== decoded) normalized.recycle()
            decoded.recycle()
        }
    }

    /** Publish a prepared PNG as both the durable override and the effective canonical icon. */
    @JvmStatic
    @Throws(IOException::class)
    fun installPrepared(emulatorDir: File, storageKey: String, preparedPng: File): Long {
        requireSafeStorageKey(storageKey)
        if (!preparedPng.isFile || preparedPng.length() <= 0L) {
            throw IOException("Prepared custom icon is unavailable")
        }

        val custom = persistedOverrideFile(emulatorDir, storageKey)
        val customDir = custom.parentFile ?: throw IOException("Custom icon directory is unavailable")
        ensureDirectory(customDir)
        val previousCustom = moveAside(custom)
        try {
            copyAtomically(preparedPng, custom)
            copyAtomically(custom, effectiveIconFile(emulatorDir, storageKey))
            discardMovedAside(previousCustom)
        } catch (error: Throwable) {
            if (previousCustom != null && previousCustom.exists()) {
                if (custom.exists()) custom.delete()
                restoreMovedAside(previousCustom, custom)
            } else if (custom.exists()) {
                custom.delete()
            }
            throw error
        }
        return LibraryIconRevision.fromFile(effectiveIconFile(emulatorDir, storageKey))
    }

    /** Restore the icon extracted from the retained source JAR and remove the durable user override. */
    @JvmStatic
    @Throws(IOException::class)
    fun resetToOriginal(emulatorDir: File, storageKey: String): Long {
        requireSafeStorageKey(storageKey)
        val appDir = appDirectory(emulatorDir, storageKey)
        val descriptorFile = File(appDir.absolutePath + Config.MIDLET_MANIFEST_FILE)
        val retainedJar = File(appDir.absolutePath + Config.MIDLET_RES_FILE)
        if (!descriptorFile.isFile || !retainedJar.isFile) {
            throw IOException("Retained source metadata is unavailable for icon reset")
        }

        val descriptor = Descriptor(descriptorFile, false)
        val sourceIconPath = descriptor.icon?.trim()?.takeIf { it.isNotEmpty() }
        val preparedOriginal = sourceIconPath?.let { iconPath ->
            File(appDir, ".icon-original-${UUID.randomUUID()}.tmp").also { temp ->
                try {
                    ZipUtils.unzipEntry(retainedJar, iconPath, temp)
                    if (!temp.isFile || temp.length() <= 0L) {
                        throw IOException("Original MIDlet icon is unavailable in retained JAR")
                    }
                } catch (error: Throwable) {
                    temp.delete()
                    throw error
                }
            }
        }

        val custom = persistedOverrideFile(emulatorDir, storageKey)
        val previousCustom = moveAside(custom)
        val effective = effectiveIconFile(emulatorDir, storageKey)
        try {
            if (preparedOriginal == null) {
                if (effective.exists() && !effective.delete()) {
                    throw IOException("Unable to remove custom effective icon")
                }
            } else {
                copyAtomically(preparedOriginal, effective)
            }
            discardMovedAside(previousCustom)
        } catch (error: Throwable) {
            restoreMovedAside(previousCustom, custom)
            throw error
        } finally {
            preparedOriginal?.delete()
        }
        return LibraryIconRevision.fromFile(effective)
    }

    /** Reapply a durable override to a converted staging directory just before reinstall publish. */
    @JvmStatic
    @Throws(IOException::class)
    fun applyPersistedOverride(emulatorDir: File, storageKey: String, convertedStagingDir: File) {
        requireSafeStorageKey(storageKey)
        val custom = persistedOverrideFile(emulatorDir, storageKey)
        if (!custom.isFile) return
        copyAtomically(custom, File(convertedStagingDir.absolutePath + Config.MIDLET_ICON_FILE))
    }

    @JvmStatic
    fun persistedOverrideFile(emulatorDir: File, storageKey: String): File {
        requireSafeStorageKey(storageKey)
        return File(File(File(emulatorDir, CONFIGS_DIR), storageKey), CUSTOM_ICON_FILE)
    }

    @JvmStatic
    fun effectiveIconFile(emulatorDir: File, storageKey: String): File {
        requireSafeStorageKey(storageKey)
        return File(appDirectory(emulatorDir, storageKey).absolutePath + Config.MIDLET_ICON_FILE)
    }

    private fun appDirectory(emulatorDir: File, storageKey: String): File =
        File(File(emulatorDir, CONVERTED_DIR), storageKey)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create custom icon directory: ${directory.absolutePath}")
        }
    }

    /** Return a sibling backup path when [target] existed; null means there was nothing to move. */
    private fun moveAside(target: File): File? {
        if (!target.exists()) return null
        val parent = target.parentFile ?: throw IOException("Icon target has no parent directory")
        val backup = File(parent, ".${target.name}.${UUID.randomUUID()}.bak")
        if (!target.renameTo(backup)) {
            throw IOException("Unable to stage existing icon override for replacement")
        }
        return backup
    }

    private fun restoreMovedAside(backup: File?, target: File) {
        if (backup == null || !backup.exists()) return
        if (target.exists() && !target.delete()) return
        backup.renameTo(target)
    }

    private fun discardMovedAside(backup: File?) {
        if (backup != null && backup.exists()) backup.delete()
    }

    /** Copy into a sibling temp file, then replace the destination with rollback on rename failure. */
    private fun copyAtomically(source: File, target: File) {
        val parent = target.parentFile ?: throw IOException("Icon target has no parent directory")
        ensureDirectory(parent)
        val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            val previous = moveAside(target)
            if (!temp.renameTo(target)) {
                restoreMovedAside(previous, target)
                throw IOException("Unable to publish icon file: ${target.absolutePath}")
            }
            discardMovedAside(previous)
        } finally {
            temp.delete()
        }
    }

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
    }
}
