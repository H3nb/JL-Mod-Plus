// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class InternalResolutionTest {
    @Test public void autoUsesFinalViewportAndIntegerFactors() {
        assertEquals(4, InternalResolution.resolve(0, 240, 320, 1080, 1920));
        assertEquals(3, InternalResolution.resolve(0, 240, 320, 960, 1000));
        assertEquals(1, InternalResolution.resolve(0, 240, 320, 239, 319));
        // The visible height (after softbar) selects Auto; the larger backing
        // height is used only to enforce allocation limits.
        assertEquals(5, InternalResolution.resolve(0, 240, 300, 320, 1200, 1650));
    }

    @Test public void manualSupportsSupersamplingWithinBudget() {
        assertEquals(1, InternalResolution.resolve(1, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(10, 240, 320, 1080, 1920));
        assertEquals(4, InternalResolution.resolve(10, 800, 600, 1920, 1080));
        assertEquals(1, InternalResolution.resolve(8, 0, 320, 1080, 1920));
    }

    @Test public void invalidSelectionsAreBounded() {
        assertEquals(1, InternalResolution.resolve(-3, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(200, 240, 320, 1080, 1920));
    }
}
