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

package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;

import io.github.h3nb.jlmodplus.config.ProfileModel;

@RunWith(AndroidJUnit4.class)
public class VirtualKeyboardCancellationTest {
	private VirtualKeyboard keyboard;
	private Object[] keypad;
	private File profileDir;

	@Before
	public void setUp() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		profileDir = new File(context.getCacheDir(),
				"vk-cancel-regression-" + System.nanoTime());
		assertTrue(profileDir.mkdirs());

		ProfileModel settings = new ProfileModel();
		settings.dir = profileDir;
		settings.vkType = layoutType("TYPE_ANALOG");
		settings.virtualAnalogCenterMode = ProfileModel.VIRTUAL_ANALOG_CENTER_RELATIVE;
		settings.vkFeedback = false;
		keyboard = new VirtualKeyboard(settings);
		keyboard.setView(new View(context));
		keyboard.resize(new RectF(0.0f, 0.0f, 600.0f, 600.0f),
				100.0f, 100.0f, 500.0f, 500.0f);

		Field keypadField = VirtualKeyboard.class.getDeclaredField("keypad");
		keypadField.setAccessible(true);
		keypad = (Object[]) keypadField.get(keyboard);
	}

	@After
	public void tearDown() throws Exception {
		if (keyboard != null) {
			keyboard.cancel();
			Field handlerField = VirtualKeyboard.class.getDeclaredField("handler");
			handlerField.setAccessible(true);
			Handler handler = (Handler) handlerField.get(keyboard);
			handler.getLooper().quitSafely();
		}
		if (profileDir != null) {
			//noinspection ResultOfMethodCallIgnored
			profileDir.delete();
		}
	}

	@Test
	public void analogToDpadLayoutSwitchClearsDirectionalGestureAndRelativeCenter() throws Exception {
		assertTrue(acquireDirectionalPointer(7));
		assertTrue(analogCenter().hasTemporaryCenter());

		keyboard.setLayout(layoutType("TYPE_DPAD"));

		assertEquals(-1, directionalPointer());
		assertFalse(analogCenter().hasTemporaryCenter());
	}

	@Test
	public void dpadToAnalogLayoutSwitchClearsDirectionalPointer() throws Exception {
		keyboard.setLayout(layoutType("TYPE_DPAD"));
		assertTrue(acquireDirectionalPointer(8));

		keyboard.setLayout(layoutType("TYPE_ANALOG"));

		assertEquals(-1, directionalPointer());
		assertFalse(analogCenter().hasTemporaryCenter());
	}

	@Test
	public void enteringLayoutEditModeClearsHeldDirectionalGesture() throws Exception {
		assertTrue(acquireDirectionalPointer(9));
		assertTrue(analogCenter().hasTemporaryCenter());

		keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);

		assertEquals(-1, directionalPointer());
		assertFalse(analogCenter().hasTemporaryCenter());
	}

	@Test
	public void layoutSwitchClearsLegacyKeyPointerSelectionAndRepeatState() throws Exception {
		keyboard.setLayout(layoutType("TYPE_NUM_ARR"));
		Object key = keyByLabel("5");
		setLegacyHeldState(key, 11, 3);

		keyboard.setLayout(layoutType("TYPE_ANALOG"));

		assertLegacyReleasedState(key);
	}

	@Test
	public void visibilityChangeCancelsActiveLegacyKey() throws Exception {
		keyboard.setLayout(layoutType("TYPE_NUM_ARR"));
		Object key = keyByLabel("5");
		setLegacyHeldState(key, 12, 2);

		boolean[] hidden = keyboard.getKeysVisibility();
		int keyIndex = keyIndex(key);
		hidden[keyIndex] = !hidden[keyIndex];
		keyboard.setKeysVisibility(hidden);

		assertLegacyReleasedState(key);
	}

	@Test
	public void cancelClearsDualKeyLocalSelection() throws Exception {
		Object upLeft = keyByLabel("↖");
		Field selected = findField(upLeft.getClass(), "selected");
		selected.setAccessible(true);
		selected.setBoolean(upLeft, true);

		keyboard.cancel();

		assertFalse(selected.getBoolean(upLeft));
	}

	private void setLegacyHeldState(Object key, int pointer, int repeatCount) throws Exception {
		Field selected = findField(key.getClass(), "selected");
		Field activePointer = findField(key.getClass(), "activePointer");
		Field repeat = findField(key.getClass(), "repeatCount");
		selected.setAccessible(true);
		activePointer.setAccessible(true);
		repeat.setAccessible(true);
		selected.setBoolean(key, true);
		activePointer.setInt(key, pointer);
		repeat.setInt(key, repeatCount);
	}

	private void assertLegacyReleasedState(Object key) throws Exception {
		Field selected = findField(key.getClass(), "selected");
		Field activePointer = findField(key.getClass(), "activePointer");
		Field repeat = findField(key.getClass(), "repeatCount");
		selected.setAccessible(true);
		activePointer.setAccessible(true);
		repeat.setAccessible(true);
		assertFalse(selected.getBoolean(key));
		assertEquals(-1, activePointer.getInt(key));
		assertEquals(0, repeat.getInt(key));
	}

	private int keyIndex(Object key) {
		for (int i = 0; i < keypad.length; i++) {
			if (keypad[i] == key) {
				return i;
			}
		}
		throw new AssertionError("Missing key instance");
	}

	private boolean acquireDirectionalPointer(int pointer) throws Exception {
		Field boundsField = VirtualKeyboard.class.getDeclaredField("directionalBounds");
		boundsField.setAccessible(true);
		RectF bounds = (RectF) boundsField.get(keyboard);
		return keyboard.pointerPressed(
				pointer,
				bounds.left + bounds.width() * 0.25f,
				bounds.top + bounds.height() * 0.25f);
	}

	private int directionalPointer() throws Exception {
		Field field = VirtualKeyboard.class.getDeclaredField("directionalPointer");
		field.setAccessible(true);
		return field.getInt(keyboard);
	}

	private DirectionalControlGeometry.AnalogCenterState analogCenter() throws Exception {
		Field field = VirtualKeyboard.class.getDeclaredField("analogCenter");
		field.setAccessible(true);
		return (DirectionalControlGeometry.AnalogCenterState) field.get(keyboard);
	}

	private static int layoutType(String name) throws Exception {
		Field field = VirtualKeyboard.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	private Object keyByLabel(String expected) throws Exception {
		for (Object key : keypad) {
			Field label = findField(key.getClass(), "label");
			label.setAccessible(true);
			if (expected.equals(label.get(key))) {
				return key;
			}
		}
		throw new AssertionError("Missing virtual key: " + expected);
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
