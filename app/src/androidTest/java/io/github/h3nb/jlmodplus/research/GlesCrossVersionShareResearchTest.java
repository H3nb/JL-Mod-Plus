// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.opengl.GLES20;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * R&D: concrete ES1/ES2 texture sharing capability differential.
 * Cross-version sharing is implementation-dependent; failure is valid
 * evidence, NOT a test failure. Same-version ES2 sharing is a control.
 * No production EGL lifecycle, M3G native context, or actual frame sharing.
 */
@RunWith(AndroidJUnit4.class)
public final class GlesCrossVersionShareResearchTest {
    private static final String TAG = "GraphicsRD_CrossES";

    private static EGLContext create(EGLDisplay d, EGLConfig config, int version, EGLContext share) {
        return EGL14.eglCreateContext(d, config, share,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE}, 0);
    }

    @Test public void egl1And2ContextTextureSharingIsNotAnUnconditionalContract() {
        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, d);
        assertTrue(EGL14.eglInitialize(d, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        final int ES1_BIT = 0x0001;
        int[] attrs = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, ES1_BIT | EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue("Shared EGL config for GLES1 and GLES2",
                EGL14.eglChooseConfig(d, attrs, 0, configs, 0, 1, found, 0));
        assertTrue(found[0] > 0);
        EGLSurface s = EGL14.eglCreatePbufferSurface(d, configs[0],
                new int[]{EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, s);
        EGLContext es2 = EGL14.EGL_NO_CONTEXT, es2Shared = EGL14.EGL_NO_CONTEXT;
        EGLContext es1 = EGL14.EGL_NO_CONTEXT, es1Shared = EGL14.EGL_NO_CONTEXT;
        int texture = 0;
        try {
            es2 = create(d, configs[0], 2, EGL14.EGL_NO_CONTEXT);
            assertNotEquals(EGL14.EGL_NO_CONTEXT, es2);
            assertTrue(EGL14.eglMakeCurrent(d, s, s, es2));
            String es2Version = GLES20.glGetString(GLES20.GL_VERSION);
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            assertTrue(texture != 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glFlush();

            es2Shared = create(d, configs[0], 2, es2);
            assertNotEquals("Same-version sharing is a positive control", EGL14.EGL_NO_CONTEXT, es2Shared);
            assertTrue(EGL14.eglMakeCurrent(d, s, s, es2Shared));
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            assertTrue("GLES2 context must observe GLES2 texture", GLES20.glIsTexture(texture));

            es1 = create(d, configs[0], 1, EGL14.EGL_NO_CONTEXT);
            assertNotEquals("Separate GLES1 context must exist", EGL14.EGL_NO_CONTEXT, es1);
            assertTrue(EGL14.eglMakeCurrent(d, s, s, es1));
            String es1Version = GLES10.glGetString(GLES10.GL_VERSION);

            // Do not assume the driver permits ES1/ES2 sharing: this is
            // exactly the architectural question under test.
            es1Shared = create(d, configs[0], 1, es2);
            int error = EGL14.eglGetError();
            boolean created = !EGL14.EGL_NO_CONTEXT.equals(es1Shared);
            boolean visible = false;
            if (created) {
                assertTrue(EGL14.eglMakeCurrent(d, s, s, es1Shared));
                GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, texture);
                visible = GLES10.glIsTexture(texture);
                GLES10.glFlush();
            }
            Log.i(TAG, "ES2=" + es2Version + " ES1=" + es1Version
                    + " sameVersionTexture=true crossVersionContext=" + created
                    + " crossVersionTextureVisible=" + visible
                    + " eglError=0x" + Integer.toHexString(error));
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (es1Shared != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, es1Shared);
            if (es1 != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, es1);
            if (es2Shared != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, es2Shared);
            if (es2 != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, es2);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglTerminate(d);
        }
    }
}
