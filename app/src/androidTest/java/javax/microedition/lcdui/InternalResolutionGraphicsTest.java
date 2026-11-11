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

    @Test public void oneTimesTargetKeepsLegacyBitmapSizing() {
        Image ordinary = Image.createDisplayTarget(4097, 1, 1);
        try {
            assertEquals(4097, ordinary.getBitmap().getWidth());
            assertEquals(1, ordinary.getPixelScale());
        } finally {
            ordinary.getBitmap().recycle();
        }
    }

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

    @Test public void scaledFrameCopyPreservesPhysicalDetailAtEdges() {
        Image source = Image.createDisplayTarget(12, 10, 2);
        Image destination = Image.createDisplayTarget(12, 10, 2);
        try {
            Graphics g = source.getGraphics();
            g.setColor(RED);
            g.fillRect(11, 9, 1, 1);
            source.copyTo(destination);
            assertEquals(RED, destination.getBitmap().getPixel(23, 19) & RGB);
        } finally {
            source.getBitmap().recycle();
            destination.getBitmap().recycle();
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
    @Test public void adjacentSolidLinesMakeContinuousLogicalBarsAtHighResolution() {
        for (int factor : new int[]{2, 3, 5}) {
            Image image = Image.createDisplayTarget(24, 16, factor);
            try {
                Graphics graphics = image.getGraphics();
                graphics.setColor(RED);
                for (int y = 3; y <= 6; y++) {
                    graphics.drawLine(18, y, 3, y); // reverse direction
                }
                // A 1-pixel MIDlet stroke must fill factor consecutive physical
                // rows, not just one device pixel separated by gaps.
                for (int y = 3 * factor; y < 7 * factor; y++) {
                    for (int x = 3 * factor; x < 19 * factor; x++) {
                        assertEquals("Bar gaps at factor " + factor,
                                RED, image.getBitmap().getPixel(x, y) & RGB);
                    }
                }
                // Vertical edge must also remain one complete guest pixel wide.
                graphics.setColor(BLUE);
                graphics.drawLine(20, 9, 20, 3);
                for (int y = 3 * factor; y < 10 * factor; y++) {
                    for (int x = 20 * factor; x < 21 * factor; x++) {
                        assertEquals("Vertical stroke at factor " + factor,
                                BLUE, image.getBitmap().getPixel(x, y) & RGB);
                    }
                }
                assertEquals(0x00FFFFFF,
                        image.getBitmap().getPixel(2 * factor, 4 * factor) & RGB);
            } finally {
                image.getBitmap().recycle();
            }
        }
    }



    @Test public void partialFlushKeepsUnchangedPixelsAcrossDifferentBackingScales() {
        Image hd = Image.createDisplayTarget(12, 8, 4);
        Image ordinary = Image.createImage(12, 8);
        Image target = Image.createDisplayTarget(12, 8, 4);
        try {
            Graphics src = hd.getGraphics();
            src.setColor(RED);
            src.fillRect(4, 2, 3, 2);
            ordinary.getGraphics().flush(hd, 5, 2, 1, 2);
            assertEquals(RED, ordinary.getBitmap().getPixel(5, 2) & RGB);
            assertEquals(RED, ordinary.getBitmap().getPixel(5, 3) & RGB);
            assertEquals(RGB, ordinary.getBitmap().getPixel(4, 2) & RGB);
            assertEquals(RGB, ordinary.getBitmap().getPixel(6, 2) & RGB);

            Graphics destSource = ordinary.getGraphics();
            destSource.setColor(GREEN);
            destSource.fillRect(7, 5, 2, 1);
            target.getGraphics().flush(ordinary, 7, 5, 2, 1);
            for (int y = 20; y < 24; y++) {
                for (int x = 28; x < 36; x++) {
                    assertEquals(GREEN, target.getBitmap().getPixel(x, y) & RGB);
                }
            }
            assertEquals(RGB, target.getBitmap().getPixel(24, 20) & RGB);
            assertEquals(RGB, target.getBitmap().getPixel(36, 20) & RGB);
        } finally {
            hd.getBitmap().recycle();
            ordinary.getBitmap().recycle();
            target.getBitmap().recycle();
        }
    }

    @Test public void hdReadbackSupportsNegativeScanlengthInLogicalCoordinates() {
        Image hd = Image.createDisplayTarget(4, 3, 5);
        try {
            Graphics g = hd.getGraphics();
            g.setColor(RED);
            g.fillRect(0, 0, 2, 1);
            g.setColor(BLUE);
            g.fillRect(0, 1, 2, 1);
            int[] out = new int[8];
            hd.getRGB(out, 5, -3, 0, 0, 2, 2);
            assertEquals(RED, out[5] & RGB);
            assertEquals(RED, out[6] & RGB);
            assertEquals(BLUE, out[2] & RGB);
            assertEquals(BLUE, out[3] & RGB);
        } finally {
            hd.getBitmap().recycle();
        }
    }

    @Test public void rotatedSourceRegionsPreserveLogicalCoverageAcrossTargetScales() {
        int[] colors = {
                0xffff0000, 0xff00ff00,
                0xff0000ff, 0xffffff00,
                0xffff00ff, 0xff00ffff
        };
        Image spriteSheet = Image.createRGBImage(colors, 2, 3, true);
        int[] transforms = {Sprite.TRANS_NONE, Sprite.TRANS_MIRROR,
                Sprite.TRANS_ROT90, Sprite.TRANS_ROT180, Sprite.TRANS_ROT270,
                Sprite.TRANS_MIRROR_ROT90, Sprite.TRANS_MIRROR_ROT180,
                Sprite.TRANS_MIRROR_ROT270};
        Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
        try {
            for (int transform : transforms) {
                Image reference = Image.createImage(8, 8);
                Image hd = Image.createDisplayTarget(8, 8, 4);
                try {
                    reference.getGraphics().drawRegion(spriteSheet, 0, 0, 2, 3,
                            transform, 2, 2, Graphics.TOP | Graphics.LEFT);
                    hd.getGraphics().drawRegion(spriteSheet, 0, 0, 2, 3,
                            transform, 2, 2, Graphics.TOP | Graphics.LEFT);
                    int[] a = new int[64], b = new int[64];
                    reference.getRGB(a, 0, 8, 0, 0, 8, 8);
                    hd.getRGB(b, 0, 8, 0, 0, 8, 8);
                    for (int i = 0; i < 64; i++) {
                        assertEquals("Sprite transform " + transform + ", pixel " + i,
                                a[i], b[i]);
                    }
                } finally {
                    reference.getBitmap().recycle();
                    hd.getBitmap().recycle();
                }
            }
        } finally {
            spriteSheet.getBitmap().recycle();
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
        }
    }

}
