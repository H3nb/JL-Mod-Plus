// SPDX-License-Identifier: Apache-2.0
package javax.microedition.media;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioPlayer;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioQualificationActivity;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;
import io.github.h3nb.jlmodplus.mmapi.video.VideoDisplay;
import io.github.h3nb.jlmodplus.mmapi.video.VideoLibrary;
import io.github.h3nb.jlmodplus.util.Constants;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Guest-facing filesystem/Android contracts, using only generated media and an owned workdir. */
@RunWith(AndroidJUnit4.class)
public class MediaBoundaryRuntimeTest {
    private static final long LIMIT = 64L * 1024 * 1024;
    private final ArrayList<Player> players = new ArrayList<>();
    private SharedPreferences preferences;
    private boolean hadRoot;
    private String previous;
    private File root, cache;
    private int handles;

    @Before public void ownedWorkdir() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        hadRoot = preferences.contains(Constants.PREF_EMULATOR_DIR);
        previous = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
        root = new File(context.getCacheDir(), "media-boundary-" + System.nanoTime());
        cache = new File(root, "cache");
        Config.getEmulatorDir(); // Register the listener before selecting the owned directory.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.getPath()).commit()));
        RuntimeAudioCoordinator.beginRuntime();
        handles = LibEAS.liveHandles();
    }

    @After public void restoreWorkdir() {
        try {
            for (Player player : players) player.close();
            RuntimeAudioCoordinator.current().close();
            assertEquals("Native source leaked", handles, LibEAS.liveHandles());
            assertNoCache();
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                SharedPreferences.Editor restore = preferences.edit();
                if (hadRoot) restore.putString(Constants.PREF_EMULATOR_DIR, previous);
                else restore.remove(Constants.PREF_EMULATOR_DIR);
                assertTrue(restore.commit());
            });
            File[] files = cache.listFiles();
            if (files != null) for (File file : files) file.delete();
            cache.delete();
            root.delete();
        }
    }

    @Test public void managerRejectsOversizedMmmdAndOtherInputBeforePublication() throws Exception {
        for (boolean mmmd : new boolean[] {true, false}) {
            GeneratedInput input = new GeneratedInput(LIMIT + 1, mmmd);
            try {
                Player unexpected = Manager.createPlayer(input, mmmd ? "audio/mmf" : "audio/wav");
                unexpected.close();
                fail("Oversized guest input accepted");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("64 MiB"));
            }
            assertFalse("Manager closed the caller's stream", input.closed);
            assertEquals("Read beyond the first rejected byte", LIMIT + 1, input.position);
            assertNoCache();
            assertEquals("Conversion/player was published", handles, LibEAS.liveHandles());
        }
    }

    @Test public void exactCacheLimitAllowsZeroReadFollowedByEof() throws Exception {
        GeneratedInput input = new GeneratedInput(LIMIT, true);
        InternalDataSource source = new InternalDataSource(input, "audio/mmf");
        try {
            assertEquals(LIMIT, new File(source.getLocator()).length());
            assertTrue(source.isSmaf());
            assertTrue("Zero-read boundary not exercised", input.zeroAtLimit);
            assertFalse(input.closed);
        } finally { source.disconnect(); }
        assertNoCache();
    }

    @Test public void multipleAudioTracksRejectVideoAndPreserveAudioOnlyRouting() throws Exception {
        try {
            Player unexpected = asset("video/multi-audio.mp4");
            unexpected.close();
            fail("Video accepted ambiguous soundtracks");
        } catch (MediaException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Multiple audio tracks"));
        }
        assertNoCache();
        assertEquals(handles, LibEAS.liveHandles());
        Player audio = asset("video/multi-audio-only.mp4");
        audio.realize();
        assertNull(audio.getControl("VideoControl"));
        assertEquals("audio/mp4", audio.getContentType());
    }

    @Test public void legacyDurationIsUnknownUntilLoadedThenUsesMicroseconds() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player player = asset("audio/legacy-yamaha.mmf");
            assertTrue(player instanceof MicroPlayer);
            assertEquals(Player.TIME_UNKNOWN, player.getDuration());
            player.prefetch();
            assertEquals("Unloaded media is not zero duration", Player.TIME_UNKNOWN, player.getDuration());
            player.start();
            assertEquals(1024000, player.getDuration());
            player.stop();
        }
    }

    @Test public void generatedSynthesisSampledAndVideoShareOutputAndSeekDrainsVideo() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player video = asset("video/markers.mp4"), sampled = asset("audio/pcm.wav");
            byte[] midi = {'M','T','h','d',0,0,0,6,0,0,0,1,0,96,
                    'M','T','r','k',0,0,0,13,0,(byte)0x90,60,100,
                    (byte)0x8f,0,(byte)0x80,60,0,0,(byte)0xff,0x2f,0};
            Player synth = Manager.createPlayer(new ByteArrayInputStream(midi), "audio/midi");
            players.add(synth);
            video.realize();
            VideoDisplay display = (VideoDisplay) video.getControl("VideoControl");
            display.initDisplayMode(0, null);
            host.onActivity(a -> a.setContentView(display.itemView(a, 2)));
            Field field = AudioPlayer.class.getDeclaredField("library");
            field.setAccessible(true);
            VideoLibrary backend = (VideoLibrary) field.get(video);
            sampled.setLoopCount(-1);
            synth.setLoopCount(-1);
            sampled.start(); synth.start(); video.start();
            long deadline = SystemClock.uptimeMillis() + 5000;
            while ((backend.diagnostics()[0] <= 3 || sampled.getMediaTime() <= 100000
                    || synth.getMediaTime() <= 100000) && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(20);
            assertTrue("Video did not render", backend.diagnostics()[0] > 3);
            assertTrue("Sampled audio did not progress", sampled.getMediaTime() > 100000);
            assertTrue("Synthesis did not progress", synth.getMediaTime() > 100000);
            assertEquals("Outputs were not shared", 1, backend.runtimeDiagnostics()[2]);
            CountDownLatch ended = new CountDownLatch(1);
            video.addPlayerListener((p, event, value) -> {
                if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown();
            });
            assertEquals(3500000, video.setMediaTime(3500000));
            assertTrue("Video seek did not drain", ended.await(5, TimeUnit.SECONDS));
            assertEquals(Player.PREFETCHED, video.getState());
            assertTrue(video.getDuration() >= 3900000);
            assertEquals(Player.STARTED, sampled.getState());
            assertEquals(Player.STARTED, synth.getState());
        }
    }

    private Player asset(String path) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(path)) {
            Player player = Manager.createPlayer(input, null);
            players.add(player);
            return player;
        }
    }

    private void assertNoCache() {
        File[] files = cache.listFiles();
        assertTrue("Temporary cache leaked", files == null || files.length == 0);
    }

    /** Constant memory; bulk reads yield zero once at the limit to exercise the single-byte fallback. */
    private static final class GeneratedInput extends InputStream {
        private final long length;
        private final byte[] prefix;
        private long position;
        private boolean zeroAtLimit, closed;
        GeneratedInput(long length, boolean mmmd) {
            this.length = length;
            prefix = (mmmd ? "MMMD" : "RIFF").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        @Override public int read(byte[] buffer, int offset, int count) {
            if (count == 0) return 0;
            if (position == LIMIT && !zeroAtLimit) { zeroAtLimit = true; return 0; }
            if (position == length) return -1;
            int size = (int) Math.min(count, Math.min(length - position,
                    position < LIMIT ? LIMIT - position : length - position));
            java.util.Arrays.fill(buffer, offset, offset + size, (byte) 0);
            for (int i = 0; i < size && position + i < prefix.length; i++)
                buffer[offset + i] = prefix[(int) position + i];
            position += size;
            return size;
        }
        @Override public int read() {
            if (position == length) return -1;
            int value = position < prefix.length ? prefix[(int) position] & 255 : 0;
            position++;
            return value;
        }
        @Override public void close() { closed = true; }
    }
}
