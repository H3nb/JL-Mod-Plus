// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.nokia.mid.ui.DirectGraphics;
import com.nokia.mid.ui.DirectUtils;
import com.siemens.mp.game.ExtendedImage;
import com.vodafone.util.ImageEncoder;

import io.github.h3nb.jlmodplus.config.ProfileModel;

import javax.microedition.lcdui.game.Sprite;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MutableImageScaleCompatibilityTest {
    @Test public void scaledImageCopiesAndRegionsRetainLogicalGeometry() {
        Image source = Image.createDisplayTarget(8, 6, 3);
        Image copied = null;
        Image cropped = null;
        Image rotated = null;
        try {
            Graphics g = source.getGraphics();
            g.setColor(0x123456);
            g.fillRect(4, 2, 1, 1);
            copied = Image.createImage(source);
            cropped = Image.createImage(source, 4, 2, 2, 3, Sprite.TRANS_NONE);
            rotated = Image.createImage(source, 4, 2, 2, 3, Sprite.TRANS_ROT90);

            assertFalse(copied.isMutable());
            assertEquals(8, copied.getWidth());
            assertEquals(6, copied.getHeight());
            assertEquals(3, copied.getPixelScale());
            assertEquals(0x123456, copied.getLogicalPixel(4, 2) & 0xFFFFFF);

            assertEquals(2, cropped.getWidth());
            assertEquals(3, cropped.getHeight());
            assertEquals(3, cropped.getPixelScale());
            assertEquals(0x123456, cropped.getLogicalPixel(0, 0) & 0xFFFFFF);

            assertEquals(3, rotated.getWidth());
            assertEquals(2, rotated.getHeight());
            assertEquals(3, rotated.getPixelScale());
            int[] rotatedPixels = new int[6];
            rotated.getRGB(rotatedPixels, 0, 3, 0, 0, 3, 2);
            boolean found = false;
            for (int color : rotatedPixels) found |= (color & 0xFFFFFF) == 0x123456;
            assertTrue("Rotated crop lost the source pixel", found);
        } finally {
            if (rotated != null) rotated.getBitmap().recycle();
            if (cropped != null) cropped.getBitmap().recycle();
            if (copied != null) copied.getBitmap().recycle();
            source.getBitmap().recycle();
        }
    }

    @Test public void oneTimesTargetNeverInheritsGuestImageDensity() {
        ProfileModel profile = new ProfileModel();
        profile.internalResolutionScale = 3;
        Canvas.setSettings(profile);
        Image mutable = null;
        Image display = null;
        try {
            mutable = Image.createImage(8, 6);
            display = Image.createDisplayTarget(8, 6, 1);
            assertEquals(3, mutable.getPixelScale());
            assertEquals(8, mutable.getWidth());
            assertEquals(24, mutable.getBitmap().getWidth());
            assertEquals(1, display.getPixelScale());
            assertEquals(8, display.getBitmap().getWidth());
        } finally {
            if (mutable != null) mutable.getBitmap().recycle();
            if (display != null) display.getBitmap().recycle();
            Canvas.setSettings(new ProfileModel());
        }
    }

    @Test public void vendorPixelAccessUsesGuestCoordinates() {
        Image image = Image.createDisplayTarget(8, 4, 3);
        try {
            ExtendedImage extended = new ExtendedImage(image);
            extended.setPixel(4, 2, (byte) 1);
            assertEquals(1, extended.getPixel(4, 2));
            assertEquals(0xFF000000, image.getBitmap().getPixel(4 * 3 + 1, 2 * 3 + 1));

            byte[] packed = {(byte) 0x80};
            extended.setPixels(packed, 0, 0, 8, 1);
            byte[] roundtrip = new byte[1];
            extended.getPixelBytes(roundtrip, 0, 0, 8, 1);
            assertEquals(packed[0], roundtrip[0]);

            com.siemens.mp.lcdui.Image.setPixelColor(image, 6, 2, 0xFF123456);
            assertEquals(0xFF123456, com.siemens.mp.lcdui.Image.getPixelColor(image, 6, 2));

            int[] pixels = new int[8 * 4];
            DirectUtils.getDirectGraphics(image.getGraphics()).getPixels(
                    pixels, 0, 8, 0, 0, 8, 4, DirectGraphics.TYPE_INT_8888_ARGB);
            assertEquals(0xFF123456, pixels[2 * 8 + 6]);
        } finally {
            image.getBitmap().recycle();
        }
    }

    @Test public void vendorPixelAccessRejectsCoordinatesOutsideLogicalSize() {
        Image image = Image.createDisplayTarget(8, 6, 3);
        try {
            image.setSize(4, 3);
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> image.getLogicalPixel(6, 1));
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> image.setLogicalPixel(6, 1, 0xFF112233));
        } finally {
            image.getBitmap().recycle();
        }
    }

    @Test public void invalidArrayReadAndWriteDoNotPartiallyModifyPixels() {
        for (int scale : new int[]{1, 3}) {
            Image image = Image.createDisplayTarget(4, 3, scale);
            try {
                int[] read = {17, 17, 17};
                org.junit.Assert.assertThrows(ArrayIndexOutOfBoundsException.class,
                        () -> image.getRGB(read, 0, 2, 0, 0, 2, 2));
                org.junit.Assert.assertArrayEquals(new int[]{17, 17, 17}, read);
                org.junit.Assert.assertThrows(ArrayIndexOutOfBoundsException.class,
                        () -> image.setLogicalPixels(new int[]{0xFF000000, 0xFF000000, 0xFF000000},
                                0, 2, 0, 0, 2, 2));
                assertEquals(0xFFFFFFFF, image.getLogicalPixel(0, 0));
                image.setLogicalPixels(new int[]{0xFF123456, 0xFF654321}, 1, -1, 0, 0, 1, 2);
                int[] result = new int[2];
                image.getRGB(result, 1, -1, 0, 0, 1, 2);
                org.junit.Assert.assertArrayEquals(new int[]{0xFF123456, 0xFF654321}, result);
            } finally {
                image.getBitmap().recycle();
            }
        }
    }

    @Test public void siemensTwoBitPixelsRoundTrip() {
        Image image = Image.createDisplayTarget(8, 1, 3);
        try {
            image.setBlackWhiteAlpha(true);
            ExtendedImage extended = new ExtendedImage(image);
            byte[] packed = {(byte) 0x19, (byte) 0x86};
            extended.setPixels(packed, 0, 0, 8, 1);
            byte[] result = new byte[2];
            extended.getPixelBytes(result, 0, 0, 8, 1);
            org.junit.Assert.assertArrayEquals(packed, result);
        } finally {
            image.getBitmap().recycle();
        }
    }

    @Test public void vendorEncoderKeepsLogicalDimensions() throws Exception {
        Image image = Image.createDisplayTarget(8, 4, 3);
        try {
            byte[] encoded = ImageEncoder.createEncoder(ImageEncoder.FORMAT_PNG)
                    .encodeOffscreen(image, 0, 0, 8, 4);
            Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            try {
                assertEquals(8, bitmap.getWidth());
                assertEquals(4, bitmap.getHeight());
            } finally {
                bitmap.recycle();
            }
        } finally {
            image.getBitmap().recycle();
        }
    }
}
