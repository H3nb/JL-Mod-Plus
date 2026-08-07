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

import org.junit.Test;

import io.github.h3nb.jlmodplus.config.ProfileModel;

import static org.junit.Assert.assertEquals;

public class CameraRuntimeConfigTest {
	@Test
	public void automaticOverrideDoesNotInheritGlobalFrontCamera() {
		assertEquals(LogicalCameraDevice.DEFAULT,
				CameraRuntimeConfig.resolveProfileDevice(
						ProfileModel.CAMERA_DEVICE_AUTO, LogicalCameraDevice.FRONT));
	}

	@Test
	public void inheritUsesGlobalCameraPreference() {
		assertEquals(LogicalCameraDevice.FRONT,
				CameraRuntimeConfig.resolveProfileDevice(
						ProfileModel.CAMERA_DEVICE_INHERIT, LogicalCameraDevice.FRONT));
	}

	@Test
	public void explicitFacingOverridesGlobalCameraPreference() {
		assertEquals(LogicalCameraDevice.REAR,
				CameraRuntimeConfig.resolveProfileDevice(
						ProfileModel.CAMERA_DEVICE_REAR, LogicalCameraDevice.FRONT));
		assertEquals(LogicalCameraDevice.FRONT,
				CameraRuntimeConfig.resolveProfileDevice(
						ProfileModel.CAMERA_DEVICE_FRONT, LogicalCameraDevice.REAR));
	}
}
