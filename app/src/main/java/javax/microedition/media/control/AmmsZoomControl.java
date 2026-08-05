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

import javax.microedition.amms.control.camera.ZoomControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraHardwareSession;

/** CameraX digital zoom adapter; optical zoom is explicitly fixed at 1x. */
public final class AmmsZoomControl implements ZoomControl {
	private final CameraPlayer player;

	public AmmsZoomControl(CameraPlayer player) {
		this.player = player;
	}

	@Override
	public int setOpticalZoom(int level) {
		if (level == 100) {
			return 100;
		}
		throw new IllegalArgumentException("optical zoom is not supported");
	}

	@Override
	public int getOpticalZoom() {
		return 100;
	}

	@Override
	public int getMaxOpticalZoom() {
		return 100;
	}

	@Override
	public int getOpticalZoomLevels() {
		return 1;
	}

	@Override
	public int getMinFocalLength() {
		return UNKNOWN;
	}

	@Override
	public int setDigitalZoom(int level) {
		try {
			return hardware().setDigitalZoom(level);
		} catch (MediaException e) {
			throw new IllegalStateException("Camera digital zoom is unavailable", e);
		}
	}

	@Override
	public int getDigitalZoom() {
		try {
			return hardware().getDigitalZoom();
		} catch (MediaException e) {
			throw new IllegalStateException("Camera digital zoom is unavailable", e);
		}
	}

	@Override
	public int getMaxDigitalZoom() {
		try {
			return hardware().getMaxDigitalZoom();
		} catch (MediaException e) {
			return 100;
		}
	}

	@Override
	public int getDigitalZoomLevels() {
		try {
			return hardware().getDigitalZoomLevels();
		} catch (MediaException e) {
			return 1;
		}
	}

	private CameraHardwareSession hardware() throws MediaException {
		return player.getCameraHardwareSession();
	}
}
