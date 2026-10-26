/*
 * Modified for JL-Mod Plus.
 * Copyright 2020-2024 Yury Kharchenko
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

package javax.microedition.lcdui.graphics;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.LightingColorFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;

import androidx.core.content.res.ResourcesCompat;

import javax.microedition.lcdui.Image;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;

public class CanvasWrapper {
	private final Paint drawPaint = new Paint();
	private final Paint fillPaint = new Paint();
	private final Paint textPaint = new Paint();
	private final Paint imgPaint = new Paint();
	private static final LightingColorFilter PRESSED_SKIN_FILTER = new LightingColorFilter(0xB0B0B0, 0);
	private final Paint diagnosticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint controlFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint controlGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint controlStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final float diagnosticAscent;
	private final float diagnosticHeight;
	private final float textSize;
	private final float baseTextAscent;
	private final float baseTextCenterOffset;
	private final float baseTextHeight;
	private final boolean filterBitmap;
	private final float controlOutlineWidth;

	private float textAscent;
	private float textCenterOffset;
	private float textHeight;
	private Canvas canvas;

	public CanvasWrapper(boolean filterBitmap) {
		this.filterBitmap = filterBitmap;
		imgPaint.setFilterBitmap(filterBitmap);
		drawPaint.setStyle(Paint.Style.STROKE);
		controlStrokePaint.setStyle(Paint.Style.STROKE);
		fillPaint.setStyle(Paint.Style.FILL);

		// init text paint
		Context context = ContextHolder.getAppContext();
		// Outline weight is fixed in dp, never scaled by a key, D-pad, or stick radius.
		controlOutlineWidth = context.getResources().getDisplayMetrics().density * 0.5f;
		textPaint.setTypeface(ResourcesCompat.getFont(context, R.font.roboto_regular));
		textSize = context.getResources().getDimension(R.dimen._22sp);
		textPaint.setTextSize(textSize);
		textPaint.setTextAlign(Paint.Align.CENTER);
		baseTextAscent = textPaint.ascent();
		float baseTextDescent = textPaint.descent();
		baseTextHeight = baseTextDescent - baseTextAscent;
		baseTextCenterOffset = (baseTextDescent + baseTextAscent) / 2.0f;
		textAscent = baseTextAscent;
		textHeight = baseTextHeight;
		textCenterOffset = baseTextCenterOffset;
		diagnosticPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
		diagnosticPaint.setTextSize(context.getResources().getDimension(R.dimen.performance_overlay_text_size));
		diagnosticPaint.setTextAlign(Paint.Align.LEFT);
		float shadowOffset = context.getResources().getDisplayMetrics().density;
		diagnosticPaint.setShadowLayer(shadowOffset, shadowOffset, shadowOffset,
				context.getColor(R.color.fps_overlay_shadow));
		Paint.FontMetrics diagnosticMetrics = diagnosticPaint.getFontMetrics();
		diagnosticAscent = diagnosticMetrics.ascent;
		diagnosticHeight = diagnosticMetrics.descent - diagnosticMetrics.ascent;
	}

	public void bind(Canvas canvas) {
		this.canvas = canvas;
	}

	public void clear(int color) {
		canvas.drawColor(color, PorterDuff.Mode.SRC);
	}

	/** Applies one uniform opacity to a compound control without stacking translucent layers. */
	public int saveAlphaLayer(RectF bounds, int alpha) {
		return canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
	}

	public void restoreAlphaLayer(int saveCount) {
		canvas.restoreToCount(saveCount);
	}

	/** Shader and transform reused between analog frames; each surface owns its instance. */
	public static final class ControlGradient {
		private Shader shader;
		private final Matrix transform = new Matrix();
		private int first;
		private int middle;
		private int last;
	}

	/** A three-stop diagonal gradient keeps the chosen color at the center of a surface. */
	public void fillControlLinearCircle(ControlGradient gradient, float cx, float cy,
			float radius, int top, int middle, int bottom) {
		if (!(gradient.shader instanceof LinearGradient) ||
				gradient.first != top || gradient.middle != middle || gradient.last != bottom) {
			gradient.shader = new LinearGradient(-1f, -1f, 1f, 1f,
					new int[]{top, middle, bottom},
					new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
			gradient.first = top;
			gradient.middle = middle;
			gradient.last = bottom;
		}
		drawControlGradientCircle(gradient, cx, cy, radius);
	}

	/** A shaded recess, darker at its edge, without extra concentric geometry. */
	public void fillControlRadialCircle(ControlGradient gradient, float cx, float cy,
			float radius, int center, int edge) {
		if (!(gradient.shader instanceof RadialGradient) ||
				gradient.first != center || gradient.last != edge) {
			gradient.shader = new RadialGradient(0f, 0f, 1f,
					center, edge, Shader.TileMode.CLAMP);
			gradient.first = center;
			gradient.last = edge;
		}
		drawControlGradientCircle(gradient, cx, cy, radius);
	}

	private void drawControlGradientCircle(ControlGradient gradient,
			float cx, float cy, float radius) {
		gradient.transform.setScale(radius, radius);
		gradient.transform.postTranslate(cx, cy);
		gradient.shader.setLocalMatrix(gradient.transform);
		controlGradientPaint.setShader(gradient.shader);
		canvas.drawCircle(cx, cy, radius, controlGradientPaint);
		controlGradientPaint.setShader(null);
	}

	/** Anti-aliased virtual-control primitives do not modify the legacy MIDlet paints. */
	public float getControlOutlineWidth() {
		return controlOutlineWidth;
	}

	public void fillControlRect(RectF rect, int color) {
		controlFillPaint.setColor(color);
		canvas.drawRect(rect, controlFillPaint);
	}

	public void strokeControlRect(RectF rect, int color, float width) {
		controlStrokePaint.setColor(color);
		controlStrokePaint.setStrokeWidth(width);
		canvas.drawRect(rect, controlStrokePaint);
	}

	public void strokeControlRoundRect(RectF rect, float rounding, int color, float width) {
		controlStrokePaint.setColor(color);
		controlStrokePaint.setStrokeWidth(width);
		canvas.drawRoundRect(rect, rounding, rounding, controlStrokePaint);
	}

	public void fillControlOval(RectF oval, int color) {
		controlFillPaint.setColor(color);
		canvas.drawOval(oval, controlFillPaint);
	}

	public void strokeControlOval(RectF oval, int color, float width) {
		controlStrokePaint.setColor(color);
		controlStrokePaint.setStrokeWidth(width);
		canvas.drawOval(oval, controlStrokePaint);
	}

	public void fillControlPath(Path path, int color) {
		controlFillPaint.setColor(color);
		canvas.drawPath(path, controlFillPaint);
	}

	public void strokeControlPath(Path path, int color, float width) {
		controlStrokePaint.setColor(color);
		controlStrokePaint.setStrokeWidth(width);
		canvas.drawPath(path, controlStrokePaint);
	}

	public void fillControlRoundRect(RectF rect, float rounding, int color) {
		controlFillPaint.setColor(color);
		canvas.drawRoundRect(rect, rounding, rounding, controlFillPaint);
	}

	public void fillControlCircle(float cx, float cy, float radius, int color) {
		controlFillPaint.setColor(color);
		canvas.drawCircle(cx, cy, radius, controlFillPaint);
	}

	public void strokeControlCircle(float cx, float cy, float radius, int color, float width) {
		controlStrokePaint.setColor(color);
		controlStrokePaint.setStrokeWidth(width);
		canvas.drawCircle(cx, cy, radius, controlStrokePaint);
	}

	public void drawArc(RectF oval, int startAngle, int sweepAngle) {
		canvas.drawArc(oval, startAngle, sweepAngle, false, drawPaint);
	}

	public void fillArc(RectF oval, int startAngle, int sweepAngle) {
		canvas.drawArc(oval, startAngle, sweepAngle, false, fillPaint);
	}

	public void drawRoundRect(RectF rect, int rx, int ry) {
		canvas.drawRoundRect(rect, rx, ry, drawPaint);
	}

	public void fillRoundRect(RectF rect, int rx, int ry) {
		canvas.drawRoundRect(rect, rx, ry, fillPaint);
	}

	public void drawString(String text, float x, float y) {
		canvas.drawText(text, x, y - textCenterOffset, textPaint);
	}

	/**
	 * Draws text at a scale relative to the normal overlay font without changing the persistent
	 * CanvasWrapper text state. Callers that need mixed typography in one paint pass should prefer
	 * this scoped overload to setTextScale().
	 */
	public void drawString(String text, float x, float y, float scale) {
		float normalizedScale = Math.max(0.001f, scale);
		float previousTextSize = textPaint.getTextSize();
		float targetTextSize = textSize * normalizedScale;
		if (targetTextSize == previousTextSize) {
			canvas.drawText(text, x, y - textCenterOffset, textPaint);
			return;
		}
		try {
			textPaint.setTextSize(targetTextSize);
			canvas.drawText(text, x, y - baseTextCenterOffset * normalizedScale, textPaint);
		} finally {
			textPaint.setTextSize(previousTextSize);
		}
	}

	/** Returns the current text width without mutating text state. */
	public float measureStringWidth(String text) {
		return textPaint.measureText(text);
	}

	/**
	 * Measures text at a scale relative to the normal overlay font without changing Paint state.
	 * Text width scales linearly with Paint text size, so the current measurement can be normalized
	 * back to the base size instead of temporarily resizing the Paint for every measurement.
	 */
	public float measureStringWidth(String text, float scale) {
		float normalizedScale = Math.max(0.001f, scale);
		float currentTextSize = textPaint.getTextSize();
		float targetTextSize = textSize * normalizedScale;
		if (targetTextSize == currentTextSize) {
			return textPaint.measureText(text);
		}
		if (currentTextSize > 0.0f) {
			return textPaint.measureText(text) * targetTextSize / currentTextSize;
		}

		// setTextScale(0) is legal existing behavior. Handle that uncommon state without division by
		// zero while still restoring the caller's persistent text size.
		try {
			textPaint.setTextSize(targetTextSize);
			return textPaint.measureText(text);
		} finally {
			textPaint.setTextSize(currentTextSize);
		}
	}

	/** Measures scaled text height from immutable base metrics without mutating Paint state. */
	public float getTextHeight(float scale) {
		return baseTextHeight * Math.max(0.001f, scale);
	}

	/** Cached control bitmap; restores shared Paint state after each draw. */
	public void drawControlBitmap(Bitmap bitmap, RectF dst, int alpha, boolean pressedFallback) {
		if (bitmap == null) return;
		imgPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
		imgPaint.setFilterBitmap(true);
		imgPaint.setColorFilter(pressedFallback ? PRESSED_SKIN_FILTER : null);
		try { canvas.drawBitmap(bitmap, null, dst, imgPaint); }
		finally {
			imgPaint.setAlpha(255);
			imgPaint.setFilterBitmap(filterBitmap);
			imgPaint.setColorFilter(null);
		}
	}

	public void drawImage(Image image, RectF dst) {
		Bitmap bitmap = image.getBitmap();
		bitmap.prepareToDraw();
		canvas.drawBitmap(bitmap, image.getBounds(), dst, imgPaint);
	}

	public void fillRect(RectF rect) {
		canvas.drawRect(rect, fillPaint);
	}

	public void drawRect(RectF rect) {
		canvas.drawRect(rect, drawPaint);
	}

	public void setDrawColor(int color) {
		drawPaint.setColor(color);
	}

	public void setFillColor(int color) {
		fillPaint.setColor(color);
	}

	public void setTextColor(int color) {
		textPaint.setColor(color);
	}

	public void drawBackgroundedText(String text) {
		float width = textPaint.measureText(text);
		canvas.drawRect(0, 0, width, textHeight, fillPaint);
		canvas.drawText(text, width / 2.0f, -textAscent, textPaint);
	}

	/** Dedicated host text paint leaves guest controls' font, alignment and colors untouched. */
	public void drawDiagnosticText(String text, int foreground, float left, float top) {
		float baseline = top - diagnosticAscent;
		diagnosticPaint.setColor(foreground);
		canvas.drawText(text, left, baseline, diagnosticPaint);
	}

	public float measureDiagnosticText(String text) {
		return diagnosticPaint.measureText(text);
	}

	public float getDiagnosticTextHeight() {
		return diagnosticHeight;
	}

	public int clipDiagnostics(RectF bounds) {
		int save = canvas.save();
		canvas.clipRect(bounds);
		return save;
	}

	public void restoreDiagnostics(int save) {
		canvas.restoreToCount(save);
	}

	public float getTextHeight() {
		return textHeight;
	}

	public void setTextAlign(Paint.Align align) {
		textPaint.setTextAlign(align);
	}

	public void setTextScale(float scale) {
		textPaint.setTextSize(textSize * scale);
		textAscent = baseTextAscent * scale;
		textHeight = baseTextHeight * scale;
		textCenterOffset = baseTextCenterOffset * scale;
	}

	public void drawBackground(Bitmap bitmap, RectF dst, RectF exclude) {
		int save = canvas.save();
		if (exclude != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				canvas.clipOutRect(exclude);
			} else {
				canvas.clipRect(exclude, Region.Op.DIFFERENCE);
			}
		}
		imgPaint.setFilterBitmap(true);
		canvas.drawBitmap(bitmap, null, dst, imgPaint);
		imgPaint.setFilterBitmap(filterBitmap);
		canvas.restoreToCount(save);
	}
}
