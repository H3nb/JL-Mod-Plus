/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.librarydb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams a plain JAR-only archive for sharing selected apps with other tools/users. */
internal object LibraryJarArchiveExporter {
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_FILE_STEM_LENGTH = 72

    data class JarSource(
        val appId: Long,
        val title: String,
        val file: File,
    )

    @Throws(IOException::class)
    fun exportToZip(
        sources: List<JarSource>,
        target: File,
        onProgress: ((LibraryAppBundleExporter.Progress) -> Unit)? = null,
    ) {
        if (sources.isEmpty()) throw IOException("JAR archive has no apps")
        val ordered = sources
            .distinctBy(JarSource::appId)
            .sortedWith { left, right ->
                left.title.trim().compareTo(right.title.trim(), ignoreCase = true).takeIf { it != 0 }
                    ?: left.appId.compareTo(right.appId)
            }
        if (ordered.size != sources.size) throw IOException("JAR archive contains duplicate app IDs")
        val entries = ordered.map { source ->
            val file = source.file.canonicalFile
            if (!file.isFile || file.length() <= 0L) {
                throw IOException("Retained JAR is unavailable for ${source.title}")
            }
            SourceEntry(
                source = source,
                file = file,
                name = uniqueFileName(source.title, emptySet()),
            )
        }.let { initial ->
            val used = LinkedHashSet<String>()
            initial.map { entry ->
                val name = uniqueFileName(entry.source.title, used)
                used += name
                entry.copy(name = name)
            }
        }
        val totalBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, entry.file.length()) }
        val parent = target.parentFile ?: throw IOException("JAR archive target has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Unable to create JAR archive target directory")
        }

        var writtenBytes = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target, false))).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            entries.forEachIndexed { index, entry ->
                val zipEntry = ZipEntry(entry.name).apply { time = 0L }
                zip.putNextEntry(zipEntry)
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                BufferedInputStream(FileInputStream(entry.file)).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        writtenBytes = saturatingAdd(writtenBytes, read.toLong())
                    }
                }
                zip.closeEntry()
                onProgress?.invoke(
                    LibraryAppBundleExporter.Progress(
                        completedEntries = index + 1,
                        totalEntries = entries.size,
                        currentEntry = entry.name,
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
        }
    }

    internal fun safeFileName(title: String, usedNames: Set<String> = emptySet()): String {
        val trimmed = title.trim().removeSuffixIgnoreCase(".jar")
        val normalized = buildString(trimmed.length.coerceAtMost(MAX_FILE_STEM_LENGTH)) {
            for (character in trimmed) {
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
        var candidate = "$stem.jar"
        var suffix = 2
        while (candidate in usedNames) {
            candidate = "$stem-$suffix.jar"
            suffix++
        }
        return candidate
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private fun uniqueFileName(title: String, usedNames: Set<String>): String =
        safeFileName(title, usedNames)

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right <= 0L) left else if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class SourceEntry(
        val source: JarSource,
        val file: File,
        val name: String,
    )
}
