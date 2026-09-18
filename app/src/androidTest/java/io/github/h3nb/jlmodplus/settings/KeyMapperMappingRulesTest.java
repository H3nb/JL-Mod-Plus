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
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.github.h3nb.jlmodplus.config.ProfileModel;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.keyboard.KeyMapper;

@RunWith(AndroidJUnit4.class)
public class KeyMapperMappingRulesTest {
	@After
	public void restoreDefaultRuntimeMapping() {
		KeyMapper.setKeyMapping(new ProfileModel());
	}

	@Test
	public void defaultMapKeepsRepresentativeKeyboardMappings() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();

		assertEquals(Canvas.KEY_UP, defaults.get(KeyEvent.KEYCODE_DPAD_UP));
		assertEquals(Canvas.KEY_DOWN, defaults.get(KeyEvent.KEYCODE_DPAD_DOWN));
		assertEquals(Canvas.KEY_LEFT, defaults.get(KeyEvent.KEYCODE_DPAD_LEFT));
		assertEquals(Canvas.KEY_RIGHT, defaults.get(KeyEvent.KEYCODE_DPAD_RIGHT));
		assertEquals(Canvas.KEY_FIRE, defaults.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(Canvas.KEY_NUM5, defaults.get(KeyEvent.KEYCODE_5));
	}

	@Test
	public void runtimeMappingAcceptsGamepadButtonFromProfile() {
		ProfileModel profile = new ProfileModel();
		profile.keyMappings = new SparseIntArray();
		profile.keyMappings.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		KeyMapper.setKeyMapping(profile);

		assertEquals(Canvas.KEY_FIRE, KeyMapper.convertAndroidKeyCode(
				KeyEvent.KEYCODE_BUTTON_A,
				new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
	}

	@Test
	public void customGamepadFireMappingCoexistsWithDefaultKeyboardFireMapping() {
		ProfileModel profile = new ProfileModel();
		profile.keyMappings = new SparseIntArray();
		profile.keyMappings.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		KeyMapper.setKeyMapping(profile);

		assertEquals(Canvas.KEY_FIRE, KeyMapper.convertAndroidKeyCode(
				KeyEvent.KEYCODE_BUTTON_A,
				new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
		assertEquals(Canvas.KEY_FIRE, KeyMapper.convertAndroidKeyCode(
				KeyEvent.KEYCODE_ENTER,
				new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
	}

	@Test
	public void resolveWithoutOverridesClonesDefaults() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray snapshot = defaults.clone();

		SparseIntArray effective = KeyMapper.resolveKeyMappings(defaults, null);

		assertTrue(KeyMapperMappingRules.equalMaps(defaults, effective));
		effective.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		assertTrue(KeyMapperMappingRules.equalMaps(snapshot, defaults));
	}

	@Test
	public void resolveAdditionalBindingPreservesKeyboardDefaultAndInputs() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray defaultSnapshot = defaults.clone();
		SparseIntArray overrides = new SparseIntArray();
		overrides.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		SparseIntArray overrideSnapshot = overrides.clone();

		SparseIntArray effective = KeyMapper.resolveKeyMappings(defaults, overrides);

		assertEquals(Canvas.KEY_FIRE, effective.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_FIRE, effective.get(KeyEvent.KEYCODE_ENTER));
		assertTrue(KeyMapperMappingRules.equalMaps(defaultSnapshot, defaults));
		assertTrue(KeyMapperMappingRules.equalMaps(overrideSnapshot, overrides));
	}

	@Test
	public void resolveOverrideReplacesOnlyThatPhysicalSource() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray overrides = new SparseIntArray();
		overrides.put(KeyEvent.KEYCODE_ENTER, Canvas.KEY_NUM5);

		SparseIntArray effective = KeyMapper.resolveKeyMappings(defaults, overrides);

		assertEquals(Canvas.KEY_NUM5, effective.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(Canvas.KEY_UP, effective.get(KeyEvent.KEYCODE_DPAD_UP));
	}

	@Test
	public void explicitRemovalDeletesDefaultPhysicalBinding() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray overrides = new SparseIntArray();
		overrides.put(KeyEvent.KEYCODE_ENTER, KeyMapper.KEY_MAPPING_REMOVED);

		SparseIntArray effective = KeyMapper.resolveKeyMappings(defaults, overrides);

		assertEquals(-1, effective.indexOfKey(KeyEvent.KEYCODE_ENTER));
		assertEquals(Canvas.KEY_UP, effective.get(KeyEvent.KEYCODE_DPAD_UP));
	}

	@Test
	public void menuTargetZeroSurvivesResolveAndDiff() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray overrides = new SparseIntArray();
		overrides.put(KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);

		SparseIntArray effective = KeyMapper.resolveKeyMappings(defaults, overrides);
		SparseIntArray persisted = KeyMapper.getKeyMappingOverrides(defaults, effective);
		SparseIntArray roundTrip = KeyMapper.resolveKeyMappings(defaults, persisted);

		int startIndex = effective.indexOfKey(KeyEvent.KEYCODE_BUTTON_START);
		assertTrue(startIndex >= 0);
		assertEquals(KeyMapper.KEY_OPTIONS_MENU, effective.valueAt(startIndex));
		int persistedIndex = persisted.indexOfKey(KeyEvent.KEYCODE_BUTTON_START);
		assertTrue(persistedIndex >= 0);
		assertEquals(KeyMapper.KEY_OPTIONS_MENU, persisted.valueAt(persistedIndex));
		assertTrue(KeyMapper.KEY_OPTIONS_MENU != KeyMapper.KEY_MAPPING_REMOVED);
		assertTrue(KeyMapperMappingRules.equalMaps(effective, roundTrip));
	}

	@Test
	public void effectiveMapDiffRoundTripsAddChangeRemovalAndMenu() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray effective = defaults.clone();
		effective.delete(KeyEvent.KEYCODE_ENTER);
		effective.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);
		effective.put(KeyEvent.KEYCODE_DPAD_UP, Canvas.KEY_NUM2);
		effective.put(KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);

		SparseIntArray overrides = KeyMapper.getKeyMappingOverrides(defaults, effective);
		SparseIntArray roundTrip = KeyMapper.resolveKeyMappings(defaults, overrides);

		assertEquals(KeyMapper.KEY_MAPPING_REMOVED, overrides.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(Canvas.KEY_FIRE, overrides.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_NUM2, overrides.get(KeyEvent.KEYCODE_DPAD_UP));
		assertTrue(KeyMapperMappingRules.equalMaps(effective, roundTrip));
	}

	@Test
	public void unchangedEffectiveMapProducesNoOverrides() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();

		SparseIntArray overrides = KeyMapper.getKeyMappingOverrides(defaults, defaults.clone());

		assertEquals(0, overrides.size());
	}

	@Test
	public void legacyPersistedMapStillInheritsMissingDefaults() {
		SparseIntArray legacyPersisted = new SparseIntArray();
		legacyPersisted.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		SparseIntArray effective =
				KeyMapper.resolveKeyMappings(KeyMapper.getDefaultKeyMap(), legacyPersisted);

		assertEquals(Canvas.KEY_FIRE, effective.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_FIRE, effective.get(KeyEvent.KEYCODE_ENTER));
	}

	@Test
	public void runtimeSetKeyMappingAppliesExplicitRemovalInsteadOfLeakingMarker() {
		ProfileModel profile = new ProfileModel();
		profile.keyMappings = new SparseIntArray();
		profile.keyMappings.put(KeyEvent.KEYCODE_ENTER, KeyMapper.KEY_MAPPING_REMOVED);
		KeyEvent enter = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
		int fallback = enter.getUnicodeChar() & KeyCharacterMap.COMBINING_ACCENT_MASK;

		KeyMapper.setKeyMapping(profile);

		assertEquals(fallback, KeyMapper.convertAndroidKeyCode(KeyEvent.KEYCODE_ENTER, enter));
	}

	@Test
	public void addOrReplaceBindingKeepsExistingSiblingForSameTarget() {
		SparseIntArray original = new SparseIntArray();
		original.put(KeyEvent.KEYCODE_ENTER, Canvas.KEY_FIRE);

		SparseIntArray updated = KeyMapperMappingRules.addOrReplaceBinding(
				original, KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		assertEquals(Canvas.KEY_FIRE, updated.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(Canvas.KEY_FIRE, updated.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(-1, original.indexOfKey(KeyEvent.KEYCODE_BUTTON_A));
	}

	@Test
	public void addOrReplaceBindingReassignsSamePhysicalSource() {
		SparseIntArray original = new SparseIntArray();
		original.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_NUM5);

		SparseIntArray updated = KeyMapperMappingRules.addOrReplaceBinding(
				original, KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		assertEquals(Canvas.KEY_FIRE, updated.get(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_NUM5, original.get(KeyEvent.KEYCODE_BUTTON_A));
	}

	@Test
	public void removeBindingLeavesOtherSourcesForSameTargetIntact() {
		SparseIntArray original = new SparseIntArray();
		original.put(KeyEvent.KEYCODE_ENTER, Canvas.KEY_FIRE);
		original.put(KeyEvent.KEYCODE_BUTTON_A, Canvas.KEY_FIRE);

		SparseIntArray updated =
				KeyMapperMappingRules.removeBinding(original, KeyEvent.KEYCODE_BUTTON_A);

		assertEquals(Canvas.KEY_FIRE, updated.get(KeyEvent.KEYCODE_ENTER));
		assertEquals(-1, updated.indexOfKey(KeyEvent.KEYCODE_BUTTON_A));
		assertEquals(Canvas.KEY_FIRE, original.get(KeyEvent.KEYCODE_BUTTON_A));
	}

	@Test
	public void menuKeyPresenceControlsSafeBackContract() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		SparseIntArray withSecondMenu = KeyMapperMappingRules.addOrReplaceBinding(
				defaults, KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);
		SparseIntArray withoutBack = KeyMapperMappingRules.removeBinding(
				withSecondMenu, KeyEvent.KEYCODE_BACK);
		SparseIntArray withoutAnyMenu = KeyMapperMappingRules.removeBinding(
				withoutBack, KeyEvent.KEYCODE_BUTTON_START);

		assertTrue(KeyMapperMappingRules.containsValue(defaults, KeyMapper.KEY_OPTIONS_MENU));
		assertTrue(KeyMapperMappingRules.containsValue(withoutBack, KeyMapper.KEY_OPTIONS_MENU));
		assertFalse(KeyMapperMappingRules.containsValue(withoutAnyMenu, KeyMapper.KEY_OPTIONS_MENU));
	}


	@Test
	public void runtimeMenuDetectionRecognizesDefaultAndAdditionalEffectiveMappings() {
		KeyMapper.setKeyMapping(new ProfileModel());
		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BACK));
		assertFalse(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BUTTON_A));

		ProfileModel profile = new ProfileModel();
		profile.keyMappings = new SparseIntArray();
		profile.keyMappings.put(KeyEvent.KEYCODE_BUTTON_START, KeyMapper.KEY_OPTIONS_MENU);
		KeyMapper.setKeyMapping(profile);

		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BACK));
		assertTrue(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BUTTON_START));
	}

	@Test
	public void runtimeMenuDetectionHonorsRemovedAndRemappedBack() {
		ProfileModel removed = new ProfileModel();
		removed.keyMappings = new SparseIntArray();
		removed.keyMappings.put(KeyEvent.KEYCODE_BACK, KeyMapper.KEY_MAPPING_REMOVED);
		KeyMapper.setKeyMapping(removed);

		assertFalse(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BACK));
		assertFalse(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BUTTON_A));

		ProfileModel remapped = new ProfileModel();
		remapped.keyMappings = new SparseIntArray();
		remapped.keyMappings.put(KeyEvent.KEYCODE_BACK, Canvas.KEY_FIRE);
		KeyMapper.setKeyMapping(remapped);

		assertFalse(KeyMapper.isOptionsMenuKey(KeyEvent.KEYCODE_BACK));
	}

	@Test
	public void equalityDistinguishesNullAndDefaultForPersistence() {
		SparseIntArray defaults = KeyMapper.getDefaultKeyMap();
		assertTrue(KeyMapperMappingRules.equalMaps(defaults, defaults.clone()));
		assertFalse(KeyMapperMappingRules.equalMaps(null, defaults));
		assertTrue(KeyMapperMappingRules.equalMaps(null, null));
	}

	@Test
	public void dispatchAcceptsControllerButtons() {
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START));
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT));
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
		assertTrue(KeyMapperDispatchRules.isAssignableKey(
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
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
				KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
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

	@Test
	public void popupTouchBoundaryConsumesOutsideDownButLeavesInsideInteractive() {
		Rect popup = new Rect(100, 200, 300, 400);

		assertTrue(KeyMapperDispatchRules.shouldDismissMappingPopup(
				KeyEvent.ACTION_DOWN, popup, 99, 200));
		assertFalse(KeyMapperDispatchRules.shouldDismissMappingPopup(
				KeyEvent.ACTION_DOWN, popup, 150, 250));
		assertFalse(KeyMapperDispatchRules.shouldDismissMappingPopup(
				KeyEvent.ACTION_UP, popup, 99, 200));
	}
}
