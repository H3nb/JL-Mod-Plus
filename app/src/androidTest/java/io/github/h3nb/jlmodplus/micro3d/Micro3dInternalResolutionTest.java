// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.micro3d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * The Micro3D pbuffer and readback use physical pixels, but its projection
 * geometry must remain in the dimensions exposed to the guest.
 */
@RunWith(AndroidJUnit4.class)
public class Micro3dInternalResolutionTest {
    @Test
    public void displayTargetKeepsLogicalProjectionWhileRenderingToPhysicalPbuffer() {
        Render render = Render.getRender();
        Image target = Image.createDisplayTarget(48, 36, 3);
        Graphics graphics = target.getGraphics();
        graphics.setClip(4, 5, 17, 13);

        render.bind(graphics);
        try {
            assertEquals(48, render.env.width);
            assertEquals(36, render.env.height);
            assertEquals(144, target.getBitmap().getWidth());
            assertEquals(108, target.getBitmap().getHeight());
            render.setCenter(24, 18);
            render.setOrthographicScale(4096, 4096);
            assertEquals(0f, render.env.projMatrix[12], 0.0001f);
            assertEquals(0f, render.env.projMatrix[13], 0.0001f);
        } finally {
            render.release();
        }

        // Moving from a high-density display target back to an ordinary
        // render target must restore the original projection dimensions.
        Image ordinary = Image.createImage(32, 24);
        render.bind(ordinary.getGraphics());
        try {
            assertEquals(32, render.env.width);
            assertEquals(24, render.env.height);
            render.setCenter(16, 12);
            render.setOrthographicScale(4096, 4096);
            assertEquals(0f, render.env.projMatrix[12], 0.0001f);
            assertEquals(0f, render.env.projMatrix[13], 0.0001f);
        } finally {
            render.release();
        }
    }
    @Test
    public void offscreenTextureTargetResetsThePreviousHdReconstructionScale() throws Exception {
        Render render = Render.getRender();
        Field selectedScale = TextureImpl.class.getDeclaredField("screenTargetScale");
        selectedScale.setAccessible(true);
        Image hd = Image.createDisplayTarget(24, 20, 3);
        try {
            render.bind(hd.getGraphics());
            try {
                assertEquals(3, selectedScale.getInt(null));
            } finally {
                render.release();
            }
            render.bind(new TextureImpl());
            try {
                assertEquals(1, selectedScale.getInt(null));
            } finally {
                render.release();
            }
        } finally {
            hd.getBitmap().recycle();
        }
    }
    @Test
    public void resizeDuringMicro3dBindingDoesNotWriteIntoTheReplacementBitmap() {
        Render render = Render.getRender();
        Image frame = Image.createDisplayTarget(48, 36, 3);
        frame.enableResizableDisplayTarget();
        Graphics drawing = frame.getGraphics();
        drawing.setColor(Color.RED);
        drawing.fillRect(0, 0, 48, 36);
        Bitmap oldBitmap = frame.getBitmap();
        Bitmap nextBitmap = null;
        try {
            render.bind(drawing);
            try {
                frame.resizeDisplayTarget(24, 20, 2);
                nextBitmap = frame.getBitmap();
                assertNotSame(oldBitmap, nextBitmap);
                drawing.setColor(Color.BLUE);
                drawing.fillRect(0, 0, 24, 20);
            } finally {
                render.release();
            }
            assertEquals("Readback must not overwrite the new backing store",
                    Color.BLUE, nextBitmap.getPixel(8, 8));
        } finally {
            oldBitmap.recycle();
            if (nextBitmap != null) nextBitmap.recycle();
        }
    }

    @Test
    public void nativeReadbackRejectsOutOfBoundsAndUnsupportedBitmapFormats() {
        Bitmap argb = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        Bitmap rgb565 = Bitmap.createBitmap(8, 6, Bitmap.Config.RGB_565);
        try {
            assertInvalidReadback(-1, 0, 2, 2, argb);
            assertInvalidReadback(0, -1, 2, 2, argb);
            assertInvalidReadback(7, 0, 2, 2, argb);
            assertInvalidReadback(0, 5, 2, 2, argb);
            assertInvalidReadback(0, 0, -1, 2, argb);
            assertInvalidReadback(0, 0, 8, 6, rgb565);
        } finally {
            argb.recycle();
            rgb565.recycle();
        }
    }

    private static void assertInvalidReadback(int x, int y, int width, int height, Bitmap target) {
        try {
            Utils.glReadPixels(x, y, width, height, target);
            fail("Expected readback validation to reject " + x + "," + y + " " + width + "x" + height);
        } catch (IllegalArgumentException expected) {
            // Out-of-bounds native writes must be rejected before locking pixels.
        }
    }

}
