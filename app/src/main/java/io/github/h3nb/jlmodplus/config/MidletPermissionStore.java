package io.github.h3nb.jlmodplus.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Main-process durable decisions scoped to a Library installation, never to a reusable preset.
 * ALLOW is valid only for the exact retained JAR fingerprint that received the grant.
 */
public final class MidletPermissionStore {
    public static final int ASK = 0;
    public static final int ALLOW = 1;
    public static final int DENY = 2;

    public static final String FILE_READ = "file.read";
    public static final String FILE_WRITE = "file.write";

    private MidletPermissionStore() {}

    public static int read(Context context, long appId, String capability, String fingerprint) {
        if (appId <= 0 || !supported(capability)) return DENY;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = key(appId, capability);
        int choice = preferences.getInt(key, ASK);
        if (choice == DENY) return DENY;
        if (choice != ALLOW || fingerprint == null || fingerprint.isEmpty()) return ASK;
        return fingerprint.equals(preferences.getString(key + ".sha256", null)) ? ALLOW : ASK;
    }

    public static boolean write(Context context, long appId, String capability,
            String fingerprint, int decision) {
        if (appId <= 0 || !supported(capability)
                || decision != ASK && decision != ALLOW && decision != DENY
                || decision == ALLOW && (fingerprint == null || fingerprint.isEmpty())) return false;
        String key = key(appId, capability);
        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(context).edit();
        if (decision == ASK) {
            edit.remove(key).remove(key + ".sha256");
        } else if (decision == DENY) {
            edit.putInt(key, DENY).remove(key + ".sha256");
        } else {
            edit.putInt(key, ALLOW).putString(key + ".sha256", fingerprint);
        }
        return edit.commit();
    }

    public static boolean supported(String capability) {
        return FILE_READ.equals(capability) || FILE_WRITE.equals(capability);
    }

    private static String key(long appId, String capability) {
        return "midlet_permission:" + appId + ":" + capability;
    }
}
