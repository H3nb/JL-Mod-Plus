/*
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
package javax.microedition.lcdui.overlay;

import static io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions.ALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.lcdui.graphics.CanvasWrapper;

import io.github.h3nb.jlmodplus.R;

@RunWith(AndroidJUnit4.class)
public class PerformanceOverlayRenderingTest {

	@Test
	public void diagnosticTextPaintsOnlyGlyphsWithoutBackground() {
		CanvasWrapper graphics = new CanvasWrapper(false);
		float margin = 12 * density();
		String text = "H   H";
		int width = (int) Math.ceil(graphics.measureDiagnosticText(text) + margin * 2);
		int height = (int) Math.ceil(graphics.getDiagnosticTextHeight() + margin * 2);
		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		graphics.bind(new android.graphics.Canvas(bitmap));
		graphics.drawDiagnosticText(text, Color.WHITE, margin, margin);

		int[] pixels = new int[width * height];
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
		int painted = 0;
		for (int pixel : pixels) {
			if (Color.alpha(pixel) != 0) {
				painted++;
				assertEquals("Glyphs have no outline color", 0xFFFFFF, pixel & 0xFFFFFF);
			}
		}
		assertTrue("The native renderer must paint glyphs", painted > 0);
		assertTrue("No rectangular text background may be painted", painted < pixels.length / 2);
		int gap = Math.round(margin + graphics.measureDiagnosticText("H  "));
		for (int y = 0; y < height; y++) {
			assertEquals("Space between glyphs must stay transparent", 0, bitmap.getPixel(gap, y));
			assertEquals("Margin must stay transparent", 0, bitmap.getPixel(0, y));
		}
		bitmap.recycle();
	}

	@Test
	public void diagnosticFontIsBoldWithoutOutline() {
		CanvasWrapper graphics = new CanvasWrapper(false);
		String text = "FPS 30/60 | RFPS 30";
		Bitmap actual = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888);
		Bitmap expected = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888);
		graphics.bind(new android.graphics.Canvas(actual));
		graphics.drawDiagnosticText(text, Color.WHITE, 10, 10);
		Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
		bold.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
		bold.setTextSize(targetContext().getResources().getDimension(R.dimen.performance_overlay_text_size));
		bold.setColor(Color.WHITE);
		new android.graphics.Canvas(expected).drawText(text, 10, 10 - bold.getFontMetrics().ascent, bold);
		assertTrue("Diagnostic text must be plain bold glyphs", actual.sameAs(expected));
		actual.recycle();
		expected.recycle();
	}

	@Test
	public void diagnosticDrawingAndMeasurementPreserveOrdinaryTextState() {
		CanvasWrapper graphics = ordinaryGraphics();
		CanvasWrapper expectedGraphics = ordinaryGraphics();
		float width = graphics.measureStringWidth("Ordinary controls");
		float height = graphics.getTextHeight();
		int bitmapWidth = (int) Math.ceil(width + 60 * density());
		int bitmapHeight = (int) Math.ceil(height + 60 * density());
		Bitmap actual = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
		Bitmap expected = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
		graphics.bind(new android.graphics.Canvas(actual));
		expectedGraphics.bind(new android.graphics.Canvas(expected));

		graphics.measureDiagnosticText("FPS 30/60 | RFPS 30 | SPD 1.00x");
		graphics.getDiagnosticTextHeight();
		graphics.drawDiagnosticText("FPS 30/60", Color.WHITE, 10, 10);
		assertEquals(width, graphics.measureStringWidth("Ordinary controls"), 0.01f);
		assertEquals(height, graphics.getTextHeight(), 0.01f);
		graphics.clear(Color.TRANSPARENT);
		float x = bitmapWidth - 12 * density();
		float y = bitmapHeight / 2f;
		graphics.drawString("Ordinary controls", x, y);
		expectedGraphics.drawString("Ordinary controls", x, y);
		assertTrue("Ordinary font, color, alignment and baseline must be unchanged", actual.sameAs(expected));
		actual.recycle();
		expected.recycle();
	}

	@Test
	public void allMetricsWrapAtNativeFontSizeAndRenderPreview() throws Exception {
		Context context = targetContext();
		CanvasWrapper graphics = new CanvasWrapper(false);
		String[][] groups = PerformanceOverlayText.format(ALL, exampleValues());
		List<String> originalCells = new ArrayList<>();
		for (String[] group : groups) originalCells.addAll(Arrays.asList(group));
		assertEquals(23, Integer.bitCount(ALL));
		// FPS and CAP share one cell; all 23 selected metrics therefore occupy 22 cells.
		assertEquals(22, originalCells.size());
		assertEquals("FPS 46/60", originalCells.get(0));
		List<String> labels = new ArrayList<>();
		for (String cell : originalCells) labels.add(cell.substring(0, cell.indexOf(' ')));
		assertEquals(Arrays.asList("FPS", "RFPS", "SPD", "FI", "P95", "MAX", "DISP",
				"PAINT", "COPY", "SUB", "INQ", "FRQ", "COAL", "CPU", "RAM", "JAVA",
				"NATIVE", "CPUT", "GPUT", "BAT", "THRM", "REN"), labels);

		float margin = 12 * density();
		int panelWidth = (int) Math.ceil(360 * density());
		float availableWidth = panelWidth - margin * 2;
		String[] rows = PerformanceOverlayText.wrap(groups, availableWidth, graphics::measureDiagnosticText);
		List<String> wrappedCells = new ArrayList<>();
		for (String row : rows) {
			assertTrue("Native font row exceeds the available width: " + row,
					graphics.measureDiagnosticText(row) <= availableWidth + 0.01f);
			assertFalse(row.startsWith("|") || row.endsWith("|"));
			wrappedCells.addAll(Arrays.asList(row.split(" \\| ")));
		}
		assertEquals("Wrapping must preserve every complete metric cell in order", originalCells, wrappedCells);

		float lineHeight = graphics.getDiagnosticTextHeight() + 2 * density();
		int panelHeight = (int) Math.ceil(margin * 2 + (rows.length + 2) * lineHeight);
		Bitmap preview = Bitmap.createBitmap(panelWidth * 3, panelHeight, Bitmap.Config.ARGB_8888);
		android.graphics.Canvas canvas = new android.graphics.Canvas(preview);
		graphics.bind(canvas);
		Paint background = new Paint();
		int foreground = ContextCompat.getColor(context, R.color.fps_overlay_content);
		assertEquals("Diagnostic overlay uses neutral gray", 0xFF757575, foreground);
		String[] titles = {"DARK | ALL METRICS", "BRIGHT | ALL METRICS", "CONTRAST | ALL METRICS"};
		for (int panel = 0; panel < 3; panel++) {
			float left = panel * panelWidth;
			background.setColor(panel == 0 ? 0xFF171A20 : 0xFFF5F5F5);
			canvas.drawRect(left, 0, left + panelWidth, panelHeight, background);
			if (panel == 2) {
				int tile = Math.max(1, Math.round(24 * density()));
				for (int y = 0; y < panelHeight; y += tile) {
					for (int x = 0; x < panelWidth; x += tile) {
						background.setColor(((x / tile + y / tile) & 1) == 0 ? 0xFFB5D8E8 : 0xFF23373E);
						canvas.drawRect(left + x, y, left + Math.min(x + tile, panelWidth),
								Math.min(y + tile, panelHeight), background);
					}
				}
			}
			graphics.drawDiagnosticText(titles[panel], foreground, left + margin, margin);
			for (int i = 0; i < rows.length; i++) {
				graphics.drawDiagnosticText(rows[i], foreground, left + margin,
						margin + (i + 2) * lineHeight);
			}
		}
		File output = new File(context.getCacheDir(), "perf-overlay-preview.png");
		try (FileOutputStream stream = new FileOutputStream(output)) {
			assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, stream));
		}
		assertTrue("Native render preview must exist", output.length() > 0);
		preview.recycle();
	}

	private static CanvasWrapper ordinaryGraphics() {
		CanvasWrapper graphics = new CanvasWrapper(false);
		graphics.setTextScale(0.75f);
		graphics.setTextAlign(Paint.Align.RIGHT);
		graphics.setTextColor(0xFFDC2845);
		return graphics;
	}

	private static Context targetContext() {
		return InstrumentationRegistry.getInstrumentation().getTargetContext();
	}

	private static float density() {
		return targetContext().getResources().getDisplayMetrics().density;
	}

	private static PerformanceOverlayText.Values exampleValues() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.fps = 46;
		v.cap = 60;
		v.renderFps = 45;
		v.speedPercent = 100;
		v.interval = 21.7;
		v.p95 = 30.2;
		v.maximum = 68.4;
		v.displayHz = 120;
		v.paint = 2.4;
		v.copy = 0.3;
		v.submit = 1.1;
		v.inputQueue = 1.8;
		v.frameQueue = 3.2;
		v.coalesced = 1;
		v.cpu = 82;
		v.ram = 146;
		v.javaHeap = 38;
		v.nativeHeap = 24;
		v.cpuTemp = 40;
		v.gpuTemp = 40;
		v.batteryTemp = 38.2;
		v.thermal = 0;
		v.renderer = "GLES";
		return v;
	}
}
