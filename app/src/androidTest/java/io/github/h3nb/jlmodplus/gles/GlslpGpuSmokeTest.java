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

/** Runs the actual application GLSLP executor against the packaged Libretro shaders. */
@RunWith(AndroidJUnit4.class)
public final class GlslpGpuSmokeTest {
    private static final int SURFACE = 256;
    private static final int SOURCE = 32;

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

    private static void execute(String selection, int expectedPasses) throws Exception {
        execute(GlslpAssets.load(selection, null), expectedPasses,
                path -> { throw new java.io.IOException("Unexpected LUT: " + path); }, -1);
    }

    private static void execute(GlslpPreset preset, int expectedPasses,
            GlslpPipeline.TextureLoader images, int expectedRgb) throws Exception {
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
