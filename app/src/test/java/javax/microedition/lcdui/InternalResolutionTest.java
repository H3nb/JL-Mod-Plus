// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

public class InternalResolutionTest {
    @Test public void autoUsesFinalViewportAndIntegerFactors() {
        assertEquals(4, InternalResolution.resolve(0, 240, 320, 1080, 1920));
        assertEquals(3, InternalResolution.resolve(0, 240, 320, 960, 1000));
        assertEquals(1, InternalResolution.resolve(0, 240, 320, 239, 319));
        // The visible height (after softbar) selects Auto; the larger backing
        // height is used only to enforce allocation limits.
        assertEquals(5, InternalResolution.resolve(0, 240, 300, 320, 1200, 1650));
    }

    @Test public void manualSupportsSupersamplingWithinBitmapRepresentationLimits() {
        assertEquals(1, InternalResolution.resolve(1, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(10, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(10, 800, 600, 1920, 1080));
        assertEquals(1, InternalResolution.resolve(8, 0, 320, 1080, 1920));
    }

    @Test public void invalidSelectionsAreBounded() {
        assertEquals(1, InternalResolution.resolve(-3, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(200, 240, 320, 1080, 1920));
    }
    @Test public void logicalResizeOnlyReducesDensityWhenTargetLimitsRequireIt() {
        int initial = InternalResolution.resolve(10, 240, 135, 135, 1920, 1080);
        assertEquals(10, initial);
        int resized = InternalResolution.resolve(initial, 240, 426, 426, 1080, 1920);
        assertEquals(10, resized);
        assertEquals(10, InternalResolution.resolve(resized,
                240, 135, 135, 1920, 1080));

        assertEquals(10, InternalResolution.resolve(10, 800, 500, 500, 1920, 1080));
        assertEquals(1, InternalResolution.resolve(10,
                Integer.MAX_VALUE, Integer.MAX_VALUE, 1080, 1920));
    }
    @Test public void allocationFailureDowngradesToTheFirstUsableDensity() {
        java.util.ArrayList<Integer> attempts = new java.util.ArrayList<>();
        int resolved = InternalResolution.allocateWithFallback(5, scale -> {
            attempts.add(scale);
            if (scale > 2) throw new OutOfMemoryError("simulated allocation failure");
        });
        assertEquals(2, resolved);
        assertEquals(java.util.List.of(5, 4, 3, 2), attempts);
    }

    @Test public void fatalOneTimesAllocationFailurePropagates() {
        java.util.ArrayList<Integer> attempts = new java.util.ArrayList<>();
        org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class,
                () -> InternalResolution.allocateWithFallback(3, scale -> {
                    attempts.add(scale);
                    throw new OutOfMemoryError("all buffers unavailable");
                }));
        assertEquals(java.util.List.of(3, 2, 1), attempts);
    }

}
