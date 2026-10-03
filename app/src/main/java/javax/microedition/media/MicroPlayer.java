/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2025 Yury Kharchenko
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

package javax.microedition.media;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.amms.control.PanControl;
import javax.microedition.amms.control.audioeffect.EqualizerControl;
import javax.microedition.media.control.MIDIControl;
import javax.microedition.media.control.MetaDataControl;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.tone.MidiToneConstants;
import javax.microedition.media.tone.ToneSequence;

import kotlin.io.FilesKt;
import io.github.h3nb.jlmodplus.mmapi.FileCacheDataSource;
import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import io.github.h3nb.jlmodplus.mmapi.control.MIDIControlImpl;
import io.github.h3nb.jlmodplus.mmapi.protocol.device.DeviceMetaData;

class MicroPlayer extends BasePlayer implements MediaPlayer.OnCompletionListener,
		VolumeControl, PanControl, ToneControl, RuntimeAudioCoordinator.Participant {
	private static final String TAG = MicroPlayer.class.getSimpleName();

	protected final HashMap<String, Control> controls = new HashMap<>();
	protected final MediaPlayer player = new AndroidPlayer();
	protected final DataSource source;
	protected int state = UNREALIZED;

	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "MidletPlayerCallback");
		thread.setUncaughtExceptionHandler((t, e) ->
				Log.e(t.getName(), "UncaughtException in " + t, e));
		return thread;
	});
	private final ArrayList<PlayerListener> listeners = new ArrayList<>();
	private final InternalMetaData metadata;

	private int loopCount = 1;
	private boolean mute = false;
	private int level = 100;
	private int pan;
	private final RuntimeAudioCoordinator audio = RuntimeAudioCoordinator.current();
	private long playbackToken;
	private boolean hostSuspended;
	private boolean hostFocusRevoked;

	public MicroPlayer(String locator) throws IOException {
		if (!Manager.TONE_DEVICE_LOCATOR.equals(locator)) {
			throw new IllegalArgumentException();
		}
		source = new FileCacheDataSource("audio/x-tone-seq", "mid");
		controls.put(MidiToneConstants.TONE_CONTROL_FULL_NAME, this);
		metadata = new DeviceMetaData();
		init();
	}

	MicroPlayer(DataSource datasource) {
		source = datasource;
		metadata = new InternalMetaData();
		init();
	}

	private void init() {
		try {
			audio.register(this);
		} catch (RuntimeException failure) {
			player.release();
			source.disconnect();
			throw failure;
		}
		player.setOnCompletionListener(this);
		player.setOnErrorListener((media, what, extra) -> {
			synchronized (this) {
				if (state != CLOSED) {
					postEvent(PlayerListener.ERROR, "Sampled audio failure (" + what + ", " + extra + ")");
					close();
				}
			}
			return true;
		});
		controls.put(VolumeControl.class.getName(), this);
		controls.put(PanControl.class.getName(), this);
		controls.put(MetaDataControl.class.getName(), metadata);
		controls.put(EqualizerControl.class.getName(), new InternalEqualizer());
		// TODO: 12.02.2025 Needs to be added only if content type is MIDI
		controls.put(MIDIControl.class.getName(), new MIDIControlImpl(this));
	}

	@Override
	public Control getControl(String controlType) {
		checkRealized();
		if (!controlType.contains(".")) {
			controlType = "javax.microedition.media.control." + controlType;
		}
		return controls.get(controlType);
	}

	@Override
	public Control[] getControls() {
		checkRealized();
		return controls.values().toArray(new Control[0]);
	}

	@Override
	public synchronized void addPlayerListener(PlayerListener playerListener) {
		checkClosed();
		if (!listeners.contains(playerListener) && playerListener != null) {
			listeners.add(playerListener);
		}
	}

	@Override
	public synchronized void removePlayerListener(PlayerListener playerListener) {
		checkClosed();
		listeners.remove(playerListener);
	}

	private synchronized void postEvent(String event, Object eventData) {
		PlayerListener[] snapshot = listeners.toArray(new PlayerListener[0]);
		if (callbackExecutor.isShutdown()) return;
		callbackExecutor.execute(() -> {
			for (PlayerListener listener : snapshot) {
				synchronized (this) {
					if (state == CLOSED && !PlayerListener.CLOSED.equals(event)
							&& !PlayerListener.ERROR.equals(event)) return;
				}
				try {
					listener.playerUpdate(this, event, eventData);
				} catch (Throwable error) {
					Log.e(TAG, "Player listener failed", error);
				}
			}
		});
	}

	@Override
	public synchronized void onCompletion(MediaPlayer mp) {
		if (state != STARTED) {
			return;
		}
		postEvent(PlayerListener.END_OF_MEDIA, getMediaTime());

		if (loopCount == 1) {
			state = PREFETCHED;
			playbackToken = 0;
			audio.cancelPlayback(this);
		} else if (loopCount > 1) {
			loopCount--;
		}

		if (state == STARTED && loopCount != -1) {
			player.seekTo(0);
			if (audio.isPlaybackAllowed(this, playbackToken)) player.start();
			else hostSuspended = true;
			postEvent(PlayerListener.STARTED, getMediaTime());
		}
	}

	@Override
	public synchronized void realize() throws MediaException {
		checkClosed();

		if (state == UNREALIZED) {
			try {
				source.connect();
				player.setDataSource(source.getLocator());
			} catch (IOException e) {
				throw new MediaException(e.getMessage());
			}

			state = REALIZED;
		}
	}

	@Override
	public synchronized void prefetch() throws MediaException {
		checkClosed();

		if (state == UNREALIZED) {
			realize();
		}

		if (state == REALIZED) {
			metadata.updateMetaData(source);
			state = PREFETCHED;
		}
	}

	@Override
	public synchronized void start() throws MediaException {
		prefetch();

		if (state == PREFETCHED || (state == STARTED &&
				(hostFocusRevoked || !audio.isPlaybackRequested(this, playbackToken)))) {
			boolean notifyStarted = state != STARTED;
			playbackToken = audio.requestPlayback(this);
			try {
				player.start();
			} catch (RuntimeException failure) {
				audio.cancelPlayback(this);
				playbackToken = 0;
				throw new MediaException("Unable to start sampled audio: " + failure.getMessage());
			}
			hostSuspended = false;
			hostFocusRevoked = false;

			state = STARTED;
			if (notifyStarted) postEvent(PlayerListener.STARTED, getMediaTime());
		}
	}

	@Override
	public synchronized void stop() {
		checkClosed();
		if (state == STARTED) {
			if (!hostSuspended) player.pause();
			playbackToken = 0;
			hostSuspended = false;
			hostFocusRevoked = false;
			audio.cancelPlayback(this);

			state = PREFETCHED;
			postEvent(PlayerListener.STOPPED, getMediaTime());
		}
	}

	@Override
	public synchronized void deallocate() {
		stop();

		if (state == PREFETCHED) {
			player.reset();
			state = UNREALIZED;

			try {
				realize();
			} catch (MediaException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public synchronized void close() {
		if (state == CLOSED) return;
		state = CLOSED;
		playbackToken = 0;
		audio.unregister(this);
		try {
			player.release();
		} finally {
			try {
				source.disconnect();
			} finally {
				try {
					postEvent(PlayerListener.CLOSED, null);
				} finally {
					// Never join from a guest listener; terminate even if final scheduling fails.
					callbackExecutor.shutdown();
				}
			}
		}
	}

	@Override
	public synchronized void onHostSuspend(long token) {
		if (state != STARTED || playbackToken != token || hostSuspended
				|| audio.isPlaybackAllowed(this, token)) return;
		try {
			player.pause();
			hostSuspended = true;
		} catch (RuntimeException failure) {
			postEvent(PlayerListener.ERROR, "Unable to suspend sampled audio: " + failure.getMessage());
			close();
		}
	}

	@Override
	public synchronized void onHostResume(long token) {
		if (state != STARTED || playbackToken != token || !hostSuspended
				|| !audio.isPlaybackAllowed(this, token)) return;
		try {
			player.start();
			hostSuspended = false;
		} catch (RuntimeException failure) {
			postEvent(PlayerListener.ERROR, "Unable to resume sampled audio: " + failure.getMessage());
			close();
		}
	}

	@Override
	public synchronized void onHostFocusRevoked(long token) {
		if (state != STARTED || playbackToken != token) return;
		onHostSuspend(token);
		if (state == CLOSED) return;
		hostFocusRevoked = true;
	}

	@Override
	public void closeForRuntime() {
		close();
	}

	private void checkClosed() {
		if (state == CLOSED) {
			throw new IllegalStateException("player is closed");
		}
	}

	private void checkRealized() {
		checkClosed();

		if (state == UNREALIZED) {
			throw new IllegalStateException("call realize() before using the player");
		}
	}

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		if (state < PREFETCHED) {
			return 0;
		} else {
			int time = (int) (now / 1000L);
			if (time != player.getCurrentPosition()) {
				player.seekTo(time);
			}
			return getMediaTime();
		}
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		if (state < PREFETCHED) {
			return TIME_UNKNOWN;
		} else {
			return player.getCurrentPosition() * 1000L;
		}
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		if (state < PREFETCHED) {
			return TIME_UNKNOWN;
		} else {
			return player.getDuration() * 1000L;
		}
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (state == STARTED)
			throw new IllegalStateException("player must not be in STARTED state while using setLoopCount()");

		if (count == 0) {
			throw new IllegalArgumentException("loop count must not be 0");
		}

		player.setLooping(count == -1);

		loopCount = count;
	}

	@Override
	public synchronized int getState() {
		return state;
	}

	@Override
	public String getContentType() {
		checkRealized();
		return source.getContentType();
	}

	// VolumeControl

	private void updateVolume() {
		float left, right;

		if (mute) {
			left = right = 0;
		} else {
			left = right = volumeToGain(level);
			if (pan > 0) {
				left = volumeToGain(level * (100 - pan) / 100);
			} else if (pan < 0) {
				right =  volumeToGain(level * (100 + pan) / 100);
			}
		}

		player.setVolume(left, right);
		postEvent(PlayerListener.VOLUME_CHANGED, this);
	}

	private float volumeToGain(int volume) {
		if (volume <= 0) {
			return 0.0f;
		} else if (volume >= 100) {
			return 1.0f;
		}
		return (float) (1 - (Math.log(100 - volume) / Math.log(100)));
	}

	@Override
	public synchronized void setMute(boolean mute) {
		if (state == CLOSED) {
			// Avoid IllegalStateException in MediaPlayer.setVolume()
			return;
		}

		this.mute = mute;
		updateVolume();
	}

	@Override
	public synchronized boolean isMuted() {
		return mute;
	}

	@Override
	public synchronized int setLevel(int level) {
		if (state == CLOSED) {
			// Avoid IllegalStateException in MediaPlayer.setVolume()
			return this.level;
		}

		if (level < 0) {
			level = 0;
		} else if (level > 100) {
			level = 100;
		}

		this.level = level;
		updateVolume();

		return level;
	}

	@Override
	public synchronized int getLevel() {
		return level;
	}


	// PanControl

	@Override
	public synchronized int setPan(int pan) {
		if (pan < -100) {
			pan = -100;
		} else if (pan > 100) {
			pan = 100;
		}

		this.pan = pan;
		updateVolume();

		return pan;
	}

	@Override
	public synchronized int getPan() {
		return pan;
	}

	// ToneControl

	@Override
	public synchronized void setSequence(byte[] sequence) {
		if (state >= PREFETCHED) {
			throw new IllegalStateException();
		} else if (sequence == null) {
			throw new IllegalArgumentException("sequence is NULL");
		}
		try {
			ToneSequence tone = new ToneSequence(sequence);
			tone.process();
			byte[] data = tone.getByteArray();
			String locator = source.getLocator();
			FilesKt.writeBytes(new File(locator), data);
		} catch (Exception e) {
			Log.e(TAG, "setSequence: ", e);
			throw new IllegalArgumentException(e);
		}
	}
}
