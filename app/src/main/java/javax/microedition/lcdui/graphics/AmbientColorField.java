/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

/** Low-resolution linear-light field with one temporal transition per renderer instance. */
public final class AmbientColorField {
    public static final int CHANNEL_COUNT = 3;
    public static final int GRID_SIZE = 32;
    public static final int GRID_COLOR_COUNT = GRID_SIZE * GRID_SIZE;
    public static final int GRID_CHANNEL_COUNT = GRID_COLOR_COUNT * CHANNEL_COUNT;
    private static final int MEDIUM_GRID_SIZE = 16;
    private static final int MEDIUM_GRID_CHANNEL_COUNT =
            MEDIUM_GRID_SIZE * MEDIUM_GRID_SIZE * CHANNEL_COUNT;
    private static final int WIDE_GRID_SIZE = 8;
    private static final int WIDE_GRID_CHANNEL_COUNT =
            WIDE_GRID_SIZE * WIDE_GRID_SIZE * CHANNEL_COUNT;
    /** Matches the host presentation cadence so animated midlets feel live. */
    public static final long SAMPLE_INTERVAL_NS = 33_333_333L;
    public static final long MAX_TRANSITION_NS = 1_000_000_000L;
    public static final long TAU_NS = 140_000_000L;
    /** Rounded only for the emitted blur field; the guest LCD itself remains rectangular. */
    private static final float EMITTER_CORNER_SCALE = 0.10f;
    /** Even at the LCD edge, retain some blur so text and sharp shapes do not escape intact. */
    private static final float MIN_MEDIUM_MIX = 0.32f;
    /** Physical distances that widen the edge blur from fine -> medium -> wide. */
    private static final float MEDIUM_DISTANCE_SCALE = 0.20f;
    private static final float WIDE_START_DISTANCE_SCALE = 0.08f;
    private static final float WIDE_DISTANCE_SCALE = 0.38f;
    /** Lateral spread makes neighboring edge colors overlap like light in frosted glass. */
    private static final float TANGENT_SPREAD_NEAR_SCALE = 0.015f;
    private static final float TANGENT_SPREAD_FAR_SCALE = 0.18f;
    private static final float TANGENT_DISTANCE_SCALE = 0.45f;
    /** Fixed inward sample keeps the extension anchored to the same LCD-edge neighborhood. */
    private static final float SOURCE_INSET_SCALE = 0.04f;
    private static final float LINEAR_EPSILON = 1.0f / 4096.0f;
    /** Five-tap blur softens neighboring samples without erasing the higher-resolution field. */
    private static final float[] BLUR_KERNEL = {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};

    private final float[] fineStart = new float[GRID_CHANNEL_COUNT];
    private final float[] fineTarget = new float[GRID_CHANNEL_COUNT];
    private final float[] mediumStart = new float[MEDIUM_GRID_CHANNEL_COUNT];
    private final float[] mediumTarget = new float[MEDIUM_GRID_CHANNEL_COUNT];
    private final float[] wideStart = new float[WIDE_GRID_CHANNEL_COUNT];
    private final float[] wideTarget = new float[WIDE_GRID_CHANNEL_COUNT];
    private final float[] evaluationFine = new float[GRID_CHANNEL_COUNT];
    private final float[] evaluationMedium = new float[MEDIUM_GRID_CHANNEL_COUNT];
    private final float[] evaluationWide = new float[WIDE_GRID_CHANNEL_COUNT];
    private final float[] filteredHorizontal = new float[GRID_CHANNEL_COUNT];
    private final float[] filteredFine = new float[GRID_CHANNEL_COUNT];
    private final float[] evaluationInner = new float[CHANNEL_COUNT];
    private final float[] evaluationMediumColor = new float[CHANNEL_COUNT];
    private final float[] evaluationWideColor = new float[CHANNEL_COUNT];
    private final float[] evaluationTap = new float[CHANNEL_COUNT];
    private float[] emissionU = new float[0];
    private float[] emissionV = new float[0];
    private float[] mediumMix = new float[0];
    private float[] wideMix = new float[0];
    private float[] tangentU = new float[0];
    private float[] tangentV = new float[0];
    private int nodeCount;
    private long transitionStartNs;
    private boolean transitioning;
    private boolean guestFieldActive;

    public AmbientColorField() {
        resetToBase(0xFF000000);
    }

    /** Initializes the pre-sample field from the resolved host theme. */
    public void resetToBase(int baseArgb) {
        float r = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float g = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float b = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        fillGrid(fineStart, r, g, b);
        fillGrid(fineTarget, r, g, b);
        buildDiffusionFields(fineStart, mediumStart, wideStart);
        buildDiffusionFields(fineTarget, mediumTarget, wideTarget);
        transitioning = false;
        transitionStartNs = 0L;
        guestFieldActive = false;
    }

    /** Retargets the filtered guest field while preserving temporal continuity. */
    public void setTarget(float[] gridLinear, long nowNs, boolean instant) {
        if (gridLinear == null || gridLinear.length < GRID_CHANNEL_COUNT) return;
        evaluate(nowNs, evaluationFine);
        // Blur only when a new sample arrives. Blur is linear, so interpolating filtered
        // endpoints is equivalent to filtering every temporally interpolated frame.
        blurGrid(gridLinear, filteredHorizontal, filteredFine);
        retarget(evaluationFine, filteredFine, nowNs, instant, true);
    }

    /** Retargets only the pre-sample solid field; active guest output stays theme-independent. */
    public void setBaseColor(int baseArgb, long nowNs, boolean instant) {
        if (guestFieldActive) return;
        float r = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float g = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float b = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        evaluate(nowNs, evaluationFine);
        fillGrid(filteredFine, r, g, b);
        retarget(evaluationFine, filteredFine, nowNs, instant, false);
    }

    private void retarget(float[] currentFine, float[] newFine, long nowNs,
            boolean instant, boolean guestSample) {
        boolean materiallyDifferent = false;
        for (int i = 0; i < GRID_CHANNEL_COUNT && !materiallyDifferent; i++) {
            materiallyDifferent = Math.abs(fineTarget[i] - clamp01(newFine[i]))
                    > LINEAR_EPSILON;
        }
        if (!materiallyDifferent && !instant) {
            // Guest ownership is independent from temporal color state. If the first guest
            // target already equals the existing target, keep any in-flight transition intact.
            if (guestSample) guestFieldActive = true;
            return;
        }

        if (instant) {
            copyClamped(newFine, fineStart);
            System.arraycopy(fineStart, 0, fineTarget, 0, fineStart.length);
            buildDiffusionFields(fineStart, mediumStart, wideStart);
            System.arraycopy(mediumStart, 0, mediumTarget, 0, mediumStart.length);
            System.arraycopy(wideStart, 0, wideTarget, 0, wideStart.length);
            transitioning = false;
            transitionStartNs = 0L;
        } else {
            System.arraycopy(currentFine, 0, fineStart, 0, fineStart.length);
            copyClamped(newFine, fineTarget);
            // Downsampling is linear. Deriving both pyramid endpoints here makes rendering a
            // direct temporal interpolation and preserves exact continuity on mid-transition
            // retargets without rebuilding the pyramid on every host redraw.
            buildDiffusionFields(fineStart, mediumStart, wideStart);
            buildDiffusionFields(fineTarget, mediumTarget, wideTarget);
            transitionStartNs = nowNs;
            transitioning = true;
        }
        if (guestSample) guestFieldActive = true;
    }

    /** Builds projection and diffusion geometry for the supplied normalized host nodes. */
    public void configureNodes(float[] normalizedX, float[] normalizedY,
            float gameLeft, float gameTop, float gameRight, float gameBottom,
            float aspectRatio) {
        configureNodes(normalizedX, normalizedY, normalizedX == null ? 0 : normalizedX.length,
                gameLeft, gameTop, gameRight, gameBottom, aspectRatio);
    }

    /** Same as configureNodes, with a bounded prefix for a shared reusable vertex array. */
    public void configureNodes(float[] normalizedX, float[] normalizedY, int requestedCount,
            float gameLeft, float gameTop, float gameRight, float gameBottom,
            float aspectRatio) {
        if (normalizedX == null || normalizedY == null) {
            clearNodes();
            return;
        }
        nodeCount = Math.max(0,
                Math.min(requestedCount, Math.min(normalizedX.length, normalizedY.length)));
        emissionU = new float[nodeCount];
        emissionV = new float[nodeCount];
        mediumMix = new float[nodeCount];
        wideMix = new float[nodeCount];
        tangentU = new float[nodeCount];
        tangentV = new float[nodeCount];

        float safeAspect = aspectRatio > 0.0f && Float.isFinite(aspectRatio) ? aspectRatio : 1.0f;
        float left = clamp01(Math.min(gameLeft, gameRight));
        float top = clamp01(Math.min(gameTop, gameBottom));
        float right = clamp01(Math.max(gameLeft, gameRight));
        float bottom = clamp01(Math.max(gameTop, gameBottom));
        float physicalLeft = left * safeAspect;
        float physicalRight = right * safeAspect;
        float physicalGameWidth = Math.max(physicalRight - physicalLeft, 1.0e-6f);
        float physicalGameHeight = Math.max(bottom - top, 1.0e-6f);
        float shortGameSide = Math.min(physicalGameWidth, physicalGameHeight);
        float cornerRadius = Math.min(shortGameSide * EMITTER_CORNER_SCALE,
                0.5f * shortGameSide);
        float mediumDistance = Math.max(
                shortGameSide * MEDIUM_DISTANCE_SCALE, 1.0e-6f);
        float wideStartDistance = shortGameSide * WIDE_START_DISTANCE_SCALE;
        float wideDistance = Math.max(
                shortGameSide * WIDE_DISTANCE_SCALE, 1.0e-6f);
        float tangentDistance = Math.max(
                shortGameSide * TANGENT_DISTANCE_SCALE, 1.0e-6f);

        for (int n = 0; n < nodeCount; n++) {
            float x = clamp01(normalizedX[n]);
            float y = clamp01(normalizedY[n]);
            configureEmissionNode(n, x, y, safeAspect, physicalLeft, top, physicalRight, bottom,
                    physicalGameWidth, physicalGameHeight, shortGameSide, cornerRadius,
                    mediumDistance, wideStartDistance, wideDistance, tangentDistance);
        }
    }

    private void clearNodes() {
        nodeCount = 0;
        emissionU = new float[0];
        emissionV = new float[0];
        mediumMix = new float[0];
        wideMix = new float[0];
        tangentU = new float[0];
        tangentV = new float[0];
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** Writes the current linear-light field as sRGB values, with no allocation. */
    public boolean renderNodes(long nowNs, float[] outRgb) {
        if (outRgb == null || outRgb.length < nodeCount * CHANNEL_COUNT) return false;
        boolean active = evaluatePyramid(nowNs);
        for (int n = 0; n < nodeCount; n++) {
            sampleNode(n);
            int output = n * CHANNEL_COUNT;
            outRgb[output] = AmbientColorSampler.linearChannelToSrgb(evaluationInner[0]);
            outRgb[output + 1] = AmbientColorSampler.linearChannelToSrgb(evaluationInner[1]);
            outRgb[output + 2] = AmbientColorSampler.linearChannelToSrgb(evaluationInner[2]);
        }
        return active;
    }

    /** Same field operation as renderNodes, quantized to opaque ARGB for the Canvas bitmap. */
    public boolean renderNodesArgb(long nowNs, int[] outArgb) {
        if (outArgb == null || outArgb.length < nodeCount) return false;
        boolean active = evaluatePyramid(nowNs);
        for (int n = 0; n < nodeCount; n++) {
            sampleNode(n);
            outArgb[n] = 0xFF000000
                    | (AmbientColorSampler.linearChannelToByte(evaluationInner[0]) << 16)
                    | (AmbientColorSampler.linearChannelToByte(evaluationInner[1]) << 8)
                    | AmbientColorSampler.linearChannelToByte(evaluationInner[2]);
        }
        return active;
    }

    private void sampleNode(int index) {
        sampleTangentially(emissionU[index], emissionV[index], tangentU[index], tangentV[index],
                evaluationFine, GRID_SIZE, evaluationInner);
        sampleTangentially(emissionU[index], emissionV[index], tangentU[index], tangentV[index],
                evaluationMedium, MEDIUM_GRID_SIZE, evaluationMediumColor);
        sampleTangentially(emissionU[index], emissionV[index], tangentU[index], tangentV[index],
                evaluationWide, WIDE_GRID_SIZE, evaluationWideColor);
        float medium = mediumMix[index];
        float wide = wideMix[index];
        for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
            float blurred = evaluationInner[channel]
                    + (evaluationMediumColor[channel] - evaluationInner[channel]) * medium;
            blurred += (evaluationWideColor[channel] - blurred) * wide;
            evaluationInner[channel] = clamp01(blurred);
        }
    }

    /** Applies the fixed separable prefilter to a newly sampled low-resolution source field. */
    private static void blurGrid(float[] source, float[] horizontal, float[] output) {
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                int outputOffset = (y * GRID_SIZE + x) * CHANNEL_COUNT;
                for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                    float value = 0.0f;
                    for (int tap = -2; tap <= 2; tap++) {
                        int sampleX = Math.max(0, Math.min(GRID_SIZE - 1, x + tap));
                        int sampleOffset = (y * GRID_SIZE + sampleX) * CHANNEL_COUNT;
                        value += clamp01(source[sampleOffset + channel])
                                * BLUR_KERNEL[tap + 2];
                    }
                    horizontal[outputOffset + channel] = value;
                }
            }
        }
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                int outputOffset = (y * GRID_SIZE + x) * CHANNEL_COUNT;
                for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                    float value = 0.0f;
                    for (int tap = -2; tap <= 2; tap++) {
                        int sampleY = Math.max(0, Math.min(GRID_SIZE - 1, y + tap));
                        int sampleOffset = (sampleY * GRID_SIZE + x) * CHANNEL_COUNT;
                        value += horizontal[sampleOffset + channel] * BLUR_KERNEL[tap + 2];
                    }
                    output[outputOffset + channel] = value;
                }
            }
        }
    }

    public boolean isTransitioning() {
        return transitioning;
    }

    /** Evaluates the current fine field on the shared monotonic host clock. */
    public boolean evaluate(long nowNs, float[] outFine) {
        if (outFine == null || outFine.length < GRID_CHANNEL_COUNT) return false;
        if (!transitioning) {
            System.arraycopy(fineTarget, 0, outFine, 0, fineTarget.length);
            return false;
        }
        if (nowNs < transitionStartNs) {
            System.arraycopy(fineStart, 0, outFine, 0, fineStart.length);
            return true;
        }
        long elapsed = nowNs - transitionStartNs;
        if (elapsed >= MAX_TRANSITION_NS) {
            System.arraycopy(fineTarget, 0, outFine, 0, fineTarget.length);
            transitioning = false;
            return false;
        }
        float blend = (float) (1.0 - Math.exp(-(double) elapsed / TAU_NS));
        interpolate(fineStart, fineTarget, blend, outFine);
        return true;
    }

    private boolean evaluatePyramid(long nowNs) {
        if (!transitioning) {
            copyTargetsToEvaluation();
            return false;
        }
        if (nowNs < transitionStartNs) {
            copyStartsToEvaluation();
            return true;
        }
        long elapsed = nowNs - transitionStartNs;
        if (elapsed >= MAX_TRANSITION_NS) {
            copyTargetsToEvaluation();
            transitioning = false;
            return false;
        }
        float blend = (float) (1.0 - Math.exp(-(double) elapsed / TAU_NS));
        interpolate(fineStart, fineTarget, blend, evaluationFine);
        interpolate(mediumStart, mediumTarget, blend, evaluationMedium);
        interpolate(wideStart, wideTarget, blend, evaluationWide);
        return true;
    }

    private void copyStartsToEvaluation() {
        System.arraycopy(fineStart, 0, evaluationFine, 0, fineStart.length);
        System.arraycopy(mediumStart, 0, evaluationMedium, 0, mediumStart.length);
        System.arraycopy(wideStart, 0, evaluationWide, 0, wideStart.length);
    }

    private void copyTargetsToEvaluation() {
        System.arraycopy(fineTarget, 0, evaluationFine, 0, fineTarget.length);
        System.arraycopy(mediumTarget, 0, evaluationMedium, 0, mediumTarget.length);
        System.arraycopy(wideTarget, 0, evaluationWide, 0, wideTarget.length);
    }

    private static void interpolate(float[] start, float[] target, float blend, float[] out) {
        for (int i = 0; i < target.length; i++) {
            out[i] = start[i] + (target[i] - start[i]) * blend;
        }
    }

    /**
     * Maps one host node to the nearest point on a rounded LCD emitter. All distances are measured
     * in physical host units so the light shape stays symmetric regardless of LCD placement.
     */
    private void configureEmissionNode(int index, float x, float y, float aspect,
            float left, float top, float right, float bottom,
            float gameWidth, float gameHeight, float shortGameSide, float cornerRadius,
            float mediumDistance, float wideStartDistance, float wideDistance,
            float tangentDistance) {
        float px = x * aspect;
        float py = y;
        boolean insideRect = px > left + 1.0e-6f && px < right - 1.0e-6f
                && py > top + 1.0e-6f && py < bottom - 1.0e-6f;

        if (insideRect) {
            emissionU[index] = clamp01((px - left) / gameWidth);
            emissionV[index] = clamp01((py - top) / gameHeight);
            mediumMix[index] = MIN_MEDIUM_MIX;
            wideMix[index] = 0.0f;
            tangentU[index] = 0.0f;
            tangentV[index] = 0.0f;
            return;
        }

        float centerX = 0.5f * (left + right);
        float centerY = 0.5f * (top + bottom);
        float innerHalfWidth = Math.max(0.0f, 0.5f * gameWidth - cornerRadius);
        float innerHalfHeight = Math.max(0.0f, 0.5f * gameHeight - cornerRadius);
        float coreX = clamp(px, centerX - innerHalfWidth, centerX + innerHalfWidth);
        float coreY = clamp(py, centerY - innerHalfHeight, centerY + innerHalfHeight);
        float vx = px - coreX;
        float vy = py - coreY;
        float length = (float) Math.sqrt(vx * vx + vy * vy);
        float nx;
        float ny;
        if (length > 1.0e-6f) {
            nx = vx / length;
            ny = vy / length;
        } else {
            float dx = px - centerX;
            float dy = py - centerY;
            if (Math.abs(dx) >= Math.abs(dy)) {
                nx = dx < 0.0f ? -1.0f : 1.0f;
                ny = 0.0f;
            } else {
                nx = 0.0f;
                ny = dy < 0.0f ? -1.0f : 1.0f;
            }
        }

        float boundaryX = coreX + nx * cornerRadius;
        float boundaryY = coreY + ny * cornerRadius;
        float distance = Math.max(0.0f, length - cornerRadius);
        float mediumProgress = smoothStep(clamp01(distance / mediumDistance));
        mediumMix[index] = MIN_MEDIUM_MIX + (1.0f - MIN_MEDIUM_MIX) * mediumProgress;
        wideMix[index] = smoothStep(
                clamp01((distance - wideStartDistance) / wideDistance));

        float insetDistance = shortGameSide * SOURCE_INSET_SCALE;
        float sampleX = boundaryX - nx * insetDistance;
        float sampleY = boundaryY - ny * insetDistance;
        emissionU[index] = clamp01((sampleX - left) / gameWidth);
        emissionV[index] = clamp01((sampleY - top) / gameHeight);

        float tangentProgress = smoothStep(clamp01(distance / tangentDistance));
        float tangentRadius = shortGameSide * (TANGENT_SPREAD_NEAR_SCALE
                + (TANGENT_SPREAD_FAR_SCALE - TANGENT_SPREAD_NEAR_SCALE)
                * tangentProgress);
        tangentU[index] = (-ny * tangentRadius) / gameWidth;
        tangentV[index] = (nx * tangentRadius) / gameHeight;
    }

    /** Builds successively wider diffusion levels while keeping the source position edge-anchored. */
    private static void buildDiffusionFields(float[] fine, float[] medium, float[] wide) {
        downsampleGrid(fine, GRID_SIZE, medium, MEDIUM_GRID_SIZE);
        downsampleGrid(medium, MEDIUM_GRID_SIZE, wide, WIDE_GRID_SIZE);
    }

    private static void downsampleGrid(float[] source, int sourceSize,
            float[] output, int outputSize) {
        int block = sourceSize / outputSize;
        float inverseCount = 1.0f / (block * block);
        for (int outputY = 0; outputY < outputSize; outputY++) {
            for (int outputX = 0; outputX < outputSize; outputX++) {
                int sourceY = outputY * block;
                int sourceX = outputX * block;
                int outputOffset = (outputY * outputSize + outputX) * CHANNEL_COUNT;
                for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                    float sum = 0.0f;
                    for (int y = 0; y < block; y++) {
                        for (int x = 0; x < block; x++) {
                            int sourceOffset =
                                    ((sourceY + y) * sourceSize + sourceX + x) * CHANNEL_COUNT;
                            sum += source[sourceOffset + channel];
                        }
                    }
                    output[outputOffset + channel] = sum * inverseCount;
                }
            }
        }
    }

    /**
     * Samples a Gaussian-like footprint along the local edge tangent. The footprint widens in
     * configureEmissionNode() as the host node moves away from the LCD, so neighboring edge colors
     * overlap instead of forming long color stripes.
     */
    private void sampleTangentially(float u, float v, float du, float dv,
            float[] grid, int gridSize, float[] out) {
        out[0] = 0.0f;
        out[1] = 0.0f;
        out[2] = 0.0f;
        for (int tap = -2; tap <= 2; tap++) {
            float offset = tap * 0.5f;
            sampleGridAt(u + du * offset, v + dv * offset, grid, gridSize, evaluationTap);
            float weight = BLUR_KERNEL[tap + 2];
            out[0] += evaluationTap[0] * weight;
            out[1] += evaluationTap[1] * weight;
            out[2] += evaluationTap[2] * weight;
        }
    }

    private static void sampleGridAt(float u, float v, float[] grid, int gridSize, float[] out) {
        float gridX = clamp01(u) * (gridSize - 1);
        float gridY = clamp01(v) * (gridSize - 1);
        int x0 = (int) gridX;
        int y0 = (int) gridY;
        int x1 = Math.min(gridSize - 1, x0 + 1);
        int y1 = Math.min(gridSize - 1, y0 + 1);
        float xWeight = gridX - x0;
        float yWeight = gridY - y0;
        int topLeft = (y0 * gridSize + x0) * CHANNEL_COUNT;
        int topRight = (y0 * gridSize + x1) * CHANNEL_COUNT;
        int bottomLeft = (y1 * gridSize + x0) * CHANNEL_COUNT;
        int bottomRight = (y1 * gridSize + x1) * CHANNEL_COUNT;
        for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
            float top = grid[topLeft + channel]
                    + (grid[topRight + channel] - grid[topLeft + channel]) * xWeight;
            float bottom = grid[bottomLeft + channel]
                    + (grid[bottomRight + channel] - grid[bottomLeft + channel]) * xWeight;
            out[channel] = top + (bottom - top) * yWeight;
        }
    }

    private static void copyClamped(float[] source, float[] output) {
        for (int i = 0; i < output.length; i++) {
            output[i] = clamp01(source[i]);
        }
    }

    private static void fillGrid(float[] grid, float r, float g, float b) {
        for (int i = 0; i < grid.length; i += CHANNEL_COUNT) {
            grid[i] = r;
            grid[i + 1] = g;
            grid[i + 2] = b;
        }
    }

    private static float smoothStep(float value) {
        float t = clamp01(value);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }
}
