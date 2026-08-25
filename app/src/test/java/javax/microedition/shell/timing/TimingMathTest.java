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

package javax.microedition.shell.timing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimingMathTest {
	@Test
	public void guestDurationIsScaledToHostWithCeiling() {
		assertEquals(500_000_000L, TimingMath.scaleGuestToHostNanos(1_000_000_000L, 200));
		assertEquals(4_000_000L, TimingMath.scaleGuestToHostNanos(1_000_000L, 25));
		assertEquals(1L, TimingMath.scaleGuestToHostNanos(1L, 1600));
	}

	@Test
	public void durationConversionSaturatesBeforeOverflow() {
		assertEquals(Long.MAX_VALUE, TimingMath.millisToNanos(Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, TimingMath.scaleGuestToHostNanos(Long.MAX_VALUE, 25));
	}
}
