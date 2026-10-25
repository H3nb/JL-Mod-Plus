package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class ControlSkinStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void manualSetGetsStableIdentityWithoutRequiringMetadata() throws Exception {
        File workdir = temp.newFolder("work");
        File folder = new File(ControlSkinStore.directory(workdir), "Handmade");
        assertTrue(folder.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(folder, "key_5.png"))) {
            out.write("data".getBytes(StandardCharsets.UTF_8));
        }
        List<ControlSkinStore.SetInfo> sets = ControlSkinStore.list(workdir);
        assertEquals(1, sets.size());
        assertTrue(new File(folder, "skin.json").isFile());
        assertEquals(sets.get(0).id, ControlSkinStore.list(workdir).get(0).id);
        assertEquals(sets.get(0).id, ControlSkinStore.findReadonly(workdir, sets.get(0).id).id);
        assertEquals("icon", ControlSkinStore.mode(folder, "5"));
        assertEquals("key_5.png", ControlSkinStore.image(folder, "5", false).getName());
    }

    @Test public void renameAndDuplicatePreserveAndSeparateStableIdentities() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.SetInfo original = ControlSkinStore.create(workdir, "Fantasy");
        ControlSkinStore.setMode(workdir, original.id, "fire", "full");
        ControlSkinStore.SetInfo renamed = ControlSkinStore.rename(workdir, original.id, "Fantasy New");
        assertEquals(original.id, renamed.id);
        assertEquals("Fantasy New", ControlSkinStore.findReadonly(workdir, original.id).name);
        ControlSkinStore.SetInfo copy = ControlSkinStore.duplicate(workdir, original.id, "Fantasy Copy");
        assertNotEquals(original.id, copy.id);
        assertEquals("full", ControlSkinStore.mode(copy.folder, "fire"));
        ControlSkinStore.setMode(workdir, copy.id, "fire", "icon");
        assertEquals("full", ControlSkinStore.mode(renamed.folder, "fire"));
        ControlSkinStore.delete(workdir, original.id);
        assertNull(ControlSkinStore.findReadonly(workdir, original.id));
        assertNotNull(ControlSkinStore.findReadonly(workdir, copy.id));
    }

    @Test public void conflictingNamesAndTraversalAreRefused() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.create(workdir, "Retro Nokia");
        try {
            ControlSkinStore.create(workdir, "retro nokia");
            fail("case-insensitive duplicate");
        } catch (java.io.IOException expected) { }
        try {
            ControlSkinStore.create(workdir, "../elsewhere");
            fail("directory traversal");
        } catch (java.io.IOException expected) { }
        assertEquals(1, ControlSkinStore.list(workdir).size());
    }

    @Test public void invalidModesAndKeysCannotBePublished() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Minimal");
        try {
            ControlSkinStore.setMode(workdir, set.id, "../../other", "full");
            fail("invalid key");
        } catch (java.io.IOException expected) { }
        try {
            ControlSkinStore.setMode(workdir, set.id, "5", "stretch");
            fail("invalid mode");
        } catch (java.io.IOException expected) { }
        assertEquals("icon", ControlSkinStore.mode(set.folder, "5"));
    }
    @Test public void customizedCountTracksNormalSlotsNotImageFiles() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Counted");
        assertEquals(0, ControlSkinStore.countCustomizedButtons(set.folder));

        // A pressed-only image cannot replace the built-in button.
        assertTrue(new File(set.folder, "key_5_pressed.png").createNewFile());
        assertEquals(0, ControlSkinStore.countCustomizedButtons(set.folder));

        assertTrue(new File(set.folder, "key_5.png").createNewFile());
        assertEquals(1, ControlSkinStore.countCustomizedButtons(set.folder));
        assertTrue(new File(set.folder, "key_fire.webp").createNewFile());
        assertEquals(2, ControlSkinStore.countCustomizedButtons(set.folder));

        ControlSkinStore.removeImage(workdir, set.id, "5", false);
        assertEquals(1, ControlSkinStore.countCustomizedButtons(set.folder));
        // The pressed asset is intentionally kept if the normal image is removed.
        assertNotNull(ControlSkinStore.image(set.folder, "5", true));
    }

    @Test public void deletingSetWithUnsupportedDirectoryKeepsExistingImages() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Manual");
        File normal = new File(set.folder, "key_5.png");
        assertTrue(normal.createNewFile());
        File extra = new File(set.folder, "nested");
        assertTrue(extra.mkdir());

        try {
            ControlSkinStore.delete(workdir, set.id);
            fail("a set with unsupported entries must not be partially deleted");
        } catch (java.io.IOException expected) { }

        assertTrue(normal.isFile());
        assertTrue(new File(set.folder, "skin.json").isFile());
        assertTrue(extra.isDirectory());
        assertNotNull(ControlSkinStore.findReadonly(workdir, set.id));
    }

    @Test public void removingInvalidKeyCannotTouchAnotherFile() throws Exception {
        File workdir = temp.newFolder("work");
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Guarded");
        File normal = new File(set.folder, "key_5.png");
        assertTrue(normal.createNewFile());

        try {
            ControlSkinStore.removeImage(workdir, set.id, "../5", false);
            fail("invalid key must be rejected");
        } catch (java.io.IOException expected) { }

        assertTrue(normal.isFile());
    }

    @Test public void runtimeLoadsFullAndPressedAssetsAndFallsBackForInvalidNormal()
            throws Exception {
        File workdir = temp.newFolder("work");
        File configDir = new File(new File(workdir, "configs"), "Example");
        assertTrue(configDir.mkdirs());
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Runtime");
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        try {
            image.eraseColor(android.graphics.Color.MAGENTA);
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png));
        } finally {
            image.recycle();
        }

        byte[] data = png.toByteArray();
        ControlSkinStore.importImage(workdir, set.id, "5", false,
                new java.io.ByteArrayInputStream(data), ".png");
        ControlSkinStore.importImage(workdir, set.id, "5", true,
                new java.io.ByteArrayInputStream(data), ".png");
        ControlSkinStore.setMode(workdir, set.id, "5", "full");
        // A present but undecodable normal image must keep the built-in button.
        try (FileOutputStream out = new FileOutputStream(new File(set.folder, "key_fire.png"))) {
            out.write("invalid".getBytes(StandardCharsets.UTF_8));
        }

        ControlSkinRuntime runtime = ControlSkinRuntime.load(configDir, set.id);
        ControlSkinRuntime.Button five = runtime.get(4);
        assertNotNull(five);
        assertTrue(five.full);
        assertNotNull(five.normal);
        assertNotNull(five.pressed);
        assertEquals(8, five.normal.getWidth());
        assertNull(runtime.get(24)); // fire has an invalid image
        assertNull(runtime.get(-1));
        assertNull(ControlSkinRuntime.load(configDir, null).get(4));
    }

}
