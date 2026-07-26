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

import androidx.annotation.Keep;

/**
 * Stable bytecode-transform entry points for intrinsic monitor operations.
 */
@Keep
public final class EmulationMonitor {
	private EmulationMonitor() {
	}

	public static void waitOn(Object monitor) throws InterruptedException {
		EmulationTime.controller().waitOn(monitor, 0L, 0);
	}

	public static void waitOn(Object monitor, long millis) throws InterruptedException {
		EmulationTime.controller().waitOn(monitor, millis, 0);
	}

	public static void waitOn(Object monitor, long millis, int nanos)
			throws InterruptedException {
		EmulationTime.controller().waitOn(monitor, millis, nanos);
	}

	public static void notifyOne(Object monitor) {
		EmulationTime.controller().notifyMonitor(monitor);
	}

	public static void notifyAllOn(Object monitor) {
		EmulationTime.controller().notifyAllMonitors(monitor);
	}
}
