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

/** Defines chronological MotionEvent sample order without depending on Android classes. */
final class ControllerSampleOrder {
	static final int CURRENT = -1;

	private ControllerSampleOrder() {
	}

	static int sampleCount(int historySize) {
		return Math.max(0, historySize) + 1;
	}

	static int historyIndexAt(int sampleIndex, int historySize) {
		int safeHistorySize = Math.max(0, historySize);
		if (sampleIndex < 0 || sampleIndex > safeHistorySize) {
			throw new IndexOutOfBoundsException("sampleIndex=" + sampleIndex);
		}
		return sampleIndex < safeHistorySize ? sampleIndex : CURRENT;
	}
}
