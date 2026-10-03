// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi;

import static org.junit.Assert.*;

import io.github.h3nb.jlmodplus.mmapi.protocol.device.DeviceDataSource;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioPlayer;
import io.github.h3nb.jlmodplus.mmapi.synth.Library;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/** Exercise the actual wrapper without a JNI decoder or Android focus owner. */
public class AudioPlayerDurationTest {
    private RuntimeAudioCoordinator previous, coordinator;
    private Field active;
    private AudioPlayer player;
    private final Backend backend = new Backend();
    private final BlockingQueue<Object> durations = new LinkedBlockingQueue<>();

    @Before public void setup() throws Exception {
        active = RuntimeAudioCoordinator.class.getDeclaredField("active");
        active.setAccessible(true);
        previous = (RuntimeAudioCoordinator) active.get(null);
        coordinator = new RuntimeAudioCoordinator(new RuntimeAudioCoordinator.FocusDriver() {
            public boolean request(long epoch, RuntimeAudioCoordinator.FocusListener listener) { return true; }
            public void abandon() {}
            public void execute(Runnable action) { /* These tests do not change host policy. */ }
        }, true);
        active.set(null, coordinator);
        player = new AudioPlayer(backend, new DeviceDataSource(Manager.TONE_DEVICE_LOCATOR));
        player.addPlayerListener((p, event, value) -> {
            if (PlayerListener.DURATION_UPDATED.equals(event)) durations.add(value);
        });
    }

    @After public void cleanup() throws Exception {
        player.close(); coordinator.close(); active.set(null, previous);
    }

    @Test public void managementPublishesDurationWithoutGetterAndSameValueDoesNotRepeatOnLoop() throws Exception {
        player.setLoopCount(2); player.start();
        backend.duration = 1024013;
        backend.events.add(new long[]{1, backend.duration, 0});
        assertEquals(Long.valueOf(1024013), durations.poll(2, TimeUnit.SECONDS));
        backend.events.add(new long[]{2, backend.duration, 0});
        assertNull(durations.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(Player.PREFETCHED, player.getState());
    }

    @Test public void getterCannotConsumeNotificationAndKnownDurationSurvivesDeallocate() throws Exception {
        player.prefetch();
        synchronized (player) {
            backend.duration = 1024013;
            assertEquals(1024013, player.getDuration());
        }
        assertEquals(Long.valueOf(1024013), durations.poll(2, TimeUnit.SECONDS));
        player.deallocate();
        assertEquals(1024013, player.getDuration());
        assertNull(durations.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test public void queuedDurationIsFencedWhenMediaIsReplaced() throws Exception {
        player.realize();
        CountDownLatch release = holdCallbacks();
        try {
            backend.duration = 100;
            player.getDuration();
            backend.duration = 200;
            player.setSequence(new byte[]{1});
        } finally { release.countDown(); }
        assertEquals(Long.valueOf(200), durations.poll(2, TimeUnit.SECONDS));
        assertNull(durations.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test public void queuedDurationIsFencedAfterClose() throws Exception {
        player.realize();
        CountDownLatch release = holdCallbacks();
        try { backend.duration = 100; player.getDuration(); player.close(); }
        finally { release.countDown(); }
        assertTrue(callbacks().awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(durations.isEmpty());
    }

    @Test public void finiteSeekProbeDoesNotHoldPlayerLockAndCloseFencesItsResult() throws Exception {
        player.realize();
        backend.probeEntered = new CountDownLatch(1);
        backend.probeRelease = new CountDownLatch(1);
        ExecutorService operations = Executors.newFixedThreadPool(2);
        try {
            Future<?> seek = operations.submit(() -> {
                try { player.setMediaTime(9000000); fail("Closed seek committed"); }
                catch (IllegalStateException expected) { /* Terminal close wins. */ }
                catch (javax.microedition.media.MediaException unexpected) { throw new AssertionError(unexpected); }
            });
            assertTrue(backend.probeEntered.await(2, TimeUnit.SECONDS));
            assertEquals(Player.REALIZED, player.getState());
            operations.submit(player::close).get(2, TimeUnit.SECONDS);
            backend.probeRelease.countDown();
            seek.get(2, TimeUnit.SECONDS);
            assertEquals(0, backend.seeks);
            assertTrue(durations.isEmpty());
        } finally { backend.probeRelease.countDown(); operations.shutdownNow(); }
    }

    @Test public void mediaReplacementFencesPreparedSeekAndBackendFailureUsesMediaException() throws Exception {
        player.realize();
        backend.probeEntered = new CountDownLatch(1);
        backend.probeRelease = new CountDownLatch(1);
        ExecutorService operations = Executors.newSingleThreadExecutor();
        try {
            Future<?> seek = operations.submit(() -> {
                try { player.setMediaTime(9000000); fail("Replaced media seek committed"); }
                catch (javax.microedition.media.MediaException expected) { /* Logical source changed. */ }
            });
            assertTrue(backend.probeEntered.await(2, TimeUnit.SECONDS));
            player.setSequence(new byte[]{1});
            backend.probeRelease.countDown();
            seek.get(2, TimeUnit.SECONDS);
            assertEquals(0, backend.seeks);
            backend.seekFailure = true;
            try { player.setMediaTime(1); fail("Backend failure escaped contract"); }
            catch (javax.microedition.media.MediaException expected) { }
        } finally { backend.probeRelease.countDown(); operations.shutdownNow(); }
    }

    private ExecutorService callbacks() throws Exception {
        Field field = AudioPlayer.class.getDeclaredField("callbackExecutor");
        field.setAccessible(true); return (ExecutorService) field.get(player);
    }
    private CountDownLatch holdCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        callbacks().execute(() -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); return release;
    }

    private static final class Backend implements Library {
        volatile long duration = Player.TIME_UNKNOWN;
        CountDownLatch probeEntered, probeRelease;
        int seeks;
        boolean seekFailure;
        final BlockingQueue<long[]> events = new LinkedBlockingQueue<>();
        public long createPlayer(String locator) { return 1; }
        public void realize(long handle) {}
        public void prefetch(long handle) {}
        public void start(long handle) {}
        public void pause(long handle) {}
        public void deallocate(long handle) { duration = Player.TIME_UNKNOWN; }
        public void close(long handle) {}
        public void prepareMediaTime(long handle, long time) {
            if (probeEntered != null) {
                probeEntered.countDown();
                try { if (!probeRelease.await(3, TimeUnit.SECONDS)) throw new AssertionError("Probe timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        }
        public long setMediaTime(long handle, long time) {
            if (seekFailure) throw new IllegalStateException("Injected seek failure");
            seeks++;
            return time;
        }
        public long getMediaTime(long handle) { return 0; }
        public void setRepeat(long handle, int count) {}
        public void setVolume(long handle, float left, float right) {}
        public long getDuration(long handle) { return duration; }
        public void setDataSource(long handle, byte[] bytes) {}
        public int writeMIDI(long handle, byte[] bytes, int offset, int length) { return 0; }
        public long[] pollEvent(long handle) { return events.poll(); }
    }
}
