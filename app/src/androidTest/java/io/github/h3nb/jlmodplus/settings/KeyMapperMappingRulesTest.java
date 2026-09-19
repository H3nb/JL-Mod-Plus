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

package io.github.h3nb.jlmodplus.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.SparseIntArray;
import android.graphics.Rect;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.github.h3nb.jlmodplus.config.ProfileModel;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.keyboard.KeyMapper;

@RunWith(AndroidJUnit4.class)
public class KeyMapperMappingRulesTest {
	@Test
	public void assignmentPreservesExistingManyToOneBindings() {
		SparseIntArray original = new SparseIntArray();
		original.put(10, Canvas.KEY_LEFT);
		original.put(11, Canvas.KEY_LEFT);
		original.put(12, Canvas.KEY_RIGHT);

		SparseIntArray updated = KeyMapperMappingRules.assign(original, Canvas.KEY_LEFT, 13);

		assertEquals(Canvas.KEY_LEFT, updated.get(13));
		assertEquals(3, countValue(updated, Canvas.KEY_LEFT));
		assertEquals(Canvas.KEY_RIGHT, updated.get(12));
		assertEquals(Canvas.KEY_LEFT, original.get(10));
	}

	@Test
	public void removalIsExplicitAndTargetResetDoesNotAffectOtherTargets() {
		SparseIntArray original = new SparseIntArray();
		original.put(KeyEvent.KEYCODE_ENTER, Canvas.KEY_FIRE);
		original.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		original.put(KeyEvent.KEYCODE_DPAD_UP, Canvas.KEY_UP);

		SparseIntArray removed = KeyMapperMappingRules.removeBinding(
				original, KeyEvent.KEYCODE_BUTTON_A);
		assertEquals(Canvas.KEY_FIRE, removed.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(-1, removed.indexOfKey(KeyEvent.KEYCODE_BUTTON_A));

		SparseIntArray reset = KeyMapperMappingRules.removeBindingsForTarget(
				original, Canvas.KEY_FIRE);
		assertEquals(-1, reset.indexOfKey(KeyEvent.KEYCODE_ENTER));
		assertEquals(-1, reset.indexOfKey(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_UP, reset.get(KeyEvent.KEYCODE_DPAD_UP));
	}

	@Test
	public void commonGamepadDefaultsUseTheUniversalGuestMap() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		assertEquals(Canvas.KEY_FIRE, defaults.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_FIRE, defaults.get(KeyEvent.KEYCODE_BUTTON_1));
		assertEquals(Canvas.KEY_NUM0, defaults.get(KeyEvent.KEYCODE_BUTTON_B));
		assertEquals(Canvas.KEY_NUM0, defaults.get(KeyEvent.KEYCODE_BUTTON_2));
		assertEquals(Canvas.KEY_NUM1, defaults.get(KeyEvent.KEYCODE_BUTTON_X));
		assertEquals(Canvas.KEY_NUM1, defaults.get(KeyEvent.KEYCODE_BUTTON_3));
		assertEquals(Canvas.KEY_NUM3, defaults.get(KeyEvent.KEYCODE_BUTTON_Y));
		assertEquals(Canvas.KEY_NUM3, defaults.get(KeyEvent.KEYCODE_BUTTON_4));
		assertEquals(Canvas.KEY_SOFT_LEFT, defaults.get(KeyEvent.KEYCODE_BUTTON_L1));
		assertEquals(Canvas.KEY_SOFT_LEFT, defaults.get(KeyEvent.KEYCODE_BUTTON_5));
		assertEquals(Canvas.KEY_SOFT_RIGHT, defaults.get(KeyEvent.KEYCODE_BUTTON_R1));
		assertEquals(Canvas.KEY_SOFT_RIGHT, defaults.get(KeyEvent.KEYCODE_BUTTON_6));
		assertEquals(4, countValue(defaults, Canvas.KEY_FIRE));
		assertEquals(-1, defaults.indexOfKey(KeyEvent.KEYCODE_BUTTON_START));
		assertEquals(-1, defaults.indexOfKey(KeyEvent.KEYCODE_BUTTON_SELECT));
	}

	private static int countValue(SparseIntArray map, int value) {
		int count = 0;
		for (int i = 0; i < map.size(); i++) {
			if (map.valueAt(i) == value) count++;
		}
		return count;
	}

	@Test
	public void menuKeyPresenceControlsSafeBackContract() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		assertTrue(KeyMapperMappingRules.containsValue(defaults, KeyMapper.KEY_OPTIONS_MENU));

		SparseIntArray withoutMenu = defaults.clone();
		withoutMenu.removeAt(withoutMenu.indexOfKey(KeyEvent.KEYCODE_BACK));
		assertFalse(KeyMapperMappingRules.containsValue(withoutMenu, KeyMapper.KEY_OPTIONS_MENU));
	}

	@Test
	public void runtimeMenuTargetRecognizesMultiplePhysicalSources() {
		ProfileModel profile = new ProfileModel();
		profile.keyMappings = new SparseIntArray();
		profile.keyMappings.put(KeyEvent.KEYCODE_F1, KeyMapper.KEY_OPTIONS_MENU);
		profile.keyMappings.put(KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);

		KeyMapper.setKeyMapping(profile);

		// Default Back remains valid and both user assignments join the same host-only M target.
		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BACK));
		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_F1));
		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BUTTON_START));
		assertFalse(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BUTTON_SELECT));
	}

	@Test
	public void removedDefaultBindingRoundTripsAsTombstone() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray effective = defaults.clone();
		effective.delete(KeyEvent.KEYCODE_BUTTON_A);

		SparseIntArray persisted = KeyMapperMappingRules.diff(defaults, effective);
		assertTrue(persisted.indexOfKey(KeyEvent.KEYCODE_BUTTON_A) >= 0);
		assertEquals(KeyMapperMappingRules.UNMAPPED_TOMBSTONE,
				persisted.get(KeyEvent.KEYCODE_BUTTON_A));
		SparseIntArray restored = KeyMapperMappingRules.resolve(defaults, persisted);
		assertEquals(-1, restored.indexOfKey(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_FIRE, restored.get(KeyEvent.KEYCODE_ENTER));
	}

	@Test
	public void menuTargetRoundTripsWithoutCollidingWithTombstone() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray effective = defaults.clone();
		effective.put(KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);

		SparseIntArray persisted = KeyMapperMappingRules.diff(defaults, effective);
		assertEquals(KeyMapper.KEY_OPTIONS_MENU,
				persisted.get(KeyEvent.KEYCODE_BUTTON_START));
		assertTrue(persisted.get(KeyEvent.KEYCODE_BUTTON_START)
				!= KeyMapperMappingRules.UNMAPPED_TOMBSTONE);

		SparseIntArray restored = KeyMapperMappingRules.resolve(defaults, persisted);
		assertEquals(KeyMapper.KEY_OPTIONS_MENU,
				restored.get(KeyEvent.KEYCODE_BUTTON_START));
	}

	@Test
	public void equalityDistinguishesNullAndDefaultForPersistence() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		assertTrue(KeyMapperMappingRules.equalMaps(defaults, defaults.clone()));
		assertFalse(KeyMapperMappingRules.equalMaps(null, defaults));
		assertTrue(KeyMapperMappingRules.equalMaps(null, null));
	}

	@Test
	public void dispatchKeepsProtectedHardwareKeysOutsideMapper() {
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
		assertFalse(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME));
		assertFalse(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
		assertFalse(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
	}

	@Test
	public void popupGeometryMatchesLegacyOutsideDismissRule() {
		Rect popup = new Rect(100, 200, 300, 400);
		assertTrue(KeyMapperDispatchRules.isInsidePopup(popup, 100, 200));
		assertTrue(KeyMapperDispatchRules.isInsidePopup(popup, 299, 399));
		assertFalse(KeyMapperDispatchRules.isInsidePopup(popup, 99, 200));
		assertFalse(KeyMapperDispatchRules.isInsidePopup(popup, 300, 400));
		assertFalse(KeyMapperDispatchRules.isInsidePopup(null, 100, 200));
	}
}
