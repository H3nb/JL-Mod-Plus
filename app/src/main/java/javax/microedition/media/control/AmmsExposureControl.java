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

import javax.microedition.amms.control.camera.ExposureControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraHardwareSession;

/** CameraX exposure-compensation adapter with explicit automatic-only fallbacks. */
public final class AmmsExposureControl implements ExposureControl {
	private static final String[] METERINGS = new String[0];

	private final CameraPlayer player;

	public AmmsExposureControl(CameraPlayer player) {
		this.player = player;
	}

	@Override
	public int[] getSupportedFStops() {
		return new int[]{0};
	}

	@Override
	public int getFStop() {
		return 0;
	}

	@Override
	public void setFStop(int aperture) throws MediaException {
		if (aperture != 0) {
			throw new MediaException("Manual aperture is not supported by the CameraX backend");
		}
	}

	@Override
	public int getMinExposureTime() {
		return 0;
	}

	@Override
	public int getMaxExposureTime() {
		return 0;
	}

	@Override
	public int getExposureTime() {
		return 0;
	}

	@Override
	public int setExposureTime(int time) throws MediaException {
		if (time != 0) {
			throw new MediaException("Manual exposure time is not supported by the CameraX backend");
		}
		return 0;
	}

	@Override
	public int[] getSupportedISOs() {
		return new int[]{0};
	}

	@Override
	public int getISO() {
		return 0;
	}

	@Override
	public void setISO(int iso) throws MediaException {
		if (iso != 0) {
			throw new MediaException("Manual ISO is not supported by the CameraX backend");
		}
	}

	@Override
	public int[] getSupportedExposureCompensations() {
		try {
			return hardware().getSupportedExposureCompensations();
		} catch (MediaException e) {
			return new int[]{0};
		}
	}

	@Override
	public int getExposureCompensation() {
		try {
			return hardware().getExposureCompensation();
		} catch (MediaException e) {
			throw unavailable(e);
		}
	}

	@Override
	public void setExposureCompensation(int ec) throws MediaException {
		hardware().setExposureCompensation(ec);
	}

	@Override
	public int getExposureValue() {
		return getExposureCompensation();
	}

	@Override
	public String[] getSupportedLightMeterings() {
		return METERINGS.clone();
	}

	@Override
	public void setLightMetering(String metering) {
		throw new IllegalArgumentException("light metering selection is not supported");
	}

	@Override
	public String getLightMetering() {
		return null;
	}

	private CameraHardwareSession hardware() throws MediaException {
		return player.getCameraHardwareSession();
	}

	private static IllegalStateException unavailable(MediaException cause) {
		return new IllegalStateException("Camera exposure controls are unavailable", cause);
	}
}
