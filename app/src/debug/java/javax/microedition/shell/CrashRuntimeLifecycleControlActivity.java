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

package javax.microedition.shell;

import android.app.Activity;
import android.os.Bundle;

/** Debug-only lifecycle trigger used by hosted MIDlet containment tests. */
public final class CrashRuntimeLifecycleControlActivity extends Activity {
	public static final String EXTRA_COMMAND = "command";
	public static final String COMMAND_PAUSE = "pause";
	public static final String COMMAND_DESTROY = "destroy";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (COMMAND_DESTROY.equals(getIntent().getStringExtra(EXTRA_COMMAND))) {
			MidletThread.destroyApp();
		}
		// Merely launching this activity pauses MicroActivity first. That is enough for COMMAND_PAUSE;
		// finishing immediately keeps the trigger out of normal emulator state.
		finish();
	}
}
