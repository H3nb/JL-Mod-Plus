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
