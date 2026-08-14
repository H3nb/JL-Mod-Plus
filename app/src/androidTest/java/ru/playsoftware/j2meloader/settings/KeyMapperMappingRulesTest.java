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

package ru.playsoftware.j2meloader.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.SparseIntArray;
import android.graphics.Rect;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.keyboard.KeyMapper;

@RunWith(AndroidJUnit4.class)
public class KeyMapperMappingRulesTest {
	@Test
	public void assignmentRemovesExistingDuplicateCanvasKey() {
		SparseIntArray original = new SparseIntArray();
		original.put(10, Canvas.KEY_LEFT);
		original.put(11, Canvas.KEY_LEFT);
		original.put(12, Canvas.KEY_RIGHT);

		SparseIntArray updated = KeyMapperMappingRules.assign(original, Canvas.KEY_LEFT, 13);

		assertEquals(Canvas.KEY_LEFT, updated.get(13));
		assertEquals(-1, updated.indexOfValue(Canvas.KEY_LEFT));
		assertEquals(Canvas.KEY_RIGHT, updated.get(12));
		assertEquals(Canvas.KEY_LEFT, original.get(10));
	}

	@Test
	public void menuKeyPresenceControlsSafeBackContract() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		assertTrue(KeyMapperMappingRules.containsValue(defaults, KeyMapper.KEY_OPTIONS_MENU));

		SparseIntArray withoutMenu = defaults.clone();
		withoutMenu.removeAt(withoutMenu.indexOfKey(android.view.KeyEvent.KEYCODE_BACK));
		assertFalse(KeyMapperMappingRules.containsValue(withoutMenu, KeyMapper.KEY_OPTIONS_MENU));
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
