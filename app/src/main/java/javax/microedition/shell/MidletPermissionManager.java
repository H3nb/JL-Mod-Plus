/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import org.microemu.cldc.file.StorageMode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.config.MidletPermissionStore;

/** Java ME file authorization for the current installed runtime, distinct from Android grants. */
public final class MidletPermissionManager {
    static final int CANCEL = 0;
    static final int ONCE = 1;
    static final int SESSION = 2;
    static final int ALWAYS = 3;
    static final int DENY = 4;
    private static volatile Session current;

    @FunctionalInterface
    public interface Choice {
        void accept(int choice);
    }

    private MidletPermissionManager() {}

    static StorageMode install(Context context, String appPath, long appId, String jarSha256) {
        PresetAuthorityClient authority = new PresetAuthorityClient(context);
        MidletPermissionStore.Access decisions = authority.getFilePermissions(appPath, appId, jarSha256);
        clear();
        current = new Session(authority, appPath, appId, jarSha256, decisions.mode, decisions.read, decisions.write);
        return decisions.mode;
    }

    public static void clear() {
        Session previous = current;
        current = null;
        if (previous != null) {
            previous.closed = true;
            cancel(previous);
        }
    }

    public static void cancelPending() {
        Session active = current;
        if (active != null) cancel(active);
    }

    private static void cancel(Session active) {
        Pending prompt = active.pending;
        if (prompt != null) {
            prompt.complete(CANCEL);
            dismiss(prompt);
        }
    }

    public static int checkPermission(String name) {
        Session active = current;
        if (active == null || active.closed || name == null) return 0;
        if ("javax.microedition.io.Connector.file.read".equals(name)) {
            return status(active.readDecision, active.readSession);
        }
        if ("javax.microedition.io.Connector.file.write".equals(name)) {
            return status(active.writeDecision, active.writeSession);
        }
        // Other existing Java ME protocols still use their legacy authorization behavior.
        // Report their implemented capabilities as allowed until they have a policy gate.
        return switch (name) {
            case "javax.microedition.io.Connector.http",
                    "javax.microedition.io.Connector.https",
                    "javax.microedition.io.Connector.socket",
                    "javax.microedition.io.Connector.ssl",
                    "javax.microedition.io.Connector.datagram",
                    "javax.microedition.io.Connector.sms",
                    "javax.microedition.io.Connector.bluetooth.client",
                    "javax.microedition.io.Connector.bluetooth.server",
                    "javax.wireless.messaging.sms.send",
                    "javax.wireless.messaging.sms.receive" -> 1;
            default -> 0;
        };
    }

    private static int status(int decision, boolean sessionGranted) {
        if (decision == MidletPermissionStore.DENY) return 0;
        if (decision == MidletPermissionStore.ALLOW || sessionGranted) return 1;
        return -1;
    }

    public static void requireFileAccess(String root, boolean write) {
        Session active = current;
        if (active == null || active.closed) throw new SecurityException("No active MIDlet permissions");
        try {
            if (org.microemu.cldc.file.VirtualFileSystem.isPrivate(root)) return;
        } catch (java.io.IOException invalidRoot) {
            throw new SecurityException("Invalid virtual filesystem root", invalidRoot);
        }
        String capability = write ? MidletPermissionStore.FILE_WRITE : MidletPermissionStore.FILE_READ;
        int decision = write ? active.writeDecision : active.readDecision;
        boolean granted = write ? active.writeSession : active.readSession;
        if (decision == MidletPermissionStore.ALLOW || granted) return;
        if (decision == MidletPermissionStore.DENY) throw new SecurityException("Java ME file access denied");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // FileConnection is synchronous: waiting here would deadlock the permission dialog.
            throw new SecurityException("File permission requires a MIDlet worker thread");
        }
        synchronized (active.promptLock) {
            if (active.closed) throw new SecurityException("MIDlet runtime has ended");
            if (write ? active.writeSession : active.readSession) return;
            int latest = write ? active.writeDecision : active.readDecision;
            if (latest == MidletPermissionStore.ALLOW) return;
            if (latest == MidletPermissionStore.DENY) {
                throw new SecurityException("Java ME file access denied");
            }
            Pending request = new Pending();
            active.pending = request;
            new Handler(Looper.getMainLooper()).post(() -> showPrompt(active, request, write));
            int result = CANCEL;
            try {
                if (request.finished.await(120, TimeUnit.SECONDS)) {
                    result = request.result.get();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                request.complete(CANCEL);
                dismiss(request);
                active.pending = null;
            }
            if (active.closed) throw new SecurityException("MIDlet runtime has ended");
            if (result == ONCE) return;
            if (result == SESSION) {
                if (write) active.writeSession = true;
                else active.readSession = true;
                return;
            }
            if (result == ALWAYS || result == DENY) {
                int stored = result == ALWAYS ? MidletPermissionStore.ALLOW : MidletPermissionStore.DENY;
                boolean persisted = active.authority.setFilePermission(active.appPath, active.appId,
                        active.jarSha256, active.mode, capability, stored);
                if (persisted || stored == MidletPermissionStore.DENY) {
                    if (write) active.writeDecision = stored;
                    else active.readDecision = stored;
                    if (stored == MidletPermissionStore.ALLOW) return;
                }
            }
            throw new SecurityException("Java ME file access denied");
        }
    }

    private static void showPrompt(Session active, Pending request, boolean write) {
        if (active.closed || active.pending != request || request.result.get() != -1) {
            request.complete(CANCEL);
            return;
        }
        MicroActivity activity = ContextHolder.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            request.complete(CANCEL);
            return;
        }
        try {
            request.dismiss = activity.showFilePermissionPrompt(write, request::complete);
            if (request.dismiss == null || active.closed || request.result.get() != -1) {
                request.complete(CANCEL);
                dismiss(request);
            }
        } catch (RuntimeException failure) {
            request.complete(CANCEL);
        }
    }

    private static void dismiss(Pending request) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Runnable dismiss = request.dismiss;
            if (dismiss != null) dismiss.run();
        });
    }

    private static final class Session {
        final PresetAuthorityClient authority;
        final String appPath;
        final long appId;
        final String jarSha256;
        final StorageMode mode;
        final Object promptLock = new Object();
        volatile int readDecision;
        volatile int writeDecision;
        volatile boolean readSession;
        volatile boolean writeSession;
        volatile boolean closed;
        volatile Pending pending;

        Session(PresetAuthorityClient authority, String appPath, long appId, String jarSha256,
                StorageMode mode, int readDecision, int writeDecision) {
            this.authority = authority;
            this.appPath = appPath;
            this.appId = appId;
            this.jarSha256 = jarSha256;
            this.mode = mode;
            this.readDecision = readDecision;
            this.writeDecision = writeDecision;
        }
    }

    private static final class Pending {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(-1);
        volatile Runnable dismiss;

        void complete(int choice) {
            if (result.compareAndSet(-1, choice)) finished.countDown();
        }
    }
}
