/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.ProfilesManager
import io.github.h3nb.jlmodplus.jar.Descriptor
import io.github.h3nb.jlmodplus.jar.MidletDescriptionLocalization
import java.io.File
import javax.microedition.shell.MidletLocaleResolver

/**
 * Reconstructible MIDlet descriptions live in installed descriptors, not user-owned Room data.
 * Cache parsed descriptors and profile properties across Library projections; a changed file is
 * read on the next Library refresh. Searching never opens a JAR or touches the UI thread.
 */
internal class LibraryDescriptionCatalog {
    private data class Stamp(val modified: Long, val size: Long)
    private data class Cached(
        val descriptorStamp: Stamp,
        val configStamp: Stamp,
        val legacyConfigStamp: Stamp,
        val descriptions: Map<String, String>,
        val systemProperties: String?,
    )

    private var root: File? = null
    private val entries = HashMap<String, Cached>()

    fun resolve(
        emulatorDir: File,
        apps: List<LibraryAppRow>,
        deviceLocale: String?,
        overridesByApp: Map<Long, Map<String, String>> = emptyMap(),
        checkActive: () -> Unit = {},
    ): Map<Long, String> {
        if (root != emulatorDir) {
            root = emulatorDir
            entries.clear()
        }
        val activeKeys = HashSet<String>(apps.size)
        val localized = HashMap<Long, String>()
        for ((index, app) in apps.withIndex()) {
            if (index % 64 == 0) checkActive()
            activeKeys.add(app.storageKey)
            val descriptorFile = File(emulatorDir, "converted/${app.storageKey}/converted.dex.conf")
            val configDir = File(emulatorDir, "configs/${app.storageKey}")
            val configFile = File(configDir, "config.json")
            val legacyConfigFile = File(configDir, "config.xml")
            val descriptorStamp = descriptorFile.stamp()
            val configStamp = configFile.stamp()
            val legacyConfigStamp = legacyConfigFile.stamp()
            val prior = entries[app.storageKey]
            val entry = if (prior != null && prior.descriptorStamp == descriptorStamp
                && prior.configStamp == configStamp
                && prior.legacyConfigStamp == legacyConfigStamp) {
                prior
            } else {
                val descriptions = readSourceDescriptions(descriptorFile)
                val systemProperties = try {
                    if (configFile.isFile || legacyConfigFile.isFile) {
                        ProfilesManager.loadPreparedMidletConfig(configDir, false)?.systemProperties
                    } else null
                } catch (_: Exception) {
                    null
                }
                Cached(descriptorStamp, configStamp, legacyConfigStamp, descriptions, systemProperties)
                    .also { entries[app.storageKey] = it }
            }
            val overrides = overridesByApp[app.id].orEmpty()
            if (entry.descriptions.isEmpty() && overrides.isEmpty()) continue
            val effectiveLocale = MidletLocaleResolver.withSystemProperties(
                deviceLocale, entry.systemProperties)
            val text = MidletDescriptionLocalization.resolve(
                entry.descriptions + overrides, effectiveLocale, app.description)
            if (text != null && text != app.description) localized[app.id] = text
        }
        entries.keys.retainAll(activeKeys)
        checkActive()
        return localized
    }

    private fun File.stamp(): Stamp = Stamp(lastModified(), length())

    companion object {
        /** Descriptor content is reconstructible; callers perform disk reads off the UI thread. */
        fun readSourceDescriptions(emulatorDir: File, storageKey: String): Map<String, String> =
            readSourceDescriptions(File(emulatorDir, "converted/$storageKey/converted.dex.conf"))

        private fun readSourceDescriptions(file: File): Map<String, String> = try {
            if (file.isFile) MidletDescriptionLocalization.extract(Descriptor(file, false).attrs)
            else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
