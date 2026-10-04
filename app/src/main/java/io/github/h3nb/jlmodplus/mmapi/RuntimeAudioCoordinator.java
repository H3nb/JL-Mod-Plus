/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.mmapi;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.media.MediaException;
import javax.microedition.util.ContextHolder;

/** One audio-focus owner for a runtime, independent of its current Activity. */
public final class RuntimeAudioCoordinator implements AutoCloseable {
    public interface Participant {
        void onHostSuspend(long token);
        void onHostResume(long token);
        void onHostFocusRevoked(long token);
        void closeForRuntime();
        default void onSharedOutputFailure(long group, String message) {}
        default boolean requiresAudioFocus() { return true; }
    }

    interface FocusListener {
        void changed(long epoch, int change);
    }

    interface FocusDriver {
        boolean request(long epoch, FocusListener listener);
        void abandon();
        /** Must enqueue, never invoke inline: participants may already hold their own locks. */
        void execute(Runnable action);
    }

    public interface OutputGate {
        void update(long epoch, long minimumRequestEpoch, boolean allowed);
        default void close() {}
    }
    private static final AtomicLong nextSession = new AtomicLong();
    private final long sessionId = nextSession.incrementAndGet();
    private OutputGate outputGate;
    private long policyEpoch, minimumRequestEpoch, failedGroup;
    private static RuntimeAudioCoordinator active;
    private static boolean hostForeground;
    private final FocusDriver driver;
    private final ScheduledThreadPoolExecutor management;
    private final Map<Participant, Entry> participants = new IdentityHashMap<>();
    private boolean foreground;
    private boolean focusHeld;
    private boolean focusRequested;
    private volatile boolean closed;
    private long nextToken;
    private long focusEpoch;

    private static final class Entry {
        long token, requestEpoch;
        boolean requested;
        final boolean requiresFocus;
        Entry(boolean requiresFocus) { this.requiresFocus = requiresFocus; }
    }

    RuntimeAudioCoordinator(FocusDriver driver, boolean foreground) {
        this.driver = driver;
        this.foreground = foreground;
        management = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "RuntimeAudioManagement");
            thread.setDaemon(true);
            return thread;
        });
        management.setRemoveOnCancelPolicy(true);
        management.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** Also supports MMAPI use outside a launched MIDlet (e.g. local qualification). */
    public static synchronized RuntimeAudioCoordinator current() {
        if (active == null) {
            active = new RuntimeAudioCoordinator(new AndroidFocusDriver(), hostForeground);
        }
        return active;
    }

    /** Only the runtime launch owner may establish a new session after terminal cleanup. */
    public static synchronized RuntimeAudioCoordinator beginRuntime() {
        if (active == null || active.closed) {
            active = new RuntimeAudioCoordinator(new AndroidFocusDriver(), hostForeground);
        }
        return active;
    }

    public static void onHostForegroundChanged(boolean foreground) {
        RuntimeAudioCoordinator session;
        synchronized (RuntimeAudioCoordinator.class) {
            hostForeground = foreground;
            session = active;
        }
        if (session != null) session.setForeground(foreground);
    }

    public static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    public synchronized void register(Participant participant) {
        if (closed) throw new IllegalStateException("Audio runtime is closed");
        if (!participants.containsKey(participant)) participants.put(participant, new Entry(participant.requiresAudioFocus()));
    }

    public long sessionId() { return sessionId; }

    public synchronized void attachOutputGate(OutputGate gate) {
        if (closed) throw new IllegalStateException("Audio runtime is closed");
        if (outputGate == null) { outputGate = gate; publishPolicy(); }
    }

    private void publishPolicy() {
        ++policyEpoch;
        if (outputGate != null) outputGate.update(policyEpoch, minimumRequestEpoch,
                !closed && foreground && focusHeld);
    }

    public synchronized long requestEpoch(Participant participant, long token) {
        Entry entry = participants.get(participant);
        return entry != null && entry.token == token ? entry.requestEpoch : -1;
    }

    public synchronized void sharedOutputFailure(long group, String message) {
        if (closed || group == 0 || failedGroup == group) return;
        failedGroup = group;
        ArrayList<Participant> snapshot = new ArrayList<>(participants.keySet());
        management.execute(() -> {
            for (Participant participant : snapshot) {
                try { participant.onSharedOutputFailure(group, message); }
                catch (RuntimeException error) {
                    Log.w("RuntimeAudio", "Unable to close failed output participant", error);
                }
            }
        });
    }

    /** Native state/output management must remain independent of arbitrary guest listeners. */
    public synchronized ScheduledFuture<?> scheduleManagement(Runnable action) {
        if (closed) throw new IllegalStateException("Audio runtime is closed");
        return management.scheduleWithFixedDelay(action, 10, 10, TimeUnit.MILLISECONDS);
    }

    /** A denied request has no deferred autoplay intent and must not produce STARTED. */
    public synchronized long requestPlayback(Participant participant) throws MediaException {
        Entry entry = participants.get(participant);
        if (closed || entry == null) throw new MediaException("Audio runtime is closed");
        if (!foreground) throw new MediaException("Runtime audio requires a foreground host");
        if (entry.requiresFocus && !focusHeld && !acquireFocus()) {
            throw new MediaException("Audio focus request was denied");
        }
        entry.token = ++nextToken;
        entry.requested = true;
        entry.requestEpoch = policyEpoch;
        return entry.token;
    }

    public synchronized boolean isPlaybackAllowed(Participant participant, long token) {
        Entry entry = participants.get(participant);
        return foreground && entry != null && (!entry.requiresFocus || focusHeld) && isPlaybackRequested(participant, token);
    }

    public synchronized boolean isPlaybackRequested(Participant participant, long token) {
        Entry entry = participants.get(participant);
        return !closed && entry != null
                && entry.requested && entry.token == token;
    }

    public synchronized void cancelPlayback(Participant participant) {
        Entry entry = participants.get(participant);
        if (entry != null) entry.requested = false;
        if (!hasRequests()) abandonFocus();
    }

    public synchronized void unregister(Participant participant) {
        participants.remove(participant);
        if (!hasRequests()) abandonFocus();
    }

    private boolean hasRequests() {
        for (Entry entry : participants.values()) if (entry.requested && entry.requiresFocus) return true;
        return false;
    }

    private boolean acquireFocus() {
        abandonFocus();
        long epoch = ++focusEpoch;
        focusRequested = true;
        try {
            focusHeld = driver.request(epoch, this::onFocusChange);
        } catch (RuntimeException error) {
            focusHeld = false;
        }
        if (!focusHeld) abandonFocus();
        else { publishPolicy(); dispatchRequested(false, false, true); }
        return focusHeld;
    }

    private void abandonFocus() {
        ++focusEpoch;
        focusHeld = false;
        if (focusRequested) {
            focusRequested = false;
            driver.abandon();
        }
        publishPolicy();
    }

    synchronized void setForeground(boolean value) {
        if (closed || foreground == value) return;
        foreground = value;
        if (!value) {
            abandonFocus();
            dispatchRequested(true, false, false);
        } else {
            // Recreation/return may resume prior intent. Denial keeps it suspended;
            // there is no delayed focus request or background autoplay.
            if (hasRequests()) acquireFocus();
            dispatchRequested(false, false, false);
        }
    }

    private synchronized void onFocusChange(long epoch, int change) {
        if (closed || !focusRequested || epoch != focusEpoch) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = foreground;
            publishPolicy();
            if (focusHeld) dispatchRequested(false, false, true);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false;
            minimumRequestEpoch = policyEpoch + 1;
            publishPolicy();
            dispatchRequested(true, true, true);
            for (Entry entry : participants.values()) if (entry.requiresFocus) entry.requested = false;
            abandonFocus();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusHeld = false;
            publishPolicy();
            dispatchRequested(true, false, true);
        }
    }

    private void dispatchRequested(boolean suspend, boolean revoked, boolean focusOnly) {
        for (Map.Entry<Participant, Entry> item : participants.entrySet()) {
            if (!item.getValue().requested) continue;
            if (focusOnly && !item.getValue().requiresFocus) continue;
            Participant participant = item.getKey();
            long token = item.getValue().token;
            driver.execute(() -> {
                // A participant compares this token while holding its own state lock.
                if (revoked) participant.onHostFocusRevoked(token);
                else if (suspend) participant.onHostSuspend(token);
                else participant.onHostResume(token);
            });
        }
    }

    @Override
    public void close() {
        ArrayList<Participant> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            abandonFocus();
            closing = new ArrayList<>(participants.keySet());
            participants.clear();
        }
        management.shutdown();
        for (Participant participant : closing) {
            try {
                participant.closeForRuntime();
            } catch (RuntimeException error) {
                Log.w("RuntimeAudio", "Unable to close runtime audio participant", error);
            }
        }
        if (outputGate != null) outputGate.close();
    }

    private static final class AndroidFocusDriver implements FocusDriver {
        private final AudioManager manager = (AudioManager) ContextHolder.getAppContext()
                .getSystemService(Context.AUDIO_SERVICE);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private AudioFocusRequest request;
        private AudioManager.OnAudioFocusChangeListener listener;

        @Override
        public boolean request(long epoch, FocusListener target) {
            listener = change -> target.changed(epoch, change);
            int result;
            if (Build.VERSION.SDK_INT >= 26) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(audioAttributes()).setWillPauseWhenDucked(true)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(listener, handler).build();
                result = manager.requestAudioFocus(request);
            } else {
                result = manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        @Override
        public void abandon() {
            if (Build.VERSION.SDK_INT >= 26 && request != null) {
                manager.abandonAudioFocusRequest(request);
            } else if (listener != null) {
                manager.abandonAudioFocus(listener);
            }
            request = null;
            listener = null;
        }

        @Override
        public void execute(Runnable action) {
            handler.post(action);
        }
    }
}
