/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageMotionDirectionTest {
    @Test fun peerDestinationsFollowTheirOrderRatherThanAlwaysMovingBack() {
        assertEquals(1, pageMotionDirection(2, 2, 1, 3))
        assertEquals(-1, pageMotionDirection(2, 2, 3, 1))
    }

    @Test fun hierarchyBeatsSiblingOrderWhenReturningToParent() {
        assertEquals(1, pageMotionDirection(0, 2, 4, 1))
        assertEquals(-1, pageMotionDirection(2, 0, 1, 4))
    }
}
