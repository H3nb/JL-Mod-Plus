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
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RecordPlayerFailureTest {
	private final List<File> temporaryFiles = new ArrayList<>();

	@After
	public void cleanup() {
		for (File file : temporaryFiles) {
			file.delete();
		}
	}

	@Test
	public void stopRecordFailureInvalidatesDestinationAndPostsRecordError() throws Exception {
		FailingDependencies dependencies = new FailingDependencies();
		RecordPlayer player = new RecordPlayer("capture://audio", dependencies);
		player.realize();
		player.start();

		List<String> events = new CopyOnWriteArrayList<>();
		player.addPlayerListener((source, event, data) -> events.add(event));
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();

		control.stopRecord();
		awaitEvent(events, PlayerListener.RECORD_ERROR);
		assertThrows(IllegalStateException.class, control::startRecord);
		player.close();
	}

	@Test
	public void playerStopFailureReturnsToPrefetchedAndInvalidatesDestination() throws Exception {
		FailingDependencies dependencies = new FailingDependencies();
		RecordPlayer player = new RecordPlayer("capture://audio", dependencies);
		player.realize();
		player.start();

		List<String> events = new CopyOnWriteArrayList<>();
		player.addPlayerListener((source, event, data) -> events.add(event));
		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();

		assertThrows(MediaException.class, player::stop);
		assertEquals(Player.PREFETCHED, player.getState());
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

	private final class FailingDependencies implements RecordPlayer.Dependencies {
		@Override
		public RecordPlayer.RecorderBackend createRecorder() {
			return new FailingRecorder();
		}

		@Override
		public File createTempFile() throws IOException {
			File file = File.createTempFile("record-player-failure-", ".amr");
			temporaryFiles.add(file);
			return file;
		}

		@Override
		public OutputStream openOutputStream(String locator) {
			return new ByteArrayOutputStream();
		}

		@Override
		public void requireRecordPermission() {
		}
	}

	private static final class FailingRecorder implements RecordPlayer.RecorderBackend {
		private boolean started;

		@Override
		public void prepare(File outputFile) {
		}

		@Override
		public void start() {
			started = true;
		}

		@Override
		public void stop() {
			if (!started) {
				throw new IllegalStateException("not started");
			}
			throw new IllegalStateException("synthetic finalization failure");
		}

		@Override
		public void release() {
			started = false;
		}
	}
}
