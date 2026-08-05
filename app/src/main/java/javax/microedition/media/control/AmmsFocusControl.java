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

import javax.microedition.amms.control.camera.FocusControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraHardwareSession;

/** CameraX autofocus adapter for the JSR-234 FocusControl contract. */
public final class AmmsFocusControl implements FocusControl {
	private final CameraPlayer player;

	public AmmsFocusControl(CameraPlayer player) {
		this.player = player;
	}

	@Override
	public int setFocus(int distance) throws MediaException {
		return hardware().setFocus(distance);
	}

	@Override
	public int getFocus() {
		try {
			return hardware().getFocus();
		} catch (MediaException e) {
			throw unavailable(e);
		}
	}

	@Override
	public int getMinFocus() {
		return isAutoFocusSupported() ? AUTO : UNKNOWN;
	}

	@Override
	public int getFocusSteps() {
		return 0;
	}

	@Override
	public boolean isManualFocusSupported() {
		return false;
	}

	@Override
	public boolean isAutoFocusSupported() {
		try {
			return hardware().isAutoFocusSupported();
		} catch (MediaException e) {
			return false;
		}
	}

	@Override
	public boolean isMacroSupported() {
		try {
			return hardware().isMacroSupported();
		} catch (MediaException e) {
			return false;
		}
	}

	@Override
	public void setMacro(boolean enable) throws MediaException {
		hardware().setMacro(enable);
	}

	@Override
	public boolean getMacro() {
		try {
			return hardware().getMacro();
		} catch (MediaException e) {
			throw unavailable(e);
		}
	}

	private CameraHardwareSession hardware() throws MediaException {
		return player.getCameraHardwareSession();
	}

	private static IllegalStateException unavailable(MediaException cause) {
		return new IllegalStateException("Camera focus controls are unavailable", cause);
	}
}
