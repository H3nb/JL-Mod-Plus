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
}
