/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Prepares a retained source JAR for read-only sharing through the app's narrow FileProvider. */
object LibraryShareManager {
    private const val PROVIDER_SUFFIX = ".diagnostic-files"
    private const val SHARE_DIR = "library-share"
    private const val MIME_TYPE = "application/java-archive"
    private const val MAX_FILE_STEM_LENGTH = 80
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private val prepareMutex = Mutex()

    data class PreparedShare(
        val uri: Uri,
        val fileName: String,
        val mimeType: String = MIME_TYPE,
    )

    suspend fun prepare(
        context: Context,
        emulatorDir: File,
        storageKey: String,
        displayTitle: String,
    ): PreparedShare = prepareMutex.withLock {
        withContext(Dispatchers.IO) {
            val root = emulatorDir.canonicalFile
            val source = LibraryFileOperations.retainedJar(emulatorDir, storageKey).canonicalFile
            if (!insideRoot(root, source)) {
                throw IOException("Retained source JAR resolves outside the active workdir")
            }
            if (!source.isFile) {
                throw IOException("Retained source JAR is unavailable")
            }

            val directory = File(File(context.cacheDir, SHARE_DIR), storageKey)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Unable to create app-share cache directory")
            }
            val fileName = safeFileName(displayTitle)
            val target = File(directory, fileName)
            val staging = File(directory, "$fileName.tmp")
            try {
                copyFile(source, staging)
                if (target.exists() && !target.delete()) {
                    throw IOException("Unable to replace previous app-share cache file")
                }
                if (!staging.renameTo(target)) {
                    throw IOException("Unable to publish app-share cache file")
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + PROVIDER_SUFFIX,
                    target,
                )
                PreparedShare(uri = uri, fileName = fileName)
            } finally {
                if (staging.exists()) staging.delete()
            }
        }
    }

    internal fun safeFileName(displayTitle: String): String {
        val trimmed = displayTitle.trim()
        val title = if (trimmed.endsWith(".jar", ignoreCase = true)) {
            trimmed.dropLast(4)
        } else {
            trimmed
        }
        val normalized = buildString(title.length.coerceAtMost(MAX_FILE_STEM_LENGTH)) {
            for (character in title) {
                if (length >= MAX_FILE_STEM_LENGTH) break
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == ' ' || character == '-' || character == '_' ||
                            character == '(' || character == ')' || character == '[' || character == ']' -> character
                        else -> '_'
                    },
                )
            }
        }.trim(' ', '_')
        val stem = normalized.ifBlank { "J2ME-App" }
        return "$stem.jar"
    }

    @Throws(IOException::class)
    internal fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
        if (target.length() != source.length()) {
            throw IOException("Incomplete app-share cache copy")
        }
    }

    private fun insideRoot(root: File, candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)
}
