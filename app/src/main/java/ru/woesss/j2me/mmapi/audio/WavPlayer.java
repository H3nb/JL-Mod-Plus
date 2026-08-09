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

import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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
	private static final String TAG = WavPlayer.class.getSimpleName();
	private static final String CONTENT_TYPE = "audio/x-wav";

	private static final class NativeEvent {
		final int type;
		final long time;

		NativeEvent(int type, long time) {
			this.type = type;
			this.time = time;
		}
	}

	private final DataSource source;
	private final Map<String, Control> controls = new HashMap<>();
	private final ArrayList<PlayerListener> listeners = new ArrayList<>();
	private final ArrayList<NativeEvent> pendingStartEvents = new ArrayList<>();
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
	private boolean starting;

	static {
		// mmapi_common owns JNI_OnLoad and records the JavaVM used by the native
		// PlayerListener bridge. Load it explicitly before mmapi_wav: merely being
		// a dynamic-linker dependency does not guarantee JNI_OnLoad is invoked.
		System.loadLibrary("c++_shared");
		System.loadLibrary("oboe");
		System.loadLibrary("mmapi_common");
		System.loadLibrary("mmapi_wav");
	}

	public WavPlayer(DataSource source) throws MediaException {
		if (source == null || source.getLocator() == null) {
			throw new IllegalArgumentException("WAV source has no locator");
		}
		this.source = source;
		try {
			handle = nativeCreate(source.getLocator());
		} catch (MediaException e) {
			throw enrichCreateFailure(source.getLocator(), e);
		}
		if (handle == 0) {
			throw enrichCreateFailure(source.getLocator(),
					new MediaException("Unable to create WAV player"));
		}
		nativeSetListener(handle, this);
		controls.put(VolumeControl.class.getName(), this);
	}

	private static MediaException enrichCreateFailure(String path, MediaException error) {
		String message = error.getMessage();
		if (message == null || message.isEmpty()) {
			message = "Unable to create WAV player";
		}
		try {
			WavFileFormat.Info info = WavFileFormat.inspect(new File(path));
			if (info != null) {
				message += "; " + info.describe();
			}
		} catch (IOException | RuntimeException ignored) {
			// The original playback failure remains useful even if inspection fails.
		}
		return new MediaException(message);
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
	public void start() throws MediaException {
		prefetch();

		synchronized (this) {
			if (state != PREFETCHED) {
				return;
			}
			starting = true;
			pendingStartEvents.clear();
		}

		try {
			nativeStart(handle);
		} catch (MediaException | RuntimeException e) {
			synchronized (this) {
				starting = false;
				pendingStartEvents.clear();
				state = PREFETCHED;
			}
			throw e;
		}

		synchronized (this) {
			state = STARTED;
			errorEventPosted = false;
			enqueuePlayerEvent(PlayerListener.STARTED, nativeGetMediaTime(handle));
			starting = false;
			for (NativeEvent event : pendingStartEvents) {
				enqueueNativeEvent(event.type, event.time);
			}
			pendingStartEvents.clear();
		}
	}

	@Override
	public synchronized void stop() throws MediaException {
		checkClosed();
		if (state == STARTED) {
			nativePause(handle);
			state = PREFETCHED;
			enqueuePlayerEvent(PlayerListener.STOPPED, nativeGetMediaTime(handle));
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
		starting = false;
		pendingStartEvents.clear();
		long nativeHandle = handle;
		handle = 0;
		if (nativeHandle != 0) {
			nativeDestroy(nativeHandle);
		}
		source.disconnect();
		enqueuePlayerEvent(PlayerListener.CLOSED, null);
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
		if (count == 0 || count < -1) {
			throw new IllegalArgumentException("loop count must be positive or -1");
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
			enqueuePlayerEvent(PlayerListener.VOLUME_CHANGED, this);
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

	/**
	 * JNI entry point. Native audio callbacks only enqueue work here; user code
	 * and native destruction run later on the Java callback executor.
	 */
	@SuppressWarnings("unused")
	@Keep
	private void postEvent(int type, long time) {
		synchronized (this) {
			if (state == CLOSED) {
				return;
			}
			if (starting) {
				pendingStartEvents.add(new NativeEvent(type, time));
				return;
			}
		}
		enqueueNativeEvent(type, time);
	}

	private void enqueueNativeEvent(int type, long time) {
		enqueueCallback(() -> handleNativeEvent(type, time));
	}

	private void handleNativeEvent(int type, long time) {
		synchronized (this) {
			if (state == CLOSED) {
				return;
			}
			if (type == 2) {
				state = PREFETCHED;
			}
		}

		switch (type) {
			case 1 -> {
				dispatchPlayerEvent(PlayerListener.END_OF_MEDIA, time);
				dispatchPlayerEvent(PlayerListener.STARTED, 0L);
			}
			case 2 -> dispatchPlayerEvent(PlayerListener.END_OF_MEDIA, time);
			case 3 -> {
				String code = "WAV_NATIVE_RUNTIME_ERROR_" + time;
				reportRuntimeError(code, null, false);
				dispatchPlayerEvent(PlayerListener.ERROR, code);
				close();
			}
			default -> Log.w(TAG, "Ignoring unknown native WAV event " + type);
		}
	}

	private void enqueuePlayerEvent(String event, Object data) {
		enqueueCallback(() -> dispatchPlayerEvent(event, data));
	}

	private void enqueueCallback(Runnable callback) {
		try {
			callbackExecutor.execute(callback);
		} catch (RejectedExecutionException ignored) {
			// A callback racing with close() has no observable Player state left.
		}
	}

	private void dispatchPlayerEvent(String event, Object data) {
		ArrayList<PlayerListener> snapshot;
		synchronized (this) {
			snapshot = new ArrayList<>(listeners);
		}
		for (PlayerListener listener : snapshot) {
			try {
				listener.playerUpdate(this, event, data);
			} catch (Throwable e) {
				Log.e(TAG, "PlayerListener failed for event " + event, e);
			}
		}
	}

	private void reportRuntimeError(String code, Throwable error) {
		reportRuntimeError(code, error, true);
	}

	private void reportRuntimeError(String code, Throwable error, boolean notify) {
		AudioFailureReporter.report(source.getLocator(), CONTENT_TYPE, "dr_wav",
				AudioFailure.Phase.RUNTIME, code, error);
		if (notify && !errorEventPosted) {
			errorEventPosted = true;
			enqueuePlayerEvent(PlayerListener.ERROR, code);
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
