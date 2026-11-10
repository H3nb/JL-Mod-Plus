// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class EffectCatalogAssetsTest {
    @Test
    void prototypeCrtIdResolvesToOriginalCrtClassic() {
        assertSame(EffectCatalog.get("crt-classic"), EffectCatalog.get("crt-easymode"));
        assertNotNull(EffectCatalog.get("crt-classic"));
    }

    @Test
    void builtInEffectsHaveBundledSourcesAndValidDefaultRanges() {
        Path assets = Path.of("src/main/assets/shaders/effects");
        assertTrue(Files.isRegularFile(assets.resolve("fullscreen.vsh")));
        assertTrue(Files.isRegularFile(assets.resolve("identity.fsh")));
        HashSet<String> effectIds = new HashSet<>();
        for (EffectCatalog.Effect effect : EffectCatalog.all()) {
            assertTrue(effectIds.add(effect.id), "Duplicate effect ID: " + effect.id);
            assertFalse(effect.passes.isEmpty(), effect.id);
            assertTrue(effect.passes.size() <= 16, effect.id);
            for (EffectCatalog.Pass pass : effect.passes) {
                assertTrue(Files.isRegularFile(assets.resolve(pass.fragment)),
                        "Missing bundled shader: " + pass.fragment);
            }
            HashSet<String> parameters = new HashSet<>();
            for (EffectCatalog.Parameter parameter : effect.parameters) {
                assertTrue(parameters.add(parameter.id), effect.id + ": " + parameter.id);
                assertTrue(Float.isFinite(parameter.initial));
                assertTrue(parameter.minimum <= parameter.initial
                        && parameter.initial <= parameter.maximum,
                        effect.id + ": " + parameter.id);
                assertTrue(parameter.step > 0f, effect.id + ": " + parameter.id);
            }
        }
    }
}
