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

package javax.microedition.shell.timing;

import java.util.Map;

import javax.microedition.shell.GuestTimingBridge;

/**
 * Timing-specific compatibility marker stored in the converted descriptor next to the DEX
 * artifact.
 *
 * <p>This is intentionally not a universal "converted" bit. A future bytecode feature must use
 * its own namespaced transform/ABI entries, or participate in an explicit transform manifest, so
 * that enabling or revving one feature cannot make an unrelated transformed artifact appear
 * compatible.</p>
 */
public final class TimingTransformMetadata {
	/** Version 6 virtualizes Date.class.newInstance() with caller-aware access checks. */
	public static final int TRANSFORM_VERSION = 6;
	public static final String TRANSFORM_VERSION_ATTRIBUTE = "JLMod-Timing-Transform-Version";
	public static final String BRIDGE_ABI_ATTRIBUTE = "JLMod-Timing-Bridge-ABI";

	private TimingTransformMetadata() {
	}

	public static void mark(Map<String, String> attributes) {
		if (attributes == null) {
			throw new NullPointerException("attributes");
		}
		attributes.put(TRANSFORM_VERSION_ATTRIBUTE, Integer.toString(TRANSFORM_VERSION));
		attributes.put(BRIDGE_ABI_ATTRIBUTE, Integer.toString(GuestTimingBridge.ABI_VERSION));
	}

	public static boolean isCompatible(Map<String, String> attributes) {
		if (attributes == null) {
			return false;
		}
		return Integer.toString(TRANSFORM_VERSION).equals(
				attributes.get(TRANSFORM_VERSION_ATTRIBUTE))
				&& Integer.toString(GuestTimingBridge.ABI_VERSION).equals(
						attributes.get(BRIDGE_ABI_ATTRIBUTE));
	}
}
