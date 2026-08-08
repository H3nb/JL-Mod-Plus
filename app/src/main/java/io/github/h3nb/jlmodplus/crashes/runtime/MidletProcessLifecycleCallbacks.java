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
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;

/** Handles normal MicroActivity finishes that occur before MidletThread exists. */
public final class MidletProcessLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity instanceof MicroActivity
                && activity.isFinishing()
                && MidletThread.getEmulationTimeController() == null) {
            CrashSessionStore.markExpectedMidletExit(
                    activity,
                    "midlet_finished_before_thread_start"
            );
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
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
}
