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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAppBundleReaderTest {
    @Test
    fun readsUniversalSingleAppManifestAndNamespace() {
        val bundle = zip(
            "bundle.json" to manifest(
                bundleId = "a0001",
                title = "Demo MIDlet",
                vendor = "Example Vendor",
                version = "1.0",
            ),
            "apps/a0001/app/res.jar" to byteArrayOf(1),
            "apps/a0001/config/" to byteArrayOf(),
            "apps/a0001/data/save.bin" to byteArrayOf(2),
        )

        val parsed = LibraryAppBundleReader.read(ByteArrayInputStream(bundle))

        assertEquals(2, parsed.formatVersion)
        assertFalse(parsed.legacyAssurance)
        assertEquals(1, parsed.apps.size)
        assertEquals("a0001", parsed.apps.single().bundleId)
        assertEquals(BundleNamespaceState.PresentEmpty, parsed.apps.single().configState)
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().dataState)
        assertTrue(parsed.apps.single().sourceSha256!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun adaptsUnversionedLegacyPayloadToOneLogicalApp() {
        val parsed = LibraryAppBundleReader.read(
            ByteArrayInputStream(zip("app/res.jar" to byteArrayOf(1))),
        )

        assertEquals(0, parsed.formatVersion)
        assertTrue(parsed.legacyAssurance)
        assertEquals("legacy", parsed.apps.single().bundleId)
        assertEquals(null, parsed.apps.single().sourceSha256)
    }

    @Test
    fun rejectsOrphanNamespacesAndUnknownEntries() {
        assertReadFails(
            zip(
                "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0"),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
                "apps/a0002/app/res.jar" to byteArrayOf(2),
            ),
        )
        assertReadFails(
            zip(
                "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0"),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
                "apps/a0001/other/file.bin" to byteArrayOf(2),
            ),
        )
    }

    @Test
    fun rejectsInvalidHashAndTraversal() {
        assertReadFails(
            zip(
                "bundle.json" to manifest(
                    bundleId = "a0001",
                    title = "Demo",
                    vendor = "Vendor",
                    version = "1.0",
                    hash = "not-a-hash",
                ),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
            ),
        )
        assertReadFails(
            zip(
                "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0"),
                "apps/a0001/app/../res.jar" to byteArrayOf(1),
            ),
        )
    }

    @Test
    fun rejectsNamespaceStateThatDoesNotMatchZipEntries() {
        assertReadFails(
            zip(
                "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0", configState = "absent"),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
                "apps/a0001/config/" to byteArrayOf(),
            ),
        )
        assertReadFails(
            zip(
                "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0", configState = "present-empty"),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
                "apps/a0001/config/settings.json" to byteArrayOf(1),
            ),
        )
    }

    @Test
    fun rejectsMalformedManifestTypesAsIoException() {
        assertReadFails(
            zip(
                "bundle.json" to """
                    {"schema":"${LibraryAppBundleFormat.UNIVERSAL_SCHEMA}","formatVersion":2,"apps":[{"bundleId":{},"title":"Demo","vendor":"Vendor","version":"1.0","payloadRoot":"apps/a0001/","sourceSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","configState":"absent","dataState":"absent"}]}
                """.trimIndent().toByteArray(),
                "apps/a0001/app/res.jar" to byteArrayOf(1),
            ),
        )
        assertReadFails(
            zip(
                "bundle.json" to """{"formatVersion":{}}""".toByteArray(),
                "app/res.jar" to byteArrayOf(1),
            ),
        )
    }

    @Test
    fun rejectsBundlesThatExceedRoutingUncompressedByteLimit() {
        val payload = ByteArray(2_048) { 7 }
        val bundle = zip(
            "bundle.json" to manifest("a0001", "Demo", "Vendor", "1.0"),
            "apps/a0001/app/res.jar" to payload,
        )

        try {
            LibraryAppBundleReader.read(
                ByteArrayInputStream(bundle),
                maxTotalBytes = 1_024,
            )
            throw AssertionError("Expected the routing byte limit to reject the bundle")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("too large"))
        }
    }

    @Test
    fun verifiesStagedSourceHashAgainstManifestValue() {
        val file = File.createTempFile("bundle-reader", ".jar")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val expected = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            LibraryAppBundleReader.verifySourceHash(file, expected)
            try {
                LibraryAppBundleReader.verifySourceHash(file, "0".repeat(64))
                throw AssertionError("Expected source hash mismatch")
            } catch (_: IOException) {
                // Expected.
            }
        } finally {
            file.delete()
        }
    }

    private fun assertReadFails(bytes: ByteArray) {
        try {
            LibraryAppBundleReader.read(ByteArrayInputStream(bytes))
            throw AssertionError("Expected bundle reader to reject input")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun manifest(
        bundleId: String,
        title: String,
        vendor: String,
        version: String,
        hash: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        configState: String = "present-empty",
    ): ByteArray {
        return """
            {"schema":"${LibraryAppBundleFormat.UNIVERSAL_SCHEMA}","formatVersion":2,"apps":[{"bundleId":"$bundleId","title":"$title","vendor":"$vendor","version":"$version","payloadRoot":"apps/$bundleId/","sourceSha256":"$hash","configState":"$configState","dataState":"present"}]}
        """.trimIndent().toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
