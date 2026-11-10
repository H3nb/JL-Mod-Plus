// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

import java.lang.ref.WeakReference;

import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;
import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrzFreescaleScaler;

/**
 * Immutable source-region enhancement for LCDUI's HD screen target.
 * Guest pixels and Java ME logical geometry are never replaced or scaled.
 */
final class BitmapUpscaling {
    static final int NEAREST = 0;
    static final int BILINEAR = 1;
    static final int MMPX = 2;
    static final int XBRZ = 3;

    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private static final Paint NEAREST_PAINT = createPaint(false);
    private static final Paint BILINEAR_PAINT = createPaint(true);

    private final int mode;
    private final LruCache<RegionKey, Bitmap> cache =
            new LruCache<RegionKey, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(RegionKey key, Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };
    private volatile boolean allocationFailed;

    private static Paint createPaint(boolean filtered) {
        Paint paint = new Paint();
        paint.setFilterBitmap(filtered);
        return paint;
    }

    BitmapUpscaling(int mode) {
        this.mode = mode >= NEAREST && mode <= XBRZ ? mode : NEAREST;
    }

    void draw(Canvas canvas, Image source, Rect sourceRect, RectF destination, int targetScale) {
        Bitmap bitmap = source.getBitmap();
        if (targetScale <= 1 || source.getPixelScale() != 1) {
            canvas.drawBitmap(bitmap, sourceRect, destination, NEAREST_PAINT);
            return;
        }
        if (mode == NEAREST) {
            canvas.drawBitmap(bitmap, sourceRect, destination, NEAREST_PAINT);
            return;
        }
        if (mode == BILINEAR) {
            boolean full = sourceRect.left == 0 && sourceRect.top == 0
                    && sourceRect.right == bitmap.getWidth()
                    && sourceRect.bottom == bitmap.getHeight();
            // Direct bilinear sampling can read unrelated atlas pixels.
            Bitmap crop = full || source.isMutable() ? null : prepare(bitmap, sourceRect, 1);
            canvas.drawBitmap(crop == null ? bitmap : crop,
                    crop == null ? sourceRect : null, destination,
                    full || crop != null ? BILINEAR_PAINT : NEAREST_PAINT);
            return;
        }
        int factor = source.isMutable() ? 1 : ImageEnhancementPolicy.chooseFactor(
                sourceRect.width(), sourceRect.height(), targetScale, Integer.MAX_VALUE);
        Bitmap enhanced = factor == 1 ? null : prepare(bitmap, sourceRect, factor);
        // xBRZ already reconstructed its edges; smooth only the remaining
        // non-integer resampling. MMPX retains hard source-palette transitions.
        Paint paint = mode == XBRZ && factor != targetScale && enhanced != null
                ? BILINEAR_PAINT : NEAREST_PAINT;
        canvas.drawBitmap(enhanced == null ? bitmap : enhanced,
                enhanced == null ? sourceRect : null, destination,
                enhanced == null ? NEAREST_PAINT : paint);
    }

    private Bitmap prepare(Bitmap bitmap, Rect rect, int scale) {
        if (allocationFailed || bitmap.isRecycled()) return null;
        int width = rect.width();
        int height = rect.height();
        if (scale < 1 || width <= 0 || height <= 0
                || (long) width * height > ImageEnhancementPolicy.MAX_RESULT_BYTES
                        / (4L * scale * scale)) return null;

        RegionKey key = new RegionKey(bitmap, rect, scale);
        synchronized (cache) {
            Bitmap hit = cache.get(key);
            if (hit != null) return hit;
        }
        Bitmap output;
        try {
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, rect.left, rect.top, width, height);
            int[] reconstructed = scale == 1 ? pixels
                    : mode == MMPX ? MmpxScaler.scale(pixels, width, height, scale)
                    : XbrzFreescaleScaler.scale(pixels, width, height, scale);
            output = Bitmap.createBitmap(reconstructed, width * scale, height * scale,
                    Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            // Optional enhancement cannot be allowed to terminate the MIDlet.
            allocationFailed = true;
            return null;
        }
        synchronized (cache) {
            Bitmap existing = cache.get(key);
            if (existing != null) return existing;
            // Evicted bitmaps must not be recycled: a render may still use one.
            cache.put(key, output);
        }
        return output;
    }

    private static final class RegionKey {
        private final WeakReference<Bitmap> source;
        private final int identity;
        private final int x, y, width, height, factor, generation;

        RegionKey(Bitmap bitmap, Rect rect, int factor) {
            source = new WeakReference<>(bitmap);
            identity = System.identityHashCode(bitmap);
            x = rect.left;
            y = rect.top;
            width = rect.width();
            height = rect.height();
            this.factor = factor;
            generation = bitmap.getGenerationId();
        }

        @Override public int hashCode() {
            int h = identity;
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + width;
            h = 31 * h + height;
            h = 31 * h + factor;
            return 31 * h + generation;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RegionKey)) return false;
            RegionKey key = (RegionKey) other;
            Bitmap bitmap = source.get();
            return bitmap != null && bitmap == key.source.get()
                    && identity == key.identity && x == key.x && y == key.y
                    && width == key.width && height == key.height
                    && factor == key.factor && generation == key.generation;
        }
    }
}
