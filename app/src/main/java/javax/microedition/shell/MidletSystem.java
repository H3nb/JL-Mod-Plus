package javax.microedition.shell;


import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Keep;

import java.util.HashMap;
import java.util.Map;

/** {@link java.lang.System} Delegate for Midlet */
@Keep
public final class MidletSystem {
    private static final String TAG = MidletSystem.class.getSimpleName();

    private static final Map<String, String> PROPERTY = new HashMap<>();

    static void setProperty(String key, String value) {
        PROPERTY.put(key, value);
    }


    public static String getProperty(String key) {
        String value = PROPERTY.get(key);
        if (TextUtils.isEmpty(value)) value = System.getProperty(key);
        Log.d(TAG, "System.getProperty: " + key + "=" + value);
        return value;
    }

    public static String getProperty(String key, String def) {
        String value = PROPERTY.get(key);
        if (TextUtils.isEmpty(value)) value = System.getProperty(key, def);
        Log.d(TAG, "System.getProperty: " + key + "=" + value);
        return value;
    }

    /**
     * Handles an advisory garbage-collection request made by a MIDlet.
     *
     * <p>Forwarding every guest request to Android can start a compacting collection inside hot
     * MIDlet loops. ART still collects normally when allocation pressure requires it.</p>
     */
    public static void gc() {
        // Java ME only requires the VM to make a best effort, so the emulator may ignore the hint.
    }

    /** Preserves the receiver null check while suppressing {@link Runtime#gc()} from guest code. */
    public static void gc(Runtime runtime) {
        if (runtime == null) throw new NullPointerException("runtime");
    }

}
