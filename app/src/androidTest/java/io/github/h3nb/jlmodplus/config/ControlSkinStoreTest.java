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

    @Test public void modeSnapshotReadsDefaultsAndOverridesWithoutReusingStaleMetadata()
            throws Exception {
        File workdir = temp.newFolder("mode-snapshot-work");
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Mode Snapshot");
        ControlSkinStore.setMode(workdir, set.id, "5", "full");
        ControlSkinStore.setMode(workdir, set.id, "fire", "full");
        java.util.Map<String, String> snapshot = ControlSkinStore.modes(set.folder);
        assertEquals(ControlSkinStore.KEYS.length, snapshot.size());
        for (String key : ControlSkinStore.KEYS) {
            assertEquals(ControlSkinStore.mode(set.folder, key), snapshot.get(key));
        }
        assertEquals("icon", snapshot.get("6"));
        assertEquals("full", snapshot.get("5"));
        assertEquals("full", snapshot.get("fire"));
        ControlSkinStore.setMode(workdir, set.id, "5", "icon");
        assertEquals("full", snapshot.get("5"));
        assertEquals("icon", ControlSkinStore.modes(set.folder).get("5"));
        assertEquals("full", ControlSkinStore.modes(set.folder).get("fire"));
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

    @Test public void groupedSkinAssetsPersistDuplicateAndDecodeWithoutChangingKeySlots()
            throws Exception {
        File workdir = temp.newFolder("grouped-work");
        File configDir = new File(new File(workdir, "configs"), "GroupedExample");
        assertTrue(configDir.mkdirs());
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Movement");

        android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(
                8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            sample.eraseColor(android.graphics.Color.CYAN);
            assertTrue(sample.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            sample.recycle();
        }
        byte[] data = out.toByteArray();

        // Overlays without a D-pad base are stored but inactive at runtime.
        ControlSkinStore.importAsset(workdir, set.id, "dpad_up",
                new java.io.ByteArrayInputStream(data), ".png");
        assertEquals(0, ControlSkinStore.countCustomizedButtons(set.folder));
        assertNull(ControlSkinRuntime.load(configDir, set.id).dpad());

        for (String stem : ControlSkinStore.GROUPED_ASSETS) {
            ControlSkinStore.importAsset(workdir, set.id, stem,
                    new java.io.ByteArrayInputStream(data), ".png");
            assertNotNull(ControlSkinStore.asset(set.folder, stem));
        }
        assertEquals(2, ControlSkinStore.countCustomizedButtons(set.folder));

        ControlSkinRuntime runtime = ControlSkinRuntime.load(configDir, set.id);
        assertNotNull(runtime.dpad());
        assertNotNull(runtime.dpad().base);
        assertNotNull(runtime.dpad().up);
        assertNotNull(runtime.dpad().down);
        assertNotNull(runtime.dpad().left);
        assertNotNull(runtime.dpad().right);
        assertNotNull(runtime.analog());
        assertNotNull(runtime.analog().base);
        assertNotNull(runtime.analog().thumb);
        assertNotNull(runtime.analog().thumbPressed);
        assertNull(runtime.get(0));

        ControlSkinStore.SetInfo copy = ControlSkinStore.duplicate(workdir, set.id, "Copy Movement");
        assertNotEquals(set.id, copy.id);
        for (String stem : ControlSkinStore.GROUPED_ASSETS) {
            assertNotNull(ControlSkinStore.asset(copy.folder, stem));
        }
        ControlSkinStore.removeAsset(workdir, copy.id, "dpad_base");
        assertEquals(1, ControlSkinStore.countCustomizedButtons(copy.folder));
        assertNull(ControlSkinRuntime.load(configDir, copy.id).dpad());
        assertNotNull(ControlSkinStore.asset(set.folder, "dpad_base"));
    }

    @Test public void groupedAssetSlotsRejectTraversalAndRespectIndependentAnalogFallback()
            throws Exception {
        File workdir = temp.newFolder("grouped-guarded");
        File configDir = new File(new File(workdir, "configs"), "GuardedExample");
        assertTrue(configDir.mkdirs());
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Independent Analog");

        try {
            ControlSkinStore.importAsset(workdir, set.id, "../key_5",
                    new java.io.ByteArrayInputStream(new byte[]{1}), ".png");
            fail("invalid image stem");
        } catch (java.io.IOException expected) { }
        try {
            ControlSkinStore.removeAsset(workdir, set.id, "analog_../../thumb");
            fail("invalid remove must not escape skin folder");
        } catch (java.io.IOException expected) { }

        android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(
                8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            assertTrue(sample.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { sample.recycle(); }
        byte[] data = out.toByteArray();
        ControlSkinStore.importAsset(workdir, set.id, "analog_thumb_pressed",
                new java.io.ByteArrayInputStream(data), ".png");
        assertNull(ControlSkinRuntime.load(configDir, set.id).analog());
        assertEquals(0, ControlSkinStore.countCustomizedButtons(set.folder));
        ControlSkinStore.importAsset(workdir, set.id, "analog_thumb",
                new java.io.ByteArrayInputStream(data), ".png");
        ControlSkinRuntime.Analog analog = ControlSkinRuntime.load(configDir, set.id).analog();
        assertNotNull(analog);
        assertNull(analog.base);
        assertNotNull(analog.thumb);
        assertNotNull(analog.thumbPressed);
        assertEquals(1, ControlSkinStore.countCustomizedButtons(set.folder));
    }

    @Test public void completeSkinDoesNotDropLateSlotsWhenImagesExceedBudget()
            throws Exception {
        File workdir = temp.newFolder("complete-skin-work");
        File configDir = new File(new File(workdir, "configs"), "Complete");
        assertTrue(configDir.mkdirs());
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Full Neon");
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                512, 512, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            image.eraseColor(android.graphics.Color.CYAN);
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
        byte[] data = out.toByteArray();
        for (String key : ControlSkinStore.KEYS) {
            ControlSkinStore.importImage(workdir, set.id, key, false,
                    new java.io.ByteArrayInputStream(data), ".png");
            ControlSkinStore.importImage(workdir, set.id, key, true,
                    new java.io.ByteArrayInputStream(data), ".png");
        }
        for (String stem : ControlSkinStore.GROUPED_ASSETS) {
            ControlSkinStore.importAsset(workdir, set.id, stem,
                    new java.io.ByteArrayInputStream(data), ".png");
        }

        // 64 fully expanded ARGB images would need 64 MiB. The last button and
        // all pressed states must still be present after proportional scaling.
        ControlSkinRuntime skin = ControlSkinRuntime.load(configDir, set.id);
        long bytes = 0;
        for (int i = 0; i < ControlSkinStore.KEYS.length; i++) {
            ControlSkinRuntime.Button button = skin.get(i);
            assertNotNull("Missing button " + i, button);
            assertNotNull(button.pressed);
            assertEquals(skin.get(0).normal.getWidth(), button.normal.getWidth());
            bytes += button.normal.getAllocationByteCount();
            bytes += button.pressed.getAllocationByteCount();
        }
        assertNotNull(skin.dpad());
        assertNotNull(skin.analog());
        for (android.graphics.Bitmap bitmap : new android.graphics.Bitmap[]{
                skin.dpad().base, skin.dpad().up, skin.dpad().down, skin.dpad().left,
                skin.dpad().right, skin.analog().base, skin.analog().thumb,
                skin.analog().thumbPressed}) {
            assertNotNull(bitmap);
            bytes += bitmap.getAllocationByteCount();
        }
        assertTrue("Allocated " + bytes + " bitmap bytes",
                bytes <= 24L * 1024 * 1024);
        assertTrue("Full set should be sampled", skin.get(0).normal.getWidth() < 512);
    }

    @Test public void sparseSkinKeepsFullDetailUntilDisplayNeedsLess() throws Exception {
        File workdir = temp.newFolder("sparse-skin-work");
        File configDir = new File(new File(workdir, "configs"), "Sparse");
        assertTrue(configDir.mkdirs());
        ControlSkinStore.SetInfo set = ControlSkinStore.create(workdir, "Single Key");
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                320, 320, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
        ControlSkinStore.importImage(workdir, set.id, "5", false,
                new java.io.ByteArrayInputStream(out.toByteArray()), ".png");
        assertEquals(320, ControlSkinRuntime.load(configDir, set.id).get(4).normal.getWidth());
        int width = ControlSkinRuntime.load(configDir, set.id, 200, 512)
                .get(4).normal.getWidth();
        assertTrue("The display-sized raster should be close to 200px: " + width,
                width >= 195 && width <= 201);
    }

}
