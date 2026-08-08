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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.crashes.dialog.ProcessDeathReportActivity;

/** Main-process lifecycle hook that surfaces a durable report at the next safe resume. */
public final class CrashDiagnosticsLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private String lastLaunchedReportId;

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity instanceof ProcessDeathReportActivity) {
            return;
        }

        String reportId = ProcessExitReconciler.reconcileMidlet(activity);
        if (reportId == null) {
            reportId = ProcessDeathReportStore.findLatestPendingId(activity);
        }
        if (reportId == null || reportId.equals(lastLaunchedReportId)) {
            return;
        }

        lastLaunchedReportId = reportId;
        Intent intent = new Intent(activity, ProcessDeathReportActivity.class)
                .putExtra(ProcessDeathReportActivity.EXTRA_REPORT_ID, reportId);
        activity.startActivity(intent);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity instanceof ProcessDeathReportActivity
                && ProcessDeathReportStore.findLatestPendingId(activity) == null) {
            lastLaunchedReportId = null;
        }
    }
}
