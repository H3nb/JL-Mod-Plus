// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.sonivox.productionfocusrival;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/** Qualification companion only: a distinct UID that can request focus without pausing the host. */
public final class FocusRivalService extends Service {
    public static final String RESULT = "io.github.h3nb.jlmodplus.audioqualification.FOCUS_RESULT";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager manager;
    private AudioFocusRequest request;
    private String resultPackage;
    private String token;
    private boolean released;

    @Override public void onCreate() {
        super.onCreate();
        manager = getSystemService(AudioManager.class);
        NotificationChannel channel = new NotificationChannel("qualification", "Audio focus qualification",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, "qualification")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Audio focus qualification")
                .setContentText("Temporary focus rival; stops automatically")
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else startForeground(7, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("release".equals(intent.getAction())) {
            if (token != null && token.equals(intent.getStringExtra("token"))) {
                release();
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        handler.removeCallbacksAndMessages(null);
        release();
        resultPackage = intent.getStringExtra("resultPackage");
        token = intent.getStringExtra("token");
        released = false;
        int gain = intent.getIntExtra("gain", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        long hold = Math.max(500, Math.min(10000, intent.getLongExtra("holdMillis", 1200)));
        request = new AudioFocusRequest.Builder(gain)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(change -> report("callback", change), handler).build();
        // Let the system observe the new foreground-service process state for API 35+ eligibility.
        handler.postDelayed(() -> {
            int grant = manager.requestAudioFocus(request);
            report("request", grant);
            handler.postDelayed(() -> { release(); stopSelf(); }, hold);
        }, 250);
        return START_NOT_STICKY;
    }

    private void report(String stage, int value) {
        if (resultPackage == null || token == null) return;
        sendBroadcast(new Intent(RESULT).setPackage(resultPackage).putExtra("token", token)
                .putExtra("stage", stage).putExtra("value", value).putExtra("uid", Process.myUid())
                .putExtra("atMillis", SystemClock.elapsedRealtime()));
    }

    private void release() {
        if (released || request == null) return;
        released = true;
        manager.abandonAudioFocusRequest(request);
        report("abandon", 0);
        request = null;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
