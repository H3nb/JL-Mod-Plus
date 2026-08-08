/*
 * Copyright 2026 H3NB
 * SPDX-License-Identifier: Apache-2.0
 */

package ru.woesss.j2me.mmapi.synth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

import ru.woesss.j2me.mmapi.synth.eas.LibEAS;

/**
 * Device-side smoke coverage for the legacy sequenced parsers restored in the
 * SONiVOX v4 bridge. All fixtures are generated here from their public format
 * grammar; no commercial ringtone/game assets are used.
 *
 * <p>These tests intentionally exercise the real ARM64 JNI/native backend.
 * A process-level native crash therefore fails the instrumentation run rather
 * than being hidden behind a Java mock.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LegacySonivoxParserInstrumentedTest {
    private static final long EVENT_TIMEOUT_SECONDS = 5;

    @Test
    public void iMelodyLifecycleAndMalformedInputAreSafe() throws Exception {
        assertLifecycle(iMelodyFixture(), "imy");
        assertRepeatedOpenClose(iMelodyFixture(), "imy");
        assertControlledMalformedFailure(
                "BEGIN:IMELODY\nVERSION:".getBytes(StandardCharsets.US_ASCII), "imy");
    }

    @Test
    public void rtttlLifecycleAndMalformedInputAreSafe() throws Exception {
        assertLifecycle(rtttlFixture(), "rtttl");
        assertRepeatedOpenClose(rtttlFixture(), "rtttl");
        assertControlledMalformedFailure(
                "broken:d=999,o=9,b=1:".getBytes(StandardCharsets.US_ASCII), "rtttl");
    }

    @Test
    public void nokiaOtaLifecycleAndMalformedInputAreSafe() throws Exception {
        assertLifecycle(nokiaOtaFixture(), "ota");
        assertRepeatedOpenClose(nokiaOtaFixture(), "ota");
        // Valid OTA command prefix, deliberately truncated before the song
        // header. This gets far enough to exercise prepare/cleanup ownership.
        assertControlledMalformedFailure(new byte[]{0x02, 0x4a, 0x3a}, "ota");
    }

    private static void assertLifecycle(byte[] fixture, String suffix) throws Exception {
        Player player = createPlayer(fixture, suffix);
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstEom = new CountDownLatch(1);
        CountDownLatch secondEom = new CountDownLatch(1);
        AtomicInteger eomCount = new AtomicInteger();

        player.addPlayerListener((source, event, data) -> {
            events.add(event);
            if (PlayerListener.END_OF_MEDIA.equals(event)) {
                if (eomCount.incrementAndGet() == 1) {
                    firstEom.countDown();
                } else {
                    secondEom.countDown();
                }
            }
        });

        try {
            assertEquals(Player.UNREALIZED, player.getState());

            player.realize();
            assertEquals(Player.REALIZED, player.getState());

            player.prefetch();
            assertEquals(Player.PREFETCHED, player.getState());

            player.start();
            assertEquals(Player.STARTED, player.getState());
            assertTrue("first END_OF_MEDIA timed out for " + suffix,
                    firstEom.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(Player.PREFETCHED, player.getState());
            assertStartedBeforeFirstEom(events, suffix);

            // JSR-135: start() after natural EOM must replay from the beginning.
            player.start();
            assertTrue("second END_OF_MEDIA timed out for " + suffix,
                    secondEom.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(Player.PREFETCHED, player.getState());

            player.deallocate();
            assertEquals(Player.REALIZED, player.getState());
        } finally {
            player.close();
            assertEquals(Player.CLOSED, player.getState());
            player.close(); // close() must remain idempotent.
            assertEquals(Player.CLOSED, player.getState());
        }
    }

    private static void assertStartedBeforeFirstEom(List<String> events, String suffix) {
        int started = events.indexOf(PlayerListener.STARTED);
        int eom = events.indexOf(PlayerListener.END_OF_MEDIA);
        assertTrue("missing STARTED for " + suffix + ": " + events, started >= 0);
        assertTrue("missing END_OF_MEDIA for " + suffix + ": " + events, eom >= 0);
        assertTrue("STARTED must precede END_OF_MEDIA for " + suffix + ": " + events,
                started < eom);
    }

    private static void assertRepeatedOpenClose(byte[] fixture, String suffix) throws Exception {
        for (int i = 0; i < 3; i++) {
            Player player = createPlayer(fixture, suffix);
            try {
                player.realize();
                player.prefetch();
            } finally {
                player.close();
            }
            assertEquals(Player.CLOSED, player.getState());
        }
    }

    private static void assertControlledMalformedFailure(byte[] fixture, String suffix)
            throws Exception {
        Player player = null;
        try {
            player = createPlayer(fixture, suffix);
            player.realize();
            player.prefetch();
            player.start();
            // Some truncated sequenced files are accepted as an empty song.
            // That is still safe as long as they terminate without a native
            // crash/hang; give the callback thread a short bounded window.
            Thread.sleep(150);
            if (player.getState() == Player.STARTED) {
                player.stop();
            }
        } catch (MediaException | IllegalArgumentException | IllegalStateException expected) {
            // Controlled parser/backend rejection is the required outcome.
        } finally {
            if (player != null) {
                player.close();
            }
        }
    }

    private static Player createPlayer(byte[] fixture, String suffix) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("sonivox-legacy-", "." + suffix, context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(fixture);
        }

        try {
            return new SynthPlayer(new LibEAS(), new TestFileDataSource(file));
        } catch (Throwable error) {
            file.delete();
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error fatalError) {
                throw fatalError;
            }
            throw new AssertionError(error);
        }
    }

    private static byte[] iMelodyFixture() {
        String data = "BEGIN:IMELODY\n"
                + "VERSION:1.2\n"
                + "FORMAT:CLASS1.0\n"
                + "NAME:JL-Mod Plus test\n"
                + "BEAT:120\n"
                + "STYLE:S0\n"
                + "VOLUME:V7\n"
                + "MELODY:*4c5\n"
                + "END:IMELODY\n";
        return data.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] rtttlFixture() {
        return "JLTest:d=32,o=5,b=120:c".getBytes(StandardCharsets.US_ASCII);
    }

    /** Builds one short temporary-song Nokia OTA ringtone, MSB first. */
    private static byte[] nokiaOtaFixture() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(2);          // two OTA commands
        output.write(0x25 << 1);  // Ringing Tone Programming command (7-bit ID)

        BitWriter bits = new BitWriter(output);
        bits.write(0x1d, 7); // Sound command
        bits.write(0x02, 3); // temporary song
        bits.write(1, 8);    // one pattern

        bits.write(0, 3);    // pattern-header instruction
        bits.write(0, 2);    // pattern id 0
        bits.write(0, 4);    // no loop
        bits.write(1, 8);    // one instruction in the pattern

        bits.write(1, 3);    // note instruction
        bits.write(1, 4);    // note C
        bits.write(5, 3);    // 32nd-note duration
        bits.write(0, 2);    // normal duration modifier
        bits.finish();
        return output.toByteArray();
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream output;
        private int current;
        private int count;

        BitWriter(ByteArrayOutputStream output) {
            this.output = output;
        }

        void write(int value, int bitCount) {
            for (int bit = bitCount - 1; bit >= 0; bit--) {
                current = (current << 1) | ((value >> bit) & 1);
                count++;
                if (count == 8) {
                    output.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        void finish() {
            if (count != 0) {
                output.write(current << (8 - count));
                current = 0;
                count = 0;
            }
        }
    }

    private static final class TestFileDataSource extends DataSource {
        private final File file;

        TestFileDataSource(File file) {
            super(file.getAbsolutePath());
            this.file = file;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
            file.delete();
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public SourceStream[] getStreams() {
            return new SourceStream[0];
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public Control getControl(String control) {
            return null;
        }
    }
}
