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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.input.GuestViewport;
import io.github.h3nb.jlmodplus.input.VirtualAnalogStick;
import io.github.h3nb.jlmodplus.input.VirtualAnalogVisualState;
import io.github.h3nb.jlmodplus.input.VirtualDpadGeometry;

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

        refreshKeypadReflection();
    }

    @After
    public void tearDown() throws Exception {
        disposeKeyboard(keyboard);
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
    public void standardDirectPlacementHasReconstructibleScreenSnapState() throws Exception {
        RectF screen = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(screen, 0f, 0f, 1200f, 600f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        for (String label : new String[] { "F", "L", "R", "*", "0" }) {
            Object key = keyByLabel(label);
            RectF generated = rectField(key);
            int origin = intField(key, "snapOrigin");
            int mode = intField(key, "snapMode");
            PointF offset = pointField(key, "snapOffset");

            assertEquals(-1, origin);
            assertNotEquals(RectSnap.NO_SNAP, mode);

            RectF reconstructed = new RectF(0f, 0f, generated.width(), generated.height());
            RectSnap.snap(reconstructed, screen, mode, offset);
            assertEquals(generated.centerX(), reconstructed.centerX(), EPS);
            assertEquals(generated.centerY(), reconstructed.centerY(), EPS);
        }
    }

    @Test
    public void editedDpadStandardRotatesBeforeSaveWithoutStaleLegacyPixels() throws Exception {
        assertEditedStandardRotatesBeforeSave(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, "dpadGeometry");
    }

    @Test
    public void editedAnalogStandardRotatesBeforeSaveWithoutStaleLegacyPixels() throws Exception {
        assertEditedStandardRotatesBeforeSave(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD, "analogGeometry");
    }

    @Test
    public void standardCustomizationSurvivesRealDiskRoundTrip() throws Exception {
        RectF screen = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(screen, 0f, 0f, 1200f, 600f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        VirtualDpadGeometry dpad = geometry("dpadGeometry");
        assertTrue(keyboard.pointerPressed(0, dpad.getCenterX(), dpad.getCenterY()));
        assertTrue(keyboard.pointerDragged(0, dpad.getCenterX() + 20f, dpad.getCenterY()));
        assertTrue(keyboard.pointerReleased(0, dpad.getCenterX() + 20f, dpad.getCenterY()));
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        float[][] before = legacyCenters();
        recreateKeyboardFromDisk(screen);

        assertEquals(VirtualKeyboard.TYPE_CUSTOM, keyboard.getLayout());
        float[][] after = legacyCenters();
        for (int i = 0; i < before.length; i++) {
            assertEquals(before[i][0], after[i][0], EPS);
            assertEquals(before[i][1], after[i][1], EPS);
        }
    }

    @Test
    public void analogVisualHitTestAndStickUseSameResolvedCenter() throws Exception {
        settings.virtualDpadEnabled = false;
        settings.virtualAnalogEnabled = true;
        settings.virtualAnalogCenterX = 0.98f;
        settings.virtualAnalogCenterY = 0.98f;
        settings.virtualAnalogRadius = 0.34f;
        RectF screen = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(screen, 0f, 0f, 1200f, 600f);

        VirtualDpadGeometry effective = geometry("analogGeometry");
        assertGeometryInside(effective, screen);
        assertTrue(invokeInsideAnalog(effective.getCenterX(), effective.getCenterY(), 1.0f));

        Field stickField = VirtualControlsKeyboard.class.getDeclaredField("analogStick");
        stickField.setAccessible(true);
        VirtualAnalogStick stick = (VirtualAnalogStick) stickField.get(keyboard);
        Field viewportField = VirtualControlsKeyboard.class.getDeclaredField("viewport");
        viewportField.setAccessible(true);
        GuestViewport viewport = (GuestViewport) viewportField.get(keyboard);
        VirtualAnalogVisualState visual = stick.visualState(viewport);

        assertEquals(effective.getCenterX(), screen.left + visual.getCenterX(), EPS);
        assertEquals(effective.getCenterY(), screen.top + visual.getCenterY(), EPS);
        assertEquals(effective.getRadius(), visual.getRadius(), EPS);
    }

    @Test
    public void passiveRotationResolvesGroupedControlWithoutStoredDrift() throws Exception {
        settings.virtualDpadEnabled = true;
        settings.virtualAnalogEnabled = false;
        settings.virtualDpadCenterX = 0.50f;
        settings.virtualDpadCenterY = 0.83f;
        settings.virtualDpadRadius = 0.20f;

        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        VirtualDpadGeometry firstPortrait = geometry("dpadGeometry");
        assertGeometryInside(firstPortrait, portrait);

        float preferredX = settings.virtualDpadCenterX;
        float preferredY = settings.virtualDpadCenterY;
        float preferredRadius = settings.virtualDpadRadius;

        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        VirtualDpadGeometry landscapeGeometry = geometry("dpadGeometry");
        assertGeometryInside(landscapeGeometry, landscape);
        assertEquals(480f, landscapeGeometry.getCenterY(), EPS);
        assertEquals(preferredX, settings.virtualDpadCenterX, 0.0001f);
        assertEquals(preferredY, settings.virtualDpadCenterY, 0.0001f);
        assertEquals(preferredRadius, settings.virtualDpadRadius, 0.0001f);

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        VirtualDpadGeometry secondPortrait = geometry("dpadGeometry");
        assertGeometryInside(secondPortrait, portrait);
        assertEquals(firstPortrait.getCenterX(), secondPortrait.getCenterX(), EPS);
        assertEquals(firstPortrait.getCenterY(), secondPortrait.getCenterY(), EPS);
        assertEquals(preferredY, settings.virtualDpadCenterY, 0.0001f);
    }

    @Test
    public void nearEdgePinchCommitsAViewportSafePreferredCenter() throws Exception {
        settings.virtualDpadEnabled = false;
        settings.virtualAnalogEnabled = true;
        settings.virtualAnalogCenterX = 0.90f;
        settings.virtualAnalogCenterY = 0.90f;
        settings.virtualAnalogRadius = 0.10f;
        RectF screen = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(screen, 0f, 0f, 1200f, 600f);

        VirtualDpadGeometry start = geometry("analogGeometry");
        assertTrue(keyboard.pointerPressed(0, start.getCenterX(), start.getCenterY()));
        assertTrue(keyboard.pointerPressed(1, start.getCenterX() + 30f, start.getCenterY()));
        assertTrue(keyboard.pointerDragged(1, start.getCenterX() + 300f, start.getCenterY()));
        assertTrue(keyboard.pointerReleased(1, start.getCenterX() + 300f, start.getCenterY()));
        assertTrue(keyboard.pointerReleased(0, start.getCenterX(), start.getCenterY()));

        VirtualDpadGeometry edited = geometry("analogGeometry");
        assertGeometryInside(edited, screen);
        assertTrue(settings.virtualAnalogRadius > 0.10f);
        assertEquals(edited.getCenterX() / screen.width(), settings.virtualAnalogCenterX, 0.002f);
        assertEquals(edited.getCenterY() / screen.height(), settings.virtualAnalogCenterY, 0.002f);
    }

    @Test
    public void legacyCustomNoSnapEntryKeepsSafeFallbackTopology() throws Exception {
        RectF screen = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(screen, 0f, 0f, 1200f, 600f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        writeLegacyV3Layout(keyboard.captureLayoutSnapshot());

        Object fire = keyByLabel("F");
        int fireHash = fire.hashCode();
        patchKeyAsNoSnap(fireHash);
        recreateKeyboardFromDisk(screen);

        Object recovered = keyByLabel("F");
        assertNotEquals(RectSnap.NO_SNAP, intField(recovered, "snapMode"));
        RectF recoveredRect = rectField(recovered);
        assertTrue(recoveredRect.centerX() > 1f);
        assertTrue(recoveredRect.centerY() > 1f);
    }

    @Test
    public void malformedCustomLayoutFilesFallBackWithoutCrashing() throws Exception {
        int knownHash = keyByLabel("F").hashCode();

        writeExcessiveKeyCountLayout();
        assertMalformedLayoutFallsBack();

        writeExcessiveScaleCountLayout();
        assertMalformedLayoutFallsBack();

        writeSingleKeyLayout(knownHash, 99, RectSnap.INT_NORTHWEST, 0f, 0f);
        assertMalformedLayoutFallsBack();

        writeSingleKeyLayout(knownHash, -1, RectSnap.INT_NORTHWEST, Float.NaN, 0f);
        assertMalformedLayoutFallsBack();

        writeTruncatedLayout();
        assertMalformedLayoutFallsBack();
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

    @Test
    public void passiveStandardReflowDoesNotCreateCustomDraftOrDirtyTransactionState() {
        keyboard.resize(new RectF(0f, 0f, 945f, 2048f), 18f, 0f, 812f, 1118f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        VirtualKeyboardLayoutEditState baseline = keyboard.captureLayoutEditState();

        keyboard.resize(
                new RectF(0f, 0f, 1536f, 709f),
                503f, 0f, 1034f, 709f);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertEquals(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, keyboard.getLayout());
        assertEquals(baseline, keyboard.captureLayoutEditState());
    }

    @Test
    public void portraitAndLandscapeDpadOverridesAreIndependentAndDriftFree() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        dragGrouped("dpadGeometry", 36f, -24f);
        dragLegacy("F", 24f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        VirtualKeyboardLayoutState portraitOnly =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(portraitOnly.portraitOverride());
        assertEquals(null, portraitOnly.landscapeOverride());
        float portraitFireX = rectField(keyByLabel("F")).centerX();
        float portraitFireY = rectField(keyByLabel("F")).centerY();
        float portraitDpadX = geometry("dpadGeometry").getCenterX();
        float portraitDpadY = geometry("dpadGeometry").getCenterY();

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertEquals(VirtualKeyboard.TYPE_CUSTOM, keyboard.getLayout());
        assertFreshStandardGeometry(landscape, 0f, 0f, 1200f, 600f, "dpadGeometry");
        assertEquals(null,
                keyboard.captureLayoutEditState().customLayout().landscapeOverride());

        dragGrouped("dpadGeometry", -42f, 18f);
        dragLegacy("F", -30f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState both = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(both.portraitOverride());
        assertNotNull(both.landscapeOverride());
        float landscapeFireX = rectField(keyByLabel("F")).centerX();
        float landscapeFireY = rectField(keyByLabel("F")).centerY();
        float landscapeDpadX = geometry("dpadGeometry").getCenterX();
        float landscapeDpadY = geometry("dpadGeometry").getCenterY();

        for (int i = 0; i < 3; i++) {
            keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
            assertEquals(portraitFireX, rectField(keyByLabel("F")).centerX(), EPS);
            assertEquals(portraitFireY, rectField(keyByLabel("F")).centerY(), EPS);
            assertEquals(portraitDpadX, geometry("dpadGeometry").getCenterX(), EPS);
            assertEquals(portraitDpadY, geometry("dpadGeometry").getCenterY(), EPS);
            assertEquals(both, keyboard.captureLayoutEditState().customLayout());

            keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
            assertEquals(landscapeFireX, rectField(keyByLabel("F")).centerX(), EPS);
            assertEquals(landscapeFireY, rectField(keyByLabel("F")).centerY(), EPS);
            assertEquals(landscapeDpadX, geometry("dpadGeometry").getCenterX(), EPS);
            assertEquals(landscapeDpadY, geometry("dpadGeometry").getCenterY(), EPS);
            assertEquals(both, keyboard.captureLayoutEditState().customLayout());
        }
    }

    @Test
    public void analogStandardUsesIndependentOrientationSlots() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);

        dragGrouped("analogGeometry", 24f, -36f);
        dragLegacy("F", 18f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutSnapshot portraitOverride =
                keyboard.captureLayoutEditState().customLayout().portraitOverride();
        assertNotNull(portraitOverride);

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertFreshStandardGeometry(
                landscape, 0f, 0f, 1200f, 600f, "analogGeometry");
        dragGrouped("analogGeometry", -36f, 24f);
        dragLegacy("F", -24f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState state = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(state.landscapeOverride());

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertEquals(portraitOverride, state.portraitOverride());
        assertAnalogVisualMatchesResolvedGeometry();

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertEquals(state, keyboard.captureLayoutEditState().customLayout());
        assertAnalogVisualMatchesResolvedGeometry();
    }

    @Test
    public void onePortraitOverrideRestartsLandscapeFromFreshStandardBase() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 30f, -30f);
        dragLegacy("F", 24f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        float portraitFire = rectField(keyByLabel("F")).centerX();

        recreateKeyboardFromDisk(landscape);
        VirtualKeyboardLayoutState loadedLandscape =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(loadedLandscape.portraitOverride());
        assertEquals(null, loadedLandscape.landscapeOverride());
        assertFreshStandardGeometry(landscape, 0f, 0f, 1200f, 600f, "dpadGeometry");

        recreateKeyboardFromDisk(portrait);
        assertEquals(portraitFire, rectField(keyByLabel("F")).centerX(), EPS);
    }

    @Test
    public void bothOrientationOverridesSurviveRealDiskRecreation() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 30f, -18f);
        dragLegacy("F", 18f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -30f, 18f);
        dragLegacy("F", -18f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState saved = keyboard.captureLayoutEditState().customLayout();

        recreateKeyboardFromDisk(portrait);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
        float portraitFire = rectField(keyByLabel("F")).centerX();

        // An unrelated profile save may capture only the current working geometry, but v4 remains
        // authoritative for both orientation slots.
        ProfilesManager.saveConfig(settings);
        recreateKeyboardFromDisk(landscape);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
        assertNotEquals(portraitFire, rectField(keyByLabel("F")).centerX(), EPS);
    }

    @Test
    public void dormantCustomSurvivesBuiltInRestartAndRestoresBothOrientations() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState saved =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.setLayout(3);
        assertEquals(3, keyboard.getLayout());
        assertEquals(saved, keyboard.captureLayoutEditState().dormantCustomLayout());

        recreateKeyboardFromDisk(landscape);
        assertEquals(3, keyboard.getLayout());
        assertEquals(saved, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(VirtualKeyboard.TYPE_CUSTOM, keyboard.getLayout());
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void multipleBuiltInSelectionsDoNotReplaceDormantCustomDefinition() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState saved =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        for (int type : new int[] { 3, 6, 2 }) {
            keyboard.setLayout(type);
            assertEquals(type, keyboard.getLayout());
            assertEquals(saved, keyboard.captureLayoutEditState().dormantCustomLayout());
        }

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void portraitOnlyOverrideSurvivesDormantBuiltInRestart() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        dragLegacy("F", 18f, 0f);
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
        VirtualKeyboardLayoutState saved = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(saved.portraitOverride());
        assertEquals(null, saved.landscapeOverride());

        keyboard.setLayout(3);
        recreateKeyboardFromDisk(landscape);
        assertEquals(3, keyboard.getLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
        assertFreshStandardGeometry(
                landscape, 0f, 0f, 1200f, 600f, "dpadGeometry");
    }

    @Test
    public void dormantV3PayloadWithBuiltInTypeMigratesBeforeAnyRewrite() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        dragLegacy("F", 18f, 0f);
        writeLegacyV3Layout(keyboard.captureLayoutSnapshot());
        patchLayoutType(3);

        settings.virtualDpadEnabled = true;
        settings.virtualAnalogEnabled = false;
        ProfilesManager.saveConfig(settings);
        recreateKeyboardFromDisk(portrait);

        assertEquals(3, keyboard.getLayout());
        VirtualKeyboardLayoutState dormant =
                keyboard.captureLayoutEditState().dormantCustomLayout();
        assertNotNull(dormant);
        assertNotNull(dormant.legacySharedFallback());
        assertTrue(dormant.legacySharedFallback().hasGroupedControls);
        assertTrue(dormant.legacySharedFallback().dpadEnabled);

        keyboard.setLayout(6);
        recreateKeyboardFromDisk(portrait);
        assertEquals(6, keyboard.getLayout());
        assertNotNull(keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState restored =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(restored.legacySharedFallback());
        assertTrue(restored.legacySharedFallback().dpadEnabled);
    }

    @Test
    public void migratedV3FallbackSurvivesDormantBuiltInRoundTrip() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        writeLegacyV3Layout(keyboard.captureLayoutSnapshot());

        recreateKeyboardFromDisk(portrait);
        VirtualKeyboardLayoutState migrated = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(migrated.legacySharedFallback());

        keyboard.setLayout(3);
        recreateKeyboardFromDisk(portrait);
        assertEquals(3, keyboard.getLayout());
        assertEquals(migrated, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState restored = keyboard.captureLayoutEditState().customLayout();
        assertEquals(migrated, restored);
        assertNotNull(restored.legacySharedFallback());
    }

    @Test
    public void discardFromBuiltInRestoresPreviousDormantCustomState() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState saved =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);
        keyboard.setLayout(3);
        VirtualKeyboardLayoutEditState baseline = keyboard.captureLayoutEditState();
        assertEquals(saved, baseline.dormantCustomLayout());

        keyboard.setLayoutForEditing(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);
        assertEquals(
                VirtualControlsKeyboard.TYPE_ANALOG_STANDARD,
                keyboard.captureLayoutEditState().customLayout().baseVariant());

        keyboard.restoreLayoutEditState(baseline);
        assertEquals(3, keyboard.getLayout());
        assertEquals(saved, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void malformedDormantCustomStateRejectsWholeV4Artifact() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        createAndSaveTwoOrientationDpadCustom(portrait, landscape);
        keyboard.setLayout(3);
        truncateInsideV4LandscapeOverride();

        settings.vkType = 6;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VirtualControlsKeyboard candidate = null;
        try {
            candidate = new VirtualControlsKeyboard(settings);
            candidate.setView(new View(context));
            candidate.resize(landscape, 0f, 0f, 1200f, 600f);
            assertEquals(6, candidate.getLayout());
        } finally {
            disposeKeyboard(candidate);
        }
    }

    @Test
    public void failedInvariantSaveKeepsBothOrientationDraftsAndCanRetry() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        dragGrouped("dpadGeometry", 24f, -18f);
        dragLegacy("F", 18f, 0f);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -24f, 18f);
        dragLegacy("F", -18f, 0f);

        PointF liveOffset = liveSnapOffset("F");
        float originalX = liveOffset.x;
        liveOffset.x = Float.NaN;
        assertFalse(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));

        VirtualKeyboardLayoutState failedDraft =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(failedDraft.portraitOverride());
        assertNotNull(failedDraft.landscapeOverride());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VirtualControlsKeyboard persisted = null;
        try {
            ProfileModel persistedSettings = ProfilesManager.loadConfig(profileDir);
            assertNotNull(persistedSettings);
            persisted = new VirtualControlsKeyboard(persistedSettings);
            persisted.setView(new View(context));
            persisted.resize(landscape, 0f, 0f, 1200f, 600f);
            assertEquals(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, persisted.getLayout());
        } finally {
            disposeKeyboard(persisted);
        }

        liveOffset.x = originalX;
        dragLegacy("F", 2f, 0f);
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
        VirtualKeyboardLayoutState saved = keyboard.captureLayoutEditState().customLayout();

        recreateKeyboardFromDisk(portrait);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
        recreateKeyboardFromDisk(landscape);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void ioFailurePreservesPreviousLayoutFileAndRetryCommitsDraft() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState persistedState =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        dragLegacy("F", 16f, 0f);
        VirtualKeyboardLayoutEditState dirty = keyboard.captureLayoutEditState();
        assertNotEquals(
                persistedState.portraitOverride(),
                dirty.customLayout().portraitOverride());

        File blocker = new File(layoutFile().getPath() + ".new");
        assertTrue(blocker.mkdir());
        File blockerChild = new File(blocker, "keep");
        assertTrue(blockerChild.createNewFile());

        assertFalse(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
        VirtualKeyboardLayoutState retryState =
                keyboard.captureLayoutEditState().customLayout();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VirtualControlsKeyboard candidate = null;
        try {
            ProfileModel persistedSettings = ProfilesManager.loadConfig(profileDir);
            assertNotNull(persistedSettings);
            candidate = new VirtualControlsKeyboard(persistedSettings);
            candidate.setView(new View(context));
            candidate.resize(portrait, 0f, 0f, 600f, 1200f);
            assertEquals(VirtualKeyboard.TYPE_CUSTOM, candidate.getLayout());
            assertEquals(
                    persistedState,
                    candidate.captureLayoutEditState().customLayout());
        } finally {
            disposeKeyboard(candidate);
        }

        assertTrue(blockerChild.delete());
        assertTrue(blocker.delete());
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));

        recreateKeyboardFromDisk(portrait);
        assertEquals(retryState, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void unsavedHideButtonsCannotReplaceDormantCustomOnLaterLayoutSwitch() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState committed =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.setLayout(3);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        assertNotEquals(committed, keyboard.captureLayoutEditState().customLayout());

        keyboard.setLayout(6);
        assertEquals(committed, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void rejectedHideButtonsWithoutPreviousCustomCreatesNoDormantDefinition() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(3);
        assertEquals(null, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        assertNotNull(keyboard.captureLayoutEditState().customLayout());

        keyboard.setLayout(6);
        recreateKeyboardFromDisk(portrait);
        assertEquals(6, keyboard.getLayout());
        assertEquals(null, keyboard.captureLayoutEditState().dormantCustomLayout());

        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(null, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void nonEditorHideButtonsSaveCommitsAndSurvivesRestart() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);

        setButtonHidden("F", true);
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
        VirtualKeyboardLayoutState committed =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(committed.portraitOverride());
        assertEquals(null, committed.landscapeOverride());

        recreateKeyboardFromDisk(portrait);
        assertEquals(VirtualKeyboard.TYPE_CUSTOM, keyboard.getLayout());
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
        assertTrue(isButtonHidden("F"));
    }

    @Test
    public void nonEditorHideButtonsSaveUpdatesOnlyActiveCustomOrientation() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState original =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));

        VirtualKeyboardLayoutState committed =
                keyboard.captureLayoutEditState().customLayout();
        assertNotEquals(original.portraitOverride(), committed.portraitOverride());
        assertEquals(original.landscapeOverride(), committed.landscapeOverride());

        recreateKeyboardFromDisk(portrait);
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
        assertTrue(isButtonHidden("F"));
        recreateKeyboardFromDisk(landscape);
        assertEquals(committed, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void failedNonEditorSaveKeepsWorkingMutationAndBaselineForRollback() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState original =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        assertTrue(isButtonHidden("F"));

        File blocker = new File(layoutFile().getPath() + ".new");
        assertTrue(blocker.mkdir());
        File blockerChild = new File(blocker, "keep");
        assertTrue(blockerChild.createNewFile());

        assertFalse(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
        assertTrue(isButtonHidden("F"));
        assertNotEquals(original, keyboard.captureLayoutEditState().customLayout());

        assertTrue(blockerChild.delete());
        assertTrue(blocker.delete());

        keyboard.setLayout(6);
        assertEquals(original, keyboard.captureLayoutEditState().dormantCustomLayout());
        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(original, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void multipleUnsavedHideButtonMutationsKeepFirstCommittedBaseline() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState original =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.setLayout(3);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        setButtonHidden("L", true);
        assertTrue(isButtonHidden("F"));
        assertTrue(isButtonHidden("L"));

        keyboard.setLayout(6);
        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(original, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void passiveRotationDoesNotCommitOrReplacePendingHideButtonsBaseline() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState original =
                createAndSaveTwoOrientationDpadCustom(portrait, landscape);

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        setButtonHidden("F", true);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);

        assertNotEquals(original, keyboard.captureLayoutEditState().customLayout());

        keyboard.setLayout(3);
        keyboard.setLayout(VirtualKeyboard.TYPE_CUSTOM);
        assertEquals(original, keyboard.captureLayoutEditState().customLayout());
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertEquals(original, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void absentStandardOverrideReflowsButExistingOverrideDoesNotRegenerate() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragLegacy("F", 24f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        float portraitFireBefore = rectField(keyByLabel("F")).centerY();
        VirtualKeyboardLayoutSnapshot portraitOverride =
                keyboard.captureLayoutEditState().customLayout().portraitOverride();
        keyboard.resize(portrait, 0f, 0f, 600f, 500f);
        assertEquals(portraitFireBefore, rectField(keyByLabel("F")).centerY(), EPS);
        assertEquals(portraitOverride,
                keyboard.captureLayoutEditState().customLayout().portraitOverride());

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        float landscapeBefore = rectField(keyByLabel("F")).centerX();
        keyboard.resize(landscape, 400f, 0f, 800f, 600f);
        float landscapeAfter = rectField(keyByLabel("F")).centerX();
        assertTrue(Math.abs(landscapeAfter - landscapeBefore) > 20f);
        assertEquals(null,
                keyboard.captureLayoutEditState().customLayout().landscapeOverride());
    }

    @Test
    public void editingBothOrientationsThenSingleSavePersistsBothDrafts() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        dragGrouped("dpadGeometry", 30f, -18f);
        dragLegacy("F", 18f, 0f);
        // No save here. Rotation must stash the Portrait draft only in memory.
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState afterRotation =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(afterRotation.portraitOverride());
        assertEquals(null, afterRotation.landscapeOverride());

        dragGrouped("dpadGeometry", -30f, 18f);
        dragLegacy("F", -18f, 0f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM); // one final Save
        VirtualKeyboardLayoutState saved =
                keyboard.captureLayoutEditState().customLayout();
        assertNotNull(saved.portraitOverride());
        assertNotNull(saved.landscapeOverride());

        recreateKeyboardFromDisk(portrait);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());

        recreateKeyboardFromDisk(landscape);
        assertEquals(saved, keyboard.captureLayoutEditState().customLayout());
    }

    @Test
    public void editingBothOrientationsThenDiscardRestoresCompleteBaseline() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        VirtualKeyboardLayoutEditState baseline = keyboard.captureLayoutEditState();

        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -24f, 18f);
        assertNotEquals(baseline, keyboard.captureLayoutEditState());

        keyboard.restoreLayoutEditState(baseline);
        assertEquals(baseline, keyboard.captureLayoutEditState());

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertEquals(baseline, keyboard.captureLayoutEditState());

        // No editor mutation was persisted while the transaction was live.
        recreateKeyboardFromDisk(portrait);
        assertEquals(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, keyboard.getLayout());
    }

    @Test
    public void malformedV4AfterValidPortraitBlockFallsBackWithoutPartialApply() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -24f, 18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        truncateInsideV4LandscapeOverride();

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VirtualControlsKeyboard candidate = null;
        try {
            candidate = new VirtualControlsKeyboard(settings);
            candidate.setView(new View(context));
            candidate.resize(portrait, 0f, 0f, 600f, 1200f);
            assertNotEquals(VirtualKeyboard.TYPE_CUSTOM, candidate.getLayout());
        } finally {
            disposeKeyboard(candidate);
        }
    }

    @Test
    public void templateSwitchClearsDraftOverridesAndDiscardRestoresCompleteState() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -24f, 18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutEditState baseline = keyboard.captureLayoutEditState();

        keyboard.setLayoutForEditing(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);
        VirtualKeyboardLayoutState draft = keyboard.captureLayoutEditState().customLayout();
        assertEquals(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD, draft.baseVariant());
        assertEquals(null, draft.portraitOverride());
        assertEquals(null, draft.landscapeOverride());
        assertEquals(null, draft.legacySharedFallback());
        assertEquals(VirtualKeyboard.TYPE_CUSTOM, keyboard.getLayout());

        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        assertTrue(settings.virtualAnalogEnabled);
        assertFalse(settings.virtualDpadEnabled);
        assertEquals(null,
                keyboard.captureLayoutEditState().customLayout().portraitOverride());

        keyboard.restoreLayoutEditState(baseline);
        assertEquals(baseline, keyboard.captureLayoutEditState());

        keyboard.setLayoutForEditing(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState committed = keyboard.captureLayoutEditState().customLayout();
        assertEquals(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD, committed.baseVariant());
        assertEquals(null, committed.portraitOverride());
        assertEquals(null, committed.landscapeOverride());
        assertEquals(null, committed.legacySharedFallback());
    }

    @Test
    public void reselectingActiveCustomDoesNotDiscardUnsavedDraft() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        dragLegacy("F", 30f, 0f);
        VirtualKeyboardLayoutEditState draftBefore = keyboard.captureLayoutEditState();
        float fireBefore = rectField(keyByLabel("F")).centerX();

        keyboard.setLayoutForEditing(VirtualKeyboard.TYPE_CUSTOM);

        assertEquals(draftBefore, keyboard.captureLayoutEditState());
        assertEquals(fireBefore, rectField(keyByLabel("F")).centerX(), EPS);
    }

    @Test
    public void templateSwitchSavePersistsNewBaseWithoutStaleOverrides() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        dragGrouped("dpadGeometry", -24f, 18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        keyboard.setLayoutForEditing(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        recreateKeyboardFromDisk(portrait);
        VirtualKeyboardLayoutState portraitState =
                keyboard.captureLayoutEditState().customLayout();
        assertEquals(VirtualControlsKeyboard.TYPE_ANALOG_STANDARD, portraitState.baseVariant());
        assertEquals(null, portraitState.portraitOverride());
        assertEquals(null, portraitState.landscapeOverride());
        assertEquals(null, portraitState.legacySharedFallback());
        assertTrue(settings.virtualAnalogEnabled);
        assertFalse(settings.virtualDpadEnabled);
        assertFreshStandardGeometry(
                portrait, 0f, 0f, 600f, 1200f, "analogGeometry");

        recreateKeyboardFromDisk(landscape);
        VirtualKeyboardLayoutState landscapeState =
                keyboard.captureLayoutEditState().customLayout();
        assertEquals(portraitState, landscapeState);
        assertTrue(settings.virtualAnalogEnabled);
        assertFalse(settings.virtualDpadEnabled);
        assertFreshStandardGeometry(
                landscape, 0f, 0f, 1200f, 600f, "analogGeometry");
    }

    @Test
    public void v3MigrationKeepsSharedFallbackUntilBothOrientationsAreEdited() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        writeLegacyV3Layout(keyboard.captureLayoutSnapshot());

        recreateKeyboardFromDisk(portrait);
        VirtualKeyboardLayoutState migrated = keyboard.captureLayoutEditState().customLayout();
        assertEquals(VirtualKeyboardLayoutState.BASE_UNKNOWN, migrated.baseVariant());
        assertNotNull(migrated.legacySharedFallback());

        dragGrouped("dpadGeometry", 24f, -18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState portraitEdited = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(portraitEdited.legacySharedFallback());
        assertNotNull(portraitEdited.portraitOverride());
        assertEquals(null, portraitEdited.landscapeOverride());
        VirtualKeyboardLayoutSnapshot savedPortrait = portraitEdited.portraitOverride();
        VirtualKeyboardLayoutSnapshot sharedFallback = portraitEdited.legacySharedFallback();

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        VirtualKeyboardLayoutState landscapeBeforeEdit =
                keyboard.captureLayoutEditState().customLayout();
        assertEquals(null, landscapeBeforeEdit.landscapeOverride());
        assertEquals(sharedFallback, landscapeBeforeEdit.legacySharedFallback());
        assertEquals(sharedFallback, keyboard.captureLayoutSnapshot().asCustomOverride());

        dragGrouped("dpadGeometry", -24f, 18f);
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
        VirtualKeyboardLayoutState both = keyboard.captureLayoutEditState().customLayout();
        assertEquals(null, both.legacySharedFallback());
        assertEquals(savedPortrait, both.portraitOverride());
        assertNotNull(both.landscapeOverride());
    }

    @Test
    public void hostBoundsOwnOrientationAndSquareTransitionKeepsPreviousSlot() throws Exception {
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(landscape, 500f, 0f, 700f, 600f);
        assertEquals(VirtualLayoutOrientation.LANDSCAPE, activeOrientation());

        keyboard.resize(new RectF(0f, 0f, 800f, 800f), 300f, 0f, 500f, 800f);
        assertEquals(VirtualLayoutOrientation.LANDSCAPE, activeOrientation());

        keyboard.resize(new RectF(0f, 0f, 600f, 1200f), 200f, 0f, 400f, 1200f);
        assertEquals(VirtualLayoutOrientation.PORTRAIT, activeOrientation());
    }

    @Test
    public void orientationSwitchCancelsLegacyInputAndEditorGesture() throws Exception {
        keyboard.setLayout(3);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
        RectF fire = rectField(keyByLabel("F"));
        assertTrue(keyboard.pointerPressed(0, fire.centerX(), fire.centerY()));
        assertTrue(booleanField(keyByLabel("F"), "selected"));

        keyboard.resize(new RectF(0f, 0f, 600f, 1200f), 0f, 0f, 600f, 1200f);
        assertFalse(booleanField(keyByLabel("F"), "selected"));

        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
        RectF left = rectField(keyByLabel("L"));
        assertTrue(keyboard.pointerPressed(0, left.centerX(), left.centerY()));
        assertTrue(intField(keyboard, "legacyEditPointer") >= 0);

        keyboard.resize(new RectF(0f, 0f, 1200f, 600f), 0f, 0f, 1200f, 600f);
        assertEquals(-1, intField(keyboard, "legacyEditPointer"));
    }

    @Test
    public void rotationMidLegacyDragStashesReconstructiblePortraitOverride() throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);

        RectF fire = rectField(keyByLabel("F"));
        assertTrue(keyboard.pointerPressed(0, fire.centerX(), fire.centerY()));
        assertTrue(keyboard.pointerDragged(0, fire.centerX() + 30f, fire.centerY() - 18f));

        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);

        VirtualKeyboardLayoutState state = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(state.portraitOverride());
        for (int mode : state.portraitOverride().snapModes) {
            assertNotEquals(RectSnap.NO_SNAP, mode);
        }
        keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);

        recreateKeyboardFromDisk(portrait);
        assertNotNull(keyboard.captureLayoutEditState().customLayout().portraitOverride());
        assertNotEquals(RectSnap.NO_SNAP, intField(keyByLabel("F"), "snapMode"));
    }

    @Test
    public void orientationSwitchCancelsLegacyPinchAndRestoresPublicEditMode() throws Exception {
        keyboard.setLayout(3);
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);

        RectF left = rectField(keyByLabel("L"));
        float cx = left.centerX();
        float cy = left.centerY();
        assertTrue(keyboard.pointerPressed(0, cx, cy));
        assertTrue(keyboard.pointerPressed(1, cx + 100f, cy));
        assertEquals(VirtualKeyboard.LAYOUT_SCALES, keyboard.getLayoutEditMode());
        assertTrue(intField(keyboard, "legacyPinchPointer") >= 0);

        keyboard.resize(
                new RectF(0f, 0f, 600f, 1200f),
                0f, 0f, 600f, 1200f);

        assertEquals(VirtualKeyboard.LAYOUT_KEYS, keyboard.getLayoutEditMode());
        assertEquals(-1, intField(keyboard, "legacyEditPointer"));
        assertEquals(-1, intField(keyboard, "legacyPinchPointer"));
    }

    private void setButtonHidden(String label, boolean hidden) {
        String[] names = keyboard.getKeyNames();
        boolean[] states = keyboard.getKeysVisibility();
        for (int i = 0; i < names.length; i++) {
            if (label.equals(names[i])) {
                states[i] = hidden;
                keyboard.setKeysVisibility(states);
                return;
            }
        }
        throw new AssertionError("Virtual key not found: " + label);
    }

    private boolean isButtonHidden(String label) {
        String[] names = keyboard.getKeyNames();
        boolean[] states = keyboard.getKeysVisibility();
        for (int i = 0; i < names.length; i++) {
            if (label.equals(names[i])) return states[i];
        }
        throw new AssertionError("Virtual key not found: " + label);
    }

    private VirtualKeyboardLayoutState createAndSaveTwoOrientationDpadCustom(
            RectF portrait, RectF landscape) throws Exception {
        keyboard.resize(portrait, 0f, 0f, portrait.width(), portrait.height());
        keyboard.setLayout(VirtualControlsKeyboard.TYPE_DPAD_STANDARD);
        dragGrouped("dpadGeometry", 30f, -18f);
        dragLegacy("F", 18f, 0f);

        keyboard.resize(landscape, 0f, 0f, landscape.width(), landscape.height());
        dragGrouped("dpadGeometry", -30f, 18f);
        dragLegacy("F", -18f, 0f);
        assertTrue(keyboard.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));

        VirtualKeyboardLayoutState saved = keyboard.captureLayoutEditState().customLayout();
        assertNotNull(saved.portraitOverride());
        assertNotNull(saved.landscapeOverride());
        return saved;
    }

    private PointF liveSnapOffset(String label) throws Exception {
        Object key = keyByLabel(label);
        Field field = findField(key.getClass(), "snapOffset");
        field.setAccessible(true);
        return (PointF) field.get(key);
    }

    private void dragGrouped(String geometryMethod, float dx, float dy) throws Exception {
        VirtualDpadGeometry geometry = geometry(geometryMethod);
        assertTrue(keyboard.pointerPressed(0, geometry.getCenterX(), geometry.getCenterY()));
        assertTrue(keyboard.pointerDragged(
                0, geometry.getCenterX() + dx, geometry.getCenterY() + dy));
        assertTrue(keyboard.pointerReleased(
                0, geometry.getCenterX() + dx, geometry.getCenterY() + dy));
    }

    private void dragLegacy(String label, float dx, float dy) throws Exception {
        RectF key = rectField(keyByLabel(label));
        assertTrue(keyboard.pointerPressed(0, key.centerX(), key.centerY()));
        assertTrue(keyboard.pointerDragged(0, key.centerX() + dx, key.centerY() + dy));
        assertTrue(keyboard.pointerReleased(0, key.centerX() + dx, key.centerY() + dy));
    }

    private void assertFreshStandardGeometry(
            RectF screen,
            float guestLeft,
            float guestTop,
            float guestRight,
            float guestBottom,
            String groupedMethod) throws Exception {
        StandardVirtualControlsLayout expected = StandardVirtualControlsLayout.resolve(
                screen.left, screen.top, screen.right, screen.bottom,
                guestLeft, guestTop, guestRight, guestBottom);
        RectF fire = rectField(keyByLabel("F"));
        assertEquals(expected.actionCenterX, fire.centerX(), EPS);
        assertEquals(expected.actionCenterY, fire.centerY(), EPS);
        VirtualDpadGeometry grouped = geometry(groupedMethod);
        assertEquals(expected.movementCenterX, grouped.getCenterX(), EPS);
        assertEquals(expected.movementCenterY, grouped.getCenterY(), EPS);
        assertEquals(expected.movementRadius, grouped.getRadius(), EPS);
    }

    private void assertAnalogVisualMatchesResolvedGeometry() throws Exception {
        VirtualDpadGeometry effective = geometry("analogGeometry");
        Field stickField = VirtualControlsKeyboard.class.getDeclaredField("analogStick");
        stickField.setAccessible(true);
        VirtualAnalogStick stick = (VirtualAnalogStick) stickField.get(keyboard);
        Field viewportField = VirtualControlsKeyboard.class.getDeclaredField("viewport");
        viewportField.setAccessible(true);
        GuestViewport currentViewport = (GuestViewport) viewportField.get(keyboard);
        VirtualAnalogVisualState visual = stick.visualState(currentViewport);
        assertEquals(effective.getCenterX(), visual.getCenterX(), EPS);
        assertEquals(effective.getCenterY(), visual.getCenterY(), EPS);
        assertEquals(effective.getRadius(), visual.getRadius(), EPS);
    }

    private VirtualLayoutOrientation activeOrientation() throws Exception {
        Field field = VirtualControlsKeyboard.class.getDeclaredField("activeLayoutOrientation");
        field.setAccessible(true);
        return (VirtualLayoutOrientation) field.get(keyboard);
    }

    private void assertEditedStandardRotatesBeforeSave(int type, String geometryMethod)
            throws Exception {
        RectF portrait = new RectF(0f, 0f, 600f, 1200f);
        keyboard.resize(portrait, 0f, 0f, 600f, 1200f);
        keyboard.setLayout(type);

        VirtualDpadGeometry grouped = geometry(geometryMethod);
        assertTrue(keyboard.pointerPressed(0, grouped.getCenterX(), grouped.getCenterY()));
        assertTrue(keyboard.pointerDragged(
                0, grouped.getCenterX() + 20f, grouped.getCenterY() - 20f));
        assertTrue(keyboard.pointerReleased(
                0, grouped.getCenterX() + 20f, grouped.getCenterY() - 20f));

        RectF landscape = new RectF(0f, 0f, 1200f, 600f);
        keyboard.resize(landscape, 0f, 0f, 1200f, 600f);
        assertLegacyControlsInside(landscape);
        assertGeometryInside(geometry(geometryMethod), landscape);
    }

    private void assertLegacyControlsInside(RectF screen) throws Exception {
        for (String label : new String[] { "F", "L", "R", "*", "0" }) {
            RectF rect = rectField(keyByLabel(label));
            assertTrue(Float.isFinite(rect.left));
            assertTrue(Float.isFinite(rect.top));
            assertTrue(rect.left >= screen.left - EPS);
            assertTrue(rect.top >= screen.top - EPS);
            assertTrue(rect.right <= screen.right + EPS);
            assertTrue(rect.bottom <= screen.bottom + EPS);
        }
    }

    private float[][] legacyCenters() throws Exception {
        String[] labels = { "F", "L", "R", "*", "0" };
        float[][] centers = new float[labels.length][2];
        for (int i = 0; i < labels.length; i++) {
            RectF rect = rectField(keyByLabel(labels[i]));
            centers[i][0] = rect.centerX();
            centers[i][1] = rect.centerY();
        }
        return centers;
    }

    private void recreateKeyboardFromDisk(RectF screen) throws Exception {
        disposeKeyboard(keyboard);
        keyboard = null;

        ProfileModel loaded = ProfilesManager.loadConfig(profileDir);
        assertNotNull(loaded);
        settings = loaded;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        keyboard = new VirtualControlsKeyboard(settings);
        keyboard.setView(new View(context));
        keyboard.resize(screen, 0f, 0f, screen.width(), screen.height());
        keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
        refreshKeypadReflection();
    }

    private void refreshKeypadReflection() throws Exception {
        Field keypadField = VirtualKeyboard.class.getDeclaredField("keypad");
        keypadField.setAccessible(true);
        keypad = (Object[]) keypadField.get(keyboard);
    }

    private static void disposeKeyboard(VirtualKeyboard value) throws Exception {
        if (value == null) return;
        value.cancel();
        Field handlerField = VirtualKeyboard.class.getDeclaredField("handler");
        handlerField.setAccessible(true);
        Handler handler = (Handler) handlerField.get(value);
        handler.getLooper().quitSafely();
    }

    private VirtualDpadGeometry geometry(String methodName) throws Exception {
        Method method = VirtualControlsKeyboard.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (VirtualDpadGeometry) method.invoke(keyboard);
    }

    private boolean invokeInsideAnalog(float x, float y, float scale) throws Exception {
        Method method = VirtualControlsKeyboard.class.getDeclaredMethod(
                "insideAnalog", float.class, float.class, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(keyboard, x, y, scale);
    }

    private static void assertGeometryInside(VirtualDpadGeometry geometry, RectF screen) {
        assertTrue(geometry.getCenterX() - geometry.getRadius() >= screen.left - EPS);
        assertTrue(geometry.getCenterY() - geometry.getRadius() >= screen.top - EPS);
        assertTrue(geometry.getCenterX() + geometry.getRadius() <= screen.right + EPS);
        assertTrue(geometry.getCenterY() + geometry.getRadius() <= screen.bottom + EPS);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static PointF pointField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        PointF value = (PointF) field.get(target);
        return new PointF(value.x, value.y);
    }

    private File layoutFile() {
        return new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE);
    }

    private void writeLegacyV3Layout(VirtualKeyboardLayoutSnapshot snapshot) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile()))) {
            writeLayoutHeaderAndCustomType(out);
            out.writeInt(VirtualKeyboard.LAYOUT_KEYS);
            out.writeInt(4 + keypad.length * 21);
            out.writeInt(keypad.length);
            for (int i = 0; i < keypad.length; i++) {
                out.writeInt(keypad[i].hashCode());
                out.writeBoolean(snapshot.visible[i]);
                out.writeInt(snapshot.snapOrigins[i]);
                out.writeInt(snapshot.snapModes[i]);
                out.writeFloat(snapshot.snapOffsetX[i]);
                out.writeFloat(snapshot.snapOffsetY[i]);
            }
            out.writeInt(VirtualKeyboard.LAYOUT_SCALES);
            out.writeInt(4 + snapshot.keyScales.length * 4);
            out.writeInt(snapshot.keyScales.length);
            for (float scale : snapshot.keyScales) out.writeFloat(scale);
            out.writeInt(VirtualKeyboard.LAYOUT_EOF);
            out.writeInt(0);
        }
    }

    private void patchLayoutType(int type) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(layoutFile(), "rw")) {
            assertEquals(0x564B4C00, raf.readInt());
            assertEquals(3, raf.readInt());
            while (raf.getFilePointer() < raf.length()) {
                int block = raf.readInt();
                int length = raf.readInt();
                if (block == VirtualKeyboard.LAYOUT_TYPE) {
                    assertTrue(length >= 1);
                    raf.writeByte(type);
                    return;
                }
                if (block == VirtualKeyboard.LAYOUT_EOF) break;
                raf.seek(raf.getFilePointer() + length);
            }
        }
        throw new AssertionError("Layout type block was not found");
    }

    private void patchKeyAsNoSnap(int targetHash) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(layoutFile(), "rw")) {
            assertEquals(0x564B4C00, raf.readInt());
            assertEquals(3, raf.readInt());
            while (true) {
                int block = raf.readInt();
                int length = raf.readInt();
                if (block == VirtualKeyboard.LAYOUT_KEYS) {
                    int count = raf.readInt();
                    for (int i = 0; i < count; i++) {
                        int hash = raf.readInt();
                        raf.readBoolean();
                        long snapOriginPosition = raf.getFilePointer();
                        if (hash == targetHash) {
                            raf.seek(snapOriginPosition);
                            raf.writeInt(-1);
                            raf.writeInt(RectSnap.NO_SNAP);
                            raf.writeFloat(0f);
                            raf.writeFloat(0f);
                            return;
                        }
                        raf.skipBytes(16);
                    }
                    break;
                }
                if (block == VirtualKeyboard.LAYOUT_EOF) break;
                raf.skipBytes(length);
            }
        }
        throw new AssertionError("Target key was not found in layout");
    }

    private void truncateInsideV4LandscapeOverride() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(layoutFile(), "rw")) {
            assertEquals(0x564B4C00, raf.readInt());
            assertEquals(4, raf.readInt());
            while (raf.getFilePointer() < raf.length()) {
                int block = raf.readInt();
                int length = raf.readInt();
                if (block == VirtualKeyboard.LAYOUT_LANDSCAPE_OVERRIDE) {
                    raf.setLength(raf.getFilePointer() + Math.min(32, length));
                    return;
                }
                if (block == VirtualKeyboard.LAYOUT_EOF) break;
                raf.seek(raf.getFilePointer() + length);
            }
        }
        throw new AssertionError("Landscape override block was not found");
    }

    private void writeExcessiveKeyCountLayout() throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile()))) {
            writeLayoutHeaderAndCustomType(out);
            out.writeInt(VirtualKeyboard.LAYOUT_KEYS);
            out.writeInt(4 + 29 * 21);
            out.writeInt(29);
        }
    }

    private void writeExcessiveScaleCountLayout() throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile()))) {
            writeLayoutHeaderAndCustomType(out);
            out.writeInt(VirtualKeyboard.LAYOUT_SCALES);
            out.writeInt(4 + 13 * 4);
            out.writeInt(13);
        }
    }

    private void writeSingleKeyLayout(
            int hash, int origin, int mode, float offsetX, float offsetY) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile()))) {
            writeLayoutHeaderAndCustomType(out);
            out.writeInt(VirtualKeyboard.LAYOUT_KEYS);
            out.writeInt(25);
            out.writeInt(1);
            out.writeInt(hash);
            out.writeBoolean(true);
            out.writeInt(origin);
            out.writeInt(mode);
            out.writeFloat(offsetX);
            out.writeFloat(offsetY);
            out.writeInt(VirtualKeyboard.LAYOUT_EOF);
            out.writeInt(0);
        }
    }

    private void writeTruncatedLayout() throws Exception {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile()))) {
            writeLayoutHeaderAndCustomType(out);
            out.writeInt(VirtualKeyboard.LAYOUT_KEYS);
            out.writeInt(25);
            out.writeInt(1);
        }
    }

    private static void writeLayoutHeaderAndCustomType(DataOutputStream out) throws Exception {
        out.writeInt(0x564B4C00);
        out.writeInt(3);
        out.writeInt(VirtualKeyboard.LAYOUT_TYPE);
        out.writeInt(1);
        out.writeByte(VirtualKeyboard.TYPE_CUSTOM);
    }

    private void assertMalformedLayoutFallsBack() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VirtualControlsKeyboard candidate = null;
        try {
            candidate = new VirtualControlsKeyboard(settings);
            candidate.setView(new View(context));
            candidate.resize(new RectF(0f, 0f, 1200f, 600f), 0f, 0f, 1200f, 600f);
            assertNotEquals(VirtualKeyboard.TYPE_CUSTOM, candidate.getLayout());
        } finally {
            disposeKeyboard(candidate);
        }
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
