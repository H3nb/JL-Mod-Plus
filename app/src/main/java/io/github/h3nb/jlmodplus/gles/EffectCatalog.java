// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import io.github.h3nb.jlmodplus.R;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** APK-owned effect definitions. Neither profile state nor GL resources live here. */
public final class EffectCatalog {
    public enum Size { INPUT, VIEWPORT, HALF_VIEWPORT }

    public static final class Parameter {
        public final String id;
        public final int label;
        public final float initial, minimum, maximum, step;

        private Parameter(String id, int label, float initial, float minimum, float maximum, float step) {
            this.id = id;
            this.label = label;
            this.initial = initial;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
        }
    }

    public static final class Pass {
        public final String fragment;
        public final Size size;
        public final boolean sampleNearest;
        public final boolean composite;

        Pass(String fragment, Size size, boolean sampleNearest, boolean composite) {
            this.fragment = fragment;
            this.size = size;
            this.sampleNearest = sampleNearest;
            this.composite = composite;
        }
    }

    public static final class Effect {
        public final String id;
        public final int title, description;
        public final List<Pass> passes;
        public final List<Parameter> parameters;

        private Effect(String id, int title, int description, Pass[] passes, Parameter[] parameters) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.passes = Collections.unmodifiableList(Arrays.asList(passes));
            this.parameters = Collections.unmodifiableList(Arrays.asList(parameters));
        }
    }

    private static Parameter param(String id, int label, float def, float min, float max, float step) {
        return new Parameter(id, label, def, min, max, step);
    }

    private static Pass pass(String name, Size size) {
        return new Pass(name, size, false, false);
    }

    private static Pass nearest(String name) {
        return new Pass(name, Size.VIEWPORT, true, false);
    }

    private static final List<Effect> ALL = Collections.unmodifiableList(Arrays.asList(
        new Effect("color", R.string.effect_color, R.string.effect_color_desc,
            new Pass[]{pass("color.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uBrightness", R.string.effect_brightness, 0f, -0.5f, 0.5f, 0.01f),
                param("uContrast", R.string.effect_contrast, 1f, 0.2f, 2f, 0.05f),
                param("uSaturation", R.string.effect_saturation, 1f, 0f, 2f, 0.05f)}),
        new Effect("vignette", R.string.effect_vignette, R.string.effect_vignette_desc,
            new Pass[]{pass("vignette.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uStrength", R.string.effect_strength, 0.35f, 0f, 1f, 0.05f)}),
        new Effect("sharpen", R.string.effect_sharpen, R.string.effect_sharpen_desc,
            new Pass[]{pass("sharpen.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uSharpness", R.string.effect_strength, 0.3f, 0f, 1f, 0.05f)}),
        new Effect("cartoon", R.string.effect_cartoon, R.string.effect_cartoon_desc,
            new Pass[]{pass("cartoon.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uLevels", R.string.effect_color_levels, 5f, 2f, 12f, 1f),
                param("uEdge", R.string.effect_edge, 0.35f, 0f, 1f, 0.05f)}),
        new Effect("bloom", R.string.effect_bloom, R.string.effect_bloom_desc,
            new Pass[]{
                pass("bloom-threshold.fsh", Size.HALF_VIEWPORT),
                pass("bloom-blur-h.fsh", Size.HALF_VIEWPORT),
                pass("bloom-blur-v.fsh", Size.HALF_VIEWPORT),
                new Pass("bloom-composite.fsh", Size.VIEWPORT, false, true)
            }, new Parameter[]{
                param("uThreshold", R.string.effect_threshold, 0.72f, 0.1f, 1f, 0.02f),
                param("uRadius", R.string.effect_radius, 1f, 0.5f, 3f, 0.1f),
                param("uIntensity", R.string.effect_strength, 0.4f, 0f, 2f, 0.05f)}),
        new Effect("crt-easymode", R.string.effect_crt_easy, R.string.effect_crt_easy_desc,
            new Pass[]{nearest("crt-easymode.fsh")}, new Parameter[]{
                param("SHARPNESS_H", R.string.effect_sharp_h, 0.5f, 0f, 1f, 0.05f),
                param("SHARPNESS_V", R.string.effect_sharp_v, 1f, 0f, 1f, 0.05f),
                param("MASK_STRENGTH", R.string.effect_mask_strength, 0.3f, 0f, 1f, 0.01f),
                param("MASK_DOT_WIDTH", R.string.effect_mask_width, 1f, 1f, 100f, 1f),
                param("MASK_DOT_HEIGHT", R.string.effect_mask_height, 1f, 1f, 100f, 1f),
                param("MASK_STAGGER", R.string.effect_mask_stagger, 0f, 0f, 100f, 1f),
                param("MASK_SIZE", R.string.effect_mask_size, 1f, 1f, 100f, 1f),
                param("SCANLINE_STRENGTH", R.string.effect_scan_strength, 1f, 0f, 1f, 0.05f),
                param("SCANLINE_BEAM_WIDTH_MIN", R.string.effect_beam_min, 1.5f, 0.5f, 5f, 0.5f),
                param("SCANLINE_BEAM_WIDTH_MAX", R.string.effect_beam_max, 1.5f, 0.5f, 5f, 0.5f),
                param("SCANLINE_BRIGHT_MIN", R.string.effect_scan_bright_min, 0.35f, 0f, 1f, 0.05f),
                param("SCANLINE_BRIGHT_MAX", R.string.effect_scan_bright_max, 0.65f, 0f, 1f, 0.05f),
                param("SCANLINE_CUTOFF", R.string.effect_scan_cutoff, 400f, 1f, 1000f, 1f),
                param("GAMMA_INPUT", R.string.effect_gamma_in, 2f, 0.1f, 5f, 0.1f),
                param("GAMMA_OUTPUT", R.string.effect_gamma_out, 1.8f, 0.1f, 5f, 0.1f),
                param("BRIGHT_BOOST", R.string.effect_bright_boost, 1.2f, 1f, 2f, 0.01f),
                param("DILATION", R.string.effect_dilation, 1f, 0f, 1f, 1f)})
    ));

    private EffectCatalog() {}

    public static List<Effect> all() { return ALL; }

    public static Effect get(String id) {
        if (id == null) return null;
        for (Effect effect : ALL) if (effect.id.equals(id)) return effect;
        return null;
    }
}
