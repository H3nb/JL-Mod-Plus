// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import static org.junit.Assert.*;

/** Disposable R&D probe, not production GLSLP or renderer code. */
@RunWith(AndroidJUnit4.class)
public final class GraphicsPipelineResearchTest {
    private static final String VS =
            "attribute vec2 pos; varying vec2 uv;\n" +
            "void main(){uv=(pos+1.0)*0.5;gl_Position=vec4(pos,0.0,1.0);}";
    private static final String FS =
            "precision mediump float; uniform sampler2D tex; varying vec2 uv;\n" +
            "void main(){vec4 c=texture2D(tex,uv);gl_FragColor=vec4(c.b,c.g,c.r,c.a);}";

    @Test public void twoPassFramebufferAndIndependentViewport() {
        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, d);
        assertTrue(EGL14.eglInitialize(d, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(d, attrs, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext c = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, c);
        EGLSurface s = EGL14.eglCreatePbufferSurface(d, configs[0],
                new int[]{EGL14.EGL_WIDTH, 8, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, s);
        assertTrue(EGL14.eglMakeCurrent(d, s, s, c));
        int source = 0, intermediate = 0, fbo = 0, program = 0;
        try {
            Log.i("GraphicsRD", "GL_VENDOR " + GLES20.glGetString(GLES20.GL_VENDOR));
            Log.i("GraphicsRD", "GL_RENDERER " + GLES20.glGetString(GLES20.GL_RENDERER));
            Log.i("GraphicsRD", "GL_VERSION " + GLES20.glGetString(GLES20.GL_VERSION));
            int[] limit = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0);
            Log.i("GraphicsRD", "GL_MAX_TEXTURE_SIZE " + limit[0]);
            assertTrue(limit[0] >= 8);
            ByteBuffer red = ByteBuffer.allocateDirect(4 * 4 * 4);
            for (int i = 0; i < 16; i++)
                red.put((byte)255).put((byte)0).put((byte)0).put((byte)255);
            red.rewind();
            source = texture(red);
            intermediate = texture(null);
            program = program(VS, FS);
            int[] id = new int[1];
            GLES20.glGenFramebuffers(1, id, 0);
            fbo = id[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, intermediate, 0);
            assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            draw(program, source, 4, 4);
            pixel(0, 0, 255, 255); // red -> blue at 4x4 intermediate target
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            draw(program, intermediate, 8, 8);
            pixel(255, 0, 0, 255); // blue -> red at 8x8 presentation target
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            if (program != 0) GLES20.glDeleteProgram(program);
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            if (source != 0) GLES20.glDeleteTextures(1, new int[]{source}, 0);
            if (intermediate != 0) GLES20.glDeleteTextures(1, new int[]{intermediate}, 0);
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, c);
            EGL14.eglTerminate(d);
        }
    }

    private static int texture(ByteBuffer pixels) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 4, 4, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return id[0];
    }

    private static int compile(int type, String source) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, source);
        GLES20.glCompileShader(id);
        int[] status = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
        assertEquals(GLES20.glGetShaderInfoLog(id), 1, status[0]);
        return id;
    }

    private static int program(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
        assertEquals(GLES20.glGetProgramInfoLog(p), 1, linked[0]);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static void draw(int program, int texture, int w, int h) {
        FloatBuffer quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1,-1,1,-1,-1,1,1,1});
        quad.rewind();
        GLES20.glViewport(0, 0, w, h);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0);
        int pos = GLES20.glGetAttribLocation(program, "pos");
        assertTrue(pos >= 0);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glFinish(); // Research readback only; do not copy into production.
    }

    private static void pixel(int r, int g, int b, int a) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(2, 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        assertEquals(r, pixels.get(0) & 0xff);
        assertEquals(g, pixels.get(1) & 0xff);
        assertEquals(b, pixels.get(2) & 0xff);
        assertEquals(a, pixels.get(3) & 0xff);
    }
}
