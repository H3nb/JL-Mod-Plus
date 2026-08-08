/*
 * Copyright 2019 Nikita Shakarun
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

import android.media.MediaRecorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.microedition.io.Connector;
import javax.microedition.media.camera.MidletMediaPermissionGate;
import javax.microedition.media.control.RecordControl;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.util.IOUtils;

/** JSR-135 live audio capture Player backed by Android AMR-NB recording. */
public class RecordPlayer extends BasePlayer implements RecordControl {
	static final String CONTENT_TYPE = "audio/amr";
	private static final byte[] AMR_HEADER = "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
	private static final int DESTINATION_NONE = 0;
	private static final int DESTINATION_STREAM = 1;
	private static final int DESTINATION_LOCATION = 2;

	interface RecorderBackend {
		void prepare(File outputFile) throws IOException;

		void start();

		void stop();

		void release();
	}

	interface Dependencies {
		RecorderBackend createRecorder();

		File createTempFile() throws IOException;

		OutputStream openOutputStream(String locator) throws IOException;

		void requireRecordPermission();
	}

	private static final class AndroidRecorderBackend implements RecorderBackend {
		private final MediaRecorder recorder = new MediaRecorder();

		@Override
		public void prepare(File outputFile) throws IOException {
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
			recorder.setAudioSamplingRate(8000);
			recorder.setAudioChannels(1);
			recorder.setOutputFile(outputFile.getAbsolutePath());
			recorder.prepare();
		}

		@Override
		public void start() {
			recorder.start();
		}

		@Override
		public void stop() {
			recorder.stop();
		}

		@Override
		public void release() {
			recorder.release();
		}
	}

	private static final class AndroidDependencies implements Dependencies {
		@Override
		public RecorderBackend createRecorder() {
			return new AndroidRecorderBackend();
		}

		@Override
		public File createTempFile() throws IOException {
			return File.createTempFile("record-audio-", ".amr", ContextHolder.getCacheDir());
		}

		@Override
		public OutputStream openOutputStream(String locator) throws IOException {
			return Connector.openOutputStream(locator);
		}

		@Override
		public void requireRecordPermission() {
			MidletMediaPermissionGate.requireRecordPermission();
		}
	}

	private final Map<String, Control> controls = new HashMap<>();
	private final List<PlayerListener> listeners = new ArrayList<>();
	private final List<File> completedSegments = new ArrayList<>();
	private final Dependencies dependencies;

	private int playerState = UNREALIZED;
	private TimeBase timeBase;
	private OutputStream destination;
	private boolean destinationOwned;
	private int destinationKind = DESTINATION_NONE;
	private boolean recordingRequested;
	private boolean recordingActive;
	private boolean recordingCycleStarted;
	private RecorderBackend recorder;
	private File activeSegment;

	public RecordPlayer(String locator) throws MediaException {
		this(locator, new AndroidDependencies());
	}

	RecordPlayer(String locator, Dependencies dependencies) throws MediaException {
		validateLocator(locator);
		this.dependencies = dependencies;
		controls.put(RecordControl.class.getName(), this);
	}

	private static void validateLocator(String locator) throws MediaException {
		if (locator == null || !locator.startsWith("capture://audio")) {
			throw new MediaException("Unsupported audio capture locator");
		}
		String suffix = locator.substring("capture://audio".length());
		if (suffix.isEmpty()) {
			return;
		}
		if (suffix.charAt(0) != '?' || suffix.length() == 1) {
			throw new MediaException("Invalid audio capture locator");
		}

		boolean encodingSeen = false;
		boolean rateSeen = false;
		boolean channelsSeen = false;
		for (String pair : suffix.substring(1).split("&", -1)) {
			int equals = pair.indexOf('=');
			if (equals <= 0 || equals == pair.length() - 1) {
				throw new MediaException("Malformed audio capture parameter");
			}
			String key = pair.substring(0, equals).toLowerCase(Locale.ROOT);
			String value = pair.substring(equals + 1);
			switch (key) {
				case "encoding" -> {
					if (encodingSeen) {
						throw new MediaException("Duplicate audio capture encoding");
					}
					encodingSeen = true;
					if (!("amr".equalsIgnoreCase(value) || CONTENT_TYPE.equalsIgnoreCase(value))) {
						throw new MediaException("Unsupported audio capture encoding: " + value);
					}
				}
				case "rate" -> {
					if (rateSeen) {
						throw new MediaException("Duplicate audio capture rate");
					}
					rateSeen = true;
					if (!"8000".equals(value)) {
						throw new MediaException("AMR-NB capture requires rate=8000");
					}
				}
				case "channels" -> {
					if (channelsSeen) {
						throw new MediaException("Duplicate audio capture channels");
					}
					channelsSeen = true;
					if (!"1".equals(value)) {
						throw new MediaException("AMR-NB capture requires channels=1");
					}
				}
				default -> throw new MediaException("Unsupported audio capture parameter: " + key);
			}
		}
	}

	@Override
	public synchronized void realize() {
		checkClosed();
		if (playerState == UNREALIZED) {
			playerState = REALIZED;
		}
	}

	@Override
	public synchronized void prefetch() throws MediaException {
		checkClosed();
		if (playerState == STARTED) {
			return;
		}
		if (playerState == UNREALIZED) {
			realize();
		}
		playerState = PREFETCHED;
	}

	@Override
	public synchronized void start() throws MediaException {
		checkClosed();
		if (playerState == STARTED) {
			return;
		}
		if (playerState == UNREALIZED || playerState == REALIZED) {
			prefetch();
		}
		if (recordingRequested) {
			try {
				startPhysicalSegment();
			} catch (MediaException e) {
				recordingRequested = false;
				notifyEvent(PlayerListener.RECORD_ERROR, e);
				throw e;
			}
		}
		playerState = STARTED;
		notifyEvent(PlayerListener.STARTED, Long.valueOf(0));
	}

	@Override
	public synchronized void stop() throws MediaException {
		checkClosed();
		if (playerState != STARTED) {
			return;
		}
		if (recordingActive) {
			try {
				finishPhysicalSegment();
			} catch (MediaException e) {
				notifyEvent(PlayerListener.RECORD_ERROR, e);
				invalidateRecording();
				playerState = PREFETCHED;
				throw e;
			}
		}
		playerState = PREFETCHED;
		notifyEvent(PlayerListener.STOPPED, Long.valueOf(0));
	}

	@Override
	public synchronized void deallocate() {
		checkClosed();
		if (playerState == STARTED) {
			try {
				stop();
			} catch (MediaException e) {
				notifyEvent(PlayerListener.ERROR, e);
			}
		}
		if (playerState == PREFETCHED) {
			playerState = REALIZED;
		}
	}

	@Override
	public synchronized void close() {
		if (playerState == CLOSED) {
			return;
		}
		try {
			reset();
		} catch (IOException ignored) {
			// close() cannot report checked I/O failures; release resources below.
		}
		releaseRecorder();
		closeOwnedDestinationQuietly();
		destination = null;
		destinationKind = DESTINATION_NONE;
		playerState = CLOSED;
		notifyEvent(PlayerListener.CLOSED, null);
		listeners.clear();
	}

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		throw new MediaException("Live audio capture media time cannot be set");
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		return TIME_UNKNOWN;
	}

	@Override
	public synchronized TimeBase getTimeBase() {
		checkRealized();
		return timeBase == null ? Manager.getSystemTimeBase() : timeBase;
	}

	@Override
	public synchronized void setTimeBase(TimeBase master) {
		checkRealized();
		if (playerState == STARTED) {
			throw new IllegalStateException("time base cannot be changed while started");
		}
		timeBase = master;
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		return TIME_UNKNOWN;
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (count == 0 || count < -1) {
			throw new IllegalArgumentException("invalid loop count");
		}
		if (playerState == STARTED) {
			throw new IllegalStateException("loop count cannot be changed while started");
		}
	}

	@Override
	public synchronized int getState() {
		return playerState;
	}

	@Override
	public synchronized void addPlayerListener(PlayerListener listener) {
		checkClosed();
		if (listener == null) {
			throw new IllegalArgumentException("listener must not be null");
		}
		if (!listeners.contains(listener)) {
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
			throw new IllegalArgumentException("control type must not be null");
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
	public synchronized void setRecordStream(OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("record stream must not be null");
		}
		checkRecordControlUsable();
		checkDestinationReplaceable(DESTINATION_STREAM);
		dependencies.requireRecordPermission();
		closeOwnedDestinationQuietly();
		destination = stream;
		destinationOwned = false;
		destinationKind = DESTINATION_STREAM;
	}

	@Override
	public synchronized void setRecordLocation(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		checkRecordControlUsable();
		checkDestinationReplaceable(DESTINATION_LOCATION);
		dependencies.requireRecordPermission();
		OutputStream replacement = dependencies.openOutputStream(locator);
		try {
			closeOwnedDestination();
		} catch (IOException e) {
			try {
				replacement.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
		destination = replacement;
		destinationOwned = true;
		destinationKind = DESTINATION_LOCATION;
	}

	@Override
	public synchronized void startRecord() {
		checkRecordControlUsable();
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested) {
			return;
		}
		recordingCycleStarted = true;
		recordingRequested = true;
		if (playerState == STARTED) {
			try {
				startPhysicalSegment();
			} catch (MediaException e) {
				recordingRequested = false;
				notifyEvent(PlayerListener.RECORD_ERROR, e);
				throw new IllegalStateException("Audio recording could not start", e);
			}
		}
	}

	@Override
	public synchronized void stopRecord() {
		checkRecordControlUsable();
		if (!recordingRequested && !recordingActive) {
			return;
		}
		boolean wasRequested = recordingRequested;
		recordingRequested = false;
		if (recordingActive) {
			try {
				finishPhysicalSegment();
			} catch (MediaException e) {
				notifyEvent(PlayerListener.RECORD_ERROR, e);
				invalidateRecording();
				throw new IllegalStateException("Audio recording could not stop", e);
			}
		}
		if (wasRequested) {
			notifyEvent(PlayerListener.RECORD_STOPPED, null);
		}
	}

	@Override
	public synchronized void commit() throws IOException {
		checkRecordControlUsable();
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested || recordingActive) {
			try {
				stopRecord();
			} catch (IllegalStateException e) {
				invalidateRecording();
				throw new IOException("Audio recording could not be stopped", e);
			}
		}
		if (!recordingCycleStarted || completedSegments.isEmpty()) {
			invalidateRecording();
			throw new IOException("Audio recording was never started");
		}

		IOException failure = null;
		try {
			copySegmentsToDestination();
		} catch (IOException e) {
			failure = e;
		}
		try {
			finishCommittedCycle();
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
		checkRecordControlUsable();
		if (size <= 0) {
			throw new IllegalArgumentException("record size limit must be positive");
		}
		throw new MediaException("Audio record size limit is not supported");
	}

	@Override
	public synchronized void reset() throws IOException {
		if (playerState == CLOSED) {
			return;
		}
		if (recordingRequested || recordingActive) {
			try {
				stopRecord();
			} catch (IllegalStateException e) {
				invalidateRecording();
				throw new IOException("Audio recording could not be reset", e);
			}
		}
		releaseRecorder();
		boolean deleted = deleteSegments();
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		if (!deleted) {
			invalidateRecording();
			throw new IOException("Audio recording could not be erased");
		}
	}

	private void checkClosed() {
		if (playerState == CLOSED) {
			throw new IllegalStateException("Player is closed");
		}
	}

	private void checkRealized() {
		checkClosed();
		if (playerState == UNREALIZED) {
			throw new IllegalStateException("Player is unrealized");
		}
	}

	private void checkRecordControlUsable() {
		checkRealized();
	}

	private void checkDestinationReplaceable(int newKind) {
		if (recordingCycleStarted) {
			throw new IllegalStateException("commit or reset the current recording first");
		}
		if (destinationKind != DESTINATION_NONE && destinationKind != newKind) {
			throw new IllegalStateException("record destination type cannot be changed before commit");
		}
	}

	private void startPhysicalSegment() throws MediaException {
		if (recordingActive) {
			return;
		}
		File segment = null;
		RecorderBackend newRecorder = null;
		try {
			segment = dependencies.createTempFile();
			newRecorder = dependencies.createRecorder();
			newRecorder.prepare(segment);
			newRecorder.start();
			activeSegment = segment;
			recorder = newRecorder;
			recordingActive = true;
			notifyEvent(PlayerListener.RECORD_STARTED, null);
		} catch (IOException | RuntimeException e) {
			if (newRecorder != null) {
				try {
					newRecorder.release();
				} catch (RuntimeException ignored) {
				}
			}
			if (segment != null) {
				segment.delete();
			}
			throw mediaException("Audio recorder could not start", e);
		}
	}

	private void finishPhysicalSegment() throws MediaException {
		if (!recordingActive) {
			return;
		}
		File segment = activeSegment;
		RecorderBackend current = recorder;
		recordingActive = false;
		activeSegment = null;
		recorder = null;
		RuntimeException stopFailure = null;
		try {
			current.stop();
		} catch (RuntimeException e) {
			stopFailure = e;
		} finally {
			try {
				current.release();
			} catch (RuntimeException releaseFailure) {
				if (stopFailure == null) {
					stopFailure = releaseFailure;
				} else {
					stopFailure.addSuppressed(releaseFailure);
				}
			}
		}
		if (stopFailure != null) {
			if (segment != null) {
				segment.delete();
			}
			throw mediaException("Audio recorder could not finalize a segment", stopFailure);
		}
		try {
			validateAmrSegment(segment);
			completedSegments.add(segment);
		} catch (IOException e) {
			if (segment != null) {
				segment.delete();
			}
			throw mediaException("Audio recorder produced invalid AMR data", e);
		}
	}

	private static MediaException mediaException(String message, Throwable cause) {
		MediaException exception = new MediaException(message);
		exception.initCause(cause);
		return exception;
	}

	private static void validateAmrSegment(File segment) throws IOException {
		if (segment == null || segment.length() < AMR_HEADER.length) {
			throw new IOException("AMR recording is empty");
		}
		byte[] header = new byte[AMR_HEADER.length];
		try (FileInputStream input = new FileInputStream(segment)) {
			if (input.read(header) != header.length) {
				throw new IOException("AMR recording header is incomplete");
			}
		}
		for (int i = 0; i < AMR_HEADER.length; i++) {
			if (header[i] != AMR_HEADER[i]) {
				throw new IOException("Unexpected audio recording format");
			}
		}
	}

	private void copySegmentsToDestination() throws IOException {
		for (int index = 0; index < completedSegments.size(); index++) {
			File segment = completedSegments.get(index);
			try (FileInputStream input = new FileInputStream(segment)) {
				if (index > 0) {
					skipExactly(input, AMR_HEADER.length);
				}
				IOUtils.copy(input, destination);
			}
		}
		destination.flush();
	}

	private static void skipExactly(FileInputStream input, int count) throws IOException {
		int remaining = count;
		while (remaining > 0) {
			long skipped = input.skip(remaining);
			if (skipped <= 0) {
				if (input.read() == -1) {
					throw new IOException("AMR segment header is incomplete");
				}
				skipped = 1;
			}
			remaining -= (int) skipped;
		}
	}

	private void finishCommittedCycle() throws IOException {
		IOException failure = null;
		if (destinationOwned && destination != null) {
			try {
				destination.close();
			} catch (IOException e) {
				failure = e;
			}
		}
		destination = null;
		destinationOwned = false;
		destinationKind = DESTINATION_NONE;
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		deleteSegments();
		if (failure != null) {
			throw failure;
		}
	}

	private void invalidateRecording() {
		releaseRecorder();
		deleteSegments();
		closeOwnedDestinationQuietly();
		destination = null;
		destinationOwned = false;
		destinationKind = DESTINATION_NONE;
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
	}

	private boolean deleteSegments() {
		boolean deleted = true;
		if (activeSegment != null) {
			deleted &= !activeSegment.exists() || activeSegment.delete();
			activeSegment = null;
		}
		for (File segment : completedSegments) {
			deleted &= !segment.exists() || segment.delete();
		}
		completedSegments.clear();
		return deleted;
	}

	private void releaseRecorder() {
		RecorderBackend current = recorder;
		recorder = null;
		recordingActive = false;
		if (current != null) {
			try {
				current.release();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void closeOwnedDestination() throws IOException {
		if (destinationOwned && destination != null) {
			destination.close();
		}
	}

	private void closeOwnedDestinationQuietly() {
		try {
			closeOwnedDestination();
		} catch (IOException ignored) {
		}
	}

	private void notifyEvent(String event, Object data) {
		PlayerListener[] snapshot = listeners.toArray(new PlayerListener[0]);
		for (PlayerListener listener : snapshot) {
			try {
				listener.playerUpdate(this, event, data);
			} catch (RuntimeException ignored) {
				// A MIDlet listener must not break the media state machine.
			}
		}
	}
}
