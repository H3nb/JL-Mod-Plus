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

package javax.microedition.lcdui;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class ControllerSampleOrderTest {
	private static final int CENTER = 0;
	private static final int RIGHT = 1;
	private static final int DOWN_RIGHT = 2;

	@Test
	public void historicalRightIsProcessedBeforeCurrentCenter() {
		assertArrayEquals(
				new int[]{RIGHT, CENTER},
				process(new int[]{RIGHT}, CENTER));
	}

	@Test
	public void multipleHistoricalSamplesRemainChronologicalBeforeCurrent() {
		assertArrayEquals(
				new int[]{CENTER, RIGHT, DOWN_RIGHT},
				process(new int[]{CENTER, RIGHT}, DOWN_RIGHT));
	}

	private static int[] process(int[] history, int current) {
		int count = ControllerSampleOrder.sampleCount(history.length);
		int[] processed = new int[count];
		for (int sample = 0; sample < count; sample++) {
			int historyIndex = ControllerSampleOrder.historyIndexAt(sample, history.length);
			processed[sample] = historyIndex == ControllerSampleOrder.CURRENT
					? current : history[historyIndex];
		}
		return processed;
	}
}
