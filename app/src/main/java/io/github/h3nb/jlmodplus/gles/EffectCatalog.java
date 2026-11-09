// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import io.github.h3nb.jlmodplus.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** APK-owned effect definitions. Neither profile state nor GL resources live here. */
public final class EffectCatalog {
    public enum Size { INPUT, VIEWPORT, HALF_VIEWPORT }
    public enum Sampling { DISPLAY, NEAREST, LINEAR }

    public static final class Parameter {
        public final String id;
        public final int label;
        public final float initial, minimum, maximum, step;
        /** Discrete values use localized labels instead of an arbitrary numeric slider. */
        public final List<Integer> options;

        private Parameter(String id, int label, float initial, float minimum, float maximum,
                          float step, List<Integer> options) {
            this.id = id;
            this.label = label;
            this.initial = initial;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.options = options;
        }
    }

    public static final class Pass {
        public final String fragment;
        public final Size size;
        public final Sampling sampling;
        public final boolean composite;

        Pass(String fragment, Size size, Sampling sampling, boolean composite) {
            this.fragment = fragment;
            this.size = size;
            this.sampling = sampling;
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
        return new Parameter(id, label, def, min, max, step, Collections.emptyList());
    }

    private static Parameter choice(String id, int label, int selected, int... labels) {
        ArrayList<Integer> options = new ArrayList<>(labels.length);
        for (int option : labels) options.add(option);
        return new Parameter(id, label, selected, 0f, labels.length - 1f, 1f,
                Collections.unmodifiableList(options));
    }

    private static Pass pass(String name, Size size) {
        return new Pass(name, size, Sampling.DISPLAY, false);
    }

    private static Pass nearest(String name) {
        return new Pass(name, Size.VIEWPORT, Sampling.NEAREST, false);
    }

    private static Pass linear(String name, Size size) {
        return new Pass(name, size, Sampling.LINEAR, false);
    }

    private static final List<Effect> ALL = Collections.unmodifiableList(Arrays.asList(
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
                param("DILATION", R.string.effect_dilation, 1f, 0f, 1f, 1f)}),
        new Effect("lcd", R.string.effect_lcd, R.string.effect_lcd_desc,
            new Pass[]{pass("lcd.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uGridStrength", R.string.effect_lcd_grid, 0.3f, 0f, 1f, 0.05f),
                param("uSubpixelStrength", R.string.effect_lcd_subpixel, 0.2f, 0f, 1f, 0.05f),
                param("uLcdBrightness", R.string.effect_lcd_brightness, 1.05f, 0.6f, 1.5f, 0.05f)}),
        new Effect("scanlines", R.string.effect_scanlines, R.string.effect_scanlines_desc,
            new Pass[]{pass("scanlines.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uLineStrength", R.string.effect_scan_strength, 0.4f, 0f, 1f, 0.05f),
                param("uLineWidth", R.string.effect_line_width, 2f, 0.5f, 6f, 0.25f)}),
        new Effect("color", R.string.effect_color, R.string.effect_color_desc,
            new Pass[]{pass("color.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uBrightness", R.string.effect_brightness, 0f, -0.5f, 0.5f, 0.01f),
                param("uContrast", R.string.effect_contrast, 1f, 0.2f, 2f, 0.05f),
                param("uSaturation", R.string.effect_saturation, 1f, 0f, 2f, 0.05f),
                param("uGamma", R.string.effect_gamma, 1f, 0.5f, 2.5f, 0.05f),
                param("uTemperature", R.string.effect_temperature, 0f, -1f, 1f, 0.05f),
                param("uTint", R.string.effect_tint, 0f, -1f, 1f, 0.05f),
                param("uMonochrome", R.string.effect_monochrome, 0f, 0f, 1f, 1f)}),
        new Effect("vignette", R.string.effect_vignette, R.string.effect_vignette_desc,
            new Pass[]{pass("vignette.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uStrength", R.string.effect_strength, 0.2f, 0f, 1f, 0.05f)}),
        new Effect("sharpen", R.string.effect_sharpen, R.string.effect_sharpen_desc,
            new Pass[]{pass("sharpen.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uSharpness", R.string.effect_strength, 0.3f, 0f, 1f, 0.05f)}),
        new Effect("cartoon", R.string.effect_cartoon, R.string.effect_cartoon_desc,
            new Pass[]{pass("cartoon.fsh", Size.VIEWPORT)}, new Parameter[]{
                param("uLevels", R.string.effect_color_levels, 8f, 2f, 12f, 1f),
                param("uEdge", R.string.effect_edge, 0.25f, 0f, 1f, 0.05f)}),
        new Effect("color-depth", R.string.effect_depth, R.string.effect_depth_desc,
            new Pass[]{pass("color-depth.fsh", Size.VIEWPORT)}, new Parameter[]{
                choice("uColorMode", R.string.effect_depth_format, 1,
                    R.string.effect_depth_rgb444, R.string.effect_depth_rgb565,
                    R.string.effect_depth_rgb666),
                choice("uDitherMode", R.string.effect_dither_mode, 1,
                    R.string.effect_dither_none, R.string.effect_dither_bayer4,
                    R.string.effect_dither_bayer8),
                param("uDitherStrength", R.string.effect_dither_strength, 1f, 0f, 1f, 0.05f)}),
        new Effect("mono-display", R.string.effect_mono_display, R.string.effect_mono_display_desc,
            new Pass[]{pass("mono-display.fsh", Size.VIEWPORT)}, new Parameter[]{
                choice("uMonoPalette", R.string.effect_mono_palette, 1,
                    R.string.effect_mono_bw, R.string.effect_mono_green,
                    R.string.effect_mono_amber),
                choice("uToneSteps", R.string.effect_mono_tones, 0,
                    R.string.effect_mono_two, R.string.effect_mono_four)}),
        new Effect("bloom", R.string.effect_bloom, R.string.effect_bloom_desc,
            new Pass[]{
                new Pass("bloom-threshold.fsh", Size.HALF_VIEWPORT, Sampling.NEAREST, false),
                linear("bloom-blur-h.fsh", Size.HALF_VIEWPORT),
                linear("bloom-blur-v.fsh", Size.HALF_VIEWPORT),
                new Pass("bloom-composite.fsh", Size.VIEWPORT, Sampling.LINEAR, true)
            }, new Parameter[]{
                param("uThreshold", R.string.effect_threshold, 0.72f, 0.1f, 0.98f, 0.02f),
                param("uRadius", R.string.effect_radius, 1f, 0.5f, 3f, 0.1f),
                param("uIntensity", R.string.effect_strength, 0.4f, 0f, 2f, 0.05f)})
    ));

    private EffectCatalog() {}

    public static List<Effect> all() { return ALL; }

    public static Effect get(String id) {
        if (id == null) return null;
        for (Effect effect : ALL) if (effect.id.equals(id)) return effect;
        return null;
    }
}
