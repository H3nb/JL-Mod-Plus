/* Licensed under the Apache License, Version 2.0. */
package javax.microedition.lcdui.overlay;

import static io.github.h3nb.jlmodplus.config.PerformanceOverlayOptions.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class PerformanceOverlayTextTest {
	@Test
	public void optionalCapAndEmptySelectionNeverLeaveDanglingSeparators() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.fps = 46;
		v.cap = 120;
		v.speedPercent = 200;
		assertArrayEquals(new String[]{"FPS 46/120", "SPD 2.00x"},
				PerformanceOverlayText.format(FPS | CAP | SPEED, v)[0]);
		assertArrayEquals(new String[]{"CAP 120"}, PerformanceOverlayText.format(CAP, v)[0]);
		assertArrayEquals(new String[]{"FPS 46"}, PerformanceOverlayText.format(FPS, v)[0]);
		assertEquals(0, PerformanceOverlayText.format(0, v).length);
		v.cap = 73.8;
		assertEquals("FPS 46/73.8", PerformanceOverlayText.format(FPS | CAP, v)[0][0]);
		v.cap = 0;
		assertEquals("FPS 46/∞", PerformanceOverlayText.format(FPS | CAP, v)[0][0]);
	}

	@Test
	public void unavailableSensorsAreNotZeroAndMulticoreCpuIsNotClamped() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.cpu = 182;
		v.batteryTemp = 0;
		String[][] groups = PerformanceOverlayText.format(CPU | CPU_TEMP | GPU_TEMP | BATTERY_TEMP, v);
		assertArrayEquals(new String[]{"CPU 182%"}, groups[0]);
		assertArrayEquals(new String[]{"CPUT —", "GPUT —", "BAT 0.0°C"}, groups[1]);
	}

	@Test
	public void reflowPreservesMetricsAndSeparatesWholeCellsBeforeBreakingWords() {
		String[][] cells = {{"FPS 46/60", "RFPS 45", "SPD 1.00x"}, {"RAM 146 MiB"}};
		String[] rows = PerformanceOverlayText.wrap(cells, 19, String::length);
		assertArrayEquals(new String[]{"FPS 46/60 | RFPS 45", "SPD 1.00x", "RAM 146 MiB"}, rows);
		String[] narrow = PerformanceOverlayText.wrap(cells, 5, String::length);
		for (String row : narrow) {
			assertTrue(row.length() <= 5);
			assertFalse(row.startsWith("|") || row.endsWith("|"));
		}
		assertEquals("FPS46/60RFPS45SPD1.00xRAM146MiB",
				String.join("", narrow).replace(" ", "").replace("|", ""));
	}

	@Test
	public void allMetricsHaveUppercaseLabelsAndLocaleIndependentUnits() {
		PerformanceOverlayText.Values v = new PerformanceOverlayText.Values();
		v.interval = 33.3;
		v.autoSpeed = true;
		String text = Arrays.deepToString(PerformanceOverlayText.format(ALL, v));
		assertTrue(text.contains("FI 33.3 ms"));
		assertTrue(text.contains("SPD AUTO 1.00x"));
		assertTrue(text.contains("THRM —"));
		assertFalse(text.contains("DROP"));
	}
}
