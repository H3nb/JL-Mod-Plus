package io.github.h3nb.jlmodplus.mmapi;

import static org.junit.Assert.*;

import android.media.AudioManager;

import org.junit.Test;

import java.util.ArrayDeque;

import javax.microedition.media.MediaException;

public class RuntimeAudioCoordinatorTest {
    @Test
    public void outputGateFencesOldGrantsBeforeQueuedParticipantCallbacks() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, true);
        long[] policy = new long[3];
        audio.attachOutputGate((epoch, minimum, allowed) -> {
            policy[0] = epoch; policy[1] = minimum; policy[2] = allowed ? 1 : 0;
        });
        Player old = new Player(audio);
        old.start();
        long grant = audio.requestEpoch(old, old.token);
        assertEquals(1, policy[2]);
        driver.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(0, policy[2]);
        assertTrue(grant >= policy[1]);
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(1, policy[2]);
        driver.change(AudioManager.AUDIOFOCUS_LOSS);
        assertTrue(grant < policy[1]);
        assertFalse(old.revoked); // The native gate precedes queued Java delivery.
        Player fresh = new Player(audio);
        fresh.start();
        assertTrue(audio.requestEpoch(fresh, fresh.token) >= policy[1]);
        assertEquals(grant, audio.requestEpoch(old, old.token));
        driver.drain();
        assertTrue(audio.isPlaybackAllowed(fresh, fresh.token));
        audio.close();
        assertEquals(0, policy[2]);
    }

    @Test
    public void oneFocusOwnerSuspendsBothBackendsAndGuestStopCancelsResume() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, true);
        Player synth = new Player(audio);
        Player sampled = new Player(audio);
        synth.start();
        sampled.start();
        assertEquals(1, driver.requests);
        driver.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        driver.drain();
        assertTrue(synth.suspended);
        assertTrue(sampled.suspended);
        sampled.stop();
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        driver.drain();
        assertFalse(synth.suspended);
        assertTrue(sampled.suspended);
        assertEquals(1, synth.resumes);
        assertEquals(0, sampled.resumes);
        synth.stop();
        assertEquals(1, driver.abandons);
    }

    @Test
    public void denialDoesNotLeaveAutoplayIntentAndBackgroundNeverRequestsFocus() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, false);
        Player player = new Player(audio);
        assertThrows(MediaException.class, player::start);
        assertEquals(0, driver.requests);
        audio.setForeground(true);
        driver.granted = false;
        assertThrows(MediaException.class, player::start);
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        driver.drain();
        assertEquals(0, player.resumes);
        assertFalse(audio.isPlaybackAllowed(player, player.token));
    }

    @Test
    public void permanentLossRequiresFreshRequestAndRejectsOldGain() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, true);
        Player player = new Player(audio);
        player.start();
        RuntimeAudioCoordinator.FocusListener oldListener = driver.listener;
        long oldEpoch = driver.epoch;
        driver.change(AudioManager.AUDIOFOCUS_LOSS);
        assertFalse(audio.isPlaybackRequested(player, player.token));
        driver.drain();
        assertTrue(player.revoked);
        oldListener.changed(oldEpoch, AudioManager.AUDIOFOCUS_GAIN);
        driver.drain();
        assertTrue(player.suspended);
        player.start();
        oldListener.changed(oldEpoch, AudioManager.AUDIOFOCUS_LOSS);
        driver.drain();
        assertTrue(audio.isPlaybackAllowed(player, player.token));
        assertEquals(2, driver.requests);
    }

    @Test
    public void recreationRetainsIntentButRuntimeCloseIsTerminalForEveryParticipant() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, true);
        Player started = new Player(audio);
        Player idle = new Player(audio);
        started.start();
        audio.setForeground(false);
        driver.drain();
        assertTrue(started.suspended);
        audio.setForeground(true);
        driver.drain();
        assertEquals(1, started.resumes);
        assertEquals(0, idle.resumes);
        audio.close();
        audio.close();
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        driver.drain();
        assertEquals(1, started.closes);
        assertEquals(1, idle.closes);
        assertThrows(MediaException.class, started::start);
    }

    @Test
    public void queuedOldCallbacksCannotSuspendNewPlaybackOrReviveClosedPlayer() throws Exception {
        Driver driver = new Driver();
        RuntimeAudioCoordinator audio = new RuntimeAudioCoordinator(driver, true);
        Player player = new Player(audio);
        player.start();
        driver.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        driver.drain();
        assertFalse(player.suspended);
        driver.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        player.stop();
        player.start();
        driver.drain();
        assertFalse(player.suspended);
        driver.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        driver.change(AudioManager.AUDIOFOCUS_GAIN);
        player.closeForRuntime();
        driver.drain();
        assertEquals(0, player.resumes);
    }

    private static final class Driver implements RuntimeAudioCoordinator.FocusDriver {
        final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        boolean granted = true;
        int requests;
        int abandons;
        long epoch;
        RuntimeAudioCoordinator.FocusListener listener;

        @Override
        public boolean request(long epoch, RuntimeAudioCoordinator.FocusListener listener) {
            this.epoch = epoch;
            this.listener = listener;
            requests++;
            return granted;
        }

        @Override
        public void abandon() { abandons++; }

        @Override
        public void execute(Runnable action) { actions.add(action); }

        void change(int change) { listener.changed(epoch, change); }
        void drain() { while (!actions.isEmpty()) actions.remove().run(); }
    }

    private static final class Player implements RuntimeAudioCoordinator.Participant {
        final RuntimeAudioCoordinator audio;
        long token;
        boolean suspended;
        boolean revoked;
        int resumes;
        int closes;

        Player(RuntimeAudioCoordinator audio) {
            this.audio = audio;
            audio.register(this);
        }

        void start() throws MediaException {
            token = audio.requestPlayback(this);
            suspended = false;
            revoked = false;
        }

        void stop() { token = 0; audio.cancelPlayback(this); }

        @Override
        public void onHostSuspend(long token) {
            if (this.token == token && !audio.isPlaybackAllowed(this, token)) suspended = true;
        }

        @Override
        public void onHostResume(long token) {
            if (this.token == token && suspended && audio.isPlaybackAllowed(this, token)) {
                suspended = false;
                resumes++;
            }
        }

        @Override
        public void onHostFocusRevoked(long token) {
            if (this.token == token) { suspended = true; revoked = true; }
        }

        @Override
        public void closeForRuntime() { token = 0; closes++; audio.unregister(this); }
    }
}
