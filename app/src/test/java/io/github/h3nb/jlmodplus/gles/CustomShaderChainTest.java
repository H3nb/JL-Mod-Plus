// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static org.junit.jupiter.api.Assertions.*;

import io.github.h3nb.jlmodplus.config.CustomShaderStage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomShaderChainTest {
    @TempDir Path shaderRoot;

    private void source(String path, String text) throws IOException {
        Path file = shaderRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void customChainResolvesRealExternalSourcesAndStageLayout() throws Exception {
        source("shaders_glsl/crop.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        source("shaders_glsl/scanlines.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        GlslpPreset chain = GlslpAssets.loadCustom(Arrays.asList(
                new CustomShaderStage("shaders_glsl/crop.glsl", 2, false),
                new CustomShaderStage("shaders_glsl/scanlines.glsl", 1, true)),
                shaderRoot.toFile());

        GlslpPreset.Plan plan = chain.plan(32, 40, 120, 160, 4096, 64L * 1024 * 1024);
        assertEquals(2, plan.passes.size());
        assertEquals(64, plan.passes.get(0).outputWidth);
        assertEquals(80, plan.passes.get(0).outputHeight);
        assertTrue(plan.passes.get(0).offscreen);
        assertEquals(120, plan.passes.get(1).outputWidth);
        assertEquals(160, plan.passes.get(1).outputHeight);
        assertFalse(plan.passes.get(1).offscreen);
        assertEquals(Boolean.FALSE, chain.passes.get(0).linearFilter);
        assertEquals(Boolean.TRUE, chain.passes.get(1).linearFilter);
    }

    @Test void customChainRejectsUnsafeSourcesAndExtraTextureDependencies() throws Exception {
        source("shaders_glsl/complex.glsl",
                "uniform sampler2D Texture;\nuniform sampler2D OriginalTexture;");
        IOException extra = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Collections.singletonList(
                        new CustomShaderStage("shaders_glsl/complex.glsl", 1, null)),
                        shaderRoot.toFile()));
        assertTrue(extra.getMessage().contains("additional sampler"));

        IOException unsafe = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Collections.singletonList(
                        new CustomShaderStage("../escape.glsl", 1, null)),
                        shaderRoot.toFile()));
        assertTrue(unsafe.getMessage().contains("Asset escapes GLSLP bundle"),
                unsafe.getMessage());

        IOException empty = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Collections.emptyList(), shaderRoot.toFile()));
        assertTrue(empty.getMessage().contains("1–16 stages"));
    }

    @Test void firstPassCanReadOriginalButNotFuturePasses() throws Exception {
        source("original/first.glsl",
                "uniform sampler2D Texture;\nuniform sampler2D OrigTexture;\nvoid main() {}");
        source("original/second.glsl",
                "uniform sampler2D Texture;\nuniform sampler2D OrigTexture;\nvoid main() {}");
        source("original/chain.glslp",
                "shaders = 2\nshader0 = first.glsl\nshader1 = second.glsl\n");

        GlslpPreset standalone = GlslpAssets.loadCustom(Collections.singletonList(
                new CustomShaderStage("original/first.glsl", 1, null)),
                shaderRoot.toFile());
        assertTrue(standalone.passes.get(0).sourceText.contains("OrigTexture"));
        GlslpPreset inherited = GlslpAssets.loadCustom(Arrays.asList(
                new CustomShaderStage("original/first.glsl", 0, null),
                new CustomShaderStage("original/second.glsl", 0, null)),
                shaderRoot.toFile(), "original/chain.glslp", "glsl");
        assertEquals(2, inherited.passes.size());

        source("original/future.glsl", "uniform sampler2D Pass2Texture;\nvoid main() {}");
        IOException future = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Arrays.asList(
                        new CustomShaderStage("original/future.glsl", 0, null),
                        new CustomShaderStage("original/second.glsl", 0, null)),
                        shaderRoot.toFile(), "original/chain.glslp", "glsl"));
        assertTrue(future.getMessage().contains("Pass2Texture"), future.getMessage());
        source("original/previous.glsl",
                "uniform sampler2D PassPrev2Texture;\nvoid main() {}");
        IOException previous = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Arrays.asList(
                        new CustomShaderStage("original/previous.glsl", 0, null),
                        new CustomShaderStage("original/second.glsl", 0, null)),
                        shaderRoot.toFile(), "original/chain.glslp", "glsl"));
        assertTrue(previous.getMessage().contains("PassPrev2Texture"), previous.getMessage());
    }

    @Test void customChainAndPresetAcceptNestedShaderFolderPaths() throws Exception {
        source("extra/passes/first.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        source("extra/passes/final.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        source("extra/passes/effect.glslp",
                "shaders = 2\nshader0 = first.glsl\n"
                + "scale_type0 = source\nscale0 = 1\n"
                + "shader1 = final.glsl\n");
        GlslpPreset userPreset = GlslpAssets.load("extra/passes/effect.glslp",
                shaderRoot.toFile());
        assertEquals(2, userPreset.passes.size());
        GlslpPreset custom = GlslpAssets.loadCustom(Arrays.asList(
                new CustomShaderStage("extra/passes/first.glsl", 2, false),
                new CustomShaderStage("extra/passes/final.glsl", 1, null)),
                shaderRoot.toFile());
        assertEquals(2, custom.passes.size());
        assertEquals(64, custom.plan(32, 32, 100, 100, 4096,
                64L * 1024 * 1024).passes.get(0).outputWidth);
        assertThrows(IOException.class,
                () -> GlslpAssets.load("../unsafe.glslp", shaderRoot.toFile()));
    }

    @Test void mixedCaseExtensionsPreserveRealFileNames() throws Exception {
        source("nested/effect.GLSLP",
                "shaders = 1\nshader0 = detail.GLSL\n");
        source("nested/detail.GLSL", "uniform sampler2D Texture;\nvoid main() {}");
        GlslpPreset preset = GlslpAssets.load("nested/effect.GLSLP", shaderRoot.toFile());
        assertEquals("nested/detail.GLSL", preset.passes.get(0).sourcePath);
        GlslpPreset custom = GlslpAssets.loadCustom(Collections.singletonList(
                new CustomShaderStage("nested/detail.GLSL", 1, null)), shaderRoot.toFile());
        assertEquals("nested/detail.GLSL", custom.passes.get(0).sourcePath);
    }


    @Test void inheritedTenPassPresetRetainsMetadataAndParameters() throws Exception {
        StringBuilder text = new StringBuilder("shaders = 10\n")
                .append("textures = LUT\nLUT = lut.png\n");
        for (int i = 0; i < 10; i++) {
            text.append("shader").append(i).append(" = pass").append(i).append(".glsl\n");
            source("many/pass" + i + ".glsl", "uniform sampler2D Texture;\n"
                    + (i == 0 ? "#pragma parameter GAIN \"Gain\" 1.0 0.0 2.0 0.1\n" : "")
                    + "void main() {}\n");
        }
        text.append("scale_type0 = source\nscale0 = 2\n");
        text.append("filter_linear0 = true\nwrap_mode0 = repeat\nalias0 = PREVIEW\n");
        text.append("GAIN = 1.3\nparameters = GAIN\n");
        source("many/ten.glslp", text.toString());
        GlslpPreset original = GlslpAssets.load("many/ten.glslp", shaderRoot.toFile());
        java.util.List<CustomShaderStage> stages = new java.util.ArrayList<>();
        for (GlslpPreset.Pass pass : original.passes) {
            stages.add(new CustomShaderStage(pass.sourcePath, 0, null));
        }
        GlslpPreset inherited = GlslpAssets.loadCustom(stages,
                shaderRoot.toFile(), "many/ten.glslp", "glsl");
        assertEquals(10, inherited.passes.size());
        assertEquals(1, inherited.textures.size());
        assertEquals("many/lut.png", inherited.textures.get(0).sourcePath);
        assertEquals("PREVIEW", inherited.passes.get(0).alias);
        assertEquals("repeat", inherited.passes.get(0).wrapMode);
        assertEquals(Boolean.TRUE, inherited.passes.get(0).linearFilter);
        assertEquals(1.3f, inherited.resolvedParameters().get("GAIN"), 0.001f);
        assertEquals(64, inherited.plan(32, 32, 128, 128, 4096,
                64L * 1024 * 1024).passes.get(0).outputWidth);
    }

    @Test void inheritedCustomChainRejectsUnboundSamplerButAllowsExistingDependencies() throws Exception {
        source("derived/first.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        source("derived/second.glsl", "uniform sampler2D Texture;\nvoid main() {}");
        source("derived/chain.glslp", "shaders = 2\nshader0 = first.glsl\n"
                + "alias0 = FirstOutput\nshader1 = second.glsl\n"
                + "textures = ColorLUT\nColorLUT = lut.png\n");
        source("derived/unknown.glsl", "uniform sampler2D UnboundTexture;\nvoid main() {}");
        source("derived/unknown-list.glsl", "uniform sampler2D Texture, UnboundTexture;\n");
        source("derived/valid.glsl", "// uniform sampler2D NotBound;\n"
                + "/* uniform sampler2D AlsoNotBound; */\n"
                + "uniform sampler2D Texture;\n"
                + "uniform sampler2D FirstOutputTexture;\n"
                + "uniform sampler2D PassPrev2Texture;\n"
                + "uniform sampler2D ColorLUT;\nvoid main() {}");
        java.util.List<CustomShaderStage> invalid = Arrays.asList(
                new CustomShaderStage("derived/first.glsl", 0, null),
                new CustomShaderStage("derived/unknown.glsl", 0, null));
        IOException error = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(invalid, shaderRoot.toFile(),
                        "derived/chain.glslp", "glsl"));
        assertTrue(error.getMessage().contains("UnboundTexture"), error.getMessage());
        IOException grouped = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Arrays.asList(
                        new CustomShaderStage("derived/first.glsl", 0, null),
                        new CustomShaderStage("derived/unknown-list.glsl", 0, null)),
                        shaderRoot.toFile(), "derived/chain.glslp", "glsl"));
        assertTrue(grouped.getMessage().contains("UnboundTexture"), grouped.getMessage());
        GlslpPreset accepted = GlslpAssets.loadCustom(Arrays.asList(
                new CustomShaderStage("derived/first.glsl", 0, null),
                new CustomShaderStage("derived/valid.glsl", 0, null)),
                shaderRoot.toFile(), "derived/chain.glslp", "glsl");
        assertEquals(2, accepted.passes.size());
        assertEquals(1, accepted.textures.size());
        assertEquals("FirstOutput", accepted.passes.get(0).alias);
    }

    @Test void customPipelineRejectsNon2dSamplerBeforeGpuInitialization() throws Exception {
        source("samplers/cube.glsl", "uniform samplerCube Texture;\nvoid main() {}");
        IOException rejected = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(Collections.singletonList(
                                new CustomShaderStage("samplers/cube.glsl", 1, null)),
                        shaderRoot.toFile()));
        assertTrue(rejected.getMessage().contains("samplerCube"), rejected.getMessage());
    }

    @Test void inheritedCustomSourceReplacementDropsOnlyObsoleteParameters() throws Exception {
        source("parameters/original.glsl",
                "#pragma parameter GAIN \"Gain\" 1.0 0.0 2.0 0.1\n"
                + "#pragma parameter KEEP \"Keep\" 0.5 0.0 1.0 0.1\n"
                + "uniform sampler2D Texture;\nvoid main() {}");
        source("parameters/replacement.glsl",
                "#pragma parameter KEEP \"Keep\" 0.5 0.0 1.0 0.1\n"
                + "uniform sampler2D Texture;\nvoid main() {}");
        source("parameters/preset.glslp",
                "shaders = 1\nshader0 = original.glsl\n"
                + "parameters = \"GAIN;KEEP\"\nGAIN = 1.3\nKEEP = 0.6\n");
        CustomShaderStage original = new CustomShaderStage("parameters/original.glsl", 0, null);
        GlslpPreset unchanged = GlslpAssets.loadCustom(
                Collections.singletonList(original), shaderRoot.toFile(),
                "parameters/preset.glslp", "glsl");
        assertEquals(1.3f, unchanged.resolvedParameters().get("GAIN"), 0.001f);
        assertEquals(0.6f, unchanged.resolvedParameters().get("KEEP"), 0.001f);

        GlslpPreset changed = GlslpAssets.loadCustom(
                Collections.singletonList(new CustomShaderStage(
                        "parameters/replacement.glsl", 0, null)),
                shaderRoot.toFile(), "parameters/preset.glslp", "glsl");
        assertFalse(changed.parameterOverrides.containsKey("GAIN"));
        assertEquals(0.6f, changed.resolvedParameters().get("KEEP"), 0.001f);

        java.util.Map<String, Float> userEdits = new java.util.LinkedHashMap<>();
        userEdits.put("GAIN", 1.8f); // removed #pragma
        userEdits.put("KEEP", 0.7f); // still declared
        userEdits.put("UNKNOWN", 0.3f);
        java.util.Map<String, Float> applicable = changed.applicableParameterOverrides(userEdits);
        assertEquals(Collections.singletonMap("KEEP", 0.7f), applicable);
        assertEquals(0.7f,
                changed.withParameterOverrides(applicable).resolvedParameters().get("KEEP"), 0.001f);
    }

    @Test void inheritedCustomChainCannotBypassAggregateSourceBudget() throws Exception {
        StringBuilder description = new StringBuilder("shaders = 5\n");
        java.util.List<CustomShaderStage> stages = new java.util.ArrayList<>();
        String largeSource = "//" + "x".repeat(450 * 1024) + "\nvoid main() {}";
        for (int i = 0; i < 5; i++) {
            description.append("shader").append(i).append(" = base")
                    .append(i).append(".glsl\n");
            source("budget/base" + i + ".glsl", "void main() {}");
            source("budget/large" + i + ".glsl", largeSource);
            stages.add(new CustomShaderStage("budget/large" + i + ".glsl", 0, null));
        }
        source("budget/preset.glslp", description.toString());
        assertEquals(4, GlslpAssets.loadCustom(stages.subList(0, 4), shaderRoot.toFile(),
                "budget/preset.glslp", "glsl").passes.size());
        IOException rejected = assertThrows(IOException.class,
                () -> GlslpAssets.loadCustom(stages, shaderRoot.toFile(),
                        "budget/preset.glslp", "glsl"));
        assertTrue(rejected.getMessage().contains("source budget"), rejected.getMessage());
    }

    @Test void legacyCustomPassesRetainSeparateShaderPairsAndTuningValues() throws Exception {
        source("legacy/one.vsh", "attribute vec4 a_position;\n"
                + "attribute vec2 a_texcoord0;\nvarying vec2 v_texcoord0;\n"
                + "void main() { gl_Position=a_position; v_texcoord0=a_texcoord0; }\n");
        source("legacy/one.fsh", "precision mediump float;\n"
                + "uniform sampler2D sampler0;\nuniform vec4 u_setting;\n"
                + "varying vec2 v_texcoord0;\n"
                + "void main() { gl_FragColor = texture2D(sampler0,v_texcoord0); }\n");
        java.util.List<CustomShaderStage> stages = Arrays.asList(
                new CustomShaderStage("legacy/one.fsh", 2, null, "legacy/one.vsh",
                        Arrays.asList(0.1f, 0.2f, 0.3f, 0.4f), "Old CRT"),
                new CustomShaderStage("legacy/one.fsh", 2, false, "legacy/one.vsh",
                        Arrays.asList(0.7f, 0.8f, 0.9f, 1.0f), "Old CRT"));
        GlslpPreset chain = GlslpAssets.loadCustom(stages,
                shaderRoot.toFile(), null, "legacy");
        assertEquals(2, chain.passes.size());
        assertTrue(chain.passes.get(0).sourceText.contains("#define sampler0 Texture"));
        assertTrue(chain.passes.get(0).sourceText.contains("#define a_position VertexCoord"));
        assertEquals(0.2f, chain.passes.get(0).legacyValues.get(1));
        assertEquals(0.8f, chain.passes.get(1).legacyValues.get(1));
        assertEquals(64, chain.plan(32, 32, 128, 128, 4096,
                64L * 1024 * 1024).passes.get(0).outputWidth);
        assertThrows(IOException.class, () -> GlslpAssets.loadCustom(stages,
                shaderRoot.toFile(), null, "glsl"));
    }


    @Test void legacyCustomResolvesSourcesMovedByEarlierAppVersions() throws Exception {
        source("shaders_legacy/old.vsh",
                "attribute vec4 a_position; attribute vec2 a_texcoord0; "
                + "void main() { gl_Position = a_position; }");
        source("shaders_legacy/old.fsh",
                "precision mediump float; uniform sampler2D sampler0;"
                + "void main() { gl_FragColor = texture2D(sampler0, vec2(0.5)); }");
        GlslpPreset chain = GlslpAssets.loadCustom(Collections.singletonList(
                new CustomShaderStage("old.fsh", 1, null, "old.vsh",
                        Arrays.asList(1f, 2f, 3f, 4f), "Original")),
                shaderRoot.toFile(), null, "legacy");
        assertEquals(1, chain.passes.size());
        assertTrue(chain.passes.get(0).sourceText.contains("#define sampler0 Texture"));
    }

}
