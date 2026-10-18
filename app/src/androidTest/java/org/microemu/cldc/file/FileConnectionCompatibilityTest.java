/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.cldc.file;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import javax.microedition.io.Connector;
import javax.microedition.shell.MidletPermissionManager;

public class FileConnectionCompatibilityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Before public void prepare() throws Exception {
        VirtualFileSystem.install(temporary.getRoot(), "one");
        Method install = MidletPermissionManager.class.getDeclaredMethod("install", Context.class,
                String.class, long.class, String.class);
        install.setAccessible(true);
        // A nonexistent installation receives no shared grant; its private mount stays usable.
        install.invoke(null, InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new File(temporary.getRoot(), "converted/one").getPath(), Long.MAX_VALUE, null);
    }

    @After public void clear() {
        MidletPermissionManager.clear();
        VirtualFileSystem.clear();
    }

    @Test public void streamsRemainUsableAfterConnectionClose() throws Exception {
        File fixture = VirtualFileSystem.resolve("private:/", "save.dat");
        try (FileOutputStream output = new FileOutputStream(fixture)) { output.write(new byte[]{1, 2}); }
        FileSystemFileConnection read = new FileSystemFileConnection("file:///private:/save.dat", Connector.READ);
        try (InputStream stream = read.openInputStream()) {
            read.close();
            assertFalse(read.isOpen());
            assertEquals(1, stream.read());
        }
        FileSystemFileConnection write = new FileSystemFileConnection("file:///private:/save.dat", Connector.WRITE);
        try (OutputStream stream = write.openOutputStream()) {
            write.close();
            stream.write(3);
        }
        assertEquals(1L, fixture.length());
    }

    @Test public void writeTraversalKeepsModeAndCorrectDirectoryUrls() throws Exception {
        File directory = VirtualFileSystem.resolve("private:/", "folder/child");
        assertTrue(directory.mkdirs());
        FileSystemFileConnection connection = new FileSystemFileConnection("file:///private:/", Connector.WRITE);
        connection.setFileConnection("folder/");
        connection.setFileConnection("child/");
        assertEquals("file:///private:/folder/child/", connection.getURL());
        connection.setFileConnection("..");
        assertEquals("file:///private:/folder/", connection.getURL());
        assertThrows(javax.microedition.io.file.IllegalModeException.class, connection::list);
        connection.setFileConnection("..");
        assertThrows(java.io.IOException.class, () -> connection.setFileConnection(".."));
        connection.close();
    }
}
