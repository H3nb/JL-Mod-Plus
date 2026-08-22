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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Config/data directory state declared by the universal app-payload manifest. */
internal enum class BundleNamespaceState {
    Absent,
    PresentEmpty,
    Present;

    companion object {
        fun parse(value: String, field: String): BundleNamespaceState = when (value) {
            "absent" -> Absent
            "present-empty" -> PresentEmpty
            "present" -> Present
            else -> throw IOException("Invalid $field in app bundle manifest: $value")
        }
    }
}

internal data class BundleApp(
    val bundleId: String,
    val title: String,
    val vendor: String,
    val version: String,
    val payloadRoot: String,
    val sourceSha256: String?,
    val configState: BundleNamespaceState,
    val dataState: BundleNamespaceState,
    val legacyAssurance: Boolean = false,
)

internal data class ParsedBundle(
    val formatVersion: Int,
    val apps: List<BundleApp>,
    val legacyAssurance: Boolean,
)

/**
 * Reads the universal manifest and ZIP namespace without extracting or mutating app data.
 * Existing v0/v1 payloads are represented as one legacy-assured logical app and remain readable.
 */
internal object LibraryAppBundleReader {
    private const val MAX_MANIFEST_BYTES = 4L * 1024L
    private const val MAX_ENTRIES = 10_000
    private const val MAX_BUNDLE_APPS = 1_024
    private const val ROOT_PREFIX = "apps/"
    private const val JAR_SUFFIX = "/app/res.jar"
    private val SHA256 = Regex("[0-9a-f]{64}")

    @Throws(IOException::class)
    fun read(input: InputStream): ParsedBundle {
        val names = LinkedHashSet<String>()
        var manifest: ByteArray? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (names.size >= MAX_ENTRIES) throw IOException("App bundle contains too many entries")
                val name = normalizeEntryName(entry.name)
                if (!names.add(name)) throw IOException("Duplicate app bundle entry: $name")
                if (name == LibraryAppBundleFormat.MANIFEST_ENTRY && !entry.isDirectory) {
                    manifest = zip.readBounded(MAX_MANIFEST_BYTES)
                }
                zip.closeEntry()
            }
        }

        val manifestBytes = manifest
        if (manifestBytes == null) {
            return legacyBundle(names)
        }
        val root = parseManifest(manifestBytes)
        return when (root.get("formatVersion")?.asInt) {
            LibraryAppBundleFormat.UNIVERSAL_VERSION -> parseUniversal(root, names)
            LibraryAppBundleFormat.CURRENT_VERSION -> legacyBundle(names, formatVersion = LibraryAppBundleFormat.CURRENT_VERSION)
            else -> throw IOException("Unsupported app bundle format version")
        }
    }

    @Throws(IOException::class)
    internal fun parseManifest(manifest: ByteArray): JsonObject = try {
        JsonParser.parseString(manifest.toString(Charsets.UTF_8)).asJsonObject
    } catch (error: RuntimeException) {
        throw IOException("Invalid app bundle manifest", error)
    }

    @Throws(IOException::class)
    private fun parseUniversal(root: JsonObject, names: Set<String>): ParsedBundle {
        val schema = root.get("schema")?.takeUnless { it.isJsonNull }?.asString
        if (schema != LibraryAppBundleFormat.UNIVERSAL_SCHEMA) {
            throw IOException("Invalid app bundle schema")
        }
        val appsElement = root.get("apps")
        if (appsElement == null || !appsElement.isJsonArray || appsElement.asJsonArray.size() == 0) {
            throw IOException("Universal app bundle has no apps")
        }
        if (appsElement.asJsonArray.size() > MAX_BUNDLE_APPS) {
            throw IOException("Universal app bundle contains too many apps")
        }

        val apps = appsElement.asJsonArray.map { element ->
            if (!element.isJsonObject) throw IOException("Invalid app record in bundle manifest")
            parseApp(element.asJsonObject)
        }
        val ids = apps.map(BundleApp::bundleId)
        if (ids.size != ids.toSet().size) throw IOException("Duplicate bundleId in app bundle manifest")
        val roots = apps.map(BundleApp::payloadRoot)
        if (roots.size != roots.toSet().size) throw IOException("Duplicate payloadRoot in app bundle manifest")

        val declaredRoots = roots.toSet()
        val observedRoots = names.asSequence()
            .filter { it.startsWith(ROOT_PREFIX) }
            .map { name ->
                val remainder = name.removePrefix(ROOT_PREFIX)
                val id = remainder.substringBefore('/')
                if (id.isBlank() || !remainder.contains('/')) {
                    throw IOException("Invalid app namespace entry: $name")
                }
                "$ROOT_PREFIX$id/"
            }
            .toSet()
        if (observedRoots != declaredRoots) {
            throw IOException("Manifest app namespaces do not match ZIP entries")
        }

        apps.forEach { app ->
            val entries = names.filter { it.startsWith(app.payloadRoot) }
            if (entries.none { it == app.payloadRoot + "app/res.jar" }) {
                throw IOException("App ${app.bundleId} does not contain a retained JAR")
            }
            entries.forEach { entry ->
                val relative = entry.removePrefix(app.payloadRoot)
                val allowed = relative == "app/res.jar" ||
                    relative == "app/converted.dex.conf" ||
                    relative == "app/icon.png" ||
                    relative.startsWith("config/") ||
                    relative.startsWith("data/")
                if (!allowed) throw IOException("Unsupported app bundle entry: $entry")
            }
        }
        return ParsedBundle(
            formatVersion = LibraryAppBundleFormat.UNIVERSAL_VERSION,
            apps = apps,
            legacyAssurance = false,
        )
    }

    @Throws(IOException::class)
    private fun parseApp(app: JsonObject): BundleApp {
        fun requiredString(field: String): String {
            val value = app.get(field)?.takeUnless { it.isJsonNull }?.asString?.trim()
            if (value.isNullOrEmpty()) throw IOException("Missing $field in app bundle manifest")
            return value
        }

        val bundleId = requiredString("bundleId")
        if (!bundleId.matches(Regex("a[0-9a-zA-Z_-]{1,71}"))) {
            throw IOException("Invalid bundleId in app bundle manifest")
        }
        val payloadRoot = requiredString("payloadRoot")
        if (payloadRoot != "$ROOT_PREFIX$bundleId/" ||
            payloadRoot.contains("\\") || payloadRoot.contains("..")
        ) {
            throw IOException("Invalid payloadRoot in app bundle manifest")
        }
        val sourceSha256 = requiredString("sourceSha256")
        if (!SHA256.matches(sourceSha256)) throw IOException("Invalid sourceSha256 in app bundle manifest")
        return BundleApp(
            bundleId = bundleId,
            title = requiredString("title"),
            vendor = requiredString("vendor"),
            version = requiredString("version"),
            payloadRoot = payloadRoot,
            sourceSha256 = sourceSha256,
            configState = BundleNamespaceState.parse(requiredString("configState"), "configState"),
            dataState = BundleNamespaceState.parse(requiredString("dataState"), "dataState"),
        )
    }

    private fun legacyBundle(
        names: Set<String>,
        formatVersion: Int = 0,
    ): ParsedBundle {
        if (names.none { it == "app/res.jar" }) {
            throw IOException("App bundle does not contain a retained JAR")
        }
        val configState = when {
            names.any { it.startsWith("config/") && it != "config/" } -> BundleNamespaceState.Present
            names.any { it == "config/" } -> BundleNamespaceState.PresentEmpty
            else -> BundleNamespaceState.Absent
        }
        val dataState = when {
            names.any { it.startsWith("data/") && it != "data/" } -> BundleNamespaceState.Present
            names.any { it == "data/" } -> BundleNamespaceState.PresentEmpty
            else -> BundleNamespaceState.Absent
        }
        return ParsedBundle(
            formatVersion = formatVersion,
            apps = listOf(
                BundleApp(
                    bundleId = "legacy",
                    title = "",
                    vendor = "",
                    version = "",
                    payloadRoot = "",
                    sourceSha256 = null,
                    configState = configState,
                    dataState = dataState,
                    legacyAssurance = true,
                ),
            ),
            legacyAssurance = true,
        )
    }

    @Throws(IOException::class)
    private fun normalizeEntryName(raw: String): String {
        if (raw.isBlank() || raw.contains('\u0000') || raw.startsWith('/') ||
            raw.startsWith('\\') || raw.contains('\\')
        ) throw IOException("Unsafe app bundle entry: $raw")
        val normalized = raw.removeSuffix("/")
        if (normalized.isBlank()) throw IOException("Unsafe app bundle entry: $raw")
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Unsafe app bundle entry: $raw")
        }
        return if (raw.endsWith('/')) "$normalized/" else normalized
    }

    @Throws(IOException::class)
    private fun InputStream.readBounded(limit: Long): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("App bundle manifest is too large")
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
