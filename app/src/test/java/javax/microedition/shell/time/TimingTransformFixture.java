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

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class TimingTransformFixture {
	private TimingTransformFixture() {
	}

	public static void joinMillis(Thread thread, long millis) throws InterruptedException {
		thread.join(millis);
	}

	public static void joinMillisNanos(Thread thread, long millis, int nanos)
			throws InterruptedException {
		thread.join(millis, nanos);
	}

	public static void yieldOnce() {
		Thread.yield();
	}

	public static void useTimer() {
		java.util.Timer timer = new java.util.Timer(true);
		timer.cancel();
	}

	public static void waitMillis(Object monitor, long millis) throws InterruptedException {
		monitor.wait(millis);
	}

	public static void waitMillisNanos(Object monitor, long millis, int nanos)
			throws InterruptedException {
		monitor.wait(millis, nanos);
	}

	public static void waitIndefinitely(Object monitor) throws InterruptedException {
		monitor.wait();
	}

	public static void notifyOne(Object monitor) {
		monitor.notify();
	}

	public static void notifyAllWaiters(Object monitor) {
		monitor.notifyAll();
	}

	public static long newDateTime() {
		return new Date().getTime();
	}

	public static long calendarTime() {
		return Calendar.getInstance().getTimeInMillis();
	}

	public static long calendarTimeWithZone(TimeZone zone) {
		return Calendar.getInstance(zone).getTimeInMillis();
	}

	public static long calendarTimeWithLocale(Locale locale) {
		return Calendar.getInstance(locale).getTimeInMillis();
	}

	public static long calendarTimeWithZoneAndLocale(TimeZone zone, Locale locale) {
		return Calendar.getInstance(zone, locale).getTimeInMillis();
	}
}
