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

import javax.microedition.media.control.VideoControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class CameraPlayerTest {
	@Test
	public void realizationExposesOnlyStableJsr135VideoControl() throws Exception {
		CameraPlayer player = new CameraPlayer("capture://video");
		try {
			assertEquals(Player.UNREALIZED, player.getState());
			player.realize();
			assertEquals(Player.REALIZED, player.getState());

			Control shortName = player.getControl("VideoControl");
			Control fullName = player.getControl(VideoControl.class.getName());
			assertNotNull(shortName);
			assertSame(shortName, fullName);
			assertEquals(1, player.getControls().length);
			assertSame(shortName, player.getControls()[0]);

			assertNull(player.getControl("RecordControl"));
			assertNull(player.getControl("CameraControl"));
			assertNull(player.getControl("SnapshotControl"));
			assertThrows(IllegalArgumentException.class, () -> player.getControl(null));
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
}
