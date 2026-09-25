/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A process-lifetime reservation for one installed MIDlet's private storage namespace. */
public final class RuntimeStorageLease implements AutoCloseable {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final FileChannel channel;
    private final FileLock lock;

    private RuntimeStorageLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns null when another runtime already owns this path. */
    public static RuntimeStorageLease acquire(File filesDir, File installedDir) throws IOException {
        FileChannel channel = openChannel(filesDir, installedDir);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new RuntimeStorageLease(channel, lock);
        } catch (OverlappingFileLockException occupied) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /** Probes the OS lock without waiting; an abandoned lock file is not an active lease. */
    public static boolean isActive(File filesDir, File installedDir) throws IOException {
        try (FileChannel channel = openChannel(filesDir, installedDir)) {
            try (FileLock probe = channel.tryLock()) {
                return probe == null;
            } catch (OverlappingFileLockException occupied) {
                return true;
            }
        }
    }

    private static FileChannel openChannel(File filesDir, File installedDir) throws IOException {
        File directory = new File(new File(filesDir, "runtime"), "leases");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create runtime lease directory: " + directory);
        }
        File lockFile = new File(directory, digest(installedDir.getCanonicalPath()) + ".lock");
        return new FileOutputStream(lockFile, true).getChannel();
    }

    private static String digest(String identity) {
        final byte[] bytes;
        try {
            bytes = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        char[] text = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            text[i * 2] = HEX[value >>> 4];
            text[i * 2 + 1] = HEX[value & 0xf];
        }
        return new String(text);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (lock.isValid()) lock.release();
        } finally {
            channel.close();
        }
    }
}
