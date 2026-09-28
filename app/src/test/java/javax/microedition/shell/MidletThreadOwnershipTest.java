/*
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MidletThreadOwnershipTest {
	@Test
	public void guestExecutionTokenIsScopedToExactSessionIdentity() {
		Object current = new Object();
		Object stale = new Object();

		assertTrue(MidletThread.isCurrentGuestExecutionToken(current, current));
		assertFalse(MidletThread.isCurrentGuestExecutionToken(current, stale));
		assertFalse(MidletThread.isCurrentGuestExecutionToken(current, null));
	}

	@Test
	public void escapingFailureMarkerRequiresExactSessionAndThrowableIdentity() {
		Object current = new Object();
		Object stale = new Object();
		Throwable failure = new IllegalStateException("guest");
		MidletThread.EscapingGuestFailure marker =
				new MidletThread.EscapingGuestFailure(current, failure);

		assertTrue(marker.matches(current, failure));
		assertFalse(marker.matches(stale, failure));
		assertFalse(marker.matches(current, new IllegalStateException("other")));
	}
}
