/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.playsoftware.j2meloader.EmulatorApplication;
import ru.playsoftware.j2meloader.util.FileUtils;

/** Request-owned cache materialization for one AppInstaller instance. */
final class InstallerScratch {
    private static final long STALE_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final AtomicBoolean CLEANED_STALE = new AtomicBoolean();

    private final File directory;

    InstallerScratch() {
        Context context = EmulatorApplication.getInstance();
        File root = new File(context.getCacheDir(), "installer");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Can't create installer cache root: " + root);
        }
        cleanupStaleOnce(root);
        directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdirs()) {
            throw new IllegalStateException("Can't create installer request cache: " + directory);
        }
    }

    File directory() {
        return directory;
    }

    File file(String name) throws IOException {
        return safeChild(name);
    }

    File materialize(Uri uri) throws IOException {
        if (uri == null) throw new NullPointerException("uri is null");
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                File source = new File(path);
                if (source.isFile()) return source;
            }
        }

        Context context = EmulatorApplication.getInstance();
        byte[] prefix = new byte[1024];
        int prefixLength = 0;
        String mimeType = context.getContentResolver().getType(uri);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't read data from uri: " + uri);
            while (prefixLength < prefix.length) {
                int read = in.read(prefix, prefixLength, prefix.length - prefixLength);
                if (read < 0) break;
                if (read == 0) break;
                prefixLength += read;
                if (prefixLength >= 3) break;
            }
            if (prefixLength <= 0) throw new IOException("Can't read data from uri: " + uri);

            File target = safeChild(tempName(uri, mimeType, prefix, prefixLength));
            try (OutputStream out = new FileOutputStream(target, false)) {
                out.write(prefix, 0, prefixLength);
                int read;
                while ((read = in.read(prefix)) != -1) {
                    if (read > 0) out.write(prefix, 0, read);
                }
            }
            return target;
        } catch (SecurityException error) {
            throw new IOException("Can't read data from uri: " + uri, error);
        }
    }

    void clear() {
        FileUtils.deleteDirectory(directory);
    }

    private File safeChild(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\")) {
            throw new IOException("Unsafe installer scratch filename: " + name);
        }
        File root = directory.getCanonicalFile();
        File child = new File(root, name).getCanonicalFile();
        if (!root.equals(child.getParentFile())) {
            throw new IOException("Installer scratch path escapes request root: " + name);
        }
        return child;
    }

    private static String tempName(Uri uri, String mimeType, byte[] prefix, int length) {
        if (length >= 3 && prefix[0] == 'K' && prefix[1] == 'J' && prefix[2] == 'X') {
            return "source.kjx";
        }
        if (length >= 2 && prefix[0] == 0x50 && prefix[1] == 0x4B) {
            return "source.jar";
        }
        String path = uri.getPath();
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".kjx")) return "source.kjx";
            if (lower.endsWith(".jar")) return "source.jar";
            if (lower.endsWith(".jad")) return "source.jad";
        }
        if ("application/java-archive".equalsIgnoreCase(mimeType) ||
                "application/java".equalsIgnoreCase(mimeType) ||
                "application/x-java-archive".equalsIgnoreCase(mimeType) ||
                "application/zip".equalsIgnoreCase(mimeType)) {
            return "source.jar";
        }
        return "source.jad";
    }

    private static void cleanupStaleOnce(File root) {
        if (!CLEANED_STALE.compareAndSet(false, true)) return;
        long cutoff = System.currentTimeMillis() - STALE_AGE_MILLIS;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            long modified = child.lastModified();
            if (modified > 0L && modified < cutoff) {
                FileUtils.deleteDirectory(child);
            }
        }
    }
}
