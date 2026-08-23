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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TimingTransformMetadataTest {
	@Test
	public void markedMetadataIsCompatible() {
		Map<String, String> attributes = new HashMap<>();
		TimingTransformMetadata.mark(attributes);

		assertTrue(TimingTransformMetadata.isCompatible(attributes));
	}

	@Test
	public void missingOrChangedMetadataIsRejected() {
		Map<String, String> attributes = new HashMap<>();
		TimingTransformMetadata.mark(attributes);
		attributes.put(TimingTransformMetadata.BRIDGE_ABI_ATTRIBUTE, "999");

		assertFalse(TimingTransformMetadata.isCompatible(attributes));
		assertFalse(TimingTransformMetadata.isCompatible(null));
	}
}
