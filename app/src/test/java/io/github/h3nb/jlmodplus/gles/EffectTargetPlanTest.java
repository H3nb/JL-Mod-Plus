// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import org.junit.jupiter.api.Test;
import io.github.h3nb.jlmodplus.config.ShaderInfo;
import io.github.h3nb.jlmodplus.config.ShaderSelection;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EffectTargetPlanTest {
    private static final long BUDGET = 64L * 1024 * 1024;

    private static EffectTargetPlan.Pass first(EffectCatalog.Size size) {
        return new EffectTargetPlan.Pass(size, true);
    }

    private static EffectTargetPlan.Pass next(EffectCatalog.Size size) {
        return new EffectTargetPlan.Pass(size, false);
    }

    @Test
    void singleScreenEffectWritesDirectlyToDisplayWithoutFbo() {
        EffectTargetPlan plan = EffectTargetPlan.create(
            List.of(first(EffectCatalog.Size.VIEWPORT)),
            240, 320, 1920, 3200, 1024, BUDGET);
        assertTrue(plan.slots.isEmpty());
        assertEquals(EffectTargetPlan.DISPLAY, plan.destination(0));
    }

    @Test
    void bloomPreservesOriginalInputAndReusesHalfResolutionTargets() {
        List<EffectTargetPlan.Pass> bloomAndColor = Arrays.asList(
            first(EffectCatalog.Size.HALF_VIEWPORT),
            next(EffectCatalog.Size.HALF_VIEWPORT),
            next(EffectCatalog.Size.HALF_VIEWPORT),
            next(EffectCatalog.Size.VIEWPORT),
            first(EffectCatalog.Size.VIEWPORT));
        EffectTargetPlan plan = EffectTargetPlan.create(
            bloomAndColor, 240, 320, 1080, 1920, 4096, BUDGET);
        assertEquals(3, plan.slots.size());
        assertEquals(540, plan.slots.get(0).width);
        assertEquals(960, plan.slots.get(0).height);
        assertEquals(1080, plan.slots.get(2).width);
        assertEquals(1920, plan.slots.get(2).height);
        assertEquals(0, plan.destination(0));
        assertEquals(1, plan.destination(1));
        assertEquals(0, plan.destination(2));
        assertEquals(2, plan.destination(3));
        assertEquals(EffectTargetPlan.DISPLAY, plan.destination(4));
    }

    @Test
    void threeFullScreenEffectsNeedTwoTargetsRatherThanThree() {
        EffectTargetPlan plan = EffectTargetPlan.create(List.of(
            first(EffectCatalog.Size.VIEWPORT),
            first(EffectCatalog.Size.VIEWPORT),
            first(EffectCatalog.Size.VIEWPORT)),
            240, 320, 1080, 1920, 4096, BUDGET);
        assertEquals(2, plan.slots.size());
        assertEquals(0, plan.destination(0));
        assertEquals(1, plan.destination(1));
        assertEquals(EffectTargetPlan.DISPLAY, plan.destination(2));
    }

    @Test
    void rejectsExcessiveBudgetBeforeAnyOffscreenTargetExists() {
        assertThrows(IllegalStateException.class, () -> EffectTargetPlan.create(
            List.of(first(EffectCatalog.Size.VIEWPORT),
                    first(EffectCatalog.Size.VIEWPORT),
                    first(EffectCatalog.Size.VIEWPORT)),
            240, 320, 2300, 3700, 4096, BUDGET));
    }

    @Test
    void legacyUserShadersKeepViewportSizeAndConfiguredSampling() {
        ShaderInfo info = new ShaderInfo("Legacy", "User");
        info.vertex = "legacy.vsh";
        info.fragment = "legacy.fsh";
        for (boolean outputResolution : new boolean[]{false, true}) {
            for (boolean upscaling : new boolean[]{false, true}) {
                info.set("OutputResolution=" + outputResolution);
                info.set("Upscaling=" + upscaling);
                ShaderSelection selection = ShaderSelection.fromInfo(info);
                EffectCatalog.Pass pass = PostProcessRenderer.legacyPass(selection);
                assertEquals("legacy.fsh", pass.fragment);
                assertEquals(EffectCatalog.Size.VIEWPORT, pass.size);
                assertEquals(EffectCatalog.Sampling.DISPLAY, pass.sampling,
                        "Legacy shader must follow ScreenFilter");
                assertFalse(pass.composite);
                EffectTargetPlan plan = EffectTargetPlan.create(
                    List.of(first(pass.size), first(EffectCatalog.Size.VIEWPORT)),
                    240, 320, 1080, 1920, 4096, BUDGET);
                assertEquals(1, plan.slots.size());
                assertEquals(1080, plan.slots.get(0).width);
                assertEquals(1920, plan.slots.get(0).height);
                assertEquals(EffectTargetPlan.DISPLAY, plan.destination(1));
            }
        }
    }

    @Test
    void inputSizedPassUsesGuestContentSize() {
        EffectTargetPlan plan = EffectTargetPlan.create(List.of(
            first(EffectCatalog.Size.INPUT), first(EffectCatalog.Size.VIEWPORT)),
            240, 320, 1080, 1920, 4096, BUDGET);
        assertEquals(1, plan.slots.size());
        assertEquals(240, plan.slots.get(0).width);
        assertEquals(320, plan.slots.get(0).height);
    }
}
