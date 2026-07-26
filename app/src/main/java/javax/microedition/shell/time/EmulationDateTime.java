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

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Guest-facing date and calendar factories used by the timing transformer.
 *
 * <p>The host implementation still creates the platform Calendar instance;
 * only its initial wall-clock value is replaced with the active emulation
 * wall-clock value. Explicit timestamps supplied by a MIDlet are not changed.
 */
@Keep
public final class EmulationDateTime {
	private EmulationDateTime() {
	}

	public static Calendar getInstance() {
		return atVirtualTime(Calendar.getInstance());
	}

	public static Calendar getInstance(TimeZone zone) {
		return atVirtualTime(Calendar.getInstance(zone));
	}

	public static Calendar getInstance(Locale locale) {
		return atVirtualTime(Calendar.getInstance(locale));
	}

	public static Calendar getInstance(TimeZone zone, Locale locale) {
		return atVirtualTime(Calendar.getInstance(zone, locale));
	}

	private static Calendar atVirtualTime(Calendar calendar) {
		calendar.setTimeInMillis(EmulationTime.currentTimeMillis());
		return calendar;
	}
}
