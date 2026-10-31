// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.shell.timing.PresentationMailbox;
import javax.microedition.khronos.opengles.GL10;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Branch-local investigation of the ACTUAL Canvas.GLRenderer.
 * EGL is a controlled pbuffer (not a replacement GLSurfaceView.Renderer).
 * It exercises the real Canvas public flushBuffer -> PresentationMailbox ->
 * bufferLock -> texture upload -> legacy GLES draw path and context recreation.
 */
@RunWith(AndroidJUnit4.class)
public final class CanvasRendererPublicationResearchTest {
    private static final int W = 12, H = 12;
    private static final String TAG = "GraphicsRD_RealCanvas";
    private static final String SHADER_DIR = "graphics-rd/blurs/shaders/gauss_4tap/";

    private static final class FixtureCanvas extends Canvas {
        FixtureCanvas() { super(true); }
        FixtureCanvas(boolean fullscreen) { super(fullscreen); }
        @Override protected void paint(Graphics g) { }
    }

    @Test public void publishedFramesAndHostRedrawUseTheProductionRenderer() throws Exception {
        ProfileModel profile = ProfileModel.fresh("");
        profile.graphicsMode = 1;
        profile.screenWidth = 8;
        profile.screenHeight = 8;
        profile.screenScaleType = 1;
        profile.screenPadding = 0;
        profile.screenFilter = false;
        profile.shader = null;
        Canvas.setSettings(profile);
        // This test exercises the real GLRenderer without a running MicroActivity.
        // Normal Canvas construction continues to require MicroActivity.
        Canvas.setGraphicsResearchHeadlessHost(true);
        FixtureCanvas canvas;
        try {
            canvas = new FixtureCanvas();
        } finally {
            Canvas.setGraphicsResearchHeadlessHost(false);
        }
        Field renderField = Canvas.class.getDeclaredField("renderer");
        renderField.setAccessible(true);
        Object renderer = renderField.get(canvas);
        assertNotNull(renderer);
        Method updateSize = renderer.getClass().getDeclaredMethod("updateSize",
                float.class, float.class, float.class, float.class,
                float.class, float.class);
        updateSize.setAccessible(true);
        // Limit the game viewport to the central half: host background is
        // outside, while the guest's 8x8 Bitmap covers the interior.
        updateSize.invoke(renderer, -0.5f, 0.5f, 0.5f, -0.5f, 1f, 1f);
        Field lastUploadField = renderer.getClass().getDeclaredField("lastUploadedSequence");
        lastUploadField.setAccessible(true);
        Field mailboxField = Canvas.class.getDeclaredField("presentationMailbox");
        mailboxField.setAccessible(true);
        PresentationMailbox mailbox = (PresentationMailbox) mailboxField.get(canvas);
        mailbox.begin();
        GL10 ignoredGl = null;
        javax.microedition.khronos.egl.EGLConfig ignoredConfig = null;

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        String hSource = readSource(SHADER_DIR + "gauss_2tap_h.glsl");
        String vSource = readSource(SHADER_DIR + "gauss_2tap_v.glsl");
        Field twoPassField = renderer.getClass().getDeclaredField("researchTwoPass");
        twoPassField.setAccessible(true);
        try {
            for (int generation = 0; generation < 4; generation++) {
                // 0: legacy; 1: real Libretro multipass; 2: invalid preset
                // falls back to legacy; 3: valid preset in a new EGL context.
                if (generation == 1 || generation == 3) {
                    Canvas.setGraphicsResearchTwoPassSources(hSource, vSource);
                } else if (generation == 2) {
                    Canvas.setGraphicsResearchTwoPassSources("invalid source", vSource);
                } else {
                    Canvas.setGraphicsResearchTwoPassSources(null, null);
                }
                EGLContext context = EGL14.eglCreateContext(display, configs[0],
                        EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                        new int[]{EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
                assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
                assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
                try {
                    assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
                    android.opengl.GLSurfaceView.Renderer real =
                            (android.opengl.GLSurfaceView.Renderer) renderer;
                    real.onSurfaceCreated(ignoredGl, ignoredConfig);
                    real.onSurfaceChanged(ignoredGl, W, H);
                    LibretroTwoPassResearchPipeline twoPass =
                            (LibretroTwoPassResearchPipeline) twoPassField.get(renderer);
                    assertEquals("GLSLP selected only for valid source",
                            generation == 1 || generation == 3, twoPass != null);

                    Image red = Image.createImage(8, 8, Color.RED);
                    canvas.flushBuffer(red, 0, 0);
                    long redSequence = mailboxPublishedSequence(canvas);
                    assertTrue(redSequence > 0L);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(redSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.RED, 6, 6);
                    if (twoPass != null) assertEquals(1, twoPass.offscreenPassExecutions());
                    byte[] hostBefore = readPixel(0, 0);

                    // Host redraw without a new guest publication must reuse the
                    // uploaded texture. Renderer still draws the full host frame.
                    assertTrue(mailbox.invalidateHost(mailbox.generation()) > 0L);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(redSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.RED, 6, 6);
                    if (twoPass != null) {
                        assertEquals("Host-only redraw cannot rerun offscreen shader",
                                1, twoPass.offscreenPassExecutions());
                    }

                    Image blue = Image.createImage(8, 8, Color.BLUE);
                    canvas.flushBuffer(blue, 0, 0);
                    long blueSequence = mailboxPublishedSequence(canvas);
                    assertTrue(blueSequence > redSequence);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(blueSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.BLUE, 6, 6);
                    if (twoPass != null) {
                        assertEquals(2, twoPass.offscreenPassExecutions());

                        Image stripes = Image.createImage(8, 8);
                        Graphics graphics = stripes.getGraphics();
                        graphics.setColor(0xff0000);
                        graphics.fillRect(0, 0, 4, 8);
                        graphics.setColor(0x0000ff);
                        graphics.fillRect(4, 0, 4, 8);
                        canvas.flushBuffer(stripes, 0, 0);
                        long stripeSequence = mailboxPublishedSequence(canvas);
                        assertTrue(stripeSequence > blueSequence);
                        assertTrue(mailbox.trySchedule(mailbox.generation()));
                        real.onDrawFrame(ignoredGl);
                        assertEquals(3, twoPass.offscreenPassExecutions());
                        byte[] blurred = readPixel(5, 6);
                        assertTrue("Real Gaussian must mix a red/blue edge (R)",
                                (blurred[0] & 255) > 0);
                        assertTrue("Real Gaussian must mix a red/blue edge (B)",
                                (blurred[2] & 255) > 0);
                    }
                    assertArrayEquals("Host area must be outside the shader input",
                            hostBefore, readPixel(0, 0));
                    assertEquals("GL error after a new frame", GLES20.GL_NO_ERROR,
                            GLES20.glGetError());
                    Log.i(TAG, "generation=" + generation
                            + " published red=" + redSequence + " blue=" + blueSequence
                            + " host redraw preserved texture");
                } finally {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(display, surface);
                    EGL14.eglDestroyContext(display, context);
                }
            }
        } finally {
            Canvas.setGraphicsResearchTwoPassSources(null, null);
            mailbox.close();
            EGL14.eglTerminate(display);
        }
    }

    private static String readSource(String path) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readPixel(int x, int y) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, pixel);
        byte[] result = new byte[4];
        pixel.get(result);
        return result;
    }

        private static long mailboxPublishedSequence(FixtureCanvas canvas) throws Exception {
        Field sequenceField = Canvas.class.getDeclaredField("publishedFrameSequence");
        sequenceField.setAccessible(true);
        return sequenceField.getLong(canvas);
    }

    private static void assertColor(int expected, int x, int y) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, pixel);
        assertEquals("red", Color.red(expected), pixel.get(0) & 255);
        assertEquals("green", Color.green(expected), pixel.get(1) & 255);
        assertEquals("blue", Color.blue(expected), pixel.get(2) & 255);
    }

    @Test public void actualSoftbarBackingCanBeTightWithoutChangingGuestPixels() throws Exception {
        ProfileModel profile = ProfileModel.fresh("");
        profile.graphicsMode = 1;
        profile.screenWidth = 32;
        profile.screenHeight = 48;
        profile.screenScaleType = 1;
        profile.screenPadding = 0;
        profile.screenFilter = false;
        profile.shader = null;
        Canvas.setSettings(profile);

        String horizontal = readSource(SHADER_DIR + "gauss_2tap_h.glsl");
        String vertical = readSource(SHADER_DIR + "gauss_2tap_v.glsl");
        Canvas.setGraphicsResearchTwoPassSources(horizontal, vertical);

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        try {
            for (int scenario = 0; scenario < 3; scenario++) {
                // 0 = padded backing (must fail closed to legacy).
                // 1 = exact backing from creation (no normalization pass).
                // 2 = start padded; convert it in-place at a size update.
                boolean initialTight = scenario == 1;
                Canvas.setGraphicsResearchTightPresentationBitmap(initialTight);
                Canvas.setGraphicsResearchHeadlessHost(true);
                FixtureCanvas canvas;
                try {
                    canvas = new FixtureCanvas(false); // real softbar shrinks active height
                } finally {
                    Canvas.setGraphicsResearchHeadlessHost(false);
                }
                Field bufferField = Canvas.class.getDeclaredField("offscreenCopy");
                bufferField.setAccessible(true);
                Image backing = (Image) bufferField.get(canvas);
                assertTrue("softbar should leave an active content area", canvas.getHeight() > 0);
                assertTrue("test must actually exercise a softbar reduction",
                        canvas.getHeight() < profile.screenHeight);
                if (initialTight) {
                    assertEquals(canvas.getHeight(), backing.getBitmap().getHeight());
                } else {
                    assertTrue(backing.getBitmap().getHeight() > canvas.getHeight());
                }

                Image red = Image.createImage(canvas.getWidth(), canvas.getHeight(), Color.RED);
                PresentationMailbox mailbox = (PresentationMailbox)
                        getField(Canvas.class, "presentationMailbox").get(canvas);
                mailbox.begin();
                canvas.flushBuffer(red, 0, 0);
                if (scenario == 2) {
                    Canvas.setGraphicsResearchTightPresentationBitmap(true);
                    canvas.updateSize();
                    backing = (Image) bufferField.get(canvas);
                    assertEquals(canvas.getHeight(), backing.getBitmap().getHeight());
                    assertEquals(Color.RED, backing.getBitmap().getPixel(0, 0));
                    assertEquals(Color.RED, backing.getBitmap().getPixel(
                            canvas.getWidth() - 1, canvas.getHeight() - 1));
                }
                if (scenario == 1) {
                    assertEquals(canvas.getHeight(), backing.getBitmap().getHeight());
                }

                Object renderer = getField(Canvas.class, "renderer").get(canvas);
                assertNotNull(renderer);
                Field pipelineField = getField(renderer.getClass(), "researchTwoPass");
                Method update = renderer.getClass().getDeclaredMethod("updateSize",
                        float.class, float.class, float.class, float.class,
                        float.class, float.class);
                update.setAccessible(true);
                float th = (float) canvas.getHeight() / backing.getBitmap().getHeight();
                float tw = (float) canvas.getWidth() / backing.getBitmap().getWidth();
                update.invoke(renderer, -0.5f, 0.5f, 0.5f, -0.5f, th, tw);
                EGLContext context = EGL14.eglCreateContext(display, configs[0],
                        EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                        new int[]{EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
                assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
                assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
                try {
                    assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
                    android.opengl.GLSurfaceView.Renderer real =
                            (android.opengl.GLSurfaceView.Renderer) renderer;
                    real.onSurfaceCreated(null, null);
                    real.onSurfaceChanged(null, W, H);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(null);
                    assertColor(Color.RED, 6, 6);
                    LibretroTwoPassResearchPipeline pipeline =
                            (LibretroTwoPassResearchPipeline) pipelineField.get(renderer);
                    if (scenario == 0) {
                        assertNull("padded input must fall back rather than bleed", pipeline);
                    } else {
                        assertNotNull("tight input should use the genuine Libretro FBO", pipeline);
                        assertEquals(1, pipeline.offscreenPassExecutions());
                        assertTrue(mailbox.invalidateHost(mailbox.generation()) > 0);
                        assertTrue(mailbox.trySchedule(mailbox.generation()));
                        real.onDrawFrame(null);
                        assertEquals(1, pipeline.offscreenPassExecutions());
                    }
                    assertEquals("OpenGL error during softbar path",
                            GLES20.GL_NO_ERROR, GLES20.glGetError());
                    Log.i(TAG, "softbar scenario=" + scenario + " logical="
                            + canvas.getWidth() + "x" + canvas.getHeight()
                            + " backing=" + backing.getBitmap().getWidth()
                            + "x" + backing.getBitmap().getHeight()
                            + " multipass=" + (pipeline != null));
                } finally {
                    mailbox.close();
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(display, surface);
                    EGL14.eglDestroyContext(display, context);
                }
            }
        } finally {
            Canvas.setGraphicsResearchTwoPassSources(null, null);
            Canvas.setGraphicsResearchTightPresentationBitmap(false);
            Canvas.setGraphicsResearchHeadlessHost(false);
            EGL14.eglTerminate(display);
        }
    }

    private static Field getField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

}
