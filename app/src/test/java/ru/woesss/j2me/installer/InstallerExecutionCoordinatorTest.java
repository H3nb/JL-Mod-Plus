package ru.woesss.j2me.installer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstallerExecutionCoordinatorTest {
    @Test public void cancelledWaitDoesNotConsumePermit() throws Exception {
        InstallerExecutionCoordinator.Permit owner = InstallerExecutionCoordinator.acquire();
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try (InstallerExecutionCoordinator.Permit ignored = InstallerExecutionCoordinator.acquire(cancel::get)) {
                result.set(new AssertionError("Cancelled waiter acquired permit"));
            } catch (IOException expected) { result.set(expected); }
        });
        try {
            waiter.start();
            cancel.set(true);
            waiter.join(3000);
            assertFalse(waiter.isAlive());
            assertTrue(result.get() instanceof IOException);
        } finally { owner.close(); }
        try (InstallerExecutionCoordinator.Permit next = InstallerExecutionCoordinator.acquire()) {
            next.close(); // Idempotent: an owner may release after a callback and again during cleanup.
        }
    }
}
