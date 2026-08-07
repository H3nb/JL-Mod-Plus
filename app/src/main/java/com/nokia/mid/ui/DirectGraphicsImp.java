/*
 *  Nokia API for MicroEmulator
 *  Copyright (C) 2003 Markus Heberling <markus@heberling.net>
 *
 *  It is licensed under the following two licenses as alternatives:
 *    1. GNU Lesser General Public License (the "LGPL") version 2.1 or any newer version
 *    2. Apache License (the "AL") Version 2.0
 *
 *  You may not use this file except in compliance with at least one of
 *  the above two licenses.
 *
 *  You may obtain a copy of the LGPL at
 *      http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
 *
 *  You may obtain a copy of the AL at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the LGPL or the AL for the specific language governing permissions and
 *  limitations.
 *
 *  Contributor(s):
 *    Bartek Teodorczyk <barteo@barteo.net>
 *    Nikita Shakarun
 */

package com.nokia.mid.ui;

import android.graphics.Bitmap;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

public class DirectGraphicsImp implements DirectGraphics {
	private final Graphics graphics;
	private static final String KEY_FORMAT = "com.nokia.mid.ui.DirectGraphics.PIXEL_FORMAT";
	private static final int PIXEL_FORMAT = resolveNativePixelFormat(
			Integer.getInteger(KEY_FORMAT, TYPE_USHORT_565_RGB));

	private static final int[][] MANIPULATION2TRANSFORM = new int[][]{
			// rotate:                0,                        90,                        180,                        270
			{Sprite.TRANS_NONE         , Sprite.TRANS_ROT270       , Sprite.TRANS_ROT180       , Sprite.TRANS_ROT90        }, // flip none
			{Sprite.TRANS_MIRROR       , Sprite.TRANS_MIRROR_ROT90 , Sprite.TRANS_MIRROR_ROT180, Sprite.TRANS_MIRROR_ROT270}, // flip horizontal
			{Sprite.TRANS_MIRROR_ROT180, Sprite.TRANS_MIRROR_ROT270, Sprite.TRANS_MIRROR       , Sprite.TRANS_MIRROR_ROT90 }, // flip vertical
			{Sprite.TRANS_ROT180       , Sprite.TRANS_ROT90        , Sprite.TRANS_NONE         , Sprite.TRANS_ROT270       }, // flip both
	};

	public DirectGraphicsImp(Graphics g) {
		graphics = g;
	}

	private static int resolveNativePixelFormat(int format) {
		switch (format) {
			case TYPE_BYTE_1_GRAY:
			case TYPE_BYTE_1_GRAY_VERTICAL:
			case TYPE_USHORT_4444_ARGB:
			case TYPE_USHORT_444_RGB:
			case TYPE_USHORT_565_RGB:
			case TYPE_INT_888_RGB:
			case TYPE_INT_8888_ARGB:
				return format;
			default:
				return TYPE_USHORT_565_RGB;
		}
	}

	private static int getPixel(byte[] pixels, byte[] alpha, int idx, int shift) {
		int p = (pixels[idx] >> shift & 1 ^ 1) * 0x00FFFFFF;
		if (alpha == null) {
			return p;
		}
		return (alpha[idx] >> shift & 1) * 0xFF000000 | p;
	}

	private static int getTransformation(int manipulation) {
		int flip = manipulation >>> 13;
		if (flip > 3) {
			throw new IllegalArgumentException();
		}
		int rotation = manipulation & 0x1FFF;
		int rotIdx = rotation / 90;
		if (rotation - rotIdx * 90 != 0 || rotIdx > 3) {
			throw new IllegalArgumentException();
		}
		return MANIPULATION2TRANSFORM[flip][rotIdx];
	}

	private static void setPixel(byte[] pixels, byte[] alpha, int idx, int shift, int color) {
		int r = color >> 16 & 0xff;
		int g = color >> 8 & 0xff;
		int b = color & 0xff;
		int pixel = (0x4CB2 * r + 0x9691 * g + 0x1D3E * b >> 23) ^ 1;
		if (pixel == 1) {
			pixels[idx] |= 1 << shift;
		} else {
			pixels[idx] &= ~(1 << shift);
		}
		if (alpha != null) {
			if ((color >>> 24) != 0) {
				alpha[idx] |= 1 << shift;
			} else {
				alpha[idx] &= ~(1 << shift);
			}
		}
	}

	private static void validateAnchor(int anchor) {
		int allowed = Graphics.HCENTER | Graphics.VCENTER | Graphics.LEFT | Graphics.RIGHT
				| Graphics.TOP | Graphics.BOTTOM;
		if ((anchor & ~allowed) != 0) {
			throw new IllegalArgumentException();
		}
		int horizontal = anchor & (Graphics.HCENTER | Graphics.LEFT | Graphics.RIGHT);
		int vertical = anchor & (Graphics.VCENTER | Graphics.TOP | Graphics.BOTTOM);
		if (Integer.bitCount(horizontal) > 1 || Integer.bitCount(vertical) > 1) {
			throw new IllegalArgumentException();
		}
	}

	private static void validateArrayBounds(int length, int offset, int scanlength, int width, int height) {
		long rowDelta = (long) (height - 1) * scanlength;
		long minIndex = (long) offset + Math.min(0L, rowDelta);
		long maxIndex = (long) offset + Math.max(0L, rowDelta) + width - 1L;
		if (minIndex < 0L || maxIndex >= length) {
			throw new ArrayIndexOutOfBoundsException();
		}
	}

	private static void validateBitArrayBounds(int length, int offset, int scanlength, int width, int height) {
		long rowDelta = (long) (height - 1) * scanlength;
		long minBit = (long) offset + Math.min(0L, rowDelta);
		long maxBit = (long) offset + Math.max(0L, rowDelta) + width - 1L;
		if (minBit < 0L || maxBit >= (long) length * 8L) {
			throw new ArrayIndexOutOfBoundsException();
		}
	}

	private static void validateVerticalBitArrayBounds(int length, int offset, int scanlength,
											   int width, int height) {
		if (scanlength == 0) {
			throw new IllegalArgumentException("scanlength must be non-zero for vertical bit format");
		}
		long baseRow = (long) offset / scanlength;
		long baseColumn = (long) offset % scanlength;
		long lastRow = baseRow + height - 1L;
		long firstBase = (baseRow >> 3) * (long) scanlength + baseColumn;
		long lastBase = (lastRow >> 3) * (long) scanlength + baseColumn;
		long minIndex = Math.min(firstBase, lastBase);
		long maxIndex = Math.max(firstBase, lastBase) + width - 1L;
		if (minIndex < 0L || maxIndex >= length) {
			throw new ArrayIndexOutOfBoundsException();
		}
	}

	private static void validateByteFormat(int format) {
		switch (format) {
			case TYPE_BYTE_1_GRAY:
			case TYPE_BYTE_1_GRAY_VERTICAL:
				return;
			case TYPE_BYTE_2_GRAY:
			case TYPE_BYTE_4_GRAY:
			case TYPE_BYTE_8_GRAY:
			case TYPE_BYTE_332_RGB:
				throw new IllegalArgumentException("Unsupported format: " + format);
			default:
				throw new IllegalArgumentException("Illegal format: " + format);
		}
	}

	private static void validateIntFormat(int format) {
		if (format != TYPE_INT_888_RGB && format != TYPE_INT_8888_ARGB) {
			throw new IllegalArgumentException("Illegal format: " + format);
		}
	}

	private static void validateShortFormat(int format) {
		switch (format) {
			case TYPE_USHORT_4444_ARGB:
			case TYPE_USHORT_444_RGB:
			case TYPE_USHORT_565_RGB:
				return;
			case TYPE_USHORT_555_RGB:
			case TYPE_USHORT_1555_ARGB:
				throw new IllegalArgumentException("Unsupported format: " + format);
			default:
				throw new IllegalArgumentException("Illegal format: " + format);
		}
	}

	@Override
	public void drawImage(Image img, int x, int y, int anchor, int manipulation) {
		if (img == null) {
			throw new NullPointerException();
		}
		validateAnchor(anchor);
		int transform = getTransformation(manipulation);
		graphics.drawRegion(img, 0, 0, img.getWidth(), img.getHeight(), transform, x, y, anchor);
	}

	@Override
	public void drawPixels(byte[] pixels,
						   byte[] transparencyMask,
						   int offset,
						   int scanlength,
						   int x,
						   int y,
						   int width,
						   int height,
						   int manipulation,
						   int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateByteFormat(format);
		int transform = getTransformation(manipulation);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		int[] colors = new int[height * width];
		switch (format) {
			case TYPE_BYTE_1_GRAY: {
				validateBitArrayBounds(pixels.length, offset, scanlength, width, height);
				if (transparencyMask != null) {
					validateBitArrayBounds(transparencyMask.length, offset, scanlength, width, height);
				}
				for (int yi = 0, di = 0; yi < height; yi++) {
					long row = (long) offset + (long) yi * scanlength;
					for (int xi = 0; xi < width; xi++) {
						long bit = row + xi;
						colors[di++] = getPixel(pixels, transparencyMask,
								(int) (bit >> 3), 7 - (int) (bit & 7L));
					}
				}
				break;
			}
			case TYPE_BYTE_1_GRAY_VERTICAL: {
				validateVerticalBitArrayBounds(pixels.length, offset, scanlength, width, height);
				if (transparencyMask != null) {
					validateVerticalBitArrayBounds(transparencyMask.length, offset, scanlength, width, height);
				}
				long baseRow = (long) offset / scanlength;
				long baseColumn = (long) offset % scanlength;
				for (int yi = 0, di = 0; yi < height; yi++) {
					long row = baseRow + yi;
					int shift = (int) (row & 7L);
					long idx = (row >> 3) * (long) scanlength + baseColumn;
					for (int xi = 0; xi < width; xi++) {
						colors[di++] = getPixel(pixels, transparencyMask, (int) (idx + xi), shift);
					}
				}
				break;
			}
			default:
				throw new AssertionError();
		}

		Image image = Image.createRGBImage(colors, width, height, transparencyMask != null);
		graphics.drawRegion(image, 0, 0, width, height, transform, x, y, 0);
	}

	@Override
	public void drawPixels(int[] pixels,
						   boolean transparency,
						   int offset,
						   int scanlength,
						   int x,
						   int y,
						   int width,
						   int height,
						   int manipulation,
						   int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateIntFormat(format);
		int transform = getTransformation(manipulation);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		validateArrayBounds(pixels.length, offset, scanlength, width, height);
		int[] colors = new int[height * width];
		for (int yi = 0, di = 0; yi < height; yi++) {
			int row = (int) ((long) offset + (long) yi * scanlength);
			for (int xi = 0; xi < width; xi++) {
				colors[di++] = pixels[row + xi];
			}
		}
		Image image = Image.createRGBImage(colors, width, height,
				format == TYPE_INT_8888_ARGB && transparency);
		graphics.drawRegion(image, 0, 0, width, height, transform, x, y, 0);
	}

	@Override
	public void drawPixels(short[] pixels,
						   boolean transparency,
						   int offset,
						   int scanlength,
						   int x,
						   int y,
						   int width,
						   int height,
						   int manipulation,
						   int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateShortFormat(format);
		int transform = getTransformation(manipulation);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		validateArrayBounds(pixels.length, offset, scanlength, width, height);
		int[] colors = new int[height * width];

		for (int yi = 0, di = 0; yi < height; yi++) {
			int row = (int) ((long) offset + (long) yi * scanlength);
			for (int xi = 0; xi < width; xi++) {
				short s = pixels[row + xi];
				switch (format) {
					case TYPE_USHORT_4444_ARGB: {
						int a = (s & 0xF000) << 12;
						int r = (s & 0x0F00) << 8;
						int g = (s & 0x00F0) << 4;
						int b = s & 0x000F;
						int argb = a | r | g | b;
						colors[di++] = argb | argb << 4;
						break;
					}
					case TYPE_USHORT_444_RGB: {
						int rgb = (s & 0x0F00) << 8 | (s & 0x00F0) << 4 | (s & 0x000F);
						colors[di++] = 0xFF000000 | rgb | rgb << 4;
						break;
					}
					case TYPE_USHORT_565_RGB: {
						int r = (s & 0xF800) << 8 | (s & 0xE000) << 3;
						int g = (s & 0x07E0) << 5 | (s & 0x0600) >> 1;
						int b = (s & 0x001F) << 3 | (s & 0x001C) >> 2;
						colors[di++] = 0xFF000000 | r | g | b;
						break;
					}
					default:
						throw new AssertionError();
				}
			}
		}
		Image image = Image.createRGBImage(colors, width, height,
				format == TYPE_USHORT_4444_ARGB && transparency);
		graphics.drawRegion(image, 0, 0, width, height, transform, x, y, 0);
	}

	@Override
	public void drawPolygon(int[] xPoints,
							int xOffset,
							int[] yPoints,
							int yOffset,
							int nPoints,
							int argbColor) {
		graphics.drawPolygon(xPoints, xOffset, yPoints, yOffset, nPoints, argbColor);
	}

	@Override
	public void drawTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int argbColor) {
		drawPolygon(new int[]{x1, x2, x3}, 0, new int[]{y1, y2, y3}, 0, 3, argbColor);
	}

	@Override
	public void fillPolygon(int[] xPoints,
							int xOffset,
							int[] yPoints,
							int yOffset,
							int nPoints,
							int argbColor) {
		graphics.fillPolygon(xPoints, xOffset, yPoints, yOffset, nPoints, argbColor, true);
	}

	@Override
	public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int argbColor) {
		fillPolygon(new int[]{x1, x2, x3}, 0, new int[]{y1, y2, y3}, 0, 3, argbColor);
	}

	@Override
	public int getAlphaComponent() {
		return graphics.getARGBColor() >>> 24;
	}

	@Override
	public int getNativePixelFormat() {
		return PIXEL_FORMAT;
	}

	@Override
	public void getPixels(byte[] pixels,
						  byte[] transparencyMask,
						  int offset,
						  int scanlength,
						  int x,
						  int y,
						  int width,
						  int height,
						  int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (x < 0 || y < 0 || width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateByteFormat(format);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		int[] colors = new int[width * height];
		getPixels(colors, 0, width, x, y, width, height);
		switch (format) {
			case TYPE_BYTE_1_GRAY: {
				validateBitArrayBounds(pixels.length, offset, scanlength, width, height);
				if (transparencyMask != null) {
					validateBitArrayBounds(transparencyMask.length, offset, scanlength, width, height);
				}
				for (int yi = 0, si = 0; yi < height; yi++) {
					long row = (long) offset + (long) yi * scanlength;
					for (int xi = 0; xi < width; xi++) {
						long bit = row + xi;
						setPixel(pixels, transparencyMask,
								(int) (bit >> 3), 7 - (int) (bit & 7L), colors[si++]);
					}
				}
				break;
			}
			case TYPE_BYTE_1_GRAY_VERTICAL: {
				validateVerticalBitArrayBounds(pixels.length, offset, scanlength, width, height);
				if (transparencyMask != null) {
					validateVerticalBitArrayBounds(transparencyMask.length, offset, scanlength, width, height);
				}
				long baseRow = (long) offset / scanlength;
				long baseColumn = (long) offset % scanlength;
				for (int yi = 0, si = 0; yi < height; yi++) {
					long row = baseRow + yi;
					int shift = (int) (row & 7L);
					long idx = (row >> 3) * (long) scanlength + baseColumn;
					for (int xi = 0; xi < width; xi++) {
						setPixel(pixels, transparencyMask, (int) (idx + xi), shift, colors[si++]);
					}
				}
				break;
			}
			default:
				throw new AssertionError();
		}
	}

	@Override
	public void getPixels(int[] pixels,
						  int offset,
						  int scanlength,
						  int x,
						  int y,
						  int width,
						  int height,
						  int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (x < 0 || y < 0 || width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateIntFormat(format);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		validateArrayBounds(pixels.length, offset, scanlength, width, height);
		long absScanlength = Math.abs((long) scanlength);
		if (absScanlength >= width) {
			getPixels(pixels, offset, scanlength, x, y, width, height);
			if (format == TYPE_INT_888_RGB) {
				for (int yi = 0; yi < height; yi++) {
					int row = (int) ((long) offset + (long) yi * scanlength);
					for (int xi = 0; xi < width; xi++) {
						pixels[row + xi] &= 0x00FFFFFF;
					}
				}
			}
			return;
		}

		// Android Bitmap.getPixels requires |stride| >= width. Nokia does not;
		// overlapping or zero scanlengths are valid as long as every requested
		// array access stays in range, so read contiguously and scatter in Nokia
		// row order for those layouts.
		int[] colors = new int[width * height];
		getPixels(colors, 0, width, x, y, width, height);
		for (int yi = 0, si = 0; yi < height; yi++) {
			int row = (int) ((long) offset + (long) yi * scanlength);
			for (int xi = 0; xi < width; xi++, si++) {
				int color = colors[si];
				pixels[row + xi] = format == TYPE_INT_888_RGB ? color & 0x00FFFFFF : color;
			}
		}
	}

	@Override
	public void getPixels(short[] pixels,
						  int offset,
						  int scanlength,
						  int x,
						  int y,
						  int width,
						  int height,
						  int format) {
		if (pixels == null) {
			throw new NullPointerException();
		}
		if (x < 0 || y < 0 || width < 0 || height < 0) {
			throw new IllegalArgumentException();
		}
		validateShortFormat(format);
		if (offset < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width == 0 || height == 0) {
			return;
		}

		validateArrayBounds(pixels.length, offset, scanlength, width, height);
		int[] colors = new int[width * height];
		getPixels(colors, 0, width, x, y, width, height);
		for (int yi = 0, si = 0; yi < height; yi++) {
			int row = (int) ((long) offset + (long) yi * scanlength);
			for (int xi = 0; xi < width; xi++, si++) {
				int color = colors[si];
				switch (format) {
					case TYPE_USHORT_4444_ARGB: {
						int a = color >> 16 & 0xF000;
						int r = color >> 12 & 0x0F00;
						int g = color >> 8 & 0x00F0;
						int b = color >> 4 & 0x000F;
						pixels[row + xi] = (short) (a | r | g | b);
						break;
					}
					case TYPE_USHORT_444_RGB: {
						int r = color >> 12 & 0x0F00;
						int g = color >> 8 & 0x00F0;
						int b = color >> 4 & 0x000F;
						pixels[row + xi] = (short) (r | g | b);
						break;
					}
					case TYPE_USHORT_565_RGB: {
						int r = color >> 8 & 0xF800;
						int g = color >> 5 & 0x07E0;
						int b = color >> 3 & 0x001F;
						pixels[row + xi] = (short) (r | g | b);
						break;
					}
					default:
						throw new AssertionError();
				}
			}
		}
	}

	@Override
	public void setARGBColor(int argb) {
		graphics.setColorAlpha(argb);
	}

	private void getPixels(int[] pixels,
						   int offset,
						   int stride,
						   int x,
						   int y,
						   int width,
						   int height) {
		x += graphics.getTranslateX();
		y += graphics.getTranslateY();
		Bitmap image = graphics.getBitmap();
		int w = Math.min(width, image.getWidth() - x);
		int h = Math.min(height, image.getHeight() - y);
		image.getPixels(pixels, offset, stride, x, y, w, h);
	}
}
