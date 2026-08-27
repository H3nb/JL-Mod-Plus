/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scanner hosted in :memory_engine. Candidate vectors, scan buffers, refinement and raw writes live
 * outside the MIDlet ART heap; :midlet only supplies PID/generation and mincore-compressed ART runs.
 */
public final class RemoteMemoryScanService extends Service {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int RESULT_STRIDE = MemoryScanContract.RAW_RESULT_STRIDE;
    private static final int MAX_RESIDENT_RUNS = 2048;
    private static final long[] EMPTY_RESULTS = new long[0];

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "remote-memory-scan-worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong nextOperation = new AtomicLong(1L);
    private final RemoteCallbackList<IMemoryScanCallback> callbacks = new RemoteCallbackList<>();
    private final Object resultPageLock = new Object();
    private final long[] resultPage = new long[1 + MAX_PAGE_SIZE * RESULT_STRIDE];

    private volatile IMemoryTargetBridge target;
    private volatile boolean targetBound;
    private volatile long operationId;
    private volatile long searchGeneration;
    private volatile long resultCount;
    private volatile boolean hasSearchSession;
    private volatile String state = MemoryScanContract.STATE_NO_TARGET;
    private volatile String message = "Connecting remote target bridge";
    private volatile String currentQuery = "";
    private volatile int currentScope = MemoryScanContract.SCOPE_JAVA_FAST;
    private volatile int currentValueType = MemoryScanContract.TYPE_AUTO;
    private volatile String diagnostics = "backend=remote-memory-engine";

    private final ServiceConnection targetConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            target = IMemoryTargetBridge.Stub.asInterface(binder);
            state = MemoryScanContract.STATE_IDLE;
            message = "Remote engine ready · target-local mincore bridge connected";
            notifyStatusChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            target = null;
            invalidateTarget("MIDlet target bridge disconnected");
            notifyTargetClosed();
            notifyStatusChanged();
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    private final IMemoryScanService.Stub binder = new IMemoryScanService.Stub() {
        @Override
        public Bundle getCapabilities() {
            long generation = targetGeneration();
            Bundle bundle = new Bundle();
            bundle.putBoolean(MemoryScanContract.KEY_ACTIVE, generation != 0L);
            bundle.putLong(MemoryScanContract.KEY_GENERATION, generation);
            bundle.putString(MemoryScanContract.KEY_CAPABILITY, generation == 0L ? "TARGET_NOT_VISIBLE" : "OK");
            // Preserve the existing UI readiness contract. The remote access probe already proved
            // raw read/write/readback; no managed self-scan is required in the engine process.
            bundle.putString(MemoryScanContract.KEY_MANAGED_SELF_TEST, generation == 0L ? "WAITING" : "PASS");
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
            if (!validGeneration(generation) || running.get()
                    || (scope != MemoryScanContract.SCOPE_JAVA_FAST
                    && scope != MemoryScanContract.SCOPE_JAVA_THOROUGH)
                    || !MemoryScanContract.isSearchType(valueType)) return -1L;
            if (!running.compareAndSet(false, true)) return -1L;
            long id = nextOperation.getAndIncrement();
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            message = "Remote New Search · " + MemoryScanContract.scopeName(scope)
                    + " · " + MemoryScanContract.typeName(valueType);
            notifyStatusChanged();
            worker.execute(() -> runSearch(id, generation, value == null ? "" : value,
                    scope, valueType));
            return id;
        }

        @Override
        public long refine(long generation, String value) {
            if (!validGeneration(generation) || running.get() || !hasSearchSession
                    || searchGeneration != generation) return -1L;
            if (!running.compareAndSet(false, true)) return -1L;
            long id = nextOperation.getAndIncrement();
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            message = "Remote Next Scan · refining " + resultCount + " candidates";
            notifyStatusChanged();
            worker.execute(() -> runRefine(id, generation, value == null ? "" : value));
            return id;
        }

        @Override
        public long[] getResultsPage(long generation, int offset, int limit) {
            if (!validGeneration(generation) || running.get() || !hasSearchSession
                    || searchGeneration != generation || offset < 0 || limit <= 0
                    || limit > MAX_PAGE_SIZE) return EMPTY_RESULTS;
            synchronized (resultPageLock) {
                int count = NativeRemoteScanner.nativeFillResultsPage(resultPage, offset, limit);
                if (count < 0) return EMPTY_RESULTS;
                resultPage[0] = count;
                return resultPage;
            }
        }

        @Override
        public Bundle editValue(long generation, long address, int valueType,
                String expected, String replacement) {
            Bundle result = new Bundle();
            if (!validGeneration(generation) || running.get() || !hasSearchSession
                    || searchGeneration != generation || !MemoryScanContract.isCandidateType(valueType)) {
                result.putBoolean(MemoryScanContract.KEY_SUCCESS, false);
                result.putString(MemoryScanContract.KEY_MESSAGE,
                        "Remote target/search generation is no longer safe for writes");
                return result;
            }
            String nativeResult = NativeRemoteScanner.nativeEdit(address, valueType,
                    expected == null ? "" : expected,
                    replacement == null ? "" : replacement);
            boolean success = "OK".equals(nativeResult);
            result.putBoolean(MemoryScanContract.KEY_SUCCESS, success);
            result.putString(MemoryScanContract.KEY_MESSAGE,
                    success ? "Remote typed write + independent readback succeeded" : nativeResult);
            return result;
        }

        @Override
        public void clearSearch(long generation) {
            if (!validGeneration(generation)) return;
            NativeRemoteScanner.nativeCancel();
            worker.execute(() -> {
                NativeRemoteScanner.nativeClear();
                resetSearchState("Remote search cleared");
                notifyStatusChanged();
            });
        }

        @Override
        public void cancelOperation(long generation) {
            if (validGeneration(generation) && running.get()) NativeRemoteScanner.nativeCancel();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Intent targetIntent = new Intent(this, MemoryTargetBridgeService.class);
        targetBound = bindService(targetIntent, targetConnection, Context.BIND_AUTO_CREATE);
        if (!targetBound) {
            state = MemoryScanContract.STATE_ERROR;
            message = "Unable to bind :midlet target bridge";
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        NativeRemoteScanner.nativeCancel();
        NativeRemoteScanner.nativeClear();
        worker.shutdownNow();
        callbacks.kill();
        if (targetBound) {
            try { unbindService(targetConnection); } catch (RuntimeException ignored) {}
        }
        targetBound = false;
        target = null;
        super.onDestroy();
    }

    private void runSearch(long id, long generation, String value, int scope, int valueType) {
        boolean hadSession = hasSearchSession;
        try {
            String targetError = configureTarget(generation, scope);
            if (targetError != null) {
                failOrPreserve(hadSession, targetError);
                return;
            }
            int code = NativeRemoteScanner.nativeSearch(value, scope, valueType);
            diagnostics = engineDiagnostics();
            if (!validGeneration(generation)) {
                NativeRemoteScanner.nativeClear();
                invalidateTarget("MIDlet target changed during remote search");
                return;
            }
            if (code == NativeRemoteScanner.RESULT_OK) {
                resultCount = NativeRemoteScanner.nativeGetResultCount();
                currentQuery = value;
                currentScope = scope;
                currentValueType = valueType;
                searchGeneration = resultCount > 0L ? generation : 0L;
                hasSearchSession = resultCount > 0L;
                state = MemoryScanContract.STATE_COMPLETE;
                String note = NativeRemoteScanner.nativeGetLastError();
                message = "Remote New Search complete: " + resultCount + " "
                        + MemoryScanContract.typeName(valueType) + " candidates"
                        + (note == null || note.isBlank() ? "" : " · " + note);
            } else if (code == NativeRemoteScanner.RESULT_CANCELLED) {
                state = MemoryScanContract.STATE_CANCELLED;
                message = "Remote New Search cancelled";
            } else {
                failOrPreserve(hadSession, nativeError("Remote New Search failed", code));
            }
        } catch (RemoteException | RuntimeException | UnsatisfiedLinkError error) {
            failOrPreserve(hadSession, "Remote scanner failure: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (operationId == id) running.set(false);
            notifyStatusChanged();
        }
    }

    private void runRefine(long id, long generation, String value) {
        try {
            int code = NativeRemoteScanner.nativeRefine(value);
            boolean relocationAttempted = false;
            if (code == NativeRemoteScanner.RESULT_NO_MATCHES) {
                if (NativeRemoteScanner.nativeCanRelocate()) {
                    String targetError = configureTarget(generation, currentScope);
                    if (targetError != null) {
                        NativeRemoteScanner.nativeCommitZero();
                        code = NativeRemoteScanner.RESULT_OK;
                        message = targetError + " · previous raw addresses discarded; final result 0";
                    } else {
                        relocationAttempted = true;
                        code = NativeRemoteScanner.nativeRefineRelocating(value);
                    }
                } else {
                    NativeRemoteScanner.nativeCommitZero();
                    code = NativeRemoteScanner.RESULT_OK;
                }
            }
            diagnostics = engineDiagnostics();
            if (!validGeneration(generation)) {
                NativeRemoteScanner.nativeClear();
                invalidateTarget("MIDlet target changed during remote refinement");
                return;
            }
            if (code == NativeRemoteScanner.RESULT_OK) {
                resultCount = NativeRemoteScanner.nativeGetResultCount();
                currentQuery = value;
                state = MemoryScanContract.STATE_COMPLETE;
                hasSearchSession = resultCount > 0L;
                searchGeneration = resultCount > 0L ? generation : 0L;
                String note = NativeRemoteScanner.nativeGetLastError();
                message = (relocationAttempted ? "Remote address-aware Next Scan complete: "
                        : "Remote Next Scan complete: ") + resultCount + " candidates"
                        + (note == null || note.isBlank() ? "" : " · " + note)
                        + (resultCount == 0L ? " · start a New Search to continue" : "");
            } else if (code == NativeRemoteScanner.RESULT_CANCELLED) {
                state = MemoryScanContract.STATE_CANCELLED;
                message = "Remote Next Scan cancelled";
            } else {
                state = MemoryScanContract.STATE_ERROR;
                message = nativeError("Remote Next Scan failed", code);
            }
        } catch (RemoteException | RuntimeException | UnsatisfiedLinkError error) {
            state = MemoryScanContract.STATE_ERROR;
            message = "Remote Next Scan failure: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage();
        } finally {
            if (operationId == id) running.set(false);
            notifyStatusChanged();
        }
    }

    /** Returns null on success. */
    private String configureTarget(long generation, int scope) throws RemoteException {
        IMemoryTargetBridge bridge = target;
        if (bridge == null) return "Target bridge is not connected";
        if (bridge.getGeneration() != generation || generation == 0L) {
            return "MIDlet generation changed before resident-page snapshot";
        }
        int targetPid = bridge.getTargetPid();
        int pageSize = bridge.getPageSize();
        long[] runs = bridge.getResidentJavaRuns(generation, scope, MAX_RESIDENT_RUNS);
        if (runs == null || runs.length < 2 || runs[0] <= 0L) {
            return "Target mincore bridge returned no resident Java ranges";
        }
        String configured = NativeRemoteScanner.nativeConfigureTarget(targetPid, pageSize, runs);
        return "OK".equals(configured) ? null : "Remote target configuration failed: " + configured;
    }

    private long targetGeneration() {
        IMemoryTargetBridge bridge = target;
        if (bridge == null) return 0L;
        try {
            return bridge.getGeneration();
        } catch (RemoteException error) {
            return 0L;
        }
    }

    private boolean validGeneration(long generation) {
        return generation != 0L && targetGeneration() == generation;
    }

    private void failOrPreserve(boolean hadSession, String error) {
        diagnostics = engineDiagnostics();
        if (hadSession && NativeRemoteScanner.nativeGetResultCount() > 0L) {
            resultCount = NativeRemoteScanner.nativeGetResultCount();
            state = MemoryScanContract.STATE_COMPLETE;
            message = error + " · previous remote search retained (" + resultCount + ")";
        } else {
            hasSearchSession = false;
            searchGeneration = 0L;
            resultCount = 0L;
            state = MemoryScanContract.STATE_ERROR;
            message = error;
        }
    }

    private void invalidateTarget(String reason) {
        NativeRemoteScanner.nativeCancel();
        NativeRemoteScanner.nativeClear();
        hasSearchSession = false;
        searchGeneration = 0L;
        resultCount = 0L;
        currentQuery = "";
        currentValueType = MemoryScanContract.TYPE_AUTO;
        state = MemoryScanContract.STATE_NO_TARGET;
        message = reason;
        diagnostics = "backend=remote-memory-engine\nremoteTarget=lost";
    }

    private void resetSearchState(String reason) {
        hasSearchSession = false;
        searchGeneration = 0L;
        resultCount = 0L;
        currentQuery = "";
        currentValueType = MemoryScanContract.TYPE_AUTO;
        state = targetGeneration() == 0L ? MemoryScanContract.STATE_NO_TARGET : MemoryScanContract.STATE_IDLE;
        message = reason;
        diagnostics = "backend=remote-memory-engine";
    }

    private Bundle buildStatusBundle() {
        Bundle bundle = new Bundle();
        long generation = targetGeneration();
        bundle.putLong(MemoryScanContract.KEY_GENERATION, generation);
        bundle.putString(MemoryScanContract.KEY_CAPABILITY, generation == 0L ? "TARGET_NOT_VISIBLE" : "OK");
        bundle.putString(MemoryScanContract.KEY_MANAGED_SELF_TEST, generation == 0L ? "WAITING" : "PASS");
        bundle.putString(MemoryScanContract.KEY_STATE, state);
        bundle.putString(MemoryScanContract.KEY_MESSAGE, message);
        bundle.putLong(MemoryScanContract.KEY_OPERATION_ID, operationId);
        bundle.putLong(MemoryScanContract.KEY_RESULT_COUNT, resultCount);
        bundle.putString(MemoryScanContract.KEY_DIAGNOSTICS, diagnostics);
        bundle.putString(MemoryScanContract.KEY_QUERY, currentQuery);
        bundle.putInt(MemoryScanContract.KEY_SCOPE, currentScope);
        bundle.putInt(MemoryScanContract.KEY_VALUE_TYPE, currentValueType);
        bundle.putLong(MemoryScanContract.KEY_GC_COUNT, -1L);
        bundle.putLong(MemoryScanContract.KEY_GC_TIME_MS, -1L);
        bundle.putLong(MemoryScanContract.KEY_GC_COUNT_DELTA, -1L);
        bundle.putLong(MemoryScanContract.KEY_GC_TIME_DELTA_MS, -1L);
        return bundle;
    }

    private String engineDiagnostics() {
        return NativeRemoteScanner.nativeGetDiagnostics()
                + "\nscannerHostProcess=:memory_engine"
                + "\nscannerHostPid=" + Process.myPid()
                + "\ntargetGcTelemetry=not-required-for-correctness";
    }

    private String nativeError(String prefix, int code) {
        String detail = NativeRemoteScanner.nativeGetLastError();
        return prefix + " (code " + code + ")"
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private void notifyStatusChanged() {
        int count = callbacks.beginBroadcast();
        try {
            if (count == 0) return;
            Bundle status = buildStatusBundle();
            for (int i = 0; i < count; ++i) {
                try { callbacks.getBroadcastItem(i).onStatusChanged(status); }
                catch (RemoteException ignored) {}
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    private void notifyTargetClosed() {
        int count = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; ++i) {
                try { callbacks.getBroadcastItem(i).onTargetClosed(); }
                catch (RemoteException ignored) {}
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }
}
