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

import static org.acra.ReportField.ANDROID_VERSION;
import static org.acra.ReportField.APP_VERSION_NAME;
import static org.acra.ReportField.BRAND;
import static org.acra.ReportField.CUSTOM_DATA;
import static org.acra.ReportField.PHONE_MODEL;
import static org.acra.ReportField.REPORT_ID;
import static org.acra.ReportField.STACK_TRACE;
import static org.acra.ReportField.THREAD_DETAILS;

import android.content.Context;
import android.util.Log;

import org.acra.data.CrashReportData;
import org.acra.file.CrashReportPersister;
import org.acra.file.ReportLocator;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads, correlates, renders, and deletes the bounded local diagnostic records. */
public final class LocalDiagnosticRepository {
	private static final String TAG = LocalDiagnosticRepository.class.getSimpleName();
	private static final String KEY_PROCESS_NAME = "jlmod.process.name";
	private static final String KEY_PROCESS_ROLE = "jlmod.process.role";
	private static final String KEY_PROCESS_PID = "jlmod.process.pid";
	private static final String KEY_MIDLET_NAME = "jlmod.midlet.name";
	private static final String KEY_MIDLET_VENDOR = "jlmod.midlet.vendor";
	private static final String KEY_MIDLET_VERSION = "jlmod.midlet.version";
	private static final String KEY_MIDLET_JAR_SIZE = "jlmod.midlet.jar.size";
	private static final String KEY_MIDLET_JAR_SHA256 = "jlmod.midlet.jar.sha256";
	private static final String KEY_MIDLET_MAIN_CLASS = "jlmod.midlet.mainClass";
	private static final String KEY_SESSION_ID = "jlmod.session.id";

	private LocalDiagnosticRepository() {}

	public static List<Record> load(Context context) {
		ArrayList<MutableRecord> journalRecords = readFailureJournals(context);
		Map<String, MutableRecord> bySession = new HashMap<>();
		for (MutableRecord record : journalRecords) {
			if (record.sessionId != null) {
				bySession.put(record.sessionId, record);
			}
		}

		ArrayList<Record> standaloneReports = new ArrayList<>();
		for (RawJavaReport raw : readRawJavaReports(context)) {
			MutableRecord journal = bySession.get(raw.sessionId);
			if (journal != null && isExactEventMatch(
					journal.sessionId, journal.eventId, raw.sessionId, raw.stackTrace)) {
				journal.attach(raw);
			} else {
				standaloneReports.add(Record.fromRaw(raw));
			}
		}

		ArrayList<Record> records = new ArrayList<>(journalRecords.size() + standaloneReports.size());
		for (MutableRecord journal : journalRecords) {
			records.add(journal.freeze());
		}
		records.addAll(standaloneReports);
		Collections.sort(records, (left, right) -> {
			if (left.timestampMillis == right.timestampMillis) {
				return left.id.compareTo(right.id);
			}
			return left.timestampMillis < right.timestampMillis ? 1 : -1;
		});
		return records;
	}

	public static Record find(Context context, String id) {
		if (id == null) {
			return null;
		}
		for (Record record : load(context)) {
			if (id.equals(record.id)) {
				return record;
			}
		}
		return null;
	}

	/** Deletes exactly the selected logical record. Correlated raw files are deleted before journal. */
	public static boolean delete(Context context, Record record) {
		if (record == null) {
			return false;
		}
		boolean success = true;
		for (File rawFile : record.rawFiles) {
			if (rawFile.isFile() && !rawFile.delete()) {
				Log.w(TAG, "Unable to delete raw crash report: " + rawFile.getName());
				success = false;
			}
		}
		if (!success) {
			return false;
		}
		if (record.journalFile != null) {
			if (!MidletSessionJournal.delete(record.journalFile)) {
				Log.w(TAG, "Unable to delete MIDlet failure journal: " + record.journalFile.getName());
				return false;
			}
			// Do not drop the recovery acknowledgment until the durable failure journal is gone.
			MidletFailureRecovery.deleteAcknowledgment(context, record.eventId);
		}
		return true;
	}

	static boolean isExactEventMatch(String journalSessionId, String eventId, String rawSessionId,
			String stackTrace) {
		return journalSessionId != null
				&& journalSessionId.equals(rawSessionId)
				&& MidletFailureRecovery.isSafeEventId(eventId)
				&& stackTrace != null
				&& stackTrace.contains("eventId=" + eventId + ";");
	}

	private static ArrayList<MutableRecord> readFailureJournals(Context context) {
		List<File> files = MidletSessionJournal.journalFiles(context);
		ArrayList<MutableRecord> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(file);
				if (snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
						&& MidletFailureRecovery.isSafeEventId(snapshot.failureEventId)) {
					records.add(new MutableRecord(file, snapshot));
				}
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "Ignoring unreadable MIDlet diagnostic journal: " + file.getName(), e);
			}
		}
		return records;
	}

	private static List<RawJavaReport> readRawJavaReports(Context context) {
		ReportLocator locator = new ReportLocator(context);
		ArrayList<File> files = new ArrayList<>();
		Collections.addAll(files, locator.getApprovedReports());
		Collections.addAll(files, locator.getUnapprovedReports());
		ArrayList<RawJavaReport> reports = new ArrayList<>(files.size());
		CrashReportPersister persister = new CrashReportPersister();
		Set<String> seenPaths = new HashSet<>();
		for (File file : files) {
			if (file == null || !file.isFile() || !seenPaths.add(file.getAbsolutePath())) {
				continue;
			}
			try {
				CrashReportData data = persister.load(file);
				reports.add(RawJavaReport.from(file, data));
			} catch (Exception e) {
				Log.w(TAG, "Ignoring unreadable local Java crash report: " + file.getName(), e);
			}
		}
		return reports;
	}

	private static String stringValue(Object value) {
		if (value == null || JSONObject.NULL.equals(value)) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static String custom(JSONObject custom, String key) {
		return custom == null ? null : stringValue(custom.opt(key));
	}

	private static void appendLine(StringBuilder text, String label, String value) {
		if (value != null && !value.isEmpty()) {
			text.append(label).append(": ").append(value).append('\n');
		}
	}

	public enum Kind {
		MIDLET_FAILURE,
		JAVA_REPORT
	}

	public static final class Record {
		private final String id;
		private final Kind kind;
		private final long timestampMillis;
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final String processRole;
		private final String stackTrace;
		private final String detailText;
		private final File journalFile;
		private final List<File> rawFiles;

		private Record(String id, Kind kind, long timestampMillis, String eventId, String sessionId,
				String midletName, String processRole, String stackTrace, String detailText,
				File journalFile, List<File> rawFiles) {
			this.id = id;
			this.kind = kind;
			this.timestampMillis = timestampMillis;
			this.eventId = eventId;
			this.sessionId = sessionId;
			this.midletName = midletName;
			this.processRole = processRole;
			this.stackTrace = stackTrace;
			this.detailText = detailText;
			this.journalFile = journalFile;
			this.rawFiles = Collections.unmodifiableList(new ArrayList<>(rawFiles));
		}

		private static Record fromRaw(RawJavaReport raw) {
			StringBuilder detail = new StringBuilder();
			detail.append("Type: Java diagnostic report\n");
			appendLine(detail, "Report ID", raw.reportId);
			appendLine(detail, "Session ID", raw.sessionId);
			appendLine(detail, "Process role", raw.processRole);
			appendLine(detail, "Process name", raw.processName);
			appendLine(detail, "Process PID", raw.processPid);
			appendLine(detail, "MIDlet", raw.midletName);
			appendLine(detail, "Vendor", raw.midletVendor);
			appendLine(detail, "Version", raw.midletVersion);
			appendLine(detail, "Entrypoint", raw.mainClass);
			appendLine(detail, "JAR size", raw.jarSize);
			appendLine(detail, "JAR SHA-256", raw.jarSha256);
			appendLine(detail, "App version", raw.appVersion);
			appendLine(detail, "Android", raw.androidVersion);
			appendLine(detail, "Device", joinDevice(raw.brand, raw.phoneModel));
			appendLine(detail, "Thread", raw.threadDetails);
			if (raw.stackTrace != null) {
				detail.append("\nStack trace:\n").append(raw.stackTrace.trim()).append('\n');
			}
			String reportKey = raw.reportId != null ? raw.reportId : raw.file.getName();
			return new Record(
					"acra:" + reportKey,
					Kind.JAVA_REPORT,
					raw.timestampMillis,
					null,
					raw.sessionId,
					raw.midletName,
					raw.processRole,
					raw.stackTrace,
					detail.toString().trim(),
					null,
					Collections.singletonList(raw.file)
			);
		}

		public String getId() {
			return id;
		}

		public Kind getKind() {
			return kind;
		}

		public long getTimestampMillis() {
			return timestampMillis;
		}

		public String getEventId() {
			return eventId;
		}

		public String getSessionId() {
			return sessionId;
		}

		public String getMidletName() {
			return midletName;
		}

		public String getProcessRole() {
			return processRole;
		}

		public String getStackTrace() {
			return stackTrace;
		}

		public String getDetailText() {
			return detailText;
		}

		public boolean hasJavaReport() {
			return !rawFiles.isEmpty();
		}
	}

	private static final class MutableRecord {
		private final File journalFile;
		private final MidletSessionJournal.Snapshot snapshot;
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final ArrayList<RawJavaReport> rawReports = new ArrayList<>();

		private MutableRecord(File journalFile, MidletSessionJournal.Snapshot snapshot) {
			this.journalFile = journalFile;
			this.snapshot = snapshot;
			this.eventId = snapshot.failureEventId;
			this.sessionId = snapshot.sessionId;
			this.midletName = snapshot.midletName;
		}

		private void attach(RawJavaReport raw) {
			rawReports.add(raw);
		}

		private Record freeze() {
			StringBuilder detail = new StringBuilder();
			detail.append("Type: MIDlet session failure\n");
			appendLine(detail, "Event ID", eventId);
			appendLine(detail, "Session ID", sessionId);
			appendLine(detail, "Lifecycle stage", snapshot.stage == null ? null : snapshot.stage.name());
			appendLine(detail, "Failure boundary", snapshot.failureBoundary == null
					? null : snapshot.failureBoundary.name());
			appendLine(detail, "Process name", snapshot.processName);
			detail.append("Process PID: ").append(snapshot.processPid).append('\n');
			appendLine(detail, "MIDlet", snapshot.midletName);
			appendLine(detail, "Vendor", snapshot.midletVendor);
			appendLine(detail, "Version", snapshot.midletVersion);
			appendLine(detail, "Entrypoint", snapshot.mainClass);
			appendLine(detail, "JAR size", snapshot.jarSize);
			appendLine(detail, "JAR SHA-256", snapshot.jarSha256);
			detail.append("Java report attached: ").append(rawReports.isEmpty() ? "no" : "yes").append('\n');

			String stackTrace = null;
			String processRole = "midlet";
			ArrayList<File> rawFiles = new ArrayList<>();
			for (RawJavaReport raw : rawReports) {
				rawFiles.add(raw.file);
				if (stackTrace == null && raw.stackTrace != null) {
					stackTrace = raw.stackTrace;
				}
				if (raw.processRole != null) {
					processRole = raw.processRole;
				}
				appendLine(detail, "ACRA report ID", raw.reportId);
				appendLine(detail, "Android", raw.androidVersion);
				appendLine(detail, "Device", joinDevice(raw.brand, raw.phoneModel));
				appendLine(detail, "Thread", raw.threadDetails);
			}
			if (stackTrace != null) {
				detail.append("\nStack trace:\n").append(stackTrace.trim()).append('\n');
			}
			return new Record(
					"event:" + eventId,
					Kind.MIDLET_FAILURE,
					snapshot.updatedWallTimeMillis,
					eventId,
					sessionId,
					midletName,
					processRole,
					stackTrace,
					detail.toString().trim(),
					journalFile,
					rawFiles
			);
		}
	}

	private static final class RawJavaReport {
		private final File file;
		private final long timestampMillis;
		private final String reportId;
		private final String sessionId;
		private final String processRole;
		private final String processName;
		private final String processPid;
		private final String midletName;
		private final String midletVendor;
		private final String midletVersion;
		private final String mainClass;
		private final String jarSize;
		private final String jarSha256;
		private final String appVersion;
		private final String androidVersion;
		private final String brand;
		private final String phoneModel;
		private final String threadDetails;
		private final String stackTrace;

		private RawJavaReport(File file, long timestampMillis, String reportId, String sessionId,
				String processRole, String processName, String processPid, String midletName,
				String midletVendor, String midletVersion, String mainClass, String jarSize,
				String jarSha256, String appVersion, String androidVersion, String brand,
				String phoneModel, String threadDetails, String stackTrace) {
			this.file = file;
			this.timestampMillis = timestampMillis;
			this.reportId = reportId;
			this.sessionId = sessionId;
			this.processRole = processRole;
			this.processName = processName;
			this.processPid = processPid;
			this.midletName = midletName;
			this.midletVendor = midletVendor;
			this.midletVersion = midletVersion;
			this.mainClass = mainClass;
			this.jarSize = jarSize;
			this.jarSha256 = jarSha256;
			this.appVersion = appVersion;
			this.androidVersion = androidVersion;
			this.brand = brand;
			this.phoneModel = phoneModel;
			this.threadDetails = threadDetails;
			this.stackTrace = stackTrace;
		}

		private static RawJavaReport from(File file, CrashReportData data) {
			Object customValue = data.get(CUSTOM_DATA.toString());
			JSONObject custom = customValue instanceof JSONObject ? (JSONObject) customValue : null;
			return new RawJavaReport(
					file,
					file.lastModified(),
					stringValue(data.get(REPORT_ID.toString())),
					custom(custom, KEY_SESSION_ID),
					custom(custom, KEY_PROCESS_ROLE),
					custom(custom, KEY_PROCESS_NAME),
					custom(custom, KEY_PROCESS_PID),
					custom(custom, KEY_MIDLET_NAME),
					custom(custom, KEY_MIDLET_VENDOR),
					custom(custom, KEY_MIDLET_VERSION),
					custom(custom, KEY_MIDLET_MAIN_CLASS),
					custom(custom, KEY_MIDLET_JAR_SIZE),
					custom(custom, KEY_MIDLET_JAR_SHA256),
					stringValue(data.get(APP_VERSION_NAME.toString())),
					stringValue(data.get(ANDROID_VERSION.toString())),
					stringValue(data.get(BRAND.toString())),
					stringValue(data.get(PHONE_MODEL.toString())),
					stringValue(data.get(THREAD_DETAILS.toString())),
					stringValue(data.get(STACK_TRACE.toString()))
			);
		}
	}

	private static String joinDevice(String brand, String model) {
		if (brand == null) {
			return model;
		}
		if (model == null) {
			return brand;
		}
		return brand + " " + model;
	}
}
