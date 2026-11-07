// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.microedition.lcdui.game.Sprite;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Research-only real Android Bitmap/Graphics boundary, not end-to-end MIDlet compliance. */
@RunWith(AndroidJUnit4.class)
public class InternalResolutionGraphicsResearchTest {
    @Test public void scaledTargetPreservesLogicalDimensionsAndCopies() {
        Image hd = Image.createDisplayTarget(12, 10, 2);
        Image normal = Image.createImage(12, 10);
        try {
            assertEquals(12, hd.getWidth());
            assertEquals(10, hd.getHeight());
            assertEquals(24, hd.getBitmap().getWidth());
            assertEquals(20, hd.getBitmap().getHeight());
            Graphics g = hd.getGraphics();
            g.setColor(0x00FF0000);
            g.fillRect(1, 1, 3, 3);
            assertEquals(0x00FF0000, hd.getBitmap().getPixel(3, 3) & 0xFFFFFF);
            int[] logical = new int[1];
            hd.getRGB(logical, 0, 1, 1, 1, 1, 1);
            assertEquals(0x00FF0000, logical[0] & 0xFFFFFF);
            g.copyArea(1, 1, 3, 3, 6, 5, Graphics.TOP | Graphics.LEFT);
            assertEquals(0x00FF0000, hd.getBitmap().getPixel(13, 11) & 0xFFFFFF);
            Graphics destination = normal.getGraphics();
            destination.drawImage(hd, 0, 0, Graphics.TOP | Graphics.LEFT);
            assertEquals(0x00FF0000, normal.getBitmap().getPixel(1, 1) & 0xFFFFFF);
            destination.drawRegion(hd, 6, 5, 3, 3, Sprite.TRANS_NONE,
                    9, 6, Graphics.TOP | Graphics.LEFT);
            assertEquals(0x00FF0000, normal.getBitmap().getPixel(9, 6) & 0xFFFFFF);
        } finally {
            hd.getBitmap().recycle();
            normal.getBitmap().recycle();
        }
    }

    @Test public void scaledTargetGrowthRetainsPhysicalPixelsAndTextMetricsStayLogical() {
        Image image = Image.createDisplayTarget(12, 10, 2);
        try {
            Graphics g = image.getSingleGraphics();
            g.setColor(0x0000FF00);
            g.fillRect(2, 2, 3, 3);
            image.setSize(16, 12);
            assertEquals(16, image.getWidth());
            assertEquals(12, image.getHeight());
            assertEquals(32, image.getBitmap().getWidth());
            assertEquals(24, image.getBitmap().getHeight());
            assertEquals(0x0000FF00, image.getBitmap().getPixel(5, 5) & 0xFFFFFF);
            assertEquals(16, image.getPhysicalContentRect().right / image.getPixelScale());
            assertTrue(Font.getDefaultFont().stringWidth("HD") > 0);
        } finally {
            image.getBitmap().recycle();
        }
    }
}
