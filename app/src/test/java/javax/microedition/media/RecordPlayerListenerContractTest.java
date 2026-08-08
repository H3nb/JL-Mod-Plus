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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class RecordPlayerListenerContractTest {
	private final List<File> temporaryFiles = new ArrayList<>();

	@After
	public void cleanup() {
		for (File file : temporaryFiles) {
			file.delete();
		}
	}

	@Test
	public void nullListenerIsIgnored() throws Exception {
		RecordPlayer player = new RecordPlayer("capture://audio", new FakeDependencies());
		player.addPlayerListener(null);
		assertEquals(Player.UNREALIZED, player.getState());
		player.close();
	}

	@Test
	public void armedRecordingEventsAreAsynchronousOrderedAndObserveStartedState() throws Exception {
		RecordPlayer player = new RecordPlayer("capture://audio", new FakeDependencies());
		player.realize();
		Thread callerThread = Thread.currentThread();
		List<String> events = new CopyOnWriteArrayList<>();
		List<Integer> states = new CopyOnWriteArrayList<>();
		List<Thread> callbackThreads = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(2);
		player.addPlayerListener((source, event, data) -> {
			events.add(event);
			states.add(source.getState());
			callbackThreads.add(Thread.currentThread());
			latch.countDown();
		});

		RecordControl control = (RecordControl) player.getControl("RecordControl");
		control.setRecordStream(new ByteArrayOutputStream());
		control.startRecord();
		player.start();

		assertTrue("Timed out waiting for asynchronous Player events",
				latch.await(2, TimeUnit.SECONDS));
		assertEquals(PlayerListener.STARTED, events.get(0));
		assertEquals(Player.STARTED, (int) states.get(0));
		assertEquals(PlayerListener.RECORD_STARTED, events.get(1));
		assertEquals(Player.STARTED, (int) states.get(1));
		assertNotSame(callerThread, callbackThreads.get(0));
		assertEquals(callbackThreads.get(0), callbackThreads.get(1));
		player.close();
	}

	private final class FakeDependencies implements RecordPlayer.Dependencies {
		@Override
		public RecordPlayer.RecorderBackend createRecorder() {
			return new FakeRecorder();
		}

		@Override
		public File createTempFile() throws IOException {
			File file = File.createTempFile("record-player-listener-", ".amr");
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

	private static final class FakeRecorder implements RecordPlayer.RecorderBackend {
		private File file;

		@Override
		public void prepare(File outputFile) {
			file = outputFile;
		}

		@Override
		public void start() {
		}

		@Override
		public void stop() {
			try (FileOutputStream output = new FileOutputStream(file)) {
				output.write("#!AMR\nframe".getBytes(StandardCharsets.US_ASCII));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void release() {
		}
	}
}
