// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ImageEnhancementPolicyTest {
    @Test void supportsRequestedScaleWithoutAConstantMemoryBudget() {
        assertEquals(10, ImageEnhancementPolicy.chooseFactor(64, 64, 10, Integer.MAX_VALUE));
        assertEquals(4, ImageEnhancementPolicy.chooseFactor(240, 320, 4, Integer.MAX_VALUE));
        assertEquals(10, ImageEnhancementPolicy.chooseFactor(240, 320, 10, Integer.MAX_VALUE));
        assertEquals(4, ImageEnhancementPolicy.chooseFactor(1024, 1024, 4, Integer.MAX_VALUE));
    }

    @Test void hardwareLimitsAndInvalidSizesHaveSafeFallbacks() {
        assertEquals(2, ImageEnhancementPolicy.chooseFactor(256, 256, 4, 512));
        assertEquals(1, ImageEnhancementPolicy.chooseFactor(256, 256, 4, 255));
        assertEquals(1, ImageEnhancementPolicy.chooseFactor(64, 64, 1, 4096));
        assertEquals(1, ImageEnhancementPolicy.chooseFactor(-1, 64, 4, 4096));
        assertEquals(1, ImageEnhancementPolicy.chooseFactor(Integer.MAX_VALUE,
                Integer.MAX_VALUE, 10, Integer.MAX_VALUE));
        assertEquals(1, ImageEnhancementPolicy.chooseFactor(32768, 32768, 2, Integer.MAX_VALUE));
    }
}
