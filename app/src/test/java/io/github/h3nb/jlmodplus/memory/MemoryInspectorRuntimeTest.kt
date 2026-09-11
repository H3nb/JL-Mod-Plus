/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryInspectorRuntimeTest {
    @Test fun inspectorCenteringClampsWithoutSyntheticBlankSpace() {
        assertEquals(0, inspectorCenteredFirstIndex(cellCount = 0, anchorIndex = 0, visibleRows = 8))
        assertEquals(0, inspectorCenteredFirstIndex(cellCount = 5, anchorIndex = 2, visibleRows = 8))
        assertEquals(6, inspectorCenteredFirstIndex(cellCount = 20, anchorIndex = 10, visibleRows = 8))
        assertEquals(12, inspectorCenteredFirstIndex(cellCount = 20, anchorIndex = 19, visibleRows = 8))
        assertEquals(0, inspectorCenteredFirstIndex(cellCount = 20, anchorIndex = -50, visibleRows = 8))
        assertEquals(12, inspectorCenteredFirstIndex(cellCount = 20, anchorIndex = 99, visibleRows = 8))
    }

}
