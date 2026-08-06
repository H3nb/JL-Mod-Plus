/*
 * Copyright 2026 H3NB
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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import javax.microedition.lcdui.keyboard.KeyMapper;

import org.junit.Test;

/**
 * JVM-only characterization of Canvas key constants and the key-name mapping.
 * Canvas instances cannot be created without an activity, and getKeyCode /
 * getGameAction rely on android.util.SparseIntArray (mocked on the JVM), so
 * only the constant contract and the SparseArrayCompat-based key names are
 * exercised here.
 */
public class CanvasModelTest {
	@Test
	public void keyCodeConstantsMatchSpecification() {
		assertEquals(35, Canvas.KEY_POUND);
		assertEquals(42, Canvas.KEY_STAR);
		assertEquals(48, Canvas.KEY_NUM0);
		assertEquals(57, Canvas.KEY_NUM9);
		assertEquals(-1, Canvas.KEY_UP);
		assertEquals(-2, Canvas.KEY_DOWN);
		assertEquals(-3, Canvas.KEY_LEFT);
		assertEquals(-4, Canvas.KEY_RIGHT);
		assertEquals(-5, Canvas.KEY_FIRE);
		assertEquals(-6, Canvas.KEY_SOFT_LEFT);
		assertEquals(-7, Canvas.KEY_SOFT_RIGHT);
		assertEquals(-8, Canvas.KEY_CLEAR);
		assertEquals(-10, Canvas.KEY_SEND);
		assertEquals(-11, Canvas.KEY_END);
	}

	@Test
	public void gameActionConstantsMatchSpecification() {
		assertEquals(1, Canvas.UP);
		assertEquals(2, Canvas.LEFT);
		assertEquals(5, Canvas.RIGHT);
		assertEquals(6, Canvas.DOWN);
		assertEquals(8, Canvas.FIRE);
		assertEquals(9, Canvas.GAME_A);
		assertEquals(10, Canvas.GAME_B);
		assertEquals(11, Canvas.GAME_C);
		assertEquals(12, Canvas.GAME_D);
	}

	@Test
	public void namedKeysHaveStableNames() {
		assertEquals("UP", KeyMapper.getKeyName(Canvas.KEY_UP));
		assertEquals("DOWN", KeyMapper.getKeyName(Canvas.KEY_DOWN));
		assertEquals("LEFT", KeyMapper.getKeyName(Canvas.KEY_LEFT));
		assertEquals("RIGHT", KeyMapper.getKeyName(Canvas.KEY_RIGHT));
		assertEquals("SELECT", KeyMapper.getKeyName(Canvas.KEY_FIRE));
		assertEquals("SOFT1", KeyMapper.getKeyName(Canvas.KEY_SOFT_LEFT));
		assertEquals("SOFT2", KeyMapper.getKeyName(Canvas.KEY_SOFT_RIGHT));
		assertEquals("CLEAR", KeyMapper.getKeyName(Canvas.KEY_CLEAR));
		assertEquals("SEND", KeyMapper.getKeyName(Canvas.KEY_SEND));
		assertEquals("END", KeyMapper.getKeyName(Canvas.KEY_END));
	}

	@Test
	public void characterKeysFallBackToCodePoint() {
		assertEquals("0", KeyMapper.getKeyName(Canvas.KEY_NUM0));
		assertEquals("9", KeyMapper.getKeyName(Canvas.KEY_NUM9));
		assertEquals("*", KeyMapper.getKeyName(Canvas.KEY_STAR));
		assertEquals("#", KeyMapper.getKeyName(Canvas.KEY_POUND));
		assertNull("invalid code point must produce no name",
				KeyMapper.getKeyName(Character.MAX_CODE_POINT + 1));
	}
}