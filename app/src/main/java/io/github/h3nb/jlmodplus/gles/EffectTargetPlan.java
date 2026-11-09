// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A frame-sized plan for offscreen targets. Does not own or access GL resources.
 * A pass cannot write to its current input or the start-of-effect input kept
 * for compositing. The final viewport-sized pass writes to the display.
 */
final class EffectTargetPlan {
    static final int DISPLAY = -1;

    static final class Pass {
        final EffectCatalog.Size size;
        final boolean first;
        Pass(EffectCatalog.Size size, boolean first) {
            this.size = size;
            this.first = first;
        }
    }

    static final class Slot {
        final int width, height;
        Slot(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    final List<Slot> slots;
    private final int[] destinations;

    private EffectTargetPlan(List<Slot> slots, int[] destinations) {
        this.slots = Collections.unmodifiableList(slots);
        this.destinations = destinations;
    }

    int destination(int pass) { return destinations[pass]; }

    static EffectTargetPlan create(List<Pass> passes, int contentW, int contentH,
                                   int viewportW, int viewportH, int maxTextureSize,
                                   long budgetBytes) {
        if (contentW <= 0 || contentH <= 0 || viewportW <= 0 || viewportH <= 0) {
            throw new IllegalArgumentException("Invalid effect render dimensions");
        }
        ArrayList<Slot> slots = new ArrayList<>();
        int[] destinations = new int[passes.size()];
        int current = DISPLAY, effectBase = DISPLAY;
        int visibleW = contentW, visibleH = contentH;
        long allocatedBytes = 0L;
        for (int i = 0; i < passes.size(); i++) {
            Pass pass = passes.get(i);
            if (pass.first) effectBase = current;
            int outW = viewportW, outH = viewportH;
            if (pass.size == EffectCatalog.Size.HALF_VIEWPORT) {
                outW = Math.max(1, viewportW / 2);
                outH = Math.max(1, viewportH / 2);
            } else if (pass.size == EffectCatalog.Size.INPUT) {
                outW = visibleW;
                outH = visibleH;
            }

            // Writing straight to the default framebuffer needs no GPU texture.
            boolean direct = i == passes.size() - 1
                    && outW == viewportW && outH == viewportH;
            if (direct) {
                destinations[i] = DISPLAY;
            } else {
                int next = DISPLAY;
                for (int j = 0; j < slots.size(); j++) {
                    Slot slot = slots.get(j);
                    if (j != current && j != effectBase &&
                            slot.width == outW && slot.height == outH) {
                        next = j;
                        break;
                    }
                }
                if (next == DISPLAY) {
                    allocatedBytes += 4L * outW * outH;
                    if (outW > maxTextureSize || outH > maxTextureSize
                            || allocatedBytes > budgetBytes) {
                        throw new IllegalStateException("Post-processing target budget exceeded");
                    }
                    next = slots.size();
                    slots.add(new Slot(outW, outH));
                }
                destinations[i] = next;
                current = next;
            }
            visibleW = outW;
            visibleH = outH;
        }
        return new EffectTargetPlan(slots, destinations);
    }
}
