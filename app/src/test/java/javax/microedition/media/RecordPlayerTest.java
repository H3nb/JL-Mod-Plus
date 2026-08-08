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

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RecordPlayerTest {
	private final List<File> temporaryFiles = new ArrayList<>();

	@After
	public void cleanup() {
		for (File file : temporaryFiles) {
			file.delete();
		}
		temporaryFiles.clear();
	}

	@Test
	public void playerLifecycleMatchesJsr135States() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = new RecordPlayer("capture://audio", dependencies);

		assertEquals(Player.UNREALIZED, player.getState());
		assertThrows(IllegalStateException.class, () -> player.getControl("RecordControl"));
		assertThrows(IllegalStateException.class, player::getContentType);

		player.realize();
		assertEquals(Player.REALIZED, player.getState());
		assertSame(player, player.getControl("RecordControl"));
		assertEquals(RecordPlayer.CONTENT_TYPE, player.getContentType());

		player.prefetch();
		assertEquals(Player.PREFETCHED, player.getState());
		player.start();
		assertEquals(Player.STARTED, player.getState());
		player.stop();
		assertEquals(Player.PREFETCHED, player.getState());
		player.deallocate();
		assertEquals(Player.REALIZED, player.getState());
		player.close();
		assertEquals(Player.CLOSED, player.getState());
	}

	@Test
	public void startRecordWaitsForPlayerStartAndResumesAcrossPlayerStops() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		List<String> events = new CopyOnWriteArrayList<>();
		player.addPlayerListener((source, event, data) -> events.add(event));

		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(output);
		control.startRecord();
		assertEquals(0, dependencies.recorders.size());

		player.start();
		assertEquals(1, dependencies.recorders.size());
		awaitEvent(events, PlayerListener.RECORD_STARTED);

		player.stop();
		assertEquals(1, dependencies.recorders.get(0).stopCount);

		player.start();
		assertEquals(2, dependencies.recorders.size());
		control.commit();

		assertArrayEquals(
				"#!AMR\nsegment-1segment-2".getBytes(StandardCharsets.US_ASCII),
				output.toByteArray());
		awaitEvent(events, PlayerListener.RECORD_STOPPED);
		player.close();
	}

	@Test
	public void stopRecordIsIdempotentAndCanResumeInSameCycle() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(output);
		player.start();

		control.startRecord();
		control.startRecord();
		assertEquals(1, dependencies.recorders.size());
		control.stopRecord();
		control.stopRecord();
		assertEquals(1, dependencies.recorders.get(0).stopCount);

		control.startRecord();
		assertEquals(2, dependencies.recorders.size());
		control.commit();
		assertArrayEquals(
				"#!AMR\nsegment-1segment-2".getBytes(StandardCharsets.US_ASCII),
				output.toByteArray());
		player.close();
	}

	@Test
	public void recordingDestinationCannotChangeDuringUncommittedCycle() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();

		assertThrows(IllegalStateException.class,
				() -> control.setRecordStream(new ByteArrayOutputStream()));
		assertThrows(IllegalStateException.class,
				() -> control.setRecordLocation("memory://replacement"));
		player.close();
	}

	@Test
	public void resetErasesCurrentRecordingButKeepsDestination() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(output);
		player.start();
		control.startRecord();
		control.stopRecord();
		control.reset();

		control.startRecord();
		control.commit();
		assertArrayEquals(
				"#!AMR\nsegment-2".getBytes(StandardCharsets.US_ASCII),
				output.toByteArray());
		player.close();
	}

	@Test
	public void commitWithoutPhysicalRecordingInvalidatesDestination() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();

		assertThrows(IOException.class, control::commit);
		assertThrows(IllegalStateException.class, control::startRecord);
		player.close();
	}

	@Test
	public void destinationSetterChecksMidletRecordPermission() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		RecordControl control = (RecordControl) player.getControl("RecordControl");

		control.setRecordStream(new ByteArrayOutputStream());
		assertEquals(1, dependencies.permissionChecks);
		player.close();
	}

	@Test
	public void invalidRecordLocationRuntimeSyntaxFailureBecomesMediaException() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		dependencies.failLocationWithIllegalArgument = true;
		RecordPlayer player = realizedPlayer(dependencies);
		RecordControl control = (RecordControl) player.getControl("RecordControl");

		assertThrows(MediaException.class,
				() -> control.setRecordLocation("file:///AudioCaptureSmokeTest.amr"));
		player.close();
	}

	@Test
	public void recordSizeLimitRejectsInvalidAndReportsUnsupported() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		RecordControl control = (RecordControl) player.getControl("RecordControl");

		assertThrows(IllegalArgumentException.class, () -> control.setRecordSizeLimit(0));
		assertThrows(MediaException.class, () -> control.setRecordSizeLimit(1024));
		player.close();
	}

	@Test
	public void audioLocatorAcceptsDefaultAndAmrFormsOnly() throws Exception {
		new RecordPlayer("capture://audio", new FakeDependencies()).close();
		new RecordPlayer("capture://audio?encoding=amr", new FakeDependencies()).close();
		new RecordPlayer("capture://audio?encoding=audio/amr", new FakeDependencies()).close();
		new RecordPlayer(
				"capture://audio?encoding=audio/amr&rate=8000&channels=1",
				new FakeDependencies()).close();

		assertThrows(MediaException.class,
				() -> new RecordPlayer("capture://audio?encoding=pcm", new FakeDependencies()));
		assertThrows(MediaException.class,
				() -> new RecordPlayer("capture://audio?rate=16000", new FakeDependencies()));
		assertThrows(MediaException.class,
				() -> new RecordPlayer("capture://audio?bits=16", new FakeDependencies()));
	}

	@Test
	public void backendFailurePostsRecordErrorAndDoesNotLeaveRecorderActive() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		dependencies.failNextStart = true;
		RecordPlayer player = realizedPlayer(dependencies);
		List<String> events = new CopyOnWriteArrayList<>();
		player.addPlayerListener((source, event, data) -> events.add(event));
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();

		assertThrows(MediaException.class, player::start);
		awaitEvent(events, PlayerListener.RECORD_ERROR);
		assertFalse(dependencies.recorders.get(0).started);
		assertThrows(IllegalStateException.class, control::startRecord);
		player.close();
	}

	@Test
	public void immediateStartRecordBackendFailureUsesRecordErrorInsteadOfUncheckedException() throws Exception {
		FakeDependencies dependencies = new FakeDependencies();
		RecordPlayer player = realizedPlayer(dependencies);
		player.start();
		List<String> events = new CopyOnWriteArrayList<>();
		player.addPlayerListener((source, event, data) -> events.add(event));
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		dependencies.failNextStart = true;

		control.startRecord();
		awaitEvent(events, PlayerListener.RECORD_ERROR);
		assertThrows(IllegalStateException.class, control::startRecord);
		player.close();
	}

	private static void awaitEvent(List<String> events, String event) throws InterruptedException {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!events.contains(event) && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertTrue("Timed out waiting for " + event + "; events=" + events, events.contains(event));
	}

	private RecordPlayer realizedPlayer(FakeDependencies dependencies) throws Exception {
		RecordPlayer player = new RecordPlayer("capture://audio", dependencies);
		player.realize();
		assertNotNull(player.getControl("RecordControl"));
		return player;
	}

	private final class FakeDependencies implements RecordPlayer.Dependencies {
		final List<FakeRecorder> recorders = new ArrayList<>();
		int permissionChecks;
		boolean failNextStart;
		boolean failLocationWithIllegalArgument;

		@Override
		public RecordPlayer.RecorderBackend createRecorder() {
			FakeRecorder recorder = new FakeRecorder(recorders.size() + 1, failNextStart);
			failNextStart = false;
			recorders.add(recorder);
			return recorder;
		}

		@Override
		public File createTempFile() throws IOException {
			File file = File.createTempFile("record-player-test-", ".amr");
			temporaryFiles.add(file);
			return file;
		}

		@Override
		public OutputStream openOutputStream(String locator) {
			if (failLocationWithIllegalArgument) {
				throw new IllegalArgumentException("synthetic invalid locator");
			}
			return new ByteArrayOutputStream();
		}

		@Override
		public void requireRecordPermission() {
			permissionChecks++;
		}
	}

	private static final class FakeRecorder implements RecordPlayer.RecorderBackend {
		private final int index;
		private final boolean failStart;
		private File file;
		private boolean started;
		private int stopCount;

		FakeRecorder(int index, boolean failStart) {
			this.index = index;
			this.failStart = failStart;
		}

		@Override
		public void prepare(File outputFile) {
			file = outputFile;
		}

		@Override
		public void start() {
			if (failStart) {
				throw new IllegalStateException("synthetic start failure");
			}
			started = true;
		}

		@Override
		public void stop() {
			stopCount++;
			if (!started) {
				throw new IllegalStateException("not started");
			}
			try (FileOutputStream output = new FileOutputStream(file)) {
				output.write("#!AMR\n".getBytes(StandardCharsets.US_ASCII));
				output.write(("segment-" + index).getBytes(StandardCharsets.US_ASCII));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			started = false;
		}

		@Override
		public void release() {
			started = false;
		}
	}
}
