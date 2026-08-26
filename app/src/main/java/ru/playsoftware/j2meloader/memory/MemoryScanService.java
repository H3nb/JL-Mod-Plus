/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.memory;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.shell.MicroActivity;

/** Debug-only Binder endpoint hosted in the same :midlet process as the target. */
public final class MemoryScanService extends Service {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int RESULT_STRIDE = 4;
    private static final int PROBE_A = 0x51A7C3D1;
    private static final int PROBE_B = 0x62B8D4E2;
    private static final int PROBE_C = 0x73C9E5F3;
    private static final String GC_COUNT_STAT = "art.gc.gc-count";
    private static final String GC_TIME_STAT = "art.gc.gc-time";
    private static final long[] EMPTY_RESULTS = new long[0];

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-scan-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong nextOperation = new AtomicLong(1L);
    private final Object resultPageLock = new Object();
    private final Object callbackLock = new Object();
    private final RemoteCallbackList<IMemoryScanCallback> callbacks = new RemoteCallbackList<>();

    /** Reused in :midlet so live refresh does not allocate a fresh managed long[] every second. */
    private final long[] resultPage = new long[1 + MAX_PAGE_SIZE * RESULT_STRIDE];

    private volatile long operationId;
    private volatile long searchGeneration;
    private volatile String state = MemoryScanContract.STATE_IDLE;
    private volatile String message = "Preparing managed ART self-test";
    private volatile String currentQuery = "";
    private volatile int currentScope = MemoryScanContract.SCOPE_JAVA_FAST;
    private volatile int currentValueType = MemoryScanContract.TYPE_AUTO;
    private volatile boolean hasSearchSession;
    private volatile boolean targetDestroyed;
    private volatile long resultCount;
    private volatile String diagnostics = "";
    private volatile String nativeCapability = "PENDING";
    private volatile String managedSelfTest = "RUNNING";
    private volatile long lastGcCountDelta;
    private volatile long lastGcTimeDeltaMs;
    private volatile long gcAtCandidateBind = -1L;

    private final Application.ActivityLifecycleCallbacks runtimeLifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}
                @Override public void onActivityStarted(@NonNull Activity activity) {}
                @Override public void onActivityResumed(@NonNull Activity activity) {}
                @Override public void onActivityPaused(@NonNull Activity activity) {}
                @Override public void onActivityStopped(@NonNull Activity activity) {}
                @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                        @NonNull Bundle outState) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    if (!(activity instanceof MicroActivity)) return;
                    targetDestroyed = true;
                    NativeMemoryAgent.nativeCancel();
                    invalidateForTargetLoss();
                    notifyTargetClosed();
                    worker.execute(NativeMemoryAgent::nativeClear);
                    stopSelf();
                }
            };

    private final IMemoryScanService.Stub binder = new IMemoryScanService.Stub() {
        @Override
        public Bundle getCapabilities() {
            Bundle bundle = new Bundle();
            long generation = targetDestroyed ? 0L : MemoryRuntimeSession.currentGeneration();
            bundle.putBoolean(MemoryScanContract.KEY_ACTIVE, generation != 0L);
            bundle.putLong(MemoryScanContract.KEY_GENERATION, generation);
            bundle.putString(MemoryScanContract.KEY_CAPABILITY,
                    generation == 0L ? "TARGET_NOT_VISIBLE" : nativeCapability);
            bundle.putString(MemoryScanContract.KEY_MANAGED_SELF_TEST, managedSelfTest);
            return bundle;
        }

        @Override
        public void registerCallback(IMemoryScanCallback callback) {
            if (callback == null) return;
            callbacks.register(callback);
            try {
                callback.onStatusChanged(buildStatusBundle());
            } catch (RemoteException ignored) {
                callbacks.unregister(callback);
            }
        }

        @Override
        public void unregisterCallback(IMemoryScanCallback callback) {
            if (callback != null) callbacks.unregister(callback);
        }

        @Override
        public long startSearch(long generation, String value, int scope, int valueType) {
            if (!validGeneration(generation) || !scannerReady()) return -1L;
            if (scope != MemoryScanContract.SCOPE_JAVA_FAST
                    && scope != MemoryScanContract.SCOPE_JAVA_THOROUGH) return -1L;
            if (!MemoryScanContract.isSearchType(valueType)) return -1L;
            discardSearchFromOtherGeneration(generation);
            if (!running.compareAndSet(false, true)) return -1L;
            long id = nextOperation.getAndIncrement();
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            message = "New Search · " + MemoryScanContract.scopeName(scope)
                    + " · " + MemoryScanContract.typeName(valueType);
            notifyStatusChanged();
            worker.execute(() -> runSearch(id, generation, value == null ? "" : value,
                    scope, valueType));
            return id;
        }

        @Override
        public long refine(long generation, String value) {
            if (!validGeneration(generation) || !hasSearchSession || searchGeneration != generation
                    || !scannerReady()) return -1L;
            if (!running.compareAndSet(false, true)) return -1L;
            long id = nextOperation.getAndIncrement();
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            message = "Next Scan · refining " + resultCount + " retained "
                    + MemoryScanContract.typeName(currentValueType) + " candidates";
            notifyStatusChanged();
            worker.execute(() -> runRefine(id, generation, value == null ? "" : value));
            return id;
        }

        @Override
        public long[] getResultsPage(long generation, int offset, int limit) {
            if (!validGeneration(generation) || searchGeneration != generation || running.get()
                    || !hasSearchSession || offset < 0 || limit <= 0 || limit > MAX_PAGE_SIZE) {
                return EMPTY_RESULTS;
            }
            synchronized (resultPageLock) {
                int count = NativeMemoryAgent.nativeFillResultsPage(resultPage, offset, limit);
                if (count < 0) return EMPTY_RESULTS;
                resultPage[0] = count;
                return resultPage;
            }
        }

        @Override
        public Bundle editValue(long generation, long address, int valueType,
                String expected, String replacement) {
            Bundle result = new Bundle();
            if (!validGeneration(generation) || searchGeneration != generation || !hasSearchSession) {
                result.putBoolean(MemoryScanContract.KEY_SUCCESS, false);
                result.putString(MemoryScanContract.KEY_MESSAGE,
                        "MIDlet target/search generation is no longer valid");
                return result;
            }
            if (!MemoryScanContract.isCandidateType(valueType)) {
                result.putBoolean(MemoryScanContract.KEY_SUCCESS, false);
                result.putString(MemoryScanContract.KEY_MESSAGE, "Invalid candidate type");
                return result;
            }
            if (running.get()) {
                result.putBoolean(MemoryScanContract.KEY_SUCCESS, false);
                result.putString(MemoryScanContract.KEY_MESSAGE,
                        "Wait for New Search / Next Scan to finish");
                return result;
            }
            String nativeResult = NativeMemoryAgent.nativeEdit(address, valueType,
                    expected == null ? "" : expected,
                    replacement == null ? "" : replacement);
            boolean success = "OK".equals(nativeResult);
            result.putBoolean(MemoryScanContract.KEY_SUCCESS, success);
            result.putString(MemoryScanContract.KEY_MESSAGE,
                    success ? "Raw typed write and independent readback succeeded" : nativeResult);
            return result;
        }

        @Override
        public void clearSearch(long generation) {
            if (!validGeneration(generation)) return;
            if (running.get()) NativeMemoryAgent.nativeCancel();
            worker.execute(() -> {
                NativeMemoryAgent.nativeClear();
                resetSearchState("Search cleared");
                notifyStatusChanged();
            });
        }

        @Override
        public void cancelOperation(long generation) {
            if (validGeneration(generation) && running.get()) NativeMemoryAgent.nativeCancel();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        getApplication().registerActivityLifecycleCallbacks(runtimeLifecycleCallbacks);
        nativeCapability = NativeMemoryAgent.nativeSelfTest();
        worker.execute(() -> {
            managedSelfTest = runManagedSelfTest();
            if ("PASS".equals(managedSelfTest)) {
                message = "Ready · native + managed ART self-tests passed";
            } else {
                state = MemoryScanContract.STATE_ERROR;
                message = "Managed ART self-test failed; game scanning disabled";
            }
            notifyStatusChanged();
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        try {
            startService(new Intent(this, MemoryScanService.class));
        } catch (IllegalStateException error) {
            message = "Scanner session retention unavailable: " + error.getMessage();
        }
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (targetDestroyed || MemoryRuntimeSession.currentGeneration() == 0L) stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        getApplication().unregisterActivityLifecycleCallbacks(runtimeLifecycleCallbacks);
        NativeMemoryAgent.nativeCancel();
        worker.execute(() -> {
            NativeMemoryAgent.nativeClear();
            resetSearchState("Scanner stopped");
        });
        worker.shutdown();
        synchronized (callbackLock) {
            callbacks.kill();
        }
        super.onDestroy();
    }

    private boolean scannerReady() {
        return "OK".equals(nativeCapability) && "PASS".equals(managedSelfTest);
    }

    private String runManagedSelfTest() {
        if (!"OK".equals(nativeCapability)) return "FAIL: " + nativeCapability;
        try {
            ManagedMemoryProbe.set(PROBE_A);
            int search = NativeMemoryAgent.nativeSearch(Integer.toString(PROBE_A),
                    MemoryScanContract.SCOPE_JAVA_FAST, MemoryScanContract.TYPE_INT32);
            if (search != NativeMemoryAgent.RESULT_OK || NativeMemoryAgent.nativeGetResultCount() == 0L) {
                return "FAIL: raw Int32 search could not find managed static probe";
            }

            ManagedMemoryProbe.set(PROBE_B);
            int refine = NativeMemoryAgent.nativeRefine(Integer.toString(PROBE_B));
            if (refine != NativeMemoryAgent.RESULT_OK) {
                return "FAIL: Next Scan lost managed static probe (code " + refine + ")";
            }
            long count = NativeMemoryAgent.nativeGetResultCount();
            if (count != 1L) return "FAIL: managed probe refine remained ambiguous (" + count + ")";

            long address;
            synchronized (resultPageLock) {
                int filled = NativeMemoryAgent.nativeFillResultsPage(resultPage, 0, 1);
                if (filled != 1 || resultPage[0] != 1L
                        || resultPage[2] != MemoryScanContract.TYPE_INT32 || resultPage[3] == 0L) {
                    return "FAIL: managed probe typed snapshot could not be materialized";
                }
                address = resultPage[1];
            }
            String edit = NativeMemoryAgent.nativeEdit(address, MemoryScanContract.TYPE_INT32,
                    Integer.toString(PROBE_B), Integer.toString(PROBE_C));
            if (!"OK".equals(edit) || ManagedMemoryProbe.get() != PROBE_C) {
                return "FAIL: raw managed write/readback did not reach Java field: " + edit;
            }
            return "PASS";
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            return "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            ManagedMemoryProbe.set(0);
            NativeMemoryAgent.nativeClear();
        }
    }

    private void runSearch(long id, long generation, String value, int scope, int valueType) {
        boolean hadSession = hasSearchSession && searchGeneration == generation;
        long gcCountBefore = runtimeStatLong(GC_COUNT_STAT);
        long gcTimeBefore = runtimeStatLong(GC_TIME_STAT);
        try {
            if (!validGeneration(generation)) {
                invalidateForTargetLoss();
                return;
            }
            int result = NativeMemoryAgent.nativeSearch(value, scope, valueType);
            if (!validGeneration(generation)) {
                NativeMemoryAgent.nativeClear();
                invalidateForTargetLoss();
                return;
            }
            recordGcDelta(gcCountBefore, gcTimeBefore);
            finishSearchOperation(result, value, scope, valueType, generation, hadSession);
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            recordGcDelta(gcCountBefore, gcTimeBefore);
            preserveOrFail(hadSession, "Scanner failure: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        } finally {
            if (operationId == id) running.set(false);
            notifyStatusChanged();
        }
    }

    private void runRefine(long id, long generation, String value) {
        long gcCountBefore = runtimeStatLong(GC_COUNT_STAT);
        long gcTimeBefore = runtimeStatLong(GC_TIME_STAT);
        long gcSinceBind = delta(gcAtCandidateBind, gcCountBefore);
        boolean trackingReady = relocationTrackingEnabled(diagnostics);
        boolean relocationAttempted = false;
        boolean untrackedGcDirectRefine = gcSinceBind > 0L && !trackingReady;
        try {
            if (!validGeneration(generation) || searchGeneration != generation) {
                invalidateForTargetLoss();
                return;
            }
            if (!nativeSessionTypeMatches(diagnostics, currentValueType)) {
                NativeMemoryAgent.nativeClear();
                hasSearchSession = false;
                searchGeneration = 0L;
                resultCount = 0L;
                gcAtCandidateBind = -1L;
                state = MemoryScanContract.STATE_ERROR;
                message = "Memory scanner session type desynchronized; stale candidates were discarded";
                return;
            }

            int result;
            if (gcSinceBind > 0L && trackingReady) {
                relocationAttempted = true;
                result = NativeMemoryAgent.nativeRefineRelocating(value);
            } else {
                result = NativeMemoryAgent.nativeRefine(value);
                if (result == NativeMemoryAgent.RESULT_NO_MATCHES) {
                    if (trackingReady) {
                        relocationAttempted = true;
                        result = NativeMemoryAgent.nativeRefineRelocating(value);
                    } else {
                        String zeroDiagnostics = NativeMemoryAgent.nativeGetDiagnostics();
                        NativeMemoryAgent.nativeClear();
                        recordGcDelta(gcCountBefore, gcTimeBefore);
                        diagnostics = withGcDiagnostics(zeroDiagnostics
                                + "\nfinalRetained=0"
                                + "\nuntrackedGcDirectRefine=" + untrackedGcDirectRefine);
                        resultCount = 0L;
                        currentQuery = value;
                        state = MemoryScanContract.STATE_COMPLETE;
                        hasSearchSession = false;
                        searchGeneration = 0L;
                        gcAtCandidateBind = -1L;
                        message = untrackedGcDirectRefine
                                ? "Next Scan complete: 0 candidates · GC occurred while relocation tracking "
                                        + "was unavailable, so moved candidates may have been lost"
                                : "Next Scan complete: 0 candidates";
                        return;
                    }
                }
            }

            if (!validGeneration(generation) || (hasSearchSession && searchGeneration != generation)) {
                NativeMemoryAgent.nativeClear();
                invalidateForTargetLoss();
                return;
            }
            recordGcDelta(gcCountBefore, gcTimeBefore);
            diagnostics = withGcDiagnostics(NativeMemoryAgent.nativeGetDiagnostics());
            if (result == NativeMemoryAgent.RESULT_OK) {
                resultCount = NativeMemoryAgent.nativeGetResultCount();
                currentQuery = value;
                state = MemoryScanContract.STATE_COMPLETE;
                if (resultCount == 0L) {
                    hasSearchSession = false;
                    searchGeneration = 0L;
                    gcAtCandidateBind = -1L;
                } else {
                    gcAtCandidateBind = runtimeStatLong(GC_COUNT_STAT);
                }
                String nativeNote = NativeMemoryAgent.nativeGetLastError();
                String prefix;
                if (relocationAttempted) {
                    prefix = "GC-aware Next Scan complete: ";
                } else if (untrackedGcDirectRefine) {
                    prefix = "GC occurred without relocation tracking; direct Next Scan complete: ";
                } else {
                    prefix = "Next Scan complete: ";
                }
                message = prefix + resultCount + " candidates"
                        + (nativeNote == null || nativeNote.isBlank() ? "" : " · " + nativeNote)
                        + (resultCount == 0L ? " · start a New Search to continue" : "")
                        + overflowSuffix(diagnostics);
            } else {
                resultCount = NativeMemoryAgent.nativeGetResultCount();
                String error = NativeMemoryAgent.nativeGetLastError();
                state = MemoryScanContract.STATE_COMPLETE;
                message = (error == null || error.isBlank() ? "Next Scan did not commit" : error)
                        + " · retained " + resultCount + " previous candidates because the operation failed";
            }
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            recordGcDelta(gcCountBefore, gcTimeBefore);
            preserveOrFail(true, "Next Scan failure: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        } finally {
            if (operationId == id) running.set(false);
            notifyStatusChanged();
        }
    }

    private void finishSearchOperation(int result, String value, int scope, int valueType,
            long generation, boolean hadSession) {
        diagnostics = withGcDiagnostics(NativeMemoryAgent.nativeGetDiagnostics());
        if (result == NativeMemoryAgent.RESULT_OK) {
            resultCount = NativeMemoryAgent.nativeGetResultCount();
            currentQuery = value;
            currentScope = scope;
            currentValueType = valueType;
            searchGeneration = generation;
            hasSearchSession = true;
            state = MemoryScanContract.STATE_COMPLETE;
            gcAtCandidateBind = runtimeStatLong(GC_COUNT_STAT);
            String warning = NativeMemoryAgent.nativeGetLastError();
            message = "New Search complete: " + resultCount + " "
                    + MemoryScanContract.typeName(valueType) + " candidates"
                    + (warning == null || warning.isBlank() ? "" : " · " + warning);
            return;
        }
        String nativeError = NativeMemoryAgent.nativeGetLastError();
        preserveOrFail(hadSession, nativeError == null || nativeError.isBlank()
                ? "New Search failed (code " + result + ")" : nativeError);
    }

    private void preserveOrFail(boolean hadSession, String error) {
        diagnostics = withGcDiagnostics(NativeMemoryAgent.nativeGetDiagnostics());
        if (hadSession) {
            resultCount = NativeMemoryAgent.nativeGetResultCount();
            state = MemoryScanContract.STATE_COMPLETE;
            message = error + " · previous search retained (" + resultCount + ")";
        } else {
            hasSearchSession = false;
            searchGeneration = 0L;
            resultCount = 0L;
            state = MemoryScanContract.STATE_ERROR;
            message = error;
        }
    }

    private void discardSearchFromOtherGeneration(long generation) {
        if (!running.get() && hasSearchSession && searchGeneration != generation) {
            NativeMemoryAgent.nativeClear();
            resetSearchState("Runtime generation changed; stale raw candidates were discarded");
            notifyStatusChanged();
        }
    }

    private boolean validGeneration(long generation) {
        return !targetDestroyed && MemoryRuntimeSession.isActive(generation);
    }

    private void invalidateForTargetLoss() {
        state = MemoryScanContract.STATE_NO_TARGET;
        message = "MIDlet target is no longer running";
        currentQuery = "";
        currentValueType = MemoryScanContract.TYPE_AUTO;
        searchGeneration = 0L;
        hasSearchSession = false;
        resultCount = 0L;
        diagnostics = "";
        lastGcCountDelta = 0L;
        lastGcTimeDeltaMs = 0L;
        gcAtCandidateBind = -1L;
    }

    private void resetSearchState(String statusMessage) {
        state = scannerReady() ? MemoryScanContract.STATE_IDLE : MemoryScanContract.STATE_ERROR;
        message = statusMessage;
        currentQuery = "";
        currentScope = MemoryScanContract.SCOPE_JAVA_FAST;
        currentValueType = MemoryScanContract.TYPE_AUTO;
        searchGeneration = 0L;
        hasSearchSession = false;
        resultCount = 0L;
        diagnostics = "";
        lastGcCountDelta = 0L;
        lastGcTimeDeltaMs = 0L;
        gcAtCandidateBind = -1L;
    }

    private Bundle buildStatusBundle() {
        Bundle bundle = new Bundle();
        long generation = targetDestroyed ? 0L : MemoryRuntimeSession.currentGeneration();
        bundle.putLong(MemoryScanContract.KEY_GENERATION, generation);
        bundle.putString(MemoryScanContract.KEY_CAPABILITY,
                generation == 0L ? "TARGET_NOT_VISIBLE" : nativeCapability);
        bundle.putString(MemoryScanContract.KEY_MANAGED_SELF_TEST, managedSelfTest);
        bundle.putString(MemoryScanContract.KEY_STATE, state);
        bundle.putString(MemoryScanContract.KEY_MESSAGE, message);
        bundle.putLong(MemoryScanContract.KEY_OPERATION_ID, operationId);
        bundle.putLong(MemoryScanContract.KEY_RESULT_COUNT, resultCount);
        bundle.putString(MemoryScanContract.KEY_DIAGNOSTICS, diagnosticsWithSessionGc());
        bundle.putString(MemoryScanContract.KEY_QUERY, currentQuery);
        bundle.putInt(MemoryScanContract.KEY_SCOPE, currentScope);
        bundle.putInt(MemoryScanContract.KEY_VALUE_TYPE, currentValueType);
        bundle.putLong(MemoryScanContract.KEY_GC_COUNT, runtimeStatLong(GC_COUNT_STAT));
        bundle.putLong(MemoryScanContract.KEY_GC_TIME_MS, runtimeStatLong(GC_TIME_STAT));
        bundle.putLong(MemoryScanContract.KEY_GC_COUNT_DELTA, lastGcCountDelta);
        bundle.putLong(MemoryScanContract.KEY_GC_TIME_DELTA_MS, lastGcTimeDeltaMs);
        return bundle;
    }

    private String diagnosticsWithSessionGc() {
        if (!hasSearchSession || gcAtCandidateBind < 0L) return diagnostics;
        long current = runtimeStatLong(GC_COUNT_STAT);
        long since = delta(gcAtCandidateBind, current);
        return diagnostics + "\ngcAtCandidateBind=" + gcAtCandidateBind
                + "\ngcSinceCandidateBind=" + since;
    }

    private void notifyStatusChanged() {
        synchronized (callbackLock) {
            int count = callbacks.beginBroadcast();
            try {
                if (count == 0) return;
                Bundle status = buildStatusBundle();
                for (int i = 0; i < count; ++i) {
                    try {
                        callbacks.getBroadcastItem(i).onStatusChanged(status);
                    } catch (RemoteException ignored) {
                        // RemoteCallbackList removes dead binders automatically.
                    }
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }
    }

    private void notifyTargetClosed() {
        synchronized (callbackLock) {
            int count = callbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; ++i) {
                    try {
                        callbacks.getBroadcastItem(i).onTargetClosed();
                    } catch (RemoteException ignored) {
                        // RemoteCallbackList removes dead binders automatically.
                    }
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }
    }

    private void recordGcDelta(long countBefore, long timeBefore) {
        long countAfter = runtimeStatLong(GC_COUNT_STAT);
        long timeAfter = runtimeStatLong(GC_TIME_STAT);
        lastGcCountDelta = delta(countBefore, countAfter);
        lastGcTimeDeltaMs = delta(timeBefore, timeAfter);
    }

    private String withGcDiagnostics(String nativeDiagnostics) {
        return (nativeDiagnostics == null ? "" : nativeDiagnostics)
                + "\ngcCountDelta=" + lastGcCountDelta
                + "\ngcTimeMsDelta=" + lastGcTimeDeltaMs;
    }

    private static boolean relocationTrackingEnabled(String value) {
        return hasDiagnosticLine(value, "relocationTracking=true");
    }

    private static boolean nativeSessionTypeMatches(String value, int expectedType) {
        if (value == null || value.isBlank()) return true;
        String marker = "sessionType=";
        int index = value.indexOf(marker);
        if (index < 0) return true;
        int end = value.indexOf('\n', index);
        String actual = value.substring(index + marker.length(), end < 0 ? value.length() : end).trim();
        return actual.equals(MemoryScanContract.typeName(expectedType));
    }

    private static boolean hasDiagnosticLine(String value, String line) {
        if (value == null || value.isEmpty()) return false;
        return value.equals(line) || value.startsWith(line + "\n")
                || value.endsWith("\n" + line) || value.contains("\n" + line + "\n");
    }

    private static String overflowSuffix(String value) {
        return value != null && value.contains("overflow=true")
                ? " · one or more Auto type buckets remain quota-limited"
                : "";
    }

    private static long runtimeStatLong(String name) {
        try {
            String value = Debug.getRuntimeStat(name);
            return value == null ? -1L : Long.parseLong(value);
        } catch (RuntimeException error) {
            return -1L;
        }
    }

    private static long delta(long before, long after) {
        return before >= 0L && after >= before ? after - before : -1L;
    }
}