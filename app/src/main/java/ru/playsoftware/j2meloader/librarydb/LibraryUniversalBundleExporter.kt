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

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic writer for the universal app-payload bundle. A single app is represented by the
 * same [AppSource] list and namespace as a bulk export; the caller decides how that list is built.
 *
 * This writer intentionally has no Android or Room dependency. The single-app exporter delegates
 * here whenever retained source identity is available; legacy v1 output remains readable for old
 * integrations that cannot provide that identity.
 */
internal object LibraryUniversalBundleExporter {
    private const val ROOT_PREFIX = "apps/"
    private const val COPY_BUFFER_SIZE = 64 * 1024

    data class AppSource(
        val bundleId: String,
        val title: String,
        val vendor: String,
        val version: String,
        val emulatorDir: File,
        val storageKey: String,
    )

    @Throws(IOException::class)
    fun exportToZip(
        apps: List<AppSource>,
        target: File,
        onProgress: ((LibraryAppBundleExporter.Progress) -> Unit)? = null,
    ) {
        if (apps.isEmpty()) throw IOException("Universal app bundle has no apps")
        val plannedApps = apps.map(::planApp).sortedBy { it.source.bundleId }
        if (plannedApps.zipWithNext().any { (left, right) -> left.source.bundleId == right.source.bundleId }) {
            throw IOException("Universal app bundle contains duplicate bundle IDs")
        }
        val manifest = manifestBytes(plannedApps)
        val entries = plannedApps.flatMap(PlannedApp::entries).sortedBy(SourceEntry::path)
        val totalEntries = entries.size + 1
        val totalBytes = entries.fold(manifest.size.toLong()) { total, entry ->
            if (entry.directory) total else saturatingAdd(total, entry.file.length())
        }
        val parent = target.parentFile ?: throw IOException("Universal app bundle target has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Unable to create universal app-bundle target directory")
        }

        var writtenBytes = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target, false))).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            writeBytes(zip, LibraryAppBundleFormat.MANIFEST_ENTRY, manifest)
            writtenBytes = manifest.size.toLong()
            onProgress?.invoke(
                LibraryAppBundleExporter.Progress(
                    completedEntries = 1,
                    totalEntries = totalEntries,
                    currentEntry = LibraryAppBundleFormat.MANIFEST_ENTRY,
                    writtenBytes = writtenBytes,
                    totalBytes = totalBytes,
                ),
            )

            entries.forEachIndexed { index, entry ->
                val digest = if (entry.expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
                writeEntry(zip, entry, digest)
                if (digest != null) {
                    val actual = digest.digest().toHexString()
                    if (actual != entry.expectedSha256) {
                        throw IOException("Source JAR changed while exporting app bundle")
                    }
                }
                if (!entry.directory) writtenBytes = saturatingAdd(writtenBytes, entry.file.length())
                onProgress?.invoke(
                    LibraryAppBundleExporter.Progress(
                        completedEntries = index + 2,
                        totalEntries = totalEntries,
                        currentEntry = entry.path,
                        writtenBytes = writtenBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
        }
    }

    private fun planApp(source: AppSource): PlannedApp {
        validateBundleId(source.bundleId)
        requireText(source.title, "title")
        requireText(source.vendor, "vendor")
        requireText(source.version, "version")
        requireSafeStorageKey(source.storageKey)

        val workdirRoot = source.emulatorDir.canonicalFile
        if (!workdirRoot.isDirectory) throw IOException("App bundle workdir is unavailable")
        val convertedRoot = File(File(source.emulatorDir, "converted"), source.storageKey)
        val jar = requiredFile(workdirRoot, File(convertedRoot, "res.jar"), "Retained JAR")
        if (jar.length() <= 0L) throw IOException("Retained JAR is empty")
        val payloadRoot = "$ROOT_PREFIX${source.bundleId}/"
        val entries = ArrayList<SourceEntry>()
        entries += SourceEntry(
            path = "${payloadRoot}app/res.jar",
            file = jar,
            directory = false,
            expectedSha256 = sha256(jar),
        )
        addOptionalFile(entries, workdirRoot, File(convertedRoot, "converted.dex.conf"), "${payloadRoot}app/converted.dex.conf")
        addOptionalFile(entries, workdirRoot, File(convertedRoot, "icon.png"), "${payloadRoot}app/icon.png")

        val configState = addNamespace(
            entries = entries,
            workdirRoot = workdirRoot,
            root = File(File(source.emulatorDir, "configs"), source.storageKey),
            prefix = "${payloadRoot}config",
        )
        val dataState = addNamespace(
            entries = entries,
            workdirRoot = workdirRoot,
            root = File(File(source.emulatorDir, "data"), source.storageKey),
            prefix = "${payloadRoot}data",
        )
        return PlannedApp(
            source = source,
            payloadRoot = payloadRoot,
            sourceSha256 = entries.first().expectedSha256!!,
            configState = configState,
            dataState = dataState,
            entries = entries,
        )
    }

    private fun addNamespace(
        entries: MutableList<SourceEntry>,
        workdirRoot: File,
        root: File,
        prefix: String,
    ): BundleNamespaceState {
        if (!root.exists()) return BundleNamespaceState.Absent
        val canonicalRoot = requiredDirectory(workdirRoot, root, "App-owned namespace")
        entries += SourceEntry(path = "$prefix/", file = canonicalRoot, directory = true)
        walkNamespace(entries, workdirRoot, canonicalRoot, prefix)
        return if (entries.any { !it.directory && it.path.startsWith("$prefix/") }) {
            BundleNamespaceState.Present
        } else {
            BundleNamespaceState.PresentEmpty
        }
    }

    private fun walkNamespace(
        entries: MutableList<SourceEntry>,
        workdirRoot: File,
        directory: File,
        prefix: String,
    ) {
        val children = directory.listFiles()
            ?: throw IOException("Unable to list app-owned directory: ${directory.absolutePath}")
        children.sortedBy(File::getName).forEach { child ->
            val canonical = child.canonicalFile
            if (!insideRoot(workdirRoot, canonical)) {
                throw IOException("App-owned path resolves outside the active workdir: ${child.absolutePath}")
            }
            val path = "$prefix/${child.name}"
            when {
                canonical.isDirectory -> {
                    entries += SourceEntry(path = "$path/", file = canonical, directory = true)
                    walkNamespace(entries, workdirRoot, canonical, path)
                }
                canonical.isFile -> entries += SourceEntry(path = path, file = canonical, directory = false)
                else -> throw IOException("App-owned path is not a regular file or directory: ${child.absolutePath}")
            }
        }
    }

    private fun addOptionalFile(
        entries: MutableList<SourceEntry>,
        workdirRoot: File,
        file: File,
        path: String,
    ) {
        if (!file.exists()) return
        val canonical = requiredFile(workdirRoot, file, "App-owned file")
        entries += SourceEntry(path = path, file = canonical, directory = false)
    }

    private fun requiredFile(workdirRoot: File, file: File, label: String): File {
        val canonical = file.canonicalFile
        if (!insideRoot(workdirRoot, canonical)) {
            throw IOException("$label resolves outside the active workdir: ${file.absolutePath}")
        }
        if (!canonical.isFile) throw IOException("$label is unavailable: ${file.absolutePath}")
        return canonical
    }

    private fun requiredDirectory(workdirRoot: File, file: File, label: String): File {
        val canonical = file.canonicalFile
        if (!insideRoot(workdirRoot, canonical)) {
            throw IOException("$label resolves outside the active workdir: ${file.absolutePath}")
        }
        if (!canonical.isDirectory) throw IOException("$label is not a directory: ${file.absolutePath}")
        return canonical
    }

    private fun manifestBytes(apps: List<PlannedApp>): ByteArray {
        val root = JsonObject()
        root.addProperty("schema", LibraryAppBundleFormat.UNIVERSAL_SCHEMA)
        root.addProperty("formatVersion", LibraryAppBundleFormat.UNIVERSAL_VERSION)
        val records = JsonArray()
        apps.forEach { app ->
            val record = JsonObject()
            record.addProperty("bundleId", app.source.bundleId)
            record.addProperty("title", app.source.title.trim())
            record.addProperty("vendor", app.source.vendor.trim())
            record.addProperty("version", app.source.version.trim())
            record.addProperty("payloadRoot", app.payloadRoot)
            record.addProperty("sourceSha256", app.sourceSha256)
            record.addProperty("configState", app.configState.manifestValue())
            record.addProperty("dataState", app.dataState.manifestValue())
            records.add(record)
        }
        root.add("apps", records)
        return (GsonBuilder().disableHtmlEscaping().create().toJson(root) + "\n")
            .toByteArray(Charsets.UTF_8)
    }

    private fun writeBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, source: SourceEntry, digest: MessageDigest?) {
        val entry = ZipEntry(source.path).apply { time = 0L }
        zip.putNextEntry(entry)
        if (!source.directory) {
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            BufferedInputStream(FileInputStream(source.file)).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest?.update(buffer, 0, read)
                    zip.write(buffer, 0, read)
                }
            }
        }
        zip.closeEntry()
    }

    private fun validateBundleId(bundleId: String) {
        require(bundleId.matches(Regex("a[0-9a-zA-Z_-]{1,71}"))) {
            "Invalid bundleId: $bundleId"
        }
    }

    private fun requireText(value: String, field: String) {
        require(value.isNotBlank()) { "Bundle $field is blank" }
    }

    private fun requireSafeStorageKey(storageKey: String) {
        require(storageKey.isNotBlank()) { "storageKey is blank" }
        require(storageKey != "." && storageKey != "..") { "Unsafe storageKey: $storageKey" }
        require(!storageKey.contains('/') && !storageKey.contains('\\')) {
            "Unsafe storageKey: $storageKey"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        BufferedInputStream(FileInputStream(file)).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun BundleNamespaceState.manifestValue(): String = when (this) {
        BundleNamespaceState.Absent -> "absent"
        BundleNamespaceState.PresentEmpty -> "present-empty"
        BundleNamespaceState.Present -> "present"
    }

    private fun insideRoot(root: File, candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right <= 0L) left else if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class PlannedApp(
        val source: AppSource,
        val payloadRoot: String,
        val sourceSha256: String,
        val configState: BundleNamespaceState,
        val dataState: BundleNamespaceState,
        val entries: List<SourceEntry>,
    )

    private data class SourceEntry(
        val path: String,
        val file: File,
        val directory: Boolean,
        val expectedSha256: String? = null,
    )
}
