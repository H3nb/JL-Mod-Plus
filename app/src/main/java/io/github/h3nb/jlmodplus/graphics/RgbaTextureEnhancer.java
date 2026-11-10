// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

import java.nio.ByteBuffer;

/** Converts immutable Mascot RGBA texture data to enhanced GPU-upload pixels. */
public final class RgbaTextureEnhancer {
    private RgbaTextureEnhancer() {}

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
        for (int argb : output) {
            packed.put((byte) (argb >>> 16));
            packed.put((byte) (argb >>> 8));
            packed.put((byte) argb);
            packed.put((byte) (argb >>> 24));
        }
        packed.flip();
        return packed;
    }
}
