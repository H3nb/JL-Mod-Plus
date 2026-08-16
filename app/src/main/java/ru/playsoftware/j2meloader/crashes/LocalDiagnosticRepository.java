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

import android.app.ApplicationExitInfo;
import android.content.Context;
import android.system.OsConstants;
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

	/** Self-contained load for background/non-UI callers that also snapshots framework history. */
	public static List<Record> load(Context context) {
		// Snapshot system exit history before reading the local projection; Android keeps it in a
		// bounded ring and traces can be overwritten independently of our app-private records.
		ProcessExitStore.ingest(context);
		return loadStored(context);
	}

	/** Reads only the already-maintained durable projection without historical ingestion. */
	public static List<Record> loadStored(Context context) {
		ArrayList<SessionRecord> sessions = readSessionRecords(context);
		Map<String, SessionRecord> allSessions = new HashMap<>();
		ArrayList<MutableRecord> journalRecords = new ArrayList<>();
		Map<String, MutableRecord> failuresBySession = new HashMap<>();
		for (SessionRecord session : sessions) {
			if (session.snapshot.sessionId != null) {
				allSessions.put(session.snapshot.sessionId, session);
			}
			if (session.snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
					&& MidletFailureRecovery.isSafeEventId(session.snapshot.failureEventId)) {
				MutableRecord failure = new MutableRecord(session);
				journalRecords.add(failure);
				failuresBySession.put(failure.sessionId, failure);
			}
		}

		ArrayList<Record> standaloneReports = new ArrayList<>();
		for (RawJavaReport raw : readRawJavaReports(context)) {
			MutableRecord journal = failuresBySession.get(raw.sessionId);
			if (journal != null && isExactEventMatch(
					journal.sessionId, journal.eventId, raw.sessionId, raw.stackTrace)) {
				journal.attach(raw);
			} else {
				standaloneReports.add(Record.fromRaw(raw));
			}
		}

		for (ProcessExitStore.Snapshot exit : ProcessExitStore.loadStored(context)) {
			MutableRecord journal = failuresBySession.get(exit.sessionId);
			if (journal != null) {
				// Process state summary carries the exact immutable session ID. No timestamp/PID
				// heuristic is needed to enrich the existing MIDlet failure logical record.
				journal.attach(exit);
			} else {
				standaloneReports.add(Record.fromProcessExit(exit, allSessions.get(exit.sessionId)));
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

	public static Record findStored(Context context, String id) {
		if (id == null) {
			return null;
		}
		for (Record record : loadStored(context)) {
			if (id.equals(record.id)) {
				return record;
			}
		}
		return null;
	}

	/**
	 * Deletes exactly the selected logical record. The durable suppression marker is created only
	 * immediately before process-exit evidence is removed, so an earlier dependent-file failure does
	 * not hide evidence that the failed delete left behind.
	 */
	public static boolean delete(Context context, Record record) {
		if (record == null) {
			return false;
		}
		for (File rawFile : record.rawFiles) {
			if (rawFile.isFile() && !rawFile.delete()) {
				Log.w(TAG, "Unable to delete raw crash report: " + rawFile.getName());
				return false;
			}
		}
		if (record.journalFile != null) {
			if (!MidletSessionJournal.delete(record.journalFile)) {
				Log.w(TAG, "Unable to delete MIDlet session journal: " + record.journalFile.getName());
				return false;
			}
			// Do not drop the recovery acknowledgment until the durable journal is gone.
			MidletFailureRecovery.deleteAcknowledgment(context, record.eventId);
		}
		if (record.processExit != null) {
			if (!ProcessExitDeletionStore.markDeleted(context, record.processExit.key)) {
				Log.w(TAG, "Unable to persist process-exit deletion marker: " + record.processExit.key);
				return false;
			}
			if (!ProcessExitStore.delete(context, record.processExit)) {
				Log.w(TAG, "Unable to delete process-exit diagnostic: " + record.processExit.key);
				return false;
			}
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

	private static ArrayList<SessionRecord> readSessionRecords(Context context) {
		List<File> files = MidletSessionJournal.journalFiles(context);
		ArrayList<SessionRecord> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				records.add(new SessionRecord(file, MidletSessionJournal.read(file)));
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

	private static void appendPositiveKb(StringBuilder text, String label, long value) {
		if (value > 0) {
			text.append(label).append(": ").append(value).append(" kB\n");
		}
	}

	private static void appendSessionDetails(StringBuilder detail, MidletSessionJournal.Snapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		appendLine(detail, "Session ID", snapshot.sessionId);
		appendLine(detail, "Lifecycle stage", snapshot.stage == null ? null : snapshot.stage.name());
		appendLine(detail, "Session outcome", snapshot.outcome == null ? null : snapshot.outcome.name());
		appendLine(detail, "MIDlet", snapshot.midletName);
		appendLine(detail, "Vendor", snapshot.midletVendor);
		appendLine(detail, "Version", snapshot.midletVersion);
		appendLine(detail, "Entrypoint", snapshot.mainClass);
		appendLine(detail, "JAR size", snapshot.jarSize);
		appendLine(detail, "JAR SHA-256", snapshot.jarSha256);
	}

	private static void appendProcessExitDetails(StringBuilder detail, ProcessExitStore.Snapshot exit) {
		if (exit == null) {
			return;
		}
		appendLine(detail, "Exit reason", ProcessExitStore.reasonLabel(exit.reason)
				+ " (" + exit.reason + ")");
		appendLine(detail, "Process role", exit.processRole);
		appendLine(detail, "Process name", exit.processName);
		if (exit.pid > 0) {
			detail.append("Process PID: ").append(exit.pid).append('\n');
		}
		if (exit.reason == ApplicationExitInfo.REASON_SIGNALED
				|| exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.status != 0) {
			appendLine(detail, "Exit status", ProcessExitStore.statusLabel(exit));
		}
		appendLine(detail, "Process importance", ProcessExitStore.importanceLabel(exit.importance));
		appendPositiveKb(detail, "Last PSS sample", exit.pssKb);
		appendPositiveKb(detail, "Last RSS sample", exit.rssKb);
		appendLine(detail, "System description", exit.description);
		if (exit.stateVersionCode >= 0) {
			detail.append("App version code at exit: ").append(exit.stateVersionCode).append('\n');
		}
		if (exit.stateSdk >= 0) {
			detail.append("Android SDK at exit: ").append(exit.stateSdk).append('\n');
		}
		appendLine(detail, "Device", joinDevice(exit.deviceBrand, exit.deviceModel));
		appendLine(detail, "Primary ABI", exit.primaryAbi);
		if (exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY
				|| (exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == OsConstants.SIGKILL)) {
			detail.append("Dedicated low-memory kill classification supported: ")
					.append(exit.lowMemoryKillReportSupported ? "yes" : "no")
					.append('\n');
		}
		if (exit.traceBytes > 0) {
			String label = "native-tombstone-protobuf".equals(exit.traceKind)
					? "Native tombstone" : "System trace";
			detail.append(label).append(": captured, ").append(exit.traceBytes).append(" bytes");
			if (exit.traceKind != null) {
				detail.append(" (").append(exit.traceKind).append(')');
			}
			if (exit.traceTruncated) {
				detail.append(" [retention limit reached]");
			}
			detail.append('\n');
		}
		String trace = ProcessExitStore.readDisplayTrace(exit);
		if (trace != null && !trace.trim().isEmpty()) {
			detail.append("\nANR trace:\n").append(trace.trim()).append('\n');
		}
	}

	public enum Kind {
		MIDLET_FAILURE,
		JAVA_REPORT,
		PROCESS_EXIT
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
		private final ProcessExitStore.Snapshot processExit;

		private Record(String id, Kind kind, long timestampMillis, String eventId, String sessionId,
				String midletName, String processRole, String stackTrace, String detailText,
				File journalFile, List<File> rawFiles, ProcessExitStore.Snapshot processExit) {
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
			this.processExit = processExit;
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
					Collections.singletonList(raw.file),
					null
			);
		}

		private static Record fromProcessExit(ProcessExitStore.Snapshot exit, SessionRecord session) {
			StringBuilder detail = new StringBuilder();
			detail.append("Type: Process exit diagnostic\n");
			appendProcessExitDetails(detail, exit);
			if (session != null) {
				detail.append('\n');
				appendSessionDetails(detail, session.snapshot);
			}
			return new Record(
					exit.id,
					Kind.PROCESS_EXIT,
					exit.timestampMillis,
					null,
					exit.sessionId,
					session == null ? null : session.snapshot.midletName,
					exit.processRole,
					null,
					detail.toString().trim(),
					session == null ? null : session.file,
					Collections.emptyList(),
					exit
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

		public boolean hasProcessExit() {
			return processExit != null;
		}
	}

	private static final class SessionRecord {
		private final File file;
		private final MidletSessionJournal.Snapshot snapshot;

		private SessionRecord(File file, MidletSessionJournal.Snapshot snapshot) {
			this.file = file;
			this.snapshot = snapshot;
		}
	}

	private static final class MutableRecord {
		private final File journalFile;
		private final MidletSessionJournal.Snapshot snapshot;
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final ArrayList<RawJavaReport> rawReports = new ArrayList<>();
		private ProcessExitStore.Snapshot processExit;

		private MutableRecord(SessionRecord session) {
			this.journalFile = session.file;
			this.snapshot = session.snapshot;
			this.eventId = snapshot.failureEventId;
			this.sessionId = snapshot.sessionId;
			this.midletName = snapshot.midletName;
		}

		private void attach(RawJavaReport raw) {
			rawReports.add(raw);
		}

		private void attach(ProcessExitStore.Snapshot exit) {
			// There should be at most one terminal ApplicationExitInfo for one process/session.
			// If malformed history yields duplicates, retain the newest rather than multiplying UI noise.
			if (processExit == null || exit.timestampMillis > processExit.timestampMillis) {
				processExit = exit;
			}
		}

		private Record freeze() {
			StringBuilder detail = new StringBuilder();
			detail.append("Type: MIDlet session failure\n");
			appendLine(detail, "Event ID", eventId);
			appendSessionDetails(detail, snapshot);
			appendLine(detail, "Failure boundary", snapshot.failureBoundary == null
					? null : snapshot.failureBoundary.name());
			appendLine(detail, "Process name", snapshot.processName);
			detail.append("Process PID: ").append(snapshot.processPid).append('\n');
			detail.append("Java report attached: ").append(rawReports.isEmpty() ? "no" : "yes").append('\n');
			detail.append("Process-exit evidence attached: ").append(processExit == null ? "no" : "yes").append('\n');

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
			if (processExit != null) {
				detail.append('\n');
				appendProcessExitDetails(detail, processExit);
				if (processExit.processRole != null) {
					processRole = processExit.processRole;
				}
			}
			if (stackTrace != null) {
				detail.append("\nStack trace:\n").append(stackTrace.trim()).append('\n');
			}
			return new Record(
					"event:" + eventId,
					Kind.MIDLET_FAILURE,
					Math.max(snapshot.updatedWallTimeMillis,
							processExit == null ? 0 : processExit.timestampMillis),
					eventId,
					sessionId,
					midletName,
					processRole,
					stackTrace,
					detail.toString().trim(),
					journalFile,
					rawFiles,
					processExit
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
