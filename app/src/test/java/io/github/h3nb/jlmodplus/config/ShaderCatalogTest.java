// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ShaderCatalogTest {
    @TempDir Path temporary;

    @Test void readsLegacyWithoutMovingUserFiles() throws Exception {
        File root = temporary.resolve("shaders").toFile();
        assertTrue(root.mkdir());
        Files.write(new File(root, "pixel.ini").toPath(),
                ("[Pixel]\nVertex = pixel.vsh\nFragment = pixel.fsh\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "pixel.vsh").toPath(), "vertex".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "pixel.fsh").toPath(), "fragment".getBytes(StandardCharsets.UTF_8));

        List<ShaderInfo> catalog = ShaderCatalog.scan(root);
        assertEquals(1, catalog.size());
        assertEquals("Pixel", catalog.get(0).toString());
        assertTrue(new File(root, "pixel.ini").isFile());
        assertFalse(new File(root, "shaders_legacy/pixel.ini").exists());
        assertEquals(new File(root, "pixel.fsh").getCanonicalFile(),
                ShaderCatalog.resolveLegacySource(root, "pixel.fsh"));
        assertEquals(1, ShaderCatalog.scan(root).size());
    }

    @Test void retainsLiteralFileNamesInShaderPaths() throws Exception {
        File root = temporary.resolve("name-test").toFile();
        File folder = new File(root, "shaders_glsl");
        assertTrue(folder.mkdirs());
        Files.write(new File(folder, "soft blur.glsl").toPath(),
                "uniform sampler2D Texture;".getBytes(StandardCharsets.UTF_8));
        List<ShaderInfo> catalog = ShaderCatalog.scan(root);
        assertEquals(1, catalog.size());
        assertEquals("shaders_glsl/soft blur.glsl", catalog.get(0).preset);
    }

    @Test void discoversNestedGlslpWithoutOfferingItsPassFiles() throws Exception {
        File root = temporary.resolve("shaders").toFile();
        File nested = new File(root, "shaders_glsl/xbrz/shaders");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "pass.glsl").toPath(), "pass".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(nested.getParentFile(), "crisp.glslp").toPath(),
                "shaders = 1\nshader0 = shaders/pass.glsl\n".getBytes(StandardCharsets.UTF_8));

        List<ShaderInfo> catalog = ShaderCatalog.scan(root);
        assertEquals(1, catalog.size());
        assertEquals("shaders_glsl/xbrz/crisp.glslp", catalog.get(0).preset);
    }

    @Test void selectedLegacyIniExposesAllVariantsAndKeepsNestedPaths() throws Exception {
        File root = temporary.resolve("nested-shaders").toFile();
        File sub = new File(root, "shaders_legacy/custom");
        assertTrue(sub.mkdirs());
        Files.write(new File(sub, "effects.ini").toPath(),
                ("[Warm]\nVertex = common.vsh\nFragment = warm.fsh\n"
                        + "[Cool]\nVertex = common.vsh\nFragment = cool.fsh\n")
                        .getBytes(StandardCharsets.UTF_8));
        for (String name : List.of("common.vsh", "warm.fsh", "cool.fsh")) {
            Files.write(new File(sub, name).toPath(), "source".getBytes(StandardCharsets.UTF_8));
        }
        List<ShaderInfo> entries = ShaderCatalog.readLegacyFile(root, "shaders_legacy/custom/effects.ini");
        assertEquals(List.of("Warm", "Cool"), entries.stream().map(ShaderInfo::toString).toList());
        assertEquals("shaders_legacy/custom/common.vsh", entries.get(0).vertex);
        assertEquals("shaders_legacy/custom/cool.fsh", entries.get(1).fragment);
        assertEquals(2, ShaderCatalog.readLegacyFile(root, "shaders_legacy/custom/effects.ini").size());
        // A saved nested selection must be rediscovered to recover its tuning metadata.
        List<ShaderInfo> scanned = ShaderCatalog.scan(root);
        assertEquals(2, scanned.size());
        assertEquals("shaders_legacy/custom/common.vsh", scanned.get(0).vertex);
        assertEquals("shaders_legacy/custom/cool.fsh", scanned.get(1).fragment);

        assertThrows(java.io.IOException.class,
                () -> ShaderCatalog.readLegacyFile(root, "../outside.ini"));
        assertThrows(java.io.IOException.class,
                () -> ShaderCatalog.readLegacyFile(root, "shaders_legacy/custom/common.vsh"));
    }
}
