/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.system.OsConstants;

import io.github.h3nb.jlmodplus.EmulatorApplication;
import io.github.h3nb.jlmodplus.crashes.runtime.CrashBreadcrumbStore;
import io.github.h3nb.jlmodplus.crashes.runtime.CrashSessionStore;

/** ADB-only destructive crash hooks compiled into debug builds only. */
public final class DebugCrashReceiver extends BroadcastReceiver {
    public static final String ACTION_JAVA_CRASH =
            "io.github.h3nb.jlmodplus.debug.CRASH_JAVA";
    public static final String ACTION_NATIVE_CRASH =
            "io.github.h3nb.jlmodplus.debug.CRASH_NATIVE";
    public static final String ACTION_ANR =
            "io.github.h3nb.jlmodplus.debug.ANR";

    // Keep the receiver blocked well past Android's long broadcast watchdog window.
    // On a foreground MIDlet, user input should normally trigger an input-dispatch ANR sooner.
    private static final long ANR_HANG_MS = 120_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_JAVA_CRASH.equals(action)) {
            ensureCrashSession(context, "java");
            CrashBreadcrumbStore.record(context, "debug_inject_java_crash");
            throw new RuntimeException("JL-Mod Plus debug Java crash injection");
        }
        if (ACTION_NATIVE_CRASH.equals(action)) {
            ensureCrashSession(context, "native");
            CrashBreadcrumbStore.record(context, "debug_inject_sigsegv");
            Process.sendSignal(Process.myPid(), OsConstants.SIGSEGV);
            return;
        }
        if (ACTION_ANR.equals(action)) {
            ensureCrashSession(context, "anr");
            CrashBreadcrumbStore.record(context, "debug_inject_anr_begin");
            long end = SystemClock.uptimeMillis() + ANR_HANG_MS;
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(1_000L);
            }
            CrashBreadcrumbStore.record(context, "debug_inject_anr_returned");
        }
    }

    /**
     * A destructive test kills :midlet, so the next ADB broadcast can start a fresh receiver-only
     * :midlet process without going through Config.startApp(). Give that process its own durable
     * diagnostic session instead of silently crashing outside the production session tracker.
     */
    private static void ensureCrashSession(Context context, String kind) {
        CrashSessionStore.Session session = CrashSessionStore.read(context);
        String processName = EmulatorApplication.getProcessName();
        boolean currentRunningSession = session != null
                && CrashSessionStore.STATE_RUNNING.equals(session.state)
                && processName.equals(session.processName)
                && (session.pid <= 0 || session.pid == Process.myPid());
        if (currentRunningSession) {
            CrashBreadcrumbStore.record(context,
                    "debug_inject_use_existing_session kind=" + kind);
            return;
        }

        CrashSessionStore.startMidletSession(
                context,
                "Debug crash injection (" + kind + ")",
                "debug://crash-injection/" + kind
        );
        CrashSessionStore.markMidletProcessStarted(context);
        CrashBreadcrumbStore.record(context,
                "debug_inject_synthetic_session kind=" + kind);
    }
}
