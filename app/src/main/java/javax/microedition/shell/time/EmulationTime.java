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

import java.util.Objects;

import javax.microedition.media.Manager;

/**
 * Static entry points used by transformed MIDlet bytecode.
 *
 * <p>The active controller is installed for one MIDlet session before the
 * MIDlet class is loaded.  Keeping this facade small gives the bytecode
 * transformer stable method descriptors without exposing controller state to
 * game code.</p>
 */
public final class EmulationTime {
	/**
	 * Legacy system-property key for the per-MIDlet monitor compatibility mode.
	 * The original name is retained so existing profiles remain compatible.
	 */
	public static final String TIMED_WAIT_PROPERTY = "jlmodplus.timing.timed_wait";

	private static volatile EmulationTimeController controller = newController();

	private EmulationTime() {
	}

	public static void install(EmulationTimeController replacement) {
		EmulationTimeController next = Objects.requireNonNull(replacement, "replacement");
		controller = next;
		Manager.setSystemTimeBase(new EmulationClock(next));
	}

	public static void reset() {
		EmulationTimeController next = newController();
		controller = next;
		Manager.setSystemTimeBase(new EmulationClock(next));
	}

	private static EmulationTimeController newController() {
		return new EmulationTimeController();
	}

	public static EmulationTimeController controller() {
		return controller;
	}

	public static SpeedSnapshot snapshot() {
		return controller.snapshot();
	}

	public static long nanoTime() {
		return controller.nanoTime();
	}

	public static long currentTimeMillis() {
		return controller.currentTimeMillis();
	}

	public static void sleep(long millis) throws InterruptedException {
		controller.sleep(millis);
	}

	public static void sleep(long millis, int nanos) throws InterruptedException {
		controller.sleep(millis, nanos);
	}

	public static void join(Thread thread, long millis) throws InterruptedException {
		controller.join(thread, millis);
	}

	public static void join(Thread thread, long millis, int nanos) throws InterruptedException {
		controller.join(thread, millis, nanos);
	}

	public static void waitOn(Object monitor, long millis) throws InterruptedException {
		controller.waitOn(monitor, millis);
	}

	public static void waitOn(Object monitor, long millis, int nanos)
			throws InterruptedException {
		controller.waitOn(monitor, millis, nanos);
	}

	public static boolean isTimedWaitEnabled() {
		return controller.isTimedWaitEnabled();
	}

	public static void setTimedWaitEnabled(boolean enabled) {
		controller.setTimedWaitEnabled(enabled);
	}

	public static void awaitVirtualMillis(long millis) throws InterruptedException {
		controller.awaitVirtualMillis(millis);
	}

	public static boolean awaitVirtualMillisOrSignal(long millis, long knownGeneration)
			throws InterruptedException {
		return controller.awaitVirtualMillisOrSignal(millis, knownGeneration);
	}

	public static boolean awaitWallMillisOrSignal(long targetMillis, long knownGeneration)
			throws InterruptedException {
		return controller.awaitWallMillisOrSignal(targetMillis, knownGeneration);
	}

	public static long waitGeneration() {
		return controller.waitGeneration();
	}

	public static void signalWaiters() {
		controller.signalWaiters();
	}

	public static SpeedSnapshot setSpeed(EmulationSpeed speed) {
		return controller.setSpeed(speed);
	}

	public static SpeedSnapshot pause() {
		return controller.pause();
	}

	public static SpeedSnapshot resume() {
		return controller.resume();
	}

	public static SpeedSnapshot stop() {
		return controller.stop();
	}
}
