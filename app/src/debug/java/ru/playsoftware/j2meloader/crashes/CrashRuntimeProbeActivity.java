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

package ru.playsoftware.j2meloader.crashes;

import android.app.Activity;
import android.os.Bundle;

/** Debug-only remote-process probe used by instrumentation runtime validation. */
public final class CrashRuntimeProbeActivity extends Activity {
	static final String MIDLET_NAME = "JL-Mod Plus Crash Runtime Probe";
	static final String MAIN_CLASS = CrashRuntimeProbeActivity.class.getName();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		CrashReporter.setMidletContext(MIDLET_NAME, "JL-Mod Plus", "debug", null, null);
		CrashReporter.setMidletMainClass(MAIN_CLASS);

		MidletSessionJournal journal = MidletSessionJournal.create(
				this,
				MIDLET_NAME,
				"JL-Mod Plus",
				"debug",
				MAIN_CLASS,
				null,
				null
		);
		String eventId = journal.recordUnexpectedFailure(
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);
		if (eventId == null) {
			throw new IllegalStateException("Crash runtime probe could not claim a failure event");
		}

		throw new RuntimeException(
				"JL-Mod Plus session failure; eventId=" + eventId
						+ "; boundary=UNCAUGHT_THREAD; runtimeProbe=true;",
				new IllegalStateException("Intentional debug-only crash runtime probe")
		);
	}
}
