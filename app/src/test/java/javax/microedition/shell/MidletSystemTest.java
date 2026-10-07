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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MidletSystemTest {
	@Test
	public void propertyPublicationKeepsHostAndGuestStoresInSync() {
		String key = "jlmod.test.midlet.property";
		String previous = System.getProperty(key);
		try {
			MidletSystem.setProperty(key, "value");
			assertEquals("value", System.getProperty(key));
			assertEquals("value", MidletSystem.getProperty(key));

			MidletSystem.setProperty(key, null);
			assertNull(System.getProperty(key));
			assertNull(MidletSystem.getProperty(key));
		} finally {
			MidletSystem.setProperty(key, null);
			if (previous != null) {
				System.setProperty(key, previous);
			}
		}
	}

	@Test
	public void advisoryGcRequestsReturnWithoutCollecting() {
		MidletSystem.gc();
		MidletSystem.gc(Runtime.getRuntime());
	}

	@Test(expected = NullPointerException.class)
	public void runtimeGcBridgePreservesReceiverNullCheck() {
		MidletSystem.gc((Runtime) null);
	}
}
