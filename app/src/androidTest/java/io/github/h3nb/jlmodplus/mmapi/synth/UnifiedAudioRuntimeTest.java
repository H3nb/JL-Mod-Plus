// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static org.junit.Assert.*;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.expectThrows;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.TimeBase;
import javax.microedition.media.control.MetaDataControl;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/** Android bridge checks; native tests own framing, mixing and recovery interleavings. */
@RunWith(AndroidJUnit4.class)
public class UnifiedAudioRuntimeTest {
    private ActivityScenario<AudioQualificationActivity> host;
    private final ArrayList<Player> players = new ArrayList<>();
    private int initialHandles;
    @Before public void foreground() {
        host = ActivityScenario.launch(AudioQualificationActivity.class);
        initialHandles = LibEAS.liveHandles();
    }
    @After public void cleanup() {
        for (Player player : players) player.close();
        assertEquals(initialHandles, LibEAS.liveHandles());
        host.close();
    }

    @Test public void retainedLegacyWrapperLoadsWithTheSingleNativeDependencySet() {
        assertEquals("n6.0", FFmpegKitConfig.getFFmpegVersion());
    }

    @Test public void retainedFormatsResolveWithoutMimeAndDrainThroughCommonPlayer() throws Exception {
        String[] fixtures = {"pcm.wav", "adpcm.wav", "alaw.wav", "gsm.wav", "effect.mp3", "effect.aac",
                "generated-tone-dtx-nb.amr", "generated-dtx-nb.amr", "generated-sid-wb.awb"};
        for (String fixture : fixtures) {
            Player player = asset(fixture, null);
            assertTrue(fixture, player instanceof AudioPlayer);
            CountDownLatch ended = new CountDownLatch(1);
            player.addPlayerListener((p, event, value) -> {
                if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown();
            });
            player.prefetch();
            assertNull(player.getControl("MIDIControl"));
            assertNull(player.getControl("ToneControl"));
            assertEquals(0, player.getMediaTime());
            player.start();
            assertTrue(fixture + " did not drain", ended.await(7, TimeUnit.SECONDS));
            assertEquals(Player.PREFETCHED, player.getState());
            assertTrue(fixture + " has no known final duration", player.getDuration() > 0);
            long time = player.getMediaTime(), duration = player.getDuration();
            assertTrue(fixture + " EOS time mismatch", Math.abs(time - duration) <= 30000);
            player.deallocate();
            assertEquals(time, player.getMediaTime()); assertEquals(duration, player.getDuration());
            player.close();
        }
    }

    @Test public void sampledMetadataAndTimeBaseUseActualFormatAndIndependentClock() throws Exception {
        Player player = asset("tagged.wav", "audio/midi");
        expectThrows(IllegalStateException.class, player::getTimeBase);
        player.prefetch();
        assertEquals("audio/wav", player.getContentType());
        MetaDataControl metadata = (MetaDataControl) player.getControl("MetaDataControl");
        assertEquals("Nada 🟢", metadata.getKeyValue(MetaDataControl.TITLE_KEY));
        assertEquals("22050", metadata.getKeyValue("samplerate"));
        assertEquals("1", metadata.getKeyValue("channels"));
        TimeBase clock = player.getTimeBase();
        long before = clock.getTime(); SystemClock.sleep(30);
        assertTrue(clock.getTime() >= before + 20000);
        assertEquals(0, player.getMediaTime());
        player.setTimeBase(null);
        expectThrows(MediaException.class, () -> player.setTimeBase(() -> 0));
        player.start();
        expectThrows(IllegalStateException.class, () -> player.setTimeBase(null));
        player.stop();
        assertSame(Manager.getSystemTimeBase(), player.getTimeBase());
    }

    @Test public void corruptSampledCreationDoesNotCloseOrReplacePlayingPeer() throws Exception {
        Player peer = asset("effect.mp3", null);
        peer.setLoopCount(-1); peer.start();
        await(() -> peer.getMediaTime() > 50000, 3000, "Peer did not start");
        long[] before = runtime(peer);
        int handles = LibEAS.liveHandles();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("audio/corrupt.wav")) {
            expectThrows(MediaException.class, () -> Manager.createPlayer(input, "audio/mpeg"));
        }
        assertEquals(handles, LibEAS.liveHandles());
        long frames = stats(peer)[0];
        await(() -> stats(peer)[0] > frames + 512, 3000, "Corrupt source stopped peer");
        long[] after = runtime(peer);
        assertEquals(before[0], after[0]); assertEquals(before[1], after[1]);
        assertEquals(1, after[2]);
    }

    private Player asset(String name, String mime) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("audio/" + name)) {
            Player player = Manager.createPlayer(input, mime); players.add(player); return player;
        }
    }
    static long[] stats(Player player) throws Exception { return diagnostic(player, "diagnostics"); }
    static long[] runtime(Player player) throws Exception { return diagnostic(player, "runtimeDiagnostics"); }
    private static long[] diagnostic(Player player, String method) throws Exception {
        Field library = AudioPlayer.class.getDeclaredField("library"), handle = AudioPlayer.class.getDeclaredField("handle");
        library.setAccessible(true); handle.setAccessible(true);
        LibEAS backend = (LibEAS) library.get(player);
        return (long[]) LibEAS.class.getMethod(method, long.class).invoke(backend, handle.getLong(player));
    }
}
