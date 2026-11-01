// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * R&D only: Android Picture as a reusable 1x/HD command stream, not a production
 * J2ME Graphics implementation. A framebuffer-dependent copy is an explicit
 * recording/replay barrier. Tests are valid on Android 6 and newer.
 */
@RunWith(AndroidJUnit4.class)
public final class RecordedDualPlaneResearchTest {
    private static final int W = 24, H = 20, S = 3;
    private static final String TAG = "GraphicsRD_DualPlane";

    private static void drawSequence(Canvas c) {
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStrokeWidth(1f);
        c.drawLine(2.2f, 3.15f, 20.7f, 15.6f, p);
        p.setAntiAlias(false);
        p.setColor(Color.RED);
        c.drawRect(2, 13, 8, 17, p);
        p.setColor(Color.GREEN);
        c.drawRect(12, 2, 19, 5, p);
    }

    private static void replay(Picture picture, Bitmap bitmap, int scale) {
        Canvas target = new Canvas(bitmap);
        if (scale != 1) target.scale(scale, scale);
        picture.draw(target);
    }

    private static int different(Bitmap a, Bitmap b) {
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++;
        return diff;
    }

    @Test public void recordedCommandsReplayExactlyToTwoIndependentResolutions() {
        Bitmap direct1 = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap direct3 = Bitmap.createBitmap(W * S, H * S, Bitmap.Config.ARGB_8888);
        Bitmap replay1 = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap replay3 = Bitmap.createBitmap(W * S, H * S, Bitmap.Config.ARGB_8888);
        Bitmap upscaled1 = null;
        try {
            drawSequence(new Canvas(direct1));
            Canvas scaled = new Canvas(direct3);
            scaled.scale(S, S);
            drawSequence(scaled);
            Picture commands = new Picture();
            drawSequence(commands.beginRecording(W, H));
            commands.endRecording();
            replay(commands, replay1, 1);
            replay(commands, replay3, S);
            assertEquals("Recording preserves original logical raster", 0, different(direct1, replay1));
            assertEquals("Same commands preserve true high resolution raster", 0, different(direct3, replay3));
            upscaled1 = Bitmap.createScaledBitmap(direct1, W * S, H * S, false);
            int improved = different(upscaled1, direct3);
            assertTrue("Re-rasterized fractional line must contain new physical pixels", improved > 0);
            Log.i(TAG, "Picture replay 1x and 3x pixel-exact; 1x nearest vs 3x newPixels=" + improved);
        } finally {
            if (upscaled1 != null) upscaled1.recycle();
            direct1.recycle(); direct3.recycle(); replay1.recycle(); replay3.recycle();
        }
    }

    private static void drawSource(Canvas canvas) {
        Paint p = new Paint();
        p.setColor(Color.RED);
        canvas.drawRect(1, 1, 5, 5, p);
    }

    private static void drawHud(Canvas canvas) {
        Paint p = new Paint();
        p.setColor(Color.GREEN);
        canvas.drawRect(9, 7, 11, 9, p);
    }

    private static Bitmap snapshot(Bitmap bitmap, int scale) {
        return Bitmap.createBitmap(bitmap, scale, scale, 4 * scale, 4 * scale);
    }

    private static void copyTo(Canvas canvas, Bitmap fragment) {
        canvas.drawBitmap(fragment, null, new Rect(8, 6, 12, 10), null);
    }

    @Test public void framebufferReadAndCopyForcesAReplayBarrier() {
        final int w = 16, h = 12, scale = 2;
        Bitmap baseline1 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap baseline2 = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
        Bitmap correct1 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap correct2 = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
        Bitmap invalid = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap oldCopy = null;
        try {
            baseline1.eraseColor(Color.BLACK);
            baseline2.eraseColor(Color.BLACK);
            correct1.eraseColor(Color.BLACK);
            correct2.eraseColor(Color.BLACK);
            invalid.eraseColor(Color.BLACK);
            Canvas direct1 = new Canvas(baseline1);
            Canvas direct2 = new Canvas(baseline2);
            direct2.scale(scale, scale);
            drawSource(direct1); drawSource(direct2);
            Bitmap crop1 = snapshot(baseline1, 1);
            Bitmap crop2 = snapshot(baseline2, scale);
            try { copyTo(direct1, crop1); copyTo(direct2, crop2); }
            finally { crop1.recycle(); crop2.recycle(); }
            drawHud(direct1); drawHud(direct2);

            // An invalid command buffer snapshots before the producing draw
            // has been replayed. The copied region must therefore be stale.
            oldCopy = snapshot(invalid, 1);
            Picture unsound = new Picture();
            Canvas recorded = unsound.beginRecording(w, h);
            drawSource(recorded);
            copyTo(recorded, oldCopy);
            drawHud(recorded);
            unsound.endRecording();
            replay(unsound, invalid, 1);
            int broken = different(baseline1, invalid);
            assertTrue("Copying from a deferred backing must differ from direct execution", broken > 0);

            Picture pre = new Picture();
            drawSource(pre.beginRecording(w, h));
            pre.endRecording();
            replay(pre, correct1, 1);
            replay(pre, correct2, scale);
            Bitmap current1 = snapshot(correct1, 1);
            Bitmap current2 = snapshot(correct2, scale);
            try {
                copyTo(new Canvas(correct1), current1);
                Canvas hd = new Canvas(correct2);
                hd.scale(scale, scale);
                copyTo(hd, current2);
            } finally { current1.recycle(); current2.recycle(); }
            Picture post = new Picture();
            drawHud(post.beginRecording(w, h));
            post.endRecording();
            replay(post, correct1, 1);
            replay(post, correct2, scale);
            assertEquals("Barrier preserves guest-visible exact 1x semantics", 0, different(baseline1, correct1));
            assertEquals("Barrier preserves full HD output", 0, different(baseline2, correct2));
            assertEquals(Color.RED, correct1.getPixel(8, 6));
            assertEquals(Color.GREEN, correct1.getPixel(9, 7));
            Log.i(TAG, "Deferred source read breaks " + broken + " logical pixels; barrier matches 1x/2x exactly");
        } finally {
            if (oldCopy != null) oldCopy.recycle();
            baseline1.recycle(); baseline2.recycle(); correct1.recycle(); correct2.recycle(); invalid.recycle();
        }
    }
}
