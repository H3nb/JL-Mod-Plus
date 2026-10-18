/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.cldc.file;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;

/**
 * Workdir-backed Java ME storage. Root names are guest-visible identifiers, never Android paths.
 * An installation owns its private mounts; C and E are shared by installations in the workdir.
 */
public final class VirtualFileSystem {
    private static volatile Session session;

    private VirtualFileSystem() {}

    public static void install(File workDir, String storageKey) throws IOException {
        install(workDir, storageKey, null);
    }

    public static void install(File workDir, String storageKey, File legacyStorageRoot) throws IOException {
        if (storageKey == null || storageKey.isEmpty() || storageKey.equals(".")
                || storageKey.equals("..") || storageKey.contains("/") || storageKey.contains("\\")
                || storageKey.indexOf(':') >= 0) {
            throw new IOException("Invalid MIDlet storage identity");
        }
        File base = workDir.getCanonicalFile();
        Map<String, Mount> mounts = new LinkedHashMap<>();
        mount(mounts, "c:/", new File(base, "storage/c"), true);
        mount(mounts, "e:/", new File(base, "storage/e"), true);
        alias(mounts, "0:/", "c:/");
        alias(mounts, "1:/", "e:/");
        alias(mounts, "fs/MyStuff/", "e:/");
        File privateBase = new File(new File(base, "data"), storageKey);
        mount(mounts, "private:/", new File(privateBase, "private"), false);
        mount(mounts, "cache:/", new File(privateBase, "cache"), false);
        mount(mounts, "siemens:/", new File(privateBase, "vendor/siemens"), false);
        mount(mounts, "a:/", new File(privateBase, "vendor/siemens/a"), false);
        mount(mounts, "b:/", new File(privateBase, "vendor/siemens/b"), false);
        // Old persisted URLs may address this installation's private/cache folders. Never mount
        // the physical Android root, the workdir itself, or the mixed RMS/vendor data directory.
        if (legacyStorageRoot != null) {
            File legacyRoot = legacyStorageRoot.getCanonicalFile();
            String prefix = legacyRoot.getPath() + File.separator;
            String oldData = privateBase.getPath();
            if (oldData.startsWith(prefix)) {
                String relative = oldData.substring(prefix.length()).replace(File.separatorChar, '/');
                for (String drive : Arrays.asList("c:/", "0:/")) {
                    alias(mounts, drive + relative + "/private/", "private:/");
                    alias(mounts, drive + relative + "/cache/", "cache:/");
                }
            }
        }
        // Prepare common and installation-private directories before publishing the session.
        for (String root : Arrays.asList("c:/", "e:/", "private:/", "cache:/", "siemens:/")) {
            File dir = mounts.get(root).directory;
            checkMount(mounts.get(root));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create virtual drive " + root);
            }
            checkMount(mounts.get(root));
        }
        for (String relative : Arrays.asList("Music", "Images", "Videos", "Recordings")) {
            File folder = checked(mounts.get("e:/"), new File(mounts.get("e:/").directory, relative));
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Cannot prepare virtual media directory");
            }
        }
        File sounds = checked(mounts.get("c:/"), new File(mounts.get("c:/").directory, "Sounds"));
        if (!sounds.isDirectory() && !sounds.mkdirs()) {
            throw new IOException("Cannot prepare virtual media directory");
        }
        session = new Session(Collections.unmodifiableMap(mounts));
    }

    public static void clear() {
        session = null;
    }

    private static void mount(Map<String, Mount> mounts, String name, File directory, boolean shared) {
        // Only primary shared drives are enumerable; private mounts and aliases stay hidden.
        mounts.put(name.toLowerCase(Locale.ROOT), new Mount(directory, shared, !shared));
    }

    private static void alias(Map<String, Mount> mounts, String name, String target) {
        Mount original = mounts.get(target);
        mounts.put(rootKey(name),
                new Mount(original.directory, false, original.privateData));
    }

    private static Session active() throws IOException {
        Session current = session;
        if (current == null) throw new IOException("No active MIDlet filesystem");
        return current;
    }

    public static String rootFor(String guestPath) throws IOException {
        Session current = active();
        String match = null;
        for (String key : current.mounts.keySet()) {
            // Root aliases are case-insensitive, but an installed path inside a legacy URL is not.
            boolean matches = isLegacyPath(key)
                    ? guestPath.regionMatches(true, 0, key, 0, 3)
                        && guestPath.regionMatches(false, 3, key, 3, key.length() - 3)
                    : guestPath.regionMatches(true, 0, key, 0, key.length());
            if (matches && (match == null || key.length() > match.length())) match = key;
        }
        if (match != null) return guestPath.substring(0, match.length());
        throw new IOException("Unknown virtual filesystem root");
    }

    public static Enumeration<String> listRoots() {
        Vector<String> roots = new Vector<>();
        Session current = session;
        if (current != null) {
            for (Map.Entry<String, Mount> entry : current.mounts.entrySet()) {
                if (entry.getValue().visible) roots.add(entry.getKey());
            }
        }
        return roots.elements();
    }

    public static boolean isPrivate(String root) throws IOException {
        return mount(root).privateData;
    }

    public static File resolve(String root, String path) throws IOException {
        Mount mount = mount(root);
        checkMount(mount);
        if (path == null || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || path.startsWith("/") || path.indexOf(':') >= 0) {
            throw new IOException("Invalid virtual filesystem path");
        }
        // Reject traversal, including paths where '..' would still remain inside the mount.
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("Relative path segments are not valid in file URLs");
            }
        }
        if (!mount.directory.isDirectory() && !mount.directory.mkdirs()) {
            throw new IOException("Cannot prepare virtual filesystem root");
        }
        return checked(mount, new File(mount.directory, path));
    }

    public static File checked(String root, File candidate) throws IOException {
        return checked(mount(root), candidate);
    }

    private static File checked(Mount mount, File candidate) throws IOException {
        checkMount(mount);
        File root = mount.directory.getCanonicalFile();
        File resolved = candidate.getCanonicalFile();
        if (!resolved.equals(root)
                && !resolved.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Path escapes virtual filesystem root");
        }
        return resolved;
    }

    private static void checkMount(Mount mount) throws IOException {
        // The configured mount itself must not be a link to another directory.
        if (!mount.directory.getCanonicalFile().equals(mount.directory.getAbsoluteFile())) {
            throw new IOException("Virtual filesystem mount has changed");
        }
    }

    private static boolean isLegacyPath(String root) {
        return root.length() > 3 && (root.regionMatches(true, 0, "c:/", 0, 3)
                || root.regionMatches(true, 0, "0:/", 0, 3));
    }

    private static String rootKey(String root) {
        return isLegacyPath(root) ? root.substring(0, 3).toLowerCase(Locale.ROOT) + root.substring(3)
                : root.toLowerCase(Locale.ROOT);
    }

    private static Mount mount(String root) throws IOException {
        Map<String, Mount> mounts = active().mounts;
        Mount mount = mounts.get(rootKey(root));
        if (mount == null) throw new IOException("Unknown virtual filesystem root");
        return mount;
    }

    private static final class Mount {
        final File directory;
        final boolean visible;
        final boolean privateData;

        Mount(File directory, boolean visible, boolean privateData) {
            this.directory = directory;
            this.visible = visible;
            this.privateData = privateData;
        }
    }

    private static final class Session {
        final Map<String, Mount> mounts;

        Session(Map<String, Mount> mounts) {
            this.mounts = mounts;
        }
    }
}
