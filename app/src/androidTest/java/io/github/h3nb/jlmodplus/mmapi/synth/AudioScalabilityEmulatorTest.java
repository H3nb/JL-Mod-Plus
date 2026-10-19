// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Debug;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;

import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/**
 * Exercises logical-player scaling and concurrent lifecycle ownership on an
 * actual Android emulator. Heap values are evidence, not brittle CI thresholds.
 */
@RunWith(AndroidJUnit4.class)
public class AudioScalabilityEmulatorTest {
    private static final String TAG = "AudioScaleCI";
    private ActivityScenario<AudioQualificationActivity> host;
    private int baseline;

    @Before public void setup() {
        host = ActivityScenario.launch(AudioQualificationActivity.class);
        baseline = LibEAS.liveHandles();
    }

    @After public void teardown() {
        host.close();
        assertEquals("native Player handles leaked", baseline, LibEAS.liveHandles());
    }

    @Test public void hundredsOfPlayersCrossMultipleMixerBlocksWithoutEagerMediaContexts() throws Exception {
        List<Player> players = new ArrayList<>();
        long before = Debug.getNativeHeapAllocatedSize();
        try {
            for (int i = 0; i < 256; ++i)
                players.add(Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR));
            long created = Debug.getNativeHeapAllocatedSize();
            assertEquals(baseline + 256, LibEAS.liveHandles());

            for (int i = 0; i < 72; ++i)
                players.get(i).prefetch();
            long prepared = Debug.getNativeHeapAllocatedSize();

            for (int index : new int[]{0, 31, 32, 64})
                players.get(index).start();
            for (int index : new int[]{0, 31, 32, 64})
                assertEquals(Player.STARTED, players.get(index).getState());

            Log.i(TAG, "Players=256 prepared=72 playing=4, nativeBytes"
                    + " baseline=" + before + " created=" + created
                    + " prepared=" + prepared
                    + " active=" + Debug.getNativeHeapAllocatedSize());
        } finally {
            for (Player player : players) player.close();
        }
        assertEquals(baseline, LibEAS.liveHandles());
    }

    @Test public void concurrentCreationPrefetchAndCloseReturnToHandleBaseline() throws Exception {
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch begin = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; ++worker) {
                futures.add(pool.submit(() -> {
                    begin.await();
                    for (int i = 0; i < 24; ++i) {
                        Player player = Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR);
                        try {
                            if (i % 3 == 0) player.prefetch();
                        } finally {
                            player.close();
                        }
                    }
                    return null;
                }));
            }
            begin.countDown();
            for (Future<?> future : futures)
                future.get(90, TimeUnit.SECONDS);
        } finally {
            begin.countDown();
            pool.shutdownNow();
            assertTrue("audio lifecycle workers failed to quiesce",
                    pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(baseline, LibEAS.liveHandles());
        Log.i(TAG, "Concurrent create/prefetch/close completed: 4 workers, 96 Players");
    }
}
