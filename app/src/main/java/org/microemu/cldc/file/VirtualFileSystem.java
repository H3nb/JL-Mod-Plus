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
        mount(mounts, "a:/", new File(base, "storage/vendor/siemens/a"), false);
        mount(mounts, "b:/", new File(base, "storage/vendor/siemens/b"), false);
        File privateBase = new File(new File(base, "data"), storageKey);
        mount(mounts, "private:/", new File(privateBase, "private"), false);
        mount(mounts, "cache:/", new File(privateBase, "cache"), false);
        mount(mounts, "siemens:/", new File(privateBase, "vendor/siemens"), false);
        // C and E are always present, even if Android has no removable SD card.
        for (String root : Arrays.asList("c:/", "e:/")) {
            File dir = mounts.get(root).directory;
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create virtual drive " + root);
            }
            checkMount(mounts.get(root));
        }
        session = new Session(Collections.unmodifiableMap(mounts));
    }

    public static void clear() {
        session = null;
    }

    private static void mount(Map<String, Mount> mounts, String name, File directory, boolean visible) {
        mounts.put(name.toLowerCase(Locale.ROOT), new Mount(directory, visible));
    }

    private static void alias(Map<String, Mount> mounts, String name, String target) {
        mounts.put(name.toLowerCase(Locale.ROOT), mounts.get(target));
    }

    private static Session active() throws IOException {
        Session current = session;
        if (current == null) throw new IOException("No active MIDlet filesystem");
        return current;
    }

    public static String rootFor(String guestPath) throws IOException {
        Session current = active();
        for (String key : current.mounts.keySet()) {
            if (guestPath.regionMatches(true, 0, key, 0, key.length())) {
                return guestPath.substring(0, key.length());
            }
        }
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

    public static File resolve(String root, String path) throws IOException {
        Mount mount = mount(root);
        if (path == null || path.indexOf('\\') >= 0 || path.indexOf('\\0') >= 0
                || path.startsWith("/") || path.indexOf(':') >= 0) {
            throw new IOException("Invalid virtual filesystem path");
        }
        // Reject traversal, including paths where '..' would still remain inside the mount.
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("Relative path segments are not valid in file URLs");
            }
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

    private static Mount mount(String root) throws IOException {
        Mount mount = active().mounts.get(root.toLowerCase(Locale.ROOT));
        if (mount == null) throw new IOException("Unknown virtual filesystem root");
        return mount;
    }

    private static final class Mount {
        final File directory;
        final boolean visible;

        Mount(File directory, boolean visible) {
            this.directory = directory;
            this.visible = visible;
        }
    }

    private static final class Session {
        final Map<String, Mount> mounts;

        Session(Map<String, Mount> mounts) {
            this.mounts = mounts;
        }
    }
}
