// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static org.junit.Assert.*;

import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.MIDIControl;

import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

/** Real bounded MIDI queue and foreground-policy integration, beyond fake-backend delivery. */
@RunWith(AndroidJUnit4.class)
public class MidiDeliveryRuntimeTest {
    private ActivityScenario<AudioQualificationActivity> host;
    private final List<AudioPlayer> players = new ArrayList<>();
    private int initialHandles;

    @Before public void setup() {
        host = ActivityScenario.launch(AudioQualificationActivity.class);
        initialHandles = LibEAS.liveHandles();
    }

    @After public void cleanup() throws Exception {
        for (AudioPlayer player : players) player.close();
        if (host != null) host.close();
        await(() -> LibEAS.liveHandles() == initialHandles, 2500, "MIDI context leaked after close");
    }

    @Test public void fullSuspendedQueueRejectsDeliveryWithoutGuestExceptionOrTerminalEvents() throws Exception {
        AudioPlayer player = player();
        List<String> events = new CopyOnWriteArrayList<>();
        player.addPlayerListener((p, event, data) -> events.add(event));
        MIDIControl midi = (MIDIControl) player.getControl("MIDIControl");
        midi.shortMidiEvent(0x90, 60, 100);
        await(() -> stats(player)[2] > 0, 2000, "Initial interactive MIDI produced no PCM");
        host.moveToState(Lifecycle.State.STARTED);
        await(() -> flag(player, "hostSuspended"), 2000, "Paused host did not suspend native output");
        long frames = stats(player)[0];
        byte[] capacity = new byte[16384];
        Arrays.fill(capacity, (byte) 0xf8);
        assertEquals(capacity.length, midi.longMidiEvent(capacity, 0, capacity.length));
        midi.shortMidiEvent(0x80, 60, 0); // Full queue: JSR135 requires a silent delivery failure.
        assertEquals(-1, midi.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        SystemClock.sleep(80); // Include several independent management polls.
        assertEquals(Player.PREFETCHED, player.getState());
        assertEquals("Suspended queue started output", frames, stats(player)[0]);
        assertNoTerminal(events);
        host.moveToState(Lifecycle.State.RESUMED);
        await(() -> stats(player)[0] > frames && midi.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1) == 1,
                3000, "Healthy Player did not resume queue consumption");
        midi.shortMidiEvent(0x80, 60, 0);
        assertEquals(Player.PREFETCHED, player.getState());
        assertNoTerminal(events);
    }

    @Test public void deniedForegroundDeliveryDoesNotLeaveAutoplayIntent() throws Exception {
        AudioPlayer player = player();
        List<String> events = new CopyOnWriteArrayList<>();
        player.addPlayerListener((p, event, data) -> events.add(event));
        MIDIControl midi = (MIDIControl) player.getControl("MIDIControl");
        host.moveToState(Lifecycle.State.STARTED);
        assertEquals(0, midi.longMidiEvent(new byte[0], 0, 0));
        midi.shortMidiEvent(0x90, 60, 100);
        assertEquals(-1, midi.longMidiEvent(new byte[]{(byte) 0x90, 60, 100}, 0, 3));
        assertFalse("Denied delivery left playback intent", flag(player, "requestedPlayback"));
        assertEquals(Player.PREFETCHED, player.getState());
        assertEquals(0, stats(player)[0]);
        host.moveToState(Lifecycle.State.RESUMED);
        SystemClock.sleep(120);
        assertEquals("Foreground return auto-played a denied event", 0, stats(player)[0]);
        assertNoTerminal(events);
        assertFalse(events.contains(PlayerListener.STARTED));
        midi.shortMidiEvent(0x90, 60, 100);
        await(() -> stats(player)[2] > 0, 2000, "Fresh foreground request did not produce PCM");
        assertEquals(Player.PREFETCHED, player.getState());
    }

    private AudioPlayer player() throws Exception {
        AudioPlayer player = (AudioPlayer) Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR);
        players.add(player);
        player.prefetch();
        return player;
    }

    private static void assertNoTerminal(List<String> events) {
        assertFalse(events.contains(PlayerListener.ERROR));
        assertFalse(events.contains(PlayerListener.CLOSED));
    }

    private static boolean flag(AudioPlayer player, String name) throws Exception {
        synchronized (player) { return field(name).getBoolean(player); }
    }

    private static long[] stats(AudioPlayer player) throws Exception {
        synchronized (player) {
            LibEAS library = (LibEAS) field("library").get(player);
            return library.diagnostics(field("handle").getLong(player));
        }
    }

    private static Field field(String name) throws Exception {
        Field field = AudioPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
