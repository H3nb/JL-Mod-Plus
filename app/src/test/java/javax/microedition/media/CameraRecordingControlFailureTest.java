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

package javax.microedition.media;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;

import javax.microedition.media.camera.CameraRecordingSession;
import javax.microedition.media.camera.CameraSession;
import javax.microedition.media.camera.SnapshotRequest;
import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CameraRecordingControlFailureTest {
	@Test
	public void terminalFinalizeFailureCannotBecomeSuccessfulOnSecondCommit() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		File recordingFile = File.createTempFile("camera-terminal-finalize-", ".mp4");
		try (FileOutputStream output = new FileOutputStream(recordingFile)) {
			output.write(new byte[]{1, 2, 3, 4});
		}
		ByteArrayOutputStream destination = new ByteArrayOutputStream();
		FakeCameraSession prepared = new FakeCameraSession();
		prepared.recording = true;
		prepared.failFinalizeTerminally = true;
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());
			setField(player, "session", prepared);
			setField(control, "destination", destination);
			setField(control, "recordingFile", recordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			assertThrows(IOException.class, control::commit);
			assertEquals(1, prepared.finalizeCalls);
			assertFalse(prepared.recording);
			assertFalse(recordingFile.exists());
			assertEquals(0, destination.size());

			// A terminally invalid cycle was discarded. A second commit must not copy
			// the old temporary MP4, and a new recording requires a new destination.
			assertThrows(IOException.class, control::commit);
			assertThrows(IllegalStateException.class, control::startRecord);
			assertEquals(0, destination.size());
		} finally {
			player.close();
			if (recordingFile.exists()) {
				recordingFile.delete();
			}
		}
	}

	@Test
	public void retryableCommitFailureAllowsNewDestinationAndCleansBackendBeforeRestart() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		File oldRecordingFile = File.createTempFile("camera-retryable-commit-", ".mp4");
		try (FileOutputStream output = new FileOutputStream(oldRecordingFile)) {
			output.write(new byte[]{9, 8, 7});
		}
		ByteArrayOutputStream oldDestination = new ByteArrayOutputStream();
		ByteArrayOutputStream newDestination = new ByteArrayOutputStream();
		FakeCameraSession prepared = new FakeCameraSession();
		prepared.recording = true;
		prepared.failFinalizeRetryably = true;
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());
			setField(player, "session", prepared);
			setIntField(player, "state", Player.STARTED);
			setField(control, "destination", oldDestination);
			setField(control, "recordingFile", oldRecordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			assertThrows(IOException.class, control::commit);
			assertTrue(prepared.recording);
			assertTrue(oldRecordingFile.exists());
			assertEquals(0, oldDestination.size());

			// JSR-135 explicitly requires a new destination after an invalid/I/O-failed
			// commit. Installing it must be allowed even while CameraX cleanup is pending.
			control.setRecordStream(newDestination);

			// The stale backend still gates actual recording until it can be finalized.
			assertThrows(IllegalStateException.class, control::startRecord);
			assertTrue(prepared.recording);
			assertTrue(oldRecordingFile.exists());

			prepared.failFinalizeRetryably = false;
			control.startRecord();
			assertEquals(3, prepared.finalizeCalls);
			assertEquals(1, prepared.beginCalls);
			assertFalse(oldRecordingFile.exists());

			control.reset();
			assertFalse(prepared.recording);
		} finally {
			player.close();
			if (oldRecordingFile.exists()) {
				oldRecordingFile.delete();
			}
		}
	}

	@Test
	public void retryableResetFailureAllowsNewDestination() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		File oldRecordingFile = File.createTempFile("camera-retryable-reset-", ".mp4");
		ByteArrayOutputStream replacement = new ByteArrayOutputStream();
		FakeCameraSession prepared = new FakeCameraSession();
		prepared.recording = true;
		prepared.failFinalizeRetryably = true;
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());
			setField(player, "session", prepared);
			setField(control, "destination", new ByteArrayOutputStream());
			setField(control, "recordingFile", oldRecordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			assertThrows(IOException.class, control::reset);
			assertTrue(prepared.recording);
			assertTrue(oldRecordingFile.exists());

			// reset() has failed, so the old public recording is invalid and a fresh
			// destination must be accepted immediately for the next recording cycle.
			control.setRecordStream(replacement);
			assertThrows(IllegalStateException.class, control::startRecord);
		} finally {
			player.close();
			if (oldRecordingFile.exists()) {
				oldRecordingFile.delete();
			}
		}
	}

	@Test
	public void ownedDestinationCloseFailureIsReportedByCommit() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		File recordingFile = File.createTempFile("camera-close-failure-", ".mp4");
		byte[] expected = new byte[]{5, 6, 7, 8};
		try (FileOutputStream output = new FileOutputStream(recordingFile)) {
			output.write(expected);
		}
		FailingCloseOutputStream destination = new FailingCloseOutputStream();
		FakeCameraSession prepared = new FakeCameraSession();
		prepared.recording = true;
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());
			setField(player, "session", prepared);
			setField(control, "destination", destination);
			setBooleanField(control, "destinationOwned", true);
			setField(control, "recordingFile", recordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			IOException failure = assertThrows(IOException.class, control::commit);
			assertEquals("Camera recording destination could not be closed", failure.getMessage());
			assertArrayEquals(expected, destination.toByteArray());
			assertFalse(recordingFile.exists());
			assertFalse(prepared.recording);
			assertThrows(IllegalStateException.class, control::startRecord);
		} finally {
			player.close();
			if (recordingFile.exists()) {
				recordingFile.delete();
			}
		}
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void setIntField(Object target, String name, int value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.setInt(target, value);
	}

	private static void setBooleanField(Object target, String name, boolean value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.setBoolean(target, value);
	}

	private static final class FailingCloseOutputStream extends ByteArrayOutputStream {
		@Override
		public void close() throws IOException {
			throw new IOException("close failed");
		}
	}

	private static final class FakeCameraSession implements CameraSession, CameraRecordingSession {
		private boolean recording;
		private boolean recordingPaused;
		private boolean failFinalizeTerminally;
		private boolean failFinalizeRetryably;
		private int beginCalls;
		private int finalizeCalls;

		@Override
		public void prepare() {
		}

		@Override
		public void attachPreview(Object previewView) {
		}

		@Override
		public void detachPreview(Object previewView) {
		}

		@Override
		public void start() {
		}

		@Override
		public void stop() {
		}

		@Override
		public byte[] capture(SnapshotRequest request) {
			return new byte[0];
		}

		@Override
		public void beginRecording(File outputFile, boolean withAudio, long fileSizeLimit,
				int width, int height) {
			beginCalls++;
			recording = true;
			recordingPaused = false;
		}

		@Override
		public void pauseRecording() {
			if (recording) {
				recordingPaused = true;
			}
		}

		@Override
		public void resumeRecording() {
			if (recording) {
				recordingPaused = false;
			}
		}

		@Override
		public void finalizeRecording() throws MediaException {
			finalizeCalls++;
			if (failFinalizeTerminally) {
				recording = false;
				recordingPaused = false;
				throw new MediaException("terminal finalize failure");
			}
			if (failFinalizeRetryably) {
				throw new MediaException("retryable finalize failure");
			}
			recording = false;
			recordingPaused = false;
		}

		@Override
		public boolean hasRecording() {
			return recording;
		}

		@Override
		public boolean isRecordingActive() {
			return recording && !recordingPaused;
		}

		@Override
		public void release() {
			recording = false;
			recordingPaused = false;
		}
	}
}
