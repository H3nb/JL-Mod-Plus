// SPDX-License-Identifier: Apache-2.0
package javax.microedition.m3g;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Run this class in a fresh instrumentation process to exercise the external-EGL-first
 * initialization order. Other M3G runtime tests cover normal rendering without this owner.
 */
@RunWith(AndroidJUnit4.class)
public class M3GEglOwnershipTest {
    @Test
    public void externalEglTerminationDoesNotInvalidateM3gRendering() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        int[] major = new int[1];
        int[] minor = new int[1];
        assertTrue(EGL14.eglInitialize(display, major, 0, minor, 0));

        Graphics3D graphics3D;
        try {
            M3GRuntimeTest.loadTargetNativeLibraries();
            graphics3D = Graphics3D.getInstance();
            assertClearColor(graphics3D, 0xFF17324D);
        } finally {
            assertTrue(EGL14.eglTerminate(display));
        }

        // The display's other owner has terminated. The still-live M3G context must
        // retain its own EGL initialization reference and its cached resources.
        assertClearColor(graphics3D, 0xFF472B18);
    }

    private static void assertClearColor(Graphics3D graphics3D, int color) {
        Image image = Image.createImage(64, 64);
        Background background = new Background();
        background.setColor(color);
        background.setColorClearEnable(true);
        graphics3D.bindTarget(image.getGraphics(), true, Graphics3D.TRUE_COLOR);
        try {
            graphics3D.clear(background);
        } finally {
            graphics3D.releaseTarget();
        }
        int[] pixel = new int[1];
        image.getRGB(pixel, 0, 1, 32, 32, 1, 1);
        assertEquals(color & 0x00FFFFFF, pixel[0] & 0x00FFFFFF);
    }
}
