/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class MidletPermissionStoreInstrumentationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final long appId = (1L << 60) | (System.nanoTime() & 0x0000ffffffffffffL);
    private File temporaryJar;
    private final String appPath = new File(context.getFilesDir(), "test-workdir-a/converted/app").getPath();
    private final String otherWorkdirAppPath = new File(context.getFilesDir(),
            "test-workdir-b/converted/app").getPath();

    @After
    public void tearDown() {
        MidletPermissionStore.write(context, appPath, appId,
                MidletPermissionStore.FILE_READ, null, MidletPermissionStore.ASK);
        MidletPermissionStore.write(context, appPath, appId,
                MidletPermissionStore.FILE_WRITE, null, MidletPermissionStore.ASK);
        MidletPermissionStore.write(context, otherWorkdirAppPath, appId,
                MidletPermissionStore.FILE_READ, null, MidletPermissionStore.ASK);
        if (temporaryJar != null) temporaryJar.delete();
    }

    @Test
    public void grantsBelongToOneInstallationAndJarFingerprint() {
        String jarOne = "jar-content-sha-1";
        String jarTwo = "jar-content-sha-2";
        String capability = MidletPermissionStore.FILE_READ;
        assertEquals(MidletPermissionStore.ASK,
                MidletPermissionStore.read(context, appPath, appId, capability, jarOne));
        assertFalse(MidletPermissionStore.write(context, appPath, appId, capability,
                null, MidletPermissionStore.ALLOW));
        assertTrue(MidletPermissionStore.write(context, appPath, appId, capability,
                jarOne, MidletPermissionStore.ALLOW));
        assertEquals(MidletPermissionStore.ALLOW,
                MidletPermissionStore.read(context, appPath, appId, capability, jarOne));
        assertEquals(MidletPermissionStore.ASK,
                MidletPermissionStore.read(context, appPath, appId, capability, jarTwo));
        assertEquals(MidletPermissionStore.ASK,
                MidletPermissionStore.read(context, appPath, appId + 1, capability, jarOne));
        assertEquals(MidletPermissionStore.ASK,
                MidletPermissionStore.read(context, otherWorkdirAppPath, appId,
                        capability, jarOne));

        assertTrue(MidletPermissionStore.write(context, appPath, appId, capability,
                jarOne, MidletPermissionStore.DENY));
        assertEquals(MidletPermissionStore.DENY,
                MidletPermissionStore.read(context, appPath, appId, capability, jarTwo));
        assertTrue(MidletPermissionStore.write(context, appPath, appId, capability,
                null, MidletPermissionStore.ASK));
        assertEquals(MidletPermissionStore.ASK,
                MidletPermissionStore.read(context, appPath, appId, capability, jarOne));
    }

    @Test
    public void fingerprintUsesRetainedJarContents() throws Exception {
        temporaryJar = File.createTempFile("permission-fixture-", ".jar", context.getCacheDir());
        try (FileOutputStream stream = new FileOutputStream(temporaryJar)) {
            stream.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        String fingerprint = MidletPermissionStore.fingerprint(temporaryJar);
        assertNotNull(fingerprint);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                fingerprint);
    }
}
