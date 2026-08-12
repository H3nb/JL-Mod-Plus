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

package jlmod.runtimefixture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/** Debug-only MIDlet used by hosted runtime validation. */
public final class LifecycleMidlet extends MIDlet {
	public static final String CLASS_NAME = "jlmod.runtimefixture.LifecycleMidlet";
	public static final String MODE_PROPERTY = "JLMod-Runtime-Mode";
	public static final String MARKER_PROPERTY = "JLMod-Runtime-Marker";
	public static final String MODE_CRASH_INIT = "crash-init";
	public static final String MODE_CRASH_START = "crash-start";
	public static final String MODE_CRASH_WORKER = "crash-worker";
	public static final String MODE_CRASH_PAUSE = "crash-pause";
	public static final String MODE_CRASH_DESTROY = "crash-destroy";
	public static final String MODE_CLEAN = "clean";
	public static final String INIT_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture init failure";
	public static final String START_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture start failure";
	public static final String WORKER_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture worker failure";
	public static final String PAUSE_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture pause failure";
	public static final String DESTROY_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture destroy failure";

	public LifecycleMidlet() {
		if (MODE_CRASH_INIT.equals(getAppProperty(MODE_PROPERTY))) {
			throw new IllegalStateException(INIT_FAILURE_MARKER);
		}
	}

	@Override
	public void startApp() {
		String mode = getAppProperty(MODE_PROPERTY);
		if (MODE_CRASH_START.equals(mode)) {
			throw new IllegalStateException(START_FAILURE_MARKER);
		}
		if (MODE_CRASH_WORKER.equals(mode)) {
			new Thread(() -> {
				try {
					Thread.sleep(100L);
				} catch (InterruptedException ignored) {}
				throw new IllegalStateException(WORKER_FAILURE_MARKER);
			}, "LifecycleFixtureWorker").start();
			return;
		}
		if (MODE_CRASH_PAUSE.equals(mode) || MODE_CRASH_DESTROY.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			return;
		}
		if (MODE_CLEAN.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			notifyDestroyed();
			return;
		}
		throw new IllegalStateException("Unknown lifecycle runtime fixture mode: " + mode);
	}

	@Override
	public void pauseApp() {
		if (MODE_CRASH_PAUSE.equals(getAppProperty(MODE_PROPERTY))) {
			throw new IllegalStateException(PAUSE_FAILURE_MARKER);
		}
	}

	@Override
	public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
		if (MODE_CRASH_DESTROY.equals(getAppProperty(MODE_PROPERTY))) {
			throw new IllegalStateException(DESTROY_FAILURE_MARKER);
		}
	}

	private static void writeMarker(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalStateException("Lifecycle runtime fixture marker path is missing");
		}
		File marker = new File(path);
		File parent = marker.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IllegalStateException("Unable to create lifecycle fixture marker directory");
		}
		try (FileOutputStream output = new FileOutputStream(marker)) {
			output.write(1);
			output.flush();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to write lifecycle fixture marker", e);
		}
	}
}
