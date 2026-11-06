// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.h3nb.jlmodplus.gles.GlslpAssets;
import io.github.h3nb.jlmodplus.gles.GlslpPreset;

/** UI-only metadata; GLSL source text and GPU resources never live in Compose state. */
public final class ShaderPipelineSummary {
    public static final class Pass {
        public final String sourcePath;
        public final Boolean linearFilter;
        public final GlslpPreset.Axis x, y;

        private Pass(GlslpPreset.Pass source) {
            sourcePath = source.sourcePath;
            linearFilter = source.linearFilter;
            x = source.x;
            y = source.y;
        }
    }

    public final List<Pass> passes;
    public final List<GlslpPreset.Parameter> parameters;
    public final String error;

    private ShaderPipelineSummary(List<Pass> passes,
            List<GlslpPreset.Parameter> parameters, String error) {
        this.passes = passes;
        this.parameters = parameters;
        this.error = error;
    }

    public static ShaderPipelineSummary inspect(String presetPath, File shaderRoot) {
        try {
            return fromPreset(GlslpAssets.load(presetPath, shaderRoot));
        } catch (IOException | RuntimeException e) {
            return failed(e);
        }
    }

    public static ShaderPipelineSummary inspectCustom(
            List<CustomShaderStage> stages, File shaderRoot) {
        try {
            return fromPreset(GlslpAssets.loadCustom(stages, shaderRoot));
        } catch (IOException | RuntimeException e) {
            return failed(e);
        }
    }

    private static ShaderPipelineSummary failed(Exception error) {
        return new ShaderPipelineSummary(Collections.emptyList(), Collections.emptyList(),
                error.getMessage() == null ? "Invalid shader preset" : error.getMessage());
    }

    private static ShaderPipelineSummary fromPreset(GlslpPreset preset) {
            List<Pass> passes = new ArrayList<>(preset.passes.size());
            for (GlslpPreset.Pass pass : preset.passes) passes.add(new Pass(pass));
            try {
                return new ShaderPipelineSummary(Collections.unmodifiableList(passes),
                        preset.parameterDefinitions(), null);
            } catch (IOException e) {
                return failed(e);
            }
    }
}
