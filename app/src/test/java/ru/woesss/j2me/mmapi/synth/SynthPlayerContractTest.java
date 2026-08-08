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

import javax.microedition.media.Control;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Locks down the JSR-135 lifecycle contract at the synth-player boundary.
 *
 * <p>The fake native library keeps these tests independent from SONiVOX/TSF so
 * backend upgrades can be validated against the same Player semantics.</p>
 */
public class SynthPlayerContractTest {
	@Test
	public void lifecycleMovesThroughRequiredStates() throws Exception {
		FakeLibrary library = new FakeLibrary();
		SynthPlayer player = newPlayer(library);
		try {
			assertEquals(Player.UNREALIZED, player.getState());

			player.realize();
			assertEquals(Player.REALIZED, player.getState());
			assertEquals(1, library.realizeCalls);

			player.prefetch();
			assertEquals(Player.PREFETCHED, player.getState());
			assertEquals(1, library.prefetchCalls);

			player.start();
			assertEquals(Player.STARTED, player.getState());
			assertEquals(1, library.startCalls);

			player.stop();
			assertEquals(Player.PREFETCHED, player.getState());
			assertEquals(1, library.pauseCalls);

			player.deallocate();
			assertEquals(Player.REALIZED, player.getState());
			assertEquals(1, library.deallocateCalls);
		} finally {
			player.close();
		}

		assertEquals(Player.CLOSED, player.getState());
		assertEquals(1, library.closeCalls);
	}

	@Test
	public void startImplicitlyRealizesAndPrefetches() throws Exception {
		FakeLibrary library = new FakeLibrary();
		SynthPlayer player = newPlayer(library);
		try {
			player.start();

			assertEquals(Player.STARTED, player.getState());
			assertEquals(1, library.realizeCalls);
			assertEquals(1, library.prefetchCalls);
			assertEquals(1, library.startCalls);
		} finally {
			player.close();
		}
	}

	@Test
	public void stopPreservesCurrentMediaTimeForResume() throws Exception {
		FakeLibrary library = new FakeLibrary();
		library.mediaTime = 420_000L;
		SynthPlayer player = newPlayer(library);
		try {
			player.start();
			player.stop();

			assertEquals(Player.PREFETCHED, player.getState());
			assertEquals(420_000L, player.getMediaTime());
		} finally {
			player.close();
		}
	}

	@Test
	public void controlsRequireRealizeAndMidiDeviceExposesOnlyFunctionalControls() throws Exception {
		FakeLibrary library = new FakeLibrary();
		SynthPlayer player = newPlayer(library);
		try {
			try {
				player.getControls();
				fail("getControls() must reject UNREALIZED players");
			} catch (IllegalStateException expected) {
				// Required by JSR-135.
			}

			player.realize();

			assertNotNull(player.getControl("VolumeControl"));
			assertNotNull(player.getControl("MIDIControl"));
			assertNull(player.getControl("ToneControl"));
			assertNull(player.getControl("javax.microedition.amms.control.audioeffect.EqualizerControl"));
		} finally {
			player.close();
		}
	}

	@Test
	public void toneDeviceExposesToneControlButNotMidiControl() throws Exception {
		FakeLibrary library = new FakeLibrary();
		SynthPlayer player = newPlayer(library, Manager.TONE_DEVICE_LOCATOR);
		try {
			player.realize();

			assertNotNull(player.getControl("VolumeControl"));
			assertNotNull(player.getControl("ToneControl"));
			assertNull(player.getControl("MIDIControl"));
		} finally {
			player.close();
		}
	}

	@Test
	public void loopCountRejectsZeroAndChangesWhileStarted() throws Exception {
		FakeLibrary library = new FakeLibrary();
		SynthPlayer player = newPlayer(library);
		try {
			try {
				player.setLoopCount(0);
				fail("loop count 0 must be rejected");
			} catch (IllegalArgumentException expected) {
				// Required by JSR-135.
			}

			player.start();
			try {
				player.setLoopCount(2);
				fail("setLoopCount() must reject STARTED players");
			} catch (IllegalStateException expected) {
				// Required by JSR-135.
			}
		} finally {
			player.close();
		}
	}

	@Test
	public void repeatedCloseReleasesNativePlayerAndDataSourceOnlyOnce() {
		FakeLibrary library = new FakeLibrary();
		FakeDataSource dataSource = new FakeDataSource(Manager.MIDI_DEVICE_LOCATOR);
		SynthPlayer player = new SynthPlayer(library, dataSource);

		player.close();
		player.close();

		assertEquals(Player.CLOSED, player.getState());
		assertEquals(1, library.closeCalls);
		assertEquals(1, dataSource.disconnectCalls);
	}

	private static SynthPlayer newPlayer(FakeLibrary library) {
		return newPlayer(library, Manager.MIDI_DEVICE_LOCATOR);
	}

	private static SynthPlayer newPlayer(FakeLibrary library, String locator) {
		return new SynthPlayer(library, new FakeDataSource(locator));
	}

	private static final class FakeDataSource extends DataSource {
		int disconnectCalls;

		FakeDataSource(String locator) {
			super(locator);
		}

		@Override
		public String getContentType() {
			return Manager.TONE_DEVICE_LOCATOR.equals(getLocator()) ? "audio/x-tone-seq" : "audio/midi";
		}

		@Override
		public void connect() throws IOException {
		}

		@Override
		public void disconnect() {
			disconnectCalls++;
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
		public Control getControl(String control) {
			return null;
		}
	}

	private static final class FakeLibrary implements Library {
		int realizeCalls;
		int prefetchCalls;
		int startCalls;
		int pauseCalls;
		int deallocateCalls;
		int closeCalls;
		long mediaTime;
		long duration = 1_000_000L;

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
			realizeCalls++;
		}

		@Override
		public void prefetch(long handle) {
			prefetchCalls++;
		}

		@Override
		public void start(long handle) {
			startCalls++;
		}

		@Override
		public void pause(long handle) {
			pauseCalls++;
		}

		@Override
		public void deallocate(long handle) {
			deallocateCalls++;
		}

		@Override
		public void close(long handle) {
			closeCalls++;
		}

		@Override
		public long setMediaTime(long handle, long now) {
			mediaTime = now;
			return mediaTime;
		}

		@Override
		public long getMediaTime(long handle) {
			return mediaTime;
		}

		@Override
		public void setRepeat(long handle, int count) {
		}

		@Override
		public void setVolume(long handle, float left, float right) {
		}

		@Override
		public long getDuration(long handle) {
			return duration;
		}

		@Override
		public void setListener(long handle, Object listener) {
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
	}
}
