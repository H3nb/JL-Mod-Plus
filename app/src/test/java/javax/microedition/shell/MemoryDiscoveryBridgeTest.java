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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryDiscoveryBridgeTest {
	@Test
	public void staleCloseCannotRemoveAReplacementRegistry() {
		long first = 101L;
		long second = 202L;
		ClassLoader loader = getClass().getClassLoader();
		Fixture root = new Fixture();

		MemoryDiscoveryBridge.install(first, loader);
		MemoryDiscoveryBridge.tailSeen(Fixture.class);
		MemoryDiscoveryBridge.setMidletRoot(first, root);
		MemoryDiscoveryBridge.install(second, loader);
		MemoryDiscoveryBridge.tailSeen(Fixture.class);
		MemoryDiscoveryBridge.setMidletRoot(second, root);
		try {
			MemoryDiscoveryBridge.close(first);
			MemoryDiscoveryBridge.Snapshot snapshot = MemoryDiscoveryBridge.snapshot(second);
			assertTrue(snapshot.isAvailable());
			assertSame(root, snapshot.root());
			assertEquals(1, snapshot.classes().length);
		} finally {
			MemoryDiscoveryBridge.close(second);
		}
		assertFalse(MemoryDiscoveryBridge.snapshot(second).isAvailable());
	}

	@Test
	public void rootFromAnotherLoaderDisablesManagedDiscovery() {
		long token = 303L;
		MemoryDiscoveryBridge.install(token, getClass().getClassLoader());
		try {
			MemoryDiscoveryBridge.tailSeen(Fixture.class);
			MemoryDiscoveryBridge.setMidletRoot(token, new Object());
			assertFalse(MemoryDiscoveryBridge.snapshot(token).isAvailable());
		} finally {
			MemoryDiscoveryBridge.close(token);
		}
	}

	private static final class Fixture {
	}
}
