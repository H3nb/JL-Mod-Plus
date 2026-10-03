// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.expectThrows;
import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.media.Control;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.protocol.ContentDescriptor;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/** Container routing crosses Manager's source cache and the production native parser. */
@RunWith(AndroidJUnit4.class)
public class RmidRuntimeTest {
    private ActivityScenario<AudioQualificationActivity> host;
    private final List<Player> players = new ArrayList<>();
    private final List<File> files = new ArrayList<>();
    private int initialHandles;

    @Before public void foreground() {
        host = ActivityScenario.launch(AudioQualificationActivity.class);
        initialHandles = LibEAS.liveHandles();
    }
    @After public void cleanup() throws Exception {
        for (Player player : players) player.close();
        for (File file : files) file.delete();
        host.close();
        await(() -> LibEAS.liveHandles() == initialHandles, 2500, "RMID leaked native context");
    }

    @Test public void managerRmidAllSourcesRetainDurationSeekResumeAndLooping() throws Exception {
        byte[] smf = SonivoxRuntimeTest.midi(480, 960);
        byte[] bytes = riff("RMID", chunk("JUNK", new byte[]{1, 2, 3}),
                chunk("LIST", join(ascii("INFO"), chunk("INAM", ascii("odd")))),
                chunk("data", smf), chunk("tail", new byte[]{4}));
        MemorySource source = new MemorySource(bytes);
        Player stream = keep(Manager.createPlayer(new ByteArrayInputStream(bytes), null));
        Player protocol = keep(Manager.createPlayer(source));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = context.getExternalFilesDir(null);
        assertNotNull(external);
        String name = "rmid-qualification-" + System.nanoTime() + ".rmi";
        File file = new File(external, name); files.add(file);
        String url = "file:///c:/Android/data/" + context.getPackageName() + "/files/" + name;
        FileConnection connection = (FileConnection) Connector.open(url, Connector.READ_WRITE);
        try {
            connection.create();
            try (java.io.OutputStream output = connection.openOutputStream()) { output.write(bytes); }
        } finally { connection.close(); }
        Player locator = keep(Manager.createPlayer(url));
        Player raw = keep(Manager.createPlayer(new ByteArrayInputStream(smf), "audio/midi"));
        raw.realize();
        long duration = raw.getDuration();
        assertTrue("SMF metadata missing", duration > 0);
        for (Player player : new Player[]{stream, protocol, locator}) {
            assertTrue("RMID bypassed synthesis", player instanceof SynthPlayer);
            player.prefetch();
            assertEquals("RMID duration differs from its SMF", duration, player.getDuration());
            player.start();
            await(() -> player.getMediaTime() >= 100000 && stats(player)[2] > 0, 3000,
                    "RMID did not produce nonzero PCM");
            player.stop();
            long stopped = player.getMediaTime();
            SystemClock.sleep(60);
            assertEquals("RMID stop advanced time", stopped, player.getMediaTime());
            long samples = stats(player)[2];
            player.start();
            await(() -> player.getMediaTime() > stopped + 50000 && stats(player)[2] > samples,
                    3000, "RMID resume discarded sequence/voices");
            player.stop();
            assertEquals(250000, player.setMediaTime(250000));
            player.start();
            await(() -> player.getMediaTime() > 300000, 3000, "RMID seek did not resume");
            player.stop(); player.setMediaTime(0); player.setLoopCount(2);
            CountDownLatch ended = new CountDownLatch(2);
            player.addPlayerListener((p, event, data) -> {
                if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown();
            });
            player.start();
            assertTrue("RMID loops did not reach both EOM events", ended.await(5, TimeUnit.SECONDS));
            await(() -> player.getState() == Player.PREFETCHED, 1000, "RMID final EOM state");
            player.close(); player.close();
        }
        assertEquals(1, source.connects); assertEquals(1, source.disconnects);
        assertTrue("RMID caller locator removed by Player", file.isFile());
    }

    @Test public void corruptAndEmbeddedBankContainersFailClearlyAndReleaseSource() throws Exception {
        byte[] data = chunk("data", SonivoxRuntimeTest.midi(1, 2));
        byte[] truncated = riff("RMID", data);
        truncated = java.util.Arrays.copyOf(truncated, truncated.length - 1);
        byte[] overflow = riff("RMID", chunk("JUNK", new byte[0]), data);
        java.util.Arrays.fill(overflow, 16, 20, (byte) 0xff);
        byte[] bank = riff("DLS ");
        byte[][] corrupt = {truncated, overflow, riff("RMID", data, data), riff("RMID"),
                riff("RMID", chunk("data", new byte[14])),
                riff("RMID", data, chunk("LIST", ascii("INFOx"))),
                riff("RMID", data, bank),
                riff("RMID", data, chunk("LIST", join(ascii("INFO"), bank)))};
        for (int index = 0; index < corrupt.length; index++) {
            MemorySource source = new MemorySource(corrupt[index]);
            MediaException error = expectThrows(MediaException.class, () -> Manager.createPlayer(source));
            assertTrue("RMID failure was hidden by fallback: " + error,
                    error.getMessage().contains(index >= 6 ? "embedded DLS" : "RIFF/RMID"));
            assertEquals(1, source.connects); assertEquals(1, source.disconnects);
            assertEquals("corrupt RMID leaked native handle", initialHandles, LibEAS.liveHandles());
        }
    }

    @Test public void riffWaveRemainsSampled() throws Exception {
        int sampleRate = 8000, samples = 8000;
        ByteBuffer format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort((short) 2).putShort((short) 16);
        Player wave = keep(Manager.createPlayer(new ByteArrayInputStream(riff("WAVE",
                chunk("fmt ", format.array()), chunk("data", new byte[samples * 2]))), "audio/wav"));
        assertEquals("WAVE was routed to synthesis", "javax.microedition.media.MicroPlayer", wave.getClass().getName());
        wave.prefetch();
        wave.start(); // AndroidPlayer prepares its sampled source lazily on start.
        assertTrue(wave.getDuration() > 0);
    }

    private Player keep(Player player) { players.add(player); return player; }
    private static long[] stats(Player player) throws Exception {
        Field library = SynthPlayer.class.getDeclaredField("library");
        Field handle = SynthPlayer.class.getDeclaredField("handle");
        library.setAccessible(true); handle.setAccessible(true);
        return ((LibEAS) library.get(player)).diagnostics(handle.getLong(player));
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] join(byte[]... bytes) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] part : bytes) result.write(part, 0, part.length);
        return result.toByteArray();
    }
    private static byte[] chunk(String id, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(ascii(id)).putInt(payload.length);
        return join(header.array(), payload, new byte[payload.length & 1]);
    }
    private static byte[] riff(String type, byte[]... chunks) {
        return chunk("RIFF", join(ascii(type), join(chunks)));
    }
    private static final class MemorySource extends DataSource implements SourceStream {
        final byte[] bytes; int cursor, connects, disconnects;
        MemorySource(byte[] bytes) { super(null); this.bytes = bytes; }
        @Override public String getContentType() { return "audio/wav"; } // Content overrides this hint.
        @Override public void connect() { connects++; }
        @Override public void disconnect() { disconnects++; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public SourceStream[] getStreams() { return new SourceStream[]{this}; }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public Control getControl(String name) { return null; }
        @Override public ContentDescriptor getContentDescriptor() { return new ContentDescriptor(getContentType()); }
        @Override public long getContentLength() { return bytes.length; }
        @Override public int getSeekType() { return RANDOM_ACCESSIBLE; }
        @Override public int getTransferSize() { return 512; }
        @Override public int read(byte[] output, int offset, int length) {
            if (cursor == bytes.length) return -1;
            int count = Math.min(length, bytes.length - cursor);
            System.arraycopy(bytes, cursor, output, offset, count); cursor += count; return count;
        }
        @Override public long seek(long value) { cursor = (int) Math.max(0, Math.min(bytes.length, value)); return cursor; }
        @Override public long tell() { return cursor; }
    }
}
