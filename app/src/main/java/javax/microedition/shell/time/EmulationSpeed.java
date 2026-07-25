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

package javax.microedition.shell.time;

/**
 * Supported emulation speed stops represented as exact rational values.
 */
public enum EmulationSpeed {
	X0_1(1, 10, "0.1×"),
	X0_25(1, 4, "0.25×"),
	X0_5(1, 2, "0.5×"),
	X1(1, 1, "1×"),
	X2(2, 1, "2×"),
	X4(4, 1, "4×"),
	X8(8, 1, "8×"),
	X16(16, 1, "16×");

	private final int numerator;
	private final int denominator;
	private final String label;

	EmulationSpeed(int numerator, int denominator, String label) {
		this.numerator = numerator;
		this.denominator = denominator;
		this.label = label;
	}

	public int numerator() {
		return numerator;
	}

	public int denominator() {
		return denominator;
	}

	public double asDouble() {
		return (double) numerator / denominator;
	}

	@Override
	public String toString() {
		return label;
	}
}
