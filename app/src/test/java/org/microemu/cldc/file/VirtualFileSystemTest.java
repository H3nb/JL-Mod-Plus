package org.microemu.cldc.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;

public class VirtualFileSystemTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @After public void tearDown() {
        VirtualFileSystem.clear();
    }

    @Test public void sharedDrivesExistAndOnlyEnumerableMountsAreAdvertised() throws Exception {
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        assertTrue(VirtualFileSystem.resolve("c:/", "").isDirectory());
        assertTrue(VirtualFileSystem.resolve("e:/", "").isDirectory());
        assertEquals("storage/c", relative(VirtualFileSystem.resolve("c:/", "")));
        assertEquals("storage/e", relative(VirtualFileSystem.resolve("e:/", "")));
        for (String folder : new String[]{"Music", "Images", "Videos", "Recordings"}) {
            assertTrue(VirtualFileSystem.resolve("e:/", folder).isDirectory());
        }
        assertTrue(VirtualFileSystem.resolve("c:/", "Sounds").isDirectory());
        ArrayList<String> roots = Collections.list(VirtualFileSystem.listRoots());
        assertEquals(2, roots.size());
        assertEquals("c:/", roots.get(0));
        assertEquals("e:/", roots.get(1));
        assertFalse(roots.contains("0:/"));
        assertFalse(roots.contains("1:/"));
        assertFalse(roots.contains("fs/mystuff/"));
    }

    @Test public void aliasesShareBackingFilesWithoutAdvertisingExtraRoots() throws Exception {
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        assertEquals(VirtualFileSystem.resolve("c:/", "example.dat"),
                VirtualFileSystem.resolve("0:/", "example.dat"));
        assertEquals(VirtualFileSystem.resolve("e:/", "example.dat"),
                VirtualFileSystem.resolve("1:/", "example.dat"));
        assertEquals(VirtualFileSystem.resolve("e:/", "example.dat"),
                VirtualFileSystem.resolve("fs/MyStuff/", "example.dat"));
    }

    @Test public void installationPrivateDataIsSeparateFromSharedMounts() throws Exception {
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        File first = VirtualFileSystem.resolve("private:/", "save.dat");
        File vendor = VirtualFileSystem.resolve("siemens:/", "save.dat");
        File vendorDrive = VirtualFileSystem.resolve("a:/", "save.dat");
        assertTrue(VirtualFileSystem.isPrivate("a:/"));
        assertTrue(VirtualFileSystem.isPrivate("b:/"));
        assertTrue(relative(first).startsWith("data/game-a/private/"));
        assertEquals("data/game-a/vendor/siemens/save.dat", relative(vendor));
        VirtualFileSystem.install(temp.getRoot(), "game-b");
        File second = VirtualFileSystem.resolve("private:/", "save.dat");
        assertNotEquals(first, second);
        assertNotEquals(vendorDrive, VirtualFileSystem.resolve("a:/", "save.dat"));
        assertTrue(relative(second).startsWith("data/game-b/private/"));
        assertEquals("storage/c", relative(VirtualFileSystem.resolve("c:/", "")));
    }

    @Test public void rejectsUnknownRootsAndTraversal() throws Exception {
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        assertThrows(IOException.class, () -> VirtualFileSystem.rootFor("unknown/file"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("z:/", "file"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "../data/game-a"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "folder/../../data"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "a\\..\\b"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "/absolute"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "other:/file"));
        assertFalse(VirtualFileSystem.rootFor("c:/file").isEmpty());
    }

    @Test public void rejectsSymlinksOutsideMountAndLinkedMountRoots() throws Exception {
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        File outside = temp.newFolder("outside");
        File mount = VirtualFileSystem.resolve("c:/", "");
        Files.createSymbolicLink(new File(mount, "escape").toPath(), outside.toPath());
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "escape/secret"));
        File root = temp.newFolder("workdir");
        File storage = new File(root, "storage");
        assertTrue(storage.mkdirs());
        Files.createSymbolicLink(new File(storage, "c").toPath(), outside.toPath());
        assertThrows(IOException.class, () -> VirtualFileSystem.install(root, "game-a"));
    }

    @Test public void vendorFilesCannotExposeLegacyRmsRecords() throws Exception {
        File recordData = new File(temp.getRoot(), "data/game-a/score.rsh");
        File directory = recordData.getParentFile();
        assertTrue(directory.mkdirs() || directory.isDirectory());
        Files.write(recordData.toPath(), new byte[]{1, 2, 3});
        VirtualFileSystem.install(temp.getRoot(), "game-a");
        File vendorFile = VirtualFileSystem.resolve("siemens:/", "score.rsh");
        assertNotEquals(recordData.getCanonicalFile(), vendorFile);
        assertFalse(vendorFile.exists());
        assertTrue(recordData.isFile());
    }

    @Test public void refusesInvalidInstallationIdentifiers() throws Exception {
        assertThrows(IOException.class, () -> VirtualFileSystem.install(temp.getRoot(), "../game"));
        assertThrows(IOException.class, () -> VirtualFileSystem.install(temp.getRoot(), "a/b"));
        assertThrows(IOException.class, () -> VirtualFileSystem.install(temp.getRoot(), "a:b"));
    }

    @Test public void legacyPrivateUrlsStayWithinTheirOriginalInstallation() throws Exception {
        File workdir = new File(temp.getRoot(), "WorkDir");
        VirtualFileSystem.install(workdir, "GameOne", temp.getRoot());
        String oldRoot = "c:/WorkDir/data/GameOne/private/";
        assertEquals(oldRoot, VirtualFileSystem.rootFor(oldRoot + "save.dat"));
        assertTrue(VirtualFileSystem.isPrivate(oldRoot));
        assertEquals(VirtualFileSystem.resolve("private:/", "save.dat"),
                VirtualFileSystem.resolve(oldRoot, "save.dat"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve(oldRoot, "../score.rsh"));
        File first = VirtualFileSystem.resolve(oldRoot, "save.dat");
        Files.write(first.toPath(), new byte[]{42});
        File shared = VirtualFileSystem.resolve("c:/", "shared.dat");
        Files.write(shared.toPath(), new byte[]{7});
        VirtualFileSystem.install(workdir, "GameTwo", temp.getRoot());
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve(oldRoot, "save.dat"));
        // An old URL for another installation can refer only to the shared C namespace,
        // where no private data is mounted or copied.
        String resolvedRoot = VirtualFileSystem.rootFor(oldRoot + "save.dat");
        assertEquals("c:/", resolvedRoot);
        assertFalse(VirtualFileSystem.resolve(resolvedRoot,
                (oldRoot + "save.dat").substring(resolvedRoot.length())).exists());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(first.toPath()));
        assertEquals(shared, VirtualFileSystem.resolve("c:/", "shared.dat"));
    }

    @Test public void androidModeMountsPhysicalVolumesButKeepsPrivateLocations() throws Exception {
        File primary = temp.newFolder("primary");
        File card = temp.newFolder("card");
        File work = new File(primary, "WorkDir");
        VirtualFileSystem.install(work, "one");
        File privateSave = VirtualFileSystem.resolve("private:/", "save");
        File vendorSave = VirtualFileSystem.resolve("a:/", "save");
        Files.write(privateSave.toPath(), new byte[]{42});
        VirtualFileSystem.install(work, "one", primary, StorageMode.ANDROID, new File[]{primary, card});
        assertEquals(primary, VirtualFileSystem.resolve("c:/", ""));
        assertEquals(card, VirtualFileSystem.resolve("e:/", ""));
        assertEquals(card, VirtualFileSystem.resolve("1:/", ""));
        assertEquals(privateSave, VirtualFileSystem.resolve("private:/", "save"));
        assertEquals(vendorSave, VirtualFileSystem.resolve("a:/", "save"));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(privateSave.toPath()));
        assertFalse(new File(primary, "Sounds").exists());
        assertFalse(new File(card, "Music").exists());
        assertEquals(2, Collections.list(VirtualFileSystem.listRoots()).size());
        VirtualFileSystem.install(work, "two", primary, StorageMode.ANDROID, new File[]{primary});
        assertEquals(1, Collections.list(VirtualFileSystem.listRoots()).size());
        assertEquals(primary, VirtualFileSystem.resolve("e:/", ""));
        assertNotEquals(privateSave, VirtualFileSystem.resolve("private:/", "save"));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "WorkDir/data/one/private/save"));
        VirtualFileSystem.install(work, "one");
        assertEquals(new File(work, "storage/c"), VirtualFileSystem.resolve("c:/", ""));
    }

    @Test public void androidModeProtectsInternalsAndTheirAncestorsIncludingSymlinks() throws Exception {
        File primary = temp.newFolder("volume");
        File work = new File(primary, "parent/WorkDir");
        VirtualFileSystem.install(work, "one", primary, StorageMode.ANDROID, new File[]{primary});
        for (String internal : new String[]{"data/other/record.rsh", "configs/other", "converted/other",
                "templates/preset", "cache/private", "JL-Mod-library.db-wal", "J2ME-apps.db"}) {
            assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "parent/WorkDir/" + internal));
        }
        File privateDir = new File(work, "data");
        Files.createSymbolicLink(new File(primary, "linked-data").toPath(), privateDir.toPath());
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "linked-data/other"));
        Files.createSymbolicLink(new File(primary, "linked-work").toPath(), work.toPath());
        assertThrows(IOException.class, () -> VirtualFileSystem.checkMutation("c:/", new File(primary, "linked-work")));
        assertThrows(IOException.class, () -> VirtualFileSystem.checkMutation("c:/", work));
        assertThrows(IOException.class, () -> VirtualFileSystem.checkMutation("c:/", work.getParentFile()));
        assertThrows(IOException.class, () -> VirtualFileSystem.checkMutation("c:/", primary));
        VirtualFileSystem.checkMutation("c:/", new File(primary, "normal.txt"));
        VirtualFileSystem.checkMutation("private:/", VirtualFileSystem.resolve("private:/", "save"));
        // Selecting the storage root itself as workdir must still allow reading its listing.
        VirtualFileSystem.install(primary, "one", primary, StorageMode.ANDROID, new File[]{primary});
        assertEquals(primary, VirtualFileSystem.resolve("c:/", ""));
        assertThrows(IOException.class, () -> VirtualFileSystem.resolve("c:/", "data/one/record.rsh"));
    }

    @Test public void unavailableAndroidVolumeDoesNotGetCreated() throws Exception {
        File absent = new File(temp.getRoot(), "unmounted");
        assertThrows(IOException.class, () -> VirtualFileSystem.install(temp.getRoot(), "one", absent,
                StorageMode.ANDROID, new File[]{absent}));
        assertFalse(absent.exists());
        assertThrows(IOException.class, () -> VirtualFileSystem.install(temp.getRoot(), "one", null,
                StorageMode.ANDROID, new File[0]));
    }

    private String relative(File file) throws IOException {
        String base = temp.getRoot().getCanonicalPath();
        return file.getCanonicalPath().substring(base.length() + 1).replace(File.separatorChar, '/');
    }
}
