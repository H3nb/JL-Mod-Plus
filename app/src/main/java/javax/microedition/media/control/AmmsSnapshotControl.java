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

package javax.microedition.media.control;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.amms.control.camera.SnapshotControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.SnapshotEncodingParser;
import javax.microedition.util.ContextHolder;

/**
 * JSR-234 burst snapshot adapter. Files are written to a validated directory
 * and never exposed through a silent in-memory-only implementation.
 */
public final class AmmsSnapshotControl implements SnapshotControl {
	private final CameraPlayer player;
	private final AtomicBoolean stopRequested = new AtomicBoolean();

	private File directory;
	private String prefix;
	private String suffix;
	private Thread burstThread;
	private File pendingConfirmation;

	public AmmsSnapshotControl(CameraPlayer player) {
		this.player = player;
	}

	@Override
	public synchronized void setDirectory(String newDirectory) {
		File resolved = resolveDirectory(newDirectory);
		if (!resolved.isDirectory()) {
			throw new IllegalArgumentException("snapshot directory does not exist");
		}
		directory = resolved;
	}

	@Override
	public synchronized String getDirectory() {
		File current = currentDirectory();
		return current.getAbsolutePath() + File.separator;
	}

	@Override
	public synchronized void setFilePrefix(String newPrefix) {
		validateNamePart(newPrefix, "prefix");
		prefix = newPrefix;
	}

	@Override
	public synchronized String getFilePrefix() {
		return prefix;
	}

	@Override
	public synchronized void setFileSuffix(String newSuffix) {
		validateNamePart(newSuffix, "suffix");
		suffix = newSuffix;
	}

	@Override
	public synchronized String getFileSuffix() {
		return suffix;
	}

	@Override
	public synchronized void start(int maxShots) {
		if (maxShots < 1 && maxShots != FREEZE && maxShots != FREEZE_AND_CONFIRM) {
			throw new IllegalArgumentException("maxShots must be positive or a freeze mode");
		}
		if (player.getState() != javax.microedition.media.Player.STARTED) {
			throw new IllegalStateException("Player must be STARTED before burst shooting");
		}
		if (prefix == null || suffix == null) {
			throw new IllegalStateException("setFilePrefix and setFileSuffix before start");
		}
		if (burstThread != null) {
			throw new IllegalStateException("burst shooting is already active");
		}

		final int shots = maxShots < 0 ? 1 : maxShots;
		final boolean confirm = maxShots == FREEZE_AND_CONFIRM;
		final File targetDirectory = currentDirectory();
		if (!targetDirectory.isDirectory()) {
			throw new IllegalStateException("snapshot directory does not exist");
		}
		final String targetPrefix = prefix;
		final String targetSuffix = suffix;
		stopRequested.set(false);
		Thread thread = new Thread(() -> captureBurst(
				targetDirectory, targetPrefix, targetSuffix, shots, confirm),
				"J2ME-CameraBurst");
		thread.setDaemon(true);
		burstThread = thread;
		thread.start();
	}

	@Override
	public synchronized void stop() {
		stopRequested.set(true);
		Thread thread = burstThread;
		if (thread != null) {
			thread.interrupt();
		}
		deletePendingConfirmation();
	}

	@Override
	public synchronized void unfreeze(boolean save) {
		if (pendingConfirmation == null) {
			return;
		}
		if (!save) {
			deletePendingConfirmation();
			return;
		}
		pendingConfirmation = null;
		burstThread = null;
		player.notifyCameraEvent(SHOOTING_STOPPED, null);
	}

	private void captureBurst(File targetDirectory, String targetPrefix, String targetSuffix,
			int maxShots, boolean confirm) {
		int sequence = 0;
		try {
			for (int shot = 0; shot < maxShots && !stopRequested.get(); shot++) {
				byte[] image = player.takeSnapshot(SnapshotEncodingParser.parse(null));
				File output = createOutput(targetDirectory, targetPrefix, targetSuffix, sequence);
				sequence++;
				try (FileOutputStream stream = new FileOutputStream(output)) {
					stream.write(image);
					stream.flush();
				}
				if (confirm) {
					synchronized (this) {
						pendingConfirmation = output;
						burstThread = null;
					}
					return;
				}
			}
			finishBurst(true);
		} catch (IOException | MediaException | RuntimeException e) {
			if (!stopRequested.get()) {
				player.notifyCameraEvent(STORAGE_ERROR, e);
			}
			finishBurst(false);
		}
	}

	private synchronized void finishBurst(boolean notify) {
		burstThread = null;
		if (notify) {
			player.notifyCameraEvent(SHOOTING_STOPPED, null);
		}
	}

	private synchronized void deletePendingConfirmation() {
		File pending = pendingConfirmation;
		pendingConfirmation = null;
		if (pending != null && pending.exists() && !pending.delete()) {
			pending.deleteOnExit();
		}
		if (burstThread == null && stopRequested.get()) {
			stopRequested.set(false);
		}
	}

	private static File createOutput(File targetDirectory, String targetPrefix,
			String targetSuffix, int sequence) throws IOException {
		int current = sequence;
		while (current < 1000000) {
			String number = String.format(java.util.Locale.ROOT, "%04d", current);
			File output = new File(targetDirectory, targetPrefix + number + targetSuffix);
			if (output.createNewFile()) {
				return output;
			}
			current++;
		}
		throw new IOException("snapshot filename space is exhausted");
	}

	private static File defaultDirectory() {
		return ContextHolder.getCacheDir();
	}

	private synchronized File currentDirectory() {
		if (directory == null) {
			directory = defaultDirectory();
		}
		return directory;
	}

	private static File resolveDirectory(String value) {
		if (value == null) {
			throw new IllegalArgumentException("snapshot directory must not be null");
		}
		try {
			if (value.startsWith("file:")) {
				return new File(new URI(value));
			}
			return new File(value);
		} catch (IllegalArgumentException | URISyntaxException e) {
			throw new IllegalArgumentException("invalid snapshot directory", e);
		}
	}

	private static void validateNamePart(String value, String name) {
		if (value == null || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
				|| value.indexOf(File.separatorChar) >= 0) {
			throw new IllegalArgumentException("invalid snapshot " + name);
		}
	}
}
