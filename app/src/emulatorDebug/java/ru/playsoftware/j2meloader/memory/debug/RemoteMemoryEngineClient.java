/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory.debug;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.memory.IMemoryEngineService;
import ru.playsoftware.j2meloader.memory.MemoryEngineService;
import ru.playsoftware.j2meloader.memory.NativeTargetProbe;
import ru.playsoftware.j2meloader.memory.RemoteEngineStatus;

/** One-shot capability probe; no scanner state lives here. */
final class RemoteMemoryEngineClient {
    interface Listener {
        void onRemoteEngineStatus(boolean supported, String diagnostics);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-engine-probe");
        thread.setDaemon(true);
        return thread;
    });

    private boolean bound;
    private boolean destroyed;

    RemoteMemoryEngineClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (destroyed || bound) return;
        bound = context.bindService(new Intent(context, MemoryEngineService.class), connection,
                Context.BIND_AUTO_CREATE);
        if (!bound) publish("remoteEngineSupported=false\nremoteError=bind failed");
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (bound) {
            try { context.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        bound = false;
        worker.shutdownNow();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed) return;
            IMemoryEngineService engine = IMemoryEngineService.Stub.asInterface(binder);
            final int targetPid = Process.myPid();
            final long address;
            final long value;
            try {
                address = NativeTargetProbe.probeAddress();
                value = NativeTargetProbe.probeValue();
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                publish("remoteEngineSupported=false\nremoteError=target probe "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
                return;
            }
            worker.execute(() -> {
                String result;
                try {
                    result = engine.runCapabilityTest(targetPid, address, value)
                            + "\nremoteTargetPageSize=" + NativeTargetProbe.pageSize();
                } catch (RemoteException | RuntimeException error) {
                    result = "remoteEngineSupported=false\nremoteError="
                            + error.getClass().getSimpleName() + ": " + error.getMessage();
                }
                publish(result);
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!destroyed && !RemoteEngineStatus.supported()) {
                publish("remoteEngineSupported=false\nremoteError=engine disconnected");
            }
        }
    };

    private void publish(String diagnostics) {
        RemoteEngineStatus.update(diagnostics);
        boolean supported = RemoteEngineStatus.supported();
        main.post(() -> {
            if (!destroyed && listener != null) {
                listener.onRemoteEngineStatus(supported, RemoteEngineStatus.diagnostics());
            }
        });
    }
}
