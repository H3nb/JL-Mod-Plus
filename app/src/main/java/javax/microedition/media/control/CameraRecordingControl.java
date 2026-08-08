/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.media.control;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.media.camera.MidletMediaPermissionGate;
import javax.microedition.util.ContextHolder;

/** JSR-135 RecordControl backed by one resumable CameraX MP4 recording. */
public final class CameraRecordingControl implements RecordControl {
	private static final String CONTENT_TYPE = "video/mp4";
	private static final int DESTINATION_NONE = 0;
	private static final int DESTINATION_STREAM = 1;
	private static final int DESTINATION_LOCATION = 2;

	private final CameraPlayer player;
	private final boolean withAudio;
	private final int width;
	private final int height;

	private OutputStream destination;
	private boolean destinationOwned;
	private int destinationKind = DESTINATION_NONE;
	private File recordingFile;
	/** Invalid backend file retained only until CameraX can be finalized safely. */
	private File pendingCleanupFile;
	private boolean recordingRequested;
	private boolean recordingActive;
	private boolean recordingCycleStarted;
	/** Set after commit/reset IOException until a destination setter succeeds. */
	private boolean destinationRequiredAfterFailure;

	public CameraRecordingControl(CameraPlayer player, CaptureRequest request) {
		this.player = player;
		this.withAudio = request.isAudioVideo();
		this.width = request.getWidth();
		this.height = request.getHeight();
	}

	@Override
	public synchronized void setRecordStream(OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("record stream must not be null");
		}
		checkDestinationReplaceable(DESTINATION_STREAM);
		MidletMediaPermissionGate.requireRecordPermission();
		prepareDestinationReplacementAfterFailure();
		closeOwnedDestination();
		destination = stream;
		destinationOwned = false;
		destinationKind = DESTINATION_STREAM;
	}

	@Override
	public synchronized void setRecordLocation(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		checkDestinationReplaceable(DESTINATION_LOCATION);
		MidletMediaPermissionGate.requireRecordPermission();
		OutputStream newDestination;
		try {
			newDestination = Connector.openOutputStream(locator);
		} catch (IllegalArgumentException e) {
			throw new MediaException("Invalid or unsupported record location: " + locator);
		}
		prepareDestinationReplacementAfterFailure();
		closeOwnedDestination();
		destination = newDestination;
		destinationOwned = true;
		destinationKind = DESTINATION_LOCATION;
	}

	@Override
	public String getContentType() {
		return CONTENT_TYPE;
	}

	@Override
	public synchronized void startRecord() {
		if (destination == null || destinationRequiredAfterFailure) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested) {
			return;
		}
		recordingCycleStarted = true;
		recordingRequested = true;
		if (player.getState() == Player.STARTED) {
			startOrResumePhysicalRecording();
		}
	}

	@Override
	public synchronized void stopRecord() {
		if (!recordingRequested && !recordingActive) {
			return;
		}
		boolean wasRequested = recordingRequested;
		recordingRequested = false;
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
				recordingActive = false;
			} catch (MediaException e) {
				invalidateRuntimeRecording();
				player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
				return;
			}
		}
		if (wasRequested) {
			player.notifyRecordingEvent(PlayerListener.RECORD_STOPPED, null);
		}
	}

	@Override
	public synchronized void commit() throws IOException {
		if (destinationRequiredAfterFailure) {
			throw new IOException("Current camera recording is invalid; set a new destination");
		}
		if (recordingRequested || recordingActive) {
			stopForFinalization();
		}
		if (recordingFile == null) {
			invalidateAfterIoFailure(false);
			throw new IOException("Camera recording was never started");
		}

		boolean finalized;
		try {
			finalized = player.finalizeCameraRecording();
		} catch (MediaException e) {
			invalidateAfterIoFailure(player.hasCameraRecording());
			throw new IOException("Camera recording could not be finalized", e);
		}
		if (!finalized) {
			invalidateAfterIoFailure(false);
			throw new IOException("Camera recording backend no longer exists");
		}
		recordingActive = false;

		IOException failure = null;
		try {
			copyRecordingToDestination();
		} catch (IOException e) {
			failure = e;
		}
		try {
			finishCommittedRecordingCycle();
		} catch (IOException e) {
			if (failure == null) {
				failure = e;
			} else {
				failure.addSuppressed(e);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	@Override
	public synchronized int setRecordSizeLimit(int size) throws MediaException {
		if (size <= 0) {
			throw new IllegalArgumentException("record size limit must be positive");
		}
		// CameraX can stop its temporary file at a byte limit, but this control
		// cannot yet perform the implicit JSR-135 commit when that limit is reached.
		// Report the optional feature as unsupported instead of exposing partial semantics.
		throw new MediaException("Camera record size limit is not supported yet");
	}

	@Override
	public synchronized void reset() throws IOException {
		if (destinationRequiredAfterFailure) {
			cleanupPendingRecordingQuietly();
			return;
		}
		if (destination == null && recordingFile == null) {
			return;
		}
		if (recordingRequested || recordingActive) {
			stopForFinalization();
		}
		if (recordingFile == null) {
			recordingCycleStarted = false;
			return;
		}
		try {
			// reset() only needs the backend recording to be gone; a false result means
			// CameraX has already discarded it and the private temp file can be erased.
			player.finalizeCameraRecording();
		} catch (MediaException e) {
			invalidateAfterIoFailure(player.hasCameraRecording());
			throw new IOException("Camera recording could not be reset", e);
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		deleteRecordingFile();
	}

	/** Starts or resumes an armed recording after the Player enters STARTED. */
	public synchronized void onPlayerStarted() {
		if (recordingRequested && !recordingActive) {
			startOrResumePhysicalRecording();
		}
	}

	/** Puts an active recording into MMAPI standby before Player.stop() unbinds the camera. */
	public synchronized void onPlayerStopping() {
		if (!recordingActive) {
			return;
		}
		try {
			player.pauseCameraRecording();
			recordingActive = false;
		} catch (MediaException e) {
			// Keep the requested/active state until CameraSession.stop() makes its own
			// authoritative pause attempt. CameraPlayer calls onPlayerStopped() only
			// after that stop succeeds.
			player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
		}
	}

	/** Synchronizes RecordControl state after CameraSession.stop() has succeeded. */
	public synchronized void onPlayerStopped() {
		if (recordingRequested) {
			recordingActive = false;
		}
	}

	/** Discards an unfinished recording before camera resources are deallocated. */
	public synchronized void onPlayerDeallocated() {
		discardCurrentRecording(false);
	}

	/** Implements RecordControl.reset-on-close and releases an emulator-owned destination. */
	public synchronized void onPlayerClosed() {
		if (recordingRequested || recordingActive) {
			stopForFinalization();
		}
		discardCurrentRecording(true);
	}

	private boolean startOrResumePhysicalRecording() {
		boolean resuming = recordingFile != null;
		if (!resuming && pendingCleanupFile != null) {
			try {
				cleanupPendingRecordingBeforeStart();
			} catch (MediaException e) {
				recordingActive = false;
				recordingRequested = false;
				recordingCycleStarted = false;
				player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
				return false;
			}
		}
		try {
			if (!resuming) {
				recordingFile = File.createTempFile(
						"jlmod-camera-record-", ".mp4", ContextHolder.getCacheDir());
				player.beginCameraRecording(recordingFile, withAudio, Long.MAX_VALUE, width, height);
			} else {
				player.resumeCameraRecording();
			}
			recordingActive = true;
			player.notifyRecordingEvent(PlayerListener.RECORD_STARTED, null);
			return true;
		} catch (IOException | MediaException | RuntimeException e) {
			if (!resuming) {
				boolean finalized = false;
				try {
					finalized = player.finalizeCameraRecording();
				} catch (MediaException cleanupFailure) {
					e.addSuppressed(cleanupFailure);
				}
				if (finalized || !player.hasCameraRecording()) {
					deleteRecordingFile();
				} else if (recordingFile != null) {
					deletePendingCleanupFile();
					pendingCleanupFile = recordingFile;
					recordingFile = null;
				}
				recordingCycleStarted = false;
			} else if (!player.hasCameraRecording()) {
				deleteRecordingFile();
				recordingCycleStarted = false;
			}
			recordingActive = false;
			recordingRequested = false;
			player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
			return false;
		}
	}

	/**
	 * Implements the stop portion of implicit commit/reset. Finalization below is
	 * still authoritative when CameraX pause itself reports an error.
	 */
	private void stopForFinalization() {
		boolean wasRequested = recordingRequested;
		recordingRequested = false;
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
				recordingActive = false;
			} catch (MediaException e) {
				player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
			}
		}
		if (wasRequested) {
			player.notifyRecordingEvent(PlayerListener.RECORD_STOPPED, null);
		}
	}

	private void copyRecordingToDestination() throws IOException {
		try (FileInputStream input = new FileInputStream(recordingFile)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (count > 0) {
					destination.write(buffer, 0, count);
				}
			}
			destination.flush();
		}
	}

	private void cleanupPendingRecordingBeforeStart() throws MediaException {
		if (pendingCleanupFile == null) {
			return;
		}
		try {
			player.finalizeCameraRecording();
		} catch (MediaException e) {
			if (!player.hasCameraRecording()) {
				deletePendingCleanupFile();
				return;
			}
			throw e;
		}
		// A false result also means the stale backend is already gone. Either way,
		// the private temp file is no longer needed before the next recording starts.
		deletePendingCleanupFile();
	}

	private void cleanupPendingRecordingQuietly() {
		if (pendingCleanupFile == null) {
			return;
		}
		try {
			player.finalizeCameraRecording();
		} catch (MediaException ignored) {
			if (player.hasCameraRecording()) {
				return;
			}
		}
		deletePendingCleanupFile();
	}

	/** Discards a runtime recording error while preserving the installed destination. */
	private void invalidateRuntimeRecording() {
		if (recordingFile != null || player.hasCameraRecording()) {
			try {
				player.finalizeCameraRecording();
			} catch (MediaException ignored) {
				// The Player/session release path remains the final cleanup fallback.
			}
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		deleteRecordingFile();
	}

	/** Invalidates a recording after the IOException contracts of commit/reset. */
	private void invalidateAfterIoFailure(boolean backendMayStillExist) {
		if (backendMayStillExist && recordingFile != null) {
			deletePendingCleanupFile();
			pendingCleanupFile = recordingFile;
			recordingFile = null;
		} else {
			deleteRecordingFile();
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		destinationRequiredAfterFailure = true;
	}

	private void discardCurrentRecording(boolean closeDestination) {
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
			} catch (MediaException ignored) {
				// The recording is discarded below regardless of the pause result.
			}
		}
		if (recordingFile != null || pendingCleanupFile != null || player.hasCameraRecording()) {
			try {
				player.finalizeCameraRecording();
			} catch (MediaException ignored) {
				// Player release is the final fallback for a backend that cannot finalize.
			}
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		destinationRequiredAfterFailure = false;
		deleteRecordingFile();
		deletePendingCleanupFile();
		if (closeDestination) {
			closeOwnedDestination();
			destination = null;
			destinationOwned = false;
			destinationKind = DESTINATION_NONE;
		}
	}

	private void finishCommittedRecordingCycle() throws IOException {
		deleteRecordingFile();
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		destinationRequiredAfterFailure = false;

		IOException closeFailure = null;
		if (destinationOwned && destination != null) {
			try {
				destination.close();
			} catch (IOException e) {
				closeFailure = new IOException("Camera recording destination could not be closed", e);
			}
		}
		destination = null;
		destinationOwned = false;
		destinationKind = DESTINATION_NONE;
		if (closeFailure != null) {
			throw closeFailure;
		}
	}

	private void checkDestinationReplaceable(int requestedKind) {
		if (destinationRequiredAfterFailure) {
			return;
		}
		if (recordingCycleStarted || recordingFile != null || recordingRequested || recordingActive) {
			throw new IllegalStateException("commit or reset the current recording first");
		}
		if (destinationKind != DESTINATION_NONE && destinationKind != requestedKind) {
			throw new IllegalStateException("commit before changing the record destination type");
		}
	}

	private void prepareDestinationReplacementAfterFailure() {
		if (!destinationRequiredAfterFailure) {
			return;
		}
		if (recordingFile != null) {
			deletePendingCleanupFile();
			pendingCleanupFile = recordingFile;
			recordingFile = null;
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		destinationRequiredAfterFailure = false;
	}

	private void deleteRecordingFile() {
		File file = recordingFile;
		recordingFile = null;
		deleteFile(file);
	}

	private void deletePendingCleanupFile() {
		File file = pendingCleanupFile;
		pendingCleanupFile = null;
		deleteFile(file);
	}

	private static void deleteFile(File file) {
		if (file != null && file.exists() && !file.delete()) {
			file.deleteOnExit();
		}
	}

	private void closeOwnedDestination() {
		if (!destinationOwned || destination == null) {
			return;
		}
		try {
			destination.close();
		} catch (IOException ignored) {
			// Destination cleanup is best effort outside commit error paths.
		}
	}
}
