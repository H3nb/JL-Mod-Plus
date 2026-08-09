/*
 * Copyright 2020 Nikita Shakarun
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
import android.media.PlaybackParams;

import java.io.IOException;

/**
 * Small state adapter around Android MediaPlayer.
 *
 * <p>JSR-135 realization records the source while prefetch performs the actual
 * platform prepare. This class therefore caches source/seek/volume parameters
 * until {@link #prepareSource()} is called by {@link MicroPlayer#prefetch()}.</p>
 */
public class AndroidPlayer extends MediaPlayer {
	private boolean loaded;
	private String path;
	private float leftVolume, rightVolume;
	private int timePos;
	private boolean looping;
	private float playbackSpeed = 1.0f;

	public AndroidPlayer() {
		super();
		this.leftVolume = 1.0f;
		this.rightVolume = 1.0f;
	}

	@Override
	public void setDataSource(String path) throws IOException, IllegalArgumentException, IllegalStateException, SecurityException {
		this.path = path;
	}

	/** Performs the expensive platform preparation required by JSR-135 prefetch. */
	void prepareSource() throws IOException {
		load();
	}

	@Override
	public void seekTo(int msec) throws IllegalStateException {
		if (msec < 0) {
			msec = 0;
		}
		if (loaded) {
			super.seekTo(msec);
		}
		this.timePos = msec;
	}

	@Override
	public int getCurrentPosition() {
		if (loaded) {
			return super.getCurrentPosition();
		} else {
			return timePos;
		}
	}

	@Override
	public int getDuration() {
		if (loaded) {
			return super.getDuration();
		} else {
			return 0;
		}
	}

	@Override
	public void setLooping(boolean looping) {
		if (loaded) {
			super.setLooping(looping);
		}
		this.looping = looping;
	}

	@Override
	public void setVolume(float leftVolume, float rightVolume) {
		if (loaded) {
			super.setVolume(leftVolume, rightVolume);
		}
		this.leftVolume = leftVolume;
		this.rightVolume = rightVolume;
	}

	@Override
	public void start() throws IllegalStateException {
		if (!loaded) {
			throw new IllegalStateException("MediaPlayer must be prefetched before start()");
		}
		super.start();
	}

	/**
	 * Caches the requested speed before the MediaPlayer is prepared and applies
	 * it once the platform player can accept playback parameters.
	 */
	boolean setPlaybackSpeed(float speed) {
		if (speed <= 0.0f) {
			throw new IllegalArgumentException("speed must be positive");
		}
		playbackSpeed = speed;
		return !loaded || applyPlaybackSpeed();
	}

	void resumePlayback() throws IllegalStateException {
		if (!loaded) {
			throw new IllegalStateException("MediaPlayer must be prefetched before resume");
		}
		super.start();
	}

	/**
	 * Releases prepared platform resources but preserves source and media time so
	 * a later JSR-135 prefetch/start can resume where playback stopped.
	 */
	@Override
	public void reset() {
		if (loaded) {
			try {
				timePos = super.getCurrentPosition();
			} catch (RuntimeException ignored) {
				// Preserve the last cached position if MediaPlayer is already in error.
			}
		}
		if (loaded || path != null) {
			super.reset();
		}
		loaded = false;
	}

	private void load() throws IOException {
		if (loaded) {
			return;
		}
		if (path == null || path.isEmpty()) {
			throw new IOException("Audio data source is empty");
		}

		try {
			super.setDataSource(path);
			super.prepare();
			super.setVolume(leftVolume, rightVolume);
			super.setLooping(looping);
			super.seekTo(timePos);
			if (playbackSpeed != 1.0f && !applyPlaybackSpeed()) {
				playbackSpeed = 1.0f;
			}
			loaded = true;
		} catch (IOException | RuntimeException e) {
			loaded = false;
			try {
				super.reset();
			} catch (RuntimeException ignored) {
				// MediaPlayer may already be in its error state.
			}
			if (e instanceof IOException ioException) {
				throw ioException;
			}
			throw e;
		}
	}

	private boolean applyPlaybackSpeed() {
		try {
			PlaybackParams params = new PlaybackParams()
					.setSpeed(playbackSpeed)
					.setPitch(1.0f)
					.setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT);
			super.setPlaybackParams(params);
			return true;
		} catch (IllegalArgumentException | IllegalStateException e) {
			return false;
		}
	}
}
