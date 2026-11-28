// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PaintBufferPoolTest {
    @Test public void resizeCannotReplaceAnActivePaintOrReinstallItOverTheNewScratch() {
        PaintBufferPool pool = new PaintBufferPool();
        PaintBufferPool.Scratch active = pool.acquire(8, 8, 3);
        pool.prepare(10, 10, 2);
        pool.release(active);
        PaintBufferPool.Scratch next = pool.acquire(10, 10, 2);
        try {
            assertNotSame(active.image, next.image);
            assertEquals(3, active.image.getPixelScale());
            assertEquals(24, active.image.getBitmap().getWidth());
            assertEquals(2, next.image.getPixelScale());
            assertEquals(20, next.image.getBitmap().getWidth());
        } finally {
            active.image.getBitmap().recycle();
            next.image.getBitmap().recycle();
        }
    }

    @Test public void recycledScratchRestoresOwnerAndDirectFlushContent() {
        PaintBufferPool pool = new PaintBufferPool();
        Image first = Image.createDisplayTarget(8, 8, 2);
        Image second = Image.createDisplayTarget(8, 8, 2);
        PaintBufferPool.Scratch scratch = pool.acquire(8, 8, 2);
        try {
            first.setLogicalPixel(1, 1, 0xFF112233);
            scratch.restore(first, 1);
            assertEquals(0xFF112233, scratch.image.getLogicalPixel(1, 1));
            scratch.markPublished(first, 1);
            pool.release(scratch);
            assertSame(scratch, pool.acquire(8, 8, 2));

            // An external flush changed the published frame without touching scratch.
            first.setLogicalPixel(1, 1, 0xFF445566);
            scratch.restore(first, 2);
            assertEquals(0xFF445566, scratch.image.getLogicalPixel(1, 1));

            // An abandoned paint must not contaminate the next partial repaint.
            scratch.image.setLogicalPixel(1, 1, 0xFFFF0000);
            scratch.restore(first, 2);
            assertEquals(0xFF445566, scratch.image.getLogicalPixel(1, 1));
            scratch.markPublished(first, 2);

            second.setLogicalPixel(1, 1, 0xFF778899);
            scratch.restore(second, 2);
            assertEquals(0xFF778899, scratch.image.getLogicalPixel(1, 1));
            assertEquals(0xFFFFFFFF, scratch.image.getLogicalPixel(2, 2));
        } finally {
            first.getBitmap().recycle();
            second.getBitmap().recycle();
            scratch.image.getBitmap().recycle();
        }
    }
}
