/*
 * Modified for JL-Mod Plus.
 * Copyright 2023 Yury Kharchenko
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

package javax.microedition.lcdui.skin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.RectF;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.BitSet;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.lcdui.overlay.Overlay;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.util.SvgRasterizer;
import java.io.IOException;

public class SkinLayer implements Overlay {
	private static SkinLayer instance;

	private final Bitmap image;
	private final ProfileModel settings;
	private final RectF clip = new RectF();
	private final RectF bounds = new RectF();
	private final RectF display = new RectF();

	/**
	 * Whether the opening has non-rectangular transparent edges that need the original artwork
	 * drawn over the guest. A purely rectangular opening retains the legacy excluded-rectangle
	 * composition.
	 */
	private final boolean shapedDisplayFrame;

	public SkinLayer(Bitmap image, ProfileModel settings) {
		this.image = image;
		this.settings = settings;
		DetectedFrame enclosed = findEnclosedFrame(image);
		if (enclosed != null) {
			display.set(enclosed.bounds);
			shapedDisplayFrame = enclosed.shaped;
			return;
		}
		shapedDisplayFrame = false;
		// Older screen skins may rely on a transparent region that is not fully enclosed.
		// Keep their original detection/exclusion behavior when no enclosed opening exists.
		int imageHeight = image.getHeight();
		int imageWidth = image.getWidth();
		for (int t = 0; t < imageHeight; t++) {
			for (int l = 0; l < imageWidth; l++) {
				if (image.getPixel(l, t) != 0) {
					continue;
				}
				int r = l + 1;
				while (r < imageWidth && image.getPixel(r, t) == 0) {
					r++;
				}
				if (r - l < Math.min(imageWidth, imageHeight) / 2) {
					continue;
				}

				int b = t + 1;
				while (b < imageHeight && image.getPixel(l, b) == 0) {
					b++;
				}
				if (b - t < Math.min(imageWidth, imageHeight) / 2) {
					continue;
				}
				display.set(l, t, r, b);
				return;
			}
		}
	}

	private static final class DetectedFrame {
		final RectF bounds;
		final boolean shaped;

		DetectedFrame(RectF bounds, boolean shaped) {
			this.bounds = bounds;
			this.shaped = shaped;
		}
	}

	/**
	 * Identify the largest enclosed, predominantly transparent region. Restrict the scan to
	 * 512 pixels along the longer edge to avoid allocating a second full-sized screen bitmap.
	 * This runs once when the skin is loaded, not on the frame-rendering path.
	 */
	private static DetectedFrame findEnclosedFrame(Bitmap image) {
		final int width = image.getWidth();
		final int height = image.getHeight();
		final float factor = Math.min(1f, 512f / Math.max(width, height));
		final int scanWidth = Math.max(1, Math.round(width * factor));
		final int scanHeight = Math.max(1, Math.round(height * factor));
		Bitmap sampled = null;
		try {
			sampled = scanWidth == width && scanHeight == height
					? image : Bitmap.createScaledBitmap(image, scanWidth, scanHeight, true);
			int[] pixels = new int[scanWidth * scanHeight];
			sampled.getPixels(pixels, 0, scanWidth, 0, 0, scanWidth, scanHeight);
			BitSet visited = new BitSet(pixels.length);
			int[] queue = null;
			int minSpan = Math.max(1, Math.min(scanWidth, scanHeight) / 2);
			int bestArea = 0;
			DetectedFrame best = null;
			for (int start = 0; start < pixels.length; start++) {
				if (visited.get(start) || (pixels[start] >>> 24) > 16) continue;
				if (queue == null) queue = new int[pixels.length];
				int head = 0;
				int tail = 1;
				queue[0] = start;
				visited.set(start);
				int left = scanWidth;
				int top = scanHeight;
				int right = 0;
				int bottom = 0;
				boolean touchesEdge = false;
				while (head < tail) {
					int at = queue[head++];
					int x = at % scanWidth;
					int y = at / scanWidth;
					left = Math.min(left, x);
					top = Math.min(top, y);
					right = Math.max(right, x);
					bottom = Math.max(bottom, y);
					if (x == 0 || y == 0 || x == scanWidth - 1 || y == scanHeight - 1)
						touchesEdge = true;
					if (x > 0) tail = enqueue(at - 1, pixels, visited, queue, tail);
					if (x + 1 < scanWidth) tail = enqueue(at + 1, pixels, visited, queue, tail);
					if (y > 0) tail = enqueue(at - scanWidth, pixels, visited, queue, tail);
					if (y + 1 < scanHeight)
						tail = enqueue(at + scanWidth, pixels, visited, queue, tail);
				}
				int spanWidth = right - left + 1;
				int spanHeight = bottom - top + 1;
				int boundsArea = spanWidth * spanHeight;
				// A screen opening should be substantial and mostly filled, not a thin
				// decorative outline or a transparent region connected to the outer canvas.
				if (!touchesEdge && spanWidth >= minSpan && spanHeight >= minSpan
						&& tail * 2 >= boundsArea && tail > bestArea) {
					bestArea = tail;
					best = new DetectedFrame(new RectF(
							left * (float) width / scanWidth, top * (float) height / scanHeight,
							(right + 1f) * width / scanWidth,
							(bottom + 1f) * height / scanHeight), tail != boundsArea);
				}
			}
			return best;
		} catch (OutOfMemoryError failure) {
			android.util.Log.w("SkinLayer", "Could not analyze screen skin opening", failure);
			return null;
		} finally {
			if (sampled != null && sampled != image) sampled.recycle();
		}
	}

	private static int enqueue(int index, int[] pixels, BitSet visited,
			int[] queue, int tail) {
		if (!visited.get(index) && (pixels[index] >>> 24) <= 16) {
			visited.set(index);
			queue[tail++] = index;
		}
		return tail;
	}

	public static SkinLayer getInstance() {
		return instance;
	}

	public static void init(ProfileModel settings, File workDir) {
		instance = null;
		if (settings.screenBackgroundImage == null) return;
		File skin = new File(workDir + Config.SKINS_DIR + settings.screenBackgroundImage);
		if (SvgRasterizer.isSvg(skin)) {
			try {
				Point size = new Point();
				ContextCompat.getDisplayOrDefault(ContextHolder.getAppContext()).getSize(size);
				int side = Math.min(3072, Math.max(1, Math.max(size.x, size.y)));
				Bitmap image = SvgRasterizer.read(skin).renderWithin(side, side);
				instance = new SkinLayer(image, settings);
			} catch (IOException | IllegalArgumentException | OutOfMemoryError failure) {
				android.util.Log.w("SkinLayer", "Could not load SVG screen skin", failure);
			}
			return;
		}
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(skin.getPath(), opts);
		if (opts.outWidth <= 0 || opts.outHeight <= 0) {
			return;
		}
		Point p = new Point();
		ContextCompat.getDisplayOrDefault(ContextHolder.getAppContext()).getSize(p);
		int max = opts.outWidth / p.x;
		int sample = opts.outWidth / p.y;
		if (sample > max) {
			max = sample;
		}
		sample = opts.outHeight / p.x;
		if (sample > max) {
			max = sample;
		}
		sample = opts.outHeight / p.y;
		if (sample > max) {
			max = sample;
		}
		opts.inSampleSize = max;
		opts.inJustDecodeBounds = false;
		Bitmap bitmap = BitmapFactory.decodeFile(skin.getPath(), opts);
		if (bitmap == null) {
			return;
		}
		instance = new SkinLayer(bitmap, settings);
	}

	@Override
	public void paint(CanvasWrapper g) {
		g.drawBackground(image, bounds, shapedDisplayFrame ? null : clip);
	}

	@Override
	public void setTarget(Canvas canvas) {

	}

	@Override
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		bounds.set(left, top, right, bottom);
		if (display.isEmpty()) {
			int p = settings.screenPadding;
			clip.set(screen);
			clip.intersect(p, p, right - p, bottom - p);
		} else {
			clip.set(display);
			scale();
			clip.offset(left, top);
			screen.set(clip);
		}
	}

	private void scale() {
		float horizontalScale = bounds.width() / image.getWidth();
		clip.left = display.left * horizontalScale;
		clip.right = display.right * horizontalScale;
		float verticalScale = bounds.height() / image.getHeight();
		clip.top = display.top * verticalScale;
		clip.bottom = display.bottom * verticalScale;
	}

	@Override
	public boolean keyPressed(int keyCode) {
		return false;
	}

	@Override
	public boolean keyRepeated(int keyCode) {
		return false;
	}

	@Override
	public boolean keyReleased(int keyCode) {
		return false;
	}

	@Override
	public boolean pointerPressed(int pointer, float x, float y) {
		return false;
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		return false;
	}

	@Override
	public boolean pointerReleased(int pointer, float x, float y) {
		return false;
	}

	@Override
	public void show() {

	}

	@Override
	public void hide() {

	}

	@Override
	public void cancel() {

	}

	public boolean hasDisplayFrame() {
		return !display.isEmpty();
	}
}
