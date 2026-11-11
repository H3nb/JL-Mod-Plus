// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

import java.nio.ByteBuffer;

/** Converts immutable Mascot RGBA texture data to enhanced GPU-upload pixels. */
public final class RgbaTextureEnhancer {
    private RgbaTextureEnhancer() {}

    /**
     * Model texture cutouts depend on exact alpha-test boundaries. Until the
     * reconstructed alpha coverage is qualified, enhance only opaque sources.
     * Absolute reads leave the guest's source buffer position unchanged.
     */
    public static boolean isOpaque(ByteBuffer rgba, int width, int height) {
        long count = (long) width * height;
        if (rgba == null || width < 1 || height < 1
                || count > Integer.MAX_VALUE / 4L || count * 4L > rgba.limit()) {
            return false;
        }
        for (int i = 0; i < (int) count; i++) {
            if ((rgba.get(i * 4 + 3) & 0xff) != 255) return false;
        }
        return true;
    }

    /**
     * Android Image.getRGB supplies ARGB integers; GPU texture memory expects
     * RGBA bytes regardless of the device's native ByteBuffer byte order.
     */
    public static void putArgbPixels(ByteBuffer rgba, int[] argb) {
        if (rgba == null || argb == null || (long) argb.length * 4 > rgba.remaining()) {
            throw new IllegalArgumentException("Insufficient RGBA output");
        }
        for (int color : argb) {
            rgba.put((byte) (color >>> 16));
            rgba.put((byte) (color >>> 8));
            rgba.put((byte) color);
            rgba.put((byte) (color >>> 24));
        }
    }

    public static ByteBuffer reconstruct(ByteBuffer rgba, int width, int height,
            int factor, int mode) {
        if (rgba == null || width < 1 || height < 1 || factor < 2
                || factor > ImageEnhancementPolicy.MAX_RECONSTRUCTION_FACTOR
                || (mode != 2 && mode != 3)
                || (long) width * height * factor * factor * 4
                        > ImageEnhancementPolicy.MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("Invalid texture reconstruction request");
        }
        int count = width * height;
        ByteBuffer input = rgba.duplicate();
        input.position(0);
        if (input.remaining() < count * 4L) {
            throw new IllegalArgumentException("Insufficient RGBA source pixels");
        }
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            int r = input.get() & 255;
            int g = input.get() & 255;
            int b = input.get() & 255;
            int a = input.get() & 255;
            pixels[i] = a << 24 | r << 16 | g << 8 | b;
        }
        int[] output = mode == 2 ? MmpxScaler.scale(pixels, width, height, factor)
                : XbrzFreescaleScaler.scale(pixels, width, height, factor);
        ByteBuffer packed = ByteBuffer.allocateDirect(output.length * 4);
        putArgbPixels(packed, output);
        packed.flip();
        return packed;
    }
}
