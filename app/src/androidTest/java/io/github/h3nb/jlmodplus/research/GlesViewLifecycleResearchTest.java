// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.lcdui.graphics.GlesView;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * R&D-only real Android GLSurfaceView lifecycle with the app's GlesView,
 * real pinned Libretro GLSL, and offscreen passes.
 *
 * It deliberately does not instantiate a MIDlet/Canvas.GLRenderer; that
 * integration remains a distinct gate. No sleeps, no production toggles.
 */
@RunWith(AndroidJUnit4.class)
public final class GlesViewLifecycleResearchTest {
    private static final String ASSETS = "graphics-rd/";
    private static final long TIMEOUT_SECONDS = 25L;

    /** Registered only in the androidTest manifest, never the installed app. */
    public static final class HostActivity extends Activity {
        private FrameLayout root;
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            root = new FrameLayout(this);
            setContentView(root);
        }
        void install(GlesView view, int w, int h) {
            root.removeAllViews();
            root.addView(view, new FrameLayout.LayoutParams(w, h));
        }
        void resize(GlesView view, int w, int h) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = w;
            params.height = h;
            view.setLayoutParams(params);
        }
    }

    @Test public void appGlesViewRebuildsRealMultipassOnResumeAndResize() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getContext(), HostActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        HostActivity activity = (HostActivity) instrumentation.startActivitySync(intent);
        final GlesView[] holder = new GlesView[1];
        final ProbeRenderer renderer = new ProbeRenderer();
        try {
            instrumentation.runOnMainSync(() -> {
                GlesView view = new GlesView(activity);
                view.setPreserveEGLContextOnPause(false);
                view.setRenderer(renderer);
                view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                activity.install(view, 128, 96);
                holder[0] = view;
            });
            Frame first = nextFrame(renderer);
            assertEquals(128, first.width);
            assertEquals(96, first.height);
            assertTrue(first.surfaceGeneration > 0);
            assertTrue(first.red > 0 && first.blue > 0);
            assertEquals(Color.BLUE, first.hostBackground);
            assertEquals(GLES20.GL_NO_ERROR, first.glError);

            instrumentation.runOnMainSync(() -> holder[0].requestRender());
            Frame repeat = nextFrame(renderer);
            assertEquals(first.surfaceGeneration, repeat.surfaceGeneration);
            assertEquals(first.red, repeat.red);
            assertEquals(first.blue, repeat.blue);

            instrumentation.runOnMainSync(() -> {
                activity.resize(holder[0], 160, 120);
                holder[0].requestRender();
            });
            Frame resized = nextFrameMatching(renderer, 160, 120);
            assertEquals(160, resized.width);
            assertEquals(120, resized.height);
            assertEquals(Color.BLUE, resized.hostBackground);
            assertEquals(GLES20.GL_NO_ERROR, resized.glError);

            int beforePause = renderer.created.get();
            instrumentation.runOnMainSync(() -> holder[0].onPause());
            instrumentation.runOnMainSync(() -> {
                holder[0].onResume();
                holder[0].requestRender();
            });
            Frame resumed = nextFrameAfterGeneration(renderer, beforePause);
            assertTrue("GLSurfaceView must recreate unpreserved context",
                    resumed.surfaceGeneration > beforePause);
            assertEquals(160, resumed.width);
            assertEquals(120, resumed.height);
            assertTrue(resumed.red > 0 && resumed.blue > 0);
            assertEquals(Color.BLUE, resumed.hostBackground);
            assertEquals(GLES20.GL_NO_ERROR, resumed.glError);
            Log.i("GraphicsRD_Surface", "GLSurface lifecycle frames verified: initial, repeat, resize, context recreation");
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (holder[0] != null) {
                    holder[0].onPause();
                    activity.install(holder[0], 1, 1); // detaches prior surface without orphaning GL thread
                }
                activity.finish();
            });
        }
    }

    private static Frame nextFrame(ProbeRenderer renderer) throws InterruptedException {
        Frame f = renderer.frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No GLES frame produced on attached GlesView", f);
        if (f.failure != null) throw new AssertionError("GL thread failed", f.failure);
        return f;
    }
    private static Frame nextFrameMatching(ProbeRenderer renderer, int w, int h)
            throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            Frame f = nextFrame(renderer);
            if (f.width == w && f.height == h) return f;
        }
        throw new AssertionError("Resized viewport not observed");
    }
    private static Frame nextFrameAfterGeneration(ProbeRenderer renderer, int generation)
            throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            Frame f = nextFrame(renderer);
            if (f.surfaceGeneration > generation) return f;
        }
        throw new AssertionError("No frame after EGL context recreation");
    }

    private static final class Frame {
        int width, height, surfaceGeneration, red, blue, hostBackground, glError;
        Throwable failure;
    }

    private static final class ProbeRenderer implements GLSurfaceView.Renderer {
        final AtomicInteger created = new AtomicInteger();
        final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>(8);
        int surfaceW, surfaceH;
        int normalizeProgram, horizontalProgram, verticalProgram, presentationProgram;
        int source, normalized, horizontal, vertical;
        int[] framebuffers = new int[3];

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            created.incrementAndGet();
            try {
                String stock = read("stock.glsl");
                String h = read("blurs/shaders/gauss_4tap/gauss_2tap_h.glsl");
                String v = read("blurs/shaders/gauss_4tap/gauss_2tap_v.glsl");
                normalizeProgram = program(stock);
                horizontalProgram = program(h);
                verticalProgram = program(v);
                presentationProgram = program(stock);
                int[] pixels = new int[16];
                for (int i = 0; i < 16; i++) {
                    int x = i % 4, y = i / 4;
                    int strength = x == 0 ? 255 : 128;
                    pixels[i] = x < 2 ? (y < 2 ? Color.rgb(strength, 0, 0) :
                            Color.rgb(0, 0, strength)) : Color.GREEN;
                }
                Bitmap image = Bitmap.createBitmap(pixels, 4, 4, Bitmap.Config.ARGB_8888);
                try {
                    source = texture(4, 4);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0);
                } finally {
                    image.recycle();
                }
                normalized = texture(2, 4);
                horizontal = texture(2, 4);
                vertical = texture(2, 4);
                GLES20.glGenFramebuffers(3, framebuffers, 0);
            } catch (Throwable err) {
                Frame f = new Frame();
                f.failure = err;
                frames.offer(f);
            }
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceW = width;
            surfaceH = height;
            GLES20.glViewport(0, 0, width, height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            Frame result = new Frame();
            result.width = surfaceW;
            result.height = surfaceH;
            result.surfaceGeneration = created.get();
            try {
                // Replicates the presenter's ownership boundary: offscreen
                // passes must not inherit the host scissor.
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                pass(normalizeProgram, source, normalized, framebuffers[0],
                        2, 4, 4, 4, 0.5f, GLES20.GL_NEAREST);
                pass(horizontalProgram, normalized, horizontal, framebuffers[1],
                        2, 4, 2, 4, 1f, GLES20.GL_LINEAR);
                pass(verticalProgram, horizontal, vertical, framebuffers[2],
                        2, 4, 2, 4, 1f, GLES20.GL_LINEAR);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glViewport(0, 0, surfaceW, surfaceH);
                GLES20.glClearColor(0f, 0f, 1f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                int margin = 8;
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(margin, margin, surfaceW - 2 * margin, surfaceH - 2 * margin);
                render(presentationProgram, vertical, 0, 0, surfaceW, surfaceH,
                        2, 4, 2, 4, 1f, GLES20.GL_LINEAR);
                GLES20.glFinish(); // Diagnostic readback only.
                ByteBuffer center = sample(surfaceW / 2, surfaceH / 2);
                result.red = center.get(0) & 255;
                result.blue = center.get(2) & 255;
                ByteBuffer edge = sample(2, 2);
                result.hostBackground = Color.rgb(edge.get(0) & 255,
                        edge.get(1) & 255, edge.get(2) & 255);
                result.glError = GLES20.glGetError();
            } catch (Throwable e) {
                result.failure = e;
            } finally {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                frames.offer(result);
            }
        }

        private static String read(String path) throws Exception {
            try (InputStream input = InstrumentationRegistry.getInstrumentation()
                    .getContext().getAssets().open(ASSETS + path)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        private static int compile(int type, String code) {
            int id = GLES20.glCreateShader(type);
            GLES20.glShaderSource(id, code);
            GLES20.glCompileShader(id);
            int[] success = new int[1];
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, success, 0);
            if (success[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(id));
            return id;
        }
        private static int program(String source) {
            int vs = compile(GLES20.GL_VERTEX_SHADER, "#define VERTEX\n" + source);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, "#define FRAGMENT\n" + source);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] success = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, success, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (success[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(p));
            return p;
        }
        private static int texture(int width, int height) {
            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            return id[0];
        }
        private static void pass(int program, int input, int target, int framebuffer,
                                 int inputWidth, int inputHeight,
                                 int textureWidth, int textureHeight,
                                 float uMax, int filter) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete FBO");
            render(program, input, 0, 0, 2, 4, inputWidth, inputHeight,
                    textureWidth, textureHeight, uMax, filter);
        }
        private static void render(int program, int texture, int x, int y, int w, int h,
                                   int inputW, int inputH, int textureW, int textureH,
                                   float uMax, int filter) {
            FloatBuffer data = ByteBuffer.allocateDirect(4 * 8 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            data.put(new float[]{
                    -1, -1, 0, 1, 0, 0, 0, 1,
                     1, -1, 0, 1, uMax, 0, 0, 1,
                    -1,  1, 0, 1, 0, 1, 0, 1,
                     1,  1, 0, 1, uMax, 1, 0, 1
            }).rewind();
            GLES20.glViewport(x, y, w, h);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "Texture"), 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "InputSize"), inputW, inputH);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "TextureSize"), textureW, textureH);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "OutputSize"), w, h);
            float[] mvp={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "MVPMatrix"),
                    1, false, mvp, 0);
            int position = GLES20.glGetAttribLocation(program, "VertexCoord");
            int coords = GLES20.glGetAttribLocation(program, "TexCoord");
            if (position < 0 || coords < 0) throw new IllegalStateException("Missing Libretro ABI");
            GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 32, data);
            GLES20.glEnableVertexAttribArray(position);
            data.position(4);
            GLES20.glVertexAttribPointer(coords, 4, GLES20.GL_FLOAT, false, 32, data);
            GLES20.glEnableVertexAttribArray(coords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(coords);
        }
        private static ByteBuffer sample(int x, int y) {
            ByteBuffer b = ByteBuffer.allocateDirect(4);
            GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b);
            return b;
        }
    }
}
