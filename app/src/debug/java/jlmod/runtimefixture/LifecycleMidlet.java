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
	public static final String MODE_CRASH_START = "crash-start";
	public static final String MODE_CLEAN = "clean";
	public static final String START_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture start failure";

	@Override
	public void startApp() {
		String mode = getAppProperty(MODE_PROPERTY);
		if (MODE_CRASH_START.equals(mode)) {
			throw new IllegalStateException(START_FAILURE_MARKER);
		}
		if (MODE_CLEAN.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			notifyDestroyed();
			return;
		}
		throw new IllegalStateException("Unknown lifecycle runtime fixture mode: " + mode);
	}

	@Override
	public void pauseApp() {}

	@Override
	public void destroyApp(boolean unconditional) throws MIDletStateChangeException {}

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
