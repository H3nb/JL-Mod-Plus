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

package javax.microedition.lcdui.game;

import static org.junit.Assert.assertEquals;

import javax.microedition.lcdui.Canvas;

import org.junit.Test;

public class GameCanvasKeyStateTest {
	@Test
	public void aliasedFireRemainsHeldUntilBothRawKeysRelease() {
		GameCanvasKeyState state = new GameCanvasKeyState();
		int fire = GameCanvas.FIRE_PRESSED;

		state.press(Canvas.KEY_FIRE, fire);
		state.press(Canvas.KEY_NUM5, fire);
		state.release(Canvas.KEY_FIRE, fire);

		assertEquals(fire, state.poll(true));
		assertEquals(fire, state.poll(true));

		state.release(Canvas.KEY_NUM5, fire);
		assertEquals(0, state.poll(true));
	}

	@Test
	public void duplicatePressIsIdempotentAndResetClearsHeldAndLatch() {
		GameCanvasKeyState state = new GameCanvasKeyState();
		int left = GameCanvas.LEFT_PRESSED;

		state.press(Canvas.KEY_LEFT, left);
		state.press(Canvas.KEY_LEFT, left);
		assertEquals(left, state.poll(true));
		state.reset();
		assertEquals(0, state.poll(true));
	}

	@Test
	public void j02ReleaseBeforePollConsumesLatchButLeavesNoHeldAlias() {
		GameCanvasKeyState state = new GameCanvasKeyState();
		int fire = GameCanvas.FIRE_PRESSED;

		state.press(Canvas.KEY_FIRE, fire);
		state.release(Canvas.KEY_FIRE, fire);

		assertEquals(fire, state.poll(true));
		assertEquals(0, state.poll(true));
	}

	@Test
	public void j03NonGameKeyDoesNotEnterGamePollingBits() {
		GameCanvasKeyState state = new GameCanvasKeyState();

		state.press(Canvas.KEY_SOFT_LEFT, 0);

		assertEquals(0, state.poll(true));
		assertEquals(0, state.poll(true));
	}

	@Test
	public void j04ConcurrentPollingAndResetLeavesAStableNeutralState() throws Exception {
		GameCanvasKeyState state = new GameCanvasKeyState();
		Thread producer = new Thread(() -> {
			for (int i = 0; i < 2_000; i++) {
				state.press(Canvas.KEY_NUM5, GameCanvas.FIRE_PRESSED);
				state.release(Canvas.KEY_NUM5, GameCanvas.FIRE_PRESSED);
				if ((i & 31) == 0) state.reset();
			}
		});
		Thread poller = new Thread(() -> {
			for (int i = 0; i < 2_000; i++) {
				state.poll(true);
				if ((i & 31) == 0) state.reset();
			}
		});
		producer.start();
		poller.start();
		producer.join(2_000L);
		poller.join(2_000L);
		assertEquals(0, state.poll(false));
		state.reset();
		assertEquals(0, state.poll(true));
	}
}
