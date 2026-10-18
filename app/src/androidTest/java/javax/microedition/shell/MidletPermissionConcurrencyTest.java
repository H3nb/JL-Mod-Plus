/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.microemu.cldc.file.VirtualFileSystem;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public class MidletPermissionConcurrencyTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @After public void clear() {
        MidletPermissionManager.clear();
        VirtualFileSystem.clear();
    }

    @Test public void queuedRequestObservesDenialWithoutOpeningAnotherPrompt() throws Exception {
        VirtualFileSystem.install(temporary.getRoot(), "one");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Class<?> type = Class.forName(MidletPermissionManager.class.getName() + "$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(PresetAuthorityClient.class,
                String.class, long.class, String.class, int.class, int.class);
        constructor.setAccessible(true);
        // No installed grant is persisted by this fixture. A denied prompt must still deny the
        // current session when persistence fails (stale installation, missing storage, etc.).
        Object session = constructor.newInstance(new PresetAuthorityClient(context),
                "/nonexistent-permission-fixture", Long.MAX_VALUE, null, 0, 0);
        Field current = MidletPermissionManager.class.getDeclaredField("current");
        current.setAccessible(true);
        current.set(null, session);
        AtomicInteger denied = new AtomicInteger();
        Runnable access = () -> {
            try { MidletPermissionManager.requireFileAccess("c:/", false); }
            catch (SecurityException expected) { denied.incrementAndGet(); }
        };
        Thread first = new Thread(access);
        Thread second = new Thread(access);
        try {
            // Hold the host thread so showPrompt cannot auto-cancel before both callers queue.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    first.start();
                    Field pending = type.getDeclaredField("pending");
                    pending.setAccessible(true);
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    Object request;
                    while ((request = pending.get(session)) == null && System.nanoTime() < deadline) Thread.yield();
                    assertNotNull(request);
                    second.start();
                    while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
                    assertEquals(Thread.State.BLOCKED, second.getState());
                    Method complete = request.getClass().getDeclaredMethod("complete", int.class);
                    complete.setAccessible(true);
                    complete.invoke(request, MidletPermissionManager.DENY);
                    first.join(2000);
                    second.join(2000);
                    assertFalse(first.isAlive());
                    assertFalse(second.isAlive());
                    assertEquals(2, denied.get());
                } catch (ReflectiveOperationException | InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            });
        } finally {
            MidletPermissionManager.clear();
            first.interrupt();
            second.interrupt();
            first.join(2000);
            second.join(2000);
        }
    }
}
