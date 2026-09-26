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

package io.github.h3nb.jlmodplus.crashes;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the one public derived bundle associated with a diagnostic record.
 *
 * <p>The mapping is ownership metadata only. Diagnostic truth remains in
 * {@link LocalDiagnosticRepository} and retained evidence.</p>
 */
final class DiagnosticBundleStore {
	static final String PUBLIC_DIRECTORY = "JL-Mod Plus Diagnostics";

	private static final String PREFS = "diagnostic_bundle_ownership";
	private static final String KEY_URI = ".uri";
	private static final String KEY_FILE = ".file";
	private static final String KEY_FORMAT = ".format";
	private static final String KEY_CONTENT = ".content";
	private static final String MIME_ZIP = "application/zip";

	private DiagnosticBundleStore() {}

	static synchronized PreparedBundle ensure(
			Context context, LocalDiagnosticRepository.Record record) throws IOException {
		if (context == null || record == null || record.getIncidentSummary() == null) {
			throw new IOException("Missing diagnostic incident");
		}

		IncidentSummary incident = record.getIncidentSummary();
		String fileName = incident.bundleFileName();
		String key = key(record.getId());
		SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		NativeTombstoneSummary.Summary nativeSummary = nativeSummary(context, record);

		String javaStack = DiagnosticExportSanitizer.sanitize(context, record.getStackTrace());
		String report = DiagnosticExportSanitizer.sanitize(
				context, GitHubDiagnosticIssue.reportMarkdown(
						incident, nativeSummary, fileName, javaStack));
		String incidentJson = DiagnosticBundleFormat.incidentJson(
				incident,
				nativeSummary,
				value -> DiagnosticExportSanitizer.sanitize(context, value));

		String anrTrace = null;
		ProcessExitStore.Snapshot exit = record.getProcessExitSnapshot();
		if (exit != null && "anr-text".equals(exit.traceKind)) {
			anrTrace = DiagnosticExportSanitizer.sanitize(
					context, ProcessExitStore.readDisplayTrace(exit));
		}
		String tombstone = nativeSummary == null ? null
				: DiagnosticExportSanitizer.sanitize(
						context, NativeTombstoneSummary.format(nativeSummary));
		String contentFingerprint = DiagnosticBundleFormat.contentFingerprint(
				report, incidentJson, anrTrace, tombstone);

		Metadata existing = read(preferences, key);
		if (existing != null
				&& metadataMatches(existing, fileName, contentFingerprint)
				&& isReusable(context, existing)) {
			return prepared(context, incident, nativeSummary, Uri.parse(existing.uri), fileName);
		}

		if (existing != null) {
			boolean removed = deleteOwned(context, existing);
			if (!removed && ownedArtifactExists(context, existing)) {
				throw new IOException("Unable to replace tracked diagnostic bundle");
			}
			if (!clear(preferences, key)) {
				throw new IOException("Unable to clear diagnostic bundle ownership");
			}
		}

		Metadata complete = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
				? Api29Impl.write(
						context, preferences, key, fileName, contentFingerprint,
						report, incidentJson, anrTrace, tombstone)
				: writeLegacy(
						preferences, key, fileName, contentFingerprint,
						report, incidentJson, anrTrace, tombstone);
		return prepared(context, incident, nativeSummary, Uri.parse(complete.uri), fileName);
	}

	/**
	 * Deletes only the exact artifact tracked for this record. Failure never implies the source
	 * diagnostic must be retained.
	 */
	static synchronized boolean deleteTracked(
			Context context, LocalDiagnosticRepository.Record record) {
		if (context == null || record == null) return true;
		String key = key(record.getId());
		SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		Metadata metadata = read(preferences, key);
		if (metadata == null) return true;

		boolean deleted = deleteOwned(context, metadata);
		if (deleted || !ownedArtifactExists(context, metadata)) {
			return clear(preferences, key);
		}
		return false;
	}

	private static PreparedBundle prepared(Context context, IncidentSummary incident,
			NativeTombstoneSummary.Summary nativeSummary, Uri uri, String fileName) {
		return new PreparedBundle(
				uri,
				fileName,
				DiagnosticExportSanitizer.sanitize(
						context, GitHubDiagnosticIssue.title(incident, nativeSummary)),
				DiagnosticExportSanitizer.sanitize(
						context, GitHubDiagnosticIssue.compactSummary(
								incident, nativeSummary, fileName)));
	}

	private static NativeTombstoneSummary.Summary nativeSummary(
			Context context, LocalDiagnosticRepository.Record record) {
		ProcessExitStore.Snapshot exit = record.getProcessExitSnapshot();
		if (exit == null || !"native-tombstone-protobuf".equals(exit.traceKind)) return null;
		DiagnosticTraceAttachment.Attachment attachment = DiagnosticTraceAttachment.find(
				context, record.getId(), record.getSessionId());
		return attachment == null ? null : NativeTombstoneSummary.read(context, attachment.uri);
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private static final class Api29Impl {
		static Metadata write(Context context, SharedPreferences preferences, String key,
				String fileName, String contentFingerprint, String report, String incidentJson,
				String anrTrace, String tombstone) throws IOException {
			ContentResolver resolver = context.getContentResolver();
			ContentValues values = new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
			values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_ZIP);
			values.put(MediaStore.MediaColumns.RELATIVE_PATH,
					Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIRECTORY);
			values.put(MediaStore.MediaColumns.IS_PENDING, 1);
			Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
			if (uri == null) throw new IOException("Unable to create diagnostic bundle");

			Metadata metadata = new Metadata(
					uri.toString(), fileName, DiagnosticBundleFormat.FORMAT_VERSION,
					contentFingerprint);
			if (!exists(context, metadata)) {
				deleteMediaStoreUri(resolver, uri);
				throw new IOException("Diagnostic bundle was created with an unexpected identity");
			}
			if (!claim(preferences, key, metadata)) {
				deleteMediaStoreUri(resolver, uri);
				throw new IOException("Unable to persist diagnostic bundle ownership");
			}

			boolean complete = false;
			try {
				try (OutputStream output = resolver.openOutputStream(uri, "w")) {
					if (output == null) throw new IOException("Unable to open diagnostic bundle");
					DiagnosticBundleFormat.write(output, report, incidentJson, anrTrace, tombstone);
				}
				ContentValues publish = new ContentValues();
				publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
				if (resolver.update(uri, publish, null, null) != 1) {
					throw new IOException("Unable to publish diagnostic bundle");
				}
				if (!isReusable(context, metadata)) {
					throw new IOException("Published diagnostic bundle is not readable");
				}
				complete = true;
				return metadata;
			} finally {
				if (!complete) cleanupFailedWrite(context, preferences, key, metadata);
			}
		}

		private static void deleteMediaStoreUri(ContentResolver resolver, Uri uri) {
			try {
				resolver.delete(uri, null, null);
			} catch (RuntimeException ignored) {}
		}
	}

	@SuppressWarnings("deprecation")
	private static Metadata writeLegacy(SharedPreferences preferences, String key,
			String fileName, String contentFingerprint, String report, String incidentJson,
			String anrTrace, String tombstone) throws IOException {
		File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
		File directory = new File(downloads, PUBLIC_DIRECTORY);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("Unable to create diagnostic bundle directory");
		}

		File destination = new File(directory, fileName);
		File partial = new File(directory, "." + fileName + ".part");
		if (destination.exists() || partial.exists()) {
			// Without ownership metadata neither public file is ours to replace.
			throw new IOException("Diagnostic bundle filename is already in use");
		}

		Metadata metadata = new Metadata(
				Uri.fromFile(destination).toString(), fileName,
				DiagnosticBundleFormat.FORMAT_VERSION, contentFingerprint);
		if (!claim(preferences, key, metadata)) {
			throw new IOException("Unable to persist diagnostic bundle ownership");
		}

		boolean complete = false;
		try {
			try (FileOutputStream output = new FileOutputStream(partial, false)) {
				DiagnosticBundleFormat.write(output, report, incidentJson, anrTrace, tombstone);
				output.getFD().sync();
			}
			// Never deliberately replace an untracked public file.
			if (destination.exists() || !partial.renameTo(destination)) {
				throw new IOException("Unable to publish diagnostic bundle safely");
			}
			if (!isReusable(null, metadata)) {
				throw new IOException("Published diagnostic bundle is not readable");
			}
			complete = true;
			return metadata;
		} finally {
			if (!complete) cleanupFailedWrite(null, preferences, key, metadata);
		}
	}

	private static void cleanupFailedWrite(Context context, SharedPreferences preferences,
			String key, Metadata metadata) {
		if (deleteOwned(context, metadata) || !ownedArtifactExists(context, metadata)) {
			clear(preferences, key);
		}
		// If cleanup fails, retain ownership metadata so retry/delete can clean the exact artifact.
	}

	private static boolean claim(
			SharedPreferences preferences, String key, Metadata metadata) {
		return preferences.edit()
				.putString(key + KEY_URI, metadata.uri)
				.putString(key + KEY_FILE, metadata.fileName)
				.putInt(key + KEY_FORMAT, metadata.formatVersion)
				.putString(key + KEY_CONTENT, metadata.contentFingerprint)
				.commit();
	}

	private static boolean metadataMatches(
			Metadata metadata, String fileName, String contentFingerprint) {
		return metadata.formatVersion == DiagnosticBundleFormat.FORMAT_VERSION
				&& fileName.equals(metadata.fileName)
				&& contentFingerprint.equals(metadata.contentFingerprint);
	}

	private static boolean isReusable(Context context, Metadata metadata) {
		if (!exists(context, metadata)) return false;
		try (InputStream raw = open(context, metadata);
				ZipInputStream zip = raw == null ? null : new ZipInputStream(raw)) {
			if (zip == null) return false;
			boolean report = false;
			boolean incident = false;
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (DiagnosticBundleFormat.REPORT_ENTRY.equals(entry.getName())) report = true;
				if (DiagnosticBundleFormat.INCIDENT_ENTRY.equals(entry.getName())) incident = true;
				if (report && incident) return true;
			}
			return false;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static boolean exists(Context context, Metadata metadata) {
		if (metadata == null || metadata.uri == null) return false;
		Uri uri = Uri.parse(metadata.uri);
		if ("file".equals(uri.getScheme())) {
			File file = safeLegacyFile(uri, metadata.fileName);
			return file != null && file.isFile();
		}
		if (context == null
				|| !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
				|| !"media".equals(uri.getAuthority())) {
			return false;
		}
		try (Cursor cursor = context.getContentResolver().query(
				uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			return cursor != null && cursor.moveToFirst()
					&& metadata.fileName.equals(cursor.getString(0));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static InputStream open(Context context, Metadata metadata) throws IOException {
		Uri uri = Uri.parse(metadata.uri);
		if ("file".equals(uri.getScheme())) {
			File file = safeLegacyFile(uri, metadata.fileName);
			return file == null ? null : new FileInputStream(file);
		}
		return context == null ? null : context.getContentResolver().openInputStream(uri);
	}

	private static boolean ownedArtifactExists(Context context, Metadata metadata) {
		if (metadata == null || metadata.uri == null) return false;
		Uri uri = Uri.parse(metadata.uri);
		if ("file".equals(uri.getScheme())) {
			File file = safeLegacyFile(uri, metadata.fileName);
			File partial = safeLegacyPartial(uri, metadata.fileName);
			return file != null && file.exists() || partial != null && partial.exists();
		}
		return exists(context, metadata);
	}

	private static boolean deleteOwned(Context context, Metadata metadata) {
		if (metadata == null || metadata.fileName == null || metadata.uri == null) return false;
		Uri uri = Uri.parse(metadata.uri);
		try {
			if ("file".equals(uri.getScheme())) {
				File file = safeLegacyFile(uri, metadata.fileName);
				File partial = safeLegacyPartial(uri, metadata.fileName);
				if (file == null || partial == null) return false;
				boolean fileDeleted = !file.exists() || file.isFile() && file.delete();
				boolean partialDeleted = !partial.exists() || partial.isFile() && partial.delete();
				return fileDeleted && partialDeleted;
			}
			if (context == null
					|| !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
					|| !"media".equals(uri.getAuthority())) {
				return false;
			}
			if (!exists(context, metadata)) return true;
			return context.getContentResolver().delete(uri, null, null) == 1;
		} catch (RuntimeException e) {
			return false;
		}
	}

	@SuppressWarnings("deprecation")
	private static File safeLegacyFile(Uri uri, String expectedFileName) {
		if (uri == null || expectedFileName == null || uri.getPath() == null) return null;
		try {
			File root = new File(
					Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
					PUBLIC_DIRECTORY).getCanonicalFile();
			File file = new File(uri.getPath()).getCanonicalFile();
			File parent = file.getParentFile();
			return parent != null && root.equals(parent.getCanonicalFile())
					&& expectedFileName.equals(file.getName()) ? file : null;
		} catch (IOException | SecurityException e) {
			return null;
		}
	}

	private static File safeLegacyPartial(Uri uri, String expectedFileName) {
		File file = safeLegacyFile(uri, expectedFileName);
		if (file == null || file.getParentFile() == null) return null;
		return new File(file.getParentFile(), "." + expectedFileName + ".part");
	}

	private static Metadata read(SharedPreferences preferences, String key) {
		String uri = preferences.getString(key + KEY_URI, null);
		String file = preferences.getString(key + KEY_FILE, null);
		String contentFingerprint = preferences.getString(key + KEY_CONTENT, null);
		if (uri == null || file == null || contentFingerprint == null) return null;
		return new Metadata(
				uri, file, preferences.getInt(key + KEY_FORMAT, -1), contentFingerprint);
	}

	private static boolean clear(SharedPreferences preferences, String key) {
		return preferences.edit()
				.remove(key + KEY_URI)
				.remove(key + KEY_FILE)
				.remove(key + KEY_FORMAT)
				.remove(key + KEY_CONTENT)
				.commit();
	}

	private static String key(String recordId) {
		String value = recordId == null ? "" : recordId;
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder("record.");
			for (int i = 0; i < 8; i++) {
				result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError("SHA-256 unavailable", impossible);
		}
	}

	static final class PreparedBundle {
		final Uri uri;
		final String fileName;
		final String issueTitle;
		final String issueSummary;

		PreparedBundle(Uri uri, String fileName, String issueTitle, String issueSummary) {
			this.uri = uri;
			this.fileName = fileName;
			this.issueTitle = issueTitle;
			this.issueSummary = issueSummary;
		}
	}

	private static final class Metadata {
		final String uri;
		final String fileName;
		final int formatVersion;
		final String contentFingerprint;

		Metadata(String uri, String fileName, int formatVersion, String contentFingerprint) {
			this.uri = uri;
			this.fileName = fileName;
			this.formatVersion = formatVersion;
			this.contentFingerprint = contentFingerprint;
		}
	}
}
