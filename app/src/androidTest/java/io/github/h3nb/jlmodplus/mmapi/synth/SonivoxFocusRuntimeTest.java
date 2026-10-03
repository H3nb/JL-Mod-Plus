// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/** Optional distinct-UID physical focus qualification. No device policy is changed by this test. */
@RunWith(AndroidJUnit4.class)
public class SonivoxFocusRuntimeTest {
    private static final ComponentName RIVAL = new ComponentName(
            "io.github.h3nb.sonivox.productionfocusrival",
            "io.github.h3nb.sonivox.productionfocusrival.FocusRivalService");
    private static final String RESULT = "io.github.h3nb.jlmodplus.audioqualification.FOCUS_RESULT";
    private ActivityScenario<AudioQualificationActivity> host;
    private Context context;
    private final List<SynthPlayer> players = new ArrayList<>();

    @Before public void setup() {
        Assume.assumeTrue("Requires external production-focus-rival companion and controlled OEM policy",
                "true".equals(InstrumentationRegistry.getArguments().getString("sonivoxFocusRival")));
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 26);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        host = ActivityScenario.launch(AudioQualificationActivity.class);
    }

    @After public void cleanup() {
        if (context != null) context.stopService(new Intent().setComponent(RIVAL));
        for (SynthPlayer player : players) player.close();
        if (host != null) host.close();
    }

    @Test public void transientLossFreezesBothAndOnlyStillRequestedPlayerResumes() throws Exception {
        SynthPlayer first = player();
        SynthPlayer stopped = player();
        first.start();
        stopped.start();
        await(() -> first.getMediaTime() > 150000 && stats(first)[2] > 0, 3000, "Initial audible MIDI");
        await(() -> stopped.getMediaTime() > 150000 && stats(stopped)[2] > 0, 3000, "Peer audible MIDI");
        long token = token(first);
        RuntimeAudioCoordinator audio = RuntimeAudioCoordinator.current();
        try (Rival rival = new Rival(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)) {
            rival.awaitGrant();
            await(() -> !audio.isPlaybackAllowed(first, token) && suspended(first) && suspended(stopped),
                    2000, "Actual transient focus loss did not freeze both players");
            assertEquals("Rival paused the Activity instead of competing for focus", Lifecycle.State.RESUMED,
                    host.getState());
            assertTrue("Transient loss cleared guest intent", audio.isPlaybackRequested(first, token));
            assertEquals(Player.STARTED, first.getState());
            assertEquals(Player.STARTED, stopped.getState());
            long paused = first.getMediaTime();
            long peerPaused = stopped.getMediaTime();
            long pcm = stats(first)[2];
            SystemClock.sleep(150);
            assertEquals(paused, first.getMediaTime());
            assertEquals(peerPaused, stopped.getMediaTime());
            stopped.stop();
            rival.release();
            await(() -> audio.isPlaybackAllowed(first, token) && !suspended(first)
                            && first.getMediaTime() > paused + 50000 && stats(first)[2] > pcm,
                    3000, "Actual GAIN did not restore held-voice output");
            assertEquals(Player.PREFETCHED, stopped.getState());
            assertEquals(peerPaused, stopped.getMediaTime());
            evidence("transient", rival.uid.get(), paused, first.getMediaTime(), stats(first)[2] - pcm);
        }
    }

    @Test public void permanentLossRequiresFreshGuestRequestWithoutDuplicateStarted() throws Exception {
        SynthPlayer player = player();
        AtomicInteger starts = new AtomicInteger();
        player.addPlayerListener((p, event, data) -> {
            if (PlayerListener.STARTED.equals(event)) starts.incrementAndGet();
        });
        player.start();
        await(() -> player.getMediaTime() > 150000 && stats(player)[2] > 0 && starts.get() == 1,
                3000, "Initial audible MIDI");
        long token = token(player);
        RuntimeAudioCoordinator audio = RuntimeAudioCoordinator.current();
        try (Rival rival = new Rival(AudioManager.AUDIOFOCUS_GAIN)) {
            rival.awaitGrant();
            await(() -> !audio.isPlaybackRequested(player, token) && suspended(player), 2000,
                    "Actual permanent focus loss did not revoke intent");
            assertEquals(Lifecycle.State.RESUMED, host.getState());
            assertEquals(Player.STARTED, player.getState());
            long paused = player.getMediaTime();
            long pcm = stats(player)[2];
            rival.release();
            SystemClock.sleep(200);
            assertEquals("Permanent loss auto-resumed", paused, player.getMediaTime());
            assertEquals(pcm, stats(player)[2]);
            player.start();
            await(() -> player.getMediaTime() > paused + 50000 && stats(player)[2] > pcm,
                    3000, "Fresh guest request did not restore output");
            assertEquals(1, starts.get());
            evidence("permanent", rival.uid.get(), paused, player.getMediaTime(), stats(player)[2] - pcm);
        }
    }

    private SynthPlayer player() throws Exception {
        // Thirty seconds of held middle C, with wholly generated SMF content.
        byte[] midi = new byte[]{'M','T','h','d',0,0,0,6,0,0,0,1,0,96,
                'M','T','r','k',0,0,0,13,0,(byte) 0x90,60,100,
                (byte) 0xad,0,(byte) 0x80,60,0,0,(byte) 0xff,0x2f,0};
        SynthPlayer player = (SynthPlayer) Manager.createPlayer(new ByteArrayInputStream(midi), "audio/midi");
        players.add(player);
        return player;
    }

    private static long token(SynthPlayer player) throws Exception {
        synchronized (player) { return field("playbackToken").getLong(player); }
    }

    private static boolean suspended(SynthPlayer player) throws Exception {
        synchronized (player) { return field("hostSuspended").getBoolean(player); }
    }

    private static long[] stats(SynthPlayer player) throws Exception {
        synchronized (player) {
            LibEAS library = (LibEAS) field("library").get(player);
            return library.diagnostics(field("handle").getLong(player));
        }
    }

    private static Field field(String name) throws Exception {
        Field field = SynthPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void evidence(String mode, int rivalUid, long paused, long resumed, long pcm) {
        Bundle status = new Bundle();
        status.putString("stream", "Production focus " + mode + ": ownerUid=" + Process.myUid()
                + ", rivalUid=" + rivalUid + ", frozenMicros=" + paused + ", resumedMicros=" + resumed
                + ", freshNonzeroSamples=" + pcm + ", hostState=RESUMED\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private final class Rival implements AutoCloseable {
        final String token = "production:" + SystemClock.elapsedRealtimeNanos();
        final AtomicInteger grant = new AtomicInteger(-99);
        final AtomicInteger uid = new AtomicInteger(-1);
        final AtomicInteger abandons = new AtomicInteger();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent result) {
                if (!token.equals(result.getStringExtra("token"))) return;
                uid.set(result.getIntExtra("uid", -1));
                if ("request".equals(result.getStringExtra("stage"))) grant.set(result.getIntExtra("value", -99));
                if ("abandon".equals(result.getStringExtra("stage"))) abandons.incrementAndGet();
            }
        };

        Rival(int gain) {
            ContextCompat.registerReceiver(context, receiver, new IntentFilter(RESULT),
                    ContextCompat.RECEIVER_EXPORTED);
            try {
                ComponentName launched = context.startForegroundService(new Intent().setComponent(RIVAL)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra("resultPackage", context.getPackageName()).putExtra("token", token)
                        .putExtra("gain", gain).putExtra("holdMillis", 10000L));
                assertNotNull("Focus qualification companion is not installed", launched);
            } catch (RuntimeException | AssertionError failure) {
                context.unregisterReceiver(receiver);
                throw failure;
            }
        }

        void awaitGrant() throws Exception {
            await(() -> grant.get() != -99, 3000, "Distinct-UID focus grant broadcast");
            assertEquals("Companion focus was denied", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, grant.get());
            assertTrue("Companion did not run with a distinct UID", uid.get() >= 0 && uid.get() != Process.myUid());
        }

        void release() throws Exception {
            context.startService(new Intent("release").setComponent(RIVAL).putExtra("token", token));
            await(() -> abandons.get() > 0, 3000, "Companion focus release");
        }

        @Override public void close() {
            try { context.stopService(new Intent().setComponent(RIVAL)); }
            finally { context.unregisterReceiver(receiver); }
        }
    }
}
