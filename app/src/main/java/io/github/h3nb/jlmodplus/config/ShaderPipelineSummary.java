// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import io.github.h3nb.jlmodplus.gles.GlslpAssets;
import io.github.h3nb.jlmodplus.gles.GlslpPreset;

/** UI-only snapshot of a resolved GLSL preset; does not own textures or GL programs. */
public final class ShaderPipelineSummary {
    public final List<GlslpPreset.Pass> passes;
    public final List<GlslpPreset.Parameter> parameters;
    public final String error;

    private ShaderPipelineSummary(List<GlslpPreset.Pass> passes,
            List<GlslpPreset.Parameter> parameters, String error) {
        this.passes = passes;
        this.parameters = parameters;
        this.error = error;
    }

    public static ShaderPipelineSummary inspect(String presetPath, File shaderRoot) {
        try {
            GlslpPreset preset = GlslpAssets.load(presetPath, shaderRoot);
            return new ShaderPipelineSummary(preset.passes, preset.parameterDefinitions(), null);
        } catch (IOException | RuntimeException e) {
            return new ShaderPipelineSummary(Collections.emptyList(),
                    Collections.emptyList(), e.getMessage() == null ? "Invalid shader preset" : e.getMessage());
        }
    }
}
