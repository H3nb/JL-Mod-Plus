/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2025 Yury Kharchenko
 * Copyright 2026 H3NB
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
import javax.microedition.media.control.MetaDataControl;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.tone.MidiToneConstants;
import javax.microedition.media.tone.ToneSequence;
import javax.microedition.shell.time.EmulationTime;
import javax.microedition.shell.time.EmulationSpeedListener;
import javax.microedition.shell.time.SpeedSnapshot;

import kotlin.io.FilesKt;
import ru.woesss.j2me.mmapi.FileCacheDataSource;
import ru.woesss.j2me.mmapi.audio.AudioFailure;
import ru.woesss.j2me.mmapi.audio.AudioFailureReporter;
import ru.woesss.j2me.mmapi.protocol.device.DeviceMetaData;
import io.github.h3nb.jlmodplus.settings.EmulationAudioPolicy;

class MicroPlayer extends BasePlayer implements MediaPlayer.OnCompletionListener,
		MediaPlayer.OnErrorListener,
		VolumeControl, PanControl, ToneControl, EmulationSpeedListener {
	private static final String TAG = MicroPlayer.class.getSimpleName();

	protected final HashMap<String, Control> controls = new HashMap<>();
	protected final AndroidPlayer player = new AndroidPlayer();
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

	private int configuredLoopCount = 1;
	private int remainingLoopCount = 1;
	private boolean restartFromBeginningOnStart;
	private boolean mute;
	private int level = 100;
	private int pan;
	private boolean errorEventPosted;
	private boolean emulationPaused;
	private boolean audioSpeedWarningPosted;

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
		player.setOnCompletionListener(this);
		player.setOnErrorListener(this);
		controls.put(VolumeControl.class.getName(), this);
		controls.put(PanControl.class.getName(), this);
		controls.put(MetaDataControl.class.getName(), metadata);
		// MIDI is routed to SynthPlayer. Do not expose a no-op MIDIControl or
		// EqualizerControl on Android's compressed-audio backend.
		EmulationTime.controller().addListener(this);
	}

	@Override
	public Control getControl(String controlType) {
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
	public Control[] getControls() {
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

	private synchronized void postEvent(String event, Object eventData) {
		for (PlayerListener listener : new ArrayList<>(listeners)) {
			// JSR-135 listener callbacks are asynchronous. The single executor also
			// preserves event submission order for very short media.
			callbackExecutor.execute(() -> listener.playerUpdate(this, event, eventData));
		}
	}

	@Override
	public synchronized void onCompletion(MediaPlayer mp) {
		if (state == CLOSED) {
			return;
		}

		long endTime = getMediaTime();
		postEvent(PlayerListener.END_OF_MEDIA, endTime);

		boolean repeat = remainingLoopCount == -1 || remainingLoopCount > 1;
		if (!repeat) {
			state = PREFETCHED;
			remainingLoopCount = configuredLoopCount;
			restartFromBeginningOnStart = true;
			return;
		}

		if (remainingLoopCount > 1) {
			remainingLoopCount--;
		}
		try {
			player.seekTo(0);
			player.start();
			state = STARTED;
			restartFromBeginningOnStart = false;
			postEvent(PlayerListener.STARTED, 0L);
		} catch (RuntimeException e) {
			state = PREFETCHED;
			remainingLoopCount = configuredLoopCount;
			restartFromBeginningOnStart = true;
			reportFailure(AudioFailure.Phase.START, "MEDIA_PLAYER_RESTART_FAILED", e);
		}
	}

	@Override
	public synchronized boolean onError(MediaPlayer mp, int what, int extra) {
		if (state == CLOSED) {
			return true;
		}
		reportFailure(AudioFailure.Phase.RUNTIME,
				"MEDIA_PLAYER_ERROR_" + what + "_" + extra, null);
		// MediaPlayer error callbacks are irrecoverable for this Player. Release
		// outside the platform callback after ERROR has been queued.
		callbackExecutor.execute(this::close);
		return true;
	}

	@Override
	public synchronized void realize() throws MediaException {
		checkClosed();

		if (state == UNREALIZED) {
			try {
				source.connect();
				player.setDataSource(source.getLocator());
			} catch (IOException | RuntimeException e) {
				reportFailure(AudioFailure.Phase.REALIZE, "MEDIA_SOURCE_FAILED", e);
				if (e instanceof RuntimeException runtimeException) {
					throw runtimeException;
				}
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
			try {
				metadata.updateMetaData(source);
				player.prepareSource();
			} catch (IOException | RuntimeException e) {
				reportFailure(AudioFailure.Phase.PREFETCH, "MEDIA_PREPARE_FAILED", e);
				if (e instanceof RuntimeException runtimeException) {
					throw runtimeException;
				}
				throw new MediaException(e.getMessage());
			}
			state = PREFETCHED;
		}
	}

	@Override
	public synchronized void start() throws MediaException {
		prefetch();

		if (state == PREFETCHED) {
			try {
				if (restartFromBeginningOnStart) {
					player.seekTo(0);
					restartFromBeginningOnStart = false;
				}
				player.start();
				state = STARTED;
				errorEventPosted = false;
				onEmulationSpeedChanged(EmulationTime.snapshot());
				postEvent(PlayerListener.STARTED, getMediaTime());
			} catch (RuntimeException e) {
				state = PREFETCHED;
				reportFailure(AudioFailure.Phase.START, "MEDIA_PLAYER_START_FAILED", e);
				throw new MediaException(e.getMessage());
			}
		}
	}

	@Override
	public synchronized void stop() {
		checkClosed();
		if (state == STARTED) {
			player.pause();
			emulationPaused = false;

			state = PREFETCHED;
			postEvent(PlayerListener.STOPPED, getMediaTime());
		}
	}

	@Override
	public synchronized void deallocate() {
		checkClosed();
		if (state == STARTED) {
			stop();
		}

		if (state == PREFETCHED) {
			player.reset();
			state = REALIZED;
		}
	}

	@Override
	public synchronized void close() {
		if (state == CLOSED) {
			return;
		}

		EmulationTime.controller().removeListener(this);
		state = CLOSED;
		player.release();
		source.disconnect();
		postEvent(PlayerListener.CLOSED, null);
		callbackExecutor.shutdown();
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

	private void reportFailure(AudioFailure.Phase phase, String code, Throwable error) {
		AudioFailure failure = AudioFailure.create(source.getLocator(), source.getContentType(),
				"Android MediaPlayer", phase, code, error);
		AudioFailureReporter.report(failure);
		if (!errorEventPosted) {
			errorEventPosted = true;
			postEvent(PlayerListener.ERROR, failure.getCode());
		}
	}

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		if (now < 0) {
			now = 0;
		}
		if (state >= PREFETCHED) {
			long duration = getDuration();
			if (duration >= 0 && now > duration) {
				now = duration;
			}
		}

		long millis = now / 1000L;
		int time = millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
		player.seekTo(time);
		restartFromBeginningOnStart = false;
		return player.getCurrentPosition() * 1000L;
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		if (state < REALIZED) {
			return TIME_UNKNOWN;
		}
		return player.getCurrentPosition() * 1000L;
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		if (state < PREFETCHED) {
			return TIME_UNKNOWN;
		}
		return player.getDuration() * 1000L;
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (state == STARTED) {
			throw new IllegalStateException("player must not be in STARTED state while using setLoopCount()");
		}
		if (count == 0 || count < -1) {
			throw new IllegalArgumentException("loop count must be positive or -1");
		}

		// Keep looping in MMAPI rather than MediaPlayer so every loop emits the
		// END_OF_MEDIA and STARTED events required by JSR-135.
		player.setLooping(false);
		configuredLoopCount = count;
		remainingLoopCount = count;
	}

	@Override
	public int getState() {
		return state;
	}

	@Override
	public synchronized void onEmulationSpeedChanged(SpeedSnapshot snapshot) {
		if (state == CLOSED) {
			return;
		}
		if (!EmulationAudioPolicy.isAudioSpeedEnabled()) {
			// Keep audio at its normal rate when the experimental option is off.
			// This also restores a player if the option was disabled while it was
			// following emulation speed.
			player.setPlaybackSpeed(1.0f);
			if (state != STARTED) {
				return;
			}
			if (emulationPaused) {
				try {
					player.resumePlayback();
					emulationPaused = false;
				} catch (RuntimeException e) {
					Log.w(TAG, "Can't resume MediaPlayer after audio speed policy was disabled", e);
				}
			}
			return;
		}
		if (!player.setPlaybackSpeed((float) snapshot.speed().asDouble())
				&& !audioSpeedWarningPosted) {
			audioSpeedWarningPosted = true;
			Log.w(TAG, "MediaPlayer rejected emulation audio speed " + snapshot.speed());
		}
		if (state != STARTED) {
			return;
		}
		if (snapshot.isPaused()) {
			if (!emulationPaused) {
				try {
					player.pause();
					emulationPaused = true;
				} catch (RuntimeException e) {
					Log.w(TAG, "Can't pause MediaPlayer for emulation pause", e);
				}
			}
		} else if (emulationPaused) {
			try {
				player.resumePlayback();
				emulationPaused = false;
			} catch (RuntimeException e) {
				Log.w(TAG, "Can't resume MediaPlayer after emulation pause", e);
			}
		}
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
				right = volumeToGain(level * (100 + pan) / 100);
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
			return;
		}

		this.mute = mute;
		updateVolume();
	}

	@Override
	public boolean isMuted() {
		return mute;
	}

	@Override
	public synchronized int setLevel(int level) {
		if (state == CLOSED) {
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
	public int getLevel() {
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
		if (state == CLOSED) {
			return this.pan;
		}

		this.pan = pan;
		updateVolume();
		return pan;
	}

	@Override
	public int getPan() {
		return pan;
	}

	// ToneControl

	@Override
	public void setSequence(byte[] sequence) {
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
