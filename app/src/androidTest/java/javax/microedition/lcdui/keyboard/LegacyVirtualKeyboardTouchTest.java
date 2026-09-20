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
import javax.microedition.lcdui.Canvas;

@RunWith(AndroidJUnit4.class)
public class LegacyVirtualKeyboardTouchTest {
    private VirtualKeyboard keyboard;
    private Object[] keypad;
    private File profileDir;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        profileDir = new File(context.getCacheDir(),
                "legacy-vk-touch-regression-" + System.nanoTime());
        assertTrue(profileDir.mkdirs());

        ProfileModel settings = new ProfileModel();
        settings.dir = profileDir;
        settings.vkType = 3; // Numbers & Arrows: F occupies the center of the arrow cluster.
        settings.vkFeedback = false;
        settings.vkAlpha = 255;

        keyboard = new VirtualKeyboard(settings);
        keyboard.setView(new View(context));
        keyboard.resize(new RectF(0f, 0f, 1200f, 600f), 0f, 0f, 1200f, 600f);

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
        if (profileDir != null) profileDir.delete();
    }

    @Test
    public void centerFireRemainsARealLegacyVirtualKey() throws Exception {
        Object fire = keyByLabel("F");
        assertEquals(Canvas.KEY_FIRE, intField(fire, "keyCode"));

        RectF rect = rectField(fire);
        assertTrue(keyboard.pointerPressed(0, rect.centerX(), rect.centerY()));
        assertTrue("F must own its pressed visual/input state", booleanField(fire, "selected"));

        assertTrue(keyboard.pointerReleased(0, rect.centerX(), rect.centerY()));
        assertFalse(booleanField(fire, "selected"));
    }

    @Test
    public void legacyArrowUsesVirtualKeyPressedState() throws Exception {
        Object up = keyByLabel("↑");
        assertEquals(Canvas.KEY_UP, intField(up, "keyCode"));

        RectF rect = rectField(up);
        assertTrue(keyboard.pointerPressed(0, rect.centerX(), rect.centerY()));
        assertTrue("legacy arrows must render the pressed state", booleanField(up, "selected"));

        assertTrue(keyboard.pointerReleased(0, rect.centerX(), rect.centerY()));
        assertFalse(booleanField(up, "selected"));
    }

    private Object keyByLabel(String expected) throws Exception {
        for (Object key : keypad) {
            Field label = findField(key.getClass(), "label");
            label.setAccessible(true);
            if (expected.equals(label.get(key))) return key;
        }
        throw new AssertionError("Missing virtual key: " + expected);
    }

    private static RectF rectField(Object key) throws Exception {
        Field field = findField(key.getClass(), "rect");
        field.setAccessible(true);
        return new RectF((RectF) field.get(key));
    }

    private static int intField(Object key, String name) throws Exception {
        Field field = findField(key.getClass(), name);
        field.setAccessible(true);
        return field.getInt(key);
    }

    private static boolean booleanField(Object key, String name) throws Exception {
        Field field = findField(key.getClass(), name);
        field.setAccessible(true);
        return field.getBoolean(key);
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
