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
}
