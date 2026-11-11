// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class RgbaTextureEnhancerTest {
    @Test void mmpxPreservesRgbaOrderingAndSourceBuffer() {
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put(new byte[] { (byte) 0x20, (byte) 0x80, (byte) 0xE0, (byte) 0xA0 });
        src.position(2);
        ByteBuffer result = RgbaTextureEnhancer.reconstruct(src, 1, 1, 2, 2);
        assertEquals(2, src.position());
        assertEquals(16, result.remaining());
        for (int i = 0; i < 4; i++) {
            assertEquals(0x20, result.get() & 255);
            assertEquals(0x80, result.get() & 255);
            assertEquals(0xE0, result.get() & 255);
            assertEquals(0xA0, result.get() & 255);
        }
    }

    @Test void opaqueEligibilityProtectsBinaryAlphaAndDoesNotMutateBuffer() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(12);
        pixels.put(new byte[] {(byte) 255, 0, 0, (byte) 255,
                0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255});
        pixels.position(7);
        assertTrue(RgbaTextureEnhancer.isOpaque(pixels, 3, 1));
        assertEquals(7, pixels.position());
        pixels.put(11, (byte) 0);
        assertFalse(RgbaTextureEnhancer.isOpaque(pixels, 3, 1));
        assertFalse(RgbaTextureEnhancer.isOpaque(pixels, 4, 1));
        assertFalse(RgbaTextureEnhancer.isOpaque(pixels, 0, 1));
    }

    @Test void alphaAwareXbrzNeverCreatesInvisibleSourceColorFringesOnUniformInput() {
        ByteBuffer src = ByteBuffer.allocate(2 * 2 * 4);
        for (int i = 0; i < 4; i++) src.put(new byte[] {0, 0, (byte) 255, (byte) 255});
        ByteBuffer result = RgbaTextureEnhancer.reconstruct(src, 2, 2, 3, 3);
        assertEquals(6 * 6 * 4, result.remaining());
        while (result.hasRemaining()) {
            assertEquals(0, result.get() & 255);
            assertEquals(0, result.get() & 255);
            assertEquals(255, result.get() & 255);
            assertEquals(255, result.get() & 255);
        }
    }
}
