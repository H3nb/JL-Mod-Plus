/*
 * Copyright 2026 JL-Mod Plus contributors
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

package ru.playsoftware.j2meloader.runtime;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.LauncherActivity;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.crashes.MidletSessionStore;

/**
 * Keeps an active full-emulator MIDlet in the foreground process priority while it is backgrounded.
 * The notification is intentional: Android requires a user-visible foreground service for a
 * long-running task, and a durable session marker still provides recovery if the OS terminates the
 * isolated process despite the service.
 */
public final class MidletKeepAliveService extends Service {
    private static final String TAG = "MidletKeepAlive";
    private static final String CHANNEL_ID = "midlet_runtime";
    private static final int NOTIFICATION_ID = 0x4a4c;

    public static void start(Context context) {
        if (!BuildConfig.FULL_EMULATOR || context == null) {
            return;
        }
        Intent intent = new Intent(context, MidletKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // A denied notification permission or device policy must not prevent the MIDlet from
            // launching. The session marker still enables recovery from a later launcher tap.
            Log.w(TAG, "Unable to start MIDlet keep-alive service", e);
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, MidletKeepAliveService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to stop MIDlet keep-alive service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MidletSessionStore.State state = MidletSessionStore.read(getApplicationContext());
        if (state == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            Notification notification = buildNotification(state);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 introduced the typed overload. Zero lets the platform resolve the
                // type from the manifest on releases that predate specialUse.
                startForeground(NOTIFICATION_ID, notification, 0);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to promote MIDlet service to foreground", e);
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(MidletSessionStore.State state) {
        String appName = state.getAppName() == null
                ? getString(R.string.app_name) : state.getAppName();
        Intent launchIntent = new Intent(this, LauncherActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, NOTIFICATION_ID,
                launchIntent, pendingFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(getString(R.string.midlet_running_notification_title))
                .setContentText(getString(R.string.midlet_running_notification_text, appName))
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.midlet_running_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.midlet_running_notification_title));
        manager.createNotificationChannel(channel);
    }
}
