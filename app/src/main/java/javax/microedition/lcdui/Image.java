/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
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

package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.lcdui.game.Sprite;
import javax.microedition.shell.AppClassLoader;

import io.github.h3nb.jlmodplus.util.PNGUtils;

public class Image {
	private final boolean mutable;
	private Bitmap bitmap;
	private Graphics graphics;
	private final Rect bounds;
	private final int pixelScale;
	private boolean isBlackWhiteAlpha;

	public Image(Bitmap bitmap, boolean mutable) {
		this(bitmap, mutable, 1);
	}

	private Image(Bitmap bitmap, boolean mutable, int pixelScale) {
		if (bitmap == null) throw new NullPointerException();
		if (pixelScale < 1) throw new IllegalArgumentException("pixelScale");
		this.mutable = mutable;
		this.bitmap = bitmap;
		this.pixelScale = pixelScale;
		bounds = new Rect(0, 0, bitmap.getWidth() / pixelScale, bitmap.getHeight() / pixelScale);
	}

	/** Runtime-owned 2D surface; ordinary MIDlet images remain at their logical size. */
	public static Image createDisplayTarget(int width, int height, int scale) {
		// Do not add any new size restriction to the existing 1x behavior.
		if (scale == 1) return createImage(width, height);
		if (scale < 1 || scale > InternalResolution.MAX_SCALE || width <= 0 || height <= 0
				|| (long) width * scale > InternalResolution.MAX_TARGET_SIDE
				|| (long) height * scale > InternalResolution.MAX_TARGET_SIDE) {
			throw new IllegalArgumentException("Invalid display target size");
		}
		Bitmap bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.WHITE);
		return new Image(bitmap, true, scale);
	}

	public int getPixelScale() {
		return pixelScale;
	}

	public Rect getPhysicalContentRect() {
		return new Rect(0, 0, bounds.right * pixelScale, bounds.bottom * pixelScale);
	}

	public Bitmap getBitmap() {
		return bitmap;
	}

	public static Image createImage(int width, int height) {
		return createImage(width, height, Color.WHITE);
	}

	public static Image createImage(int width, int height, int argb) {
		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		if (argb != 0) bitmap.eraseColor(argb);
		return new Image(bitmap, true);
	}

	public static Image createImage(String resname) throws IOException {
		Bitmap b;
		try (InputStream stream = AppClassLoader.getResourceAsStream(null, resname)) {
			if (stream == null) {
				throw new IOException("Can't read image: " + resname);
			}
			b = PNGUtils.getFixedBitmap(stream);
		}
		if (b == null) {
			throw new IOException("Can't decode image: " + resname);
		}
		return new Image(b, false);
	}

	public static Image createImage(InputStream stream) throws IOException {
		Bitmap b = PNGUtils.getFixedBitmap(stream);
		if (b == null) {
			throw new IOException("Can't decode image");
		}
		return new Image(b, false);
	}

	public static Image createImage(byte[] imageData, int imageOffset, int imageLength) {
		Bitmap b = PNGUtils.getFixedBitmap(imageData, imageOffset, imageLength);
		if (b == null) {
			throw new IllegalArgumentException("Can't decode image");
		}
		return new Image(b, false);
	}

	public static Image createImage(Image image, int x, int y, int width, int height, int transform) {
		Matrix m = transform == 0 ? null : Sprite.transformMatrix(transform, width / 2.0f, height / 2.0f);
		return new Image(Bitmap.createBitmap(image.bitmap, x, y, width, height, m, false), false);
	}

	public static Image createImage(Image source) {
		if (!source.isMutable()) {
			return source;
		}
		return new Image(source.bitmap.copy(Bitmap.Config.ARGB_8888, false), false);
	}

	public static Image createRGBImage(int[] rgb, int width, int height, boolean processAlpha) {
		if (!processAlpha) {
			final int length = width * height;
			int[] tmp = new int[length];
			for (int i = 0; i < length; i++) {
				tmp[i] = rgb[i] | 0xFF000000;
			}
			rgb = tmp;
		}
		return new Image(Bitmap.createBitmap(rgb, width, height, Bitmap.Config.ARGB_8888), false);
	}

	public Graphics getGraphics() {
		return new Graphics(this);
	}

	public boolean isMutable() {
		return mutable;
	}

	public int getWidth() {
		return bounds.right;
	}

	public int getHeight() {
		return bounds.bottom;
	}

	public void getRGB(int[] rgbData, int offset, int scanlength, int x, int y, int width, int height) {
		if (pixelScale == 1) {
			bitmap.getPixels(rgbData, offset, scanlength, x, y, width, height);
			return;
		}
		if (x < 0 || y < 0 || width < 0 || height < 0
				|| (long) x + width > getWidth() || (long) y + height > getHeight()) {
			throw new IllegalArgumentException("Logical image read outside bounds");
		}
		if (width == 0 || height == 0) return;
		// Centre samples preserve guest-sized reads without exposing physical coordinates.
		// Antialiased edges are not guaranteed pixel-identical to a separate 1x render.
		int[] row = new int[width * pixelScale];
		for (int j = 0; j < height; j++) {
			bitmap.getPixels(row, 0, row.length, x * pixelScale,
					(y + j) * pixelScale + pixelScale / 2, row.length, 1);
			for (int i = 0; i < width; i++) {
				rgbData[offset + j * scanlength + i] = row[i * pixelScale + pixelScale / 2];
			}
		}
	}

	void copyTo(Image dst) {
		dst.getSingleGraphics().getCanvas().drawBitmap(
				bitmap, getPhysicalContentRect(), bounds, null);
	}

	void copyTo(Image dst, int x, int y) {
		Rect r = new Rect(x, y, x + bounds.right, y + bounds.bottom);
		dst.getSingleGraphics().getCanvas().drawBitmap(
				bitmap, getPhysicalContentRect(), r, null);
	}

	public Graphics getSingleGraphics() {
		if (graphics == null) {
			graphics = getGraphics();
		}
		return graphics;
	}

	void setSize(int width, int height) {
		if ((long) width * pixelScale > bitmap.getWidth()
				|| (long) height * pixelScale > bitmap.getHeight()) {
			Bitmap previous = bitmap;
			int previousWidth = bounds.right;
			int previousHeight = bounds.bottom;
			bitmap = Bitmap.createBitmap(width * pixelScale, height * pixelScale,
					Bitmap.Config.ARGB_8888);
			if (pixelScale > 1) bitmap.eraseColor(Color.WHITE);
			bounds.right = width;
			bounds.bottom = height;
			if (graphics != null) {
				graphics.reset(0, 0, width, height);
				if (pixelScale == 1) {
					graphics.getCanvas().drawBitmap(previous, 0, 0, null);
				} else {
					graphics.getCanvas().drawBitmap(previous,
							new Rect(0, 0, previousWidth * pixelScale,
									previousHeight * pixelScale),
							new Rect(0, 0, previousWidth, previousHeight), null);
				}
			}
		} else {
			bounds.right = width;
			bounds.bottom = height;
			if (graphics != null) graphics.reset(0, 0, width, height);
		}
	}

	public Rect getBounds() {
		return bounds;
	}

	public boolean isBlackWhiteAlpha() {
		return isBlackWhiteAlpha;
	}

	public void setBlackWhiteAlpha(boolean blackWhiteAlpha) {
		isBlackWhiteAlpha = blackWhiteAlpha;
	}
}
