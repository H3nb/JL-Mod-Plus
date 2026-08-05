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

package javax.microedition.media.camera;

import javax.microedition.media.MediaException;

/** Internal, platform-neutral camera controls backed by the active session. */
public interface CameraHardwareSession {
	int getCameraRotation() throws MediaException;

	boolean hasFlashUnit() throws MediaException;

	int getFlashMode() throws MediaException;

	void setFlashMode(int mode) throws MediaException;

	boolean isAutoFocusSupported() throws MediaException;

	int setFocus(int distance) throws MediaException;

	int getFocus() throws MediaException;

	boolean isMacroSupported() throws MediaException;

	void setMacro(boolean enable) throws MediaException;

	boolean getMacro() throws MediaException;

	int[] getSupportedExposureCompensations() throws MediaException;

	int getExposureCompensation() throws MediaException;

	void setExposureCompensation(int value) throws MediaException;

	int getDigitalZoom() throws MediaException;

	int getMaxDigitalZoom() throws MediaException;

	int getDigitalZoomLevels() throws MediaException;

	int setDigitalZoom(int level) throws MediaException;
}
