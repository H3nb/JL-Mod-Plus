// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.microedition.lcdui.game.Sprite;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InternalResolutionGraphicsTest {
    private static final int RED = 0x00FF0000;
    private static final int GREEN = 0x0000FF00;
    private static final int BLUE = 0x000000FF;
    private static final int RGB = 0x00FFFFFF;

    @Test public void logicalSizeReadbackCopyAreaAndSpriteSourceRects() {
        Image hd = Image.createDisplayTarget(12, 10, 2);
        Image normal = Image.createImage(12, 10);
        try {
            assertEquals(12, hd.getWidth());
            assertEquals(10, hd.getHeight());
            assertEquals(24, hd.getBitmap().getWidth());
            assertEquals(20, hd.getBitmap().getHeight());

            Graphics drawing = hd.getGraphics();
            drawing.setColor(RED);
            drawing.fillRect(1, 1, 3, 3);
            assertEquals(RED, hd.getBitmap().getPixel(3, 3) & RGB);

            int[] guestPixel = new int[1];
            hd.getRGB(guestPixel, 0, 1, 1, 1, 1, 1);
            assertEquals(RED, guestPixel[0] & RGB);

            drawing.copyArea(1, 1, 3, 3, 6, 5, Graphics.TOP | Graphics.LEFT);
            assertEquals(RED, hd.getBitmap().getPixel(13, 11) & RGB);

            Graphics dest = normal.getGraphics();
            dest.drawImage(hd, 0, 0, Graphics.TOP | Graphics.LEFT);
            assertEquals(RED, normal.getBitmap().getPixel(1, 1) & RGB);
            dest.drawRegion(hd, 6, 5, 3, 3, Sprite.TRANS_NONE, 9, 6,
                    Graphics.TOP | Graphics.LEFT);
            assertEquals(RED, normal.getBitmap().getPixel(9, 6) & RGB);
        } finally {
            hd.getBitmap().recycle();
            normal.getBitmap().recycle();
        }
    }

    @Test public void scaledTargetGrowsWithoutChangingLogicalCanvasUnits() {
        Image image = Image.createDisplayTarget(12, 10, 2);
        try {
            Graphics g = image.getSingleGraphics();
            g.setColor(GREEN);
            g.fillRect(2, 2, 3, 3);
            image.setSize(16, 12);
            assertEquals(16, image.getWidth());
            assertEquals(12, image.getHeight());
            assertEquals(32, image.getBitmap().getWidth());
            assertEquals(24, image.getBitmap().getHeight());
            assertEquals(GREEN, image.getBitmap().getPixel(5, 5) & RGB);

            g.setColor(BLUE);
            g.fillRect(14, 10, 2, 2);
            assertEquals(BLUE, image.getBitmap().getPixel(29, 21) & RGB);
            assertEquals(16, image.getPhysicalContentRect().right / image.getPixelScale());
            assertTrue(Font.getDefaultFont().stringWidth("HD") > 0);
        } finally {
            image.getBitmap().recycle();
        }
    }
}
