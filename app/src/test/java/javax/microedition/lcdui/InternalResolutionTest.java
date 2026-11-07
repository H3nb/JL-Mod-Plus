// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class InternalResolutionTest {
    @Test public void autoUsesFinalGameViewportWithoutChangingGuestDimensions() {
        assertEquals(4, InternalResolution.resolve(0, 240, 320, 1080, 1920));
        assertEquals(1, InternalResolution.resolve(0, 240, 320, 239, 319));
        assertEquals(3, InternalResolution.resolve(0, 240, 320, 960, 1000));
    }

    @Test public void manualSupportsOneToTenWithGuardrails() {
        assertEquals(1, InternalResolution.resolve(1, 240, 320, 1080, 1920));
        assertEquals(10, InternalResolution.resolve(10, 240, 320, 1080, 1920));
        assertEquals(4, InternalResolution.resolve(10, 800, 600, 1920, 1080));
        assertEquals(1, InternalResolution.resolve(10, 0, 320, 1080, 1920));
    }
}
