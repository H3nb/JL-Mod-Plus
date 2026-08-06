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

package javax.microedition.lcdui.commands;

import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import javax.microedition.lcdui.Command;

/**
 * JVM characterization of the soft keys bar popup guards (no View is created
 * in these tests). With no Activity registered the menu must fail closed
 * instead of building a Compose popup host.
 */
public class AbstractSoftKeysBarGuardTest {
	private static final class TestSoftKeysBar extends AbstractSoftKeysBar {
		TestSoftKeysBar() {
			super(null);
		}

		@Override
		protected void onCommandsChanged(List<Command> list) {
		}
	}

	@Test
	public void prepareMenuFailsClosedWithoutActivity() {
		TestSoftKeysBar bar = new TestSoftKeysBar();
		assertNull(bar.prepareMenu(0));
		assertNull(bar.prepareMenu(2));
	}
}
