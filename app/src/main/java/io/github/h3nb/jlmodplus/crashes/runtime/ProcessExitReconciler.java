/*
 * Copyright 2026 H3NB
 *
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

package io.github.h3nb.jlmodplus.crashes.runtime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Reconciles a durable MIDlet session with Android's process-exit history. */
public final class ProcessExitReconciler {
    private static final String TAG = ProcessExitReconciler.class.getSimpleName();
    private static final long EXIT_TIME_TOLERANCE_MS = 2_000L;
    private static final int MAX_EXIT_RECORDS = 32;

    private ProcessExitReconciler() {
    }

    /**
     * Returns the id of a newly-created report, or null if there is no
     * unexpected MIDlet process death to report.
     */
    public static String reconcileMidlet(Context context) {
        CrashSessionStore.Session session = CrashSessionStore.read(context);
        if (session == null) {
            return null;
        }

        if (isMidletProcessAlive(context, session)) {
            return null;
        }

        if (CrashSessionStore.STATE_EXPECTED_EXIT.equals(session.state)) {
            CrashSessionStore.clearMidletSession(context);
            return null;
        }

        ApplicationExitInfo exitInfo = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exitInfo = findMatchingExit(context, session);
            if (exitInfo != null && isUserOrMaintenanceExit(exitInfo.getReason())) {
                CrashSessionStore.clearMidletSession(context);
                return null;
            }
        }

        String report = buildReport(context, session, exitInfo);
        try {
            String reportId = ProcessDeathReportStore.save(context, session.sessionId, report);
            CrashSessionStore.clearMidletSession(context);
            return reportId;
        } catch (IOException | RuntimeException error) {
            // Keep the session marker so the report can be retried later.
            Log.w(TAG, "Unable to persist process death report", error);
            return null;
        }
    }

    private static boolean isMidletProcessAlive(Context context, CrashSessionStore.Session session) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (!session.processName.equals(process.processName)) {
                continue;
            }
            if (session.pid <= 0 || session.pid == process.pid) {
                return true;
            }
        }
        return false;
    }

    private static ApplicationExitInfo findMatchingExit(
            Context context,
            CrashSessionStore.Session session
    ) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) {
            return null;
        }
        List<ApplicationExitInfo> exits;
        try {
            exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(),
                    0,
                    MAX_EXIT_RECORDS
            );
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to query process exit history", error);
            return null;
        }

        ApplicationExitInfo best = null;
        for (ApplicationExitInfo candidate : exits) {
            if (!session.processName.equals(candidate.getProcessName())) {
                continue;
            }
            if (candidate.getTimestamp() + EXIT_TIME_TOLERANCE_MS < session.startedAt) {
                continue;
            }
            if (session.pid > 0 && candidate.getPid() != session.pid) {
                continue;
            }
            if (best == null || candidate.getTimestamp() > best.getTimestamp()) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isUserOrMaintenanceExit(int reason) {
        if (reason == ApplicationExitInfo.REASON_USER_REQUESTED
                || reason == ApplicationExitInfo.REASON_USER_STOPPED) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED
                    || reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE;
        }
        return false;
    }

    private static String buildReport(
            Context context,
            CrashSessionStore.Session session,
            ApplicationExitInfo exitInfo
    ) {
        StringBuilder report = new StringBuilder(2048);
        report.append("JL-Mod Plus Process Death Report\n\n");
        report.append("INCIDENT\n");
        report.append("Type: ").append(classify(exitInfo)).append('\n');
        report.append("Process: ").append(session.processName).append('\n');
        report.append("Session: ").append(session.sessionId).append('\n');
        report.append("MIDlet: ").append(emptyFallback(session.appName, "Unknown")).append('\n');
        report.append("Started: ").append(formatTime(session.startedAt)).append('\n');

        if (exitInfo == null) {
            report.append("Exit reason: unavailable on this Android version or not retained by the system\n");
        } else {
            report.append("Ended: ").append(formatTime(exitInfo.getTimestamp())).append('\n');
            report.append("Exit reason: ").append(reasonLabel(exitInfo.getReason()))
                    .append(" (").append(exitInfo.getReason()).append(")\n");
            report.append("Status: ").append(exitInfo.getStatus()).append('\n');
            String description = exitInfo.getDescription();
            if (description != null && !description.isBlank()) {
                report.append("Description: ").append(description).append('\n');
            }
            report.append("Importance: ").append(exitInfo.getImportance()).append('\n');
            report.append("PSS: ").append(exitInfo.getPss()).append(" kB\n");
            report.append("RSS: ").append(exitInfo.getRss()).append(" kB\n");
        }

        report.append("\nAPPLICATION\n");
        report.append("Version: ").append(getVersion(context)).append('\n');
        report.append("Package: ").append(context.getPackageName()).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("ABI: ").append(Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]).append('\n');

        report.append("\nNOTES\n");
        if (exitInfo != null && exitInfo.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            report.append("Android classified this as a native-code crash. Java exception handling cannot catch it.\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                report.append("A native tombstone may be available from Android process-exit history.\n");
            }
        } else if (exitInfo != null && exitInfo.getReason() == ApplicationExitInfo.REASON_ANR) {
            report.append("Android classified this process as unresponsive (ANR).\n");
        } else if (exitInfo == null) {
            report.append("The MIDlet session was still marked RUNNING after its process disappeared.\n");
        }
        report.append("This report was generated locally and is not sent automatically.\n");
        return report.toString();
    }

    private static String classify(ApplicationExitInfo info) {
        if (info == null) {
            return "Unexpected process termination";
        }
        return switch (info.getReason()) {
            case ApplicationExitInfo.REASON_CRASH -> "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash";
            case ApplicationExitInfo.REASON_ANR -> "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "System low-memory kill";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "System resource kill";
            case ApplicationExitInfo.REASON_SIGNALED -> "Signal termination";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Process initialization failure";
            default -> "Unexpected process termination";
        };
    }

    private static String reasonLabel(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN";
            case ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH -> "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR -> "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER -> "OTHER";
            case ApplicationExitInfo.REASON_FREEZER -> "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED";
            default -> "REASON_" + reason;
        };
    }

    private static String getVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return emptyFallback(info.versionName, "unknown") + " (" + info.getLongVersionCode() + ")";
        } catch (Exception error) {
            return "unknown";
        }
    }

    private static String formatTime(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(timestamp));
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
