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
import static org.acra.ReportField.APP_VERSION_CODE;
import static org.acra.ReportField.APP_VERSION_NAME;
import static org.acra.ReportField.BRAND;
import static org.acra.ReportField.CUSTOM_DATA;
import static org.acra.ReportField.IS_SILENT;
import static org.acra.ReportField.PACKAGE_NAME;
import static org.acra.ReportField.PHONE_MODEL;
import static org.acra.ReportField.REPORT_ID;
import static org.acra.ReportField.STACK_TRACE;
import static org.acra.ReportField.STACK_TRACE_HASH;
import static org.acra.ReportField.THREAD_DETAILS;
import static org.acra.ReportField.USER_APP_START_DATE;
import static org.acra.ReportField.USER_CRASH_DATE;

import android.app.Application;
import android.os.Process;

import org.acra.ACRA;
import org.acra.ErrorReporter;
import org.acra.ReportField;
import org.acra.config.CoreConfigurationBuilder;

import java.util.Arrays;
import java.util.List;

import ru.playsoftware.j2meloader.EmulatorApplication;

/** Configures ACRA as a local Java exception collector for JL-Mod Plus. */
public final class CrashReporter {
	private static final int MAX_CONTEXT_VALUE_LENGTH = 256;
	private static final int MAX_CONTEXT_MESSAGE_LENGTH = 768;

	private static final String ROLE_MAIN = "main";
	private static final String ROLE_MIDLET = "midlet";
	private static final String ROLE_REPORTER = "reporter";
	private static final String ROLE_OTHER = "other";

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
	private static final String KEY_SESSION_STAGE = "jlmod.session.stage";
	private static final String KEY_SESSION_OUTCOME = "jlmod.session.outcome";

	private static final List<ReportField> REPORT_FIELDS = Arrays.asList(
			REPORT_ID,
			APP_VERSION_CODE,
			APP_VERSION_NAME,
			PACKAGE_NAME,
			PHONE_MODEL,
			ANDROID_VERSION,
			BRAND,
			CUSTOM_DATA,
			STACK_TRACE,
			STACK_TRACE_HASH,
			USER_APP_START_DATE,
			USER_CRASH_DATE,
			IS_SILENT,
			THREAD_DETAILS
	);

	private CrashReporter() {}

	/**
	 * Initializes local-only crash collection.
	 *
	 * @return true when running in ACRA's private reporter process, where normal app initialization
	 * should be skipped.
	 */
	public static boolean initialize(Application application) {
		CoreConfigurationBuilder configuration = new CoreConfigurationBuilder()
				.withParallel(false)
				.withDeleteUnapprovedReportsOnApplicationStart(false)
				.withReportContent(REPORT_FIELDS);

		// Report sending and ACRA startup processing are intentionally disabled. JL-Mod Plus owns
		// local retention and later presentation/export of the persisted report files.
		ACRA.init(application, configuration, false);

		String processName = EmulatorApplication.getProcessName();
		String processRole = classifyProcess(application.getPackageName(), processName);
		boolean reporterProcess = ROLE_REPORTER.equals(processRole) || ACRA.isACRASenderServiceProcess();
		if (reporterProcess) {
			return true;
		}

		ErrorReporter reporter = ACRA.getErrorReporter();
		putBounded(reporter, KEY_PROCESS_NAME, processName);
		putBounded(reporter, KEY_PROCESS_ROLE, processRole);
		putBounded(reporter, KEY_PROCESS_PID, Integer.toString(Process.myPid()));

		// The main process owns diagnostic retention so :midlet remains a single-purpose writer.
		if (ROLE_MAIN.equals(processRole)) {
			LocalCrashReportStore.prune(application);
			MidletSessionJournal.prune(application);
		}
		return false;
	}

	public static void setMidletContext(String name, String vendor, String version,
											 String jarSize, String jarSha256) {
		ErrorReporter reporter = ACRA.getErrorReporter();
		clearMidletContext(reporter);
		clearSessionContext(reporter);
		putBounded(reporter, KEY_MIDLET_NAME, name);
		putBounded(reporter, KEY_MIDLET_VENDOR, vendor);
		putBounded(reporter, KEY_MIDLET_VERSION, version);
		putBounded(reporter, KEY_MIDLET_JAR_SIZE, jarSize);
		putBounded(reporter, KEY_MIDLET_JAR_SHA256, jarSha256);
	}

	public static void setMidletMainClass(String mainClass) {
		putBounded(ACRA.getErrorReporter(), KEY_MIDLET_MAIN_CLASS, mainClass);
	}

	static void setSessionContext(String sessionId, String stage, String outcome) {
		ErrorReporter reporter = ACRA.getErrorReporter();
		putBounded(reporter, KEY_SESSION_ID, sessionId);
		putBounded(reporter, KEY_SESSION_STAGE, stage);
		putBounded(reporter, KEY_SESSION_OUTCOME, outcome);
	}

	/** Persists a non-fatal installer exception without mutating process-global ACRA context. */
	public static void reportInstallerFailure(Throwable error, String sourceScheme,
												  String midletName, String midletVendor,
												  String midletVersion, String jarSize) {
		String message = buildInstallerContext(sourceScheme, midletName, midletVendor,
				midletVersion, jarSize);
		ACRA.getErrorReporter().handleException(new InstallerFailureException(message, error), false);
	}

	static String classifyProcess(String packageName, String processName) {
		if (processName == null || processName.trim().isEmpty()) {
			return ROLE_OTHER;
		}
		if (processName.equals(packageName)) {
			return ROLE_MAIN;
		}
		if (processName.equals(packageName + ":midlet")) {
			return ROLE_MIDLET;
		}
		if (processName.equals(packageName + ":acra")) {
			return ROLE_REPORTER;
		}
		return ROLE_OTHER;
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
		if (bounded == null || message.length() >= MAX_CONTEXT_MESSAGE_LENGTH) {
			return;
		}
		message.append("; ").append(key).append('=').append(bounded);
		if (message.length() > MAX_CONTEXT_MESSAGE_LENGTH) {
			message.setLength(MAX_CONTEXT_MESSAGE_LENGTH);
		}
	}

	private static void clearMidletContext(ErrorReporter reporter) {
		reporter.removeCustomData(KEY_MIDLET_NAME);
		reporter.removeCustomData(KEY_MIDLET_VENDOR);
		reporter.removeCustomData(KEY_MIDLET_VERSION);
		reporter.removeCustomData(KEY_MIDLET_JAR_SIZE);
		reporter.removeCustomData(KEY_MIDLET_JAR_SHA256);
		reporter.removeCustomData(KEY_MIDLET_MAIN_CLASS);
	}

	private static void clearSessionContext(ErrorReporter reporter) {
		reporter.removeCustomData(KEY_SESSION_ID);
		reporter.removeCustomData(KEY_SESSION_STAGE);
		reporter.removeCustomData(KEY_SESSION_OUTCOME);
	}

	private static void putBounded(ErrorReporter reporter, String key, String value) {
		String bounded = boundValue(value);
		if (bounded == null) {
			reporter.removeCustomData(key);
		} else {
			reporter.putCustomData(key, bounded);
		}
	}

	static String boundValue(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
		if (normalized.isEmpty()) {
			return null;
		}
		if (normalized.length() > MAX_CONTEXT_VALUE_LENGTH) {
			return normalized.substring(0, MAX_CONTEXT_VALUE_LENGTH);
		}
		return normalized;
	}

	private static final class InstallerFailureException extends RuntimeException {
		InstallerFailureException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
