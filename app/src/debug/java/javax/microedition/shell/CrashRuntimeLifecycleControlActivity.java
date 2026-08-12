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

import javax.microedition.util.ContextHolder;

/** Debug-only lifecycle trigger used by hosted MIDlet containment tests. */
public final class CrashRuntimeLifecycleControlActivity extends Activity {
	public static final String EXTRA_COMMAND = "command";
	public static final String COMMAND_PAUSE = "pause";
	public static final String COMMAND_DESTROY = "destroy";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		String command = getIntent().getStringExtra(EXTRA_COMMAND);
		if (COMMAND_DESTROY.equals(command)) {
			MidletThread.destroyApp();
		} else if (COMMAND_PAUSE.equals(command)) {
			// This activity can be launched in a separate task by instrumentation, which does not
			// reliably drive MicroActivity through ON_STOP. Finish the real shell activity instead;
			// its normal Android lifecycle then exercises the production pauseApp() path before
			// destruction. A pause failure claims fatal teardown first, so the queued destroy path
			// cannot overwrite the failure.
			MicroActivity activity = ContextHolder.getActivity();
			if (activity != null) {
				activity.finish();
			}
		}
		finish();
	}
}
