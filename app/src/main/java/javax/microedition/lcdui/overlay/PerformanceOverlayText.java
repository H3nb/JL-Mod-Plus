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

import static io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formatting on the sampler, independent of Android drawing and guest timing. */
final class PerformanceOverlayText {
	interface TextMeasure {
		double width(String text);
	}

	static final class Values {
		double fps = Double.NaN, guestFps = Double.NaN, coalesced = Double.NaN;
		double cap = Double.NaN;
		double speedPercent = Double.NaN;
		double interval = Double.NaN, p95 = Double.NaN, maximum = Double.NaN;
		double renderInterval = Double.NaN, renderP95 = Double.NaN, renderMaximum = Double.NaN;
		double paint = Double.NaN, copy = Double.NaN, submit = Double.NaN;
		double inputQueue = Double.NaN, frameQueue = Double.NaN;
		double cpu = Double.NaN, ram = Double.NaN, javaHeap = Double.NaN;
		double nativeHeap = Double.NaN, displayHz = Double.NaN;
		int thermal = -1;
		String renderer;
	}

	private PerformanceOverlayText() {
	}

	/** Each group contains independent cells, so wrapping never leaves a dangling separator. */
	static String[][] format(int mask, Values v) {
		List<String[]> groups = new ArrayList<>();
		List<String> row = new ArrayList<>();
		if ((mask & FPS) != 0) {
			row.add("FPS " + number(v.fps, 0));
		}
		add(row, mask, GUEST_FPS, "GFPS", v.guestFps, 0, "");
		if ((mask & CAP) != 0) row.add("CAP " + cap(v.cap));
		if ((mask & SPEED) != 0) {
			row.add(Double.isFinite(v.speedPercent)
					? "SPD " + String.format(Locale.ROOT, "%.2fx", v.speedPercent / 100d)
					: "SPD —");
		}
		finish(groups, row);
		add(row, mask, FRAME_INTERVAL, "GFI", v.interval, 1, " ms");
		add(row, mask, P95_INTERVAL, "GP95", v.p95, 1, " ms");
		add(row, mask, MAX_INTERVAL, "GMAX", v.maximum, 1, " ms");
		finish(groups, row);
		add(row, mask, RENDER_INTERVAL, "RFI", v.renderInterval, 1, " ms");
		add(row, mask, RENDER_P95_INTERVAL, "RP95", v.renderP95, 1, " ms");
		add(row, mask, RENDER_MAX_INTERVAL, "RMAX", v.renderMaximum, 1, " ms");
		finish(groups, row);
		add(row, mask, PAINT, "PAINT", v.paint, 1, " ms");
		add(row, mask, COPY, "COPY", v.copy, 1, " ms");
		add(row, mask, SUBMIT, "SUB", v.submit, 1, " ms");
		finish(groups, row);
		add(row, mask, INPUT_QUEUE, "INQ", v.inputQueue, 1, " ms");
		add(row, mask, FRAME_QUEUE, "FRQ", v.frameQueue, 1, " ms");
		add(row, mask, COALESCED, "COAL", v.coalesced, 1, "/s");
		finish(groups, row);
		if ((mask & CPU) != 0) {
			row.add("CPU " + number(v.cpu / 100d, 2) + (Double.isFinite(v.cpu) ? "c" : ""));
		}
		add(row, mask, RAM, "RAM", v.ram, 0, " MiB");
		finish(groups, row);
		add(row, mask, JAVA_HEAP, "JAVA", v.javaHeap, 0, " MiB");
		add(row, mask, NATIVE_HEAP, "NATIVE", v.nativeHeap, 0, " MiB");
		finish(groups, row);
		if ((mask & RENDERER) != 0) row.add("REN " + (v.renderer == null ? "—" : v.renderer));
		add(row, mask, DISPLAY, "DISP", v.displayHz, 0, " Hz");
		if ((mask & THERMAL) != 0) row.add("THRM " + thermal(v.thermal));
		finish(groups, row);
		return groups.toArray(new String[0][]);
	}

	private static void add(List<String> row, int mask, int bit, String label,
			double value, int decimals, String unit) {
		if ((mask & bit) != 0) {
			row.add(label + " " + number(value, decimals) + (Double.isFinite(value) ? unit : ""));
		}
	}

	private static String number(double value, int decimals) {
		return Double.isFinite(value) ? String.format(Locale.ROOT, "%." + decimals + "f", value) : "—";
	}

	private static String cap(double value) {
		if (value == 0) return "—";
		return number(value, value == Math.rint(value) ? 0 : 1);
	}

	private static void finish(List<String[]> groups, List<String> row) {
		if (!row.isEmpty()) {
			groups.add(row.toArray(new String[0]));
			row.clear();
		}
	}

	private static String thermal(int status) {
		return switch (status) {
			case 0 -> "NONE";
			case 1 -> "LIGHT";
			case 2 -> "MODERATE";
			case 3 -> "SEVERE";
			case 4 -> "CRITICAL";
			case 5 -> "EMERGENCY";
			case 6 -> "SHUTDOWN";
			default -> "—";
		};
	}

	/** Wrap complete parameter cells first, then words only when one cell cannot fit. */
	static String[] wrap(String[][] groups, double width, TextMeasure measure) {
		List<String> lines = new ArrayList<>();
		for (String[] group : groups) {
			String line = "";
			for (String cell : group) {
				String joined = line.isEmpty() ? cell : line + " | " + cell;
				if (measure.width(joined) <= width) {
					line = joined;
					continue;
				}
				if (!line.isEmpty()) { lines.add(line); line = ""; }
				if (measure.width(cell) <= width) { line = cell; continue; }
				// Large system text / very narrow windows: preserve every character at the same size.
				for (String word : cell.split(" ")) {
					String candidate = line.isEmpty() ? word : line + " " + word;
					if (measure.width(candidate) <= width) { line = candidate; continue; }
					if (!line.isEmpty()) { lines.add(line); line = ""; }
					for (int i = 0; i < word.length(); i++) {
						candidate = line + word.charAt(i);
						if (!line.isEmpty() && measure.width(candidate) > width) {
							lines.add(line);
							line = "";
						}
						line += word.charAt(i);
					}
				}
			}
			if (!line.isEmpty()) lines.add(line);
		}
		return lines.toArray(new String[0]);
	}
}
