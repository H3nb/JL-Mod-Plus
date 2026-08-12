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
import android.os.Process;

/** Debug-only remote-process probe used by instrumentation runtime validation. */
public final class CrashRuntimeProbeActivity extends Activity {
	static final String EXTRA_MODE = "ru.playsoftware.j2meloader.crashes.RUNTIME_PROBE_MODE";
	static final String MODE_SIGNAL_KILL = "signal-kill";
	static final String MIDLET_NAME = "JL-Mod Plus Crash Runtime Probe";
	static final String SIGNAL_MIDLET_NAME = "JL-Mod Plus Process Exit Runtime Probe";
	static final String MAIN_CLASS = CrashRuntimeProbeActivity.class.getName();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		boolean signalKill = MODE_SIGNAL_KILL.equals(getIntent().getStringExtra(EXTRA_MODE));
		String midletName = signalKill ? SIGNAL_MIDLET_NAME : MIDLET_NAME;
		CrashReporter.setMidletContext(midletName, "JL-Mod Plus", "debug", null, null);
		CrashReporter.setMidletMainClass(MAIN_CLASS);

		MidletSessionJournal journal = MidletSessionJournal.create(
				this,
				midletName,
				"JL-Mod Plus",
				"debug",
				MAIN_CLASS,
				null,
				null
		);

		if (signalKill) {
			// Deliberately leave outcome=NONE: this represents an abrupt process death that cannot
			// execute Java exception reporting or graceful MIDlet teardown.
			journal.transition(MidletSessionJournal.Stage.RUNNING);
			Process.killProcess(Process.myPid());
			return;
		}

		String eventId = journal.recordUnexpectedFailure(
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);
		if (eventId == null) {
			throw new IllegalStateException("Crash runtime probe could not claim a failure event");
		}

		// This probe claims UNCAUGHT_THREAD, so exercise a real non-main uncaught thread. Throwing
		// directly from Activity.onCreate() tests Activity launch failure semantics instead and, on
		// Android 6, the system can tear down the only Activity before the reporter's synchronous file
		// write becomes observable. Actual MIDlet worker/lifecycle crashes occur off the UI thread.
		Thread crashThread = new Thread(() -> {
			throw new RuntimeException(
					"JL-Mod Plus session failure; eventId=" + eventId
							+ "; boundary=UNCAUGHT_THREAD; runtimeProbe=true;",
					new IllegalStateException("Intentional debug-only crash runtime probe")
			);
		}, "jlmod-crash-runtime-probe");
		crashThread.start();
	}
}
