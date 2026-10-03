// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static org.junit.Assert.*;

import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.media.Control;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

/** JSR135 wrapper contracts using a deterministic backend, independent of rendered PCM. */
@RunWith(AndroidJUnit4.class)
public class SynthPlayerContractTest {
    private ActivityScenario<AudioQualificationActivity> host;
    private final List<Player> players = new ArrayList<>();

    @Before public void foreground() { host = ActivityScenario.launch(AudioQualificationActivity.class); }
    @After public void cleanup() {
        for (Player player : players) player.close();
        host.close();
    }

    @Test public void closeDisconnectsAndNotifiesOnceEvenWhenListenerClosesAgain() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        CountingSource source = new CountingSource();
        Player player = player(backend, source);
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        player.addPlayerListener((p, event, value) -> {
            if (PlayerListener.CLOSED.equals(event)) {
                closed.incrementAndGet();
                p.close();
                delivered.countDown();
            }
        });
        player.close();
        player.close();
        assertTrue("final callback deadlocked", delivered.await(2, TimeUnit.SECONDS));
        assertEquals(Player.CLOSED, player.getState());
        assertEquals(1, backend.closes);
        assertEquals(1, source.disconnects);
        assertEquals(1, closed.get());
        expectThrows(IllegalStateException.class, player::prefetch);
    }

    @Test public void knownTimeAndDurationSurviveDeallocationAndRePrefetch() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        Player player = player(backend, new CountingSource());
        player.prefetch();
        assertEquals(552000, player.setMediaTime(552000));
        assertEquals(552000, player.getMediaTime());
        assertEquals(2000000, player.getDuration());
        player.deallocate();
        assertEquals(Player.REALIZED, player.getState());
        assertEquals(552000, player.getMediaTime());
        assertEquals(2000000, player.getDuration());
        player.prefetch();
        assertEquals(552000, player.getMediaTime());
    }

    @Test public void midiRequiresPrefetchAndValidatesBoundsWithoutOverflow() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        Player player = player(backend, new CountingSource());
        player.realize();
        MIDIControl midi = (MIDIControl) player.getControl("MIDIControl");
        assertFalse(midi.isBankQuerySupported());
        expectThrows(IllegalStateException.class, () -> midi.shortMidiEvent(0x90, 60, 80));
        expectThrows(IllegalStateException.class, () -> midi.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        assertEquals(0, backend.writes);
        player.prefetch();
        midi.shortMidiEvent(0x90, 60, 80);
        assertEquals(1, backend.writes);
        expectThrows(IllegalArgumentException.class,
                () -> midi.longMidiEvent(new byte[4], Integer.MAX_VALUE, Integer.MAX_VALUE));
        expectThrows(IllegalArgumentException.class, () -> midi.shortMidiEvent(0xf0, 0, 0));
        expectThrows(MediaException.class, () -> midi.getProgram(0));
        player.close();
        expectThrows(IllegalStateException.class, () -> midi.shortMidiEvent(0x80, 60, 0));
    }

    @Test public void callbackExecutorsEndAcrossRepeatedLifecycles() throws Exception {
        int before = callbackThreads();
        for (int cycle = 0; cycle < 20; cycle++) {
            Player player = player(new FakeLibrary(), new CountingSource());
            CountDownLatch closed = new CountDownLatch(1);
            player.addPlayerListener((p, event, value) -> {
                if (PlayerListener.CLOSED.equals(event)) closed.countDown();
            });
            player.prefetch();
            player.close();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        }
        await(() -> callbackThreads() <= before, 2500, "callback threads remain after close");
    }

    @Test public void terminalNativeErrorClosesBeforeDeliveringErrorAndRejectsLateEvents() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        Player player = player(backend, new CountingSource());
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean explainedTerminalError = new AtomicBoolean();
        player.addPlayerListener((p, event, value) -> {
            events.add(event);
            if (PlayerListener.ERROR.equals(event)) {
                explainedTerminalError.set(p.getState() == Player.CLOSED
                        && value instanceof String && !((String) value).isEmpty());
            }
            if (PlayerListener.CLOSED.equals(event)) closed.countDown();
        });
        player.prefetch();
        backend.events.add(new long[]{3, 100000, backend.generation, -1});
        assertTrue("fatal error was not delivered", closed.await(3, TimeUnit.SECONDS));
        backend.events.add(new long[]{2, 2000000, backend.generation, 0});
        SystemClock.sleep(100);
        assertEquals(Player.CLOSED, player.getState());
        assertTrue("ERROR must observe CLOSED and carry an explanation", explainedTerminalError.get());
        assertEquals(1, Collections.frequency(events, PlayerListener.ERROR));
        assertEquals(1, Collections.frequency(events, PlayerListener.CLOSED));
        assertEquals(0, Collections.frequency(events, PlayerListener.END_OF_MEDIA));
    }

    @Test public void terminalActivationFailureStillClosesForSilentShortMidiDelivery() throws Exception {
        FakeLibrary backend = new FakeLibrary() {
            @Override public void activateMidi(long handle) { throw new IllegalStateException("Output cannot open"); }
        };
        CountingSource source = new CountingSource();
        Player player = player(backend, source);
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        player.addPlayerListener((p, event, value) -> {
            events.add(event);
            if (PlayerListener.CLOSED.equals(event)) closed.countDown();
        });
        player.prefetch();
        ((MIDIControl) player.getControl("MIDIControl")).shortMidiEvent(0x90, 60, 100);
        assertTrue("Terminal activation failure missing CLOSED", closed.await(2, TimeUnit.SECONDS));
        assertEquals(Player.CLOSED, player.getState());
        assertEquals(List.of(PlayerListener.ERROR, PlayerListener.CLOSED), events);
        assertEquals(1, backend.closes);
        assertEquals(1, source.disconnects);
    }

    @Test public void staleCompletionAfterSeekCannotStopCurrentPlayback() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        Player player = player(backend, new CountingSource());
        AtomicInteger completions = new AtomicInteger();
        player.addPlayerListener((p, event, value) -> {
            if (PlayerListener.END_OF_MEDIA.equals(event)) completions.incrementAndGet();
        });
        player.start();
        long previous = backend.generation;
        player.setMediaTime(250000);
        backend.events.add(new long[]{2, 2000000, previous, 0});
        await(backend.events::isEmpty, 2000, "stale event not polled");
        assertEquals(Player.STARTED, player.getState());
        assertEquals(0, completions.get());
        backend.events.add(new long[]{2, 2000000, backend.generation, 0});
        await(() -> completions.get() == 1, 2000, "current completion discarded");
        assertEquals(Player.PREFETCHED, player.getState());
    }

    @Test public void slowGuestListenerCannotBlockManagementCompletion() throws Exception {
        FakeLibrary backend = new FakeLibrary();
        Player player = player(backend, new CountingSource());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        player.addPlayerListener((p, event, value) -> {
            if (PlayerListener.STARTED.equals(event)) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else if (PlayerListener.END_OF_MEDIA.equals(event)) completed.countDown();
        });
        try {
            player.start();
            assertTrue("STARTED callback missing", entered.await(2, TimeUnit.SECONDS));
            backend.events.add(new long[]{2, 2000000, backend.generation, 0});
            await(() -> player.getState() == Player.PREFETCHED, 1500,
                    "guest listener stalled native management");
            assertEquals("guest callback unexpectedly unblocked", 1, release.getCount());
        } finally { release.countDown(); }
        assertTrue("EOM callback missing after listener unblocked", completed.await(2, TimeUnit.SECONDS));
    }

    private Player player(FakeLibrary backend, CountingSource source) {
        Player player = new SynthPlayer(backend, source);
        players.add(player);
        return player;
    }

    private static int callbackThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().equals("MidletPlayerCallback")) count++;
        }
        return count;
    }

    static void await(Condition condition, long milliseconds, String message) throws Exception {
        long end = SystemClock.elapsedRealtime() + milliseconds;
        do {
            if (condition.ready()) return;
            SystemClock.sleep(10);
        } while (SystemClock.elapsedRealtime() < end);
        fail(message);
    }

    interface Condition { boolean ready() throws Exception; }

    /** App-bundled JUnit is 4.12; keep runtime checks independent of 4.13 ThrowingRunnable. */
    public static <T extends Throwable> T expectThrows(Class<T> type, ThrowingAction action) {
        Throwable observed = null;
        try { action.run(); } catch (Throwable error) { observed = error; }
        if (observed == null) throw new AssertionError("Expected " + type.getName());
        if (!type.isInstance(observed))
            throw new AssertionError("Expected " + type.getName() + " but got " + observed, observed);
        return type.cast(observed);
    }

    public interface ThrowingAction { void run() throws Throwable; }

    private static final class CountingSource extends DataSource {
        int disconnects;
        CountingSource() { super(Manager.MIDI_DEVICE_LOCATOR); }
        @Override public String getContentType() { return "audio/midi"; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnects++; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public SourceStream[] getStreams() { return new SourceStream[0]; }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public Control getControl(String name) { return null; }
    }

    private static class FakeLibrary implements Library {
        final ConcurrentLinkedQueue<long[]> events = new ConcurrentLinkedQueue<>();
        volatile long generation = 1;
        long time;
        int writes;
        int closes;
        @Override public long createPlayer(String locator) { return 1; }
        @Override public void realize(long handle) { }
        @Override public void prefetch(long handle) { }
        @Override public void start(long handle) { }
        @Override public void pause(long handle) { }
        @Override public void deallocate(long handle) { }
        @Override public void close(long handle) { closes++; }
        @Override public long setMediaTime(long handle, long now) { generation++; return time = now; }
        @Override public long getMediaTime(long handle) { return time; }
        @Override public void setRepeat(long handle, int count) { }
        @Override public void setVolume(long handle, float left, float right) { }
        @Override public long getDuration(long handle) { return 2000000; }
        @Override public void setDataSource(long handle, byte[] bytes) { }
        @Override public int writeMIDI(long handle, byte[] bytes, int offset, int length) { writes++; return length; }
        @Override public long[] pollEvent(long handle) { return events.poll(); }
        @Override public long getGeneration(long handle) { return generation; }
    }
}
