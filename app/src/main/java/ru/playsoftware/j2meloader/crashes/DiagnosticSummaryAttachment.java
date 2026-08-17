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

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Creates a small sanitized text attachment only when raw diagnostic evidence is also shared. */
final class DiagnosticSummaryAttachment {
	private static final String PROVIDER_SUFFIX = ".diagnostic-files";
	private static final String SHARE_DIR = "diagnostics-share";
	private static final String FILE_NAME = "JL-Mod-Plus-diagnostic.txt";

	private DiagnosticSummaryAttachment() {}

	static Attachment create(Context context, String recordId, String text) {
		if (context == null || text == null || text.isEmpty()) return null;
		String key = Integer.toHexString(recordId == null ? 0 : recordId.hashCode());
		File directory = new File(new File(context.getCacheDir(), SHARE_DIR), key);
		if (!directory.isDirectory() && !directory.mkdirs()) return null;
		File file = new File(directory, FILE_NAME);
		try (FileOutputStream output = new FileOutputStream(file, false)) {
			output.write(text.getBytes(StandardCharsets.UTF_8));
			output.flush();
			Uri uri = FileProvider.getUriForFile(
					context, context.getPackageName() + PROVIDER_SUFFIX, file);
			return new Attachment(uri);
		} catch (IOException | IllegalArgumentException | SecurityException e) {
			return null;
		}
	}

	static final class Attachment {
		final Uri uri;

		Attachment(Uri uri) {
			this.uri = uri;
		}
	}
}
