/*
 *  Copyright 2023-2024 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.nokia.mid.m3d;

import static android.opengl.GLES20.*;

import android.graphics.Bitmap;
import android.opengl.GLES10;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

// TODO: 23.01.2023 not implemented check exceptions
public class M3D {
	public static final int DEPTH_TEST = GLES10.GL_DEPTH_TEST;
	public static final int CULL_FACE = GLES10.GL_CULL_FACE;
	public static final int BACK = GLES10.GL_BACK;
	public static final int PROJECTION = GLES10.GL_PROJECTION;
	public static final int TEXTURE_COORD_ARRAY = GLES10.GL_TEXTURE_COORD_ARRAY;
	public static final int MODELVIEW = GLES10.GL_MODELVIEW;
	public static final int VERTEX_ARRAY = GLES10.GL_VERTEX_ARRAY;
	public static final int TEXTURE_2D = GLES10.GL_TEXTURE_2D;
	public static final int LUMINANCE8 = 0x8040;
	public static final int COLOR_BUFFER_BIT = GLES10.GL_COLOR_BUFFER_BIT;
	public static final int DEPTH_BUFFER_BIT = GLES10.GL_DEPTH_BUFFER_BIT;
	public static final int TRIANGLES = GLES10.GL_TRIANGLES;
	private static final float X2F = 1.0f / 65536.0f;

	private final EGL10 egl;
	private final EGLContext eglContext;
	private final EGLDisplay eglDisplay;
	private final EGLConfig eglConfig;
	private final float[] projectionMatrix = new float[16];
	private final float[] modelViewMatrix = new float[16 * 2];
	private final float[] mvpMatrix = new float[16];

	private boolean matrixValid;
	private ShaderProgram shader;
	private EGLSurface eglWindowSurface;
	private Image imageBuffer;
	private ByteBuffer pixelBuffer;
	private int width;
	private int height;
	private ByteBuffer vertexBuffer;
	private ByteBuffer indexBuffer;
	private ByteBuffer texCoordBuffer;
	private int matrixMode = MODELVIEW;
	private boolean isTexEnabled;

	public M3D() {
		projectionMatrix[0] = 1.0f;
		modelViewMatrix[0] = 1.0f;
		projectionMatrix[5] = 1.0f;
		modelViewMatrix[5] = 1.0f;
		projectionMatrix[10] = 1.0f;
		modelViewMatrix[10] = 1.0f;
		projectionMatrix[15] = 1.0f;
		modelViewMatrix[15] = 1.0f;
		egl = (EGL10) EGLContext.getEGL();
		eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
		egl.eglInitialize(eglDisplay, null);

		int EGL_OPENGL_ES2_BIT = 0x0004;
		int[] configAttrs = {
				EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
				EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
				EGL10.EGL_RED_SIZE, 8,
				EGL10.EGL_GREEN_SIZE, 8,
				EGL10.EGL_BLUE_SIZE, 8,
				EGL10.EGL_ALPHA_SIZE, 8,
				EGL10.EGL_DEPTH_SIZE, 16,
				EGL10.EGL_STENCIL_SIZE, EGL10.EGL_DONT_CARE,
				EGL10.EGL_NONE
		};
		EGLConfig[] eglConfigs = new EGLConfig[1];
		egl.eglChooseConfig(eglDisplay, configAttrs, eglConfigs, 1, null);
		eglConfig = eglConfigs[0];

		int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
		int[] attrib_list = {
				EGL_CONTEXT_CLIENT_VERSION, 2,
				EGL10.EGL_NONE
		};
		eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
	}

	public static M3D createInstance() {
		return new M3D();
	}

	// TODO: 22.01.2023 parameter 'flags' not interpreted
	public synchronized void setupBuffers(int flags, int width, int height) {
		if (width != this.width || height != this.height || eglWindowSurface == null) {
			this.width = width;
			this.height = height;
			imageBuffer = new Image(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), true);
			pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());

			if (eglWindowSurface != null) {
				releaseEglContext();
				egl.eglDestroySurface(eglDisplay, eglWindowSurface);
			}

			int[] surface_attribs = {
					EGL10.EGL_WIDTH, width,
					EGL10.EGL_HEIGHT, height,
					EGL10.EGL_LARGEST_PBUFFER, 1,
					EGL10.EGL_NONE};
			eglWindowSurface = egl.eglCreatePbufferSurface(eglDisplay, eglConfig, surface_attribs);
		}
		if (shader == null) {
			bindEglContext();
			shader = new ShaderProgram();
			glVertexAttrib2f(shader.aTexCoord, -1.0f, -1.0f);
			releaseEglContext();
		}
	}

	public synchronized void removeBuffers() {
		if (eglWindowSurface != null) {
			releaseEglContext();
			egl.eglDestroySurface(eglDisplay, eglWindowSurface);
			eglWindowSurface = null;
			imageBuffer = null;
			pixelBuffer = null;
		}
	}

	public synchronized void cullFace(int mode) {
		bindEglContext();
		glCullFace(mode);
		releaseEglContext();
	}

	public synchronized void viewport(int x, int y, int w, int h) {
		bindEglContext();
		glViewport(x, y, w, h);
		releaseEglContext();
	}

	public synchronized void clear(int mask) {
		bindEglContext();
		glClear(mask);
		releaseEglContext();
	}

	public synchronized void matrixMode(int mode) {
		this.matrixMode = mode;
	}

	public synchronized void loadIdentity() {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		Matrix.setIdentityM(matrix, 0);
		matrixValid = false;
	}

	public synchronized void frustumxi(int left, int right, int bottom, int top, int near, int far) {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		Matrix.frustumM(matrix, 0, left * X2F, right * X2F, bottom * X2F, top * X2F, near * X2F, far * X2F);
		matrixValid = false;
	}

	public synchronized void scalexi(int x, int y, int z) {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		Matrix.scaleM(matrix, 0, x * X2F, y * X2F, z * X2F);
		matrixValid = false;
	}

	public synchronized void translatexi(int x, int y, int z) {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		Matrix.translateM(matrix, 0, x * X2F, y * X2F, z * X2F);
		matrixValid = false;
	}

	public synchronized void rotatexi(int angle, int x, int y, int z) {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		Matrix.rotateM(matrix, 0, angle * X2F, x * X2F, y * X2F, z * X2F);
		matrixValid = false;
	}

	public synchronized void pushMatrix() {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		System.arraycopy(matrix, 0, matrix, 16, matrix.length - 16);
	}

	public synchronized void popMatrix() {
		float[] matrix = matrixMode == PROJECTION ? projectionMatrix : modelViewMatrix;
		System.arraycopy(matrix, 16, matrix, 0, matrix.length - 16);
		matrixValid = false;
	}

	public synchronized void color4ub(byte r, byte g, byte b, byte a) {
		bindEglContext();
		glUniform4f(shader.uColor, normalize(r), normalize(g), normalize(b), normalize(a));
		releaseEglContext();
	}

	public synchronized void clearColor4ub(byte r, byte g, byte b, byte a) {
		bindEglContext();
		glClearColor(normalize(r), normalize(g), normalize(b), normalize(a));
		releaseEglContext();
	}

	public synchronized void vertexPointerub(int size, int stride, byte[] vertices) {
		vertexBuffer = getBuffer(vertexBuffer, vertices);
		bindEglContext();
		glVertexAttribPointer(shader.aPosition, size, GL_BYTE, false, stride, vertexBuffer);
		releaseEglContext();
	}

	public synchronized void drawElementsub(int mode, int count, byte[] indices) {
		indexBuffer = getBuffer(indexBuffer, indices);
		bindEglContext();
		if (!matrixValid) {
			Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);
			glUniformMatrix4fv(shader.uMatrix, 1, false, mvpMatrix, 0);
			matrixValid = true;
		}
		glDrawElements(mode, count, GL_UNSIGNED_BYTE, indexBuffer);
		releaseEglContext();
	}

	public synchronized void drawArrays(int mode, int first, int count) {
		bindEglContext();
		if (!matrixValid) {
			Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);
			glUniformMatrix4fv(shader.uMatrix, 1, false, mvpMatrix, 0);
			matrixValid = true;
		}
		glDrawArrays(mode, first, count);
		releaseEglContext();
	}

	public synchronized void bindTexture(int target, Texture texture) {
		bindEglContext();
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(target, texture.glId());
		glUniform1i(shader.uTextureUnit, 0);
		releaseEglContext();
	}

	public synchronized void texCoordPointerub(int size, int stride, byte[] uvs) {
		texCoordBuffer = getBuffer(texCoordBuffer, uvs);
		bindEglContext();
		glVertexAttribPointer(shader.aTexCoord, size, GL_BYTE, false, stride, texCoordBuffer);
		releaseEglContext();
	}

	public synchronized void enableClientState(int array) {
		int index;
		if (array == TEXTURE_COORD_ARRAY) {
			if (!isTexEnabled) {
				return;
			}
			index = shader.aTexCoord;
		} else if (array == VERTEX_ARRAY) {
			index = shader.aPosition;
		} else {
			throw new IllegalArgumentException();
		}
		bindEglContext();
		glEnableVertexAttribArray(index);
		releaseEglContext();
	}

	public synchronized void disableClientState(int array) {
		int index;
		if (array == TEXTURE_COORD_ARRAY) {
			index = shader.aTexCoord;
		} else if (array == VERTEX_ARRAY) {
			index = shader.aPosition;
		} else {
			throw new IllegalArgumentException();
		}
		bindEglContext();
		glDisableVertexAttribArray(index);
		releaseEglContext();
	}

	public synchronized void enable(int cap) {
		if (cap == TEXTURE_2D) {
			isTexEnabled = true;
			return;
		}
		bindEglContext();
		glEnable(cap);
		releaseEglContext();
	}

	public synchronized void disable(int cap) {
		if (cap == TEXTURE_2D) {
			isTexEnabled = false;
			return;
		}
		bindEglContext();
		glDisable(cap);
		releaseEglContext();
	}

	public synchronized void blit(Graphics g, int x, int y, int w, int h) {
		if (pixelBuffer == null || w <= 0 || h <= 0) {
			return;
		}
		bindEglContext();
		glFinish();
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer.rewind());
		imageBuffer.getBitmap().copyPixelsFromBuffer(pixelBuffer.rewind());
		g.drawRegion(imageBuffer, x, y, w, h, Sprite.TRANS_MIRROR_ROT180, x, y, 0);
		releaseEglContext();
	}

	private void bindEglContext() {
		egl.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
	}

	private static ByteBuffer getBuffer(ByteBuffer buffer, byte[] data) {
		if (buffer == null || buffer.capacity() < data.length) {
			buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
		}

		buffer.rewind();
		buffer.put(data);
		buffer.rewind();
		return buffer;
	}

	private static float normalize(byte v) {
		return (v & 0xFF) / 255.0f;
	}

	private void releaseEglContext() {
		egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
	}
}
