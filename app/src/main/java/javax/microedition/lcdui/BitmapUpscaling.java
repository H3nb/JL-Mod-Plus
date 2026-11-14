// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.h3nb.jlmodplus.R;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;
import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrFreeScaler;

/**
 * Immutable source-region enhancement for LCDUI's HD screen target.
 * Guest pixels and Java ME logical geometry are never replaced or scaled.
 */
final class BitmapUpscaling {
    static final int NEAREST = 0;
    static final int BILINEAR = 1;
    static final int MMPX = 2;
    static final int EDGE_AWARE = 3;

    static int cacheBudgetBytes(long heapLimitBytes) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, heapLimitBytes / 16L));
    }
    private static final Paint NEAREST_PAINT = createPaint(false);
    private static final Paint BILINEAR_PAINT = createPaint(true);

    private final int mode;
    private final LruCache<RegionKey, Bitmap> cache =
            new LruCache<RegionKey, Bitmap>(cacheBudgetBytes(Runtime.getRuntime().maxMemory())) {
                @Override
                protected int sizeOf(RegionKey key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private volatile long failedOutputPixels = Long.MAX_VALUE;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    private static Paint createPaint(boolean filtered) {
        Paint paint = new Paint();
        paint.setFilterBitmap(filtered);
        return paint;
    }

    BitmapUpscaling(int mode) {
        this.mode = mode >= NEAREST && mode <= EDGE_AWARE ? mode : NEAREST;
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
        long regionPixels = (long) sourceRect.width() * sourceRect.height();
        while (factor > 1 && regionPixels > (failedOutputPixels - 1)
                / ((long) factor * factor)) factor--;
        Bitmap enhanced = factor == 1 ? null : prepare(bitmap, sourceRect, factor);
        // xBR reconstruction is already antialiased; smooth only the remaining
        // non-integer resampling. MMPX retains hard source-palette transitions.
        Paint paint = mode == EDGE_AWARE && factor != targetScale && enhanced != null
                ? BILINEAR_PAINT : NEAREST_PAINT;
        canvas.drawBitmap(enhanced == null ? bitmap : enhanced,
                enhanced == null ? sourceRect : null, destination,
                enhanced == null ? NEAREST_PAINT : paint);
    }

    private Bitmap prepare(Bitmap bitmap, Rect rect, int scale) {
        if (bitmap.isRecycled()) return null;
        int width = rect.width();
        int height = rect.height();
        if (scale < 1 || width <= 0 || height <= 0
                || (long) width * height > Math.min(
                        ImageEnhancementPolicy.MAX_RESULT_PIXELS, failedOutputPixels - 1)
                        / ((long) scale * scale)) return null;
        long outputPixels = (long) width * height * scale * scale;

        RegionKey key = new RegionKey(bitmap, rect, scale);
        synchronized (cache) {
            Bitmap hit = cache.get(key);
            if (hit != null) return hit;
        }
        Bitmap output;
        try {
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, rect.left, rect.top, width, height);
            if (mode == EDGE_AWARE && scale > 1) {
                output = Bitmap.createBitmap(width * scale, height * scale,
                        Bitmap.Config.ARGB_8888);
                Bitmap target = output;
                XbrFreeScaler.scaleRows(pixels, width, height, scale,
                        (firstRow, argb, rows) -> target.setPixels(argb, 0,
                                width * scale, 0, firstRow, width * scale, rows));
            } else {
                int[] reconstructed = scale == 1 ? pixels
                        : MmpxScaler.scale(pixels, width, height, scale);
                output = Bitmap.createBitmap(reconstructed, width * scale, height * scale,
                        Bitmap.Config.ARGB_8888);
            }
        } catch (OutOfMemoryError error) {
            failedOutputPixels = Math.min(failedOutputPixels, outputPixels);
            synchronized (cache) {
                cache.evictAll();
            }
            MicroActivity activity = ContextHolder.getActivity();
            if (activity != null && failureReported.compareAndSet(false, true)) {
                activity.showRenderingNotice(R.string.render_bitmap_memory_reduced);
            }
            return null;
        }
        synchronized (cache) {
            Bitmap existing = cache.get(key);
            if (existing != null) return existing;
            int bytes = output.getAllocationByteCount();
            if (bytes > cache.maxSize()) {
                cache.evictAll();
                cache.resize(bytes);
            } else if ((long) cache.size() + bytes > Integer.MAX_VALUE) {
                cache.evictAll();
            }
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
