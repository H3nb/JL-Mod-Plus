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

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.crashes.dialog.ProcessDeathReportActivity;

/** Main-process lifecycle hook that surfaces a durable report at the next safe resume. */
public final class CrashDiagnosticsLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = CrashDiagnosticsLifecycleCallbacks.class.getSimpleName();
    private static final long EXIT_HISTORY_GRACE_MS = 1_500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastLaunchedReportId;
    private String pendingGraceSessionId;
    private Activity resumedActivity;

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        resumedActivity = activity;
        if (activity instanceof ProcessDeathReportActivity) {
            return;
        }

        String reportId = ProcessExitReconciler.reconcileMidlet(activity);
        if (reportId == null) {
            reportId = ProcessDeathReportStore.findLatestPendingId(activity);
        }
        if (launchReport(activity, reportId)) {
            return;
        }

        scheduleExitHistoryGraceRetry(activity);
    }

    private void scheduleExitHistoryGraceRetry(Activity activity) {
        CrashSessionStore.Session session = CrashSessionStore.read(activity);
        if (session == null || session.sessionId.equals(pendingGraceSessionId)) {
            return;
        }

        final String sessionId = session.sessionId;
        pendingGraceSessionId = sessionId;
        mainHandler.postDelayed(() -> {
            if (!sessionId.equals(pendingGraceSessionId)) {
                return;
            }
            pendingGraceSessionId = null;

            if (resumedActivity != activity || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            String reportId = ProcessExitReconciler.reconcileMidletAfterExitHistoryGrace(activity);
            if (reportId == null) {
                reportId = ProcessDeathReportStore.findLatestPendingId(activity);
            }
            launchReport(activity, reportId);
        }, EXIT_HISTORY_GRACE_MS);
    }

    private boolean launchReport(Activity activity, String reportId) {
        if (reportId == null || reportId.equals(lastLaunchedReportId)) {
            return false;
        }

        Intent intent = new Intent(activity, ProcessDeathReportActivity.class)
                .putExtra(ProcessDeathReportActivity.EXTRA_REPORT_ID, reportId);
        try {
            activity.startActivity(intent);
            lastLaunchedReportId = reportId;
            return true;
        } catch (RuntimeException error) {
            // The incident is already durable. Leave it pending rather than
            // turning diagnostic UI delivery into a new application crash.
            Log.w(TAG, "Unable to launch pending process death report", error);
            return false;
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
        }
        if (activity instanceof ProcessDeathReportActivity
                && ProcessDeathReportStore.findLatestPendingId(activity) == null) {
            lastLaunchedReportId = null;
        }
    }
}
