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

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import io.github.h3nb.jlmodplus.BuildConfig;
import io.github.h3nb.jlmodplus.EmulatorApplication;

/**
 * Coordinates local diagnostic evidence without owning interpretation or presentation.
 *
 * Fatal Java evidence is owned exclusively by {@link JavaDiagnosticStore}. Caught reporting remains
 * deliberately allowlisted for emulator-owned failures; arbitrary caught MIDlet/vendor exceptions
 * must not be promoted into diagnostics.
 */
public final class CrashReporter {
	private static final int MAX_CONTEXT_VALUE_LENGTH = 256;
	private static final int MAX_CONTEXT_MESSAGE_LENGTH = 768;

	private static final String TAG = CrashReporter.class.getSimpleName();
	private static final String ROLE_MAIN = "main";
	private static final String ROLE_MIDLET = "midlet";
	private static final String ROLE_MEMORY_ENGINE = "memory_engine";
	private static final String ROLE_REPORTER = "reporter";
	private static final String ROLE_OTHER = "other";

	private static final Object DIAGNOSTIC_REFRESH_LOCK = new Object();
	private static Application activeApplication;
	private static boolean mainProcess;
	private static boolean lifecycleCallbacksRegistered;
	private static boolean diagnosticRefreshRunning;
	private static boolean diagnosticRefreshPending;
	private static volatile boolean diagnosticRefreshReady;
	private static volatile DiagnosticContext diagnosticContext;

	public enum AppContextPhase {
		ENTERING("entering"),
		ACTIVE("active"),
		EXECUTING("executing"),
		LEAVING("leaving");

		final String id;

		AppContextPhase(String id) {
			this.id = id;
		}
	}

	private CrashReporter() {}

	/**
	 * Installs process-local diagnostics. Historical migration/retention remains deferred until
	 * {@link #scheduleMaintenance(Application)} so Application startup stays small.
	 */
	public static void initialize(Application application) {
		String processName = EmulatorApplication.getProcessName();
		String processRole = classifyProcess(application.getPackageName(), processName);
		mainProcess = ROLE_MAIN.equals(processRole);
		diagnosticRefreshRunning = false;
		diagnosticRefreshPending = false;
		diagnosticRefreshReady = !mainProcess;
		activeApplication = application;
		diagnosticContext = new DiagnosticContext(processName, processRole,
				null, null, null, null, null);

		CrashContextStore.initialize(
				application, processRole, BuildConfig.JLMOD_BUILD_COMMIT, BuildConfig.JLMOD_BUILD_VARIANT);
		CrashContextStore.update(application, "process.start", "start", AppContextPhase.ACTIVE.id);

		// Android 11+ state and the Java uncaught bridge are published synchronously because the
		// process may die before deferred maintenance gets CPU.
		ProcessExitStore.initializeProcess(application, processRole);
		JavaDiagnosticStore.install(application, processName, processRole);
	}

	/** Schedules one best-effort main-process diagnostics refresh after Application startup. */
	public static void scheduleMaintenance(Application application) {
		registerActivityContextCallbacks(application);
		if (!mainProcess) {
			diagnosticRefreshReady = true;
			return;
		}
		startDiagnosticRefresh(application, false, "jlmod-diagnostics-maintenance");
	}

	/**
	 * Records one high-level, stable app state. This is intentionally not a generic telemetry API.
	 */
	public static void recordAppContext(String location, String action, AppContextPhase phase) {
		Application application = activeApplication;
		if (application == null || phase == null) return;
		try {
			CrashContextStore.Snapshot snapshot = CrashContextStore.update(
					application, location, action, phase.id);
			if (snapshot != null) ProcessExitStore.updateProcessContext(application);
		} catch (Throwable error) {
			logMaintenanceFailure("Unable to update crash context", error);
		}
	}

	public static void requestDiagnosticRefresh(Application application) {
		if (!mainProcess) {
			diagnosticRefreshReady = true;
			return;
		}
		startDiagnosticRefresh(application, true, "jlmod-diagnostics-focus-refresh");
	}

	public static boolean isDiagnosticRefreshReady() {
		return diagnosticRefreshReady;
	}

	private static void startDiagnosticRefresh(Application application, boolean queueIfRunning,
			String threadName) {
		if (!beginDiagnosticRefresh(queueIfRunning)) return;
		try {
			Thread refresh = new Thread(() -> {
				setBackgroundThreadPriority();
				runDiagnosticRefreshLoop(application);
			}, threadName);
			refresh.start();
		} catch (Throwable error) {
			finishDiagnosticRefresh();
			logMaintenanceFailure("Unable to schedule diagnostics refresh", error);
		}
	}

	private static boolean beginDiagnosticRefresh(boolean queueIfRunning) {
		synchronized (DIAGNOSTIC_REFRESH_LOCK) {
			if (diagnosticRefreshRunning) {
				if (queueIfRunning) {
					diagnosticRefreshPending = true;
					diagnosticRefreshReady = false;
				}
				return false;
			}
			diagnosticRefreshRunning = true;
			diagnosticRefreshPending = false;
			diagnosticRefreshReady = false;
			return true;
		}
	}

	private static void runDiagnosticRefreshLoop(Application application) {
		try {
			while (true) {
				refreshDiagnostics(application);
				synchronized (DIAGNOSTIC_REFRESH_LOCK) {
					if (diagnosticRefreshPending) {
						diagnosticRefreshPending = false;
						continue;
					}
					diagnosticRefreshRunning = false;
					diagnosticRefreshReady = true;
					return;
				}
			}
		} catch (Throwable error) {
			finishDiagnosticRefresh();
			logMaintenanceFailure("Unexpected diagnostics refresh failure", error);
		}
	}

	private static void finishDiagnosticRefresh() {
		synchronized (DIAGNOSTIC_REFRESH_LOCK) {
			diagnosticRefreshRunning = false;
			diagnosticRefreshPending = false;
			diagnosticRefreshReady = true;
		}
	}

	private static void refreshDiagnostics(Application application) {
		runMaintenanceStep("legacy Java diagnostic migration",
				() -> JavaDiagnosticStore.migrateLegacyAndPrune(application));
		runMaintenanceStep("legacy process-exit reconciliation",
				() -> LegacyProcessExitFallback.ingest(application));
		runMaintenanceStep("process-exit ingestion", () -> ProcessExitStore.ingest(application));
		runMaintenanceStep("MIDlet session journal pruning", () -> MidletSessionJournal.prune(application));
		runMaintenanceStep("process context pruning", () -> CrashContextStore.prune(application));
	}

	private static void setBackgroundThreadPriority() {
		try {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		} catch (Throwable ignored) {}
	}

	private static void runMaintenanceStep(String label, Runnable step) {
		try {
			step.run();
		} catch (Throwable error) {
			logMaintenanceFailure("Diagnostics maintenance failed open: " + label, error);
		}
	}

	private static void logMaintenanceFailure(String message, Throwable error) {
		try {
			Log.w(TAG, message, error);
		} catch (Throwable ignored) {}
	}

	public static synchronized void setMidletContext(String name, String vendor, String version,
			String jarSize, String jarSha256) {
		DiagnosticContext current = diagnosticContext;
		if (current == null) return;
		diagnosticContext = new DiagnosticContext(
				current.processName,
				current.processRole,
				boundValue(name),
				boundValue(version),
				null,
				boundValue(jarSha256),
				null);
	}

	public static synchronized void setMidletMainClass(String mainClass) {
		DiagnosticContext current = diagnosticContext;
		if (current == null) return;
		diagnosticContext = current.withMainClass(boundValue(mainClass));
	}

	static synchronized void setSessionContext(String sessionId) {
		DiagnosticContext current = diagnosticContext;
		if (current != null) diagnosticContext = current.withSession(boundValue(sessionId));
		Application application = activeApplication;
		if (application != null) {
			// The immutable session key is shared by the journal, Java evidence and Android process
			// state. New-format Java correlation never depends on parsing a stack marker.
			ProcessExitStore.setMidletSession(application, sessionId);
		}
	}

	/**
	 * Allowlisted non-fatal incident: the emulator-owned installer operation failed.
	 */
	public static void reportInstallerFailure(Throwable error, String sourceScheme,
			String midletName, String midletVendor, String midletVersion, String jarSize) {
		if (error == null) return;
		String message = buildInstallerContext(
				sourceScheme, midletName, midletVendor, midletVersion, jarSize);
		try {
			InstallerFailureException wrapper = new InstallerFailureException(message, error);
			JavaDiagnosticStore.captureCaught(
					activeApplication,
					JavaDiagnosticStore.Kind.CAUGHT_INSTALLER,
					wrapper,
					error,
					message);
		} catch (Throwable reportingFailure) {
			logMaintenanceFailure("Unable to persist installer failure diagnostic", reportingFailure);
		}
	}

	/**
	 * Allowlisted non-fatal incident: an app-repository operation failed.
	 */
	public static void reportAppRepositoryFailure(Throwable error) {
		if (error == null) return;
		try {
			JavaDiagnosticStore.captureCaught(
					activeApplication,
					JavaDiagnosticStore.Kind.CAUGHT_APP_REPOSITORY,
					error,
					error,
					"App repository operation failure");
		} catch (Throwable reportingFailure) {
			logMaintenanceFailure("Unable to persist app-repository failure diagnostic", reportingFailure);
		}
	}

	/**
	 * Persists a terminal MIDlet failure directly from its owning session boundary.
	 *
	 * This path deliberately does not invoke the process uncaught handler. MidletThread owns the
	 * subsequent isolated-process termination after this synchronous best-effort capture returns.
	 */
	/** True only when the Throwable's primary frame proves JL-Mod Plus/framework origin. */
	public static boolean hasProvenFrameworkOrigin(Throwable error) {
		if (error == null) return false;
		try {
			StackTraceElement[] frames = error.getStackTrace();
			return frames.length > 0
					&& IncidentInterpreter.isFrameworkClass(frames[0].getClassName());
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static boolean captureMidletSessionFailure(Thread thread, MidletSessionJournal journal,
			Throwable reported, Throwable primaryFailure) {
		Application application = activeApplication;
		if (application == null || journal == null || reported == null) return false;
		try {
			return JavaDiagnosticStore.captureMidletSessionFailure(
					application,
					thread,
					journal.snapshotForDiagnostics(),
					reported,
					primaryFailure == null ? reported : primaryFailure);
		} catch (Throwable reportingFailure) {
			logMaintenanceFailure("Unable to persist handled MIDlet session failure",
					reportingFailure);
			return false;
		}
	}

	static DiagnosticContext currentDiagnosticContext() {
		return diagnosticContext;
	}

	static String classifyProcess(String packageName, String processName) {
		if (processName == null || processName.trim().isEmpty()) return ROLE_OTHER;
		if (processName.equals(packageName)) return ROLE_MAIN;
		if (processName.equals(packageName + ":midlet")) return ROLE_MIDLET;
		if (processName.equals(packageName + ":memory_engine")) return ROLE_MEMORY_ENGINE;
		// Kept only so historical ApplicationExitInfo entries created by older ACRA-enabled builds
		// remain recognized as reporting infrastructure and stay out of the inbox.
		if (processName.equals(packageName + ":acra")) return ROLE_REPORTER;
		return ROLE_OTHER;
	}

	private static void registerActivityContextCallbacks(Application application) {
		if (lifecycleCallbacksRegistered) return;
		lifecycleCallbacksRegistered = true;
		Application.ActivityLifecycleCallbacks callbacks = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
				? new Api29ActivityContextCallbacks() : new ActivityContextCallbacks();
		application.registerActivityLifecycleCallbacks(callbacks);
	}

	private static String activityLocation(Activity activity) {
		String simpleName = activity.getClass().getSimpleName();
		return "activity." + (simpleName == null || simpleName.isEmpty() ? "unknown" : simpleName);
	}

	private static String buildInstallerContext(String sourceScheme, String midletName,
			String midletVendor, String midletVersion, String jarSize) {
		StringBuilder message = new StringBuilder("Installer failure");
		appendContext(message, "sourceScheme", sourceScheme);
		appendContext(message, "midletName", midletName);
		appendContext(message, "midletVendor", midletVendor);
		appendContext(message, "midletVersion", midletVersion);
		appendContext(message, "jarSize", jarSize);
		return message.toString();
	}

	private static void appendContext(StringBuilder message, String key, String value) {
		String bounded = boundValue(value);
		if (bounded == null || message.length() >= MAX_CONTEXT_MESSAGE_LENGTH) return;
		message.append("; ").append(key).append('=').append(bounded);
		if (message.length() > MAX_CONTEXT_MESSAGE_LENGTH) {
			message.setLength(MAX_CONTEXT_MESSAGE_LENGTH);
		}
	}

	static String boundValue(String value) {
		if (value == null) return null;
		String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
		if (normalized.isEmpty()) return null;
		return normalized.length() <= MAX_CONTEXT_VALUE_LENGTH
				? normalized : normalized.substring(0, MAX_CONTEXT_VALUE_LENGTH);
	}

	private static class ActivityContextCallbacks implements Application.ActivityLifecycleCallbacks {
		@Override
		public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

		@Override
		public void onActivityStarted(@NonNull Activity activity) {}

		@Override
		public void onActivityResumed(@NonNull Activity activity) {
			recordAppContext(activityLocation(activity), "open", AppContextPhase.ACTIVE);
		}

		@Override
		public void onActivityPaused(@NonNull Activity activity) {
			recordAppContext(activityLocation(activity), "open", AppContextPhase.LEAVING);
		}

		@Override
		public void onActivityStopped(@NonNull Activity activity) {}

		@Override
		public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

		@Override
		public void onActivityDestroyed(@NonNull Activity activity) {}
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private static final class Api29ActivityContextCallbacks extends ActivityContextCallbacks {
		@Override
		public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
			recordAppContext(activityLocation(activity), "open", AppContextPhase.ENTERING);
		}
	}

	static final class DiagnosticContext {
		final String processName;
		final String processRole;
		final String midletName;
		final String midletVersion;
		final String midletMainClass;
		final String jarSha256;
		final String sessionId;

		DiagnosticContext(String processName, String processRole, String midletName,
				String midletVersion, String midletMainClass, String jarSha256, String sessionId) {
			this.processName = processName;
			this.processRole = processRole;
			this.midletName = midletName;
			this.midletVersion = midletVersion;
			this.midletMainClass = midletMainClass;
			this.jarSha256 = jarSha256;
			this.sessionId = sessionId;
		}

		DiagnosticContext withMainClass(String value) {
			return new DiagnosticContext(processName, processRole, midletName, midletVersion,
					value, jarSha256, sessionId);
		}

		DiagnosticContext withSession(String value) {
			return new DiagnosticContext(processName, processRole, midletName, midletVersion,
					midletMainClass, jarSha256, value);
		}
	}

	private static final class InstallerFailureException extends RuntimeException {
		InstallerFailureException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
