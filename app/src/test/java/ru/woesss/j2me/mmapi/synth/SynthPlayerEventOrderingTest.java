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

package ru.woesss.j2me.mmapi.synth;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.media.Control;
import javax.microedition.media.Manager;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SynthPlayerEventOrderingTest {
	@Test
	public void startedPrecedesEndOfMediaGeneratedInsideNativeStart() throws Exception {
		FakeLibrary library = new FakeLibrary(2);
		SynthPlayer player = new SynthPlayer(library, new FakeDataSource());
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(2);
		player.addPlayerListener((ignored, event, data) -> {
			if (PlayerListener.STARTED.equals(event) || PlayerListener.END_OF_MEDIA.equals(event)) {
				events.add(event);
				latch.countDown();
			}
		});

		try {
			player.start();
			assertTrue("timed out waiting for ordered start/EOM callbacks",
					latch.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(PlayerListener.STARTED, PlayerListener.END_OF_MEDIA), events);
		} finally {
			player.close();
		}
	}

	@Test
	public void fatalNativeErrorIsDeliveredBeforeClosed() throws Exception {
		FakeLibrary library = new FakeLibrary(3);
		SynthPlayer player = new SynthPlayer(library, new FakeDataSource());
		List<String> events = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(3);
		player.addPlayerListener((ignored, event, data) -> {
			if (PlayerListener.STARTED.equals(event)
					|| PlayerListener.ERROR.equals(event)
					|| PlayerListener.CLOSED.equals(event)) {
				events.add(event);
				latch.countDown();
			}
		});

		try {
			player.start();
			assertTrue("timed out waiting for fatal error callbacks",
					latch.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(PlayerListener.STARTED, PlayerListener.ERROR, PlayerListener.CLOSED), events);
			assertEquals(1, library.closeCalls);
		} finally {
			player.close();
		}
	}

	private static final class FakeDataSource extends DataSource {
		FakeDataSource() {
			super(Manager.MIDI_DEVICE_LOCATOR);
		}

		@Override
		public String getContentType() {
			return "audio/midi";
		}

		@Override
		public void connect() throws IOException {
		}

		@Override
		public void disconnect() {
		}

		@Override
		public void start() throws IOException {
		}

		@Override
		public void stop() throws IOException {
		}

		@Override
		public SourceStream[] getStreams() {
			return new SourceStream[0];
		}

		@Override
		public Control[] getControls() {
			return new Control[0];
		}

		@Override
		public Control getControl(String controlType) {
			return null;
		}
	}

	private static final class FakeLibrary implements Library {
		private final int eventDuringStart;
		private Object nativeListener;
		int closeCalls;

		FakeLibrary(int eventDuringStart) {
			this.eventDuringStart = eventDuringStart;
		}

		@Override
		public void loadSoundBank(String soundBank) {
		}

		@Override
		public long createPlayer(String locator) {
			return 1L;
		}

		@Override
		public void finalize(long handle) {
		}

		@Override
		public void realize(long handle) {
		}

		@Override
		public void prefetch(long handle) {
		}

		@Override
		public void start(long handle) {
			invokeNativeEvent(eventDuringStart, 123_000L);
		}

		@Override
		public void pause(long handle) {
		}

		@Override
		public void deallocate(long handle) {
		}

		@Override
		public void close(long handle) {
			closeCalls++;
		}

		@Override
		public long setMediaTime(long handle, long now) {
			return now;
		}

		@Override
		public long getMediaTime(long handle) {
			return 0L;
		}

		@Override
		public void setRepeat(long handle, int count) {
		}

		@Override
		public void setVolume(long handle, float left, float right) {
		}

		@Override
		public long getDuration(long handle) {
			return 1_000_000L;
		}

		@Override
		public void setListener(long handle, Object listener) {
			nativeListener = listener;
		}

		@Override
		public void setDataSource(long handle, byte[] data) {
		}

		@Override
		public int writeMIDI(long handle, byte[] data, int offset, int length) {
			return length;
		}

		@Override
		public boolean hasToneControl() {
			return true;
		}

		private void invokeNativeEvent(int type, long time) {
			try {
				Method callback = nativeListener.getClass().getDeclaredMethod("postEvent", int.class, long.class);
				callback.setAccessible(true);
				callback.invoke(nativeListener, type, time);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}
	}
}
