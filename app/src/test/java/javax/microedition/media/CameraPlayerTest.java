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

import javax.microedition.amms.control.ImageFormatControl;
import javax.microedition.amms.control.camera.CameraControl;
import javax.microedition.amms.control.camera.ExposureControl;
import javax.microedition.amms.control.camera.FlashControl;
import javax.microedition.amms.control.camera.FocusControl;
import javax.microedition.amms.control.camera.SnapshotControl;
import javax.microedition.amms.control.camera.ZoomControl;
import javax.microedition.media.control.VideoControl;
import javax.microedition.media.control.RecordControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CameraPlayerTest {
	@Test
	public void constructionAndRealizationDoNotNeedAndroidCameraAccess() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		try {
			assertEquals(Player.UNREALIZED, player.getState());
			player.realize();
			assertEquals(Player.REALIZED, player.getState());
			assertNotNull(player.getControl(VideoControl.class.getName()));
			assertTrue(player.getControl("VideoControl") instanceof VideoControl);
		} finally {
			player.close();
		}
	}

	@Test
	public void combinedCaptureExposesRecordingControlWithoutOpeningCamera() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://audio_video");
		try {
			player.realize();
			assertTrue(player.getControl("RecordControl") instanceof RecordControl);
			assertEquals("video/mp4", ((RecordControl) player.getControl("RecordControl"))
					.getContentType());
		} finally {
			player.close();
		}
	}

	@Test
	public void realizationExposesCameraControlContractsWithoutOpeningHardware() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		try {
			player.realize();
			assertTrue(player.getControl("CameraControl") instanceof CameraControl);
			assertTrue(player.getControl("SnapshotControl") instanceof SnapshotControl);
			assertTrue(player.getControl("FocusControl") instanceof FocusControl);
			assertTrue(player.getControl("ExposureControl") instanceof ExposureControl);
			assertTrue(player.getControl("FlashControl") instanceof FlashControl);
			assertTrue(player.getControl("ZoomControl") instanceof ZoomControl);
			assertTrue(player.getControl("ImageFormatControl") instanceof ImageFormatControl);

			CameraControl camera = (CameraControl) player.getControl("CameraControl");
			camera.setStillResolution(0);
			assertEquals(0, camera.getStillResolution());
			ImageFormatControl imageFormat =
					(ImageFormatControl) player.getControl("ImageFormatControl");
			assertEquals(75, imageFormat.setParameter("quality", 75));
			assertEquals(75, imageFormat.getIntParameterValue("quality"));
		} finally {
			player.close();
		}
	}
}
