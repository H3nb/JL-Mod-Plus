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

/** Resolves an optional retained process-exit trace for explicit user-initiated sharing. */
final class DiagnosticTraceAttachment {
	private static final String PROVIDER_SUFFIX = ".diagnostic-files";

	private DiagnosticTraceAttachment() {}

	static Attachment find(Context context, String recordId, String sessionId) {
		ProcessExitStore.Snapshot candidate = null;
		for (ProcessExitStore.Snapshot snapshot : ProcessExitStore.loadStored(context)) {
			if (snapshot.traceFile == null || !snapshot.traceFile.isFile()) {
				continue;
			}
			if (snapshot.id.equals(recordId)) {
				candidate = snapshot;
				break;
			}
			if (sessionId != null && sessionId.equals(snapshot.sessionId)
					&& (candidate == null || snapshot.timestampMillis > candidate.timestampMillis)) {
				candidate = snapshot;
			}
		}
		if (candidate == null) {
			return null;
		}
		try {
			Uri uri = FileProvider.getUriForFile(
					context,
					context.getPackageName() + PROVIDER_SUFFIX,
					candidate.traceFile);
			String mimeType = "native-tombstone-protobuf".equals(candidate.traceKind)
					? "application/x-protobuf" : "text/plain";
			return new Attachment(uri, mimeType);
		} catch (IllegalArgumentException | SecurityException e) {
			// Sharing diagnostics is optional; never make the report viewer unusable.
			return null;
		}
	}

	static final class Attachment {
		final Uri uri;
		final String mimeType;

		Attachment(Uri uri, String mimeType) {
			this.uri = uri;
			this.mimeType = mimeType;
		}
	}
}
