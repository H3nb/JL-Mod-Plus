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

package javax.microedition.media;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class BasePlayerTimeBaseTest {
	@Test
	public void timeBaseRequiresRealizedOrPrefetchedState() throws Exception {
		StatePlayer player = new StatePlayer();
		TimeBase custom = () -> 123L;

		assertThrows(IllegalStateException.class, player::getTimeBase);
		assertThrows(IllegalStateException.class, () -> player.setTimeBase(custom));

		player.state = Player.REALIZED;
		player.setTimeBase(custom);
		assertSame(custom, player.getTimeBase());

		player.state = Player.STARTED;
		assertSame(custom, player.getTimeBase());
		assertThrows(IllegalStateException.class, () -> player.setTimeBase(null));

		player.state = Player.PREFETCHED;
		player.setTimeBase(null);
		assertSame(Manager.getSystemTimeBase(), player.getTimeBase());

		player.state = Player.CLOSED;
		assertThrows(IllegalStateException.class, player::getTimeBase);
		assertThrows(IllegalStateException.class, () -> player.setTimeBase(custom));
	}

	private static final class StatePlayer extends BasePlayer {
		int state = Player.UNREALIZED;

		@Override
		public int getState() {
			return state;
		}
	}
}
