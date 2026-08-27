/*
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

package javax.microedition.shell.transform;

import java.util.Map;

/**
 * Universal compatibility marker for an installed MIDlet's transformed bytecode.
 *
 * <p>Increment {@link #TRANSFORM_VERSION} whenever the converter changes emitted bytecode or a
 * runtime bridge contract used by that bytecode. Installed MIDlets with an older or missing marker
 * are then rebuilt by the normal app reconversion path when their retained source is available.</p>
 */
public final class MidletTransformMetadata {
	/** Version 1 includes guest-time virtualization and suppression of advisory explicit GC. */
	public static final int TRANSFORM_VERSION = 1;
	public static final String TRANSFORM_VERSION_ATTRIBUTE = "JLMod-Transform-Version";
	private static final String LEGACY_TIMING_TRANSFORM_ATTRIBUTE =
			"JLMod-Timing-Transform-Version";
	private static final String LEGACY_TIMING_BRIDGE_ATTRIBUTE = "JLMod-Timing-Bridge-ABI";

	private MidletTransformMetadata() {
	}

	public static void mark(Map<String, String> attributes) {
		if (attributes == null) {
			throw new NullPointerException("attributes");
		}
		attributes.remove(LEGACY_TIMING_TRANSFORM_ATTRIBUTE);
		attributes.remove(LEGACY_TIMING_BRIDGE_ATTRIBUTE);
		attributes.put(TRANSFORM_VERSION_ATTRIBUTE, Integer.toString(TRANSFORM_VERSION));
	}

	public static boolean isCompatible(Map<String, String> attributes) {
		return attributes != null
				&& Integer.toString(TRANSFORM_VERSION).equals(
						attributes.get(TRANSFORM_VERSION_ATTRIBUTE));
	}
}
