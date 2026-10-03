/*
 * Copyright 2023-2025 Yury Kharchenko
 * Modified for JL-Mod Plus.
 *
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

package io.github.h3nb.jlmodplus.mmapi.synth;

import static javax.microedition.media.Manager.MIDI_DEVICE_LOCATOR;
import static javax.microedition.media.Manager.TONE_DEVICE_LOCATOR;

import android.util.Log;

import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

import javax.microedition.amms.control.PanControl;
import javax.microedition.amms.control.audioeffect.EqualizerControl;
import javax.microedition.media.BasePlayer;
import javax.microedition.media.Control;
import javax.microedition.media.InternalEqualizer;
import javax.microedition.media.InternalMetaData;
import javax.microedition.media.MediaException;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.Manager;
import javax.microedition.media.TimeBase;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.control.MetaDataControl;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.control.VideoControl;
import javax.microedition.media.control.GUIControl;
import javax.microedition.media.protocol.DataSource;

import io.github.h3nb.jlmodplus.mmapi.control.MIDIControlImpl;
import io.github.h3nb.jlmodplus.mmapi.protocol.device.DeviceMetaData;

public class AudioPlayer extends BasePlayer implements VolumeControl, PanControl, ToneControl, RuntimeAudioCoordinator.Participant {
	private static final String TAG = AudioPlayer.class.getSimpleName();

	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "MidletPlayerCallback");
		thread.setDaemon(true);
		return thread;
	});
	private ScheduledFuture<?> eventPoll;
	private final RuntimeAudioCoordinator audio = RuntimeAudioCoordinator.current();
	private long sourceGeneration;
	private final long outputGroup;
	private long playbackToken;
	private boolean hostSuspended;
	private boolean requestedPlayback;
	private long mediaTime = TIME_UNKNOWN;
	private long duration = TIME_UNKNOWN;
	// Native playback generations also change on seek/pause. Duration belongs
	// to the logical media, and only replacing that media invalidates delivery.
	private long durationSource;
	private final ArrayList<PlayerListener> listeners = new ArrayList<>();
	private final InternalMetaData metadata;
	private final DataSource dataSource;
	private final long handle;
	private final Library library;

	private Map<String, Control> controls;
	private int state = UNREALIZED;
	private int volume = 100;
	private boolean mute;
	private int pan;

	public AudioPlayer(Library library, DataSource dataSource) {
		String locator = dataSource.getLocator();
		if (MIDI_DEVICE_LOCATOR.equals(locator) || TONE_DEVICE_LOCATOR.equals(locator)) {
			metadata = new DeviceMetaData();
		} else {
			metadata = new InternalMetaData();
		}
		this.library = library;
		this.dataSource = dataSource;
		handle = library.createPlayer(locator);
		outputGroup = library.getOutputIdentity(handle);
		boolean registered = false;
		try {
			audio.register(this);
			registered = true;
		} finally { if (!registered) library.close(handle); }
	}

	@Override
	public synchronized void realize() throws MediaException {
		checkClosed();

		if (state == UNREALIZED) {
			library.realize(handle);
			if (controls == null) {
				controls = new HashMap<>();
				if (TONE_DEVICE_LOCATOR.equals(dataSource.getLocator())) {
					controls.put(ToneControl.class.getName(), this);
				}
				controls.put(VolumeControl.class.getName(), this);
				if (library.isSynthesis()) controls.put(MIDIControl.class.getName(),
						new MIDIControlImpl(this, library, handle, this::prepareMidiOutput));
				controls.put(PanControl.class.getName(), this);
				controls.put(MetaDataControl.class.getName(), metadata);
				controls.put(EqualizerControl.class.getName(), new InternalEqualizer());
				VideoControl video = library.videoControl();
				if (video != null) {
					controls.put(VideoControl.class.getName(), video);
					controls.put(GUIControl.class.getName(), video);
					library.setVideoSizeListener(() -> {
						synchronized (this) {
							if (state != CLOSED) postEvent(PlayerListener.SIZE_CHANGED, video);
						}
					});
				}
			}
			state = REALIZED;
			updateDuration();
		}
	}

	@Override
	public synchronized void prefetch() throws MediaException {
		checkClosed();

		if (state == UNREALIZED) {
			realize();
		}

		if (state == REALIZED) {
			try {
				String[] tags = library.metadata(handle);
				if (tags == null) metadata.updateMetaData(dataSource);
				else metadata.updateDemuxerMetaData(tags);
			} catch (Exception e) {
				Log.w(TAG, "prefetch: update metadata failed", e);
			}
			try { library.prefetch(handle); }
			catch (Exception error) {
				fail("Cannot prefetch audio: " + error);
				throw new MediaException("Cannot prefetch runtime output: " + error);
			}
			state = PREFETCHED;
			mediaTime = library.getMediaTime(handle);
			updateDuration();
			sourceGeneration = library.getGeneration(handle);
			eventPoll = audio.scheduleManagement(this::pollEvents);
		}
	}

	@Override
	public synchronized void start() throws MediaException {
		prefetch();
		if (state == PREFETCHED || !requestedPlayback || !audio.isPlaybackRequested(this, playbackToken)) {
			activateOutput(false);
			if (state != STARTED) {
				state = STARTED;
				postEvent(PlayerListener.STARTED, getMediaTime());
			}
		}
	}

	private void activateOutput(boolean midiOnly) throws MediaException {
		long token = audio.requestPlayback(this);
		try {
			long epoch = audio.requestEpoch(this, token);
			if (midiOnly) library.activateMidi(handle, epoch);
			else library.start(handle, epoch);
			sourceGeneration = library.getGeneration(handle);
			playbackToken = token;
			requestedPlayback = true;
            hostSuspended = library.isOutputSuspended(handle);
            if (hostSuspended && audio.isPlaybackAllowed(this, token)) {
                library.resumeOutput(handle);
                hostSuspended = library.isOutputSuspended(handle);
            }
		} catch (Exception e) {
			audio.cancelPlayback(this);
			String message = "Cannot start audio output: " + e;
			// Focus/foreground denial happens before this try. A backend activation
			// failure has no healthy output to retry and remains a terminal engine
			// fact even when shortMidiEvent must hide delivery exceptions.
			fail(message);
			throw new MediaException(message);
		}
	}

	private synchronized void prepareMidiOutput() {
		if (state < PREFETCHED) throw new IllegalStateException("Player must be prefetched");
		if (!requestedPlayback || !audio.isPlaybackRequested(this, playbackToken)) {
			try { activateOutput(state != STARTED); }
			catch (MediaException e) { throw new IllegalStateException(e); }
		}
	}

	@Override
	public synchronized void stop() throws MediaException {
		checkClosed();
		if (requestedPlayback) {
			library.pause(handle);
			sourceGeneration = library.getGeneration(handle);
			mediaTime = library.getMediaTime(handle);
			requestedPlayback = false;
			hostSuspended = false;
			audio.cancelPlayback(this);
		}
		if (state == STARTED) {
			state = PREFETCHED;
			postEvent(PlayerListener.STOPPED, getMediaTime());
		}
	}

	@Override
	public synchronized void deallocate() {
		checkClosed();
		try { stop(); }
		catch (MediaException e) { fail("Cannot stop audio output: " + e); return; }
		if (state == PREFETCHED) {
			mediaTime = library.getMediaTime(handle);
			updateDuration();
			library.deallocate(handle);
			sourceGeneration = library.getGeneration(handle);
			state = REALIZED;
			if (eventPoll != null) { eventPoll.cancel(false); eventPoll = null; }
		}
	}

	@Override
	public synchronized void close() {
		if (state == CLOSED) return;
		state = CLOSED;
		requestedPlayback = false;
		audio.unregister(this);
		if (eventPoll != null) { eventPoll.cancel(false); eventPoll = null; }
		try { library.close(handle); }
		finally {
			try { dataSource.disconnect(); }
			finally {
				try { postEvent(PlayerListener.CLOSED, null); }
				finally { callbackExecutor.shutdown(); }
			}
		}
	}

	private void fail(String message) {
		if (state == CLOSED) return;
		if (library.outputFailed(handle)) audio.sharedOutputFailure(outputGroup, message);
		postEvent(PlayerListener.ERROR, message);
		close();
	}

    @Override
    public synchronized void onSharedOutputFailure(long group, String message) {
        if (state != CLOSED && outputGroup == group) fail(message);
    }

	@Override
	public synchronized void onHostSuspend(long token) {
		if (state == CLOSED || !requestedPlayback || token != playbackToken || hostSuspended ||
				audio.isPlaybackAllowed(this, token)) return;
		try {
			library.suspendOutput(handle);
			sourceGeneration = library.getGeneration(handle);
			mediaTime = library.getMediaTime(handle);
			hostSuspended = true;
		} catch (Exception e) { fail("Cannot suspend audio output: " + e); }
	}

	@Override
	public synchronized void onHostResume(long token) {
		if (state == CLOSED || !requestedPlayback || token != playbackToken || !hostSuspended ||
				!audio.isPlaybackAllowed(this, token)) return;
		try {
			library.resumeOutput(handle);
			sourceGeneration = library.getGeneration(handle);
			hostSuspended = library.isOutputSuspended(handle);
		} catch (Exception e) { fail("Cannot resume audio output: " + e); }
	}

	@Override
	public synchronized void onHostFocusRevoked(long token) {
		onHostSuspend(token);
		if (token == playbackToken) requestedPlayback = false;
	}

	@Override
	public void closeForRuntime() { close(); }

	@Override public boolean requiresAudioFocus() { return library.requiresAudioFocus(); }

    @Override
    public synchronized TimeBase getTimeBase() {
        checkClosed();
        if (state == UNREALIZED) throw new IllegalStateException("Player must be realized");
        return Manager.getSystemTimeBase();
    }

    @Override
    public synchronized void setTimeBase(TimeBase master) throws MediaException {
        checkClosed();
        if (state == UNREALIZED || state == STARTED) throw new IllegalStateException("Player must be realized and stopped");
        if (master != null && master != Manager.getSystemTimeBase())
            throw new MediaException("Custom synchronized TimeBase is unsupported");
    }

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		mediaTime = library.setMediaTime(handle, now);
		sourceGeneration = library.getGeneration(handle);
		return mediaTime;
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		if (state < PREFETCHED) {
			return mediaTime;
		} else {
			mediaTime = library.getMediaTime(handle);
			return mediaTime;
		}
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		updateDuration();
		return duration;
	}

	private void updateDuration() {
		long observed = library.getDuration(handle);
		// Deallocation can release the decoder's duration. Retain the known
		// duration of this media until setSequence explicitly replaces it.
		if (observed != TIME_UNKNOWN && observed != duration) {
			duration = observed;
			postEvent(PlayerListener.DURATION_UPDATED, Long.valueOf(observed));
		}
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (state == STARTED)
			throw new IllegalStateException("player must not be in STARTED state while using setLoopCount()");

		if (count == 0 || count < -1) {
			throw new IllegalArgumentException("loop count must not be 0");
		}
		library.setRepeat(handle, count);
	}

	@Override
	public synchronized int getState() {
		return state;
	}

	@Override
	public synchronized String getContentType() {
		checkRealized();
		String actual = library.contentType(handle);
		return actual.isEmpty() ? dataSource.getContentType() : actual;
	}

	@Override
	public synchronized int setPan(int pan) {
		if (pan < -100) {
			pan = -100;
		} else if (pan > 100) {
			pan = 100;
		}
		if (this.pan == pan) {
			return pan;
		}
		this.pan = pan;
		if (state == CLOSED) {
			return pan;
		}
		if (!mute) {
			updateVolume();
		}
		return pan;
	}

	@Override
	public synchronized int getPan() {
		return pan;
	}

	@Override
	public synchronized void setMute(boolean mute) {
		if (this.mute == mute) {
			return;
		}
		this.mute = mute;
		if (state == CLOSED) {
			return;
		}
		if (mute) {
			library.setVolume(handle, 0, 0);
		} else {
			updateVolume();
		}
		postEvent(PlayerListener.VOLUME_CHANGED, this);
	}

	@Override
	public synchronized boolean isMuted() {
		return mute;
	}

	@Override
	public synchronized int setLevel(int level) {
		if (level < 0) {
			level = 0;
		} else if (level > 100) {
			level = 100;
		}
		if (volume == level) {
			return level;
		}
		volume = level;
		if (state == CLOSED) {
			return level;
		}
		if (!mute) {
			updateVolume();
		}
		postEvent(PlayerListener.VOLUME_CHANGED, this);
		return level;
	}

	@Override
	public synchronized int getLevel() {
		return volume;
	}

	@Override
	public synchronized Control getControl(String controlType) {
		checkRealized();
		if (controlType == null) {
			throw new IllegalArgumentException();
		}
		if (!controlType.contains(".")) {
			controlType = "javax.microedition.media.control." + controlType;
		}
		return controls.get(controlType);
	}

	@Override
	public synchronized Control[] getControls() {
		checkRealized();
		return controls.values().toArray(new Control[0]);
	}

	@Override
	public synchronized void addPlayerListener(PlayerListener playerListener) {
		checkClosed();
		if (playerListener != null && !listeners.contains(playerListener)) {
			listeners.add(playerListener);
		}
	}

	@Override
	public synchronized void removePlayerListener(PlayerListener playerListener) {
		checkClosed();
		listeners.remove(playerListener);
	}

	private synchronized void pollEvents() {
		if (state < PREFETCHED) return;
		try {
			long[] event;
			while ((event = library.pollEvent(handle)) != null) {
				if (event[2] != sourceGeneration) continue;
				updateDuration();
				acceptEvent((int) event[0], event[1], event.length > 3 ? event[3] : 0);
				if (state == CLOSED) return;
			}
			updateDuration();
			if (library.isOutputSuspended(handle)) hostSuspended = true;
		} catch (Exception e) { fail("Audio management failed: " + e); }
	}

	private void acceptEvent(int type, long time, long error) {
		if (state == CLOSED) return;
		if (type == 4) {
			if (requestedPlayback && !hostSuspended && audio.isPlaybackAllowed(this, playbackToken)) {
				library.recoverOutput(handle);
				sourceGeneration = library.getGeneration(handle);
			}
			return;
		}
		if (type == 5) { audio.sharedOutputFailure(outputGroup, "Runtime output failure (code " + error + ")"); return; }
		if (type == 3) { fail("Audio source failure (code " + error + ")"); return; }
		if (state != STARTED) return;
		mediaTime = time;
		postEvent(PlayerListener.END_OF_MEDIA, time);
		if (type == 1) {
			mediaTime = 0;
			sourceGeneration = library.getGeneration(handle);
			postEvent(PlayerListener.STARTED, 0L);
		} else if (type == 2) {
			state = PREFETCHED;
			requestedPlayback = false;
			audio.cancelPlayback(this);
		}
	}

	private synchronized void postEvent(String event, Object eventData) {
		PlayerListener[] snapshot = listeners.toArray(new PlayerListener[0]);
		if (snapshot.length == 0) return;
		long media = durationSource;
		callbackExecutor.execute(() -> {
			for (PlayerListener listener : snapshot) {
				synchronized (this) {
					if (state == CLOSED && !PlayerListener.CLOSED.equals(event) && !PlayerListener.ERROR.equals(event)) return;
					if (PlayerListener.DURATION_UPDATED.equals(event) && media != durationSource) return;
				}
				try { listener.playerUpdate(this, event, eventData); }
				catch (Throwable e) { Log.e(TAG, "Player listener failed", e); }
			}
		});
	}

	private void checkClosed() {
		if (state == CLOSED) {
			throw new IllegalStateException("player is closed");
		}
	}

	private void checkRealized() {
		checkClosed();
		if (state < REALIZED) {
			throw new IllegalStateException("call realize() before using the player");
		}
	}

	@Override
	public synchronized void setSequence(byte[] sequence) {
		checkClosed();
		if (state >= PREFETCHED) {
			throw new IllegalStateException();
		} else if (sequence == null) {
			throw new IllegalArgumentException("sequence is NULL");
		}
		try {
			library.setDataSource(handle, sequence);
			durationSource++;
			mediaTime = 0;
			duration = TIME_UNKNOWN;
			updateDuration();
			sourceGeneration = library.getGeneration(handle);
		} catch (Exception e) {
			Log.e(TAG, "setSequence: ", e);
			throw new IllegalArgumentException(e);
		}
	}

	private void updateVolume() {
		float gain = volumeToGain(volume);
		if (pan == 0) {
			library.setVolume(handle, gain, gain);
		} else if (pan < 0) {
			library.setVolume(handle, gain, volumeToGain(volume * (100 + pan) / 100));
		} else {
			library.setVolume(handle, volumeToGain(volume * (100 - pan) / 100), gain);
		}
	}

	private float volumeToGain(int volume) {
		if (volume <= 0) {
			return 0.0f;
		} else if (volume >= 100) {
			return 1.0f;
		}
		return (float) (1 - (Math.log(100 - volume) / Math.log(100)));
	}
}
