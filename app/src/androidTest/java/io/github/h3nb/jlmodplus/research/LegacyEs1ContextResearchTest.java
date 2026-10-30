// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/** Native GL ES 1.x compatibility probe: independent of the JNI M3G renderer.
 * An ES2-compatible GPU does not necessarily supply an ES1 fixed-function context.
 */
@RunWith(AndroidJUnit4.class)
public final class LegacyEs1ContextResearchTest {
    @Test public void fixedFunctionMatrixAndClearWorkOnExplicitEs1Context() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));

        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue("EGL config query", EGL14.eglChooseConfig(
                display, attributes, 0, configs, 0, 1, found, 0));
        assertTrue("ES1 config exists", found[0] > 0);
        EGLContext context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 1, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 8, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            int[] version = new int[1];
            assertTrue(EGL14.eglQueryContext(display, context,
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, version, 0));
            Log.i("GraphicsRD_ES1", "EGL client version=" + version[0]);
            assertEquals("Context must really be ES1", 1, version[0]);
            Log.i("GraphicsRD_ES1", "GL_VERSION=" + GLES10.glGetString(GLES10.GL_VERSION));
            Log.i("GraphicsRD_ES1", "GL_RENDERER=" + GLES10.glGetString(GLES10.GL_RENDERER));
            assertNotNull(GLES10.glGetString(GLES10.GL_VERSION));

            GLES10.glViewport(0, 0, 8, 8);
            GLES10.glMatrixMode(GLES10.GL_PROJECTION);
            GLES10.glLoadIdentity();
            GLES10.glMatrixMode(GLES10.GL_MODELVIEW);
            GLES10.glLoadIdentity();
            GLES10.glClearColor(1, 0, 0, 1);
            GLES10.glClear(GLES10.GL_COLOR_BUFFER_BIT);
            assertEquals("ES1 fixed-function errors", GLES10.GL_NO_ERROR, GLES10.glGetError());
            ByteBuffer output = ByteBuffer.allocateDirect(4);
            GLES10.glReadPixels(3, 3, 1, 1, GLES10.GL_RGBA,
                    GLES10.GL_UNSIGNED_BYTE, output);
            assertEquals(255, output.get(0) & 255);
            assertEquals(0, output.get(1) & 255);
            assertEquals(0, output.get(2) & 255);
            assertEquals(255, output.get(3) & 255);
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
