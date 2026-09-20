/*
 * Modified for JL-Mod Plus.
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

package javax.microedition.lcdui.game;

import java.util.HashSet;
import java.util.Set;

/**
 * Thread-safe held/latch state for GameCanvas. Raw key identities remain distinct while aliases
 * such as FIRE/NUM5 contribute to one game-action bit until every alias is released.
 */
final class GameCanvasKeyState {
	private final Set<Integer> heldRawKeys = new HashSet<>();
	private int heldBits;
	private int latchedBits;

	/** Records a raw press/repeat and returns the corresponding canonical bit, or zero. */
	synchronized int press(int rawKey, int bit) {
		if (bit == 0) {
			return 0;
		}
		heldRawKeys.add(rawKey);
		heldBits |= bit;
		latchedBits |= bit;
		return bit;
	}

	/** Removes one raw identity and recomputes held bits from all aliases still down. */
	synchronized int release(int rawKey, int bit) {
		if (bit == 0) {
			return 0;
		}
		heldRawKeys.remove(rawKey);
		heldBits = 0;
		for (Integer key : heldRawKeys) {
			heldBits |= GameCanvas.bitForKeyCode(key);
		}
		return bit;
	}

	/** Returns the current held/latch snapshot and consumes only the latch. */
	synchronized int poll(boolean shown) {
		int result = shown ? heldBits | latchedBits : 0;
		latchedBits = 0;
		return result;
	}

	synchronized void reset() {
		heldRawKeys.clear();
		heldBits = 0;
		latchedBits = 0;
	}
}
