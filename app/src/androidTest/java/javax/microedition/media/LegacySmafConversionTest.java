// SPDX-License-Identifier: Apache-2.0
package javax.microedition.media;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioQualificationActivity;
import io.github.h3nb.jlmodplus.util.Constants;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns only temporary media; conversion never registers a mixer source. */
@RunWith(AndroidJUnit4.class)
public class LegacySmafConversionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private File asset(String name) throws Exception {
        File file = File.createTempFile("legacy audio 🎵 ", ".mmf", context.getCacheDir());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("audio/" + name); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return file;
    }
    private void converted(String fixture, int channels) throws Exception {
        int handles = LibEAS.liveHandles();
        File input = asset(fixture);
        File output = File.createTempFile("legacy output 🎵 ", ".wav", context.getCacheDir());
        byte[] original = Files.readAllBytes(input.toPath());
        try {
            assertTrue(LibEAS.convertLegacySmaf(input.getPath(), output.getPath()));
            assertArrayEquals(original, Files.readAllBytes(input.toPath()));
            try (RandomAccessFile wave = new RandomAccessFile(output, "r")) {
                assertEquals(0x52494646, wave.readInt());
                assertEquals(output.length() - 8, Integer.toUnsignedLong(Integer.reverseBytes(wave.readInt())));
                assertEquals(0x57415645, wave.readInt());
                wave.seek(20); assertEquals(1, Short.reverseBytes(wave.readShort()));
                assertEquals(channels, Short.reverseBytes(wave.readShort()));
                assertEquals(16000, Integer.reverseBytes(wave.readInt()));
                wave.seek(34); assertEquals(8, Short.reverseBytes(wave.readShort()));
                wave.seek(40); int count = Integer.reverseBytes(wave.readInt());
                assertEquals(16384 * channels, count);
                int peak = 0;
                for (int i = 0; i < count; i++) peak = Math.max(peak, Math.abs(wave.readUnsignedByte() - 128));
                assertTrue("converted waveform is silent", peak > 4);
            }
            assertEquals(handles, LibEAS.liveHandles());
        } finally { input.delete(); output.delete(); }
    }
    @Test public void waveformPreservesMonoStereoAndUtf8PathsWithoutOpeningOutput() throws Exception {
        converted("legacy-yamaha.mmf", 1);
        converted("legacy-yamaha-stereo.mmf", 2);
    }
    @Test public void nonAdpcmAndNonemptyOutputDoNotOverwriteOwnedFiles() throws Exception {
        File input = asset("pcm.wav");
        File output = File.createTempFile("legacy-rejected", ".wav", context.getCacheDir());
        try {
            assertFalse(LibEAS.convertLegacySmaf(input.getPath(), output.getPath()));
            assertEquals(0, output.length());
        } finally { input.delete(); output.delete(); }
        input = asset("legacy-yamaha.mmf");
        output = File.createTempFile("legacy-nonempty", ".wav", context.getCacheDir());
        byte[] sentinel = {11, 22, 33};
        try {
            Files.write(output.toPath(), sentinel);
            try { LibEAS.convertLegacySmaf(input.getPath(), output.getPath()); fail("nonempty output accepted"); }
            catch (MediaException expected) { }
            assertArrayEquals(sentinel, Files.readAllBytes(output.toPath()));
        } finally { input.delete(); output.delete(); }
    }
    @Test public void sourcePublishesCompletedWaveAndRemovesFailedTemporaryOutput() throws Exception {
        Assume.assumeTrue("Only an isolated qualification installation may change workdir",
                context.getPackageName().startsWith("io.github.h3nb.jlmodplus.audioqualification."));
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean hadRoot = preferences.contains(Constants.PREF_EMULATOR_DIR);
        String previous = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
        File root = new File(context.getCacheDir(), "legacy-smaf-" + System.nanoTime());
        Config.getEmulatorDir(); // Register the preference listener before the temporary change.
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.getPath()).commit()));
            File cache = new File(root, "cache");
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("audio/legacy-yamaha.mmf")) {
                InternalDataSource source = new InternalDataSource(input, "audio/mmf");
                File original = new File(source.getLocator());
                try {
                    source.prepareLegacySmaf();
                    assertFalse(original.exists());
                    try (RandomAccessFile output = new RandomAccessFile(source.getLocator(), "r")) {
                        assertEquals(0x52494646, output.readInt());
                    }
                } finally { source.disconnect(); }
            }
            byte[] corrupt = {'M', 'M', 'M', 'D', 0, 0, 0, 0};
            InternalDataSource source = new InternalDataSource(new java.io.ByteArrayInputStream(corrupt), "audio/mmf");
            String original = source.getLocator();
            try {
                source.prepareLegacySmaf();
                assertEquals(original, source.getLocator());
                assertArrayEquals(corrupt, Files.readAllBytes(new File(original).toPath()));
                assertEquals("failed conversion leaked a temp file", 1, cache.list().length);
            } finally { source.disconnect(); }
            assertEquals(0, cache.list().length);
            try (ActivityScenario<AudioQualificationActivity> host = ActivityScenario.launch(AudioQualificationActivity.class);
                 InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                         .getAssets().open("audio/legacy-yamaha.mmf")) {
                Player player = Manager.createPlayer(input, "audio/mmf");
                assertTrue("legacy waveform changed playback backend", player instanceof MicroPlayer);
                File wave = new File(((MicroPlayer) player).source.getLocator());
                CountDownLatch ended = new CountDownLatch(1);
                player.addPlayerListener((p, event, value) -> {
                    if (PlayerListener.END_OF_MEDIA.equals(event)) ended.countDown();
                });
                try {
                    player.prefetch();
                    // The retained AndroidPlayer loads its platform media synchronously on start.
                    player.start();
                    long duration = player.getDuration();
                    assertTrue("legacy waveform duration after loading: " + duration,
                            duration >= 1000000 && duration <= 1050000);
                    assertTrue("legacy waveform did not finish", ended.await(5, TimeUnit.SECONDS));
                } finally { player.close(); }
                assertFalse("closed legacy Player retained its cache", wave.exists());
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                SharedPreferences.Editor restore = preferences.edit();
                if (hadRoot) restore.putString(Constants.PREF_EMULATOR_DIR, previous);
                else restore.remove(Constants.PREF_EMULATOR_DIR);
                assertTrue(restore.commit());
            });
            File cache = new File(root, "cache");
            File[] files = cache.listFiles();
            if (files != null) for (File file : files) file.delete();
            cache.delete(); root.delete();
        }
    }
    @Test public void oversizedInputFailsBeforeWritingOutput() throws Exception {
        File input = File.createTempFile("legacy-oversized", ".mmf", context.getCacheDir());
        File output = File.createTempFile("legacy-limit-output", ".wav", context.getCacheDir());
        try {
            try (RandomAccessFile file = new RandomAccessFile(input, "rw")) {
                file.writeBytes("MMMD"); file.setLength(64L * 1024 * 1024 + 1);
            }
            try { LibEAS.convertLegacySmaf(input.getPath(), output.getPath()); fail("oversized input accepted"); }
            catch (MediaException expected) { }
            assertEquals(0, output.length());
        } finally { input.delete(); output.delete(); }
    }
}
