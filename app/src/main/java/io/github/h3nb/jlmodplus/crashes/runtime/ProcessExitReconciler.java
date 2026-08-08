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
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Reconciles a durable MIDlet session with Android's process-exit history. */
public final class ProcessExitReconciler {
    private static final String TAG = ProcessExitReconciler.class.getSimpleName();
    private static final long EXIT_TIME_TOLERANCE_MS = 2_000L;
    private static final int MAX_EXIT_RECORDS = 32;

    // ApplicationExitInfo reason values are stable public API values from API 30+.
    // Keeping them as local ints avoids resolving ApplicationExitInfo on Android 6-10.
    private static final int REASON_UNKNOWN = 0;
    private static final int REASON_EXIT_SELF = 1;
    private static final int REASON_SIGNALED = 2;
    private static final int REASON_LOW_MEMORY = 3;
    private static final int REASON_CRASH = 4;
    private static final int REASON_CRASH_NATIVE = 5;
    private static final int REASON_ANR = 6;
    private static final int REASON_INITIALIZATION_FAILURE = 7;
    private static final int REASON_PERMISSION_CHANGE = 8;
    private static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
    private static final int REASON_USER_REQUESTED = 10;
    private static final int REASON_USER_STOPPED = 11;
    private static final int REASON_DEPENDENCY_DIED = 12;
    private static final int REASON_OTHER = 13;
    private static final int REASON_FREEZER = 14;
    private static final int REASON_PACKAGE_STATE_CHANGE = 15;
    private static final int REASON_PACKAGE_UPDATED = 16;

    private ProcessExitReconciler() {
    }

    /** First-pass reconciliation. API 30+ keeps an unresolved session briefly if exit history is not ready yet. */
    public static String reconcileMidlet(Context context) {
        return reconcileMidlet(context, false);
    }

    /** Second-pass reconciliation after a short UI-side grace period. */
    public static String reconcileMidletAfterExitHistoryGrace(Context context) {
        return reconcileMidlet(context, true);
    }

    private static String reconcileMidlet(Context context, boolean allowMissingExitFallback) {
        CrashSessionStore.Session session = CrashSessionStore.read(context);
        if (session == null) {
            return null;
        }

        if (isMidletProcessAlive(context, session)) {
            return null;
        }

        if (CrashSessionStore.STATE_EXPECTED_EXIT.equals(session.state)
                || CrashSessionStore.STATE_JAVA_CRASH_REPORTED.equals(session.state)) {
            CrashSessionStore.clearMidletSession(context);
            return null;
        }

        ExitRecord exit = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            exit = Api30Impl.findMatchingExit(context, session);
            if (exit == null && !allowMissingExitFallback) {
                // Activity resume can race system_server recording the exit. Keep
                // the durable session so the lifecycle callback can retry shortly.
                return null;
            }
            if (exit != null && isUserOrMaintenanceExit(exit.reason)) {
                CrashSessionStore.clearMidletSession(context);
                return null;
            }
        }

        String report = buildReport(context, session, exit);
        byte[] trace = exit == null ? null : exit.trace;
        try {
            String reportId = ProcessDeathReportStore.save(context, session.sessionId, report, trace);
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

    private static boolean isUserOrMaintenanceExit(int reason) {
        if (reason == REASON_USER_REQUESTED || reason == REASON_USER_STOPPED) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return reason == REASON_PACKAGE_UPDATED || reason == REASON_PACKAGE_STATE_CHANGE;
        }
        return false;
    }

    private static String buildReport(
            Context context,
            CrashSessionStore.Session session,
            ExitRecord exit
    ) {
        StringBuilder report = new StringBuilder(4096);
        report.append("JL-Mod Plus Process Death Report\n\n");
        report.append("INCIDENT\n");
        report.append("Type: ").append(classify(exit)).append('\n');
        report.append("Process: ").append(session.processName).append('\n');
        report.append("Session: ").append(session.sessionId).append('\n');
        report.append("MIDlet: ").append(emptyFallback(session.appName, "Unknown")).append('\n');
        report.append("Started: ").append(formatTime(session.startedAt)).append('\n');

        if (exit == null) {
            report.append("Exit reason: unavailable on this Android version or not retained by the system\n");
        } else {
            report.append("Ended: ").append(formatTime(exit.timestamp)).append('\n');
            report.append("Exit reason: ").append(reasonLabel(exit.reason))
                    .append(" (").append(exit.reason).append(")\n");
            report.append("Status: ").append(exit.status).append('\n');
            if (hasText(exit.description)) {
                report.append("Description: ").append(exit.description).append('\n');
            }
            report.append("Importance: ").append(exit.importance).append('\n');
            report.append("PSS: ").append(exit.pss).append(" kB\n");
            report.append("RSS: ").append(exit.rss).append(" kB\n");
            if (exit.trace != null && exit.trace.length > 0) {
                report.append("Trace attachment: captured ").append(exit.trace.length).append(" bytes");
                if (hasText(exit.traceKind)) {
                    report.append(" (").append(exit.traceKind).append(')');
                }
                if (exit.traceTruncated) {
                    report.append(" [truncated at ")
                            .append(ProcessDeathReportStore.MAX_TRACE_BYTES)
                            .append(" bytes]");
                }
                report.append('\n');
            } else {
                report.append("Trace attachment: unavailable\n");
            }
        }

        report.append("\nAPPLICATION\n");
        report.append("Version: ").append(getVersion(context)).append('\n');
        report.append("Package: ").append(context.getPackageName()).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("ABI: ").append(Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]).append('\n');

        String breadcrumbs = CrashBreadcrumbStore.read(context);
        if (hasText(breadcrumbs)) {
            report.append("\nRECENT BREADCRUMBS\n");
            report.append(breadcrumbs);
            if (breadcrumbs.charAt(breadcrumbs.length() - 1) != '\n') {
                report.append('\n');
            }
        }

        report.append("\nNOTES\n");
        if (exit != null && exit.reason == REASON_CRASH_NATIVE) {
            report.append("Android classified this as a native-code crash. Java exception handling cannot catch it.\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                report.append("On Android 12+, a captured native trace attachment is an Android tombstone protobuf.\n");
            }
        } else if (exit != null && exit.reason == REASON_ANR) {
            report.append("Android classified this process as unresponsive (ANR).\n");
        } else if (exit == null) {
            report.append("The MIDlet session was still marked RUNNING after its process disappeared.\n");
        }
        report.append("This report was generated locally and is not sent automatically.\n");
        return report.toString();
    }

    private static String classify(ExitRecord exit) {
        if (exit == null) {
            return "Unexpected process termination";
        }
        return switch (exit.reason) {
            case REASON_CRASH -> "Java crash";
            case REASON_CRASH_NATIVE -> "Native crash";
            case REASON_ANR -> "ANR";
            case REASON_LOW_MEMORY -> "System low-memory kill";
            case REASON_EXCESSIVE_RESOURCE_USAGE -> "System resource kill";
            case REASON_SIGNALED -> "Signal termination";
            case REASON_INITIALIZATION_FAILURE -> "Process initialization failure";
            default -> "Unexpected process termination";
        };
    }

    private static String reasonLabel(int reason) {
        return switch (reason) {
            case REASON_UNKNOWN -> "UNKNOWN";
            case REASON_EXIT_SELF -> "EXIT_SELF";
            case REASON_SIGNALED -> "SIGNALED";
            case REASON_LOW_MEMORY -> "LOW_MEMORY";
            case REASON_CRASH -> "CRASH";
            case REASON_CRASH_NATIVE -> "CRASH_NATIVE";
            case REASON_ANR -> "ANR";
            case REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE";
            case REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE";
            case REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE";
            case REASON_USER_REQUESTED -> "USER_REQUESTED";
            case REASON_USER_STOPPED -> "USER_STOPPED";
            case REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED";
            case REASON_OTHER -> "OTHER";
            case REASON_FREEZER -> "FREEZER";
            case REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE";
            case REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED";
            default -> "REASON_" + reason;
        };
    }

    private static String getVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return emptyFallback(info.versionName, "unknown") + " (" + versionCode + ")";
        } catch (Exception error) {
            return "unknown";
        }
    }

    private static String formatTime(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(timestamp));
    }

    private static String emptyFallback(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class ExitRecord {
        int reason;
        int status;
        long timestamp;
        String description;
        int importance;
        long pss;
        long rss;
        byte[] trace;
        boolean traceTruncated;
        String traceKind;
    }

    /** All ApplicationExitInfo references live here so Android 6-10 never resolve that class. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static final class Api30Impl {
        private Api30Impl() {
        }

        static ExitRecord findMatchingExit(Context context, CrashSessionStore.Session session) {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) {
                return null;
            }

            List<android.app.ApplicationExitInfo> exits;
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

            android.app.ApplicationExitInfo best = null;
            for (android.app.ApplicationExitInfo candidate : exits) {
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
            if (best == null) {
                return null;
            }

            ExitRecord record = new ExitRecord();
            record.reason = best.getReason();
            record.status = best.getStatus();
            record.timestamp = best.getTimestamp();
            record.description = best.getDescription();
            record.importance = best.getImportance();
            record.pss = best.getPss();
            record.rss = best.getRss();
            captureTrace(best, record);
            return record;
        }

        private static void captureTrace(android.app.ApplicationExitInfo info, ExitRecord record) {
            try (InputStream input = info.getTraceInputStream()) {
                if (input == null) {
                    return;
                }
                BoundedInputReader.Result result = BoundedInputReader.read(
                        input,
                        ProcessDeathReportStore.MAX_TRACE_BYTES
                );
                record.trace = result.data;
                record.traceTruncated = result.truncated;
                if (record.reason == REASON_CRASH_NATIVE
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    record.traceKind = "Android native tombstone protobuf";
                } else if (record.reason == REASON_ANR) {
                    record.traceKind = "Android ANR trace";
                } else {
                    record.traceKind = "Android process trace";
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Unable to capture process exit trace", error);
            }
        }
    }
}
