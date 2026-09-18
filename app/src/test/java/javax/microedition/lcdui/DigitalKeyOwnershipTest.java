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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DigitalKeyOwnershipTest {
	private static final int DEVICE_ONE = 1;
	private static final int DEVICE_TWO = 2;
	private static final int KEYCODE_ENTER = 66;
	private static final int KEYCODE_BUTTON_A = 96;

	@Test
	public void sharedTargetUsesFirstAcquireAndLastReleaseTransitions() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		long enter = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_ENTER);
		long buttonA = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_BUTTON_A);

		assertTrue(ownership.acquire(enter, Canvas.KEY_FIRE));
		assertFalse(ownership.acquire(buttonA, Canvas.KEY_FIRE));
		assertFalse(ownership.release(enter));
		assertEquals(Canvas.KEY_FIRE, ownership.targetOf(buttonA));
		assertTrue(ownership.release(buttonA));
	}

	@Test
	public void independentTargetsDoNotShareOwnership() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		long fireSource = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_ENTER);
		long upSource = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_BUTTON_A);

		assertTrue(ownership.acquire(fireSource, Canvas.KEY_FIRE));
		assertTrue(ownership.acquire(upSource, Canvas.KEY_UP));
		assertTrue(ownership.release(fireSource));
		assertEquals(Canvas.KEY_UP, ownership.targetOf(upSource));
		assertTrue(ownership.release(upSource));
	}

	@Test
	public void duplicateAcquireDoesNotChangePressTimeTargetOrOwnerCount() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		long source = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_BUTTON_A);

		assertTrue(ownership.acquire(source, Canvas.KEY_FIRE));
		assertFalse(ownership.acquire(source, Canvas.KEY_FIRE));
		assertFalse(ownership.acquire(source, Canvas.KEY_UP));
		assertEquals(Canvas.KEY_FIRE, ownership.targetOf(source));
		assertTrue(ownership.release(source));
		assertFalse(ownership.release(source));
	}

	@Test
	public void sameAndroidKeyFromDifferentDevicesRemainsIndependent() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		long firstDevice = DigitalKeyOwnership.sourceToken(DEVICE_ONE, KEYCODE_BUTTON_A);
		long secondDevice = DigitalKeyOwnership.sourceToken(DEVICE_TWO, KEYCODE_BUTTON_A);

		assertNotEquals(firstDevice, secondDevice);
		assertTrue(ownership.acquire(firstDevice, Canvas.KEY_FIRE));
		assertFalse(ownership.acquire(secondDevice, Canvas.KEY_FIRE));
		assertFalse(ownership.release(firstDevice));
		assertEquals(Canvas.KEY_FIRE, ownership.targetOf(secondDevice));
		assertTrue(ownership.release(secondDevice));
	}
}
