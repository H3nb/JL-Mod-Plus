/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryDescriptionCatalogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun existingInstallUsesInterfaceLocaleAndKeepsCustomFallback() {
        val workdir = temporaryFolder.newFolder("emulator")
        val gameDir = File(workdir, "converted/game").apply { mkdirs() }
        File(gameDir, "converted.dex.conf").writeText(
            "MIDlet-Name: Adventure\n" +
                "MIDlet-Vendor: Example\n" +
                "MIDlet-Version: 1.0\n" +
                "MIDlet-Description: Original\n" +
                "MIDlet-Description-id: Petualangan\n" +
                "MIDlet-Description-en: Adventure\n",
        )
        val app = app("Edited default")
        val catalog = LibraryDescriptionCatalog()

        val indonesian = catalog.resolve(workdir, listOf(app), "id-ID")
        assertEquals("Petualangan", indonesian[app.id])
        val search = LibraryListProjection.project(
            LibraryListProjection.prepare(listOf(app), 0, Locale.US, LibraryQuickView.All),
            "petualangan", LibraryQuickView.All, indonesian,
        )
        assertEquals(listOf(app.id), search.map { it.id })

        assertEquals("Adventure", catalog.resolve(workdir, listOf(app), "en-US")[app.id])
        assertTrue(catalog.resolve(workdir, listOf(app), "fr-FR").isEmpty())
        assertEquals("Edited default", app.description)
    }

    @Test fun gameConfigurationOverrideMatchesRuntimeLocale() {
        val workdir = temporaryFolder.newFolder("configured")
        val gameDir = File(workdir, "converted/game").apply { mkdirs() }
        File(gameDir, "converted.dex.conf").writeText(
            "MIDlet-Name: Adventure\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n" +
                "MIDlet-Description-id: Indonesia\nMIDlet-Description-en: English\n",
        )
        val configDir = File(workdir, "configs/game").apply { mkdirs() }
        val config = File(configDir, "config.json")
        config.writeText(
            """{"Version":7,"SystemProperties":"microedition.locale: en-US"}""",
        )
        val app = app("Default")
        val catalog = LibraryDescriptionCatalog()
        assertEquals("English", catalog.resolve(workdir, listOf(app), "id-ID")[app.id])
        // A profile change must invalidate the metadata cache without reinstalling the game.
        config.writeText(
            """{"Version":7,"SystemProperties":"microedition.locale: id"}""",
        )
        assertEquals("Indonesia", catalog.resolve(workdir, listOf(app), "id-ID")[app.id])
    }

    @Test fun replacingDescriptorRefreshesLocalizedMetadata() {
        val workdir = temporaryFolder.newFolder("replacement")
        val gameDir = File(workdir, "converted/game").apply { mkdirs() }
        val descriptor = File(gameDir, "converted.dex.conf")
        val header = "MIDlet-Name: Adventure\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n"
        descriptor.writeText(header + "MIDlet-Description-id: Sebelum\n")
        val catalog = LibraryDescriptionCatalog()
        val app = app("Default")
        assertEquals("Sebelum", catalog.resolve(workdir, listOf(app), "id-ID")[app.id])
        descriptor.writeText(header + "MIDlet-Description-id: Sesudah pembaruan\n")
        assertEquals("Sesudah pembaruan", catalog.resolve(workdir, listOf(app), "id-ID")[app.id])
    }

    @Test fun userTranslationsOverrideDescriptorsAndUseTheSameFallbackChain() {
        val root = temporaryFolder.newFolder("localized-overrides")
        File(root, "converted/game").apply { mkdirs() }
        File(root, "converted/game/converted.dex.conf").writeText(
            "MIDlet-Name: Adventure\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n" +
                "MIDlet-Description-en: Source English\n" +
                "MIDlet-Description-en-US: Source American\n",
        )
        val catalog = LibraryDescriptionCatalog()
        val game = app("Edited default")
        val overrides = mapOf(game.id to mapOf(
            "en" to "User English", "id" to "Bahasa Indonesia",
        ))
        // A regional source is more specific than an overridden base language.
        assertEquals("Source American",
            catalog.resolve(root, listOf(game), "en-US", overrides)[game.id])
        assertEquals("User English",
            catalog.resolve(root, listOf(game), "en-GB", overrides)[game.id])
        assertEquals("Bahasa Indonesia",
            catalog.resolve(root, listOf(game), "id-ID", overrides)[game.id])
        assertTrue(catalog.resolve(root, listOf(game), "fr-FR", overrides).isEmpty())
        // Removing a user override reveals the retained descriptor.
        assertEquals("Source English",
            catalog.resolve(root, listOf(game), "en-GB")[game.id])
    }

    @Test fun userTranslationWithoutDescriptorUsesConfiguredGuestLocale() {
        val root = temporaryFolder.newFolder("user-only")
        File(root, "converted/game").apply { mkdirs() }
        File(root, "converted/game/converted.dex.conf").writeText(
            "MIDlet-Name: Adventure\nMIDlet-Vendor: Example\nMIDlet-Version: 1.0\n",
        )
        val config = File(root, "configs/game").apply { mkdirs() }
        File(config, "config.json").writeText(
            """{"Version":7,"SystemProperties":"microedition.locale: en-US"}""",
        )
        val game = app("Default")
        val overrides = mapOf(game.id to mapOf("en" to "User English"))
        assertEquals("User English",
            LibraryDescriptionCatalog().resolve(root, listOf(game), "id-ID", overrides)[game.id])
    }

    private fun app(description: String) = LibraryAppRow(
        id = 7,
        storageKey = "game",
        sourceTitle = "Adventure",
        sourceVendor = "Example",
        sourceVersion = "1.0",
        title = "Adventure",
        vendor = "Example",
        version = "1.0",
        description = description,
        favorite = false,
        addedAt = null,
        lastPlayedAt = null,
        iconRevision = 0L,
    )
}
