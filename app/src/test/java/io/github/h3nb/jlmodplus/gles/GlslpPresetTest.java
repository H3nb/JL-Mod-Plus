// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** CPU-only characterization of pinned Libretro GLSLP preset semantics. */
public class GlslpPresetTest {
    private static final int GUEST = 8, VIEWPORT = 96;

    private static final class Bundle implements GlslpPreset.AssetReader {
        private final Map<String, String> files = new HashMap<>();
        Bundle put(String path, String text) { files.put(path, text); return this; }
        @Override public String read(String path) throws IOException {
            String value = files.get(path);
            if (value == null) throw new IOException("Missing bundle asset: " + path);
            return value;
        }
    }

    private static GlslpPreset.Plan plan(GlslpPreset preset) {
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
        GlslpPreset base = GlslpPreset.load(
                assets, "xbrz/xbrz-freescale-multipass.glslp");
        GlslpPreset.Plan resolved = plan(base);
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
        // Five declarations from the genuine upstream pass-0 header.
        // Overriding only two names MUST preserve defaults for the other three.
        assets.put(first, "#pragma parameter XBRZ_TOL \"Tolerance\" 15 1 80 1\n"
                + "#pragma parameter XBRZ_DOM \"Dominance\" 3.8 1 8 0.05\n"
                + "#pragma parameter XBRZ_STEEP \"Steepness\" 3.2 1 6 0.1\n"
                + "#pragma parameter XBRZ_MINC \"Minimum contrast\" 0 0 80 2\n"
                + "#pragma parameter XBRZ_DIAG \"Maximum diagonal distance\" 0 0 160 4\n");
        GlslpPreset crisp =
                GlslpPreset.load(assets, "xbrz/xbrz-crisp.glslp");
        assertEquals(Double.valueOf(24), crisp.parameterOverrides.get("XBRZ_MINC"));
        assertEquals(Double.valueOf(100), crisp.parameterOverrides.get("XBRZ_DIAG"));
        assertEquals(VIEWPORT, plan(crisp).passes.get(1).outputWidth);
        Map<String, Float> values = crisp.resolvedParameters();
        assertEquals(5, values.size());
        assertEquals(15f, values.get("XBRZ_TOL"), 0f);
        assertEquals(3.8f, values.get("XBRZ_DOM"), 0f);
        assertEquals(3.2f, values.get("XBRZ_STEEP"), 0f);
        assertEquals(24f, values.get("XBRZ_MINC"), 0f);
        assertEquals(100f, values.get("XBRZ_DIAG"), 0f);
        GlslpPreset.Parameter minimumContrast = crisp.parameterDefinitions().stream()
                .filter(parameter -> parameter.id.equals("XBRZ_MINC")).findFirst().get();
        assertEquals("Minimum contrast", minimumContrast.label);
        assertEquals(0f, minimumContrast.minimum, 0f);
        assertEquals(80f, minimumContrast.maximum, 0f);
        assertEquals(24f, minimumContrast.value, 0f);
        GlslpPreset tuned = crisp.withParameterOverrides(
                java.util.Collections.singletonMap("XBRZ_MINC", 40f));
        assertEquals(40f, tuned.resolvedParameters().get("XBRZ_MINC"), 0f);
        assertEquals(24f, crisp.resolvedParameters().get("XBRZ_MINC"), 0f);
        expectFailure(() -> crisp.withParameterOverrides(
                java.util.Collections.singletonMap("XBRZ_MINC", 1000f)).resolvedParameters(),
                "Preset parameter outside GLSL range");

        assets.put("xbrz/out-of-range.glslp", source
                + "parameters = XBRZ_MINC\nXBRZ_MINC = 100\n");
        expectFailure(() -> GlslpPreset.load(assets, "xbrz/out-of-range.glslp")
                .resolvedParameters(), "Preset parameter outside GLSL range");
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
        GlslpPreset.Plan gauss = plan(GlslpPreset.load(
                assets, "blurs/gauss_4tap.glslp"));
        assertEquals(GUEST, gauss.passes.get(1).outputWidth);
        assertTrue("Source-sized final stage requires a presentation blit",
                gauss.finalBlitRequired);
        assertEquals(2L * 4 * GUEST * GUEST, gauss.conservativeIntermediateBytes);
        GlslpPreset gaussian = GlslpPreset.load(
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
        GlslpPreset.Plan xbrz = plan(GlslpPreset.load(
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
        GlslpPreset.Plan fsr = plan(GlslpPreset.load(
                assets, "fsr/fsr.glslp"));
        assertEquals(96, fsr.passes.get(0).outputWidth);
        assertEquals(96, fsr.passes.get(1).inputWidth);
        assertEquals(96, fsr.passes.get(1).outputWidth);
        assertTrue("Source-based final FSR stage still needs a stock blit,"
                        + " even when its dimensions happen to equal viewport",
                fsr.finalBlitRequired);

        assets.put("fsr/mixed.glslp", "shaders = 1\nshader0 = a.glsl\n"
                + "scale_type_x0 = absolute\nscale_x0 = 128\n"
                + "scale_type_y0 = viewport\nscale_y0 = 0.5\n");
        GlslpPreset.Plan mixed = plan(GlslpPreset.load(
                assets, "fsr/mixed.glslp"));
        assertEquals(128, mixed.passes.get(0).outputWidth);
        assertEquals(48, mixed.passes.get(0).outputHeight);
        assertTrue(mixed.finalBlitRequired);
        GlslpPreset.Plan fractional = GlslpPreset.load(
                assets, "fsr/mixed.glslp").plan(8, 8, 95, 95, 2048, 1024 * 1024);
        assertEquals("Relative viewport FBO sizes truncate toward zero",
                47, fractional.passes.get(0).outputHeight);
    }

    @Test public void unsupportedFeaturesAndInvalidAllocationsFailClosed()
            throws Exception {
        Bundle assets = new Bundle().put("test/p.glsl", "valid");
        String base = "shaders = 1\nshader0 = p.glsl\n";
        assets.put("test/refs.glslp", "#reference \"elsewhere.glslp\"\n" + base);
        expectFailure(() -> GlslpPreset.load(assets, "test/refs.glslp"),
                "GLSLP #reference cannot redefine shader structure");
        assets.put("test/lut.glslp", base + "textures = mask\nmask = tex.png\n");
        GlslpPreset withLut = GlslpPreset.load(assets, "test/lut.glslp");
        assertEquals(1, withLut.textures.size());
        assertEquals("test/tex.png", withLut.textures.get(0).sourcePath);
        assertTrue("Unspecified LUT filtering defaults to linear", withLut.textures.get(0).linear);
        assets.put("test/escape.glslp",
                "shaders = 1\nshader0 = ../../outside.glsl\n");
        expectFailure(() -> GlslpPreset.load(assets, "test/escape.glslp"),
                "Asset escapes GLSLP bundle");
        assets.put("test/duplicate.glslp", base + "shader0 = p.glsl\n");
        expectFailure(() -> GlslpPreset.load(assets, "test/duplicate.glslp"),
                "Duplicate GLSLP key");
        assets.put("test/over.glslp",
                base + "scale_type0 = source\nscale0 = 64\n");
        GlslpPreset huge = GlslpPreset.load(assets, "test/over.glslp");
        expectFailure(() -> huge.plan(128, 128, 96, 96, 4096, 64 * 1024),
                "Unsupported pass dimension");
        assets.put("test/unknown.glslp", base + "feedback_pass = 0\n");
        expectFailure(() -> GlslpPreset.load(assets, "test/unknown.glslp"),
                "Unsupported GLSLP key");
    }

    @Test public void simpleReferenceInheritsRelativeSourcesAndLastOverrideWins()
            throws Exception {
        Bundle assets = new Bundle()
                .put("lib/shaders/main.glsl", "#pragma parameter GAIN \"Gain\" 1 0 5 0.25\n")
                .put("lib/pipeline.glslp",
                        "shaders = 1\nshader0 = shaders/main.glsl\n"
                        + "parameters = GAIN\nGAIN = 1.5\n")
                .put("custom/base.glslp", "#reference \"../lib/pipeline.glslp\"\nGAIN = 2.0\n")
                .put("custom/tuned.glslp", "#reference \"base.glslp\"\nGAIN = 3.5\n");
        GlslpPreset preset = GlslpPreset.load(assets, "custom/tuned.glslp");
        assertEquals(1, preset.passes.size());
        assertEquals("lib/shaders/main.glsl", preset.passes.get(0).sourcePath);
        assertEquals(3.5f, preset.resolvedParameters().get("GAIN"), 0f);
        assertEquals(VIEWPORT, plan(preset).passes.get(0).outputWidth);

        assets.put("custom/bad.glslp", "#reference \"../lib/pipeline.glslp\"\nGAIN = 6\n");
        expectFailure(() -> GlslpPreset.load(assets, "custom/bad.glslp"),
                "Preset parameter outside GLSL range");
    }

    @Test public void referenceRejectsCyclesTraversalAndUnsupportedEdits() throws Exception {
        Bundle assets = new Bundle()
                .put("presets/cycle-a.glslp", "#reference \"cycle-b.glslp\"\n")
                .put("presets/cycle-b.glslp", "#reference \"cycle-a.glslp\"\n")
                .put("presets/escape.glslp", "#reference \"../../outside.glslp\"\n")
                .put("presets/multiple.glslp",
                        "#reference \"a.glslp\"\n#reference \"b.glslp\"\n")
                .put("presets/main.glsl", "#pragma parameter GAIN \"Gain\" 1 0 5 0.5\n")
                .put("presets/base.glslp", "shaders = 1\nshader0 = main.glsl\n")
                .put("presets/unknown.glslp", "#reference \"base.glslp\"\nNoSuchParameter = 5\n");
        expectFailure(() -> GlslpPreset.load(assets, "presets/cycle-a.glslp"),
                "Cyclic or too deep GLSLP #reference");
        expectFailure(() -> GlslpPreset.load(assets, "presets/escape.glslp"),
                "Asset escapes GLSLP bundle");
        expectFailure(() -> GlslpPreset.load(assets, "presets/multiple.glslp"),
                "Invalid or multiple GLSLP #reference");
        expectFailure(() -> GlslpPreset.load(assets, "presets/unknown.glslp"),
                "Unsupported GLSLP #reference override");
    }

    @Test public void lookupTexturesParseAndInheritPaths() throws Exception {
        Bundle files = new Bundle()
                .put("look/shaders/p.glsl", "void main(){}")
                .put("look/p.glslp", "shaders = 1\nshader0 = shaders/p.glsl\n"
                        + "textures = \"ColorLUT;Mask\"\n"
                        + "ColorLUT = textures/lut.png\nColorLUT_linear = true\n"
                        + "Mask = textures/mask.jpg\nMask_repeat = true\nMask_mipmap = true\n")
                .put("custom/p.glslp", "#reference \"../look/p.glslp\"\n"
                        + "ColorLUT = custom.png\n");
        GlslpPreset loaded = GlslpPreset.load(files, "custom/p.glslp");
        assertEquals(2, loaded.textures.size());
        assertEquals("custom/custom.png", loaded.textures.get(0).sourcePath);
        assertEquals("look/textures/mask.jpg", loaded.textures.get(1).sourcePath);
        assertTrue(loaded.textures.get(0).linear);
        assertTrue(loaded.textures.get(1).mipmap);
        assertEquals("repeat", loaded.textures.get(1).wrapMode);
        assertEquals(2, loaded.withParameterOverrides(java.util.Collections.emptyMap()).textures.size());
    }

    @Test public void lookupTexturesRejectBadInput() throws Exception {
        Bundle files = new Bundle().put("look/p.glsl", "void main(){}");
        String base = "shaders = 1\nshader0 = p.glsl\n";
        files.put("look/traversal.glslp", base + "textures = LUT\nLUT = ../../bad.png\n");
        expectFailure(() -> GlslpPreset.load(files, "look/traversal.glslp"),
                "Asset escapes GLSLP bundle");
        files.put("look/unsupported.glslp", base + "textures = LUT\nLUT = lut.bmp\n");
        expectFailure(() -> GlslpPreset.load(files, "look/unsupported.glslp"),
                "Unsupported GLSLP texture format");
        files.put("look/collision.glslp", base + "textures = Texture\nTexture = foo.png\n");
        expectFailure(() -> GlslpPreset.load(files, "look/collision.glslp"),
                "Invalid or reserved GLSLP texture alias");
        files.put("look/duplicate.glslp", base + "textures = LUT;LUT\nLUT = foo.png\n");
        expectFailure(() -> GlslpPreset.load(files, "look/duplicate.glslp"),
                "Invalid or reserved GLSLP texture alias");
        files.put("look/bad-mipmap.glslp", base
                + "textures = LUT\nLUT = foo.png\nLUT_mipmap = maybe\n");
        expectFailure(() -> GlslpPreset.load(files, "look/bad-mipmap.glslp"),
                "Invalid boolean");
    }

    @Test public void namedPassAliasesAreRetainedAndValidated() throws Exception {
        Bundle files = new Bundle().put("named/p.glsl", "void main(){}")
                .put("named/base.glslp", "shaders = 3\n"
                        + "shader0 = p.glsl\nalias0 = First\n"
                        + "shader1 = p.glsl\nalias1 = Second\n"
                        + "shader2 = p.glsl\nalias2 = \"\"\n")
                .put("named/child.glslp", "#reference \"base.glslp\"\n");
        GlslpPreset inherited = GlslpPreset.load(files, "named/child.glslp");
        assertEquals("First", inherited.passes.get(0).alias);
        assertEquals("Second", inherited.passes.get(1).alias);
        assertEquals("", inherited.passes.get(2).alias);

        files.put("named/duplicate.glslp", "shaders = 2\n"
                + "shader0 = p.glsl\nalias0 = Reused\n"
                + "shader1 = p.glsl\nalias1 = Reused\n");
        expectFailure(() -> GlslpPreset.load(files, "named/duplicate.glslp"),
                "Invalid or duplicate GLSLP pass alias");

        files.put("named/reserved.glslp", "shaders = 1\n"
                + "shader0 = p.glsl\nalias0 = Pass1Texture\n");
        expectFailure(() -> GlslpPreset.load(files, "named/reserved.glslp"),
                "Invalid or duplicate GLSLP pass alias");

        files.put("named/numeric-collision.glslp", "shaders = 1\n"
                + "shader0 = p.glsl\nalias0 = Pass1\n");
        expectFailure(() -> GlslpPreset.load(files, "named/numeric-collision.glslp"),
                "Invalid or duplicate GLSLP pass alias");

        files.put("named/derived-collision.glslp", "shaders = 1\n"
                + "shader0 = p.glsl\nalias0 = Edge\n"
                + "textures = EdgeTexture\nEdgeTexture = mask.png\n");
        expectFailure(() -> GlslpPreset.load(files, "named/derived-collision.glslp"),
                "Invalid or reserved GLSLP texture alias");

        files.put("named/collision.glslp", "shaders = 1\n"
                + "shader0 = p.glsl\nalias0 = Mask\n"
                + "textures = Mask\nMask = mask.png\n");
        expectFailure(() -> GlslpPreset.load(files, "named/collision.glslp"),
                "Invalid or reserved GLSLP texture alias");
    }

    @Test public void exportedNeutralGlslpMetadataIsAcceptedWithoutEmulation() throws Exception {
        Bundle assets = new Bundle().put("pass.glsl", "void main(){}")
                .put("neutral.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                        + "mipmap_input0 = false\nfloat_framebuffer0 = false\n"
                        + "srgb_framebuffer0 = false\nframe_count_mod0 = 0\n");
        GlslpPreset preset = GlslpPreset.load(assets, "neutral.glslp");
        assertEquals(1, preset.passes.size());
        assertFalse(preset.passes.get(0).mipmapInput);

        assets.put("float.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                + "float_framebuffer0 = true\n");
        expectFailure(() -> GlslpPreset.load(assets, "float.glslp"),
                "Unsupported GLSLP framebuffer format");
        assets.put("srgb.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                + "srgb_framebuffer0 = true\n");
        expectFailure(() -> GlslpPreset.load(assets, "srgb.glslp"),
                "Unsupported GLSLP framebuffer format");
        assets.put("temporal.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                + "frame_count_mod0 = 2\n");
        expectFailure(() -> GlslpPreset.load(assets, "temporal.glslp"),
                "Unsupported GLSLP temporal");
        assets.put("border.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                + "wrap_mode0 = clamp_to_border\n");
        expectFailure(() -> GlslpPreset.load(assets, "border.glslp"),
                "GLSLP clamp_to_border");
    }

    @Test public void mipmapsAndRepeatRequirePowerOfTwoAndRespectMemoryBudget() throws Exception {
        Bundle assets = new Bundle().put("pass.glsl", "void main(){}")
                .put("mip.glslp", "shaders = 1\nshader0 = pass.glsl\n"
                        + "mipmap_input0 = true\nwrap_mode0 = mirrored_repeat\n");
        GlslpPreset preset = GlslpPreset.load(assets, "mip.glslp");
        assertTrue(preset.passes.get(0).mipmapInput);
        assertEquals("mirrored_repeat", preset.passes.get(0).wrapMode);
        GlslpPreset.Plan planned = preset.plan(32, 32, 96, 96, 2048, 1024 * 1024);
        assertTrue(planned.conservativeIntermediateBytes > 0);
        assertEquals("Thin textures have almost one extra full level of mip storage",
                4092L, GlslpPreset.mipChainBytes(1, 1024));
        expectFailure(() -> preset.plan(31, 32, 96, 96, 2048, 1024 * 1024),
                "power-of-two");
        assertEquals(1, preset.plan(31, 32, 96, 96, 2048,
                1024 * 1024, true).passes.size());
        expectFailure(() -> preset.plan(32, 32, 96, 96, 2048, 0),
                "Intermediate GPU budget exceeded");

        assets.put("two.glslp", "shaders = 2\n"
                + "shader0 = pass.glsl\nscale_type0 = source\nscale0 = 1\n"
                + "shader1 = pass.glsl\nmipmap_input1 = true\n");
        GlslpPreset.Plan passes = GlslpPreset.load(assets, "two.glslp")
                .plan(32, 32, 96, 96, 2048, 1024 * 1024);
        assertEquals(2, passes.passes.size());
        assertTrue(passes.conservativeIntermediateBytes > 4L * 32 * 32);
    }

    @Test public void standaloneGlslCompilesAsViewportSizedOnePass() throws Exception {
        Bundle assets = new Bundle().put("custom.glsl", "#ifdef VERTEX\\n#endif");
        GlslpPreset shader = GlslpPreset.load(assets, "custom.glsl");
        assertEquals(1, shader.passes.size());
        assertEquals("custom.glsl", shader.passes.get(0).sourcePath);
        assertFalse(plan(shader).finalBlitRequired);
        assertEquals(VIEWPORT, plan(shader).passes.get(0).outputWidth);
    }

    @Test public void multiplePassPlanningAndBudgetAreBounded() throws Exception {
        Bundle bundle = new Bundle();
        StringBuilder preset = new StringBuilder("shaders = 7\n");
        for (int i = 0; i < 7; i++) {
            preset.append("shader").append(i).append(" = p").append(i).append(".glsl\n")
                    .append("scale_type").append(i).append(" = source\n")
                    .append("scale").append(i).append(" = ").append(i == 1 ? 2 : 1).append(".0\n");
            bundle.put("many/p" + i + ".glsl", "void main(){}");
        }
        bundle.put("many/preset.glslp", preset.toString());
        GlslpPreset shader = GlslpPreset.load(bundle, "many/preset.glslp");
        assertEquals(7, shader.passes.size());
        GlslpPreset.Plan plan = shader.plan(16, 12, 128, 96, 2048, 1024 * 1024);
        assertEquals(32, plan.passes.get(1).outputWidth);
        assertEquals(32, plan.passes.get(2).inputWidth);
        assertTrue(plan.finalBlitRequired);
        expectFailure(() -> shader.plan(16, 12, 128, 96, 2048, 1000),
                "Intermediate GPU budget exceeded");
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
