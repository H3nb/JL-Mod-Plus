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
    public void exponentialSmoothingUsesMonotonicHostClockInLinearLight() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.5f}, new float[]{0.5f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        int[] red = new int[AmbientColorField.ANCHOR_COUNT];
        for (int i = 0; i < red.length; i++) red[i] = 0xFFFF0000;
        field.setTarget(red, 0xFF000000, 0L, false);

        float[] anchors = new float[AmbientColorField.ANCHOR_COUNT
                * AmbientColorField.CHANNEL_COUNT];
        float[] base = new float[AmbientColorField.CHANNEL_COUNT];
        field.evaluate(AmbientColorField.TAU_NS, anchors, base);
        assertEquals(1.0 - Math.exp(-1.0), anchors[0], 0.02);
        assertTrue(anchors[0] > 0.5f);
        assertTrue(anchors[1] < 0.01f);

        int[] blue = new int[AmbientColorField.ANCHOR_COUNT];
        for (int i = 0; i < blue.length; i++) blue[i] = 0xFF0000FF;
        field.setTarget(blue, 0xFF000000, AmbientColorField.TAU_NS, false);
        field.evaluate(AmbientColorField.TAU_NS, anchors, base);
        assertTrue(anchors[0] > 0.5f);
        field.evaluate(AmbientColorField.TAU_NS + AmbientColorField.MAX_TRANSITION_NS,
                anchors, base);
        assertEquals(0.0f, anchors[0], 0.01f);
        assertEquals(1.0f, anchors[2], 0.01f);
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
        assertTrue(output[6] < output[0]);
        assertTrue(output[1] < 0.05f);
        assertTrue(output[2] < 0.05f);
    }

    @Test
    public void fullFrameGridKeepsLocalColorsConnectedToEachEdge() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.25f, 0.75f}, new float[]{0.25f, 0.25f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        float[] grid = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                if (x < AmbientColorField.GRID_SIZE / 2) {
                    setGridColor(grid, x, y, 1.0f, 0.0f, 0.0f);
                } else {
                    setGridColor(grid, x, y, 0.0f, 0.0f, 1.0f);
                }
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[2 * AmbientColorField.CHANNEL_COUNT];
        assertFalse(field.renderNodes(0L, output));
        assertTrue(output[0] > output[2]);
        assertTrue(output[5] > output[3]);
    }

    @Test
    public void fullFrameGridSoftensAdjacentPaletteCellsWithoutFlatteningTheField() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.25f, 0.25f, 0.25f},
                new float[]{0.25f, 0.50f, 0.75f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        float[] grid = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                if (y < AmbientColorField.GRID_SIZE / 2) {
                    setGridColor(grid, x, y, 1.0f, 0.0f, 0.0f);
                } else {
                    setGridColor(grid, x, y, 0.0f, 0.0f, 1.0f);
                }
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

    @Test
    public void gridTransitionStaysLinearUntilSrgbOutput() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.5f}, new float[]{0.5f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        float[] white = solidGrid(1.0f, 1.0f, 1.0f);
        field.setTargetGrid(white, 0xFF000000, 0L, false);

        float[] evaluated = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        float[] base = new float[AmbientColorField.CHANNEL_COUNT];
        field.evaluateGrid(AmbientColorField.TAU_NS, evaluated, base);
        float expectedLinear = (float) (1.0 - Math.exp(-1.0));
        assertEquals(expectedLinear, evaluated[0], 0.02f);

        float[] output = new float[AmbientColorField.CHANNEL_COUNT];
        field.renderNodes(AmbientColorField.TAU_NS, output);
        assertTrue(output[0] > evaluated[0]);
        assertTrue(output[0] > 0.68f);
        assertEquals(output[0], output[1], 0.001f);
        assertEquals(output[1], output[2], 0.001f);
    }

    @Test
    public void activeGridDoesNotRetargetWhenHostThemeChanges() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(new float[]{0.15f}, new float[]{0.5f},
                0.25f, 0.25f, 0.75f, 0.75f, 1.0f);
        field.setTargetGrid(solidGrid(1.0f, 0.0f, 0.0f), 0xFF000000, 0L, true);

        float[] before = new float[AmbientColorField.CHANNEL_COUNT];
        field.renderNodes(0L, before);
        field.setBaseColor(0xFFFFFFFF, AmbientColorField.TAU_NS, false);
        float[] after = new float[AmbientColorField.CHANNEL_COUNT];
        assertFalse(field.renderNodes(AmbientColorField.TAU_NS, after));

        assertEquals(before[0], after[0], 0.0001f);
        assertEquals(before[1], after[1], 0.0001f);
        assertEquals(before[2], after[2], 0.0001f);
    }

    @Test
    public void guestDerivedExtensionDoesNotFadeToHostThemeAtSurfaceEdge() {
        AmbientColorField darkThemeField = new AmbientColorField();
        AmbientColorField lightThemeField = new AmbientColorField();
        float[] nodeX = {0.0f, 1.0f, 0.5f, 0.5f};
        float[] nodeY = {0.5f, 0.5f, 0.0f, 1.0f};
        darkThemeField.configureNodes(nodeX, nodeY,
                0.30f, 0.30f, 0.70f, 0.70f, 1.0f);
        lightThemeField.configureNodes(nodeX, nodeY,
                0.30f, 0.30f, 0.70f, 0.70f, 1.0f);
        float[] red = solidGrid(1.0f, 0.0f, 0.0f);
        darkThemeField.setTargetGrid(red, 0xFF000000, 0L, true);
        lightThemeField.setTargetGrid(red, 0xFFFFFFFF, 0L, true);

        float[] darkOutput = new float[4 * AmbientColorField.CHANNEL_COUNT];
        float[] lightOutput = new float[4 * AmbientColorField.CHANNEL_COUNT];
        darkThemeField.renderNodes(0L, darkOutput);
        lightThemeField.renderNodes(0L, lightOutput);
        for (int i = 0; i < darkOutput.length; i++) {
            assertEquals(darkOutput[i], lightOutput[i], 0.001f);
        }
        for (int node = 0; node < 4; node++) {
            int offset = node * AmbientColorField.CHANNEL_COUNT;
            assertTrue(darkOutput[offset] > 0.95f);
            assertTrue(darkOutput[offset + 1] < 0.01f);
            assertTrue(darkOutput[offset + 2] < 0.01f);
        }
    }

    @Test
    public void roundedEmitterDoesNotSampleTheSharpGuestCornerDirectly() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(
                new float[]{0.30f}, new float[]{0.30f},
                0.30f, 0.30f, 0.70f, 0.70f, 1.0f);
        float[] grid = solidGrid(1.0f, 0.0f, 0.0f);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                setGridColor(grid, x, y, 0.0f, 0.0f, 1.0f);
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[AmbientColorField.CHANNEL_COUNT];
        field.renderNodes(0L, output);
        assertTrue(output[0] > output[2] + 0.10f);
    }

    @Test
    public void blurFootprintWidensAsTheExtensionMovesAwayFromTheLcd() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(
                new float[]{0.28f, 0.0f}, new float[]{0.50f, 0.50f},
                0.30f, 0.30f, 0.70f, 0.70f, 1.0f);
        float[] grid = solidGrid(1.0f, 0.0f, 0.0f);
        int centerY = AmbientColorField.GRID_SIZE / 2;
        for (int y = centerY - 1; y <= centerY; y++) {
            for (int x = 0; x < 3; x++) {
                setGridColor(grid, x, y, 0.0f, 0.0f, 1.0f);
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[2 * AmbientColorField.CHANNEL_COUNT];
        field.renderNodes(0L, output);
        float nearBlue = output[2];
        float farBlue = output[5];
        assertTrue(nearBlue > farBlue + 0.02f);
        assertTrue(output[3] > output[0]);
    }

    @Test
    public void radialEdgeExtensionRemainsSpatiallyDistinctAfterDiffusion() {
        AmbientColorField field = new AmbientColorField();
        field.configureNodes(
                new float[]{0.25f, 0.75f, 0.25f, 0.75f},
                new float[]{0.12f, 0.12f, 0.88f, 0.88f},
                0.30f, 0.30f, 0.70f, 0.70f, 1.0f);
        float[] grid = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                float r = x < AmbientColorField.GRID_SIZE / 2 ? 1.0f : 0.0f;
                float b = x < AmbientColorField.GRID_SIZE / 2 ? 0.0f : 1.0f;
                float g = y >= AmbientColorField.GRID_SIZE / 2 ? 0.55f : 0.0f;
                setGridColor(grid, x, y, r, g, b);
            }
        }
        field.setTargetGrid(grid, 0xFF000000, 0L, true);

        float[] output = new float[4 * AmbientColorField.CHANNEL_COUNT];
        field.renderNodes(0L, output);
        assertTrue(output[0] > output[2]);
        assertTrue(output[5] > output[3]);
        assertTrue(output[7] > output[1]);
        assertTrue(output[10] > output[4]);
    }

    private static float[] solidGrid(float r, float g, float b) {
        float[] grid = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        for (int y = 0; y < AmbientColorField.GRID_SIZE; y++) {
            for (int x = 0; x < AmbientColorField.GRID_SIZE; x++) {
                setGridColor(grid, x, y, r, g, b);
            }
        }
        return grid;
    }

    private static void setGridColor(float[] grid, int x, int y, float r, float g, float b) {
        int offset = (y * AmbientColorField.GRID_SIZE + x) * AmbientColorField.CHANNEL_COUNT;
        grid[offset] = r;
        grid[offset + 1] = g;
        grid[offset + 2] = b;
    }
}
