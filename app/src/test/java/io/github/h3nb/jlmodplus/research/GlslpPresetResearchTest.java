// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** CPU-only characterization of pinned Libretro GLSLP preset semantics. */
public class GlslpPresetResearchTest {
    private static final int GUEST = 8, VIEWPORT = 96;

    private static final class Bundle implements GlslpPresetResearch.AssetReader {
        private final Map<String, String> files = new HashMap<>();
        Bundle put(String path, String text) { files.put(path, text); return this; }
        @Override public String read(String path) throws IOException {
            String value = files.get(path);
            if (value == null) throw new IOException("Missing bundle asset: " + path);
            return value;
        }
    }

    private static GlslpPresetResearch.Plan plan(GlslpPresetResearch preset) {
        return preset.plan(GUEST, GUEST, VIEWPORT, VIEWPORT, 2048, 8 * 1024 * 1024);
    }

    @Test public void xbrzFreescaleAndCrispPreserveSourceAndViewportStages()
            throws Exception {
        // Layout and parameters from pinned libretro/glsl-shaders@cba01a48.
        String source = "shaders = 2\n"
                + "shader0 = shaders/xbrz-freescale-multipass/xbrz-freescale-pass0.glsl\n"
                + "filter_linear0 = false\nscale_type0 = source\nscale0 = 1.0\n"
                + "shader1 = shaders/xbrz-freescale-multipass/xbrz-freescale-pass1.glsl\n"
                + "filter_linear1 = false\nscale_type1 = viewport\nscale1 = 1.0\n";
        String first = "xbrz/shaders/xbrz-freescale-multipass/xbrz-freescale-pass0.glsl";
        String second = "xbrz/shaders/xbrz-freescale-multipass/xbrz-freescale-pass1.glsl";
        Bundle assets = new Bundle().put(first, "void main() {}")
                .put(second, "uniform sampler2D PassPrev2Texture;");
        assets.put("xbrz/xbrz-freescale-multipass.glslp", source);
        GlslpPresetResearch base = GlslpPresetResearch.load(
                assets, "xbrz/xbrz-freescale-multipass.glslp");
        GlslpPresetResearch.Plan resolved = plan(base);
        assertEquals(2, resolved.passes.size());
        assertEquals(GUEST, resolved.passes.get(0).outputWidth);
        assertEquals(GUEST, resolved.passes.get(0).outputHeight);
        assertTrue(resolved.passes.get(0).offscreen);
        assertEquals(VIEWPORT, resolved.passes.get(1).outputWidth);
        assertEquals(VIEWPORT, resolved.passes.get(1).outputHeight);
        assertEquals(GUEST, resolved.passes.get(1).inputWidth);
        assertFalse(resolved.passes.get(1).offscreen);
        assertFalse(resolved.finalBlitRequired);
        assertEquals(4L * GUEST * GUEST, resolved.conservativeIntermediateBytes);
        assertEquals(Boolean.FALSE, base.passes.get(0).linearFilter);
        assertEquals(Boolean.FALSE, base.passes.get(1).linearFilter);

        // xbrz-crisp.glslp keeps the same two stages with preset-defined
        // named overrides. This parser records them; no GPU binding is claimed.
        assets.put("xbrz/xbrz-crisp.glslp", source
                + "parameters = \"XBRZ_MINC;XBRZ_DIAG\"\n"
                + "XBRZ_MINC = \"24.000000\"\n"
                + "XBRZ_DIAG = \"100.000000\"\n");
        GlslpPresetResearch crisp =
                GlslpPresetResearch.load(assets, "xbrz/xbrz-crisp.glslp");
        assertEquals(Double.valueOf(24), crisp.parameterOverrides.get("XBRZ_MINC"));
        assertEquals(Double.valueOf(100), crisp.parameterOverrides.get("XBRZ_DIAG"));
        assertEquals(VIEWPORT, plan(crisp).passes.get(1).outputWidth);
    }

    @Test public void gaussAndFourXbrzHaveDifferentFinalPresentationSemantics()
            throws Exception {
        Bundle assets = new Bundle().put("blurs/shaders/h.glsl", "first")
                .put("blurs/shaders/v.glsl", "second")
                .put("blurs/gauss_4tap.glslp",
                        "shaders = 2\n"
                        + "shader0 = shaders/h.glsl\n"
                        + "scale_type0 = source\nscale0 = 1.0\n"
                        + "shader1 = shaders/v.glsl\n"
                        + "scale_type1 = source\nscale1 = 1.0\n");
        GlslpPresetResearch.Plan gauss = plan(GlslpPresetResearch.load(
                assets, "blurs/gauss_4tap.glslp"));
        assertEquals(GUEST, gauss.passes.get(1).outputWidth);
        assertTrue("Source-sized final stage requires a presentation blit",
                gauss.finalBlitRequired);
        assertEquals(2L * 4 * GUEST * GUEST, gauss.conservativeIntermediateBytes);
        GlslpPresetResearch gaussian = GlslpPresetResearch.load(
                assets, "blurs/gauss_4tap.glslp");
        assertTrue("A source-relative last pass remains a separate FBO even"
                        + " when source and viewport dimensions coincide",
                gaussian.plan(8, 8, 8, 8, 2048, 1024).finalBlitRequired);

        // Pinned 4xbrz-linear has a 4x first pass, then ../stock.glsl
        // without a scale_type1; the last pass therefore targets viewport.
        assets.put("xbrz/4xbrz-linear.glslp",
                "shaders = 2\n"
                + "shader0 = shaders/4xbrz.glsl\n"
                + "filter_linear0 = false\nscale_type0 = source\nscale0 = 4.0\n"
                + "shader1 = ../stock.glsl\nfilter_linear1 = true\n");
        assets.put("xbrz/shaders/4xbrz.glsl", "first");
        assets.put("stock.glsl", "second");
        GlslpPresetResearch.Plan xbrz = plan(GlslpPresetResearch.load(
                assets, "xbrz/4xbrz-linear.glslp"));
        assertEquals(32, xbrz.passes.get(0).outputWidth);
        assertEquals(32, xbrz.passes.get(1).inputWidth);
        assertEquals(VIEWPORT, xbrz.passes.get(1).outputWidth);
        assertFalse(xbrz.finalBlitRequired);
        assertEquals(4L * 32 * 32, xbrz.conservativeIntermediateBytes);
        assertEquals("stock.glsl", xbrz.passes.get(1).pass.sourcePath);
        assertEquals(Boolean.TRUE, xbrz.passes.get(1).pass.linearFilter);
    }

    @Test public void viewportFirstThenSourceAndIndependentAxisSizing()
            throws Exception {
        Bundle assets = new Bundle().put("fsr/a.glsl", "pass0")
                .put("fsr/b.glsl", "pass1")
                .put("fsr/fsr.glslp",
                        "shaders = 2\nshader0 = a.glsl\n"
                        + "scale_type0 = viewport\nscale0 = 1.0\n"
                        + "shader1 = b.glsl\nscale_type1 = source\nscale1 = 1.0\n");
        GlslpPresetResearch.Plan fsr = plan(GlslpPresetResearch.load(
                assets, "fsr/fsr.glslp"));
        assertEquals(96, fsr.passes.get(0).outputWidth);
        assertEquals(96, fsr.passes.get(1).inputWidth);
        assertEquals(96, fsr.passes.get(1).outputWidth);
        assertFalse(fsr.finalBlitRequired);

        assets.put("fsr/mixed.glslp", "shaders = 1\nshader0 = a.glsl\n"
                + "scale_type_x0 = absolute\nscale_x0 = 128\n"
                + "scale_type_y0 = viewport\nscale_y0 = 0.5\n");
        GlslpPresetResearch.Plan mixed = plan(GlslpPresetResearch.load(
                assets, "fsr/mixed.glslp"));
        assertEquals(128, mixed.passes.get(0).outputWidth);
        assertEquals(48, mixed.passes.get(0).outputHeight);
        assertTrue(mixed.finalBlitRequired);
        GlslpPresetResearch.Plan fractional = GlslpPresetResearch.load(
                assets, "fsr/mixed.glslp").plan(8, 8, 95, 95, 2048, 1024 * 1024);
        assertEquals("Relative viewport FBO sizes truncate toward zero",
                47, fractional.passes.get(0).outputHeight);
    }

    @Test public void unsupportedFeaturesAndInvalidAllocationsFailClosed()
            throws Exception {
        Bundle assets = new Bundle().put("test/p.glsl", "valid");
        String base = "shaders = 1\nshader0 = p.glsl\n";
        assets.put("test/refs.glslp", "#reference \"elsewhere.glslp\"\n" + base);
        expectFailure(() -> GlslpPresetResearch.load(assets, "test/refs.glslp"),
                "Unsupported GLSLP #reference");
        assets.put("test/lut.glslp", base + "textures = mask\nmask = tex.png\n");
        expectFailure(() -> GlslpPresetResearch.load(assets, "test/lut.glslp"),
                "Unsupported GLSLP key");
        assets.put("test/escape.glslp",
                "shaders = 1\nshader0 = ../../outside.glsl\n");
        expectFailure(() -> GlslpPresetResearch.load(assets, "test/escape.glslp"),
                "Asset escapes GLSLP bundle");
        assets.put("test/duplicate.glslp", base + "shader0 = p.glsl\n");
        expectFailure(() -> GlslpPresetResearch.load(assets, "test/duplicate.glslp"),
                "Duplicate GLSLP key");
        assets.put("test/over.glslp",
                base + "scale_type0 = source\nscale0 = 64\n");
        GlslpPresetResearch huge = GlslpPresetResearch.load(assets, "test/over.glslp");
        expectFailure(() -> huge.plan(128, 128, 96, 96, 4096, 64 * 1024),
                "Unsupported pass dimension");
        assets.put("test/unknown.glslp", base + "feedback_pass = 0\n");
        expectFailure(() -> GlslpPresetResearch.load(assets, "test/unknown.glslp"),
                "Unsupported GLSLP key");
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static void expectFailure(ThrowingAction action, String message)
            throws Exception {
        try {
            action.run();
            fail("Expected: " + message);
        } catch (IOException | IllegalArgumentException e) {
            assertTrue("Wrong rejection: " + e.getMessage(),
                    e.getMessage().contains(message));
        }
    }
}
