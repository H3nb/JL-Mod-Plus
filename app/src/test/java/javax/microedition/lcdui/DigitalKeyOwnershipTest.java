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
	private static final int KEYCODE_DPAD_UP = 19;
	private static final int KEYCODE_ENTER = 66;
	private static final int KEYCODE_BUTTON_A = 96;

	@Test
	public void sharedTargetUsesFirstAcquireAndLastReleaseTransitions() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition enter =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_FIRE);
		DigitalKeyOwnership.Transition buttonA =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		assertEquals(Canvas.KEY_FIRE, enter.pressedKey);
		assertEquals(0, buttonA.pressedKey);

		DigitalKeyOwnership.Transition enterUp =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, 0);
		assertEquals(0, enterUp.releasedKey);
		assertEquals(Canvas.KEY_FIRE,
				ownership.physicalKeyTarget(DEVICE_ONE, KEYCODE_BUTTON_A));

		DigitalKeyOwnership.Transition buttonAUp =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, 0);
		assertEquals(Canvas.KEY_FIRE, buttonAUp.releasedKey);
	}

	@Test
	public void independentTargetsDoNotShareOwnership() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition fire =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_FIRE);
		DigitalKeyOwnership.Transition up =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_UP);
		assertEquals(Canvas.KEY_FIRE, fire.pressedKey);
		assertEquals(Canvas.KEY_UP, up.pressedKey);

		DigitalKeyOwnership.Transition fireUp =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, 0);
		assertEquals(Canvas.KEY_FIRE, fireUp.releasedKey);
		assertEquals(Canvas.KEY_UP,
				ownership.physicalKeyTarget(DEVICE_ONE, KEYCODE_BUTTON_A));

		DigitalKeyOwnership.Transition upRelease =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, 0);
		assertEquals(Canvas.KEY_UP, upRelease.releasedKey);
	}

	@Test
	public void duplicatePhysicalSetIsIdempotent() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition first =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		DigitalKeyOwnership.Transition duplicate =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		assertEquals(Canvas.KEY_FIRE, first.pressedKey);
		assertEquals(0, duplicate.pressedKey);
		assertEquals(0, duplicate.releasedKey);
		assertEquals(Canvas.KEY_FIRE,
				ownership.physicalKeyTarget(DEVICE_ONE, KEYCODE_BUTTON_A));

		DigitalKeyOwnership.Transition release =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, 0);
		assertEquals(Canvas.KEY_FIRE, release.releasedKey);
		DigitalKeyOwnership.Transition lateRelease =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, 0);
		assertEquals(0, lateRelease.releasedKey);
	}

	@Test
	public void physicalAndVirtualOwnersShareOneGuestTarget() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition physical =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_FIRE);
		assertEquals(Canvas.KEY_FIRE, physical.pressedKey);
		assertEquals(0, physical.releasedKey);

		DigitalKeyOwnership.Transition virtual =
				ownership.setVirtualKey(7, 0, Canvas.KEY_FIRE);
		assertEquals(0, virtual.pressedKey);
		assertEquals(0, virtual.releasedKey);

		DigitalKeyOwnership.Transition physicalUp =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, 0);
		assertEquals(0, physicalUp.releasedKey);

		DigitalKeyOwnership.Transition virtualUp = ownership.setVirtualKey(7, 0, 0);
		assertEquals(Canvas.KEY_FIRE, virtualUp.releasedKey);
	}

	@Test
	public void deviceCleanupDoesNotReleaseTargetStillOwnedElsewhere() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		ownership.setPhysicalKey(DEVICE_TWO, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		assertEquals(0, ownership.releaseDevice(DEVICE_ONE).length);
		assertEquals(Canvas.KEY_FIRE,
				ownership.physicalKeyTarget(DEVICE_TWO, KEYCODE_BUTTON_A));

		int[] released = ownership.releaseDevice(DEVICE_TWO);
		assertEquals(1, released.length);
		assertEquals(Canvas.KEY_FIRE, released[0]);
	}

	@Test
	public void virtualCleanupLeavesPhysicalOwnerIntact() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();
		ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_FIRE);
		ownership.setVirtualKey(3, 0, Canvas.KEY_FIRE);

		assertEquals(0, ownership.releaseVirtual().length);
		DigitalKeyOwnership.Transition release =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, 0);
		assertEquals(Canvas.KEY_FIRE, release.releasedKey);
	}

	@Test
	public void changingDirectionalSourceReleasesOldAndAcquiresNewTarget() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition left =
				ownership.setDeviceDirection(DEVICE_ONE, 1, Canvas.KEY_LEFT);
		assertEquals(Canvas.KEY_LEFT, left.pressedKey);

		DigitalKeyOwnership.Transition right =
				ownership.setDeviceDirection(DEVICE_ONE, 1, Canvas.KEY_RIGHT);
		assertEquals(Canvas.KEY_LEFT, right.releasedKey);
		assertEquals(Canvas.KEY_RIGHT, right.pressedKey);
	}

	@Test
	public void physicalDpadAndHatCanOwnSameDirectionIndependently() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition dpad =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_DPAD_UP, Canvas.KEY_UP);
		DigitalKeyOwnership.Transition hat =
				ownership.setDeviceDirection(DEVICE_ONE, 1, Canvas.KEY_UP);
		assertEquals(Canvas.KEY_UP, dpad.pressedKey);
		assertEquals(0, hat.pressedKey);

		DigitalKeyOwnership.Transition dpadUp =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_DPAD_UP, 0);
		assertEquals(0, dpadUp.releasedKey);

		DigitalKeyOwnership.Transition hatCenter =
				ownership.setDeviceDirection(DEVICE_ONE, 1, 0);
		assertEquals(Canvas.KEY_UP, hatCenter.releasedKey);
	}

	@Test
	public void hatAndStickCanOwnSameDirectionIndependently() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition hat =
				ownership.setDeviceDirection(DEVICE_ONE, 1, Canvas.KEY_UP);
		DigitalKeyOwnership.Transition stick =
				ownership.setDeviceDirection(DEVICE_ONE, 2, Canvas.KEY_UP);
		assertEquals(Canvas.KEY_UP, hat.pressedKey);
		assertEquals(0, stick.pressedKey);

		DigitalKeyOwnership.Transition hatCenter =
				ownership.setDeviceDirection(DEVICE_ONE, 1, 0);
		assertEquals(0, hatCenter.releasedKey);

		DigitalKeyOwnership.Transition stickCenter =
				ownership.setDeviceDirection(DEVICE_ONE, 2, 0);
		assertEquals(Canvas.KEY_UP, stickCenter.releasedKey);
	}


	@Test
	public void physicalAndSyntheticNamespacesCannotCollide() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition physical =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_FIRE);
		DigitalKeyOwnership.Transition virtual =
				ownership.setVirtualKey(DEVICE_ONE, KEYCODE_ENTER, Canvas.KEY_UP);

		assertEquals(Canvas.KEY_FIRE, physical.pressedKey);
		assertEquals(Canvas.KEY_UP, virtual.pressedKey);
		assertEquals(Canvas.KEY_FIRE,
				ownership.physicalKeyTarget(DEVICE_ONE, KEYCODE_ENTER));
		assertEquals(Canvas.KEY_UP,
				ownership.virtualKeyTarget(DEVICE_ONE, KEYCODE_ENTER));
	}


	@Test
	public void sameAndroidKeyFromDifferentDevicesRemainsIndependent() {
		DigitalKeyOwnership ownership = new DigitalKeyOwnership();

		DigitalKeyOwnership.Transition firstDevice =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		DigitalKeyOwnership.Transition secondDevice =
				ownership.setPhysicalKey(DEVICE_TWO, KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		assertEquals(Canvas.KEY_FIRE, firstDevice.pressedKey);
		assertEquals(0, secondDevice.pressedKey);

		DigitalKeyOwnership.Transition firstRelease =
				ownership.setPhysicalKey(DEVICE_ONE, KEYCODE_BUTTON_A, 0);
		assertEquals(0, firstRelease.releasedKey);
		assertEquals(Canvas.KEY_FIRE,
				ownership.physicalKeyTarget(DEVICE_TWO, KEYCODE_BUTTON_A));

		DigitalKeyOwnership.Transition secondRelease =
				ownership.setPhysicalKey(DEVICE_TWO, KEYCODE_BUTTON_A, 0);
		assertEquals(Canvas.KEY_FIRE, secondRelease.releasedKey);
	}
}
