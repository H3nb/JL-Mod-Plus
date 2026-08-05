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

package javax.microedition.media.control;

import javax.microedition.amms.control.camera.FlashControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraHardwareSession;

/** CameraX still-capture flash adapter for JSR-234. */
public final class AmmsFlashControl implements FlashControl {
	private final CameraPlayer player;

	public AmmsFlashControl(CameraPlayer player) {
		this.player = player;
	}

	@Override
	public int[] getSupportedModes() {
		try {
			if (hardware().hasFlashUnit()) {
				return new int[]{OFF, AUTO, FORCE};
			}
			return new int[]{OFF};
		} catch (MediaException e) {
			return new int[]{OFF};
		}
	}

	@Override
	public void setMode(int mode) {
		if (mode != OFF && mode != AUTO && mode != FORCE) {
			throw new IllegalArgumentException("unsupported flash mode: " + mode);
		}
		try {
			hardware().setFlashMode(mode);
		} catch (MediaException e) {
			throw new IllegalStateException("Camera flash is unavailable", e);
		}
	}

	@Override
	public int getMode() {
		try {
			return hardware().getFlashMode();
		} catch (MediaException e) {
			throw new IllegalStateException("Camera flash is unavailable", e);
		}
	}

	@Override
	public boolean isFlashReady() {
		try {
			return hardware().hasFlashUnit();
		} catch (MediaException e) {
			return false;
		}
	}

	private CameraHardwareSession hardware() throws MediaException {
		return player.getCameraHardwareSession();
	}
}
