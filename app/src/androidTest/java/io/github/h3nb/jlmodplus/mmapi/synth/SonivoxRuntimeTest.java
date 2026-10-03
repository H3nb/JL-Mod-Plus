// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.expectThrows;
import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Debug;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.microedition.media.Control;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.protocol.ContentDescriptor;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/** Exercises the production JNI/backend and Manager, with wholly generated fixtures. */
@RunWith(AndroidJUnit4.class)
public class SonivoxRuntimeTest {
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
        await(() -> LibEAS.liveHandles() == initialHandles, 2500, "native handle registry did not return to baseline");
    }

    @Test public void managerRoutesStreamDataSourceAndLocatorToIsolatedSynthesis() throws Exception {
        byte[] midi = midi(960, 1920);
        Player stream = keep(Manager.createPlayer(new ByteArrayInputStream(midi), null));
        MemorySource source = new MemorySource(midi, "application/octet-stream");
        Player protocol = keep(Manager.createPlayer(source));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = context.getExternalFilesDir(null);
        assertNotNull("qualification external files unavailable", external);
        String name = "audio-qualification-" + System.nanoTime() + ".mid";
        File file = new File(external, name);
        files.add(file);
        String locatorUrl = "file:///c:/Android/data/" + context.getPackageName() + "/files/" + name;
        FileConnection connection = (FileConnection) Connector.open(locatorUrl, Connector.READ_WRITE);
        try {
            connection.create();
            try (java.io.OutputStream output = connection.openOutputStream()) { output.write(midi); }
        } finally { connection.close(); }
        Player locator = keep(Manager.createPlayer(locatorUrl));
        for (Player player : new Player[]{stream, protocol, locator}) {
            assertTrue("Manager bypassed synthesis", player instanceof SynthPlayer);
            player.prefetch();
            assertTrue(player.getDuration() > 0);
        }
        protocol.close();
        protocol.close();
        assertEquals(1, source.connects);
        assertEquals(1, source.disconnects);
        assertTrue("caller source deleted", file.isFile());
    }

    @Test public void stopFreezesTimeResumeAndPausedSeekPreservePlayback() throws Exception {
        Player player = keep(Manager.createPlayer(new ByteArrayInputStream(midi(9600, 10560)), "audio/midi"));
        player.start();
        await(() -> player.getMediaTime() >= 150000, 3000, "MIDI did not progress");
        assertTrue("progress without audible PCM", stats(player)[2] > 0);
        player.stop();
        long stopped = player.getMediaTime();
        SystemClock.sleep(100);
        assertEquals(stopped, player.getMediaTime());
        player.start();
        long samples = stats(player)[2];
        await(() -> player.getMediaTime() > stopped + 50000, 3000, "MIDI did not resume");
        assertTrue("held voice did not resume", stats(player)[2] > samples);
        player.stop();
        assertEquals(0, player.setMediaTime(-1));
        assertTrue(player.setMediaTime(Long.MAX_VALUE) <= player.getDuration());
        assertEquals(250000, player.setMediaTime(250000));
        player.deallocate();
        assertEquals(250000, player.getMediaTime());
        player.start();
        await(() -> player.getMediaTime() > 300000, 3000, "seek/deallocate discarded position");
    }

    @Test public void ultraShortMidiOrdersStartBeforeEndAndFiniteLoopsComplete() throws Exception {
        Player player = keep(Manager.createPlayer(new ByteArrayInputStream(midi(1, 2)), "audio/midi"));
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(3);
        player.addPlayerListener((p, event, data) -> {
            if (PlayerListener.STARTED.equals(event) || PlayerListener.END_OF_MEDIA.equals(event)) events.add(event);
            if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown();
        });
        player.setLoopCount(3);
        player.start();
        assertTrue("finite loop failed to end", ended.await(5, TimeUnit.SECONDS));
        await(() -> player.getState() == Player.PREFETCHED, 1000, "EOM state");
        assertEquals(List.of(PlayerListener.STARTED, PlayerListener.END_OF_MEDIA,
                PlayerListener.STARTED, PlayerListener.END_OF_MEDIA,
                PlayerListener.STARTED, PlayerListener.END_OF_MEDIA), events);
        player.start();
        await(() -> Collections.frequency(events, PlayerListener.END_OF_MEDIA) >= 4, 3000, "restart after EOM");
        player.stop();
        player.setLoopCount(-1);
        player.setMediaTime(0);
        int beforeInfinite = Collections.frequency(events, PlayerListener.END_OF_MEDIA);
        player.start();
        await(() -> Collections.frequency(events, PlayerListener.END_OF_MEDIA) >= beforeInfinite + 3,
                3000, "infinite looping did not restart");
        assertEquals(Player.STARTED, player.getState());
        player.stop();
        long stopped = player.getMediaTime();
        SystemClock.sleep(70);
        assertEquals(stopped, player.getMediaTime());
    }

    @Test public void twoPlayersHaveIndependentSequencersAndCloseDoesNotStopPeer() throws Exception {
        Player first = keep(Manager.createPlayer(new ByteArrayInputStream(midi(9600, 10560)), "audio/midi"));
        Player second = keep(Manager.createPlayer(new ByteArrayInputStream(midi(9600, 10560)), "audio/midi"));
        first.start();
        second.start();
        await(() -> second.getMediaTime() > 100000, 3000, "second MIDI did not start");
        first.stop();
        first.close();
        long position = second.getMediaTime();
        await(() -> second.getMediaTime() > position + 100000, 3000, "closing peer stopped output");
        assertEquals(Player.STARTED, second.getState());
    }

    @Test public void contextLimitRejectsExcessPlayerWithoutLeakingExistingContexts() throws Exception {
        assertEquals("qualification requires an idle runtime", 0, initialHandles);
        for (int index = 0; index < 16; index++) keep(Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR));
        assertEquals(16, LibEAS.liveHandles());
        expectThrows(MediaException.class, () -> Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR));
        assertEquals("rejected context changed existing handles", 16, LibEAS.liveHandles());
        for (Player player : players) player.close();
        assertEquals(0, LibEAS.liveHandles());
    }

    @Test public void interactiveMidiBeforeStartAndToneSequenceUseProductionBackend() throws Exception {
        Player midi = keep(Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR));
        midi.realize();
        MIDIControl control = (MIDIControl) midi.getControl("MIDIControl");
        expectThrows(IllegalStateException.class, () -> control.shortMidiEvent(0x90, 60, 100));
        midi.prefetch();
        control.shortMidiEvent(0x90, 60, 100);
        assertEquals(Player.PREFETCHED, midi.getState());
        await(() -> stats(midi)[2] > 0, 2000, "prefetched interactive MIDI produced no PCM");
        VolumeControl volume = (VolumeControl) midi.getControl("VolumeControl");
        assertEquals(100, volume.setLevel(200));
        volume.setMute(true);
        SystemClock.sleep(30);
        long mutedSamples = stats(midi)[2];
        long mutedFrames = stats(midi)[0];
        SystemClock.sleep(70);
        assertEquals("mute leaked PCM", mutedSamples, stats(midi)[2]);
        assertTrue("mute froze MIDI engine", stats(midi)[0] > mutedFrames);
        assertEquals(100, volume.getLevel());
        volume.setMute(false);
        await(() -> stats(midi)[2] > mutedSamples, 2000, "unmute did not restore held voice");
        byte[] oversized = new byte[16385];
        java.util.Arrays.fill(oversized, (byte) 0xf8);
        assertEquals(-1, control.longMidiEvent(oversized, 0, oversized.length));
        assertEquals("queue rejection corrupted MIDI player", Player.PREFETCHED, midi.getState());
        control.shortMidiEvent(0x80, 60, 0);
        Player tone = keep(Manager.createPlayer(Manager.TONE_DEVICE_LOCATOR));
        tone.realize();
        ToneControl tones = (ToneControl) tone.getControl("ToneControl");
        assertNotNull(tones);
        tones.setSequence(new byte[]{ToneControl.VERSION, 1, ToneControl.TEMPO, 30,
                ToneControl.RESOLUTION, 64, 60, 4});
        CountDownLatch end = end(tone);
        tone.start();
        assertTrue("ToneControl did not complete", end.await(3, TimeUnit.SECONDS));
        expectThrows(IllegalStateException.class, () -> tones.setSequence(new byte[]{-2, 1, 60, 4}));
        int beforePlayTone = LibEAS.liveHandles();
        Manager.playTone(60, 100, 60);
        await(() -> LibEAS.liveHandles() == beforePlayTone, 3000,
                "Manager.playTone did not release its completed Player");
    }

    @Test public void ringtoneParsersRecognizeContentsDespiteMidiMimeHint() throws Exception {
        byte[][] payloads = {
                "fixture:d=4,o=5,b=120:c".getBytes(StandardCharsets.US_ASCII),
                "BEGIN:IMELODY\r\nVERSION:1.2\r\nFORMAT:CLASS1.0\r\nBEAT:120\r\nMELODY:*5c2\r\nEND:IMELODY\r\n"
                        .getBytes(StandardCharsets.US_ASCII),
                ota()
        };
        for (byte[] bytes : payloads) {
            Player player = keep(Manager.createPlayer(new ByteArrayInputStream(bytes), "audio/midi"));
            assertTrue(player instanceof SynthPlayer);
            CountDownLatch end = end(player);
            player.start();
            assertTrue("ringtone parser failed to reach EOM", end.await(5, TimeUnit.SECONDS));
            assertEquals(Player.PREFETCHED, player.getState());
            player.close();
        }
        com.nokia.mid.sound.Sound sound = new com.nokia.mid.sound.Sound(ota(), com.nokia.mid.sound.Sound.FORMAT_TONE);
        try {
            assertEquals(com.nokia.mid.sound.Sound.SOUND_STOPPED, sound.getState());
            sound.play(1);
            await(() -> sound.getState() == com.nokia.mid.sound.Sound.SOUND_PLAYING, 1000, "Nokia OTA route");
        } finally { sound.release(); }
    }

    @Test public void recognizedCorruptMediaAndMissingBankFailWithoutSilentFallback() throws Exception {
        expectThrows(MediaException.class,
                () -> Manager.createPlayer(new ByteArrayInputStream(new byte[]{'M','T','h','d',0,0,0,6}), "audio/midi"));
        LibEAS bank = new LibEAS(new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir(), "missing-qualification.sf2").getAbsolutePath());
        expectThrows(MediaException.class, () -> new SynthPlugin(bank).createPlayer(Manager.MIDI_DEVICE_LOCATOR));
    }

    @Test public void generatedSf2AndDlsBanksBothRenderWithIsolatedContexts() throws Exception {
        for (byte[] bytes : new byte[][]{AudioBankFixtures.sf2(), AudioBankFixtures.dls()}) {
            File bank = fixture(".bank", bytes);
            LibEAS backend = new LibEAS(bank.getAbsolutePath());
            File media = fixture(".mid", midi(240, 480));
            Player player = keep(new SynthPlugin(backend).createPlayer(new PathSource(media)));
            CountDownLatch end = end(player);
            player.start();
            assertTrue("generated bank did not complete", end.await(4, TimeUnit.SECONDS));
            assertTrue("bank completed without audible PCM", stats(player)[2] > 0);
            player.close();
        }
    }

    /** Optional local bank path in target-private files: never an application preset or distributed asset. */
    @Test public void suppliedLocalSoundBankPlaysAndRepeatedResourcesClose() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("sonivoxBankPath");
        Assume.assumeTrue("No local bank supplied; built-in bank tested by other cases", path != null);
        File bank = new File(path);
        assertTrue("local bank missing", bank.isFile());
        LibEAS backend = new LibEAS(bank.getAbsolutePath());
        File media = fixture(".mid", midi(1, 2));
        long firstBegin = SystemClock.elapsedRealtimeNanos();
        Player warmup = keep(new SynthPlugin(backend).createPlayer(new PathSource(media)));
        Bundle firstLoad = new Bundle();
        firstLoad.putString("stream", "Bank first context load: bankBytes=" + bank.length()
                + ", contextLoadMs=" + ((SystemClock.elapsedRealtimeNanos() - firstBegin) / 1000000.0) + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, firstLoad);
        CountDownLatch warmupEnd = end(warmup);
        warmup.start();
        assertTrue("custom bank warmup", warmupEnd.await(3, TimeUnit.SECONDS));
        warmup.close();
        assertEquals(initialHandles, LibEAS.liveHandles());
        long beforeBytes = Debug.getNativeHeapAllocatedSize();
        for (int cycle = 0; cycle < 20; cycle++) {
            long begin = SystemClock.elapsedRealtimeNanos();
            Player player = keep(new SynthPlugin(backend).createPlayer(new PathSource(media)));
            long loadNanos = SystemClock.elapsedRealtimeNanos() - begin;
            CountDownLatch end = end(player);
            player.start();
            assertTrue("custom bank cycle " + cycle, end.await(3, TimeUnit.SECONDS));
            assertTrue("custom bank produced no PCM", stats(player)[2] > 0);
            player.close();
            assertEquals(initialHandles, LibEAS.liveHandles());
            if (cycle == 0 || cycle == 4 || cycle == 9 || cycle == 19) {
                Bundle status = new Bundle();
                status.putString("stream", "Bank cycle " + (cycle + 1) + ": bankBytes=" + bank.length()
                        + ", contextLoadMs=" + (loadNanos / 1000000.0)
                        + ", retainedNativeAllocatedBytes=" + (Debug.getNativeHeapAllocatedSize() - beforeBytes)
                        + ", liveHandles=" + LibEAS.liveHandles() + "\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
            }
        }
    }

    private Player keep(Player player) { assertNotNull(player); players.add(player); return player; }
    /** Read backend diagnostics through the existing wrapper ownership boundary. */
    private static long[] stats(Player player) throws Exception {
        Field library = SynthPlayer.class.getDeclaredField("library");
        Field handle = SynthPlayer.class.getDeclaredField("handle");
        library.setAccessible(true); handle.setAccessible(true);
        return ((LibEAS) library.get(player)).diagnostics(handle.getLong(player));
    }
    private static CountDownLatch end(Player player) {
        CountDownLatch ended = new CountDownLatch(1);
        player.addPlayerListener((p, event, value) -> { if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown(); });
        return ended;
    }

    private File fixture(String suffix, byte[] bytes) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("audio-qualification-", suffix, context.getFilesDir());
        files.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return file;
    }

    static byte[] midi(int releaseTicks, int endTicks) throws Exception {
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        events.write(new byte[]{0, (byte) 0xff, 0x51, 3, 7, (byte) 0xa1, 0x20,
                0, (byte) 0xc0, 40, 0, (byte) 0x90, 60, 80});
        variable(events, releaseTicks);
        events.write(new byte[]{(byte) 0x80, 60, 0});
        variable(events, endTicks - releaseTicks);
        events.write(new byte[]{(byte) 0xff, 0x2f, 0});
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeBytes("MThd"); out.writeInt(6); out.writeShort(0); out.writeShort(1); out.writeShort(480);
        out.writeBytes("MTrk"); out.writeInt(events.size()); events.writeTo(out);
        return file.toByteArray();
    }

    private static void variable(ByteArrayOutputStream out, int value) {
        int encoded = value & 127;
        while ((value >>= 7) != 0) encoded = (encoded << 8) | (value & 127) | 128;
        while (true) {
            out.write(encoded & 255);
            if ((encoded & 128) == 0) break;
            encoded >>>= 8;
        }
    }

    static byte[] ota() {
        Bits bits = new Bits();
        bits.add(2, 8); bits.add(0x25, 7); bits.add(0, 1); bits.add(0x1d, 7);
        bits.add(2, 3); bits.add(1, 8); // temporary song, one pattern
        bits.add(0, 3); bits.add(0, 2); bits.add(0, 4); bits.add(2, 8);
        bits.add(4, 3); bits.add(16, 5); // 125 BPM
        bits.add(1, 3); bits.add(1, 4); bits.add(2, 3); bits.add(0, 2); // C, quarter
        return bits.finish();
    }

    private static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count; int value;
        void add(int data, int width) {
            for (int index = width - 1; index >= 0; index--) {
                value = (value << 1) | ((data >> index) & 1);
                if (++count == 8) { out.write(value); count = 0; value = 0; }
            }
        }
        byte[] finish() { if (count > 0) out.write(value << (8 - count)); return out.toByteArray(); }
    }

    private static class PathSource extends DataSource {
        PathSource(File file) { super(file.getAbsolutePath()); }
        @Override public String getContentType() { return "audio/midi"; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public SourceStream[] getStreams() { return new SourceStream[0]; }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public Control getControl(String name) { return null; }
    }

    private static final class MemorySource extends DataSource implements SourceStream {
        final byte[] bytes; final String type;
        int cursor; int connects; int disconnects;
        MemorySource(byte[] bytes, String type) { super(null); this.bytes = bytes; this.type = type; }
        @Override public String getContentType() { return type; }
        @Override public void connect() { connects++; }
        @Override public void disconnect() { disconnects++; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public SourceStream[] getStreams() { return new SourceStream[]{this}; }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public Control getControl(String name) { return null; }
        @Override public ContentDescriptor getContentDescriptor() { return new ContentDescriptor(type); }
        @Override public long getContentLength() { return bytes.length; }
        @Override public int getSeekType() { return RANDOM_ACCESSIBLE; }
        @Override public int getTransferSize() { return 512; }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (cursor == bytes.length) return -1;
            int count = Math.min(length, bytes.length - cursor);
            System.arraycopy(bytes, cursor, buffer, offset, count); cursor += count; return count;
        }
        @Override public long seek(long position) { cursor = (int) Math.max(0, Math.min(bytes.length, position)); return cursor; }
        @Override public long tell() { return cursor; }
    }
}
