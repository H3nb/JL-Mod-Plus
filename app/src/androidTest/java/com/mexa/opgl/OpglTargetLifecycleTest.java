// SPDX-License-Identifier: Apache-2.0
package com.mexa.opgl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

@RunWith(AndroidJUnit4.class)
public class OpglTargetLifecycleTest {
    @Test public void releaseWritesOnlyTheBitmapCapturedAtBind() {
        Image image = Image.createDisplayTarget(24, 20, 2);
        image.enableResizableDisplayTarget();
        Graphics graphics = image.getGraphics();
        Bitmap old = image.getBitmap();
        OpglGraphics renderer = OpglGraphics.getInstance();
        try {
            renderer.bind(graphics);
            try {
                renderer.glClearColor(1f, 0f, 0f, 1f);
                renderer.glClear(javax.microedition.khronos.opengles.GL10.GL_COLOR_BUFFER_BIT);
                image.resizeDisplayTarget(16, 12, 1);
                assertNotSame(old, image.getBitmap());
            } finally {
                renderer.release();
            }
            assertEquals(Color.RED, old.getPixel(4, 4));
            assertEquals(Color.WHITE, image.getBitmap().getPixel(4, 4));
        } finally {
            old.recycle();
            if (image.getBitmap() != old) image.getBitmap().recycle();
        }
    }
}
