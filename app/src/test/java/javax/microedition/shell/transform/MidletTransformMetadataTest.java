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

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.HashMap;
import java.util.Map;

public class MidletTransformMetadataTest {
	@Rule public final TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void overwrittenDexInvalidatesRuntimeFeaturesWithoutForcingReconversion() throws Exception {
		File payload = temporary.newFile("converted.zip");
		Files.write(payload.toPath(), new byte[]{1, 2, 3});
		Map<String, String> attributes = new HashMap<>();
		MidletTransformMetadata.mark(attributes);
		MidletTransformMetadata.recordPayload(attributes, payload);
		assertTrue(MidletTransformMetadata.isRuntimeCompatible(attributes, payload));

		// A second emulator may overwrite only the DEX, leaving version 2 in the descriptor.
		Files.write(payload.toPath(), new byte[]{4, 5, 6});
		assertFalse(MidletTransformMetadata.isRuntimeCompatible(attributes, payload));
		assertTrue(MidletTransformMetadata.isCompatible(attributes));
	}
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
