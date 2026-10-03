// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.expectThrows;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.h3nb.jlmodplus.mmapi.video.VideoDisplay;
import io.github.h3nb.jlmodplus.mmapi.video.VideoLibrary;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.lcdui.Item;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

@RunWith(AndroidJUnit4.class)
public class VideoPlayerTest {
    @Test
    public void commonPlayerLoopsAndSeeksVideoBesideSampledPeerOnOneOutput() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player video = asset("video/markers.mp4"), peer = asset("audio/effect.mp3");
            try {
                video.realize();
                VideoDisplay control = (VideoDisplay) video.getControl("VideoControl");
                assertSame(control, video.getControl("GUIControl"));
                expectThrows(IllegalStateException.class, control::getDisplayWidth);
                expectThrows(
                        IllegalArgumentException.class, () -> control.initDisplayMode(1, null));
                assertTrue(control.initDisplayMode(0, null) instanceof Item);
                expectThrows(IllegalStateException.class, () -> control.initDisplayMode(0, null));
                host.onActivity(a -> a.setContentView(control.itemView(a, 2)));
                CountDownLatch ended = new CountDownLatch(2);
                video.addPlayerListener(
                        (p, e, v) -> {
                            if (PlayerListener.END_OF_MEDIA.equals(e)) ended.countDown();
                        });
                peer.setLoopCount(-1);
                peer.start();
                video.setLoopCount(2);
                video.start();
                VideoLibrary backend = backend(video);
                await(() -> backend.diagnostics()[0] > 5, 5000, "No video frames rendered");
                assertEquals(1, backend.runtimeDiagnostics()[2]);
                final long peerFramesBeforeSeek = UnifiedAudioRuntimeTest.stats(peer)[0];
                long actual = video.setMediaTime(1500000);
                assertEquals(1500000, actual);
                await(
                        () -> UnifiedAudioRuntimeTest.stats(peer)[0] > peerFramesBeforeSeek + 1024,
                        3000,
                        "Seek halted peer");
                assertTrue("Video iterations did not finish", ended.await(12, TimeUnit.SECONDS));
                assertEquals(Player.PREFETCHED, video.getState());
                long[] stats = backend.diagnostics();
                Log.i("VideoQualification", "markers " + java.util.Arrays.toString(stats));
                assertTrue(
                        "Frame/sink tolerance " + java.util.Arrays.toString(stats),
                        stats[2] <= frameTolerance(backend, stats));
                video.deallocate();
                assertTrue(video.getDuration() > 3900000);
                video.close();
                final long before = UnifiedAudioRuntimeTest.stats(peer)[0];
                await(
                        () -> UnifiedAudioRuntimeTest.stats(peer)[0] > before + 1024,
                        3000,
                        "Video close halted peer");
            } finally {
                video.close();
                peer.close();
            }
        }
    }

    @Test
    public void pureVideoPausesAndSeeksWithoutOpeningAnyAudioOutput() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player video = asset("video/pure.mp4");
            try {
                video.start();
                SystemClock.sleep(200);
                video.stop();
                long time = video.getMediaTime();
                SystemClock.sleep(150);
                assertEquals(time, video.getMediaTime());
                assertEquals(0, backend(video).runtimeDiagnostics()[2]);
                assertEquals(2500000, video.setMediaTime(2500000));
                CountDownLatch ended = new CountDownLatch(1);
                video.addPlayerListener(
                        (p, e, v) -> {
                            if (PlayerListener.END_OF_MEDIA.equals(e)) ended.countDown();
                        });
                video.start();
                assertTrue(ended.await(4, TimeUnit.SECONDS));
            } finally {
                video.close();
            }
        }
    }

    @Test
    public void trackOffsetsAndUnequalLengthsDrainTogetherAndAudioOnlyMp4StaysAudio()
            throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            for (String name : new String[] {"offset.mp4", "short-video.mp4"}) {
                Player video = asset("video/" + name);
                try {
                    CountDownLatch ended = new CountDownLatch(1);
                    video.addPlayerListener(
                            (p, e, v) -> {
                                if (PlayerListener.END_OF_MEDIA.equals(e)) ended.countDown();
                            });
                    video.start();
                    boolean done = ended.await(7, TimeUnit.SECONDS);
                    assertTrue(name + " " + snapshot(video), done);
                    assertEquals(Player.PREFETCHED, video.getState());
                    assertTrue(
                            name + " ended at " + video.getMediaTime(),
                            video.getMediaTime() >= 3900000);
                    if ("offset.mp4".equals(name)) {
                        // Seeking/stopping in the silent video tail must not restart
                        // the shorter soundtrack or keep it consuming the audio bus.
                        video.setMediaTime(3000000);
                        long frames = audioFrames(video);
                        video.start();
                        await(() -> video.getMediaTime() > 3150000, 3000, "Tail clock froze");
                        video.stop();
                        long paused = video.getMediaTime();
                        video.start();
                        await(
                                () -> video.getMediaTime() > paused + 100000,
                                3000,
                                "Tail resume froze");
                        assertEquals("Completed soundtrack restarted", frames, audioFrames(video));
                    }
                } finally {
                    video.close();
                }
            }
            Player audio = asset("video/audio-only.mp4");
            try {
                audio.realize();
                assertNull(audio.getControl("VideoControl"));
                assertEquals("audio/mp4", audio.getContentType());
            } finally {
                audio.close();
            }
        }
    }

    @Test
    public void deviceCodecCapabilitiesRenderGeneratedH263AndAvc() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            for (String name : new String[] {"legacy-level.mp4", "h263.3gp", "avc.mp4"}) {
                Player player;
                try {
                    player = asset("video/" + name);
                } catch (MediaException e) {
                    if (!e.getMessage().contains("no compatible video decoder")) throw e;
                    Log.i(
                            "VideoQualification",
                            name + " capability unavailable: " + e.getMessage());
                    continue;
                }
                try {
                    renderTail(host, player, name);
                } finally {
                    player.close();
                }
            }
        }
    }

    @Test
    public void unknownVideoDurationDoesNotBecomeShorterSoundtrackDurationOrClampSeek()
            throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player video = asset("video/offset.mp4");
            try {
                VideoLibrary library = backend(video);
                // Metadata absence is independent of this owned fixture's packets.
                // Remove it before codec/guest observation to exercise unknown end.
                Field sf = VideoLibrary.class.getDeclaredField("source");
                sf.setAccessible(true);
                Object source = sf.get(library);
                Field ff = source.getClass().getDeclaredField("format");
                ff.setAccessible(true);
                ((android.media.MediaFormat) ff.get(source))
                        .removeKey(android.media.MediaFormat.KEY_DURATION);
                Field lf = VideoLibrary.class.getDeclaredField("length");
                lf.setAccessible(true);
                lf.setLong(library, Player.TIME_UNKNOWN);
                CountDownLatch ended = new CountDownLatch(1), duration = new CountDownLatch(1);
                video.addPlayerListener(
                        (p, e, value) -> {
                            if (PlayerListener.DURATION_UPDATED.equals(e)
                                    && value instanceof Long
                                    && (Long) value >= 3900000) duration.countDown();
                            if (PlayerListener.END_OF_MEDIA.equals(e)) ended.countDown();
                        });
                video.start();
                await(
                        () -> video.getMediaTime() > 2800000,
                        5000,
                        "Short soundtrack did not finish");
                assertEquals(Player.TIME_UNKNOWN, video.getDuration());
                assertEquals(3000000, video.setMediaTime(3000000));
                assertTrue("Unknown video end missing", ended.await(3, TimeUnit.SECONDS));
                assertTrue("Known decoded duration missing", duration.await(1, TimeUnit.SECONDS));
                assertTrue(video.getDuration() >= 3900000);
                long known = video.getDuration();
                video.start();
                await(
                        () -> video.getMediaTime() > 2800000,
                        5000,
                        "Replay soundtrack did not finish");
                assertEquals(known, video.getDuration());
                assertEquals(
                        "Replay lost its known seek bound", known, video.setMediaTime(9000000));
            } finally {
                video.close();
            }
        }
    }

    @Test
    public void selectedExternalJ2meCorpusRendersThroughTheSharedOutput() throws Exception {
        org.junit.Assume.assumeTrue(
                "External commercial corpus is opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("videoCorpus")));
        File root =
                new File(
                        InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()
                                .getFilesDir(),
                        "video-corpus");
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player peer = asset("audio/effect.mp3");
            try {
                peer.setLoopCount(-1);
                peer.start();
                for (String name : new String[] {"0.3gp", "4.3gp", "6.3gp"}) {
                    android.media.MediaExtractor probe = new android.media.MediaExtractor();
                    try {
                        probe.setDataSource(new File(root, name).getAbsolutePath());
                        for (int t = 0; t < probe.getTrackCount(); t++) {
                            android.media.MediaFormat f = probe.getTrackFormat(t);
                            String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                            if (mime == null || !mime.startsWith("video/")) continue;
                            Log.i("VideoQualification", name + " format=" + f);
                            for (android.media.MediaCodecInfo c :
                                    new android.media.MediaCodecList(
                                                    android.media.MediaCodecList.REGULAR_CODECS)
                                            .getCodecInfos()) {
                                if (c.isEncoder()
                                        || !java.util.Arrays.asList(c.getSupportedTypes())
                                                .contains(mime)) continue;
                                android.media.MediaCodecInfo.CodecCapabilities caps =
                                        c.getCapabilitiesForType(mime);
                                Log.i(
                                        "VideoQualification",
                                        c.getName()
                                                + " formatSupported="
                                                + caps.isFormatSupported(f)
                                                + " sizes="
                                                + caps.getVideoCapabilities().getSupportedWidths()
                                                + "x"
                                                + caps.getVideoCapabilities().getSupportedHeights()
                                                + " rates="
                                                + caps.getVideoCapabilities()
                                                        .getSupportedFrameRates());
                            }
                        }
                    } finally {
                        probe.release();
                    }
                    Player player;
                    try (InputStream in = new FileInputStream(new File(root, name))) {
                        try {
                            player = Manager.createPlayer(in, null);
                        } catch (MediaException e) {
                            if (!e.getMessage().contains("no compatible video decoder")) throw e;
                            Log.i(
                                    "VideoQualification",
                                    name + " explicitly unsupported: " + e.getMessage());
                            continue;
                        }
                    }
                    try {
                        renderTail(host, player, name);
                        assertEquals(1, backend(player).runtimeDiagnostics()[2]);
                    } finally {
                        player.close();
                    }
                }
            } finally {
                peer.close();
            }
        }
    }

    @Test
    public void decoderSurfaceFailureClosesOnlyItsPlayer() throws Exception {
        try (ActivityScenario<AudioQualificationActivity> host =
                ActivityScenario.launch(AudioQualificationActivity.class)) {
            Player video = asset("video/markers.mp4"), peer = asset("audio/effect.mp3");
            try {
                video.realize();
                VideoDisplay control = (VideoDisplay) video.getControl("VideoControl");
                control.initDisplayMode(0, null);
                host.onActivity(a -> a.setContentView(control.itemView(a, 2)));
                CountDownLatch error = new CountDownLatch(1), closed = new CountDownLatch(1);
                video.addPlayerListener(
                        (p, e, v) -> {
                            if (PlayerListener.ERROR.equals(e)) error.countDown();
                            if (PlayerListener.CLOSED.equals(e)) closed.countDown();
                        });
                video.setLoopCount(-1);
                peer.setLoopCount(-1);
                peer.start();
                video.start();
                VideoLibrary backend = backend(video);
                await(() -> backend.diagnostics()[0] > 3, 5000, "No frame before source failure");
                android.graphics.SurfaceTexture texture =
                        new android.graphics.SurfaceTexture(false);
                android.view.Surface invalid = new android.view.Surface(texture);
                invalid.release();
                texture.release();
                backend.changeSurface(invalid, null, Long.MAX_VALUE);
                assertTrue("Codec error missing", error.await(6, TimeUnit.SECONDS));
                assertTrue("Source did not close", closed.await(3, TimeUnit.SECONDS));
                assertEquals(Player.CLOSED, video.getState());
                long before = UnifiedAudioRuntimeTest.stats(peer)[0];
                await(
                        () -> UnifiedAudioRuntimeTest.stats(peer)[0] > before + 1024,
                        3000,
                        "Video decoder failure stopped peer");
            } finally {
                video.close();
                peer.close();
            }
        }
    }

    private static void renderTail(
            ActivityScenario<AudioQualificationActivity> host, Player player, String name)
            throws Exception {
        player.realize();
        VideoDisplay control = (VideoDisplay) player.getControl("VideoControl");
        assertNotNull(name, control);
        assertTrue(control.getSourceWidth() > 0);
        control.initDisplayMode(0, null);
        host.onActivity(a -> a.setContentView(control.itemView(a, 2)));
        CountDownLatch ended = new CountDownLatch(1);
        player.addPlayerListener(
                (p, e, v) -> {
                    if (PlayerListener.END_OF_MEDIA.equals(e)) ended.countDown();
                });
        player.start();
        await(() -> backend(player).diagnostics()[0] > 3, 5000, name + " did not render");
        player.setMediaTime(Math.max(0, player.getDuration() - 1500000));
        assertTrue(name + " " + snapshot(player), ended.await(5, TimeUnit.SECONDS));
        long[] stats = backend(player).diagnostics();
        Log.i("VideoQualification", name + " " + java.util.Arrays.toString(stats));
        assertTrue(
                "Frame/sink uncertainty tolerance " + name + " " + java.util.Arrays.toString(stats),
                stats[2] <= frameTolerance(backend(player), stats));
    }

    private static long frameTolerance(VideoLibrary library, long[] stats) throws Exception {
        Field sf = VideoLibrary.class.getDeclaredField("source");
        sf.setAccessible(true);
        Object source = sf.get(library);
        Field ff = source.getClass().getDeclaredField("frameDuration");
        ff.setAccessible(true);
        return ff.getLong(source) + stats[6];
    }

    private static Player asset(String path) throws Exception {
        try (InputStream input =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(path)) {
            return Manager.createPlayer(input, null);
        }
    }

    static VideoLibrary backend(Player player) throws Exception {
        Field field = AudioPlayer.class.getDeclaredField("library");
        field.setAccessible(true);
        return (VideoLibrary) field.get(player);
    }

    private static long audioFrames(Player player) throws Exception {
        VideoLibrary video = backend(player);
        Field af = VideoLibrary.class.getDeclaredField("audio");
        Field hf = VideoLibrary.class.getDeclaredField("audioHandle");
        af.setAccessible(true);
        hf.setAccessible(true);
        return ((io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS) af.get(video))
                .diagnostics(hf.getLong(video))[0];
    }

    private static String snapshot(Player player) throws Exception {
        VideoLibrary library = backend(player);
        StringBuilder result =
                new StringBuilder("state=" + player.getState() + " time=" + player.getMediaTime());
        for (String name :
                new String[] {
                    "audioEnd",
                    "videoEnd",
                    "playing",
                    "suspended",
                    "videoEndTime",
                    "lastScheduled",
                    "pending",
                    "nativeGeneration"
                }) {
            Field field = VideoLibrary.class.getDeclaredField(name);
            field.setAccessible(true);
            result.append(" ").append(name).append("=").append(field.get(library));
        }
        Field af = VideoLibrary.class.getDeclaredField("audio"),
                hf = VideoLibrary.class.getDeclaredField("audioHandle");
        af.setAccessible(true);
        hf.setAccessible(true);
        io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS audio =
                (io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS) af.get(library);
        if (audio != null)
            result.append(" stamp=")
                    .append(java.util.Arrays.toString(audio.presentation(hf.getLong(library))))
                    .append(" decoder=")
                    .append(
                            java.util.Arrays.toString(
                                    audio.decoderDiagnostics(hf.getLong(library))));
        return result.toString();
    }
}
