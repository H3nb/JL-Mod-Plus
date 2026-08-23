/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryAppBundleImporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun currentExportPayloadExtractsAuthoritativeImportState() {
        val staging = temporaryFolder.newFolder("staging")
        val prepared = LibraryAppBundleImporter.extractToStaging(
            bundle(
                "bundle.json" to "{\"formatVersion\":1}\n".toByteArray(),
                "app/res.jar" to byteArrayOf(1, 2, 3),
                "app/converted.dex.conf" to "converted".toByteArray(),
                "app/icon.png" to byteArrayOf(9, 8, 7),
                "config/config.json" to "config".toByteArray(),
                "data/nested/save.bin" to byteArrayOf(4, 5, 6),
            ),
            staging,
        )

        assertEquals(byteArrayOf(1, 2, 3).toList(), prepared.jarFile.readBytes().toList())
        assertEquals("converted", prepared.convertedConfigFile!!.readText())
        assertEquals("config", File(prepared.configDir, "config.json").readText())
        assertEquals(byteArrayOf(4, 5, 6).toList(), File(prepared.dataDir, "nested/save.bin").readBytes().toList())
        assertFalse(File(staging, "icon.png").exists())
    }

    @Test fun universalSinglePayloadMapsIntoTheSharedRestoreShape() {
        val staging = temporaryFolder.newFolder("universal-staging")
        val jar = byteArrayOf(1, 2, 3)
        val descriptor = "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n"
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            input = bundle(
                "bundle.json" to "{}".toByteArray(),
                "apps/a0001/" to byteArrayOf(),
                "apps/a0001/app/res.jar" to jar,
                "apps/a0001/app/converted.dex.conf" to descriptor.toByteArray(),
                "apps/a0001/config/" to byteArrayOf(),
                "apps/a0001/data/save.bin" to byteArrayOf(9),
            ),
            staging = staging,
            app = BundleApp(
                bundleId = "a0001",
                title = "Game",
                vendor = "Vendor",
                version = "1.0",
                payloadRoot = "apps/a0001/",
                sourceSha256 = null,
                configState = BundleNamespaceState.PresentEmpty,
                dataState = BundleNamespaceState.Present,
            ),
            parseSourceMetadata = true,
        )

        assertEquals(jar.toList(), prepared.jarFile.readBytes().toList())
        assertEquals(descriptor, prepared.convertedConfigFile!!.readText())
        assertTrue(prepared.configDir!!.isDirectory)
        assertEquals(
            byteArrayOf(9).toList(),
            File(requireNotNull(prepared.dataDir), "save.bin").readBytes().toList(),
        )
        assertEquals("Game", LibraryAppBundleImporter.readSourceMetadata(prepared)!!.title)
    }

    @Test fun universalBatchPayloadIsExtractedInOnePassPerManifestOrder() {
        val staging = temporaryFolder.newFolder("universal-batch-staging")
        val apps = listOf(
            BundleApp(
                bundleId = "a0001",
                title = "First",
                vendor = "Vendor",
                version = "1.0",
                payloadRoot = "apps/a0001/",
                sourceSha256 = null,
                configState = BundleNamespaceState.Absent,
                dataState = BundleNamespaceState.Absent,
            ),
            BundleApp(
                bundleId = "a0002",
                title = "Second",
                vendor = "Vendor",
                version = "2.0",
                payloadRoot = "apps/a0002/",
                sourceSha256 = null,
                configState = BundleNamespaceState.Present,
                dataState = BundleNamespaceState.PresentEmpty,
            ),
        )

        val prepared = LibraryAppBundleImporter.extractUniversalBatchToStaging(
            input = bundle(
                "bundle.json" to "{}".toByteArray(),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
                "apps/a0002/app/res.jar" to byteArrayOf(2),
                "apps/a0002/config/config.json" to byteArrayOf(3),
                "apps/a0002/data/" to byteArrayOf(),
            ),
            staging = staging,
            apps = apps,
        )

        assertEquals(listOf("a0001", "a0002"), prepared.map { it.app.bundleId })
        assertEquals(byteArrayOf(1).toList(), prepared[0].prepared.jarFile.readBytes().toList())
        assertEquals(byteArrayOf(2).toList(), prepared[1].prepared.jarFile.readBytes().toList())
        assertEquals(byteArrayOf(3).toList(), File(requireNotNull(prepared[1].prepared.configDir), "config.json").readBytes().toList())
        assertTrue(requireNotNull(prepared[1].prepared.dataDir).isDirectory)
    }

    @Test fun sourceMetadataPreservesDescriptorOverridesFromOriginalInstall() {
        val descriptor = """
            MIDlet-Name: Game
            MIDlet-Vendor: Vendor
            MIDlet-Version: 9.9
            MIDlet-Description: Description supplied by JAD
        """.trimIndent() + "\n"
        val prepared = LibraryAppBundleImporter.extractToStaging(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "app/converted.dex.conf" to descriptor.toByteArray(),
            ),
            temporaryFolder.newFolder("source-metadata"),
        )

        val metadata = requireNotNull(LibraryAppBundleImporter.readSourceMetadata(prepared))

        assertEquals("Game", metadata.title)
        assertEquals("Vendor", metadata.vendor)
        assertEquals("9.9", metadata.version)
        assertEquals("Description supplied by JAD", metadata.description)
    }

    @Test fun preflightIdentityRejectsDescriptorFromDifferentApp() {
        val descriptor = """
            MIDlet-Name: Game
            MIDlet-Vendor: Vendor
            MIDlet-Version: 9.9-jad
            MIDlet-Description: Restored description
        """.trimIndent() + "\n"
        val prepared = LibraryAppBundleImporter.extractToStaging(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "app/converted.dex.conf" to descriptor.toByteArray(),
            ),
            temporaryFolder.newFolder("preflight-identity"),
            parseSourceMetadata = true,
        )

        LibraryAppBundleImporter.validateSourceIdentity(prepared, "Game", "Vendor")
        try {
            LibraryAppBundleImporter.validateSourceIdentity(prepared, "Different Game", "Vendor")
            throw AssertionError("Expected mismatched bundle descriptor identity to fail")
        } catch (_: IOException) {
            // Expected before the installer is allowed to publish the retained JAR.
        }
    }

    @Test fun oversizedConvertedDescriptorIsRejectedDuringExtraction() {
        assertImportFails(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "app/converted.dex.conf" to ByteArray(1024 * 1024 + 1) { 'A'.code.toByte() },
            ),
        )
    }

    @Test fun staleUuidImportStagingIsCleanedConservatively() {
        val importRoot = temporaryFolder.newFolder("library-import")
        val stale = File(importRoot, UUID.randomUUID().toString()).apply {
            mkdirs()
            File(this, "payload").writeText("stale")
            setLastModified(1_000L)
        }
        val recent = File(importRoot, UUID.randomUUID().toString()).apply {
            mkdirs()
            File(this, "payload").writeText("active")
            setLastModified(9_000L)
        }
        val unrelated = File(importRoot, "keep-me").apply {
            mkdirs()
            File(this, "payload").writeText("unrelated")
            setLastModified(1_000L)
        }

        val removed = LibraryAppBundleImporter.cleanupStaleStaging(
            importRoot = importRoot,
            nowMillis = 10_000L,
            maxAgeMillis = 5_000L,
        )

        assertEquals(1, removed)
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun traversalAndUnsupportedEntriesAreRejected() {
        assertImportFails(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "config/../../outside" to byteArrayOf(2),
            ),
        )
        assertImportFails(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "unexpected/payload.bin" to byteArrayOf(3),
            ),
        )
    }

    @Test fun retainedJarIsRequired() {
        assertImportFails(bundle("config/config.json" to byteArrayOf(1)))
    }

    @Test fun restoreReplacesOnlyNamespacesPresentInBundle() {
        val descriptor = """
            MIDlet-Name: Game
            MIDlet-Vendor: Vendor
            MIDlet-Version: 1.0
        """.trimIndent() + "\n"
        val workdir = temporaryFolder.newFolder("workdir")
        val converted = File(File(workdir, "converted"), "game").apply { mkdirs() }
        val config = File(File(workdir, "configs"), "game").apply { mkdirs() }
        val data = File(File(workdir, "data"), "game").apply { mkdirs() }
        File(converted, "res.jar").writeBytes(byteArrayOf(7))
        File(converted, "converted.dex.conf").writeText(descriptor)
        File(config, "old.cfg").writeText("old config")
        File(data, "keep.sav").writeText("existing save")

        val staging = temporaryFolder.newFolder("restore-staging")
        val prepared = LibraryAppBundleImporter.extractToStaging(
            bundle(
                "app/res.jar" to byteArrayOf(1),
                "app/converted.dex.conf" to descriptor.toByteArray(),
                "config/new.cfg" to "new config".toByteArray(),
            ),
            staging,
        )

        LibraryAppBundleImporter.restore(prepared, workdir, "game")

        assertFalse(File(config, "old.cfg").exists())
        assertEquals("new config", File(config, "new.cfg").readText())
        assertEquals(descriptor, File(converted, "converted.dex.conf").readText())
        assertEquals("existing save", File(data, "keep.sav").readText())
        assertTrue(File(converted, "res.jar").isFile)
    }

    @Test fun legacyUnversionedBundleRemainsReadableButFutureFormatIsRejected() {
        val legacy = LibraryAppBundleImporter.extractToStaging(
            bundle("app/res.jar" to byteArrayOf(1)),
            temporaryFolder.newFolder("legacy-format"),
        )
        assertEquals(0, legacy.formatVersion)

        assertImportFails(
            bundle(
                "bundle.json" to "{\"formatVersion\":2}\n".toByteArray(),
                "app/res.jar" to byteArrayOf(1),
            ),
        )
    }

    @Test fun midPublishFailureRollsEveryNamespaceBack() {
        val workdir = temporaryFolder.newFolder("rollback-workdir")
        val config = File(File(workdir, "configs"), "game").apply { mkdirs() }
        val data = File(File(workdir, "data"), "game").apply { mkdirs() }
        val converted = File(File(workdir, "converted"), "game").apply { mkdirs() }
        File(config, "old.cfg").writeText("old config")
        File(data, "old.sav").writeText("old save")
        val oldDescriptor = "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n"
        File(converted, "converted.dex.conf").writeText(oldDescriptor)
        File(converted, "res.jar").writeBytes(byteArrayOf(7))

        val prepared = LibraryAppBundleImporter.extractToStaging(
            bundle(
                "bundle.json" to "{\"formatVersion\":1}\n".toByteArray(),
                "app/res.jar" to byteArrayOf(1),
                "app/converted.dex.conf" to "new descriptor".toByteArray(),
                "config/new.cfg" to "new config".toByteArray(),
                "data/new.sav" to "new save".toByteArray(),
            ),
            temporaryFolder.newFolder("rollback-staging"),
        )

        try {
            LibraryAppBundleImporter.restoreWithPublishHook(prepared, workdir, "game") { published ->
                if (published == 1) throw IOException("Injected publish failure")
            }
            throw AssertionError("Expected injected restore failure")
        } catch (_: IOException) {
            // Expected.
        }

        assertEquals("old config", File(config, "old.cfg").readText())
        assertEquals("old save", File(data, "old.sav").readText())
        assertEquals(oldDescriptor, File(converted, "converted.dex.conf").readText())
        assertFalse(File(config, "new.cfg").exists())
        assertFalse(File(data, "new.sav").exists())
        assertFalse(File(workdir, ".library-import-transactions").exists())
    }

    @Test fun preparedTransactionIsRecoveredAfterSimulatedProcessDeath() {
        val workdir = temporaryFolder.newFolder("crash-recovery-workdir")
        val configParent = File(workdir, "configs").apply { mkdirs() }
        val target = File(configParent, "game").apply { mkdirs() }
        File(target, "new.cfg").writeText("new")
        val backup = File(configParent, ".game.tx.import.bak").apply { mkdirs() }
        File(backup, "old.cfg").writeText("old")
        val staged = File(configParent, ".game.tx.import.tmp")
        val converted = File(File(workdir, "converted"), "game").apply { mkdirs() }
        File(converted, "res.jar").writeBytes(byteArrayOf(7))
        File(converted, "converted.dex.conf").writeText(
            "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )

        val transactionDir = File(workdir, ".library-import-transactions").apply { mkdirs() }
        val marker = File(transactionDir, "tx.txn")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("storageKey", "game")
            setProperty("syncIcon", "false")
            setProperty("count", "1")
            setProperty("target.0", target.absolutePath)
            setProperty("staged.0", staged.absolutePath)
            setProperty("backup.0", backup.absolutePath)
            setProperty("hadOriginal.0", "true")
        }
        marker.outputStream().use { properties.store(it, null) }

        LibraryAppBundleImporter.recoverInterruptedRestores(workdir)

        assertEquals("old", File(target, "old.cfg").readText())
        assertFalse(File(target, "new.cfg").exists())
        assertFalse(backup.exists())
        assertFalse(transactionDir.exists())
    }

    @Test fun preparedTransactionWithoutPublishedTargetCleansPartialStaging() {
        val workdir = temporaryFolder.newFolder("partial-staging-workdir")
        val configParent = File(workdir, "configs").apply { mkdirs() }
        val target = File(configParent, "game")
        val staged = File(configParent, ".game.tx.import.tmp").apply {
            mkdirs()
            File(this, "partial.cfg").writeText("partial")
        }
        val backup = File(configParent, ".game.tx.import.bak")
        val transactionDir = File(workdir, ".library-import-transactions").apply { mkdirs() }
        val marker = File(transactionDir, "tx.txn")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("storageKey", "game")
            setProperty("syncIcon", "false")
            setProperty("count", "1")
            setProperty("target.0", target.absolutePath)
            setProperty("staged.0", staged.absolutePath)
            setProperty("backup.0", backup.absolutePath)
            setProperty("hadOriginal.0", "false")
        }
        marker.outputStream().use { properties.store(it, null) }

        LibraryAppBundleImporter.recoverInterruptedRestores(workdir)

        assertFalse(target.exists())
        assertFalse(staged.exists())
        assertFalse(transactionDir.exists())
    }

    @Test fun committedTransactionKeepsNewStateAndFinishesCleanupAfterProcessDeath() {
        val workdir = temporaryFolder.newFolder("commit-recovery-workdir")
        val configParent = File(workdir, "configs").apply { mkdirs() }
        val target = File(configParent, "game").apply { mkdirs() }
        File(target, "new.cfg").writeText("new")
        val backup = File(configParent, ".game.tx.import.bak").apply { mkdirs() }
        File(backup, "old.cfg").writeText("old")
        val staged = File(configParent, ".game.tx.import.tmp")

        val transactionDir = File(workdir, ".library-import-transactions").apply { mkdirs() }
        val marker = File(transactionDir, "tx.txn")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("storageKey", "game")
            setProperty("syncIcon", "false")
            setProperty("count", "1")
            setProperty("target.0", target.absolutePath)
            setProperty("staged.0", staged.absolutePath)
            setProperty("backup.0", backup.absolutePath)
            setProperty("hadOriginal.0", "true")
        }
        marker.outputStream().use { properties.store(it, null) }
        File(transactionDir, "tx.commit").writeText("1")

        LibraryAppBundleImporter.recoverInterruptedRestores(workdir)

        assertEquals("new", File(target, "new.cfg").readText())
        assertFalse(File(target, "old.cfg").exists())
        assertFalse(backup.exists())
        assertFalse(transactionDir.exists())
    }

    @Test fun recoveryRejectsTransactionThatTargetsUnrelatedWorkdirData() {
        val workdir = temporaryFolder.newFolder("tampered-recovery-workdir")
        val unrelated = File(workdir, "unrelated.txt").apply { writeText("keep") }
        val transactionDir = File(workdir, ".library-import-transactions").apply { mkdirs() }
        val marker = File(transactionDir, "tx.txn")
        val staged = File(workdir, ".unrelated.txt.tx.import.tmp")
        val backup = File(workdir, ".unrelated.txt.tx.import.bak")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("storageKey", "game")
            setProperty("syncIcon", "false")
            setProperty("count", "1")
            setProperty("target.0", unrelated.absolutePath)
            setProperty("staged.0", staged.absolutePath)
            setProperty("backup.0", backup.absolutePath)
            setProperty("hadOriginal.0", "true")
        }
        marker.outputStream().use { properties.store(it, null) }

        try {
            LibraryAppBundleImporter.recoverInterruptedRestores(workdir)
            throw AssertionError("Expected tampered recovery transaction to be rejected")
        } catch (_: IOException) {
            // Expected.
        }

        assertEquals("keep", unrelated.readText())
        assertTrue(marker.exists())
    }

    private fun assertImportFails(input: ByteArrayInputStream) {
        val staging = temporaryFolder.newFolder("bad-${System.nanoTime()}")
        try {
            LibraryAppBundleImporter.extractToStaging(input, staging)
            throw AssertionError("Expected import validation to fail")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun bundle(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
