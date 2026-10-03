// SPDX-License-Identifier: Apache-2.0
package javax.microedition.media;

import static org.junit.Assert.*;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.expectThrows;

import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.media.protocol.ContentDescriptor;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

import io.github.h3nb.jlmodplus.mmapi.FileCacheDataSource;
import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioQualificationActivity;

/** Sampled output shares the same runtime policy without guest STOPPED/STARTED on suspension. */
@RunWith(AndroidJUnit4.class)
public class MicroPlayerRuntimeTest {
    private ActivityScenario<AudioQualificationActivity> host;
    private Player sampled;
    private Player synthesis;

    @Before public void foreground() {
        RuntimeAudioCoordinator.beginRuntime();
        host = ActivityScenario.launch(AudioQualificationActivity.class);
    }
    @After public void cleanup() {
        if (sampled != null) sampled.close();
        if (synthesis != null) synthesis.close();
        host.close();
    }

    @Test public void hostSuspensionPreservesGuestIntentAndGuestStopPreventsResume() throws Exception {
        sampled = new MicroPlayer(wavSource());
        AtomicInteger starts = new AtomicInteger();
        sampled.addPlayerListener((player, event, data) -> {
            if (PlayerListener.STARTED.equals(event)) starts.incrementAndGet();
        });
        sampled.start();
        awaitProgress(sampled, 100000);
        foreground(false);
        assertEquals(Player.STARTED, sampled.getState());
        long paused = sampled.getMediaTime();
        SystemClock.sleep(120);
        assertEquals(paused, sampled.getMediaTime());
        foreground(true);
        awaitProgress(sampled, paused + 50000);
        assertEquals(1, starts.get());
        foreground(false);
        sampled.stop();
        long stopped = sampled.getMediaTime();
        foreground(true);
        SystemClock.sleep(120);
        assertEquals(Player.PREFETCHED, sampled.getState());
        assertEquals(stopped, sampled.getMediaTime());
        assertEquals(1, starts.get());
    }

    @Test public void runtimeTerminationClosesSampledAndSynthesisWithOneFinalEvent() throws Exception {
        FileCacheDataSource source = wavSource();
        sampled = new MicroPlayer(source);
        // A held middle-C note with an end ten seconds later; entirely generated MIDI.
        byte[] midi = new byte[]{'M','T','h','d',0,0,0,6,0,0,0,1,0,96,
                'M','T','r','k',0,0,0,13,0,(byte)0x90,60,100,
                (byte)0x8f,0,(byte)0x80,60,0,0,(byte)0xff,0x2f,0};
        synthesis = Manager.createPlayer(new ByteArrayInputStream(midi), "audio/midi");
        AtomicInteger closedEvents = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(2);
        PlayerListener listener = (player, event, data) -> {
            if (PlayerListener.CLOSED.equals(event)) { closedEvents.incrementAndGet(); closed.countDown(); }
        };
        sampled.addPlayerListener(listener);
        synthesis.addPlayerListener(listener);
        sampled.start();
        synthesis.start();
        awaitProgress(sampled, 100000);
        RuntimeAudioCoordinator.current().close();
        sampled.close();
        synthesis.close();
        assertTrue("Final callbacks did not drain", closed.await(2, TimeUnit.SECONDS));
        assertEquals(Player.CLOSED, sampled.getState());
        assertEquals(Player.CLOSED, synthesis.getState());
        assertEquals(2, closedEvents.get());
        assertFalse("Sample cache was not disconnected", new File(source.getLocator()).exists());
    }

    @Test public void queuedCompletionAfterHostSuspensionStillEndsAndDoesNotRestart() throws Exception {
        MicroPlayer player = new MicroPlayer(wavSource());
        sampled = player;
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch ended = new CountDownLatch(1);
        player.addPlayerListener((p, event, data) -> {
            if (PlayerListener.END_OF_MEDIA.equals(event)) {
                completions.incrementAndGet();
                ended.countDown();
            }
        });
        player.start();
        awaitProgress(player, 100000);
        foreground(false);
        // Inject an already-queued Android completion at the wrapper boundary after freeze.
        // Avoid depending on scheduler timing to hit a five-second media endpoint.
        player.onCompletion(player.player);
        player.onCompletion(player.player);
        assertEquals(Player.PREFETCHED, player.getState());
        assertTrue("Suspension discarded completion", ended.await(2, TimeUnit.SECONDS));
        long stopped = player.getMediaTime();
        foreground(true);
        SystemClock.sleep(120);
        assertEquals(Player.PREFETCHED, player.getState());
        assertEquals(stopped, player.getMediaTime());
        assertEquals(1, completions.get());
    }

    @Test public void freshRequestAfterPermanentLossDoesNotDuplicateGuestStartedEvent() throws Exception {
        MicroPlayer player = new MicroPlayer(wavSource());
        sampled = player;
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        player.addPlayerListener((p, event, data) -> {
            if (PlayerListener.STARTED.equals(event)) { starts.incrementAndGet(); started.countDown(); }
        });
        player.start();
        awaitProgress(player, 100000);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Field tokenField = MicroPlayer.class.getDeclaredField("playbackToken");
        tokenField.setAccessible(true);
        long token = tokenField.getLong(player);
        // Inject the permanent-loss boundary: revoke coordinator intent before its callback.
        RuntimeAudioCoordinator.current().cancelPlayback(player);
        player.onHostFocusRevoked(token);
        assertEquals(Player.STARTED, player.getState());
        long paused = player.getMediaTime();
        player.start();
        awaitProgress(player, paused + 50000);
        assertEquals(Player.STARTED, player.getState());
        assertEquals(1, starts.get());
    }

    @Test public void failedProtocolConstructionDisconnectsOnceAtEveryAcquisitionStage() {
        for (FailureStage stage : FailureStage.values()) {
            FailingSource source = new FailingSource(stage);
            IOException failure = expectThrows(IOException.class, () -> Manager.createPlayer(source));
            assertEquals(stage.name(), failure.getMessage());
            assertEquals(1, source.connects);
            assertEquals("Failed " + stage + " leaked source resources", 1, source.disconnects);
        }
    }

    private static void foreground(boolean value) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> RuntimeAudioCoordinator.onHostForegroundChanged(value));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void awaitProgress(Player player, long minimum) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (player.getMediaTime() <= minimum && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
        }
        assertTrue("Sampled output did not progress", player.getMediaTime() > minimum);
    }

    private static FileCacheDataSource wavSource() throws Exception {
        int sampleRate = 8000;
        int samples = sampleRate * 5;
        ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        data.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        data.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        data.putShort((short) 2).putShort((short) 16);
        data.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            data.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / sampleRate) * 2000));
        }
        FileCacheDataSource source = new FileCacheDataSource("audio/wav", "wav");
        try (FileOutputStream output = new FileOutputStream(source.getLocator())) {
            output.write(data.array());
        }
        return source;
    }

    private enum FailureStage { CONNECT, START, READ, STOP }

    private static final class FailingSource extends DataSource implements SourceStream {
        final FailureStage failure;
        int connects;
        int disconnects;

        FailingSource(FailureStage failure) { super(null); this.failure = failure; }
        private void check(FailureStage stage) throws IOException {
            if (failure == stage) throw new IOException(stage.name());
        }
        @Override public String getContentType() { return "audio/midi"; }
        @Override public void connect() throws IOException { connects++; check(FailureStage.CONNECT); }
        @Override public void disconnect() { disconnects++; }
        @Override public void start() throws IOException { check(FailureStage.START); }
        @Override public void stop() throws IOException { check(FailureStage.STOP); }
        @Override public SourceStream[] getStreams() { return new SourceStream[]{this}; }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public Control getControl(String name) { return null; }
        @Override public ContentDescriptor getContentDescriptor() { return new ContentDescriptor("audio/midi"); }
        @Override public long getContentLength() { return -1; }
        @Override public int getSeekType() { return NOT_SEEKABLE; }
        @Override public int getTransferSize() { return 512; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            check(FailureStage.READ);
            return -1;
        }
        @Override public long seek(long position) throws IOException { throw new IOException("Not seekable"); }
        @Override public long tell() { return 0; }
    }
}
