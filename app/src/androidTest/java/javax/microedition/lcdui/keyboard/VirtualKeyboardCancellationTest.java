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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;

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
		settings.vkType = 3;
		settings.vkFeedback = false;
		keyboard = new VirtualKeyboard(settings);

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
	public void cancelClearsDualKeyLocalSelection() throws Exception {
		Object upLeft = keyByLabel("↖");
		Field selected = findField(upLeft.getClass(), "selected");
		selected.setAccessible(true);
		selected.setBoolean(upLeft, true);

		keyboard.cancel();

		assertFalse(selected.getBoolean(upLeft));
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
