// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffectGpuBudgetTest {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = EffectGpuBudget.MIB;

    @Test
    void selectsConservativeTiersWithoutTreatingRamAsFreeGpuMemory() {
        assertEquals(32L * MIB, EffectGpuBudget.select(2L * GIB, false, false));
        assertEquals(64L * MIB, EffectGpuBudget.select(4L * GIB, false, false));
        assertEquals(128L * MIB, EffectGpuBudget.select(8L * GIB, false, false));
        assertEquals(256L * MIB, EffectGpuBudget.select(12L * GIB, false, false));
    }

    @Test
    void lowMemoryAndUnknownDeviceInformationFailConservatively() {
        assertEquals(32L * MIB, EffectGpuBudget.select(16L * GIB, true, false));
        assertEquals(32L * MIB, EffectGpuBudget.select(16L * GIB, false, true));
        assertEquals(64L * MIB, EffectGpuBudget.select(0L, false, false));
    }

    @Test
    void largerDevicesPermitTheSameValidEffectPlanWithoutChangingPasses() {
        List<EffectTargetPlan.Pass> passes = List.of(
                new EffectTargetPlan.Pass(EffectCatalog.Size.VIEWPORT, true),
                new EffectTargetPlan.Pass(EffectCatalog.Size.VIEWPORT, true),
                new EffectTargetPlan.Pass(EffectCatalog.Size.VIEWPORT, true));
        int width = 2300, height = 3700;
        assertThrows(IllegalStateException.class, () -> EffectTargetPlan.create(
                passes, 240, 320, width, height, 4096,
                EffectGpuBudget.select(4L * GIB, false, false)));
        EffectTargetPlan large = EffectTargetPlan.create(passes, 240, 320,
                width, height, 4096, EffectGpuBudget.select(12L * GIB, false, false));
        assertEquals(2, large.slots.size());
        assertEquals(EffectTargetPlan.DISPLAY, large.destination(2));
    }
}
