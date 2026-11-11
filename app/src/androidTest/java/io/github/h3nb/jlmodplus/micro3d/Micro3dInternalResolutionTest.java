// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.micro3d;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;

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
}
