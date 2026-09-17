from pathlib import Path
import re

SOURCE_PATH = Path("app/src/main/java/javax/microedition/lcdui/keyboard/VirtualKeyboard.java")
TEST_PATH = Path("app/src/androidTest/java/javax/microedition/lcdui/keyboard/LegacyVirtualKeyboardTouchTest.java")

source = SOURCE_PATH.read_text(encoding="utf-8")

for name in (
    "java.util.HashMap",
    "java.util.Map",
    "java.util.Set",
    "io.github.h3nb.jlmodplus.input.PointerSourceKind",
    "io.github.h3nb.jlmodplus.input.PointerSourceToken",
    "io.github.h3nb.jlmodplus.input.VirtualDpadController",
    "io.github.h3nb.jlmodplus.input.VirtualDpadDirection",
    "io.github.h3nb.jlmodplus.input.VirtualDpadGeometry",
):
    needle = f"import {name};\n"
    assert source.count(needle) == 1, f"unexpected import count for {name}"
    source = source.replace(needle, "")

state_block = """\tprivate final VirtualDpadController virtualDpad = new VirtualDpadController();
\tprivate final Map<Integer, VirtualDpadContact> virtualDpadContacts = new HashMap<>();
\tprivate long pointerSourceSequence;
\tprivate long virtualDpadSequence;

\tprivate static final class VirtualDpadContact {
\t\tfinal PointerSourceToken token;
\t\tfinal VirtualDpadGeometry geometry;
\t\tfinal String channel;
\t\tfinal Canvas canvas;

\t\tVirtualDpadContact(PointerSourceToken token, VirtualDpadGeometry geometry, String channel,
\t\t\t\tCanvas canvas) {
\t\t\tthis.token = token;
\t\t\tthis.geometry = geometry;
\t\t\tthis.channel = channel;
\t\t\tthis.canvas = canvas;
\t\t}
\t}
"""
assert source.count(state_block) == 1, "legacy D-pad state block changed unexpectedly"
source = source.replace(state_block, "\tprivate long pointerSourceSequence;\n")

needle = "\t\t\t\tendVirtualDpad(pointer);\n"
assert source.count(needle) == 1, "pointerPressed D-pad reset changed unexpectedly"
source = source.replace(needle, "")

old_press = """\t\t\t\tif (beginVirtualDpad(pointer, x, y)) {
\t\t\t\t\tconsumed = true;
\t\t\t\t} else for (VirtualKey key : keypad) {
"""
assert source.count(old_press) == 1, "pointerPressed interception changed unexpectedly"
source = source.replace(old_press, "\t\t\t\tfor (VirtualKey key : keypad) {\n")

old_drag = """\t\t\t\tif (moveVirtualDpad(pointer, x, y)) {
\t\t\t\t\treturn true;
\t\t\t\t}
"""
assert source.count(old_drag) == 1, "pointerDragged interception changed unexpectedly"
source = source.replace(old_drag, "")

old_release = """\t\t\tif (endVirtualDpad(pointer)) {
\t\t\t\tif (overlayView != null) overlayView.postInvalidate();
\t\t\t\treturn true;
\t\t\t}
"""
assert source.count(old_release) == 1, "pointerReleased interception changed unexpectedly"
source = source.replace(old_release, "")

needle = "\t\tcancelVirtualDpadContacts();\n"
assert source.count(needle) == 1, "cancel D-pad cleanup changed unexpectedly"
source = source.replace(needle, "")

method_block = re.compile(
    r"\n\tprivate boolean beginVirtualDpad\(int pointer, float x, float y\) \{.*?"
    r"\n\t@Override\n\tpublic void run\(\) \{",
    re.DOTALL,
)
source, count = method_block.subn("\n\t@Override\n\tpublic void run() {", source, count=1)
assert count == 1, "legacy D-pad helper block changed unexpectedly"

for token in (
    "beginVirtualDpad(",
    "moveVirtualDpad(",
    "endVirtualDpad(",
    "cancelVirtualDpadContacts(",
    "virtualDpadBounds(",
    "VirtualDpadContact",
    "virtualDpadContacts",
):
    assert token not in source, f"legacy D-pad interception remains: {token}"

SOURCE_PATH.write_text(source, encoding="utf-8")

TEST_PATH.parent.mkdir(parents=True, exist_ok=True)
TEST_PATH.write_text(r'''/*
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
''', encoding="utf-8")
