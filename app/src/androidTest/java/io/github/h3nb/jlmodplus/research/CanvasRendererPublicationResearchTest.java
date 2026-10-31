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

    private static final class FixtureCanvas extends Canvas {
        FixtureCanvas() { super(true); }
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
        FixtureCanvas canvas = new FixtureCanvas();
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
        javax.microedition.khronos.opengles.egl.EGLConfig ignoredConfig = null;

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
        try {
            for (int generation = 0; generation < 2; generation++) {
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

                    Image red = Image.createImage(8, 8, Color.RED);
                    canvas.flushBuffer(red, 0, 0);
                    long redSequence = mailboxPublishedSequence(canvas);
                    assertTrue(redSequence > 0L);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(redSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.RED, 6, 6);

                    // Host redraw without a new guest publication must reuse the
                    // uploaded texture. Renderer still draws the full host frame.
                    assertTrue(mailbox.invalidateHost(mailbox.generation()) > 0L);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(redSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.RED, 6, 6);

                    Image blue = Image.createImage(8, 8, Color.BLUE);
                    canvas.flushBuffer(blue, 0, 0);
                    long blueSequence = mailboxPublishedSequence(canvas);
                    assertTrue(blueSequence > redSequence);
                    assertTrue(mailbox.trySchedule(mailbox.generation()));
                    real.onDrawFrame(ignoredGl);
                    assertEquals(blueSequence, lastUploadField.getLong(renderer));
                    assertColor(Color.BLUE, 6, 6);
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
            mailbox.close();
            EGL14.eglTerminate(display);
        }
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
}
