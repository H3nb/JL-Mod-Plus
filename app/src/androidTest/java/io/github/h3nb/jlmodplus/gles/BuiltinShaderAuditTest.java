// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.h3nb.jlmodplus.config.EffectEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Temporary characterization of the *production* PostProcessRenderer on GLES2.
 * Saves actual renderer output, not synthesized golden images.
 */
@RunWith(AndroidJUnit4.class)
public class BuiltinShaderAuditTest {
    private static final int VIEW_W = 96;
    private static final int VIEW_H = 128;
    private static final int GAME_W = 24;
    private static final int GAME_H = 32;

    private enum Pattern { FLAT, CHECKER, MARKERS }

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;

    @Before
    public void createGlesContext() throws IOException {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertTrue("No EGL display", display != EGL14.EGL_NO_DISPLAY);
        int[] version = new int[2];
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
        EGLConfig[] config = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 4,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue(EGL14.eglChooseConfig(display, attrs, 0, config, 0, 1, count, 0));
        assertTrue("No EGL2 pbuffer configuration", count[0] > 0);
        context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertTrue("No GLES2 context", context != EGL14.EGL_NO_CONTEXT);
        surface = EGL14.eglCreatePbufferSurface(display, config[0],
                new int[]{EGL14.EGL_WIDTH, VIEW_W, EGL14.EGL_HEIGHT, VIEW_H,
                        EGL14.EGL_NONE}, 0);
        assertTrue("No EGL pbuffer", surface != EGL14.EGL_NO_SURFACE);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        writeText("device.txt", "GL_VENDOR=" + GLES20.glGetString(GLES20.GL_VENDOR)
                + "\nGL_RENDERER=" + GLES20.glGetString(GLES20.GL_RENDERER)
                + "\nGL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION)
                + "\nGame=" + GAME_W + "x" + GAME_H
                + "\nViewport=" + VIEW_W + "x" + VIEW_H + "\n");
    }

    @After
    public void destroyGlesContext() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    @Test
    public void allBundledEffectsCompileLinkAndDraw() throws Exception {
        for (EffectCatalog.Effect effect : EffectCatalog.all()) {
            List<EffectEntry> stack = Collections.singletonList(EffectEntry.builtin(effect.id));
            PostProcessRenderer renderer = new PostProcessRenderer(stack, null);
            try {
                int[] pixels = render(renderer, 1, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_NEAREST);
                savePng("catalog-" + effect.id + ".png", pixels);
            } finally {
                renderer.release();
            }
        }
    }

    @Test
    public void displayPatternsAreIndependentOfSourceDensity() throws Exception {
        String[] ids = {"crt-classic", "lcd", "scanlines"};
        StringBuilder diagnostic = new StringBuilder();
        for (String id : ids) {
            for (int filter : new int[]{GLES20.GL_NEAREST, GLES20.GL_LINEAR}) {
                PostProcessRenderer renderer = new PostProcessRenderer(
                        Collections.singletonList(EffectEntry.builtin(id)), null);
                try {
                    int[] baseline = render(renderer, 1, GAME_W, GAME_H, true, Pattern.FLAT, filter);
                    String label = filter == GLES20.GL_NEAREST ? "nearest" : "linear";
                    savePng("density-" + id + "-" + label + "-1x.png", baseline);
                    for (int factor : new int[]{2, 4}) {
                        int[] actual = render(renderer, factor, GAME_W, GAME_H, true, Pattern.FLAT, filter);
                        assertPixelsNear(id + " " + label + " " + factor + "x",
                                baseline, actual, 2);
                        if (factor == 4) {
                            savePng("density-" + id + "-" + label + "-4x.png", actual);
                        }
                    }
                    diagnostic.append(id).append(" ").append(label)
                            .append(": source-density invariant (tolerance 2/255)\n");
                } finally {
                    renderer.release();
                }
            }
        }
        writeText("density-results.txt", diagnostic.toString());
    }

    @Test
    public void bloomPreservesSourceAndCreatesVisibleHalo() throws Exception {
        PostProcessRenderer baseline = stack(EffectEntry.builtin("color"));
        PostProcessRenderer disabled = stack(new EffectEntry("bloom-zero", "bloom",
                null, Collections.singletonMap("uIntensity", 0f)));
        PostProcessRenderer bloom = stack(EffectEntry.builtin("bloom"));
        PostProcessRenderer chained = stack(EffectEntry.builtin("color"),
                EffectEntry.builtin("bloom"));
        try {
            int[] original = render(baseline, 1, GAME_W, GAME_H, true,
                    Pattern.MARKERS, GLES20.GL_NEAREST);
            int[] zero = render(disabled, 1, GAME_W, GAME_H, true,
                    Pattern.MARKERS, GLES20.GL_NEAREST);
            assertPixelsNear("Zero-intensity Bloom must preserve the source", original, zero, 2);

            int[] output = render(bloom, 1, GAME_W, GAME_H, true,
                    Pattern.MARKERS, GLES20.GL_NEAREST);
            int[] afterFbo = render(chained, 1, GAME_W, GAME_H, true,
                    Pattern.MARKERS, GLES20.GL_NEAREST);
            assertCorners(output, 0, 0, VIEW_W - 1, VIEW_H - 1);
            assertGlow("Bloom", original, output);
            assertGlow("Color then Bloom", original, afterFbo);
            savePng("bloom-source.png", original);
            savePng("bloom-output.png", output);
            savePng("bloom-after-color.png", afterFbo);
        } finally {
            baseline.release();
            disabled.release();
            bloom.release();
            chained.release();
        }
    }

    @Test
    public void patternedSourceSeparatesDisplayPhaseFromResampling() throws Exception {
        StringBuilder report = new StringBuilder();
        for (String id : new String[]{"color", "crt-classic", "lcd", "scanlines"}) {
            PostProcessRenderer renderer = new PostProcessRenderer(
                    Collections.singletonList(EffectEntry.builtin(id)), null);
            try {
                int[] nearest1 = render(renderer, 1, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_NEAREST);
                int[] nearest2 = render(renderer, 2, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_NEAREST);
                int[] nearest4 = render(renderer, 4, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_NEAREST);
                assertPixelsNear(id + " patterned nearest 2x", nearest1, nearest2, 2);
                assertPixelsNear(id + " patterned nearest 4x", nearest1, nearest4, 2);

                int[] linear1 = render(renderer, 1, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_LINEAR);
                int[] linear4 = render(renderer, 4, GAME_W, GAME_H, true, Pattern.CHECKER, GLES20.GL_LINEAR);
                savePng("artwork-" + id + "-nearest-1x.png", nearest1);
                savePng("artwork-" + id + "-nearest-4x.png", nearest4);
                savePng("artwork-" + id + "-linear-1x.png", linear1);
                savePng("artwork-" + id + "-linear-4x.png", linear4);
                report.append(id).append(" linear source delta (mean RGB /255): ")
                        .append(meanRgbDelta(linear1, linear4)).append("\n");
            } finally {
                renderer.release();
            }
        }
        writeText("sampling-results.txt", report.toString());
    }

    private static double meanRgbDelta(int[] first, int[] second) {
        assertEquals(first.length, second.length);
        long sum = 0;
        for (int i = 0; i < first.length; i++) {
            for (int shift : new int[]{0, 8, 16}) {
                sum += Math.abs(((first[i] >>> shift) & 255)
                        - ((second[i] >>> shift) & 255));
            }
        }
        return (double) sum / (first.length * 3);
    }

    @Test
    public void insetViewportKeepsCornersAndClampsPadding() throws Exception {
        PostProcessRenderer direct = stack(EffectEntry.builtin("crt-classic"));
        PostProcessRenderer chained = stack(EffectEntry.builtin("color"),
                EffectEntry.builtin("crt-classic"));
        try {
            for (int scale : new int[]{1, 4}) {
                int[] expected = render(direct, scale, 27, 35, true,
                        Pattern.MARKERS, GLES20.GL_NEAREST, -0.75f, 0.75f, 0.75f, -0.75f);
                int[] actual = render(chained, scale, 27, 35, true,
                        Pattern.MARKERS, GLES20.GL_NEAREST, -0.75f, 0.75f, 0.75f, -0.75f);
                assertPixelsNear("Inset direct/offscreen " + scale + "x",
                        expected, actual, 2);
                assertCorners(actual, 12, 16, 83, 111);
                assertEquals("Outside game viewport was modified", 0xff000000, actual[0]);
                if (scale == 4) savePng("inset-chained-crt.png", actual);
            }

            int[] compact = render(direct, 1, 27, 35, false,
                    Pattern.FLAT, GLES20.GL_LINEAR, -0.75f, 0.75f, 0.75f, -0.75f);
            int[] padded = render(direct, 1, 27, 35, true,
                    Pattern.FLAT, GLES20.GL_LINEAR, -0.75f, 0.75f, 0.75f, -0.75f);
            assertPixelsNear("Padding must not introduce magenta edges", compact, padded, 2);
        } finally {
            direct.release();
            chained.release();
        }
    }

    private static PostProcessRenderer stack(EffectEntry... entries) throws IOException {
        return new PostProcessRenderer(Arrays.asList(entries), null);
    }

    private int[] render(PostProcessRenderer renderer, int scale, int guestW, int guestH,
                         boolean padded, Pattern pattern, int filter) {
        return render(renderer, scale, guestW, guestH, padded, pattern, filter,
                -1f, 1f, 1f, -1f);
    }

    private int[] render(PostProcessRenderer renderer, int scale, int guestW, int guestH,
                         boolean padded, Pattern pattern, int filter,
                         float left, float right, float top, float bottom) {
        int contentW = guestW * scale;
        int contentH = guestH * scale;
        int texW = contentW + (padded ? 7 : 0);
        int texH = contentH + (padded ? 5 : 0);
        ByteBuffer source = ByteBuffer.allocateDirect(texW * texH * 4);
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                boolean outside = x >= contentW || y >= contentH;
                int gx = x / scale;
                int gy = y / scale;
                boolean light = ((gx / 4 + gy / 4) & 1) == 0;
                int r = 185, g = 185, b = 185;
                if (pattern == Pattern.CHECKER) {
                    r = light ? 202 : 48;
                    g = light ? 190 : 83;
                    b = light ? 152 : 120;
                } else if (pattern == Pattern.MARKERS) {
                    r = g = b = 12;
                    if (gx < 2 && gy < 2) {
                        r = 180; g = 40; b = 40;
                    } else if (gx >= guestW - 2 && gy < 2) {
                        r = 40; g = 180; b = 40;
                    } else if (gx < 2 && gy >= guestH - 2) {
                        r = 40; g = 40; b = 180;
                    } else if (gx >= guestW - 2 && gy >= guestH - 2) {
                        r = 150; g = 120; b = 25;
                    } else if (gx >= guestW / 3 && gx < guestW / 3 + 2
                            && gy >= guestH / 3 && gy < guestH / 3 + 2) {
                        r = g = b = 250;
                    }
                }
                if (outside) {
                    r = 255; g = 0; b = 255;
                }
                source.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
            }
        }
        source.flip();
        int[] name = new int[1];
        GLES20.glGenTextures(1, name, 0);
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    texW, texH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, source);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, VIEW_W, VIEW_H);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glScissor(0, 0, VIEW_W, VIEW_H);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            assertTrue("Renderer declined to draw", renderer.draw(name[0],
                    quad(left, right, top, bottom),
                    texW, texH, contentW, contentH, guestW, guestH,
                    VIEW_W, VIEW_H, filter));
            ByteBuffer bytes = ByteBuffer.allocateDirect(VIEW_W * VIEW_H * 4);
            GLES20.glReadPixels(0, 0, VIEW_W, VIEW_H,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes);
            assertEquals("GLES error", GLES20.GL_NO_ERROR, GLES20.glGetError());
            int[] pixels = new int[VIEW_W * VIEW_H];
            for (int y = 0; y < VIEW_H; y++) {
                for (int x = 0; x < VIEW_W; x++) {
                    int pos = 4 * (y * VIEW_W + x);
                    int r = bytes.get(pos) & 255, g = bytes.get(pos + 1) & 255;
                    int b = bytes.get(pos + 2) & 255, a = bytes.get(pos + 3) & 255;
                    pixels[(VIEW_H - 1 - y) * VIEW_W + x] =
                            (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return pixels;
        } finally {
            GLES20.glDeleteTextures(1, name, 0);
        }
    }

    private static FloatBuffer quad(float left, float right, float top, float bottom) {
        FloatBuffer q = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        q.put(new float[]{
                left, top, 0f, 0f,
                left, bottom, 0f, 1f,
                right, top, 1f, 0f,
                right, bottom, 1f, 1f
        });
        q.rewind();
        return q;
    }

    private static void assertCorners(int[] pixels, int left, int top,
                                      int right, int bottom) {
        int tl = pixels[(top + 2) * VIEW_W + left + 2];
        int tr = pixels[(top + 2) * VIEW_W + right - 2];
        int bl = pixels[(bottom - 2) * VIEW_W + left + 2];
        int br = pixels[(bottom - 2) * VIEW_W + right - 2];
        assertTrue("Top-left red marker", channel(tl, 16) > channel(tl, 8) + 60);
        assertTrue("Top-right green marker", channel(tr, 8) > channel(tr, 16) + 60);
        assertTrue("Bottom-left blue marker", channel(bl, 0) > channel(bl, 8) + 60);
        assertTrue("Bottom-right yellow marker", channel(br, 16) > channel(br, 0) + 60);
    }

    private static void assertGlow(String name, int[] source, int[] output) {
        int halo = 0;
        for (int i = 0; i < source.length; i++) {
            int base = maxRgb(source[i]);
            if (base <= 20 && maxRgb(output[i]) >= base + 3) halo++;
        }
        assertTrue(name + " produced no visible halo", halo > 5);
    }

    private static int maxRgb(int color) {
        return Math.max(channel(color, 16),
                Math.max(channel(color, 8), channel(color, 0)));
    }

    private static int channel(int argb, int shift) {
        return (argb >>> shift) & 255;
    }

    private static void assertPixelsNear(String caseName, int[] expected,
                                         int[] actual, int tolerance) {
        assertEquals(caseName + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            for (int shift : new int[]{0, 8, 16, 24}) {
                int a = (expected[i] >>> shift) & 255;
                int b = (actual[i] >>> shift) & 255;
                assertTrue(caseName + " pixel " + i + " channel " + shift
                        + " expected " + a + " actual " + b,
                        Math.abs(a - b) <= tolerance);
            }
        }
    }

    private static File auditFile(String name) {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getFilesDir(), "shader-audit");
        assertTrue("Cannot create audit directory", dir.isDirectory() || dir.mkdirs());
        return new File(dir, name);
    }

    private static void savePng(String name, int[] pixels) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888);
        try {
            bitmap.setPixels(pixels, 0, VIEW_W, 0, 0, VIEW_W, VIEW_H);
            try (FileOutputStream out = new FileOutputStream(auditFile(name))) {
                assertTrue("Cannot encode " + name, bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void writeText(String name, String value) throws IOException {
        try (FileWriter out = new FileWriter(auditFile(name))) {
            out.write(value);
        }
    }
}
