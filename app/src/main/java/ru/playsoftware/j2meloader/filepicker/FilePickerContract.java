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

package ru.playsoftware.j2meloader.filepicker;

/**
 * Stable intent contract for the app-owned file picker.
 *
 * <p>The key names intentionally remain compatible with the previous internal
 * launchers. Callers should use this contract rather than depending on a
 * third-party picker class.</p>
 */
public final class FilePickerContract {
	public static final String EXTRA_START_PATH = "nononsense.intent.START_PATH";
	public static final String EXTRA_MODE = "nononsense.intent.MODE";
	public static final String EXTRA_ALLOW_CREATE_DIR = "nononsense.intent.ALLOW_CREATE_DIR";
	public static final String EXTRA_SINGLE_CLICK = "nononsense.intent.SINGLE_CLICK";
	public static final String EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE";
	public static final String EXTRA_ALLOW_EXISTING_FILE = "android.intent.extra.ALLOW_EXISTING_FILE";
	public static final String EXTRA_PATHS = "nononsense.intent.PATHS";

	public static final int MODE_FILE = 0;
	public static final int MODE_DIR = 1;
	public static final int MODE_FILE_AND_DIR = 2;
	public static final int MODE_NEW_FILE = 3;

	private FilePickerContract() {
	}
}
