/* Licensed under the Apache License, Version 2.0. */
package javax.microedition.lcdui.overlay;

import static io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class PerformanceOverlayTextTest {
	@Test
	public void intervalStatisticsAndHostInformationUseSeparateCompactGroups() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.interval = 33.3;
		v.p95 = 34.0;
		v.maximum = 40.1;
		v.renderer = "GLES";
		v.displayHz = 120;
		v.thermal = 0;
		v.renderInterval = 13.1;
		v.renderP95 = 16.7;
		v.renderMaximum = 25.0;
		String[][] groups = PerformanceOverlayText.format(
				FRAME_INTERVAL | P95_INTERVAL | MAX_INTERVAL | RENDER_INTERVAL
				| RENDER_P95_INTERVAL | RENDER_MAX_INTERVAL | RENDERER | DISPLAY | THERMAL, v);
		assertArrayEquals(new String[]{"GFI 33.3 ms", "GP95 34.0 ms", "GMAX 40.1 ms"}, groups[0]);
		assertArrayEquals(new String[]{"RFI 13.1 ms", "RP95 16.7 ms", "RMAX 25.0 ms"}, groups[1]);
		assertArrayEquals(new String[]{"REN GLES", "DISP 120 Hz", "THRM NONE"}, groups[2]);
		assertArrayEquals(new String[]{"GFI 33.3 ms | GP95 34.0 ms | GMAX 40.1 ms",
				"RFI 13.1 ms | RP95 16.7 ms | RMAX 25.0 ms",
				"REN GLES | DISP 120 Hz | THRM NONE"},
				PerformanceOverlayText.wrap(groups, 1000, String::length));
	}

	@Test
	public void optionalCapAndEmptySelectionNeverLeaveDanglingSeparators() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.fps = 46;
		v.cap = 120;
		v.speedPercent = 200;
		assertArrayEquals(new String[]{"FPS 46", "CAP 120", "SPD 2.00x"},
				PerformanceOverlayText.format(FPS | CAP | SPEED, v)[0]);
		assertArrayEquals(new String[]{"CAP 120"}, PerformanceOverlayText.format(CAP, v)[0]);
		assertArrayEquals(new String[]{"FPS 46"}, PerformanceOverlayText.format(FPS, v)[0]);
		assertEquals(0, PerformanceOverlayText.format(0, v).length);
		v.cap = 73.8;
		assertEquals("FPS 46", PerformanceOverlayText.format(FPS | CAP, v)[0][0]);
		v.cap = 0;
		assertArrayEquals(new String[]{"FPS 46", "CAP —"}, PerformanceOverlayText.format(FPS | CAP, v)[0]);
		v.guestFps = 72;
		assertArrayEquals(new String[]{"FPS 46", "GFPS 72"}, PerformanceOverlayText.format(FPS | GUEST_FPS, v)[0]);
	}

	@Test
	public void unavailableSpeedDoesNotAssumeNormalAndCapRemainsReportable() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.fps = 47;
		v.cap = 60;
		assertArrayEquals(new String[]{"FPS 47", "CAP 60", "SPD —"},
				PerformanceOverlayText.format(FPS | CAP | SPEED, v)[0]);
		assertFalse(Arrays.deepToString(PerformanceOverlayText.format(SPEED, v))
				.contains("1.00x"));
	}

	@Test
	public void availableSpeedFormatsManualMultipliers() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.speedPercent = 50;
		assertEquals("SPD 0.50x", PerformanceOverlayText.format(SPEED, v)[0][0]);
		v.speedPercent = 100;
		assertEquals("SPD 1.00x", PerformanceOverlayText.format(SPEED, v)[0][0]);
		v.speedPercent = 125;
		assertEquals("SPD 1.25x", PerformanceOverlayText.format(SPEED, v)[0][0]);
		v.speedPercent = 1600;
		assertEquals("SPD 16.00x", PerformanceOverlayText.format(SPEED, v)[0][0]);
	}

	@Test
	public void unavailableSensorsAreNotZeroAndMulticoreCpuIsNotClamped() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.cpu = 182;
		v.batteryTemp = 0;
		String[][] groups = PerformanceOverlayText.format(CPU | CPU_TEMP | GPU_TEMP | BATTERY_TEMP, v);
		assertArrayEquals(new String[]{"CPU 1.82c"}, groups[0]);
		assertArrayEquals(new String[]{"CPUT —", "GPUT —", "BAT 0.0°C"}, groups[1]);
	}

	@Test
	public void reflowPreservesMetricsAndSeparatesWholeCellsBeforeBreakingWords() {
		String[][] cells = {{"FPS 46", "GFPS 45", "SPD 1.00x"}, {"RAM 146 MiB"}};
		String[] rows = PerformanceOverlayText.wrap(cells, 19, String::length);
		assertArrayEquals(new String[]{"FPS 46 | GFPS 45", "SPD 1.00x", "RAM 146 MiB"}, rows);
		String[] narrow = PerformanceOverlayText.wrap(cells, 5, String::length);
		for (String row : narrow) {
			assertTrue(row.length() <= 5);
			assertFalse(row.startsWith("|") || row.endsWith("|"));
		}
		assertEquals("FPS46GFPS45SPD1.00xRAM146MiB",
				String.join("", narrow).replace(" ", "").replace("|", ""));
	}

	@Test
	public void allMetricsHaveUppercaseLabelsAndLocaleIndependentUnits() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.interval = 33.3;
		v.speedPercent = 100;
		String text = Arrays.deepToString(PerformanceOverlayText.format(ALL, v));
		assertTrue(text.contains("GFI 33.3 ms"));
		assertTrue(text.contains("SPD 1.00x"));
		assertTrue(text.contains("THRM —"));
		assertFalse(text.contains("DROP"));
	}
}
