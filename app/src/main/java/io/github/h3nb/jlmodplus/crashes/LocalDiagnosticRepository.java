/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Correlates durable evidence into logical incidents.
 *
 * This repository does not decode active collector formats or diagnose failures. Evidence stores
 * own persistence; {@link IncidentInterpreter} owns semantic interpretation.
 */
public final class LocalDiagnosticRepository {
	private static final String TAG = LocalDiagnosticRepository.class.getSimpleName();

	private LocalDiagnosticRepository() {}

	/** Self-contained background load that also harvests/migrates external evidence sources. */
	public static List<Record> load(Context context) {
		JavaDiagnosticStore.migrateLegacyAndPrune(context);
		ProcessExitStore.ingest(context);
		return loadStored(context);
	}

	/** Reads only the already-maintained durable evidence without historical ingestion/migration. */
	public static List<Record> loadStored(Context context) {
		ArrayList<SessionRecord> sessions = readSessionRecords(context);
		Map<String, SessionRecord> sessionsById = new HashMap<>();
		Map<String, MutableRecord> failuresBySession = new HashMap<>();
		Map<String, MutableRecord> failuresByEvent = new HashMap<>();
		ArrayList<MutableRecord> journalIncidents = new ArrayList<>();
		for (SessionRecord session : sessions) {
			MidletSessionJournal.Snapshot snapshot = session.snapshot;
			if (snapshot.sessionId != null) sessionsById.put(snapshot.sessionId, session);
			if (snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
					&& MidletFailureRecovery.isSafeEventId(snapshot.failureEventId)) {
				MutableRecord incident = new MutableRecord(session);
				journalIncidents.add(incident);
				if (snapshot.sessionId != null) failuresBySession.put(snapshot.sessionId, incident);
				failuresByEvent.put(snapshot.failureEventId, incident);
			}
		}

		ArrayList<JavaDiagnosticStore.Snapshot> standaloneJava = new ArrayList<>();
		for (JavaDiagnosticStore.Snapshot java : JavaDiagnosticStore.loadStored(context)) {
			MutableRecord journal = matchingJournal(java, failuresBySession, failuresByEvent);
			if (journal != null) {
				journal.attach(java);
			} else {
				standaloneJava.add(java);
			}
		}

		ArrayList<StandaloneJavaRecord> javaIncidents = new ArrayList<>(standaloneJava.size());
		Map<String, StandaloneJavaRecord> fatalJavaBySession = new HashMap<>();
		for (JavaDiagnosticStore.Snapshot java : standaloneJava) {
			StandaloneJavaRecord record = new StandaloneJavaRecord(java);
			javaIncidents.add(record);
			if (isNewFatal(java) && java.sessionId != null) {
				fatalJavaBySession.put(java.sessionId, record);
			}
		}

		ArrayList<Record> standaloneExits = new ArrayList<>();
		for (ProcessExitStore.Snapshot exit : ProcessExitStore.loadStored(context)) {
			MutableRecord journal = exit.sessionId == null ? null : failuresBySession.get(exit.sessionId);
			if (journal != null) {
				journal.attach(exit);
				continue;
			}
			StandaloneJavaRecord java = exit.sessionId == null ? null : fatalJavaBySession.get(exit.sessionId);
			if (java != null && java.processExit == null) {
				java.processExit = exit;
				continue;
			}
			standaloneExits.add(Record.fromProcessExit(exit, sessionsById.get(exit.sessionId)));
		}

		ArrayList<Record> records = new ArrayList<>(
				journalIncidents.size() + javaIncidents.size() + standaloneExits.size());
		for (MutableRecord incident : journalIncidents) records.add(incident.freeze());
		for (StandaloneJavaRecord java : javaIncidents) records.add(java.freeze());
		records.addAll(standaloneExits);
		Collections.sort(records, (left, right) -> {
			if (left.timestampMillis == right.timestampMillis) return left.id.compareTo(right.id);
			return left.timestampMillis < right.timestampMillis ? 1 : -1;
		});
		return records;
	}

	static boolean shouldAttachToSession(MidletSessionJournal.Snapshot session,
			JavaDiagnosticStore.Snapshot java) {
		if (session == null || java == null
				|| session.outcome != MidletSessionJournal.Outcome.UNEXPECTED_FAILURE) {
			return false;
		}
		if (java.kind == JavaDiagnosticStore.Kind.FATAL_UNCAUGHT
				|| java.kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE) {
			return session.sessionId != null && session.sessionId.equals(java.sessionId);
		}
		if (!java.kind.legacy
				|| !MidletFailureRecovery.isSafeEventId(session.failureEventId)
				|| !session.failureEventId.equals(java.legacyEventId)) {
			return false;
		}
		return java.sessionId == null || session.sessionId != null && session.sessionId.equals(java.sessionId);
	}

	private static MutableRecord matchingJournal(JavaDiagnosticStore.Snapshot java,
			Map<String, MutableRecord> bySession, Map<String, MutableRecord> byEvent) {
		if (java == null) return null;
		if ((java.kind == JavaDiagnosticStore.Kind.FATAL_UNCAUGHT
				|| java.kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE)
				&& java.sessionId != null) {
			MutableRecord candidate = bySession.get(java.sessionId);
			return candidate != null && shouldAttachToSession(candidate.snapshot, java) ? candidate : null;
		}
		if (java.kind.legacy && MidletFailureRecovery.isSafeEventId(java.legacyEventId)) {
			MutableRecord candidate = byEvent.get(java.legacyEventId);
			return candidate != null && shouldAttachToSession(candidate.snapshot, java) ? candidate : null;
		}
		return null;
	}

	private static boolean isNewFatal(JavaDiagnosticStore.Snapshot java) {
		return java != null && (java.kind == JavaDiagnosticStore.Kind.FATAL_UNCAUGHT
				|| java.kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE);
	}

	public static Record find(Context context, String id) {
		if (id == null) return null;
		for (Record record : load(context)) if (id.equals(record.id)) return record;
		return null;
	}

	public static Record findStored(Context context, String id) {
		if (id == null) return null;
		for (Record record : loadStored(context)) if (id.equals(record.id)) return record;
		return null;
	}

	public static boolean delete(Context context, Record record) {
		return deleteWithArtifacts(context, record).sourceDeleted;
	}

	static DeleteResult deleteWithArtifacts(Context context, Record record) {
		if (!deleteSource(context, record)) return new DeleteResult(false, true);
		boolean bundleDeleted = DiagnosticBundleStore.deleteTracked(context, record);
		return new DeleteResult(true, bundleDeleted);
	}

	private static boolean deleteSource(Context context, Record record) {
		if (record == null) return false;
		for (JavaDiagnosticStore.Snapshot java : record.javaEvidence) {
			if (!JavaDiagnosticStore.delete(java)) {
				Log.w(TAG, "Unable to delete Java diagnostic evidence");
				return false;
			}
		}
		if (record.journalFile != null) {
			if (!MidletSessionJournal.delete(record.journalFile)) {
				Log.w(TAG, "Unable to delete MIDlet session journal: " + record.journalFile.getName());
				return false;
			}
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

	static final class DeleteResult {
		final boolean sourceDeleted;
		final boolean bundleDeleted;

		DeleteResult(boolean sourceDeleted, boolean bundleDeleted) {
			this.sourceDeleted = sourceDeleted;
			this.bundleDeleted = bundleDeleted;
		}
	}

	private static ArrayList<SessionRecord> readSessionRecords(Context context) {
		List<File> files = MidletSessionJournal.journalFiles(context);
		ArrayList<SessionRecord> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				records.add(new SessionRecord(file, MidletSessionJournal.read(file)));
			} catch (Exception error) {
				Log.w(TAG, "Ignoring unreadable MIDlet diagnostic journal: " + file.getName());
			}
		}
		return records;
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
		private final IncidentSummary incidentSummary;
		private final File journalFile;
		private final List<JavaDiagnosticStore.Snapshot> javaEvidence;
		private final JavaDiagnosticStore.Snapshot primaryJava;
		private final ProcessExitStore.Snapshot processExit;

		private Record(String id, Kind kind, long timestampMillis, String eventId, String sessionId,
				String midletName, String processRole, IncidentSummary incidentSummary,
				File journalFile, List<JavaDiagnosticStore.Snapshot> javaEvidence,
				JavaDiagnosticStore.Snapshot primaryJava, ProcessExitStore.Snapshot processExit) {
			this.id = id;
			this.kind = kind;
			this.timestampMillis = timestampMillis;
			this.eventId = eventId;
			this.sessionId = sessionId;
			this.midletName = midletName;
			this.processRole = processRole;
			this.incidentSummary = incidentSummary;
			this.journalFile = journalFile;
			this.javaEvidence = Collections.unmodifiableList(new ArrayList<>(javaEvidence));
			this.primaryJava = primaryJava;
			this.processExit = processExit;
		}

		private static Record fromJava(JavaDiagnosticStore.Snapshot java,
				ProcessExitStore.Snapshot exit) {
			IncidentSummary incident = IncidentInterpreter.interpret(null, java, exit);
			return new Record(
					javaRecordId(java),
					Kind.JAVA_REPORT,
					incident.incidentTimestampMillis,
					java.legacyEventId,
					java.sessionId,
					java.midletName,
					java.processRole,
					incident,
					null,
					Collections.singletonList(java),
					java,
					exit);
		}

		private static Record fromProcessExit(ProcessExitStore.Snapshot exit, SessionRecord session) {
			MidletSessionJournal.Snapshot snapshot = session == null ? null : session.snapshot;
			IncidentSummary incident = IncidentInterpreter.interpret(snapshot, null, exit);
			return new Record(
					exit.id,
					Kind.PROCESS_EXIT,
					incident.incidentTimestampMillis,
					snapshot == null ? null : snapshot.failureEventId,
					exit.sessionId,
					snapshot == null ? null : snapshot.midletName,
					exit.processRole,
					incident,
					session == null ? null : session.file,
					Collections.emptyList(),
					null,
					exit);
		}

		public String getId() { return id; }
		public Kind getKind() { return kind; }
		public long getTimestampMillis() { return timestampMillis; }
		public String getEventId() { return eventId; }
		public String getSessionId() { return sessionId; }
		public String getMidletName() { return midletName; }
		public String getProcessRole() { return processRole; }
		public String getStackTrace() { return primaryJava == null ? null : primaryJava.stackTrace; }
		public String getDetailText() { return DiagnosticReportText.build(this); }
		IncidentSummary getIncidentSummary() { return incidentSummary; }
		ProcessExitStore.Snapshot getProcessExitSnapshot() { return processExit; }
		JavaDiagnosticStore.Snapshot getJavaEvidence() { return primaryJava; }
		public boolean hasJavaReport() { return primaryJava != null; }
		public boolean hasProcessExit() { return processExit != null; }
	}

	private static final class SessionRecord {
		final File file;
		final MidletSessionJournal.Snapshot snapshot;

		SessionRecord(File file, MidletSessionJournal.Snapshot snapshot) {
			this.file = file;
			this.snapshot = snapshot;
		}
	}

	private static final class StandaloneJavaRecord {
		final JavaDiagnosticStore.Snapshot java;
		ProcessExitStore.Snapshot processExit;

		StandaloneJavaRecord(JavaDiagnosticStore.Snapshot java) {
			this.java = java;
		}

		Record freeze() {
			return Record.fromJava(java, processExit);
		}
	}

	private static final class MutableRecord {
		final File journalFile;
		final MidletSessionJournal.Snapshot snapshot;
		final ArrayList<JavaDiagnosticStore.Snapshot> javaEvidence = new ArrayList<>();
		ProcessExitStore.Snapshot processExit;

		MutableRecord(SessionRecord session) {
			journalFile = session.file;
			snapshot = session.snapshot;
		}

		void attach(JavaDiagnosticStore.Snapshot java) {
			javaEvidence.add(java);
		}

		void attach(ProcessExitStore.Snapshot exit) {
			if (processExit == null || exit.timestampMillis > processExit.timestampMillis) {
				processExit = exit;
			}
		}

		Record freeze() {
			JavaDiagnosticStore.Snapshot primary = selectPrimaryJava(javaEvidence);
			IncidentSummary incident = IncidentInterpreter.interpret(snapshot, primary, processExit);
			String processRole = primary != null && primary.processRole != null
					? primary.processRole
					: (processExit == null ? "midlet" : processExit.processRole);
			return new Record(
					"event:" + snapshot.failureEventId,
					Kind.MIDLET_FAILURE,
					incident.incidentTimestampMillis,
					snapshot.failureEventId,
					snapshot.sessionId,
					snapshot.midletName,
					processRole,
					incident,
					journalFile,
					javaEvidence,
					primary,
					processExit);
		}
	}

	static String javaRecordId(JavaDiagnosticStore.Snapshot java) {
		if (java == null) return null;
		return java.legacyRecordId != null ? java.legacyRecordId : "java:" + java.file.getName();
	}

	private static JavaDiagnosticStore.Snapshot selectPrimaryJava(
			List<JavaDiagnosticStore.Snapshot> evidence) {
		JavaDiagnosticStore.Snapshot best = null;
		int bestScore = Integer.MIN_VALUE;
		for (JavaDiagnosticStore.Snapshot item : evidence) {
			int score = item.kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE ? 40
					: item.kind == JavaDiagnosticStore.Kind.FATAL_UNCAUGHT ? 30
					: item.kind == JavaDiagnosticStore.Kind.LEGACY_ACRA ? 20
					: item.kind == JavaDiagnosticStore.Kind.LEGACY_FATAL ? 10 : 0;
			if (item.primaryThrowable() != null) score += 3;
			if (item.sessionId != null) score += 1;
			if (best == null || score > bestScore) {
				best = item;
				bestScore = score;
			}
		}
		return best;
	}
}
