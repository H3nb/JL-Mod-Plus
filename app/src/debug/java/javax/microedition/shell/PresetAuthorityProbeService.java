/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.config.PresetAuthorityContract;

/**
 * Debug-only cross-process probe. It does not implement preset behavior; it only invokes the same
 * production runtime client from the real {@code :midlet} process for instrumentation coverage.
 */
public final class PresetAuthorityProbeService extends Service {
    public static final int MSG_PREPARE = 1;
    public static final int MSG_RESOLVE = 2;
    public static final int MSG_SAVE = 3;

    public static final String KEY_PHASE = "phase";
    public static final int PHASE_ENTERED = 1;
    public static final int PHASE_RESULT = 2;
    public static final String KEY_REMOTE_PID = "remotePid";
    public static final String KEY_LAYOUT_COMMITTED = "layoutCommitted";

    private final Messenger messenger =
            new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean handleMessage(Message message) {
        Messenger reply = message.replyTo;
        if (reply == null) return true;

        send(reply, message.what, phase(PHASE_ENTERED));
        Bundle request = message.getData();
        PresetAuthorityClient client = new PresetAuthorityClient(this);
        Bundle result = phase(PHASE_RESULT);

        String appPath = request.getString(PresetAuthorityContract.KEY_APP_PATH);
        long expectedAppId =
                request.getLong(PresetAuthorityContract.KEY_EXPECTED_APP_ID, -1L);
        if (appPath == null) {
            result.putInt(
                    PresetAuthorityContract.KEY_RESULT,
                    PresetAuthorityContract.RESULT_INVALID);
            send(reply, message.what, result);
            return true;
        }

        switch (message.what) {
            case MSG_PREPARE -> {
                PresetAuthorityClient.PrepareResult prepared =
                        client.prepareRuntime(appPath, expectedAppId);
                int code = prepared.isSuccess()
                        ? PresetAuthorityContract.RESULT_OK
                        : prepared.isStale()
                                ? PresetAuthorityContract.RESULT_STALE
                                : PresetAuthorityContract.RESULT_FAILED;
                result.putInt(PresetAuthorityContract.KEY_RESULT, code);
                result.putLong(PresetAuthorityContract.KEY_APP_ID, prepared.appId());
                result.putBoolean(
                        PresetAuthorityContract.KEY_BUILT_IN_THEME_LINKED,
                        prepared.builtInThemeLinked());
            }
            case MSG_RESOLVE -> {
                String target = client.resolveUpdateTarget(appPath, expectedAppId);
                result.putInt(
                        PresetAuthorityContract.KEY_RESULT,
                        PresetAuthorityContract.RESULT_OK);
                if (target != null) {
                    result.putString(PresetAuthorityContract.KEY_UPDATE_TARGET, target);
                }
            }
            case MSG_SAVE -> {
                byte[] payload =
                        request.getByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD);
                String requested =
                        request.getString(PresetAuthorityContract.KEY_UPDATE_TARGET);
                if (payload == null) {
                    result.putInt(
                            PresetAuthorityContract.KEY_RESULT,
                            PresetAuthorityContract.RESULT_INVALID);
                    break;
                }
                VirtualKeyboardSaveResult saved =
                        client.saveVirtualKeyboardLayout(
                                appPath, expectedAppId, payload, requested);
                result.putBoolean(KEY_LAYOUT_COMMITTED, saved.isLayoutCommitted());
                result.putInt(
                        PresetAuthorityContract.KEY_UPDATE_OUTCOME,
                        switch (saved.getPresetUpdateOutcome()) {
                            case LINKED -> PresetAuthorityContract.UPDATE_LINKED;
                            case SAVED_UNLINKED ->
                                    PresetAuthorityContract.UPDATE_SAVED_UNLINKED;
                            case FAILED -> PresetAuthorityContract.UPDATE_FAILED;
                            case NONE -> PresetAuthorityContract.UPDATE_NONE;
                        });
                result.putInt(
                        PresetAuthorityContract.KEY_RESULT,
                        saved.isLayoutCommitted()
                                ? PresetAuthorityContract.RESULT_OK
                                : PresetAuthorityContract.RESULT_FAILED);
            }
            default -> result.putInt(
                    PresetAuthorityContract.KEY_RESULT,
                    PresetAuthorityContract.RESULT_INVALID);
        }
        send(reply, message.what, result);
        return true;
    }

    private static Bundle phase(int phase) {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_PHASE, phase);
        bundle.putInt(KEY_REMOTE_PID, Process.myPid());
        return bundle;
    }

    private static void send(Messenger reply, int what, Bundle data) {
        Message response = Message.obtain();
        response.what = what;
        response.setData(data);
        try {
            reply.send(response);
        } catch (RemoteException ignored) {
            // Instrumentation caller disappeared.
        }
    }
}
