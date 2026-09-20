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

import static org.junit.Assert.assertArrayEquals;
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
public class VirtualControlsKeyboardResizeTest {
    private static final float EPS = 0.5f;

    private VirtualControlsKeyboard keyboard;
    private ProfileModel settings;
    private Object[] keypad;
    private File profileDir;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        profileDir = new File(context.getCacheDir(),
                "virtual-controls-resize-" + System.nanoTime());
        assertTrue(profileDir.mkdirs());

        settings = new ProfileModel();
        settings.dir = profileDir;
        settings.vkType = 3; // Numbers & Arrows legacy template.
        settings.vkFeedback = false;
        settings.vkAlpha = 255;

        keyboard = new VirtualControlsKeyboard(settings);
        keyboard.setView(new View(context));
        keyboard.resize(new RectF(0f, 0f, 1200f, 600f), 0f, 0f, 1200f, 600f);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);

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
    public void newProfileKeepsGroupedControlsDisabled() {
        ProfileModel profile = new ProfileModel(new File(profileDir, "new-profile"));

        assertFalse(profile.virtualDpadEnabled);
        assertFalse(profile.virtualAnalogEnabled);
    }

    @Test
    public void standardTemplatesEnableOnlyTheirIntendedGroupedControl() {
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        assertTrue(settings.virtualDpadEnabled);
        assertFalse(settings.virtualAnalogEnabled);

        keyboard.setLayout(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);
        assertFalse(settings.virtualDpadEnabled);
        assertTrue(settings.virtualAnalogEnabled);
    }

    @Test
    public void legacyBuiltInTemplateDisablesGroupedControls() {
        settings.virtualDpadEnabled = true;
        settings.virtualAnalogEnabled = true;

        keyboard.setLayout(3);

        assertFalse(settings.virtualDpadEnabled);
        assertFalse(settings.virtualAnalogEnabled);
    }

    @Test
    public void customLayoutPreservesExplicitGroupedSelections() {
        settings.virtualDpadEnabled = true;
        settings.virtualAnalogEnabled = true;

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);

        assertTrue(settings.virtualDpadEnabled);
        assertTrue(settings.virtualAnalogEnabled);
    }

    @Test
    public void malformedShortVisibilityArrayIsSafeNoOp() {
        boolean[] before = keyboard.getKeysVisibility();

        keyboard.setKeysVisibility(new boolean[] { true });

        assertArrayEquals(before, keyboard.getKeysVisibility());
    }

    @Test
    public void horizontalTwoFingerResizeChangesWidthOnly() throws Exception {
        RectF before = rectField(keyByLabel("L"));
        float cx = before.centerX();
        float cy = before.centerY();

        assertTrue(keyboard.pointerPressed(0, cx, cy));
        assertTrue(keyboard.pointerPressed(1, cx + 100f, cy));
        assertTrue(keyboard.pointerDragged(1, cx + 180f, cy));

        RectF after = rectField(keyByLabel("L"));
        assertTrue(after.width() > before.width());
        assertEquals(before.height(), after.height(), EPS);
    }

    @Test
    public void standardTemplateUsesRectangularShouldersAndCompactMovementControl() throws Exception {
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        RectF left = rectField(keyByLabel("L"));
        RectF right = rectField(keyByLabel("R"));
        RectF fire = rectField(keyByLabel("F"));
        RectF star = rectField(keyByLabel("*"));
        RectF zero = rectField(keyByLabel("0"));

        assertTrue(left.width() > left.height() * 1.4f);
        assertTrue(right.width() > right.height() * 1.4f);
        assertTrue(left.centerY() < fire.centerY());
        assertTrue(right.centerY() < fire.centerY());
        assertTrue(star.centerY() > fire.centerY());
        assertTrue(zero.centerY() > fire.centerY());
        assertEquals(0.202f, settings.virtualDpadRadius, 0.002f);
    }

    @Test
    public void standardTemplateUsesBottomDeckForPortraitGuest() throws Exception {
        float guestBottom = 1118f;
        keyboard.resize(new RectF(0f, 0f, 945f, 2048f), 18f, 0f, 812f, guestBottom);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        RectF left = rectField(keyByLabel("L"));
        RectF right = rectField(keyByLabel("R"));
        RectF fire = rectField(keyByLabel("F"));
        RectF star = rectField(keyByLabel("*"));
        RectF zero = rectField(keyByLabel("0"));

        assertTrue(left.top > guestBottom);
        assertTrue(right.top > guestBottom);
        assertTrue(fire.top > guestBottom);
        assertTrue(star.top > guestBottom);
        assertTrue(zero.top > guestBottom);
        assertTrue(settings.virtualDpadCenterY * 2048f - settings.virtualDpadRadius * 945f
                > guestBottom);
    }

    @Test
    public void standardTemplateUsesSideGuttersForWideGuest() throws Exception {
        float guestLeft = 503f;
        float guestRight = 1034f;
        keyboard.resize(
                new RectF(0f, 0f, 1536f, 709f),
                guestLeft, 0f, guestRight, 709f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        RectF left = rectField(keyByLabel("L"));
        RectF right = rectField(keyByLabel("R"));
        RectF fire = rectField(keyByLabel("F"));
        RectF star = rectField(keyByLabel("*"));
        RectF zero = rectField(keyByLabel("0"));

        float movementCenterX = settings.virtualDpadCenterX * 1536f;
        float movementRadius = settings.virtualDpadRadius * 709f;
        assertTrue(movementCenterX + movementRadius < guestLeft);
        assertTrue(left.right < guestLeft);
        assertTrue(right.left > guestRight);
        assertTrue(fire.left > guestRight);
        assertTrue(star.left > guestRight);
        assertTrue(zero.left > guestRight);
    }

    @Test
    public void standardTemplateReflowsWhenOnlyGuestViewportChanges() throws Exception {
        RectF screen = new RectF(0f, 0f, 945f, 2048f);
        keyboard.resize(screen, 0f, 0f, 945f, 2048f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        float before = rectField(keyByLabel("F")).centerY();

        keyboard.resize(screen, 18f, 0f, 812f, 1118f);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        float after = rectField(keyByLabel("F")).centerY();

        assertTrue(Math.abs(after - before) > 100f);
        assertTrue(rectField(keyByLabel("F")).top > 1118f);
    }

    @Test
    public void verticalTwoFingerResizeChangesHeightOnly() throws Exception {
        RectF before = rectField(keyByLabel("L"));
        float cx = before.centerX();
        float cy = before.centerY();

        assertTrue(keyboard.pointerPressed(0, cx, cy));
        assertTrue(keyboard.pointerPressed(1, cx, cy + 100f));
        assertTrue(keyboard.pointerDragged(1, cx, cy + 180f));

        RectF after = rectField(keyByLabel("L"));
        assertEquals(before.width(), after.width(), EPS);
        assertTrue(after.height() > before.height());
    }

    @Test
    public void semanticSnapshotRestoresLegacyEditAfterViewportChange() throws Exception {
        VirtualKeyboardLayoutSnapshot baseline = keyboard.captureLayoutSnapshot();
        RectF key = rectField(keyByLabel("L"));

        assertTrue(keyboard.pointerPressed(0, key.centerX(), key.centerY()));
        assertTrue(keyboard.pointerDragged(0, key.centerX() + 120f, key.centerY() + 40f));
        assertTrue(keyboard.pointerReleased(0, key.centerX() + 120f, key.centerY() + 40f));
        assertFalse(baseline.equals(keyboard.captureLayoutSnapshot()));

        keyboard.resize(new RectF(0f, 0f, 600f, 1200f), 0f, 0f, 600f, 1200f);
        keyboard.restoreLayoutSnapshot(baseline);

        assertEquals(baseline, keyboard.captureLayoutSnapshot());
    }

    @Test
    public void untouchedStandardTemplateStaysCleanAcrossOrientationReflow() {
        keyboard.resize(new RectF(0f, 0f, 945f, 2048f), 18f, 0f, 812f, 1118f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        VirtualKeyboardLayoutSnapshot baseline = keyboard.captureLayoutSnapshot();

        keyboard.resize(
                new RectF(0f, 0f, 1536f, 709f),
                503f, 0f, 1034f, 709f);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertEquals(baseline, keyboard.captureLayoutSnapshot());
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
