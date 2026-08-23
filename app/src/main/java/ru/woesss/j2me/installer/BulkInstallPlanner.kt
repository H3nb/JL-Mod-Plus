/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import android.net.Uri
import androidx.core.net.toUri
import io.reactivex.Single
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import ru.playsoftware.j2meloader.librarydb.LibraryGenerationToken
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel
import ru.woesss.j2me.jar.Descriptor

object BulkInstallPlanner {
    private val supportedExtensions = setOf("jar", "jad", "kjx")
    private val hardDiscoveryFailures = setOf(
        BulkInstallStatus.RemoteSourceUnsupported,
        BulkInstallStatus.SourceError,
    )

    fun planExplicit(
        files: List<File>,
        library: LibraryViewModel,
        isActive: () -> Boolean = { true },
    ): BulkInstallPlan {
        checkActive(isActive)
        val generation = requireReadyGeneration(library)
        val normalized = files
            .onEach { checkActive(isActive) }
            .mapNotNull(::canonicalReadableFile)
            .filter(::isSupportedSource)
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
        val units = normalizeSources(
            files = normalized,
            origin = BulkSourceOrigin.ExplicitSelection,
        )
        return inspectAndFinalize(units, generation, library, emptyList(), isActive)
    }

    fun fingerprint(files: List<File>): String = sourceFingerprint(files)

    private fun inspectAndFinalize(
        units: List<BulkSourceUnit>,
        generation: LibraryGenerationToken,
        library: LibraryViewModel,
        warnings: List<String>,
        isActive: () -> Boolean,
    ): BulkInstallPlan {
        val inspected = units.map { unit ->
            checkActive(isActive)
            inspect(unit, generation, library)
        }
        checkActive(isActive)
        val deduplicated = markSemanticDuplicates(inspected)
        val grouped = applyBatchVersionGrouping(deduplicated)
        return BulkInstallPlan(
            generation = generation.generation,
            workdir = generation.emulatorDir.canonicalFile,
            items = grouped,
            warnings = warnings,
        )
    }

    private fun inspect(
        unit: BulkSourceUnit,
        generation: LibraryGenerationToken,
        library: LibraryViewModel,
    ): BulkInstallItem {
        val discoveryStatus = unit.discoveryStatus
        if (discoveryStatus in hardDiscoveryFailures) {
            return BulkInstallItem(
                id = unit.id,
                unit = unit,
                name = unit.primaryFile.name,
                vendor = "",
                version = "",
                sourceFingerprint = runCatching { sourceFingerprint(unit.sourceFiles) }.getOrDefault(""),
                status = requireNotNull(discoveryStatus),
                preflightStatus = requireNotNull(discoveryStatus),
                action = BulkInstallAction.Skip,
                selected = false,
                detail = unit.discoveryDetail,
            )
        }

        val installer = AppInstaller(null, Uri.fromFile(unit.primaryFile), library)
        return try {
            val installerStatus = Single.create<Int>(installer::loadInfo).blockingGet()
            val descriptor = requireNotNull(installer.newDescriptor) { "Installer returned no descriptor" }
            val jar = installer.jar
            val candidates = library.findBySourceIdentity(
                generation.generation,
                generation.emulatorDir,
                descriptor.name,
                descriptor.vendor,
            )
            val authoritativeStatus = if (candidates.size > 1) {
                BulkInstallStatus.AmbiguousInstalledMatch
            } else {
                mapInstallerStatus(installerStatus)
            }
            val displayStatus = if (discoveryStatus == BulkInstallStatus.BatchConflict) {
                BulkInstallStatus.BatchConflict
            } else {
                authoritativeStatus
            }
            val defaults = defaultAction(displayStatus)
            BulkInstallItem(
                id = unit.id,
                unit = unit,
                name = descriptor.name,
                vendor = descriptor.vendor,
                version = descriptor.version,
                installedVersion = installer.currentVersion,
                descriptorAttributes = HashMap(descriptor.attrs),
                sourceFingerprint = sourceFingerprint(unit.sourceFiles),
                jarFingerprint = jar?.takeIf(File::isFile)?.let(::hashFile),
                status = displayStatus,
                preflightStatus = authoritativeStatus,
                action = defaults.first,
                selected = defaults.second,
                detail = unit.discoveryDetail ?: when (authoritativeStatus) {
                    BulkInstallStatus.AmbiguousInstalledMatch ->
                        "${candidates.size} installed applications match this source identity"
                    BulkInstallStatus.JadJarMismatch ->
                        "JAD and JAR source identity do not match"
                    else -> null
                },
            )
        } catch (error: Throwable) {
            BulkInstallItem(
                id = unit.id,
                unit = unit,
                name = unit.primaryFile.name,
                vendor = "",
                version = "",
                sourceFingerprint = runCatching { sourceFingerprint(unit.sourceFiles) }.getOrDefault(""),
                status = BulkInstallStatus.SourceError,
                preflightStatus = BulkInstallStatus.SourceError,
                action = BulkInstallAction.Skip,
                selected = false,
                detail = boundedMessage(error),
            )
        } finally {
            installer.clearCache()
            installer.deleteTemp()
        }
    }

    private fun mapInstallerStatus(status: Int): BulkInstallStatus = when (status) {
        AppInstaller.STATUS_NEW -> BulkInstallStatus.New
        AppInstaller.STATUS_NEWER -> BulkInstallStatus.Update
        AppInstaller.STATUS_OLDER -> BulkInstallStatus.Downgrade
        AppInstaller.STATUS_SAME -> BulkInstallStatus.AlreadyInstalled
        AppInstaller.STATUS_EQUAL -> BulkInstallStatus.ReinstallOrVariant
        AppInstaller.STATUS_UNMATCHED -> BulkInstallStatus.JadJarMismatch
        else -> BulkInstallStatus.SourceError
    }

    private fun defaultAction(status: BulkInstallStatus): Pair<BulkInstallAction, Boolean> = when (status) {
        BulkInstallStatus.New,
        BulkInstallStatus.Update,
        -> BulkInstallAction.Install to true

        else -> BulkInstallAction.Skip to false
    }

    private fun normalizeSources(
        files: List<File>,
        origin: BulkSourceOrigin,
    ): List<BulkSourceUnit> {
        val jads = files.filter { extension(it) == "jad" }
        val jars = files.filter { extension(it) == "jar" }
        val kjx = files.filter { extension(it) == "kjx" }
        val jarByCanonicalPath = jars.associateBy { canonicalPath(it) }
        val resolutions = jads.associateWith(::resolveJad)
        val consumedJarPaths = HashSet<String>()
        val localByJar = LinkedHashMap<String, MutableList<Pair<File, JadResolution.Local>>>()
        resolutions.forEach { (jad, resolution) ->
            if (resolution is JadResolution.Local) {
                val key = canonicalPath(resolution.jar)
                localByJar.getOrPut(key) { ArrayList() }.add(jad to resolution)
                if (jarByCanonicalPath.containsKey(key)) consumedJarPaths.add(key)
            }
        }

        val conflictingJads = HashSet<String>()
        localByJar.values.forEach { entries ->
            if (entries.size <= 1) return@forEach
            val semanticSets = entries.mapNotNull { (jad, _) ->
                runCatching { HashMap(Descriptor(jad, true).attrs) }.getOrNull()
            }.distinct()
            if (semanticSets.size > 1) {
                entries.forEach { (jad, _) -> conflictingJads.add(canonicalPath(jad)) }
            }
        }

        val units = ArrayList<BulkSourceUnit>()
        jads.forEach { jad ->
            val resolution = resolutions.getValue(jad)
            val conflict = canonicalPath(jad) in conflictingJads
            when (resolution) {
                is JadResolution.Local -> units.add(
                    BulkSourceUnit(
                        id = UUID.randomUUID().toString(),
                        origin = origin,
                        kind = BulkSourceKind.JadJarPair,
                        primaryFile = jad,
                        sourceFiles = listOf(jad, resolution.jar),
                        jadFile = jad,
                        jarFile = resolution.jar,
                        discoveryStatus = if (conflict) BulkInstallStatus.BatchConflict else null,
                        discoveryDetail = if (conflict) {
                            "Multiple non-equivalent JADs resolve to the same JAR"
                        } else {
                            null
                        },
                    ),
                )

                is JadResolution.Remote -> units.add(
                    unresolvedJadUnit(
                        jad,
                        origin,
                        BulkInstallStatus.RemoteSourceUnsupported,
                        "Remote JAR acquisition is not supported by Bulk Install v1",
                    ),
                )

                is JadResolution.Error -> units.add(
                    unresolvedJadUnit(
                        jad,
                        origin,
                        BulkInstallStatus.SourceError,
                        resolution.message,
                    ),
                )
            }
        }
        jars.filter { canonicalPath(it) !in consumedJarPaths }.forEach { jar ->
            units.add(
                BulkSourceUnit(
                    id = UUID.randomUUID().toString(),
                    origin = origin,
                    kind = BulkSourceKind.JarOnly,
                    primaryFile = jar,
                    sourceFiles = listOf(jar),
                    jarFile = jar,
                ),
            )
        }
        kjx.forEach { source ->
            units.add(
                BulkSourceUnit(
                    id = UUID.randomUUID().toString(),
                    origin = origin,
                    kind = BulkSourceKind.Kjx,
                    primaryFile = source,
                    sourceFiles = listOf(source),
                ),
            )
        }
        return units.sortedBy { it.primaryFile.path.lowercase(Locale.ROOT) }
    }

    private fun unresolvedJadUnit(
        jad: File,
        origin: BulkSourceOrigin,
        status: BulkInstallStatus,
        detail: String,
    ) = BulkSourceUnit(
        id = UUID.randomUUID().toString(),
        origin = origin,
        kind = BulkSourceKind.JadJarPair,
        primaryFile = jad,
        sourceFiles = listOf(jad),
        jadFile = jad,
        discoveryStatus = status,
        discoveryDetail = detail,
    )

    private sealed interface JadResolution {
        data class Local(val jar: File) : JadResolution
        data object Remote : JadResolution
        data class Error(val message: String) : JadResolution
    }

    private fun resolveJad(jad: File): JadResolution {
        return try {
            val descriptor = Descriptor(jad, true)
            val jarUrl = descriptor.jarUrl
                ?: return JadResolution.Error("JAD has no MIDlet-Jar-URL")
            val parsedUri = jarUrl.toUri()
            val scheme = parsedUri.scheme
            if (scheme != null) {
                return if (scheme.equals("http", true) || scheme.equals("https", true)) {
                    JadResolution.Remote
                } else {
                    JadResolution.Error("Unsupported JAD JAR URI scheme: $scheme")
                }
            }
            val parent = jad.parentFile
                ?: return JadResolution.Error("JAD has no parent directory")
            var jar = File(parent, jarUrl)
            if (!jar.isFile) jar = File(parent, jad.nameWithoutExtension + ".jar")
            if (!jar.isFile) {
                return JadResolution.Error("JAR referenced by JAD was not found: $jarUrl")
            }
            jar = jar.canonicalFile
            JadResolution.Local(jar)
        } catch (error: Throwable) {
            JadResolution.Error(boundedMessage(error))
        }
    }

    private fun markSemanticDuplicates(items: List<BulkInstallItem>): List<BulkInstallItem> {
        val seen = LinkedHashMap<String, String>()
        return items.map { item ->
            if (item.status == BulkInstallStatus.SourceError ||
                item.status == BulkInstallStatus.RemoteSourceUnsupported ||
                item.status == BulkInstallStatus.BatchConflict
            ) {
                return@map item
            }
            val key = buildString {
                append(item.unit.kind.name).append('\u0000')
                item.descriptorAttributes.toSortedMap().forEach { (name, value) ->
                    append(name).append('=').append(value).append('\u0001')
                }
                append('\u0000').append(item.jarFingerprint.orEmpty())
            }
            val original = seen[key]
            if (original == null) {
                seen[key] = item.id
                item
            } else {
                item.copy(
                    status = BulkInstallStatus.Duplicate,
                    action = BulkInstallAction.Skip,
                    selected = false,
                    detail = "Duplicate of another source in this batch",
                )
            }
        }
    }

    private fun applyBatchVersionGrouping(items: List<BulkInstallItem>): List<BulkInstallItem> {
        val replacements = items.toMutableList()
        val grouped = items.withIndex()
            .filter { (_, item) ->
                item.name.isNotBlank() &&
                    item.vendor.isNotBlank() &&
                    item.status !in setOf(
                        BulkInstallStatus.SourceError,
                        BulkInstallStatus.RemoteSourceUnsupported,
                        BulkInstallStatus.Duplicate,
                        BulkInstallStatus.BatchConflict,
                    )
            }
            .groupBy { it.value.groupKey }

        grouped.values.forEach { group ->
            if (group.size <= 1) return@forEach
            val maxima = group.filter { candidate ->
                group.none { other -> compareVersions(other.value.version, candidate.value.version) > 0 }
            }
            if (maxima.size > 1) {
                val semanticKeys = maxima
                    .map {
                        Triple(
                            it.value.unit.kind,
                            it.value.descriptorAttributes,
                            it.value.jarFingerprint,
                        )
                    }
                    .distinct()
                if (semanticKeys.size > 1) {
                    maxima.forEach { indexedItem ->
                        replacements[indexedItem.index] = indexedItem.value.copy(
                            status = BulkInstallStatus.BatchConflict,
                            action = BulkInstallAction.Skip,
                            selected = false,
                            detail = "Multiple variants have the same version ordering",
                        )
                    }
                }
            }
            val maxVersion = maxima.firstOrNull()?.value?.version ?: return@forEach
            group.filter { compareVersions(it.value.version, maxVersion) < 0 }.forEach { indexedItem ->
                replacements[indexedItem.index] = indexedItem.value.copy(
                    status = BulkInstallStatus.OlderBatchCandidate,
                    action = BulkInstallAction.Skip,
                    selected = false,
                    detail = "A newer candidate for this application exists in the same batch",
                )
            }
        }
        return replacements
    }

    /** Mirrors Descriptor.compareVersion without introducing SemVer/lexical ordering. */
    internal fun compareVersions(incoming: String, other: String): Int {
        val incomingParts = incoming.split('.')
        val otherParts = other.split('.')
        val length = maxOf(incomingParts.size, otherParts.size)
        repeat(length) { index ->
            val left = incomingParts.getOrNull(index)?.trim()?.toIntOrNull() ?: 0
            val right = otherParts.getOrNull(index)?.trim()?.toIntOrNull() ?: 0
            if (left != right) return Integer.signum(left - right)
        }
        return 0
    }

    private fun requireReadyGeneration(library: LibraryViewModel): LibraryGenerationToken =
        requireNotNull(library.readyGeneration()) { "Library is not READY" }

    private fun sourceFingerprint(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(hashFileBytes(file))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashFile(file: File): String =
        hashFileBytes(file).joinToString("") { "%02x".format(it) }

    private fun hashFileBytes(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun canonicalReadableFile(file: File): File? = try {
        file.canonicalFile.takeIf { it.isFile && it.canRead() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (_: IOException) {
        file.absolutePath
    } catch (_: SecurityException) {
        file.absolutePath
    }

    private fun isSupportedSource(file: File): Boolean = extension(file) in supportedExtensions

    private fun checkActive(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("Bulk source planning cancelled")
    }

    private fun extension(file: File): String = file.extension.lowercase(Locale.ROOT)

    private fun boundedMessage(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        val text = if (message.isBlank()) error.javaClass.simpleName else message
        return text.take(512)
    }
}
