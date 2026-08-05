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

import java.util.Locale;

import javax.microedition.amms.control.camera.CameraControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraConfiguration;

/** JSR-234 camera settings that map to the virtual CameraX contract. */
public final class AmmsCameraControl implements CameraControl {
	private static final String[] EXPOSURE_MODES = {"auto"};

	private final CameraPlayer player;
	private final CameraConfiguration configuration;
	private boolean shutterFeedback;
	private String exposureMode = "auto";

	public AmmsCameraControl(CameraPlayer player, CameraConfiguration configuration) {
		this.player = player;
		this.configuration = configuration;
	}

	@Override
	public int getCameraRotation() {
		try {
			return player.getCameraHardwareSession().getCameraRotation();
		} catch (MediaException e) {
			throw unavailable(e);
		}
	}

	@Override
	public void enableShutterFeedback(boolean enable) throws MediaException {
		if (enable) {
			throw new MediaException("Native shutter feedback is not supported");
		}
		shutterFeedback = false;
	}

	@Override
	public boolean isShutterFeedbackEnabled() {
		return shutterFeedback;
	}

	@Override
	public String[] getSupportedExposureModes() {
		return EXPOSURE_MODES.clone();
	}

	@Override
	public void setExposureMode(String mode) {
		if (mode == null) {
			return;
		}
		if (!"auto".equals(mode.toLowerCase(Locale.ROOT))) {
			throw new IllegalArgumentException("unsupported exposure mode: " + mode);
		}
		exposureMode = "auto";
	}

	@Override
	public String getExposureMode() {
		return exposureMode;
	}

	@Override
	public int[] getSupportedVideoResolutions() {
		return CameraConfiguration.supportedResolutions();
	}

	@Override
	public int[] getSupportedStillResolutions() {
		return CameraConfiguration.supportedResolutions();
	}

	@Override
	public void setVideoResolution(int index) {
		configuration.setVideoResolutionIndex(index);
	}

	@Override
	public void setStillResolution(int index) {
		configuration.setStillResolutionIndex(index);
	}

	@Override
	public int getVideoResolution() {
		return configuration.getVideoResolutionIndex();
	}

	@Override
	public int getStillResolution() {
		return configuration.getStillResolutionIndex();
	}

	private static IllegalStateException unavailable(MediaException cause) {
		return new IllegalStateException("Camera hardware controls are unavailable", cause);
	}
}
