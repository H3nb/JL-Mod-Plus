// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static android.opengl.GLES20.*;
import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import io.github.h3nb.jlmodplus.config.CustomShaderStage;
import java.util.Arrays;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileOutputStream;
import javax.microedition.util.ContextHolder;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs the application executor against independent test-only Libretro fixtures. */
@RunWith(AndroidJUnit4.class)
public final class GlslpGpuSmokeTest {
    private static final int SURFACE = 256;
    private static final int SOURCE = 32;


    @Test public void legacyCustomPassesReadPredecessorAndEachStageParameters() throws Exception {
        File root = new File(ContextHolder.getAppContext().getCacheDir(),
                "glslp-legacy-test-" + System.nanoTime());
        File folder = new File(root, "shaders_legacy");
        assertTrue(folder.mkdirs());
        File vertex = new File(folder, "pass.vsh");
        File first = new File(folder, "first.fsh");
        File second = new File(folder, "second.fsh");
        try {
            String vertexSource = "attribute vec4 a_position;\n"
                    + "attribute vec2 a_texcoord0;\nvarying vec2 v_texcoord0;\n"
                    + "void main(){ v_texcoord0=a_texcoord0; gl_Position=a_position; }\n";
            String firstSource = "precision mediump float;\nuniform vec4 u_setting;\n"
                    + "void main(){gl_FragColor=vec4(u_setting.r,0.0,0.0,1.0);}\n";
            String secondSource = "precision mediump float;\nuniform sampler2D sampler0;\n"
                    + "uniform vec4 u_setting;\nvarying vec2 v_texcoord0;\n"
                    + "void main(){vec4 c=texture2D(sampler0,v_texcoord0);"
                    + "gl_FragColor=vec4(c.r*u_setting.g,0.0,0.0,1.0);}\n";
            try (FileOutputStream out = new FileOutputStream(vertex)) {
                out.write(vertexSource.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream out = new FileOutputStream(first)) {
                out.write(firstSource.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream out = new FileOutputStream(second)) {
                out.write(secondSource.getBytes(StandardCharsets.UTF_8));
            }
            GlslpPreset preset = GlslpAssets.loadCustom(Arrays.asList(
                    new CustomShaderStage("shaders_legacy/first.fsh", 1, null,
                            "shaders_legacy/pass.vsh",
                            Arrays.asList(1f, 0f, 0f, 0f), "First"),
                    new CustomShaderStage("shaders_legacy/second.fsh", 1, null,
                            "shaders_legacy/pass.vsh",
                            Arrays.asList(0f, 1f, 0f, 0f), "Second")),
                    root, null, "legacy");
            execute(preset, 2, path -> { throw new IOException("No LUT expected"); },
                    Color.RED);
        } finally {
            assertTrue(first.delete());
            assertTrue(second.delete());
            assertTrue(vertex.delete());
            assertTrue(folder.delete());
            assertTrue(root.delete());
        }
    }

    @Test public void customExternalChainSamplesItsPredecessor() throws Exception {
        File root = new File(ContextHolder.getAppContext().getCacheDir(),
                "glslp-custom-" + System.nanoTime());
        File folder = new File(root, "shaders_glsl");
        assertTrue(folder.mkdirs());
        File first = new File(folder, "red.glsl");
        File second = new File(folder, "sample.glsl");
        try {
            try (FileOutputStream out = new FileOutputStream(first)) {
                out.write(colorPass("void main(){gl_FragColor=vec4(1.0,0.0,0.0,1.0);}\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream out = new FileOutputStream(second)) {
                out.write(colorPass("uniform sampler2D Texture;\n"
                        + "void main(){vec4 c=texture2D(Texture,uv);"
                        + "gl_FragColor=vec4(c.r,0.0,0.0,1.0);}\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            GlslpPreset preset = GlslpAssets.loadCustom(Arrays.asList(
                    new CustomShaderStage("shaders_glsl/red.glsl", 2, false),
                    new CustomShaderStage("shaders_glsl/sample.glsl", 1, false)),
                    root);
            execute(preset, 2, path -> { throw new IOException("No LUT expected"); }, Color.RED);
        } finally {
            assertTrue(first.delete());
            assertTrue(second.delete());
            assertTrue(folder.delete());
            assertTrue(root.delete());
        }
    }

    @Test public void fsrEasuAndRcas() throws Exception {
        execute("builtin:libretro:fsr", 2);
    }

    @Test public void xbrzCrispWithOriginalFrameInput() throws Exception {
        execute("builtin:libretro:xbrz-crisp", 2);
    }

    @Test public void xbrzFixed4xWithFinalUpscaleBlit() throws Exception {
        execute("builtin:libretro:xbrz-4x", 2);
    }

    @Test public void eightPassOriginalAndPreviousPreviousSamplers() throws Exception {
        execute("builtin:glslp:stress8", 8);
    }

    @Test public void externalPngLookupDecodesWithoutEscapingShaderRoot() throws Exception {
        File root = new File(ContextHolder.getAppContext().getCacheDir(),
                "glslp-lut-test-" + System.nanoTime());
        assertTrue(root.mkdir());
        File png = new File(root, "color.png");
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.rgb(25, 190, 85));
            try (FileOutputStream out = new FileOutputStream(png)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            Bitmap decoded = GlslpAssets.loadTextureBitmap(
                    "shaders_glsl/example.glslp", root, "color.png");
            try {
                assertEquals(2, decoded.getWidth());
                assertEquals(2, decoded.getHeight());
                assertEquals(Color.rgb(25, 190, 85), decoded.getPixel(0, 0));
            } finally {
                decoded.recycle();
            }
            try {
                GlslpAssets.loadTextureBitmap(
                        "shaders_glsl/example.glslp", root, "../color.png");
                fail("Texture paths must remain within the shader root");
            } catch (java.io.IOException expected) {
                // A traversal must be rejected before opening a file.
            }
        } finally {
            bitmap.recycle();
            if (png.exists()) assertTrue(png.delete());
            assertTrue(root.delete());
        }
    }

    @Test public void staticLookupSamplerRendersItsImage() throws Exception {
        Map<String, String> source = new HashMap<>();
        source.put("example.glslp", "shaders = 1\nshader0 = example.glsl\n"
                + "textures = ColorLUT\nColorLUT = red.png\nColorLUT_linear = true\n");
        source.put("example.glsl", "#if defined(VERTEX)\n"
                + "attribute vec4 VertexCoord; attribute vec4 TexCoord;\n"
                + "uniform mat4 MVPMatrix; varying vec2 uv;\n"
                + "void main(){uv=TexCoord.xy;gl_Position=MVPMatrix*VertexCoord;}\n"
                + "#elif defined(FRAGMENT)\n"
                + "precision mediump float; uniform sampler2D ColorLUT; varying vec2 uv;\n"
                + "void main(){gl_FragColor=texture2D(ColorLUT,vec2(0.5,0.5));}\n"
                + "#endif\n");
        GlslpPreset preset = GlslpPreset.load(path -> {
            String text = source.get(path);
            if (text == null) throw new java.io.IOException("Missing: " + path);
            return text;
        }, "example.glslp");
        execute(preset, 1, path -> {
            assertEquals("red.png", path);
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(231, 53, 29));
            return bitmap;
        }, Color.rgb(231, 53, 29));
    }

    @Test public void namedAndNumericPassOutputsSampleEarlierStages() throws Exception {
        Map<String, String> source = new HashMap<>();
        source.put("stages.glslp", "shaders = 4\n"
                + "shader0 = red.glsl\nalias0 = FirstOutput\n"
                + "shader1 = green.glsl\nalias1 = MiddleOutput\n"
                + "shader2 = blue.glsl\nshader3 = composite.glsl\n");
        source.put("red.glsl", colorPass(
                "void main(){gl_FragColor=vec4(1.0,0.0,uv.x*0.001,1.0);}\n"));
        source.put("green.glsl", colorPass(
                "void main(){gl_FragColor=vec4(uv.x*0.001,1.0,0.0,1.0);}\n"));
        source.put("blue.glsl", colorPass(
                "void main(){gl_FragColor=vec4(0.0,uv.x*0.001,1.0,1.0);}\n"));
        source.put("composite.glsl", colorPass(
                "uniform sampler2D Pass1Texture;\n"
                + "uniform sampler2D PassPrev3Texture;\n"
                + "uniform sampler2D MiddleOutput;\n"
                + "uniform sampler2D Texture;\n"
                + "void main(){gl_FragColor=vec4("
                + "0.5*texture2D(Pass1Texture,uv).r"
                + "+0.5*texture2D(PassPrev3Texture,uv).r,"
                + "texture2D(MiddleOutput,uv).g,"
                + "texture2D(Texture,uv).b,1.0);}\n"));
        GlslpPreset preset = GlslpPreset.load(path -> {
            String text = source.get(path);
            if (text == null) throw new java.io.IOException("Missing " + path);
            return text;
        }, "stages.glslp");
        execute(preset, 4,
                path -> { throw new java.io.IOException("Unexpected LUT: " + path); },
                Color.WHITE);
    }

    @Test public void macroDeclaredSamplerMustBeBoundAfterGlslLinking() throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("macro.glslp", "shaders = 1\nshader0 = macro.glsl\n");
        sources.put("macro.glsl", colorPass(
                "#define SAMPLER_TYPE sampler2D\n"
                + "uniform SAMPLER_TYPE MysteryTexture;\n"
                + "void main(){gl_FragColor=texture2D(MysteryTexture,uv);}\n"));
        GlslpPreset unsafe = GlslpPreset.load(sources::get, "macro.glslp");
        try {
            execute(unsafe, 1,
                    path -> { throw new IOException("No LUT expected"); }, -1);
            fail("An unbound macro-declared sampler must not read texture unit zero");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage()
                    .contains("Unbound GLSLP sampler MysteryTexture"));
        }

        sources.put("macro.glsl", colorPass(
                "#define SAMPLER_TYPE sampler2D\n"
                + "uniform SAMPLER_TYPE Texture;\n"
                + "void main(){gl_FragColor=texture2D(Texture,uv);}\n"));
        GlslpPreset supported = GlslpPreset.load(sources::get, "macro.glslp");
        execute(supported, 1, path -> { throw new IOException("No LUT expected"); }, -1);
    }

    @Test public void noncausalPassOutputSamplerIsRejected() throws Exception {
        Map<String, String> source = new HashMap<>();
        source.put("bad.glslp", "shaders = 1\nshader0 = bad.glsl\n");
        source.put("bad.glsl", colorPass(
                "uniform sampler2D Pass1Texture;\n"
                + "void main(){gl_FragColor=texture2D(Pass1Texture,uv);}\n"));
        GlslpPreset preset = GlslpPreset.load(source::get, "bad.glslp");
        try {
            execute(preset, 1,
                    path -> { throw new java.io.IOException("Unexpected LUT: " + path); }, -1);
            fail("A pass must not sample its own output");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Noncausal"));
        }
    }

    @Test public void borderClampUsesTransparentBlackOrRejectsUnsupportedGpu() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("border.glslp", "shaders = 1\nshader0 = border.glsl\n"
                + "wrap_mode0 = clamp_to_border\n"
                + "textures = BorderLUT\nBorderLUT = color.png\n"
                + "BorderLUT_wrap_mode = clamp_to_border\n");
        files.put("border.glsl", colorPass("uniform sampler2D Texture;\n"
                + "uniform sampler2D BorderLUT;\n"
                + "void main(){gl_FragColor=vec4("
                + "texture2D(Texture,vec2(-0.25,0.5)).a,"
                + "texture2D(BorderLUT,vec2(-0.25,0.5)).a,1.0,1.0);}\n"));
        GlslpPreset preset = GlslpPreset.load(files::get, "border.glslp");
        GlslpPipeline.TextureLoader loader = path -> {
            Bitmap lut = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            lut.eraseColor(Color.WHITE);
            return lut;
        };
        execute(preset, 1, loader, Color.BLUE, true);
    }

    @Test public void frameCountModuloFollowsGuestFramesNotHostRedraws() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("count.glslp", "shaders = 1\nshader0 = count.glsl\n"
                + "frame_count_mod0 = 2\n");
        files.put("count.glsl", colorPass("uniform int FrameCount;\n"
                + "uniform int FrameDirection;\n"
                + "void main(){gl_FragColor=vec4(float(FrameCount),"
                + "float(FrameDirection),1.0,1.0);}\n"));
        GlslpPreset preset = GlslpPreset.load(files::get, "count.glslp");
        execute(preset, 1,
                path -> { throw new java.io.IOException("Unexpected LUT: " + path); },
                Color.WHITE, false, Color.CYAN);
    }

    @Test public void floatFramebufferPreservesValuesAboveOneOrRejectsUnsupportedGpu()
            throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("float.glslp", "shaders = 2\n"
                + "shader0 = high.glsl\nfloat_framebuffer0 = true\n"
                + "scale_type0 = source\nscale0 = 1\n"
                + "shader1 = reduce.glsl\nfilter_linear1 = false\n");
        files.put("high.glsl", colorPass(
                "void main(){gl_FragColor=vec4(1.5,0.5,0.25,1.0);}\n"));
        files.put("reduce.glsl", colorPass("uniform sampler2D Texture;\n"
                + "void main(){gl_FragColor=texture2D(Texture,uv)*0.5;}\n"));
        GlslpPreset preset = GlslpPreset.load(files::get, "float.glslp");
        execute(preset, 2, path -> {
            throw new java.io.IOException("Unexpected lookup texture");
        }, Color.rgb(191, 64, 32), false, -1, true);
    }

    private static String colorPass(String fragment) {
        return "#if defined(VERTEX)\n"
                + "attribute vec4 VertexCoord; attribute vec4 TexCoord;\n"
                + "uniform mat4 MVPMatrix; varying vec2 uv;\n"
                + "void main(){uv=TexCoord.xy;gl_Position=MVPMatrix*VertexCoord;}\n"
                + "#elif defined(FRAGMENT)\n"
                + "precision mediump float; varying vec2 uv;\n"
                + fragment + "#endif\n";
    }

    @Test public void powerOfTwoRepeatAndMirroredRepeatPreserveSampling() throws Exception {
        String fragment = "uniform sampler2D Texture;\n"
                + "void main(){gl_FragColor=texture2D(Texture,vec2(1.25,0.5));}\n";
        for (String mode : new String[]{"repeat", "mirrored_repeat"}) {
            Map<String, String> files = new HashMap<>();
            files.put("wrap.glslp", "shaders = 1\nshader0 = sample.glsl\n"
                    + "filter_linear0 = false\nwrap_mode0 = " + mode + "\n");
            files.put("sample.glsl", colorPass(fragment));
            GlslpPreset preset = GlslpPreset.load(files::get, "wrap.glslp");
            int expected = mode.equals("repeat") ? Color.rgb(40, 170, 255)
                    : Color.rgb(255, 70, 35);
            execute(preset, 1,
                    path -> { throw new java.io.IOException("Unexpected LUT: " + path); },
                    expected);
        }
    }

    @Test public void mipmapInputGeneratesValidGpuLevels() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put("mip.glslp", "shaders = 2\nshader0 = first.glsl\n"
                + "scale_type0 = source\nscale0 = 1\n"
                + "shader1 = second.glsl\nmipmap_input1 = true\n"
                + "filter_linear1 = true\n");
        String fragment = "uniform sampler2D Texture;\n"
                + "void main(){gl_FragColor=texture2D(Texture,uv);}\n";
        files.put("first.glsl", colorPass(fragment));
        files.put("second.glsl", colorPass(fragment));
        GlslpPreset preset = GlslpPreset.load(files::get, "mip.glslp");
        execute(preset, 2,
                path -> { throw new java.io.IOException("Unexpected LUT: " + path); },
                -1);
    }

    private static GlslpPreset fixturePreset(String selection) throws IOException {
        String relative;
        switch (selection) {
            case "builtin:libretro:fsr": relative = "libretro/fsr/fsr.glslp"; break;
            case "builtin:libretro:xbrz-crisp": relative = "libretro/xbrz/xbrz-crisp.glslp"; break;
            case "builtin:libretro:xbrz-4x": relative = "libretro/xbrz/4xbrz-linear.glslp"; break;
            case "builtin:glslp:stress8":
                relative = "libretro/stress/eight-pass-composition.glslp"; break;
            default: throw new IOException("Unknown GPU test fixture " + selection);
        }
        return GlslpPreset.load(path -> {
            try (InputStream stream = InstrumentationRegistry.getInstrumentation()
                    .getContext().getAssets().open("shaders/glslp/" + path);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int count;
                while ((count = stream.read(block)) != -1) out.write(block, 0, count);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }, relative);
    }

    private static void execute(String selection, int expectedPasses) throws Exception {
        execute(fixturePreset(selection), expectedPasses,
                path -> { throw new java.io.IOException("Unexpected LUT: " + path); }, -1);
    }

    private static void execute(GlslpPreset preset, int expectedPasses,
            GlslpPipeline.TextureLoader images, int expectedRgb) throws Exception {
        execute(preset, expectedPasses, images, expectedRgb, false, -1);
    }

    private static void execute(GlslpPreset preset, int expectedPasses,
            GlslpPipeline.TextureLoader images, int expectedRgb,
            boolean borderCapabilityTest) throws Exception {
        execute(preset, expectedPasses, images, expectedRgb, borderCapabilityTest, -1);
    }

    private static void execute(GlslpPreset preset, int expectedPasses,
            GlslpPipeline.TextureLoader images, int expectedRgb,
            boolean borderCapabilityTest, int expectedNextRgb) throws Exception {
        execute(preset, expectedPasses, images, expectedRgb, borderCapabilityTest,
                expectedNextRgb, false);
    }

    private static void execute(GlslpPreset preset, int expectedPasses,
            GlslpPipeline.TextureLoader images, int expectedRgb,
            boolean borderCapabilityTest, int expectedNextRgb,
            boolean floatCapabilityTest) throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] number = new int[1];
        int[] attributes = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, number, 0));
        assertTrue(number[0] > 0);
        EGLContext context = EGL14.eglCreateContext(display, configs[0],
                EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, SURFACE, EGL14.EGL_HEIGHT, SURFACE,
                        EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        int[] sourceTexture = new int[1];
        Bitmap bitmap = null;
        GlslpPipeline pipeline = null;
        try {
            Log.i("GLSLP_Smoke", "GPU=" + glGetString(GL_RENDERER));
            assertEquals(expectedPasses, preset.passes.size());
            if (borderCapabilityTest && !GlslpPipeline.supportsBorderClamp(
                    glGetString(GL_VERSION), glGetString(GL_EXTENSIONS))) {
                try {
                    new GlslpPipeline(preset, GlslpAssets.stockSource(), images);
                    fail("Unsupported GPU must reject GLSLP border clamp");
                } catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage()
                            .contains("clamp_to_border requires"));
                }
                return;
            }
            if (floatCapabilityTest && !GlslpPipeline.supportsHalfFloatFramebuffer(
                    glGetString(GL_VERSION), glGetString(GL_EXTENSIONS))) {
                try {
                    new GlslpPipeline(preset, GlslpAssets.stockSource(), images);
                    fail("Unsupported GPU must reject GLSLP RGBA16F");
                } catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage()
                            .contains("float_framebuffer requires"));
                }
                return;
            }
            pipeline = new GlslpPipeline(preset, GlslpAssets.stockSource(), images);
            bitmap = Bitmap.createBitmap(SOURCE, SOURCE, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[SOURCE * SOURCE];
            for (int y = 0; y < SOURCE; y++) {
                for (int x = 0; x < SOURCE; x++) {
                    int color = (x > y) ? Color.rgb(255, 70, 35) : Color.rgb(40, 170, 255);
                    if ((x + y) % 9 == 0) color = Color.rgb(245, 245, 65);
                    pixels[y * SOURCE + x] = color;
                }
            }
            bitmap.setPixels(pixels, 0, SOURCE, 0, 0, SOURCE, SOURCE);
            glGenTextures(1, sourceTexture, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sourceTexture[0]);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
            FloatBuffer quad = quad(-1f, 1f);
            glScissor(0, 0, SURFACE, SURFACE);
            glEnable(GL_SCISSOR_TEST);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.draw(sourceTexture[0], quad, SOURCE, SOURCE, SOURCE, SOURCE,
                    SURFACE, SURFACE, 101L, GL_NEAREST);
            byte[] first = image();
            assertFilledImage("GPU preset", first);
            if (expectedRgb != -1) {
                int pixel = ((SURFACE / 2) * SURFACE + SURFACE / 2) * 4;
                assertEquals(Color.red(expectedRgb), first[pixel] & 255);
                assertEquals(Color.green(expectedRgb), first[pixel + 1] & 255);
                assertEquals(Color.blue(expectedRgb), first[pixel + 2] & 255);
            }
            // A host-only repaint must use existing intermediates and preserve pixels.
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.draw(sourceTexture[0], quad, SOURCE, SOURCE, SOURCE, SOURCE,
                    SURFACE, SURFACE, 101L, GL_NEAREST);
            assertArrayEquals("Cached frame mismatch", first, image());
            // Changing the game viewport forces viewport-sized FBOs to resize.
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.draw(sourceTexture[0], quad(-0.75f, 0.75f), SOURCE, SOURCE,
                    SOURCE, SOURCE, SURFACE, SURFACE, 102L, GL_NEAREST);
            assertEquals(GL_NO_ERROR, glGetError());
            if (expectedNextRgb != -1) {
                byte[] changed = image();
                int center = ((SURFACE / 2) * SURFACE + SURFACE / 2) * 4;
                assertEquals(Color.red(expectedNextRgb), changed[center] & 255);
                assertEquals(Color.green(expectedNextRgb), changed[center + 1] & 255);
                assertEquals(Color.blue(expectedNextRgb), changed[center + 2] & 255);
            }
            int[] currentFbo = new int[1];
            glGetIntegerv(GL_FRAMEBUFFER_BINDING, currentFbo, 0);
            assertEquals(0, currentFbo[0]);
            Log.i("GLSLP_Smoke", "Passed initial, cached and resized draws with "
                    + preset.passes.size() + " passes and " + preset.textures.size() + " LUTs");
        } finally {
            if (pipeline != null) pipeline.release();
            if (sourceTexture[0] != 0) glDeleteTextures(1, sourceTexture, 0);
            if (bitmap != null) bitmap.recycle();
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static FloatBuffer quad(float left, float right) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(new float[]{
                left, right, 0, 0,
                left, -right, 0, 1,
                right, right, 1, 0,
                right, -right, 1, 1
        });
        buffer.rewind();
        return buffer;
    }

    private static byte[] image() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(SURFACE * SURFACE * 4);
        glReadPixels(0, 0, SURFACE, SURFACE, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        byte[] pixels = new byte[buffer.capacity()];
        buffer.rewind();
        buffer.get(pixels);
        return pixels;
    }

    private static void assertFilledImage(String preset, byte[] rgba) {
        int colored = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xff) > 20 || (rgba[i + 1] & 0xff) > 20
                    || (rgba[i + 2] & 0xff) > 20) colored++;
        }
        assertTrue(preset + " produced an empty/black framebuffer", colored > SURFACE * SURFACE / 2);
    }
}
