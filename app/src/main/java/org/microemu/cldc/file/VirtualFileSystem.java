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
 * Java ME storage with a workdir-backed default and an explicit Android compatibility mode.
 * An installation owns its private mounts; C and E are shared by installations in the workdir.
 */
public final class VirtualFileSystem {
    private static volatile Session session;

    private VirtualFileSystem() {}

    public static void install(File workDir, String storageKey) throws IOException {
        install(workDir, storageKey, null);
    }

    public static void install(File workDir, String storageKey, File legacyStorageRoot) throws IOException {
        install(workDir, storageKey, legacyStorageRoot, StorageMode.SHARED, new File[0]);
    }

    public static void install(File workDir, String storageKey, File legacyStorageRoot,
            StorageMode mode, File[] androidRoots) throws IOException {
        if (mode == null) throw new IOException("Missing storage mode");
        if (storageKey == null || storageKey.isEmpty() || storageKey.equals(".")
                || storageKey.equals("..") || storageKey.contains("/") || storageKey.contains("\\")
                || storageKey.indexOf(':') >= 0) {
            throw new IOException("Invalid MIDlet storage identity");
        }
        File base = workDir.getCanonicalFile();
        Map<String, Mount> mounts = new LinkedHashMap<>();
        if (mode == StorageMode.ANDROID) {
            if (androidRoots == null || androidRoots.length == 0) {
                throw new IOException("No Android storage volume available");
            }
            for (File volume : androidRoots) {
                if (volume == null || !volume.isDirectory()) throw new IOException("Android volume unavailable");
            }
            mount(mounts, "c:/", androidRoots[0].getCanonicalFile(), true);
            mount(mounts, "e:/", androidRoots[Math.min(1, androidRoots.length - 1)].getCanonicalFile(), true);
            // A single physical volume still accepts E:/ aliases without advertising a duplicate.
            if (androidRoots.length == 1) mounts.get("e:/").visible = false;
            java.util.List<File> protectedPaths = new java.util.ArrayList<>();
            for (String name : Arrays.asList("data", "converted", "configs", "templates", "cache")) {
                protectedPaths.add(new File(base, name));
            }
            for (String db : Arrays.asList("J2ME-apps.db", "JL-Mod-library.db")) {
                for (String suffix : Arrays.asList("", "-wal", "-shm", "-journal")) {
                    protectedPaths.add(new File(base, db + suffix));
                }
            }
            for (Mount physical : mounts.values()) physical.protectedPaths = protectedPaths;
        } else {
            mount(mounts, "c:/", new File(base, "storage/c"), true);
            mount(mounts, "e:/", new File(base, "storage/e"), true);
        }
        alias(mounts, "0:/", "c:/");
        alias(mounts, "1:/", "e:/");
        alias(mounts, "fs/MyStuff/", "e:/");
        File privateBase = new File(new File(base, "data"), storageKey);
        mount(mounts, "private:/", new File(privateBase, "private"), false);
        mount(mounts, "cache:/", new File(privateBase, "cache"), false);
        mount(mounts, "siemens:/", new File(privateBase, "vendor/siemens"), false);
        mount(mounts, "a:/", new File(privateBase, "vendor/siemens/a"), false);
        mount(mounts, "b:/", new File(privateBase, "vendor/siemens/b"), false);
        // Preserve only this installation's exact private/cache legacy URLs.
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
            if (!dir.isDirectory() && (mode == StorageMode.ANDROID
                    && !mounts.get(root).privateData || !dir.mkdirs())) {
                throw new IOException("Cannot create virtual drive " + root);
            }
            checkMount(mounts.get(root));
        }
        if (mode == StorageMode.SHARED) {
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
                new Mount(original.directory, false, original.privateData, original.protectedPaths));
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
        if (!mount.directory.isDirectory() && (!mount.protectedPaths.isEmpty() || !mount.directory.mkdirs())) {
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
        for (File protectedPath : mount.protectedPaths) {
            // Check both lexical and canonical paths so links cannot expose private data or
            // move the protection away from an internal directory name.
            if (within(candidate.getAbsoluteFile(), protectedPath.getAbsoluteFile())
                    || within(resolved, protectedPath.getCanonicalFile())) {
                throw new IOException("Emulator internal storage is private");
            }
        }
        return resolved;
    }

    public static void checkMutation(String root, File candidate) throws IOException {
        Mount mount = mount(root);
        File resolved = checked(mount, candidate);
        for (File protectedPath : mount.protectedPaths) {
            if (within(protectedPath.getAbsoluteFile(), candidate.getAbsoluteFile())
                    || within(protectedPath.getCanonicalFile(), resolved)) {
                throw new IOException("Cannot modify an ancestor of emulator internal storage");
            }
        }
    }

    private static boolean within(File candidate, File parent) {
        return candidate.equals(parent)
                || candidate.getPath().startsWith(parent.getPath() + File.separator);
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
        boolean visible;
        java.util.List<File> protectedPaths = Collections.emptyList();
        final boolean privateData;

        Mount(File directory, boolean visible, boolean privateData) {
            this(directory, visible, privateData, Collections.emptyList());
        }

        Mount(File directory, boolean visible, boolean privateData, java.util.List<File> protectedPaths) {
            this.protectedPaths = protectedPaths;
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
