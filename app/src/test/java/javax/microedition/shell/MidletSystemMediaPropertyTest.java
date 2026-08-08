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

package javax.microedition.shell;

import org.junit.Test;

import javax.microedition.media.camera.VirtualCameraCapabilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Verifies the System.getProperty delegate visible to converted MIDlets. */
public class MidletSystemMediaPropertyTest {
	private static final String[] MANAGED_MEDIA_KEYS = {
			"supports.video.capture",
			"supports.audio.capture",
			"supports.recording",
			"audio.encoding",
			"audio.encodings",
			"video.encoding",
			"video.encodings",
			"video.snapshot.encoding",
			"video.snapshot.encodings"
	};

	@Test
	public void midletSystemReturnsRuntimeManagedMediaProperties() {
		for (String key : MANAGED_MEDIA_KEYS) {
			assertEquals(key,
					VirtualCameraCapabilities.systemProperty(key),
					MidletSystem.getProperty(key));
		}
	}

	@Test
	public void unavailableManagedPropertyNeverLeaksHostJvmValue() {
		String key = "video.snapshot.encodings";
		String previous = System.getProperty(key);
		try {
			System.setProperty(key, "host-value-that-must-not-leak");
			String managed = VirtualCameraCapabilities.systemProperty(key);
			assertEquals(managed, MidletSystem.getProperty(key));
			if (managed == null) {
				assertNull(MidletSystem.getProperty(key));
			}
		} finally {
			restoreProperty(key, previous);
		}
	}

	@Test
	public void managedDefaultOverloadUsesCallerDefaultOnlyWhenCapabilityIsAbsent() {
		String key = "audio.encodings";
		String managed = VirtualCameraCapabilities.systemProperty(key);
		String fallback = "fallback-value";
		assertEquals(managed == null ? fallback : managed,
				MidletSystem.getProperty(key, fallback));
	}

	private static void restoreProperty(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}
}
