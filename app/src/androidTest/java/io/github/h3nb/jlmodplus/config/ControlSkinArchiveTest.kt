package io.github.h3nb.jlmodplus.config

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlSkinArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportImportPreservesArtworkMetadataAndAvoidsDuplicateCopies() {
        val source = temporary.newFolder("source")
        val set = ControlSkinStore.create(source, "Fantasy")
        val image = png(Color.RED)
        ControlSkinStore.importImage(source, set.id, "fire", false, image.inputStream(), ".png")
        ControlSkinStore.setMode(source, set.id, "fire", "full")
        val exportDirectory = temporary.newFolder("exports")

        val archive = ControlSkinArchive.export(source, set.id, exportDirectory)
        assertEquals("Fantasy.zip", archive.name)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("Fantasy/skin.json") != null)
            assertTrue(zip.getEntry("Fantasy/key_fire.png") != null)
        }
        val another = ControlSkinArchive.export(source, set.id, exportDirectory)
        assertEquals("Fantasy (2).zip", another.name)

        val destination = temporary.newFolder("destination")
        val imported = ControlSkinArchive.importZip(destination, archive, temporary.root)
        assertTrue(imported.added)
        assertEquals("Fantasy", imported.name)
        assertEquals(set.id, imported.id)
        val installed = ControlSkinStore.findReadonly(destination, imported.id)!!
        assertArrayEquals(image, File(installed.folder, "key_fire.png").readBytes())
        assertEquals("full", ControlSkinStore.mode(installed.folder, "fire"))

        val repeated = ControlSkinArchive.importZip(destination, archive, temporary.root)
        assertFalse(repeated.added)
        assertEquals(imported.id, repeated.id)
        assertEquals(1, ControlSkinStore.list(destination).size)
    }

    @Test fun collisionCreatesIndependentSetAndRepeatedImportReusesIt() {
        val source = temporary.newFolder("source")
        val set = ControlSkinStore.create(source, "Fantasy")
        ControlSkinStore.importImage(
            source, set.id, "1", false, png(Color.BLUE).inputStream(), ".png",
        )
        val archive = ControlSkinArchive.export(source, set.id, temporary.newFolder("out"))
        val target = temporary.newFolder("target")
        val original = ControlSkinStore.create(target, "Fantasy")
        val id = set.id
        File(original.folder, "skin.json").writeText(
            """{"version":1,"id":"$id","defaultMode":"icon"}""",
        )
        val previousImage = png(Color.GREEN)
        ControlSkinStore.importImage(
            target, set.id, "1", false, previousImage.inputStream(), ".png",
        )

        val imported = ControlSkinArchive.importZip(target, archive, temporary.root)
        assertTrue(imported.added)
        assertNotEquals(set.id, imported.id)
        assertEquals("Fantasy (2)", imported.name)
        assertArrayEquals(previousImage, File(original.folder, "key_1.png").readBytes())

        val repeated = ControlSkinArchive.importZip(target, archive, temporary.root)
        assertFalse(repeated.added)
        assertEquals(imported.id, repeated.id)
        assertEquals(2, ControlSkinStore.list(target).size)
    }

    @Test fun invalidZipCannotCreateOrModifyGlobalSets() {
        val target = temporary.newFolder("target")
        val existing = ControlSkinStore.create(target, "Keep")
        val source = File(temporary.root, "bad.zip")
        val id = existing.id
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Fantasy/skin.json"))
            zip.write("""{"version":1,"id":"$id"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Fantasy/../outside.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        expectIOException {
            ControlSkinArchive.importZip(target, source, temporary.root)
        }
        assertEquals(listOf("Keep"), ControlSkinStore.list(target).map { it.name })
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".control-skin-") })
    }

    @Test fun exportRefusesDestinationsInsideManagedSkinDirectory() {
        val workdir = temporary.newFolder("managed")
        val set = ControlSkinStore.create(workdir, "Fantasy")
        val skinRoot = ControlSkinStore.directory(workdir)
        for (directory in listOf(skinRoot, set.folder)) {
            expectIOException {
                ControlSkinArchive.export(workdir, set.id, directory)
            }
        }
        assertEquals(listOf("Fantasy"), ControlSkinStore.list(workdir).map { it.name })
        assertTrue(set.folder.listFiles()!!.all { it.extension != "zip" })
    }

    @Test fun exportNormalizesLegacyMetadataWithoutChangingTheSource() {
        val workdir = temporary.newFolder("legacy")
        val set = ControlSkinStore.create(workdir, "Legacy")
        val metadata = File(set.folder, "skin.json")
        val old = """{"id":"${set.id}","defaultMode":"icon"}"""
        metadata.writeText(old)
        val output = ControlSkinArchive.export(workdir, set.id, temporary.newFolder("old-out"))
        assertEquals(old, metadata.readText())
        val duplicate = ControlSkinArchive.importZip(workdir, output, temporary.root)
        assertFalse(duplicate.added)
        assertEquals(set.id, duplicate.id)
        assertEquals(1, ControlSkinStore.list(workdir).size)
        ZipFile(output).use { zip ->
            val restored = zip.getInputStream(zip.getEntry("Legacy/skin.json"))
                .bufferedReader().use { it.readText() }
            assertEquals(1, org.json.JSONObject(restored).getInt("version"))
        }
        val imported = ControlSkinArchive.importZip(
            temporary.newFolder("restored-legacy"), output, temporary.root,
        )
        assertEquals(set.id, imported.id)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        fail("Expected IOException")
    }

    private fun png(color: Int): ByteArray {
        val image = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        return try {
            image.eraseColor(color)
            ByteArrayOutputStream().use { stream ->
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally {
            image.recycle()
        }
    }
}
