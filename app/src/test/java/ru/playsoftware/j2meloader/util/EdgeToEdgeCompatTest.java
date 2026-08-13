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

package ru.playsoftware.j2meloader.util;

import static org.junit.Assert.assertEquals;

import androidx.core.graphics.Insets;

import org.junit.Test;

public class EdgeToEdgeCompatTest {
	@Test
	public void fullWindowContentClearsActionBarAndSystemBars() {
		Insets padding = EdgeToEdgeCompat.calculateRequiredPadding(
				new EdgeToEdgeCompat.Bounds(0, 0, 320, 640),
				new EdgeToEdgeCompat.Bounds(0, 0, 320, 640),
				new EdgeToEdgeCompat.Bounds(0, 24, 320, 80),
				Insets.of(0, 24, 0, 24),
				24);

		assertEquals(Insets.of(0, 80, 0, 24), padding);
	}

	@Test
	public void existingSafeLayoutDoesNotReceiveDuplicateInsets() {
		Insets padding = EdgeToEdgeCompat.calculateRequiredPadding(
				new EdgeToEdgeCompat.Bounds(0, 80, 320, 616),
				new EdgeToEdgeCompat.Bounds(0, 0, 320, 640),
				new EdgeToEdgeCompat.Bounds(0, 24, 320, 80),
				Insets.of(0, 24, 0, 24),
				24);

		assertEquals(Insets.NONE, padding);
	}

	@Test
	public void landscapeCutoutProtectsHostContentHorizontally() {
		Insets padding = EdgeToEdgeCompat.calculateRequiredPadding(
				new EdgeToEdgeCompat.Bounds(0, 0, 640, 320),
				new EdgeToEdgeCompat.Bounds(0, 0, 640, 320),
				new EdgeToEdgeCompat.Bounds(0, 0, 640, 56),
				Insets.of(80, 0, 0, 24),
				24);

		assertEquals(Insets.of(80, 56, 0, 24), padding);
	}
}
