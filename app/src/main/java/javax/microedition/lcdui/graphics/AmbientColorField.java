/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

/** Full-frame low-resolution color field with one temporal transition per renderer instance. */
public final class AmbientColorField {
    public static final int ANCHOR_COUNT = 8;
    public static final int CHANNEL_COUNT = 3;
    public static final int GRID_SIZE = 24;
    public static final int GRID_COLOR_COUNT = GRID_SIZE * GRID_SIZE;
    public static final int GRID_CHANNEL_COUNT = GRID_COLOR_COUNT * CHANNEL_COUNT;
    private static final int BROAD_GRID_SIZE = 8;
    private static final int BROAD_GRID_CHANNEL_COUNT =
            BROAD_GRID_SIZE * BROAD_GRID_SIZE * CHANNEL_COUNT;
    /** Compensates a little for chroma lost through blur/downsampling around linear luminance. */
    private static final float BROAD_CHROMA_GAIN = 1.08f;
    /** Matches the host presentation cadence so animated midlets feel live. */
    public static final long SAMPLE_INTERVAL_NS = 33_333_333L;
    public static final long MAX_TRANSITION_NS = 1_000_000_000L;
    public static final long TAU_NS = 140_000_000L;
    private static final float INSET = 0.03f;
    private static final float RADIUS_SQUARED = 0.20f * 0.20f;
    /** Legacy anchor-path falloff retained until that compatibility path can be removed safely. */
    private static final float EDGE_FALLOFF = 0.30f;
    /** Rounded only for the emitted light field; the guest LCD itself remains rectangular. */
    private static final float EMITTER_CORNER_SCALE = 0.08f;
    /** Physical distance over which local edge color diffuses into the coarser field. */
    private static final float DIFFUSION_DISTANCE_SCALE = 0.35f;
    /** Samples slightly inside the LCD so a one-pixel border cannot dominate the emitted light. */
    private static final float SOURCE_INSET_NEAR_SCALE = 0.02f;
    private static final float SOURCE_INSET_FAR_SCALE = 0.10f;
    /** Theme is only a terminal fade close to the host boundary. */
    private static final float OUTER_THEME_FADE_SCALE = 0.10f;
    private static final float LINEAR_EPSILON = 1.0f / 4096.0f;
    /** Five-tap blur softens neighboring samples without erasing the higher-resolution field. */
    private static final float[] BLUR_KERNEL = {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};
    private static final float[] ANCHOR_X = {INSET, 0.5f, 1.0f - INSET, 1.0f - INSET,
            1.0f - INSET, 0.5f, INSET, INSET};
    private static final float[] ANCHOR_Y = {INSET, INSET, INSET, 0.5f,
            1.0f - INSET, 1.0f - INSET, 1.0f - INSET, 0.5f};

    private final float[] start = new float[ANCHOR_COUNT * CHANNEL_COUNT];
    private final float[] target = new float[ANCHOR_COUNT * CHANNEL_COUNT];
    private final float[] startBase = new float[CHANNEL_COUNT];
    private final float[] targetBase = new float[CHANNEL_COUNT];
    private final float[] evaluationAnchors = new float[ANCHOR_COUNT * CHANNEL_COUNT];
    private final float[] evaluationBase = new float[CHANNEL_COUNT];
    private final float[] evaluationEdge = new float[CHANNEL_COUNT];
    private final float[] gridStart = new float[GRID_CHANNEL_COUNT];
    private final float[] gridTarget = new float[GRID_CHANNEL_COUNT];
    private final float[] evaluationGrid = new float[GRID_CHANNEL_COUNT];
    private final float[] blurredGridHorizontal = new float[GRID_CHANNEL_COUNT];
    private final float[] blurredGrid = new float[GRID_CHANNEL_COUNT];
    private final float[] evaluationBroadGrid = new float[BROAD_GRID_CHANNEL_COUNT];
    private final float[] evaluationInner = new float[CHANNEL_COUNT];
    private float[] nodeX = new float[0];
    private float[] nodeY = new float[0];
    private float[] weights = new float[0];
    private float[] fade = new float[0];
    private float[] emissionU = new float[0];
    private float[] emissionV = new float[0];
    private float[] diffusion = new float[0];
    private float[] outerAmbient = new float[0];
    private int nodeCount;
    private float gameLeft;
    private float gameTop;
    private float gameRight;
    private float gameBottom;
    private float surfaceAspect = 1.0f;
    private long transitionStartNs;
    private boolean transitioning;
    private boolean gridMode;

    public AmbientColorField() {
        resetToBase(0xFF000000);
    }

    /** Initializes all anchors to the resolved theme color before the first valid sample. */
    public void resetToBase(int baseArgb) {
        float r = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float g = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float b = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        for (int i = 0; i < ANCHOR_COUNT; i++) {
            int offset = i * CHANNEL_COUNT;
            start[offset] = target[offset] = r;
            start[offset + 1] = target[offset + 1] = g;
            start[offset + 2] = target[offset + 2] = b;
        }
        for (int i = 0; i < GRID_COLOR_COUNT; i++) {
            int offset = i * CHANNEL_COUNT;
            gridStart[offset] = gridTarget[offset] = r;
            gridStart[offset + 1] = gridTarget[offset + 1] = g;
            gridStart[offset + 2] = gridTarget[offset + 2] = b;
        }
        startBase[0] = targetBase[0] = r;
        startBase[1] = targetBase[1] = g;
        startBase[2] = targetBase[2] = b;
        transitioning = false;
        transitionStartNs = 0L;
        gridMode = false;
    }

    /** Retargets the complete field without restarting from the old, unevaluated target. */
    public void setTarget(int[] anchorArgb, int baseArgb, long nowNs, boolean instant) {
        if (anchorArgb == null || anchorArgb.length < ANCHOR_COUNT) return;
        evaluate(nowNs, evaluationAnchors, evaluationBase);
        gridMode = false;

        float baseR = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float baseG = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float baseB = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        boolean materiallyDifferent = Math.max(
                Math.max(Math.abs(targetBase[0] - baseR), Math.abs(targetBase[1] - baseG)),
                Math.abs(targetBase[2] - baseB)) > LINEAR_EPSILON;
        for (int i = 0; i < ANCHOR_COUNT && !materiallyDifferent; i++) {
            int offset = i * CHANNEL_COUNT;
            float r = AmbientColorSampler.srgbChannelToLinear((anchorArgb[i] >>> 16) & 0xFF);
            float g = AmbientColorSampler.srgbChannelToLinear((anchorArgb[i] >>> 8) & 0xFF);
            float b = AmbientColorSampler.srgbChannelToLinear(anchorArgb[i] & 0xFF);
            materiallyDifferent = Math.abs(target[offset] - r) > LINEAR_EPSILON
                    || Math.abs(target[offset + 1] - g) > LINEAR_EPSILON
                    || Math.abs(target[offset + 2] - b) > LINEAR_EPSILON;
        }
        if (!materiallyDifferent && !instant) return;
        for (int i = 0; i < ANCHOR_COUNT; i++) {
            int offset = i * CHANNEL_COUNT;
            float r = AmbientColorSampler.srgbChannelToLinear((anchorArgb[i] >>> 16) & 0xFF);
            float g = AmbientColorSampler.srgbChannelToLinear((anchorArgb[i] >>> 8) & 0xFF);
            float b = AmbientColorSampler.srgbChannelToLinear(anchorArgb[i] & 0xFF);
            materiallyDifferent |= Math.abs(target[offset] - r) > LINEAR_EPSILON
                    || Math.abs(target[offset + 1] - g) > LINEAR_EPSILON
                    || Math.abs(target[offset + 2] - b) > LINEAR_EPSILON;
            start[offset] = instant ? r : evaluationAnchors[offset];
            start[offset + 1] = instant ? g : evaluationAnchors[offset + 1];
            start[offset + 2] = instant ? b : evaluationAnchors[offset + 2];
            target[offset] = r;
            target[offset + 1] = g;
            target[offset + 2] = b;
        }
        startBase[0] = instant ? baseR : evaluationBase[0];
        startBase[1] = instant ? baseG : evaluationBase[1];
        startBase[2] = instant ? baseB : evaluationBase[2];
        targetBase[0] = baseR;
        targetBase[1] = baseG;
        targetBase[2] = baseB;
        if (instant) {
            System.arraycopy(target, 0, start, 0, target.length);
            System.arraycopy(targetBase, 0, startBase, 0, startBase.length);
            transitioning = false;
            transitionStartNs = 0L;
        } else {
            transitionStartNs = nowNs;
            transitioning = true;
        }
    }

    /** Retargets a linear-RGB frame field while preserving temporal continuity. */
    public void setTargetGrid(float[] gridLinear, int baseArgb, long nowNs, boolean instant) {
        if (gridLinear == null || gridLinear.length < GRID_CHANNEL_COUNT) return;
        evaluateGrid(nowNs, evaluationGrid, evaluationBase);
        // Blur only when a new sample arrives. Blur is linear, so interpolating these filtered
        // endpoints is equivalent to filtering every temporally interpolated frame.
        blurGrid(gridLinear, blurredGridHorizontal, blurredGrid);

        float baseR = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float baseG = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float baseB = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        boolean materiallyDifferent = false;
        for (int i = 0; i < GRID_CHANNEL_COUNT && !materiallyDifferent; i++) {
            materiallyDifferent = Math.abs(gridTarget[i] - clamp01(blurredGrid[i]))
                    > LINEAR_EPSILON;
        }
        if (!materiallyDifferent && !instant && gridMode) return;

        for (int i = 0; i < GRID_CHANNEL_COUNT; i++) {
            float value = clamp01(blurredGrid[i]);
            gridStart[i] = instant ? value : evaluationGrid[i];
            gridTarget[i] = value;
        }
        startBase[0] = instant ? baseR : evaluationBase[0];
        startBase[1] = instant ? baseG : evaluationBase[1];
        startBase[2] = instant ? baseB : evaluationBase[2];
        targetBase[0] = baseR;
        targetBase[1] = baseG;
        targetBase[2] = baseB;
        gridMode = true;
        if (instant || !materiallyDifferent) {
            System.arraycopy(gridTarget, 0, gridStart, 0, gridTarget.length);
            System.arraycopy(targetBase, 0, startBase, 0, startBase.length);
            transitioning = false;
            transitionStartNs = 0L;
        } else {
            transitionStartNs = nowNs;
            transitioning = true;
        }
    }

    /** Retargets only the host base while preserving the sampled game colors. */
    public void setBaseColor(int baseArgb, long nowNs, boolean instant) {
        float r = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 16) & 0xFF);
        float g = AmbientColorSampler.srgbChannelToLinear((baseArgb >>> 8) & 0xFF);
        float b = AmbientColorSampler.srgbChannelToLinear(baseArgb & 0xFF);
        if (gridMode) {
            // Once a guest-derived field is active, theme changes must not restart or reshape it.
            // The base remains available for pre-sample/transparent fallback state only.
            startBase[0] = targetBase[0] = r;
            startBase[1] = targetBase[1] = g;
            startBase[2] = targetBase[2] = b;
            return;
        }
        evaluate(nowNs, evaluationAnchors, evaluationBase);
        boolean different = Math.abs(targetBase[0] - r) > LINEAR_EPSILON
                || Math.abs(targetBase[1] - g) > LINEAR_EPSILON
                || Math.abs(targetBase[2] - b) > LINEAR_EPSILON;
        if (!different && !instant) return;
        startBase[0] = instant ? r : evaluationBase[0];
        startBase[1] = instant ? g : evaluationBase[1];
        startBase[2] = instant ? b : evaluationBase[2];
        targetBase[0] = r;
        targetBase[1] = g;
        targetBase[2] = b;
        if (instant) {
            System.arraycopy(targetBase, 0, startBase, 0, CHANNEL_COUNT);
            transitioning = false;
            transitionStartNs = 0L;
        } else {
            transitionStartNs = nowNs;
            transitioning = true;
        }
    }

    /** Builds inverse-distance weights for the supplied normalized node coordinates. */
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
            nodeCount = 0;
            nodeX = new float[0];
            nodeY = new float[0];
            weights = new float[0];
            fade = new float[0];
            emissionU = new float[0];
            emissionV = new float[0];
            diffusion = new float[0];
            outerAmbient = new float[0];
            return;
        }
        nodeCount = Math.min(requestedCount, Math.min(normalizedX.length, normalizedY.length));
        nodeX = new float[nodeCount];
        nodeY = new float[nodeCount];
        weights = new float[nodeCount * ANCHOR_COUNT];
        fade = new float[nodeCount];
        emissionU = new float[nodeCount];
        emissionV = new float[nodeCount];
        diffusion = new float[nodeCount];
        outerAmbient = new float[nodeCount];
        float safeAspect = aspectRatio > 0.0f && Float.isFinite(aspectRatio) ? aspectRatio : 1.0f;
        surfaceAspect = safeAspect;
        float left = clamp01(Math.min(gameLeft, gameRight));
        float top = clamp01(Math.min(gameTop, gameBottom));
        float right = clamp01(Math.max(gameLeft, gameRight));
        float bottom = clamp01(Math.max(gameTop, gameBottom));
        this.gameLeft = left;
        this.gameTop = top;
        this.gameRight = right;
        this.gameBottom = bottom;
        float physicalLeft = left * safeAspect;
        float physicalRight = right * safeAspect;
        float physicalGameWidth = Math.max(physicalRight - physicalLeft, 1.0e-6f);
        float physicalGameHeight = Math.max(bottom - top, 1.0e-6f);
        float shortGameSide = Math.min(physicalGameWidth, physicalGameHeight);
        float cornerRadius = Math.min(shortGameSide * EMITTER_CORNER_SCALE,
                0.5f * shortGameSide);
        float diffusionDistance = Math.max(
                shortGameSide * DIFFUSION_DISTANCE_SCALE, 1.0e-6f);
        float hostShortSide = Math.max(Math.min(safeAspect, 1.0f), 1.0e-6f);
        float outerFadeWidth = Math.max(
                hostShortSide * OUTER_THEME_FADE_SCALE, 1.0e-6f);
        for (int n = 0; n < nodeCount; n++) {
            float x = clamp01(normalizedX[n]);
            float y = clamp01(normalizedY[n]);
            nodeX[n] = x;
            nodeY[n] = y;
            float sum = 0.0f;
            for (int j = 0; j < ANCHOR_COUNT; j++) {
                float dx = (x - ANCHOR_X[j]) * safeAspect;
                float dy = y - ANCHOR_Y[j];
                float denominator = dx * dx + dy * dy + RADIUS_SQUARED;
                float value = 1.0f / (denominator * denominator);
                weights[n * ANCHOR_COUNT + j] = value;
                sum += value;
            }
            float inverseSum = sum > 0.0f && Float.isFinite(sum) ? 1.0f / sum : 1.0f / ANCHOR_COUNT;
            for (int j = 0; j < ANCHOR_COUNT; j++) {
                weights[n * ANCHOR_COUNT + j] *= inverseSum;
            }
            float outsideX = x < this.gameLeft ? this.gameLeft - x
                    : x > this.gameRight ? x - this.gameRight : 0.0f;
            float outsideY = y < this.gameTop ? this.gameTop - y
                    : y > this.gameBottom ? y - this.gameBottom : 0.0f;
            float gapX = x < this.gameLeft ? this.gameLeft
                    : x > this.gameRight ? 1.0f - this.gameRight : 1.0f;
            float gapY = y < this.gameTop ? this.gameTop
                    : y > this.gameBottom ? 1.0f - this.gameBottom : 1.0f;
            float normalizedOutsideX = outsideX / Math.max(gapX, 1.0e-6f);
            float normalizedOutsideY = outsideY / Math.max(gapY, 1.0e-6f);
            float d = clamp01(Math.max(normalizedOutsideX, normalizedOutsideY));
            fade[n] = smoothStep(d);

            configureEmissionNode(n, x, y, safeAspect, physicalLeft, top, physicalRight, bottom,
                    physicalGameWidth, physicalGameHeight, shortGameSide, cornerRadius,
                    diffusionDistance, outerFadeWidth);
        }
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** Writes the current linear-light field as sRGB values, with no allocation. */
    public boolean renderNodes(long nowNs, float[] outRgb) {
        if (outRgb == null || outRgb.length < nodeCount * CHANNEL_COUNT) return false;
        if (gridMode) return renderGridNodes(nowNs, outRgb);
        evaluate(nowNs, evaluationAnchors, evaluationBase);
        for (int n = 0; n < nodeCount; n++) {
            int output = n * CHANNEL_COUNT;
            int weightOffset = n * ANCHOR_COUNT;
            float edgeBlend = edgeBlend(nodeX[n], nodeY[n]);
            if (edgeBlend > 0.0f) {
                edgeColor(nodeX[n], nodeY[n], evaluationAnchors, evaluationEdge);
            }
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                float value = 0.0f;
                for (int j = 0; j < ANCHOR_COUNT; j++) {
                    value += weights[weightOffset + j] * evaluationAnchors[j * CHANNEL_COUNT + channel];
                }
                if (edgeBlend > 0.0f) {
                    value += (evaluationEdge[channel] - value) * edgeBlend;
                }
                float darkened = value + (evaluationBase[channel] - value) * (0.25f * fade[n]);
                outRgb[output + channel] =
                        AmbientColorSampler.linearChannelToSrgb(clamp01(darkened));
            }
        }
        return transitioning;
    }

    /** Same field operation as renderNodes, quantized to opaque ARGB for the Canvas bitmap. */
    public boolean renderNodesArgb(long nowNs, int[] outArgb) {
        if (outArgb == null || outArgb.length < nodeCount) return false;
        if (gridMode) return renderGridNodesArgb(nowNs, outArgb);
        evaluate(nowNs, evaluationAnchors, evaluationBase);
        for (int n = 0; n < nodeCount; n++) {
            int weightOffset = n * ANCHOR_COUNT;
            float edgeBlend = edgeBlend(nodeX[n], nodeY[n]);
            float r = 0.0f, g = 0.0f, b = 0.0f;
            for (int j = 0; j < ANCHOR_COUNT; j++) {
                float weight = weights[weightOffset + j];
                r += weight * evaluationAnchors[j * CHANNEL_COUNT];
                g += weight * evaluationAnchors[j * CHANNEL_COUNT + 1];
                b += weight * evaluationAnchors[j * CHANNEL_COUNT + 2];
            }
            if (edgeBlend > 0.0f) {
                edgeColor(nodeX[n], nodeY[n], evaluationAnchors, evaluationEdge);
                r += (evaluationEdge[0] - r) * edgeBlend;
                g += (evaluationEdge[1] - g) * edgeBlend;
                b += (evaluationEdge[2] - b) * edgeBlend;
            }
            float outside = 0.25f * fade[n];
            r = clamp01(r + (evaluationBase[0] - r) * outside);
            g = clamp01(g + (evaluationBase[1] - g) * outside);
            b = clamp01(b + (evaluationBase[2] - b) * outside);
            outArgb[n] = 0xFF000000
                    | (AmbientColorSampler.linearChannelToByte(r) << 16)
                    | (AmbientColorSampler.linearChannelToByte(g) << 8)
                    | AmbientColorSampler.linearChannelToByte(b);
        }
        return transitioning;
    }

    private boolean renderGridNodes(long nowNs, float[] outRgb) {
        boolean active = evaluateGrid(nowNs, evaluationGrid, evaluationBase);
        buildBroadGrid(evaluationGrid, evaluationBroadGrid);
        for (int n = 0; n < nodeCount; n++) {
            sampleGridAt(emissionU[n], emissionV[n], evaluationGrid, GRID_SIZE, evaluationInner);
            sampleGridAt(emissionU[n], emissionV[n],
                    evaluationBroadGrid, BROAD_GRID_SIZE, evaluationEdge);
            int output = n * CHANNEL_COUNT;
            float spread = diffusion[n];
            float ambientMix = outerAmbient[n];
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                float emitted = evaluationInner[channel]
                        + (evaluationEdge[channel] - evaluationInner[channel]) * spread;
                float linear = evaluationBase[channel]
                        + (emitted - evaluationBase[channel]) * ambientMix;
                outRgb[output + channel] =
                        AmbientColorSampler.linearChannelToSrgb(clamp01(linear));
            }
        }
        return active;
    }

    private boolean renderGridNodesArgb(long nowNs, int[] outArgb) {
        boolean active = evaluateGrid(nowNs, evaluationGrid, evaluationBase);
        buildBroadGrid(evaluationGrid, evaluationBroadGrid);
        for (int n = 0; n < nodeCount; n++) {
            sampleGridAt(emissionU[n], emissionV[n], evaluationGrid, GRID_SIZE, evaluationInner);
            sampleGridAt(emissionU[n], emissionV[n],
                    evaluationBroadGrid, BROAD_GRID_SIZE, evaluationEdge);
            float spread = diffusion[n];
            float ambientMix = outerAmbient[n];
            float r = evaluationInner[0] + (evaluationEdge[0] - evaluationInner[0]) * spread;
            float g = evaluationInner[1] + (evaluationEdge[1] - evaluationInner[1]) * spread;
            float b = evaluationInner[2] + (evaluationEdge[2] - evaluationInner[2]) * spread;
            r = clamp01(evaluationBase[0] + (r - evaluationBase[0]) * ambientMix);
            g = clamp01(evaluationBase[1] + (g - evaluationBase[1]) * ambientMix);
            b = clamp01(evaluationBase[2] + (b - evaluationBase[2]) * ambientMix);
            outArgb[n] = 0xFF000000
                    | (AmbientColorSampler.linearChannelToByte(r) << 16)
                    | (AmbientColorSampler.linearChannelToByte(g) << 8)
                    | AmbientColorSampler.linearChannelToByte(b);
        }
        return active;
    }

    /** Applies a small separable Gaussian-like blur to the low-resolution frame palette. */
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

    public boolean evaluate(long nowNs, float[] outAnchors, float[] outBase) {
        if (outAnchors == null || outAnchors.length < target.length
                || outBase == null || outBase.length < CHANNEL_COUNT) return false;
        if (!transitioning) {
            System.arraycopy(target, 0, outAnchors, 0, target.length);
            System.arraycopy(targetBase, 0, outBase, 0, CHANNEL_COUNT);
            return false;
        }
        if (nowNs < transitionStartNs) {
            System.arraycopy(start, 0, outAnchors, 0, start.length);
            System.arraycopy(startBase, 0, outBase, 0, CHANNEL_COUNT);
            return true;
        }
        long elapsed = nowNs - transitionStartNs;
        if (elapsed >= MAX_TRANSITION_NS) {
            System.arraycopy(target, 0, outAnchors, 0, target.length);
            System.arraycopy(targetBase, 0, outBase, 0, CHANNEL_COUNT);
            transitioning = false;
            return false;
        }
        double blend = 1.0 - Math.exp(-(double) elapsed / TAU_NS);
        for (int i = 0; i < target.length; i++) {
            outAnchors[i] = (float) (start[i] + (target[i] - start[i]) * blend);
        }
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            outBase[i] = (float) (startBase[i] + (targetBase[i] - startBase[i]) * blend);
        }
        return true;
    }

    /** Evaluates the current filtered linear-light field on the shared monotonic clock. */
    public boolean evaluateGrid(long nowNs, float[] outGrid, float[] outBase) {
        if (outGrid == null || outGrid.length < gridTarget.length
                || outBase == null || outBase.length < CHANNEL_COUNT) return false;
        if (!transitioning || !gridMode) {
            System.arraycopy(gridTarget, 0, outGrid, 0, gridTarget.length);
            System.arraycopy(targetBase, 0, outBase, 0, CHANNEL_COUNT);
            return false;
        }
        if (nowNs < transitionStartNs) {
            System.arraycopy(gridStart, 0, outGrid, 0, gridStart.length);
            System.arraycopy(startBase, 0, outBase, 0, CHANNEL_COUNT);
            return true;
        }
        long elapsed = nowNs - transitionStartNs;
        if (elapsed >= MAX_TRANSITION_NS) {
            System.arraycopy(gridTarget, 0, outGrid, 0, gridTarget.length);
            System.arraycopy(targetBase, 0, outBase, 0, CHANNEL_COUNT);
            transitioning = false;
            return false;
        }
        double blend = 1.0 - Math.exp(-(double) elapsed / TAU_NS);
        for (int i = 0; i < gridTarget.length; i++) {
            outGrid[i] = (float) (gridStart[i] + (gridTarget[i] - gridStart[i]) * blend);
        }
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            outBase[i] = (float) (startBase[i] + (targetBase[i] - startBase[i]) * blend);
        }
        return true;
    }

    /**
     * Maps one host node to the nearest point on a rounded LCD emitter. All distances are measured
     * in physical host units so the light shape stays symmetric regardless of LCD placement.
     */
    private void configureEmissionNode(int index, float x, float y, float aspect,
            float left, float top, float right, float bottom,
            float gameWidth, float gameHeight, float shortGameSide, float cornerRadius,
            float diffusionDistance, float outerFadeWidth) {
        float px = x * aspect;
        float py = y;
        boolean insideRect = px >= left && px <= right && py >= top && py <= bottom;
        float edgeDistance = Math.min(Math.min(px, aspect - px), Math.min(py, 1.0f - py));
        outerAmbient[index] = smoothStep(clamp01(edgeDistance / outerFadeWidth));

        if (insideRect) {
            emissionU[index] = clamp01((px - left) / gameWidth);
            emissionV[index] = clamp01((py - top) / gameHeight);
            diffusion[index] = 0.0f;
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
        float spread = smoothStep(clamp01(distance / diffusionDistance));
        diffusion[index] = spread;

        float insetDistance = shortGameSide
                * (SOURCE_INSET_NEAR_SCALE + SOURCE_INSET_FAR_SCALE * spread);
        float sampleX = boundaryX - nx * insetDistance;
        float sampleY = boundaryY - ny * insetDistance;
        emissionU[index] = clamp01((sampleX - left) / gameWidth);
        emissionV[index] = clamp01((sampleY - top) / gameHeight);
    }

    /** Builds the broad full-frame backdrop while preserving a little post-blur chroma. */
    private static void buildBroadGrid(float[] source, float[] output) {
        int block = GRID_SIZE / BROAD_GRID_SIZE;
        float inverseCount = 1.0f / (block * block);
        for (int broadY = 0; broadY < BROAD_GRID_SIZE; broadY++) {
            for (int broadX = 0; broadX < BROAD_GRID_SIZE; broadX++) {
                float r = 0.0f;
                float g = 0.0f;
                float b = 0.0f;
                int sourceY = broadY * block;
                int sourceX = broadX * block;
                for (int y = 0; y < block; y++) {
                    for (int x = 0; x < block; x++) {
                        int sourceOffset =
                                ((sourceY + y) * GRID_SIZE + sourceX + x) * CHANNEL_COUNT;
                        r += source[sourceOffset];
                        g += source[sourceOffset + 1];
                        b += source[sourceOffset + 2];
                    }
                }
                r *= inverseCount;
                g *= inverseCount;
                b *= inverseCount;
                float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                int outputOffset = (broadY * BROAD_GRID_SIZE + broadX) * CHANNEL_COUNT;
                output[outputOffset] = clamp01(
                        luminance + (r - luminance) * BROAD_CHROMA_GAIN);
                output[outputOffset + 1] = clamp01(
                        luminance + (g - luminance) * BROAD_CHROMA_GAIN);
                output[outputOffset + 2] = clamp01(
                        luminance + (b - luminance) * BROAD_CHROMA_GAIN);
            }
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

    /** Returns the strength of the local edge bleed for a surface node. */
    private float edgeBlend(float x, float y) {
        boolean outside = x < gameLeft || x > gameRight || y < gameTop || y > gameBottom;
        boolean boundary = Math.abs(x - gameLeft) < 1.0e-5f
                || Math.abs(x - gameRight) < 1.0e-5f
                || Math.abs(y - gameTop) < 1.0e-5f
                || Math.abs(y - gameBottom) < 1.0e-5f;
        if (!outside && !boundary || gameRight <= gameLeft || gameBottom <= gameTop) return 0.0f;
        float edgeX = Math.max(gameLeft, Math.min(gameRight, x));
        float edgeY = Math.max(gameTop, Math.min(gameBottom, y));
        float dx = (x - edgeX) * surfaceAspect;
        float dy = y - edgeY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float t = clamp01(distance / EDGE_FALLOFF);
        return 1.0f - t * t * (3.0f - 2.0f * t);
    }

    /** Interpolates the anchor colors along the nearest point on the game rectangle edge. */
    private void edgeColor(float x, float y, float[] anchors, float[] out) {
        float edgeX = Math.max(gameLeft, Math.min(gameRight, x));
        float edgeY = Math.max(gameTop, Math.min(gameBottom, y));
        if (y <= gameTop + 1.0e-5f) {
            sampleThreeAnchors(anchors, 0, 1, 2,
                    (edgeX - gameLeft) / Math.max(gameRight - gameLeft, 1.0e-6f), out);
        } else if (x >= gameRight - 1.0e-5f) {
            sampleThreeAnchors(anchors, 2, 3, 4,
                    (edgeY - gameTop) / Math.max(gameBottom - gameTop, 1.0e-6f), out);
        } else if (y >= gameBottom - 1.0e-5f) {
            sampleThreeAnchors(anchors, 4, 5, 6,
                    (gameRight - edgeX) / Math.max(gameRight - gameLeft, 1.0e-6f), out);
        } else {
            sampleThreeAnchors(anchors, 6, 7, 0,
                    (gameBottom - edgeY) / Math.max(gameBottom - gameTop, 1.0e-6f), out);
        }
    }

    private static void sampleThreeAnchors(float[] anchors, int first, int middle, int last,
            float position, float[] out) {
        float t = clamp01(position) * 2.0f;
        int firstOffset = first * CHANNEL_COUNT;
        int middleOffset = middle * CHANNEL_COUNT;
        int lastOffset = last * CHANNEL_COUNT;
        if (t <= 1.0f) {
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                out[channel] = anchors[firstOffset + channel]
                        + (anchors[middleOffset + channel] - anchors[firstOffset + channel]) * t;
            }
        } else {
            t -= 1.0f;
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                out[channel] = anchors[middleOffset + channel]
                        + (anchors[lastOffset + channel] - anchors[middleOffset + channel]) * t;
            }
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
