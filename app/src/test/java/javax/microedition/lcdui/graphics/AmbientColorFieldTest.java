/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmbientColorFieldTest {
    @Test
    public void weightsRemainFiniteForSmallAndDegenerateGeometry() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(
                new float[]{Float.NaN, 0.5f, 1.0f},
                new float[]{Float.POSITIVE_INFINITY, 0.5f, -1.0f},
                0.0f, 0.0f, 1.0f, 1.0f, 0.0f);

        float[] output = new float[9];
        assertEquals(3, field.nodeCount());
        assertFalse(field.renderNodes(0L, output));
        for (float value : output) {
            assertTrue(Float.isFinite(value));
            assertTrue(value >= 0.0f && value <= 1.0f);
        }
    }

    @Test
    public void exponentialSmoothingUsesMonotonicHostClock() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.5f}, new float[]{0.5f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        int[] red = new int[AmbientColorField.ANCHOR_COUNT];
        for (int i = 0; i < red.length; i++) red[i] = 0xFFFF0000;
        field.setTarget(red, 0xFF000000, 0L, false);

        float[] output = new float[3];
        field.renderNodes(AmbientColorField.TAU_NS, output);
        assertEquals(1.0 - Math.exp(-1.0), output[0], 0.02);
        assertTrue(output[0] > 0.5f);
        assertTrue(output[1] < 0.01f);

        int[] blue = new int[AmbientColorField.ANCHOR_COUNT];
        for (int i = 0; i < blue.length; i++) blue[i] = 0xFF0000FF;
        field.setTarget(blue, 0xFF000000, AmbientColorField.TAU_NS, false);
        field.renderNodes(AmbientColorField.TAU_NS, output);
        assertTrue(output[0] > 0.5f);
        field.renderNodes(AmbientColorField.TAU_NS + AmbientColorField.MAX_TRANSITION_NS, output);
        assertEquals(0.0f, output[0], 0.01f);
        assertEquals(1.0f, output[2], 0.01f);
    }

    @Test
    public void edgeBleedFollowsGameBoundaryBeforeFadingToSurfaceField() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(
                new float[]{0.5f, 0.5f, 0.5f},
                new float[]{0.25f, 0.34f, 0.50f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        int[] anchors = new int[AmbientColorField.ANCHOR_COUNT];
        for (int i = 0; i < anchors.length; i++) anchors[i] = 0xFF0000FF;
        anchors[0] = anchors[1] = anchors[2] = 0xFFFF0000;
        field.setTarget(anchors, 0xFF000000, 0L, true);

        float[] output = new float[9];
        field.renderNodes(0L, output);
        assertTrue(output[0] > 0.95f);
        assertTrue(output[3] < output[0]);
        assertTrue(output[3] > 0.5f);
        assertTrue(output[6] < 0.4f);
        assertTrue(output[1] < 0.05f);
        assertTrue(output[2] < 0.05f);
    }

    @Test
    public void fullFrameGridKeepsLocalColorsConnectedToEachEdge() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.25f, 0.75f}, new float[]{0.25f, 0.25f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        int[] grid = new int[AmbientColorField.GRID_COLOR_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                grid[y * AmbientColorField.GRID_SIZE + x] = x < 4
                        ? 0xFFFF0000 : 0xFF0000FF;
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[2 * AmbientColorField.CHANNEL_COUNT];
        assertFalse(field.renderNodes(0L, output));
        assertTrue(output[0] > output[2]);
        assertTrue(output[5] > output[3]);
    }

    @Test
    public void fullFrameGridSoftensAdjacentPaletteCells() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.25f, 0.25f, 0.25f},
                new float[]{0.25f, 0.50f, 0.75f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        int[] grid = new int[AmbientColorField.GRID_COLOR_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                grid[y * AmbientColorField.GRID_SIZE + x] = y < 4
                        ? 0xFFFF0000 : 0xFF0000FF;
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[3 * AmbientColorField.CHANNEL_COUNT];
        assertFalse(field.renderNodes(0L, output));
        assertTrue(output[0] > output[2]);
        assertTrue(output[6] < output[8]);
        assertTrue(output[3] > 0.05f);
        assertTrue(output[5] > 0.05f);
    }
}
