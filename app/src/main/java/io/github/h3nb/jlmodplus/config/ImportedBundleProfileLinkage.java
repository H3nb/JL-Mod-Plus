/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Bridges a Library bundle configuration restore to named-preset ownership.
 * The bundle carries materialized local settings, never the source profile's name or linkage.
 */
public final class ImportedBundleProfileLinkage {
    private ImportedBundleProfileLinkage() {}

    @NonNull
    public static Ticket begin(@NonNull Context context, @NonNull File emulatorDir,
                               @NonNull String storageKey) throws IOException {
        File configDir = new File(new File(emulatorDir, "configs"), storageKey).getCanonicalFile();
        File profilesRoot = new File(emulatorDir, "templates").getCanonicalFile();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        synchronized (ProfilesManager.presetSourceLock()) {
            PresetSourceReplacement.Guard old = PresetSourceReplacement.begin(preferences, configDir);
            if (!old.canWrite()) throw new IOException("Unable to detach imported profile ownership");
            return new Ticket(preferences, configDir, profilesRoot, old);
        }
    }

    public static final class Ticket {
        private final SharedPreferences preferences;
        private final File configDir;
        private final File profilesRoot;
        private final PresetSourceReplacement.Guard old;

        private Ticket(SharedPreferences preferences, File configDir, File profilesRoot,
                       PresetSourceReplacement.Guard old) {
            this.preferences = preferences;
            this.configDir = configDir;
            this.profilesRoot = profilesRoot;
            this.old = old;
        }

        /** Filesystem rollback succeeded: restore the previous ownership if still safe. */
        public void rollback() {
            synchronized (ProfilesManager.presetSourceLock()) {
                old.restoreIfUnchanged();
            }
        }

        /** Imported snapshot is already committed; unmatched imports remain CUSTOM. */
        public void complete() {
            synchronized (ProfilesManager.presetSourceLock()) {
              try {
                ProfileModel imported = ProfilesManager.loadConfig(configDir, false);
                if (imported == null) return;
                String matchingName = null;
                for (Profile profile : ProfilesManager.getList(profilesRoot)) {
                    File source = new File(profilesRoot, profile.getName());
                    ProfilesManager.ProfileInfo candidate = ProfilesManager.inspectProfile(profile, source);
                    if (!candidate.completeSnapshotReady || candidate.config == null ||
                            !ProfileConfigMatcher.sameConfig(imported, candidate.config) ||
                            !sameLayout(new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE),
                                    new File(source, Config.MIDLET_KEY_LAYOUT_FILE))) {
                        continue;
                    }
                    if (matchingName != null) return; // Ambiguous; retain CUSTOM.
                    matchingName = profile.getName();
                }
                if (matchingName != null) {
                    new PresetLinkage(preferences, configDir).linkTo(matchingName);
                }
              } catch (RuntimeException failedInspection) {
                // The import has already committed. Keep safe CUSTOM ownership rather
                // than report that the successful data restore failed.
                android.util.Log.w("ImportedBundleProfileLinkage",
                        "Imported configuration remains CUSTOM", failedInspection);
              }
            }
        }
    }

    private static boolean sameLayout(File left, File right) {
        if (left.isFile() != right.isFile()) return false;
        if (!left.isFile()) return !left.exists() && !right.exists();
        if (left.length() != right.length()) return false;
        try (FileInputStream a = new FileInputStream(left);
             FileInputStream b = new FileInputStream(right)) {
            byte[] first = new byte[8192];
            byte[] second = new byte[8192];
            int size;
            while ((size = a.read(first)) != -1) {
                int offset = 0;
                while (offset < size) {
                    int n = b.read(second, offset, size - offset);
                    if (n < 0) return false;
                    offset += n;
                }
                for (int i = 0; i < size; i++) {
                    if (first[i] != second[i]) return false;
                }
            }
            return b.read() == -1;
        } catch (IOException error) {
            return false;
        }
    }
}
