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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.microedition.media.camera.CameraSession;
import javax.microedition.media.camera.SnapshotRequest;
import javax.microedition.media.control.RecordControl;
import javax.microedition.media.control.VideoControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class CameraPlayerTest {
	@Test
	public void realizationExposesStableJsr135CameraControls() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		try {
			assertEquals(Player.UNREALIZED, player.getState());
			player.realize();
			assertEquals(Player.REALIZED, player.getState());

			Control videoShortName = player.getControl("VideoControl");
			Control videoFullName = player.getControl(VideoControl.class.getName());
			assertNotNull(videoShortName);
			assertSame(videoShortName, videoFullName);

			Control recordShortName = player.getControl("RecordControl");
			Control recordFullName = player.getControl(RecordControl.class.getName());
			assertNotNull(recordShortName);
			assertSame(recordShortName, recordFullName);

			assertEquals(2, player.getControls().length);
			assertSame(videoShortName, player.getControls()[0]);
			assertSame(recordShortName, player.getControls()[1]);

			assertNull(player.getControl("CameraControl"));
			assertNull(player.getControl("SnapshotControl"));
			assertThrows(IllegalArgumentException.class, () -> player.getControl(null));
		} finally {
			player.close();
		}
	}

	@Test
	public void cameraRecordSizeLimitRejectsInvalidValuesAndReportsUnsupportedFeature() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		try {
			player.realize();
			RecordControl control = (RecordControl) player.getControl(RecordControl.class.getName());

			assertThrows(MediaException.class, () -> control.setRecordSizeLimit(1024));
			assertThrows(MediaException.class, () -> control.setRecordSizeLimit(Integer.MAX_VALUE));
			assertThrows(IllegalArgumentException.class, () -> control.setRecordSizeLimit(0));
			assertThrows(IllegalArgumentException.class, () -> control.setRecordSizeLimit(-1));
		} finally {
			player.close();
		}
	}

	@Test
	public void liveCameraHonorsNonHardwarePlayerContracts() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		TimeBase customTimeBase = () -> 1234L;
		try {
			assertThrows(IllegalStateException.class, player::getTimeBase);
			assertThrows(IllegalStateException.class, () -> player.setTimeBase(customTimeBase));

			player.realize();
			assertSame(Manager.getSystemTimeBase(), player.getTimeBase());
			player.setTimeBase(customTimeBase);
			assertSame(customTimeBase, player.getTimeBase());
			player.setTimeBase(null);
			assertSame(Manager.getSystemTimeBase(), player.getTimeBase());

			assertThrows(MediaException.class, () -> player.setMediaTime(1000L));
			assertThrows(IllegalArgumentException.class, () -> player.setLoopCount(0));
			assertThrows(IllegalArgumentException.class, () -> player.setLoopCount(-2));
			player.setLoopCount(-1);
			player.setLoopCount(1);

			Field state = CameraPlayer.class.getDeclaredField("state");
			state.setAccessible(true);
			state.setInt(player, Player.STARTED);
			player.start();
			assertThrows(IllegalStateException.class, () -> player.setLoopCount(1));
			assertThrows(IllegalStateException.class, () -> player.setTimeBase(customTimeBase));
		} finally {
			player.close();
		}

		assertThrows(IllegalStateException.class, () -> player.getDuration());
		assertThrows(IllegalStateException.class, player::deallocate);
	}

	@Test
	public void deallocateRetainsPreparedSessionForJsr135StandbyResume() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		FakeCameraSession prepared = new FakeCameraSession();
		Field state = CameraPlayer.class.getDeclaredField("state");
		Field session = CameraPlayer.class.getDeclaredField("session");
		state.setAccessible(true);
		session.setAccessible(true);
		try {
			state.setInt(player, Player.PREFETCHED);
			session.set(player, prepared);

			player.deallocate();

			assertEquals(Player.REALIZED, player.getState());
			assertSame(prepared, session.get(player));
			assertFalse(prepared.released);
		} finally {
			player.close();
		}
	}

	@Test
	public void stillImageAliasDoesNotExposeRecordControl() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://image");
		try {
			player.realize();
			assertNotNull(player.getControl(VideoControl.class.getName()));
			assertNull(player.getControl(RecordControl.class.getName()));
			assertEquals(1, player.getControls().length);
		} finally {
			player.close();
		}
	}

	@Test
	public void audioVideoRealizationExposesRecordControlWithoutOpeningHardware() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://audio_video");
		try {
			player.realize();
			assertEquals(Player.REALIZED, player.getState());
			assertNotNull(player.getControl(RecordControl.class.getName()));
		} finally {
			player.close();
		}
	}

	@Test
	public void realizeDoesNotOpenAndroidCamera() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://devcam1");
		try {
			player.realize();
			assertEquals(Player.REALIZED, player.getState());
		} finally {
			player.close();
		}
	}

	@Test
	public void previewCallbacksDoNotTakePlayerLifecycleMonitor() throws Exception {
		Method attach = CameraPlayer.class.getMethod("attachPreview", Object.class);
		Method detach = CameraPlayer.class.getMethod("detachPreview", Object.class);

		assertFalse(Modifier.isSynchronized(attach.getModifiers()));
		assertFalse(Modifier.isSynchronized(detach.getModifiers()));
	}

	@Test
	public void recordingSessionLookupDoesNotInvertPlayerAndRecordControlLocks() throws Exception {
		Method lookup = CameraPlayer.class.getDeclaredMethod("requireRecordingSession");

		assertFalse(Modifier.isSynchronized(lookup.getModifiers()));
	}

	private static final class FakeCameraSession implements CameraSession {
		private boolean released;

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
		public void release() {
			released = true;
		}
	}
}
