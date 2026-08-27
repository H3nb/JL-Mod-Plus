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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class MidletTransformMetadataTest {
	@Test
	public void currentVersionIsCompatibleAfterMarking() {
		Map<String, String> attributes = new HashMap<>();
		MidletTransformMetadata.mark(attributes);

		assertTrue(MidletTransformMetadata.isCompatible(attributes));
	}

	@Test
	public void missingOrChangedUniversalVersionIsRejected() {
		Map<String, String> attributes = new HashMap<>();
		MidletTransformMetadata.mark(attributes);
		attributes.put(MidletTransformMetadata.TRANSFORM_VERSION_ATTRIBUTE, "999");

		assertFalse(MidletTransformMetadata.isCompatible(attributes));
		assertFalse(MidletTransformMetadata.isCompatible(new HashMap<>()));
		assertFalse(MidletTransformMetadata.isCompatible(null));
	}

	@Test
	public void legacyTimingOnlyMarkerTriggersUniversalReconversion() {
		Map<String, String> attributes = new HashMap<>();
		attributes.put("JLMod-Timing-Transform-Version", "6");
		attributes.put("JLMod-Timing-Bridge-ABI", "4");

		assertFalse(MidletTransformMetadata.isCompatible(attributes));

		MidletTransformMetadata.mark(attributes);

		assertTrue(MidletTransformMetadata.isCompatible(attributes));
		assertNull(attributes.get("JLMod-Timing-Transform-Version"));
		assertNull(attributes.get("JLMod-Timing-Bridge-ABI"));
	}
}
