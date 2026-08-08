/*
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

package ru.woesss.j2me.mmapi.audio;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.media.BasePlayer;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.media.protocol.DataSource;

/**
 * MMAPI player for RIFF/WAVE media decoded by the pinned dr_wav backend.
 *
 * <p>This backend is intentionally independent from the MIDI synthesizer so
 * WAV/IMA-ADPCM data cannot enter SONiVOX's interactive MIDI path.</p>
 */
public final class WavPlayer extends BasePlayer implements VolumeControl {
	private static final String CONTENT_TYPE = "audio/x-wav";

	private final DataSource source;
	private final Map<String, Control> controls = new HashMap<>();
	private final ArrayList<PlayerListener> listeners = new ArrayList<>();
	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "MidletWavPlayerCallback");
		thread.setDaemon(true);
		return thread;
	});

	private long handle;
	private int state = UNREALIZED;
	private int level = 100;
	private boolean mute;
	private boolean errorEventPosted;

	static {
		System.loadLibrary("mmapi_wav");
	}

	public WavPlayer(DataSource source) throws MediaException {
		if (source == null || source.getLocator() == null) {
			throw new IllegalArgumentException("WAV source has no locator");
		}
		this.source = source;
		handle = nativeCreate(source.getLocator());
		if (handle == 0) {
			throw new MediaException("Unable to create WAV player");
		}
		nativeSetListener(handle, this);
		controls.put(VolumeControl.class.getName(), this);
	}

	@Override
	public synchronized void realize() throws MediaException {
		checkClosed();
		if (state == UNREALIZED) {
			nativeRealize(handle);
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
			nativePrefetch(handle);
			state = PREFETCHED;
			updateVolume(false);
		}
	}

	@Override
	public synchronized void start() throws MediaException {
		prefetch();
		if (state == PREFETCHED) {
			nativeStart(handle);
			state = STARTED;
			errorEventPosted = false;
			postEvent(PlayerListener.STARTED, getMediaTime());
		}
	}

	@Override
	public synchronized void stop() throws MediaException {
		checkClosed();
		if (state == STARTED) {
			nativePause(handle);
			state = PREFETCHED;
			postEvent(PlayerListener.STOPPED, getMediaTime());
		}
	}

	@Override
	public synchronized void deallocate() {
		checkClosed();
		if (state == STARTED) {
			try {
				stop();
			} catch (MediaException e) {
				reportRuntimeError("WAV_STOP_BEFORE_DEALLOCATE_FAILED", e);
				return;
			}
		}
		if (state == PREFETCHED) {
			nativeDeallocate(handle);
			state = REALIZED;
		}
	}

	@Override
	public synchronized void close() {
		if (state == CLOSED) {
			return;
		}

		state = CLOSED;
		long nativeHandle = handle;
		handle = 0;
		if (nativeHandle != 0) {
			nativeDestroy(nativeHandle);
		}
		source.disconnect();
		postEvent(PlayerListener.CLOSED, null);
		callbackExecutor.shutdown();
	}

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		return nativeSetMediaTime(handle, now);
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		return state < PREFETCHED ? TIME_UNKNOWN : nativeGetMediaTime(handle);
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		return nativeGetDuration(handle);
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (state == STARTED) {
			throw new IllegalStateException("player must not be STARTED while changing loop count");
		}
		if (count == 0) {
			throw new IllegalArgumentException("loop count must not be 0");
		}
		nativeSetRepeat(handle, count);
	}

	@Override
	public synchronized int getState() {
		return state;
	}

	@Override
	public synchronized void addPlayerListener(PlayerListener listener) {
		checkClosed();
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	@Override
	public synchronized void removePlayerListener(PlayerListener listener) {
		checkClosed();
		listeners.remove(listener);
	}

	@Override
	public synchronized String getContentType() {
		checkRealized();
		return CONTENT_TYPE;
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
	public synchronized void setMute(boolean mute) {
		if (state == CLOSED || this.mute == mute) {
			return;
		}
		this.mute = mute;
		updateVolume(true);
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
		if (state == CLOSED || this.level == level) {
			return this.level;
		}
		this.level = level;
		updateVolume(true);
		return level;
	}

	@Override
	public synchronized int getLevel() {
		return level;
	}

	private void updateVolume(boolean notify) {
		if (handle == 0) {
			return;
		}
		float gain = mute ? 0.0f : volumeToGain(level);
		nativeSetVolume(handle, gain, gain);
		if (notify) {
			postEvent(PlayerListener.VOLUME_CHANGED, this);
		}
	}

	private static float volumeToGain(int volume) {
		if (volume <= 0) {
			return 0.0f;
		}
		if (volume >= 100) {
			return 1.0f;
		}
		return (float) (1 - (Math.log(100 - volume) / Math.log(100)));
	}

	/** Called by the native PlayerListener. */
	@SuppressWarnings("unused")
	@Keep
	private synchronized void postEvent(int type, long time) {
		if (state == CLOSED) {
			return;
		}
		switch (type) {
			case 1 -> {
				postEvent(PlayerListener.END_OF_MEDIA, time);
				postEvent(PlayerListener.STARTED, 0L);
			}
			case 2 -> {
				state = PREFETCHED;
				postEvent(PlayerListener.END_OF_MEDIA, time);
			}
			case 3 -> {
				state = PREFETCHED;
				reportRuntimeError("WAV_NATIVE_RUNTIME_ERROR_" + time, null);
			}
			default -> {
				// Ignore unknown native event codes.
			}
		}
	}

	private synchronized void postEvent(String event, Object data) {
		for (PlayerListener listener : new ArrayList<>(listeners)) {
			callbackExecutor.execute(() -> listener.playerUpdate(this, event, data));
		}
	}

	private void reportRuntimeError(String code, Throwable error) {
		AudioFailureReporter.report(source.getLocator(), CONTENT_TYPE, "dr_wav",
				AudioFailure.Phase.RUNTIME, code, error);
		if (!errorEventPosted) {
			errorEventPosted = true;
			postEvent(PlayerListener.ERROR, code);
		}
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

	private static native long nativeCreate(String path) throws MediaException;
	private static native void nativeDestroy(long handle);
	private static native void nativeRealize(long handle) throws MediaException;
	private static native void nativePrefetch(long handle) throws MediaException;
	private static native void nativeStart(long handle) throws MediaException;
	private static native void nativePause(long handle) throws MediaException;
	private static native void nativeDeallocate(long handle);
	private static native long nativeSetMediaTime(long handle, long time);
	private static native long nativeGetMediaTime(long handle);
	private static native long nativeGetDuration(long handle);
	private static native void nativeSetRepeat(long handle, int count);
	private static native void nativeSetVolume(long handle, float left, float right);
	private static native void nativeSetListener(long handle, Object listener);
}
