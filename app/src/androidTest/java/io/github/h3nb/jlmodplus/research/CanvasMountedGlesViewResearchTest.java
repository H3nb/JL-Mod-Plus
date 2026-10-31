// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.graphics.GlesView;
import javax.microedition.shell.timing.PresentationMailbox;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Branch-only evidence: real Canvas.GLRenderer on a mounted Android GLSurfaceView.
 * The thin renderer delegate ONLY observes post-draw pixels; all rendering,
 * mailbox state, shader passes and GL lifecycle belong to the real Canvas.
 * This is not a MicroActivity/MIDlet fixture.
 */
@RunWith(AndroidJUnit4.class)
public final class CanvasMountedGlesViewResearchTest {
    private static final String TAG = "GraphicsRD_Mounted";
    private static final long WAIT_SECONDS = 20;

    private static final class FixtureCanvas extends Canvas {
        FixtureCanvas() { super(false); }
        @Override protected void paint(Graphics g) { }
    }

    private static final class Frame {
        int generation, surfaceW, surfaceH, logicalW, logicalH;
        int activeBackingW, activeBackingH, r, g, b, hostR, hostG, hostB;
        int firstPasses, glError;
        long uploadedSequence;
        Throwable failure;
    }

    private static final class Observer implements GLSurfaceView.Renderer {
        private final GLSurfaceView.Renderer real;
        private final FixtureCanvas canvas;
        private final Object renderer;
        private final Field uploaded, pipeline, backing, quad;
        final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>(32);
        int generation, surfaceW, surfaceH;

        Observer(FixtureCanvas canvas, Object renderer) throws Exception {
            this.real = (GLSurfaceView.Renderer) renderer;
            this.canvas = canvas;
            this.renderer = renderer;
            uploaded = field(renderer.getClass(), "lastUploadedSequence");
            pipeline = field(renderer.getClass(), "researchTwoPass");
            quad = field(renderer.getClass(), "vbo");
            backing = field(Canvas.class, "offscreenCopy");
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            generation++;
            real.onSurfaceCreated(gl, config);
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceW = width;
            surfaceH = height;
            real.onSurfaceChanged(gl, width, height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            Frame f = new Frame();
            try {
                real.onDrawFrame(gl);
                f.generation = generation;
                f.surfaceW = surfaceW;
                f.surfaceH = surfaceH;
                f.logicalW = canvas.getWidth();
                f.logicalH = canvas.getHeight();
                Image source = (Image) backing.get(canvas);
                f.activeBackingW = source.getBitmap().getWidth();
                f.activeBackingH = source.getBitmap().getHeight();
                f.uploadedSequence = uploaded.getLong(renderer);
                LibretroTwoPassResearchPipeline p =
                        (LibretroTwoPassResearchPipeline) pipeline.get(renderer);
                f.firstPasses = p == null ? -1 : p.offscreenPassExecutions();
                FloatBuffer vertices = (FloatBuffer) quad.get(renderer);
                int cx, cy;
                synchronized (vertices) {
                    float midX = (vertices.get(0) + vertices.get(8)) * 0.5f;
                    float midY = (vertices.get(1) + vertices.get(5)) * 0.5f;
                    cx = Math.max(0, Math.min(surfaceW - 1,
                            (int) ((midX + 1f) * surfaceW / 2f)));
                    cy = Math.max(0, Math.min(surfaceH - 1,
                            (int) ((midY + 1f) * surfaceH / 2f)));
                }
                ByteBuffer center = ByteBuffer.allocateDirect(4);
                GLES20.glReadPixels(cx, cy, 1, 1, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, center);
                f.r = center.get(0) & 255;
                f.g = center.get(1) & 255;
                f.b = center.get(2) & 255;
                ByteBuffer host = ByteBuffer.allocateDirect(4);
                GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, host);
                f.hostR = host.get(0) & 255;
                f.hostG = host.get(1) & 255;
                f.hostB = host.get(2) & 255;
                f.glError = GLES20.glGetError();
            } catch (Throwable error) {
                f.failure = error;
            }
            frames.offer(f);
        }
    }

    @Test public void canvasGlslSurvivesMountedViewResizeAndContextRecreation()
            throws Exception {
        ProfileModel profile = ProfileModel.fresh("");
        profile.graphicsMode = 1;
        profile.screenWidth = 32;
        profile.screenHeight = 48;
        profile.screenScaleType = 1;
        profile.screenPadding = 0;
        profile.screenFilter = false;
        profile.shader = null;
        Canvas.setSettings(profile);
        Canvas.setGraphicsResearchTightPresentationBitmap(true);
        Canvas.setGraphicsResearchTwoPassSources(
                source("blurs/shaders/gauss_4tap/gauss_2tap_h.glsl"),
                source("blurs/shaders/gauss_4tap/gauss_2tap_v.glsl"));
        Canvas.setGraphicsResearchHeadlessHost(true);
        FixtureCanvas canvas;
        try {
            canvas = new FixtureCanvas();
        } finally {
            Canvas.setGraphicsResearchHeadlessHost(false);
        }
        PresentationMailbox mailbox = (PresentationMailbox)
                field(Canvas.class, "presentationMailbox").get(canvas);
        Object renderer = field(Canvas.class, "renderer").get(canvas);
        Observer observed = new Observer(canvas, renderer);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(),
                GraphicsRdHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        GraphicsRdHostActivity activity = (GraphicsRdHostActivity)
                instrumentation.startActivitySync(intent);
        final GlesView[] mounted = new GlesView[1];
        mailbox.begin();
        try {
            instrumentation.runOnMainSync(() -> {
                GlesView view = new GlesView(activity);
                view.setPreserveEGLContextOnPause(false);
                try {
                    Method setView = renderer.getClass()
                            .getDeclaredMethod("setView", GLSurfaceView.class);
                    setView.setAccessible(true);
                    setView.invoke(renderer, view);
                    field(Canvas.class, "innerView").set(canvas, view);
                    field(Canvas.class, "displayWidth").setInt(canvas, 128);
                    field(Canvas.class, "displayHeight").setInt(canvas, 96);
                    canvas.updateSize();
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
                view.setRenderer(observed);
                view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                mounted[0] = view;
                activity.install(view, 128, 96);
            });

            Frame initial = next(observed, 1, 128, 96, -1);
            assertEquals("GL context loaded", GLES20.GL_NO_ERROR, initial.glError);

            Image red = Image.createImage(canvas.getWidth(), canvas.getHeight(), Color.RED);
            canvas.flushBuffer(red, 0, 0);
            long redSequence = (long) field(Canvas.class, "publishedFrameSequence").get(canvas);
            Frame first = next(observed, 1, 128, 96, redSequence);
            exactColor(first, Color.RED);
            assertEquals(1, first.firstPasses);
            tight(first);
            // This is the host margin, not the game texture.
            assertFalse(first.hostR == 255 && first.hostG == 0 && first.hostB == 0);

            assertTrue(mailbox.invalidateHost(mailbox.generation()) > 0);
            assertTrue(mailbox.trySchedule(mailbox.generation()));
            instrumentation.runOnMainSync(() -> mounted[0].requestRender());
            Frame host = next(observed, 1, 128, 96, redSequence);
            exactColor(host, Color.RED);
            assertEquals("Only last pass should redraw host", 1, host.firstPasses);
            assertEquals(first.hostR, host.hostR);
            assertEquals(first.hostG, host.hostG);
            assertEquals(first.hostB, host.hostB);

            instrumentation.runOnMainSync(() -> {
                activity.resize(mounted[0], 160, 120);
                try {
                    field(Canvas.class, "displayWidth").setInt(canvas, 160);
                    field(Canvas.class, "displayHeight").setInt(canvas, 120);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                canvas.updateSize(); // runs real updateSize -> GLSurfaceView.queueEvent
                mounted[0].requestRender();
            });
            Frame resizedHost = next(observed, 1, 160, 120, redSequence);
            exactColor(resizedHost, Color.RED);
            assertEquals("Host viewport alone cannot recompute guest pass",
                    1, resizedHost.firstPasses);
            tight(resizedHost);

            instrumentation.runOnMainSync(() -> {
                canvas.setFullScreenMode(true);
                mounted[0].requestRender();
            });
            Frame full = next(observed, 1, 160, 120, redSequence);
            exactColor(full, Color.RED);
            assertTrue("Fullscreen must grow active guest height",
                    full.logicalH > resizedHost.logicalH);
            tight(full);
            assertEquals(2, full.firstPasses);

            instrumentation.runOnMainSync(() -> {
                profile.screenWidth = 40;
                profile.screenHeight = 56;
                canvas.updateSize();
                mounted[0].requestRender();
            });
            Frame grown = next(observed, 1, 160, 120, redSequence);
            exactColor(grown, Color.RED);
            assertEquals(40, grown.logicalW);
            assertEquals(56, grown.logicalH);
            tight(grown);
            assertEquals(3, grown.firstPasses);

            Image blue = Image.createImage(40, 56, Color.BLUE);
            canvas.flushBuffer(blue, 0, 0);
            long blueSequence = (long) field(Canvas.class, "publishedFrameSequence").get(canvas);
            Frame blueFrame = next(observed, 1, 160, 120, blueSequence);
            exactColor(blueFrame, Color.BLUE);
            assertEquals(4, blueFrame.firstPasses);

            int beforeResumeGeneration = blueFrame.generation;
            instrumentation.runOnMainSync(() -> mounted[0].onPause());
            instrumentation.runOnMainSync(() -> {
                mounted[0].onResume();
                mounted[0].requestRender();
            });
            Frame resumed = next(observed, beforeResumeGeneration + 1, 160, 120, blueSequence);
            exactColor(resumed, Color.BLUE);
            assertEquals(1, resumed.firstPasses);
            tight(resumed);
            Log.i(TAG, "Attached GlesView real Canvas shader verified: "
                    + "host resize, guest resize, host redraw, context recreation");
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (mounted[0] != null) mounted[0].onPause();
                activity.finish();
            });
            mailbox.close();
            Canvas.setGraphicsResearchTwoPassSources(null, null);
            Canvas.setGraphicsResearchTightPresentationBitmap(false);
            Canvas.setGraphicsResearchHeadlessHost(false);
        }
    }

    private static Frame next(Observer o, int minGeneration, int width, int height,
                              long sequence) throws Exception {
        for (int attempts = 0; attempts < 12; attempts++) {
            Frame f = o.frames.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No mounted Canvas.GLRenderer frame", f);
            if (f.failure != null) throw new AssertionError("Mounted Canvas GL failed", f.failure);
            if (f.generation >= minGeneration && f.surfaceW == width && f.surfaceH == height
                    && (sequence < 0 || f.uploadedSequence == sequence)) {
                assertEquals("Mounted GL error", GLES20.GL_NO_ERROR, f.glError);
                return f;
            }
        }
        throw new AssertionError("Matching GL frame not observed");
    }

    private static void tight(Frame f) {
        assertEquals("Width must be exact source extent", f.logicalW, f.activeBackingW);
        assertEquals("Height must be exact source extent", f.logicalH, f.activeBackingH);
    }

    private static void exactColor(Frame f, int color) {
        assertEquals("red pixel", Color.red(color), f.r);
        assertEquals("green pixel", Color.green(color), f.g);
        assertEquals("blue pixel", Color.blue(color), f.b);
    }

    private static Field field(Class<?> target, String name) throws NoSuchFieldException {
        Field f = target.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static String source(String path) throws Exception {
        try (InputStream s = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("graphics-rd/" + path)) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
