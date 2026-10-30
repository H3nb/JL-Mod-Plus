// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.experiments;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

/**
 * Disposable Mascot Capsule research: only checks LCDUI offscreen target
 * preservation across bind/flush/release. No 3D geometry is submitted.
 */
@RunWith(AndroidJUnit4.class)
public final class MascotReadbackResearchTest {
    @Test public void offscreenGraphicsSurvivesBindReleaseWithCorrectPixelOrientation() {
        Image image = Image.createImage(16, 16);
        Graphics g = image.getGraphics();
        fill(g, 0xFF0000, 0, 0);
        fill(g, 0x00FF00, 8, 0);
        fill(g, 0x0000FF, 0, 8);
        fill(g, 0xFFFFFF, 8, 8);

        Graphics3D mascot = new Graphics3D();
        mascot.bind(g);
        try {
            mascot.flush();
        } finally {
            mascot.release(g);
        }

        check(image, 4, 4, 0xFF0000);
        check(image, 12, 4, 0x00FF00);
        check(image, 4, 12, 0x0000FF);
        check(image, 12, 12, 0xFFFFFF);
    }

    private static void fill(Graphics g, int rgb, int x, int y) {
        g.setColor(rgb);
        g.fillRect(x, y, 8, 8);
    }

    private static void check(Image image, int x, int y, int rgb) {
        int[] pixel = new int[1];
        image.getRGB(pixel, 0, 1, x, y, 1, 1);
        assertEquals("Preserved LCDUI pixel at " + x + "," + y,
                rgb, pixel[0] & 0x00FFFFFF);
    }
}
