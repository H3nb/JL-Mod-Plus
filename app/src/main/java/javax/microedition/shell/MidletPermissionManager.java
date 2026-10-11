package javax.microedition.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.MidletPermissionStore;

/** Java ME file authorization for the current installed runtime, distinct from Android grants. */
public final class MidletPermissionManager {
    private static final int CANCEL = 0;
    private static final int SESSION = 1;
    private static final int ALWAYS = 2;
    private static final int DENY = 3;
    private static volatile Session current;

    private MidletPermissionManager() {}

    static void install(Context context, String appPath, long appId, String jarSha256) {
        PresetAuthorityClient authority = new PresetAuthorityClient(context);
        int[] decisions = authority.getFilePermissions(appPath, appId, jarSha256);
        clear();
        current = new Session(authority, appPath, appId, jarSha256, decisions[0], decisions[1]);
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
            AlertDialog dialog = prompt.dialog;
            if (dialog != null) new Handler(Looper.getMainLooper()).post(dialog::dismiss);
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
        return 0;
    }

    private static int status(int decision, boolean sessionGranted) {
        if (decision == MidletPermissionStore.DENY) return 0;
        if (decision == MidletPermissionStore.ALLOW || sessionGranted) return 1;
        return -1;
    }

    public static void requireFileAccess(String root, boolean write) {
        Session active = current;
        if (active == null || active.closed) throw new SecurityException("No active MIDlet permissions");
        if ("private:/".equalsIgnoreCase(root) || "cache:/".equalsIgnoreCase(root)
                || "siemens:/".equalsIgnoreCase(root)) return;
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
            if ((write ? active.writeDecision : active.readDecision) == MidletPermissionStore.ALLOW) return;
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
                AlertDialog dialog = request.dialog;
                if (dialog != null) new Handler(Looper.getMainLooper()).post(dialog::dismiss);
                active.pending = null;
            }
            if (active.closed) throw new SecurityException("MIDlet runtime has ended");
            if (result == SESSION) {
                if (write) active.writeSession = true;
                else active.readSession = true;
                return;
            }
            if (result == ALWAYS || result == DENY) {
                int stored = result == ALWAYS ? MidletPermissionStore.ALLOW : MidletPermissionStore.DENY;
                boolean persisted = active.authority.setFilePermission(active.appPath, active.appId,
                        active.jarSha256, capability, stored);
                if (persisted) {
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
            String[] choices = {
                    activity.getString(R.string.midlet_file_permission_allow_session),
                    activity.getString(R.string.midlet_file_permission_allow_always),
                    activity.getString(R.string.midlet_file_permission_deny)
            };
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.midlet_file_permission_title)
                    .setMessage(write ? R.string.midlet_file_permission_write
                            : R.string.midlet_file_permission_read)
                    .setItems(choices, (ignored, index) ->
                            request.complete(index == 0 ? SESSION : index == 1 ? ALWAYS : DENY))
                    .setNegativeButton(android.R.string.cancel,
                            (ignored, index) -> request.complete(CANCEL))
                    .setOnCancelListener(ignored -> request.complete(CANCEL))
                    .create();
            request.dialog = dialog;
            if (active.closed || request.result.get() != -1) {
                request.complete(CANCEL);
                return;
            }
            dialog.show();
            if (active.closed || request.result.get() != -1) dialog.dismiss();
        } catch (RuntimeException failure) {
            request.complete(CANCEL);
        }
    }

    private static final class Session {
        final PresetAuthorityClient authority;
        final String appPath;
        final long appId;
        final String jarSha256;
        final Object promptLock = new Object();
        volatile int readDecision;
        volatile int writeDecision;
        volatile boolean readSession;
        volatile boolean writeSession;
        volatile boolean closed;
        volatile Pending pending;

        Session(PresetAuthorityClient authority, String appPath, long appId, String jarSha256,
                int readDecision, int writeDecision) {
            this.authority = authority;
            this.appPath = appPath;
            this.appId = appId;
            this.jarSha256 = jarSha256;
            this.readDecision = readDecision;
            this.writeDecision = writeDecision;
        }
    }

    private static final class Pending {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(-1);
        volatile AlertDialog dialog;

        void complete(int choice) {
            if (result.compareAndSet(-1, choice)) finished.countDown();
        }
    }
}
