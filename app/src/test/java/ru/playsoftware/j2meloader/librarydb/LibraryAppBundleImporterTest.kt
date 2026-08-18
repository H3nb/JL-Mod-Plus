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
