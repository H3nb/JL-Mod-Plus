// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Disposable R&D: genuine pinned Libretro stock.glsl / bilinear.glslp,
 * logical-content UV crop, filtering at the backing-texture boundary,
 * and two-pass FBO/host-scissor composition. Not a production GLSLP loader.
 */
@RunWith(AndroidJUnit4.class)
public final class RealLibretroPresetResearchTest {
    private static final String ASSETS = "graphics-rd/";
    private static final float[] IDENTITY = {
            1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1
    };

    @Test public void realStockPresetShowsCropBleedAndFboIsolatesHostState()
            throws Exception {
        Properties preset = new Properties();
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(ASSETS + "bilinear.glslp")) {
            preset.load(input);
        }
        assertEquals("1", preset.getProperty("shaders"));
        assertEquals("stock.glsl", preset.getProperty("shader0"));
        assertEquals("true", preset.getProperty("filter_linear0"));
        String shader = readAsset(ASSETS + preset.getProperty("shader0"));

        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, d);
        assertTrue(EGL14.eglInitialize(d, new int[1], 0, new int[1], 0));
        EGLConfig[] cfg = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(d, attrs, 0, cfg, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext c = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, c);
        EGLSurface s = EGL14.eglCreatePbufferSurface(d, cfg[0],
                new int[]{EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 12, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, s);
        assertTrue(EGL14.eglMakeCurrent(d, s, s, c));

        int program = 0, source = 0, packed = 0, intermediate = 0, fbo = 0;
        int blurH = 0, blurV = 0, blurOutput = 0, blurFbo = 0;
        Bitmap backing = null, cropped = null;
        try {
            Log.i("GraphicsRD", "REAL_GLSLP " + GLES20.glGetString(GLES20.GL_VERSION)
                    + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER));
            // Pinned original source, not a synthetic approximation of the Libretro ABI.
            program = link("#define VERTEX\n" + shader, "#define FRAGMENT\n" + shader);
            assertTrue(GLES20.glGetAttribLocation(program, "VertexCoord") >= 0);
            assertTrue(GLES20.glGetAttribLocation(program, "TexCoord") >= 0);
            assertTrue(GLES20.glGetUniformLocation(program, "Texture") >= 0);

            // Backing bitmap is 4x4 but only its left 2 columns are guest content.
            // The green right half represents undefined/inactive backing pixels.
            int[] colors = new int[16];
            for (int i = 0; i < colors.length; i++) {
                boolean guest = (i % 4) < 2;
                boolean upper = (i / 4) < 2;
                // Two logical columns intentionally differ in intensity:
                // horizontal blur must visibly change its own FBO output.
                int intensity = (i % 4) == 0 ? 255 : 128;
                colors[i] = guest ? (upper
                        ? 0xff000000 | (intensity << 16)
                        : 0xff000000 | intensity) : 0xff00ff00;
            }
            backing = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            backing.setPixels(colors, 0, 4, 0, 0, 4, 4);
            // Compare two genuine Android Bitmap -> GLUtils upload boundaries.
            // Cropping a Bitmap changes allocation and CPU work, not guest dimensions.
            cropped = Bitmap.createBitmap(backing, 0, 0, 2, 4);
            source = texture(4, 4, null);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, backing, 0);
            packed = texture(2, 4, null);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cropped, 0);
            intermediate = texture(2, 4, null);
            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];

            // Existing host may have scissor/padding, and its game viewport is
            // smaller than the Android surface. First draw a real bilinear preset.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, source, 2, 2, 12, 8, 0.5f, GLES20.GL_LINEAR,
                    2, 4, 4, 4);
            pixel(0, 0, 0, 0, 255);
            assertRedOrBlue(4, 3, true);
            assertRedOrBlue(4, 9, false);
            ByteBuffer edge = readPixel(13, 3);
            int leakedGreen = edge.get(1) & 255;
            Log.i("GraphicsRD", "REAL_GLSLP edgeGreen=" + leakedGreen);
            assertTrue("Bilinear border samples inactive backing pixels", leakedGreen > 0);

            // Independent 2-pass path: source-relative target (2x4), then
            // game viewport (12x8). Host scissor must not clip an offscreen FBO.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediate, 0);
            assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(1, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            draw(program, source, 0, 0, 2, 4, 0.5f, GLES20.GL_NEAREST,
                    2, 4, 4, 4);
            pixel(0, 0, 255, 0, 0);
            pixel(1, 3, 0, 0, 128);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, intermediate, 2, 2, 12, 8, 1.0f, GLES20.GL_LINEAR,
                    2, 4, 2, 4);
            assertRedOrBlue(4, 3, true);
            assertRedOrBlue(13, 3, true);
            assertRedOrBlue(4, 9, false);
            assertRedOrBlue(13, 9, false);
            pixel(0, 0, 0, 0, 255);
            // Whole-image oracle, using identical real stock.glsl and bilinear
            // filtering, not a few hand-picked pixels. The FBO route and a
            // tightly cropped Android Bitmap upload must agree on each channel.
            byte[] normalized = readRegion(2, 2, 12, 8);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, packed, 2, 2, 12, 8, 1.0f, GLES20.GL_LINEAR,
                    2, 4, 2, 4);
            assertArrayEquals("Tightly packed Bitmap and GPU-normalized inputs",
                    normalized, readRegion(2, 2, 12, 8));
            pixel(0, 0, 0, 0, 255);
            Log.i("GraphicsRD", "REAL_GLSLP packed-bitmap vs FBO: 12x8 RGBA exact match");

            // A genuine, separate 2-pass Libretro preset with independent
            // horizontal and vertical shaders. Both requested GLSLP outputs
            // remain source-relative 1x (2x4); display scaling is a later
            // host presentation operation, not a reinterpretation of pass 1.
            Properties blur = new Properties();
            try (InputStream stream = InstrumentationRegistry.getInstrumentation()
                    .getContext().getAssets().open(ASSETS + "blurs/gauss_4tap.glslp")) {
                blur.load(stream);
            }
            assertEquals("2", blur.getProperty("shaders"));
            assertEquals("source", blur.getProperty("scale_type0"));
            assertEquals("source", blur.getProperty("scale_type1"));
            assertEquals("1.0", blur.getProperty("scale0"));
            assertEquals("1.0", blur.getProperty("scale1"));
            String first = readAsset(ASSETS + "blurs/" + blur.getProperty("shader0"));
            String second = readAsset(ASSETS + "blurs/" + blur.getProperty("shader1"));
            blurH = link("#define VERTEX\n" + first, "#define FRAGMENT\n" + first);
            blurV = link("#define VERTEX\n" + second, "#define FRAGMENT\n" + second);
            blurOutput = texture(2, 4, null);
            int[] blurId = new int[1];
            GLES20.glGenFramebuffers(1, blurId, 0);
            blurFbo = blurId[0];

            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            draw(blurH, packed, 0, 0, 2, 4, 1.0f, GLES20.GL_LINEAR,
                    2, 4, 2, 4);
            ByteBuffer horizontal = readPixel(0, 0);
            int afterH = horizontal.get(0) & 255;
            assertTrue("Horizontal blur must mix unequal red columns",
                    afterH > 128 && afterH < 255);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurOutput, 0);
            assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            draw(blurV, intermediate, 0, 0, 2, 4, 1.0f, GLES20.GL_LINEAR,
                    2, 4, 2, 4);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, blurOutput, 2, 2, 12, 8, 1.0f, GLES20.GL_LINEAR,
                    2, 4, 2, 4);
            assertFalse("Real Gaussian passes must differ from unfiltered stock",
                    Arrays.equals(normalized, readRegion(2, 2, 12, 8)));
            ByteBuffer mixed = readPixel(7, 6);
            assertTrue("Vertical pass should mix red near source boundary",
                    (mixed.get(0) & 255) > 0);
            assertTrue("Vertical pass should mix blue near source boundary",
                    (mixed.get(2) & 255) > 0);
            assertEquals(0, mixed.get(1) & 255);
            pixel(0, 0, 0, 0, 255);
            Log.i("GraphicsRD", "REAL_GLSLP genuine gauss_4tap two passes and host blit completed");
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (blurH != 0) GLES20.glDeleteProgram(blurH);
            if (blurV != 0) GLES20.glDeleteProgram(blurV);
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            if (blurFbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{blurFbo}, 0);
            if (blurOutput != 0) GLES20.glDeleteTextures(1, new int[]{blurOutput}, 0);
            if (source != 0) GLES20.glDeleteTextures(1, new int[]{source}, 0);
            if (packed != 0) GLES20.glDeleteTextures(1, new int[]{packed}, 0);
            if (intermediate != 0) GLES20.glDeleteTextures(1, new int[]{intermediate}, 0);
            if (cropped != null) cropped.recycle();
            if (backing != null) backing.recycle();
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, c);
            EGL14.eglTerminate(d);
        }
    }


    /**
     * Actual upstream shader-source compatibility, separate from GLSLP preset parsing.
     * This checks both shader stages and GL linking in an Android-requested ES2 context.
     * A successfully linked program is still NOT evidence of correct drawn pixels or
     * that its parameter/previous-frame/LUT uniforms have been populated.
     */
    @Test public void representativeLibretroSourcesExposeGlesLanguageBoundary() throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] config = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue(EGL14.eglChooseConfig(display, attrs, 0, config, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, config[0],
                new int[]{EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            Log.i("GraphicsRD_Corpus", "GL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION)
                    + " GLSL=" + GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION));
            assertTrue("CRT EasyMode should be linkable with the pinned ES-compatible source",
                    probeLibretroSource("crt/shaders/crt-easymode.glsl"));
            assertTrue("LCD3x should be linkable with the pinned ES-compatible source",
                    probeLibretroSource("handheld/shaders/lcd3x.glsl"));
            // The FSR shader is deliberately diagnostic pending complete pass uniforms.
            boolean fsrPass0 = probeLibretroSource("fsr/shaders/fsr-pass0.glsl");
            Log.i("GraphicsRD_Corpus", "FSR_EASU_LINKED=" + fsrPass0
                    + " (not a runtime correctness claim)");
            // Desktop GLSL version 130 cannot be submitted unchanged to an ES context.
            // Insert stage defines AFTER the #version directive (not before), so failure
            // is caused by incompatibility instead of an invalid directive position.
            assertFalse("GLSL 130 desktop source must not be silently accepted as ES",
                    probeLibretroSource("handheld/shaders/lcd-cgwg/lcd-grid-v2.glsl"));
            assertFalse("NTSC desktop GLSL 130 requires an explicit compatibility decision",
                    probeLibretroSource("ntsc/shaders/ntsc-pass1-composite-3phase.glsl"));
            // RetroArch remaps the dialect and enables PARAMETER_UNIFORM. This
            // probe demonstrates exactly that bounded adaptation, not a
            // general desktop GLSL -> GLES source translator. The context
            // is requested as ES2, but this SwiftShader emulator advertises ES3.
            String glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION);
            if (glslVersion.startsWith("OpenGL ES GLSL ES 3.")) {
                assertTrue("LCD Grid v2 must link after GLES3 version and uniform adaptation",
                        probeLibretroSource("handheld/shaders/lcd-cgwg/lcd-grid-v2.glsl", true));
                // Capture a second independent source before implying this
                // strategy generalizes to all desktop-language shaders.
                boolean ntscMapped = probeLibretroSource(
                        "ntsc/shaders/ntsc-pass1-composite-3phase.glsl", true);
                Log.i("GraphicsRD_Corpus", "NTSC_MAPPED_GLES3_LINKED=" + ntscMapped
                        + " (visual and frame-count contracts untested)");
            } else {
                Log.i("GraphicsRD_Corpus", "GLES3 dialect probe skipped: " + glslVersion);
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static boolean probeLibretroSource(String path) throws Exception {
        return probeLibretroSource(path, false);
    }

    private static boolean probeLibretroSource(String path, boolean mapDesktop) throws Exception {
        String source = readAsset(ASSETS + path);
        int vertex = compileProbeStage(GLES20.GL_VERTEX_SHADER, source, "VERTEX", path, mapDesktop);
        int fragment = compileProbeStage(GLES20.GL_FRAGMENT_SHADER, source, "FRAGMENT", path, mapDesktop);
        int program = 0;
        boolean linked = false;
        try {
            if (vertex != 0 && fragment != 0) {
                program = GLES20.glCreateProgram();
                GLES20.glAttachShader(program, vertex);
                GLES20.glAttachShader(program, fragment);
                GLES20.glLinkProgram(program);
                int[] status = new int[1];
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
                linked = status[0] == 1;
                if (!linked) Log.i("GraphicsRD_Corpus", path + ": link: "
                        + GLES20.glGetProgramInfoLog(program));
            }
            Log.i("GraphicsRD_Corpus", path + ": vertex=" + (vertex != 0)
                    + " fragment=" + (fragment != 0) + " linked=" + linked);
            return linked;
        } finally {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (vertex != 0) GLES20.glDeleteShader(vertex);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
        }
    }

    private static int compileProbeStage(int kind, String source, String stage,
                                         String path, boolean mapDesktop) {
        String taggedSource;
        if (source.startsWith("#version ")) {
            int newline = source.indexOf('\n');
            assertTrue("Version directive must terminate on first line", newline > 0);
            String version = source.substring(0, newline);
            if (mapDesktop) {
                assertEquals("Only the pinned desktop GLSL 130 case is under test",
                        "#version 130", version);
                version = "#version 300 es";
            }
            taggedSource = version + "\n#define " + stage + "\n"
                    + (mapDesktop ? "#define PARAMETER_UNIFORM\n" : "")
                    + source.substring(newline + 1);
        } else {
            taggedSource = "#define " + stage + "\n" + source;
        }
        int shader = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(shader, taggedSource);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 1) return shader;
        Log.i("GraphicsRD_Corpus", path + ": " + stage + " compile: "
                + GLES20.glGetShaderInfoLog(shader));
        GLES20.glDeleteShader(shader);
        return 0;
    }

    private static String readAsset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int texture(int w, int h, ByteBuffer pixels) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return id[0];
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        assertEquals(GLES20.glGetShaderInfoLog(shader), 1, ok[0]);
        return shader;
    }

    private static int link(String vertex, String fragment) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        assertEquals(GLES20.glGetProgramInfoLog(p), 1, ok[0]);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static void draw(int program, int texture, int x, int y, int w, int h,
                             float rightU, int filter, int inputW, int inputH,
                             int textureW, int textureH) {
        FloatBuffer vertices = ByteBuffer.allocateDirect(4 * 8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{
                -1, -1, 0, 1, 0, 0, 0, 1,
                 1, -1, 0, 1, rightU, 0, 0, 1,
                -1,  1, 0, 1, 0, 1, 0, 1,
                 1,  1, 0, 1, rightU, 1, 0, 1
        }).rewind();
        GLES20.glViewport(x, y, w, h);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "Texture"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "MVPMatrix"),
                1, false, IDENTITY, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "InputSize"), inputW, inputH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "TextureSize"),
                textureW, textureH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "OutputSize"), w, h);
        int pos = GLES20.glGetAttribLocation(program, "VertexCoord");
        int uv = GLES20.glGetAttribLocation(program, "TexCoord");
        assertTrue(pos >= 0 && uv >= 0);
        GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 32, vertices);
        GLES20.glEnableVertexAttribArray(pos);
        vertices.position(4);
        GLES20.glVertexAttribPointer(uv, 4, GLES20.GL_FLOAT, false, 32, vertices);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glFinish(); // Research readback only; do not add to production.
    }

    private static void assertRedOrBlue(int x, int y, boolean red) {
        ByteBuffer p = readPixel(x, y);
        assertEquals("no green at " + x + "," + y, 0, p.get(1) & 255);
        assertEquals(255, p.get(3) & 255);
        if (red) {
            assertTrue("red at " + x + "," + y, (p.get(0) & 255) > 0);
            assertEquals(0, p.get(2) & 255);
        } else {
            assertEquals(0, p.get(0) & 255);
            assertTrue("blue at " + x + "," + y, (p.get(2) & 255) > 0);
        }
    }

    private static byte[] readRegion(int x, int y, int w, int h) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        byte[] pixels = new byte[w * h * 4];
        buffer.position(0);
        buffer.get(pixels);
        return pixels;
    }

    private static ByteBuffer readPixel(int x, int y) {
        ByteBuffer out = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out);
        return out;
    }

    private static void pixel(int x, int y, int r, int g, int b) {
        ByteBuffer out = readPixel(x, y);
        assertEquals("r " + x + "," + y, r, out.get(0) & 255);
        assertEquals("g " + x + "," + y, g, out.get(1) & 255);
        assertEquals("b " + x + "," + y, b, out.get(2) & 255);
        assertEquals("a " + x + "," + y, 255, out.get(3) & 255);
    }
}
