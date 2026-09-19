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

package javax.microedition.shell;

import java.util.HashSet;
import java.util.Set;

/** Tracks host-menu long-press suppression independently for each physical source. */
final class MenuLongPressState {
	private final Set<Long> handledSources = new HashSet<>();

	void begin(int deviceId, int androidKeyCode) {
		handledSources.remove(sourceToken(deviceId, androidKeyCode));
	}

	void markHandled(int deviceId, int androidKeyCode) {
		handledSources.add(sourceToken(deviceId, androidKeyCode));
	}

	boolean consumeHandled(int deviceId, int androidKeyCode) {
		return handledSources.remove(sourceToken(deviceId, androidKeyCode));
	}

	static long sourceToken(int deviceId, int androidKeyCode) {
		return ((long) deviceId << 32) | (androidKeyCode & 0xffffffffL);
	}
}
