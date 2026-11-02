package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.h3nb.jlmodplus.config.InstalledAppWriteGuard;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * R&D-only installed-identity and source-JAR experiment. These tests do not
 * introduce a production saved-locator storage format or authority.
 */
@RunWith(AndroidJUnit4.class)
public final class SavedLocatorIdentityResearchAndroidTest {
    private Context context;
    private File firstWorkdir;
    private File secondWorkdir;
    private File firstAppDir;
    private File secondAppDir;
    private long firstAppId;
    private long secondAppId;
    private InstalledAppWriteGuard guard;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        firstWorkdir = new File(context.getFilesDir(), "saved-locator-id-workdir-a");
        secondWorkdir = new File(context.getFilesDir(), "saved-locator-id-workdir-b");
        remove(firstWorkdir);
        remove(secondWorkdir);
        firstAppDir = prepare(firstWorkdir);
        secondAppDir = prepare(secondWorkdir);
        firstAppId = ResearchLibraryFixture.register(context, firstWorkdir);
        secondAppId = ResearchLibraryFixture.register(context, secondWorkdir);
        guard = InstalledAppWriteGuard.create(context);
    }

    @After public void tearDown() {
        remove(firstWorkdir);
        remove(secondWorkdir);
    }

    @Test public void reinstallAndJarMismatchAreDifferentFailClosedConditions() throws Exception {
        File originalJar = new File(firstAppDir, "res.jar");
        byte[] fingerprint = digest(originalJar);
        File resultFile = new File(firstWorkdir, "configs/fixture/research-saved-locator.json");
        AtomicFile atomic = new AtomicFile(resultFile);
        String appPath = firstAppDir.getCanonicalPath();

        assertEquals(firstAppId, guard.resolveCurrentAppId(appPath));
        assertEquals(InstalledAppWriteGuard.Result.SUCCESS,
                guard.run(appPath, firstAppId, () -> {
                    if (!Arrays.equals(fingerprint, digest(originalJar))) return false;
                    return publish(atomic, "{\"version\":1,\"name\":\"health\"}");
                }));
        String initial = new String(atomic.readFully(), StandardCharsets.UTF_8);
        assertTrue(initial.contains("health"));

        // Uninstall + reinstall into exactly the same filesystem location, using
        // the identical JAR. A stale editor/write request must never publish.
        long replacementAppId = ResearchLibraryFixture.replace(context, firstWorkdir);
        assertTrue("Reinstalled game must receive a new durable ID", replacementAppId != firstAppId);
        assertEquals(InstalledAppWriteGuard.Result.STALE,
                guard.run(appPath, firstAppId, () -> publish(atomic, "stale-write")));
        assertEquals(initial, new String(atomic.readFully(), StandardCharsets.UTF_8));
        assertEquals(replacementAppId, guard.resolveCurrentAppId(appPath));

        // The *current* installation still must not use a descriptor imported
        // from a different source JAR, even if the caller's appId is correct.
        try (FileOutputStream output = new FileOutputStream(originalJar, true)) {
            output.write(37);
        }
        assertFalse(Arrays.equals(fingerprint, digest(originalJar)));
        assertEquals(InstalledAppWriteGuard.Result.FAILED,
                guard.run(appPath, replacementAppId, () -> {
                    if (!Arrays.equals(fingerprint, digest(originalJar))) return false;
                    return publish(atomic, "wrong-jar");
                }));
        assertEquals(initial, new String(atomic.readFully(), StandardCharsets.UTF_8));
    }

    @Test public void appIdsAreScopedToTheWorkdirNotGloballyUnique() throws Exception {
        // Two independently initialized databases can both allocate appId = 1.
        assertEquals(firstAppId, secondAppId);
        String a = firstAppDir.getCanonicalPath();
        String b = secondAppDir.getCanonicalPath();
        assertFalse(a.equals(b));
        assertEquals(firstAppId, guard.resolveCurrentAppId(a));
        assertEquals(secondAppId, guard.resolveCurrentAppId(b));

        // Guard.run authenticates the path *provided by its caller*. It cannot
        // know that a Saved Variable from workdir A must not be presented with
        // workdir B's valid path. The eventual saved record must preserve its
        // canonical workdir/storageKey together with appId and JAR identity.
        assertEquals(InstalledAppWriteGuard.Result.SUCCESS,
                guard.run(b, firstAppId, () -> true));
    }

    private File prepare(File root) throws Exception {
        File app = new File(root, "converted/fixture");
        File config = new File(root, "configs/fixture");
        assertTrue(app.mkdirs());
        assertTrue(config.mkdirs());
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("saved-locator/Locator-Lab.jar");
             FileOutputStream output = new FileOutputStream(new File(app, "res.jar"))) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
        return app;
    }

    private static byte[] digest(File jar) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(jar)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        return hash.digest();
    }

    private static boolean publish(AtomicFile target, String value) throws Exception {
        FileOutputStream output = target.startWrite();
        try {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
            return true;
        } catch (Exception failure) {
            target.failWrite(output);
            throw failure;
        }
    }

    private static void remove(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) remove(child);
        }
        if (!file.delete()) throw new IllegalStateException("Unable to clear research fixture");
    }
}
