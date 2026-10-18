/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Test;
import org.microemu.cldc.file.StorageMode;

import io.github.h3nb.jlmodplus.config.MidletPermissionStore;

public class MidletPermissionPolicyTest {
    private static final String READ = "javax.microedition.io.Connector.file.read";
    private static final String WRITE = "javax.microedition.io.Connector.file.write";
    private static final String[] NON_STORAGE = {
            "javax.microedition.io.Connector.http",
            "javax.microedition.io.Connector.serversocket",
            "javax.microedition.io.Connector.datagramreceive",
            "javax.microedition.media.control.RecordControl",
            "javax.microedition.io.Connector.bluetooth.client",
            "unknown.permission", "", null
    };

    @After public void clear() {
        MidletPermissionManager.clear();
    }

    @Test public void nonStorageKeepsAlphaBehaviorWithoutRuntimeSession() {
        MidletPermissionManager.clear();
        assertNonStorageAllowed();
        assertEquals(0, MidletPermissionManager.checkPermission(READ));
        assertEquals(0, MidletPermissionManager.checkPermission(WRITE));
    }

    @Test public void storageDenialAndSessionClosureDoNotAffectOtherPermissions() throws Exception {
        Object session = install(MidletPermissionStore.DENY, MidletPermissionStore.DENY);
        assertNonStorageAllowed();
        assertEquals(0, MidletPermissionManager.checkPermission(READ));
        assertEquals(0, MidletPermissionManager.checkPermission(WRITE));
        field(session, "closed").setBoolean(session, true);
        assertNonStorageAllowed();
    }

    @Test public void storageKeepsSeparateDecisionsAndTemporarySessionGrant() throws Exception {
        Object session = install(MidletPermissionStore.ASK, MidletPermissionStore.ALLOW);
        assertEquals(-1, MidletPermissionManager.checkPermission(READ));
        assertEquals(1, MidletPermissionManager.checkPermission(WRITE));
        field(session, "readSession").setBoolean(session, true);
        assertEquals(1, MidletPermissionManager.checkPermission(READ));
        field(session, "closed").setBoolean(session, true);
        assertEquals(0, MidletPermissionManager.checkPermission(READ));
        assertEquals(0, MidletPermissionManager.checkPermission(WRITE));
    }

    private static void assertNonStorageAllowed() {
        for (String permission : NON_STORAGE) {
            assertEquals(String.valueOf(permission), 1, MidletPermissionManager.checkPermission(permission));
        }
    }

    private static Object install(int read, int write) throws Exception {
        MidletPermissionManager.clear();
        Class<?> type = Class.forName(MidletPermissionManager.class.getName() + "$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(PresetAuthorityClient.class,
                String.class, long.class, String.class, StorageMode.class, int.class, int.class);
        constructor.setAccessible(true);
        // Policy queries do not contact the Android authority or display a prompt.
        Object session = constructor.newInstance(null, "/fixture/converted/one", 1L, "jar",
                StorageMode.SHARED, read, write);
        Field current = MidletPermissionManager.class.getDeclaredField("current");
        current.setAccessible(true);
        current.set(null, session);
        return session;
    }

    private static Field field(Object session, String name) throws Exception {
        Field field = session.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
