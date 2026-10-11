package org.microemu.cldc.file;

import static org.junit.Assert.assertEquals;
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
        assertTrue(relative(first).startsWith("data/game-a/private/"));
        assertEquals("data/game-a/vendor/siemens/save.dat", relative(vendor));
        VirtualFileSystem.install(temp.getRoot(), "game-b");
        File second = VirtualFileSystem.resolve("private:/", "save.dat");
        assertNotEquals(first, second);
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

    private String relative(File file) throws IOException {
        String base = temp.getRoot().getCanonicalPath();
        return file.getCanonicalPath().substring(base.length() + 1).replace(File.separatorChar, '/');
    }
}
