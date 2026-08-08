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
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.microedition.media.camera.CameraRecordingSession;
import javax.microedition.media.camera.CameraSession;
import javax.microedition.media.camera.SnapshotRequest;
import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

			assertThrows(IOException.class, control::commit);
			assertThrows(IllegalStateException.class, control::startRecord);
			assertEquals(1, prepared.finalizeCalls);
			assertEquals(0, destination.size());
		} finally {
			player.close();
			if (recordingFile.exists()) {
				recordingFile.delete();
			}
		}
	}

	@Test
	public void retryableCommitFailureQuarantinesBackendAndRequiresNewDestination() throws Exception {
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
			assertEquals(1, prepared.finalizeCalls);
			assertTrue(prepared.recording);
			assertTrue(oldRecordingFile.exists());
			assertNull(fieldValue(control, "recordingFile"));
			assertEquals(oldRecordingFile, fieldValue(control, "pendingCleanupFile"));
			assertEquals(0, oldDestination.size());
			assertThrows(IOException.class, control::commit);
			assertThrows(IllegalStateException.class, control::startRecord);

			// The failed recording no longer blocks replacement before the permission gate.
			assertThrows(SecurityException.class, () -> control.setRecordStream(newDestination));
			invokeNoArg(control, "prepareDestinationReplacementAfterFailure");
			setField(control, "destination", newDestination);

			// Cleanup failure is reported asynchronously by production code and must not
			// leak the previous IllegalStateException contract from startRecord().
			control.startRecord();
			assertEquals(2, prepared.finalizeCalls);
			assertEquals(0, prepared.beginCalls);
			assertTrue(prepared.recording);

			prepared.failFinalizeRetryably = false;
			invokeNoArg(control, "cleanupPendingRecordingBeforeStart");
			assertEquals(3, prepared.finalizeCalls);
			assertFalse(oldRecordingFile.exists());
			assertFalse(prepared.recording);
		} finally {
			player.close();
			if (oldRecordingFile.exists()) {
				oldRecordingFile.delete();
			}
		}
	}

	@Test
	public void resetIOExceptionInvalidatesRecordingAndRequiresNewDestination() throws Exception {
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
			setIntField(player, "state", Player.STARTED);
			setField(control, "destination", new ByteArrayOutputStream());
			setField(control, "recordingFile", oldRecordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			assertThrows(IOException.class, control::reset);
			assertTrue(prepared.recording);
			assertEquals(oldRecordingFile, fieldValue(control, "pendingCleanupFile"));
			assertThrows(IllegalStateException.class, control::startRecord);

			// A later reset may clean stale backend resources but must not restore the
			// failed recording or remove the new-destination requirement.
			control.reset();
			assertThrows(IllegalStateException.class, control::startRecord);
			assertThrows(SecurityException.class, () -> control.setRecordStream(replacement));

			prepared.failFinalizeRetryably = false;
			invokeNoArg(control, "cleanupPendingRecordingBeforeStart");
			assertFalse(oldRecordingFile.exists());
			assertFalse(prepared.recording);
		} finally {
			player.close();
			if (oldRecordingFile.exists()) {
				oldRecordingFile.delete();
			}
		}
	}

	@Test
	public void missingBackendCannotTurnStaleTempFileIntoSuccessfulCommit() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		File recordingFile = File.createTempFile("camera-missing-backend-", ".mp4");
		try (FileOutputStream output = new FileOutputStream(recordingFile)) {
			output.write(new byte[]{4, 3, 2, 1});
		}
		ByteArrayOutputStream destination = new ByteArrayOutputStream();
		FakeCameraSession prepared = new FakeCameraSession();
		prepared.recording = false;
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());
			setField(player, "session", prepared);
			setField(control, "destination", destination);
			setField(control, "recordingFile", recordingFile);
			setBooleanField(control, "recordingCycleStarted", true);

			IOException failure = assertThrows(IOException.class, control::commit);
			assertEquals("Camera recording backend no longer exists", failure.getMessage());
			assertEquals(1, prepared.finalizeCalls);
			assertEquals(0, destination.size());
			assertFalse(recordingFile.exists());
			assertThrows(IOException.class, control::commit);
		} finally {
			player.close();
			if (recordingFile.exists()) {
				recordingFile.delete();
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

	private static Object fieldValue(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
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

	private static void invokeNoArg(Object target, String name) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(target);
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
		public boolean finalizeRecording() throws MediaException {
			finalizeCalls++;
			if (failFinalizeTerminally) {
				recording = false;
				recordingPaused = false;
				throw new MediaException("terminal finalize failure");
			}
			if (failFinalizeRetryably) {
				throw new MediaException("retryable finalize failure");
			}
			boolean existed = recording;
			recording = false;
			recordingPaused = false;
			return existed;
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
