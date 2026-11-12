/*
 * Copyright 2022-2023 Yury Kharchenko
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.micro3d;

import static android.opengl.GLES20.*;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.h3nb.jlmodplus.R;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;
import java.nio.ByteBuffer;

import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;
import io.github.h3nb.jlmodplus.graphics.RgbaTextureEnhancer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;

import javax.microedition.lcdui.Image;
import javax.microedition.shell.AppClassLoader;

public final class TextureImpl {
	private EGLContext uploadedContext;
	private static volatile int enhancementMode;
	private static volatile int screenTargetScale = 1;
	private static final AtomicBoolean enhancementFailureReported = new AtomicBoolean();

	/** Optional host enhancement, never applied to guest-visible texture storage. */
	public static void configureEnhancement(int mode) {
		enhancementMode = mode == 2 || mode == 3 ? mode : 0;
	}

	static void setScreenTargetScale(int scale) {
		screenTargetScale = Math.max(1, Math.min(10, scale));
	}

	final TextureData image;
	private final boolean isMutable;
	private final boolean modelTexture;

	int mTexId = -1;
	private int uploadedMode = -1;
	private int uploadedScale = -1;

	public TextureImpl() {
		image = new TextureData(256, 256);
		isMutable = true;
		modelTexture = false;
	}

	public TextureImpl(byte[] b) {
		this(b, true);
	}

	public TextureImpl(byte[] b, boolean isForModel) {
		if (b == null) {
			throw new NullPointerException();
		}
		try {
			image = Loader.loadBmpData(b, 0, b.length, !isForModel);
		} catch (IOException e) {
			Log.e(Utils.TAG, "Error loading data", e);
			throw new RuntimeException(e);
		}
		isMutable = false;
		modelTexture = isForModel;
	}

	public TextureImpl(byte[] b, int offset, int length) throws IOException {
		if (b == null) {
			throw new NullPointerException();
		}
		if (offset < 0 || length < 0 || offset > b.length || length > b.length - offset) {
			throw new ArrayIndexOutOfBoundsException();
		}
		try {
			image = Loader.loadBmpData(b, offset, length);
		} catch (Exception e) {
			Log.e(Utils.TAG, "Error loading data", e);
			throw e;
		}
		isMutable = false;
		modelTexture = true;
	}

	public TextureImpl(String name) throws IOException {
		this(name, true);
	}

	public TextureImpl(String name, boolean isForModel) throws IOException {
		if (name == null) {
			throw new NullPointerException();
		}
		byte[] b = AppClassLoader.getResourceAsBytes(name);
		if (b == null) {
			throw new IOException();
		}
		try {
			image = Loader.loadBmpData(b, 0, b.length, !isForModel);
		} catch (IOException e) {
			Log.e(Utils.TAG, "Error loading data from [" + name + "]", e);
			throw new RuntimeException(e);
		}
		isMutable = false;
		modelTexture = isForModel;
	}

	public TextureImpl(Image image, int x, int y, int width, int height) {
		if (image == null) {
			throw new NullPointerException();
		} else if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
				x + width > image.getWidth() || y + height > image.getHeight()) {
			throw new IllegalArgumentException();
		}
		isMutable = false;
		modelTexture = true;
		this.image = new TextureData(width, height);
		int len = width * height;
		int[] pixels = new int[len];
		image.getRGB(pixels, 0, width, x, y, width, height);
		RgbaTextureEnhancer.putArgbPixels(this.image.getRaster(), pixels);
	}

	public void dispose() {
	}

	public boolean isMutable() {
		return isMutable;
	}

	int getId() {
		EGLContext currentContext = ((EGL10) EGLContext.getEGL()).eglGetCurrentContext();
		if (currentContext == null || currentContext.equals(EGL10.EGL_NO_CONTEXT)) {
			throw new IllegalStateException("Micro3D texture upload requires an EGL context");
		}
		int mode = enhancementMode;
		int targetScale = screenTargetScale;
		// GL names can be reused by unrelated textures in an unshared new context.
		if (uploadedContext == null || !uploadedContext.equals(currentContext)
				|| !glIsTexture(mTexId)) {
			generateId();
		} else if (!isMutable && uploadedMode == mode && uploadedScale == targetScale) {
			return mTexId;
		}
		loadToGL(mode, targetScale);
		uploadedContext = currentContext;
		uploadedMode = mode;
		uploadedScale = targetScale;
		return mTexId;
	}

	int getWidth() {
		return image.width;
	}

	int getHeight() {
		return image.height;
	}

	private void generateId() {
		IntBuffer textureIds = BufferUtils.createIntBuffer(1);
		glGenTextures(1, textureIds);
		Render.checkGlError("glGenTextures");
		mTexId = textureIds.get(0);
		if (mTexId == 0) {
			throw new IllegalStateException("Unable to allocate Micro3D texture");
		}
	}

	private void loadToGL(int mode, int targetScale) {
		int width = image.width;
		int height = image.height;
		ByteBuffer source = image.getRaster();
		if (!isMutable && modelTexture && mode >= 2 && targetScale > 1) {
			int[] maxTexture = new int[1];
			glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxTexture, 0);
			int factor = ImageEnhancementPolicy.chooseFactor(
					width, height, targetScale, maxTexture[0]);
			if (factor > 1 && RgbaTextureEnhancer.isOpaque(source, width, height)) {
				try {
					source = RgbaTextureEnhancer.reconstruct(source, width, height, factor, mode);
					width *= factor;
					height *= factor;
				} catch (OutOfMemoryError error) {
					source = image.getRaster();
					notifyEnhancementFailure();
				}
			}
		}
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, mTexId);
		boolean filter = Boolean.getBoolean("micro3d.v3.texture.filter");
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter ? GL_LINEAR : GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter ? GL_LINEAR : GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
				GL_RGBA, GL_UNSIGNED_BYTE, source);
		if (width != image.width || height != image.height) {
			int error = glGetError();
			if (error != GL_NO_ERROR) {
				Log.w("TextureImpl", "Enhanced texture upload failed: GL error " + error);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image.width, image.height, 0,
						GL_RGBA, GL_UNSIGNED_BYTE, image.getRaster());
				notifyEnhancementFailure();
			}
		}
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private static void notifyEnhancementFailure() {
		if (!enhancementFailureReported.compareAndSet(false, true)) return;
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) activity.runOnUiThread(() ->
				activity.showRenderingNotice(R.string.render_texture_memory_reduced));
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			dispose();
		} finally {
			super.finalize();
		}
	}
}
