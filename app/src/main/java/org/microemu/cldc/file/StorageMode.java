package org.microemu.cldc.file;

/** Per-installation storage backend, captured at launch rather than stored in a preset. */
public enum StorageMode {
    SHARED, ANDROID;

    public static StorageMode parse(String value) {
        for (StorageMode mode : values()) if (mode.name().equals(value)) return mode;
        return null;
    }
}
