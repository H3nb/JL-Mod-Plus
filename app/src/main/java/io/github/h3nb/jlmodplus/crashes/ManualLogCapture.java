/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.github.h3nb.jlmodplus.BuildConfig;

/** User-initiated, bounded logcat snapshot. Never called from a fatal handler or the UI thread. */
public final class ManualLogCapture {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_RECORDS = 500;
    private static final int LOGCAT_LINES = 2000;
    private static final long TIMEOUT_MILLIS = 3000;
    private static final Pattern RECORD_START = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEAF]\\s+.*");
    private static final ThreadPoolExecutor READER = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            task -> {
                Thread thread = new Thread(task, "JLP-manual-logcat");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public enum Status { SUCCESS, PARTIAL, EMPTY, TIMEOUT, FAILED }

    public static final class Result {
        public final Status status;
        public final Uri uri;
        public final String fileName;
        public final int records;
        public final int bytes;
        public final boolean truncated;
        public final String reason;

        Result(Status status, Uri uri, String fileName, int records, int bytes,
                boolean truncated, String reason) {
            this.status = status;
            this.uri = uri;
            this.fileName = fileName;
            this.records = records;
            this.bytes = bytes;
            this.truncated = truncated;
            this.reason = reason;
        }

        public boolean saved() {
            return uri != null && (status == Status.SUCCESS || status == Status.PARTIAL);
        }
    }

    private ManualLogCapture() {}

    public static Result save(Context context) {
        if (context == null) return failure(Status.FAILED, "Missing application context");
        AtomicReference<java.lang.Process> process = new AtomicReference<>();
        Future<Sample> future;
        try {
            future = READER.submit(new Collector(process));
        } catch (RuntimeException error) {
            return failure(Status.FAILED, "Log capture is already busy");
        }
        Sample sample;
        try {
            sample = future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            stop(process.get());
            future.cancel(true);
            return failure(Status.TIMEOUT, "Logcat capture timed out");
        } catch (InterruptedException error) {
            stop(process.get());
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failure(Status.FAILED, "Logcat capture interrupted");
        } catch (Exception error) {
            stop(process.get());
            future.cancel(true);
            return failure(Status.FAILED, "Logcat unavailable");
        }
        if (sample.exitCode != 0) {
            return failure(Status.FAILED, "Logcat exited with status " + sample.exitCode);
        }
        Parsed parsed = parseRecent(sample.bytes, sample.truncated);
        if (parsed.records == 0) return failure(Status.EMPTY, "No readable application records");

        String safe = DiagnosticExportSanitizer.sanitize(context, parsed.text);
        byte[] payload = safe.getBytes(StandardCharsets.UTF_8);
        boolean shortened = parsed.truncated || payload.length > MAX_BYTES;
        if (payload.length > MAX_BYTES) {
            int start = payload.length - MAX_BYTES;
            // Drop the first incomplete line (and any invalid UTF-8 prefix).
            while (start < payload.length && payload[start - 1] != '\n') start++;
            safe = new String(payload, start, payload.length - start, StandardCharsets.UTF_8);
            payload = safe.getBytes(StandardCharsets.UTF_8);
        }
        Status status = shortened ? Status.PARTIAL : Status.SUCCESS;
        String fileName = fileName();
        String header = "JL-Mod Plus manual logcat snapshot\\n"
                + "Version: " + BuildConfig.VERSION_NAME + "\\n"
                + "SDK: " + Build.VERSION.SDK_INT + "\\n"
                + "Captured: " + new Date() + " (" + TimeZone.getDefault().getID() + ")\\n"
                + "Scope: Android application-accessible logcat; main and crash buffers\\n"
                + "Requested: last " + LOGCAT_LINES + " lines; retained at most " + MAX_RECORDS
                + " parsed records\\n"
                + "Records retained: " + parsed.records + "\\n"
                + "Payload bytes: " + payload.length + "\\n"
                + "Status: " + status + "; truncated: " + shortened + "\\n"
                + "This is a manual snapshot, not necessarily the log at the moment of a crash.\\n"
                + "Review the file for sensitive text before sharing.\\n\\n";
        try {
            Uri uri = publish(context, fileName, header + safe);
            return new Result(status, uri, fileName, parsed.records,
                    payload.length, shortened, null);
        } catch (IOException | RuntimeException error) {
            return failure(Status.FAILED, "Could not save log to Downloads");
        }
    }

    private static Result failure(Status status, String reason) {
        return new Result(status, null, null, 0, 0, false, reason);
    }

    private static final class Sample {
        final byte[] bytes;
        final boolean truncated;
        final int exitCode;

        Sample(byte[] bytes, boolean truncated, int exitCode) {
            this.bytes = bytes;
            this.truncated = truncated;
            this.exitCode = exitCode;
        }
    }

    private static final class Collector implements Callable<Sample> {
        private final AtomicReference<java.lang.Process> process;

        Collector(AtomicReference<java.lang.Process> process) { this.process = process; }

        @Override public Sample call() throws Exception {
            ArrayList<String> command = new ArrayList<>();
            command.add("logcat");
            command.add("-d");
            command.add("-t");
            command.add(Integer.toString(LOGCAT_LINES));
            command.add("-b");
            command.add("main");
            command.add("-b");
            command.add("crash");
            command.add("-v");
            command.add("threadtime");
            if (Build.VERSION.SDK_INT >= 30) {
                command.add("--uid=" + Process.myUid());
            }
            command.add("*:V");
            java.lang.Process child = new ProcessBuilder(command)
                    .redirectErrorStream(true).start();
            process.set(child);
            // Error text is never treated as log success if logcat exits nonzero.
            child.getOutputStream().close();
            Tail tail = new Tail(MAX_BYTES);
            try (InputStream input = child.getInputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while (!Thread.currentThread().isInterrupted()
                        && (count = input.read(chunk)) >= 0) {
                    if (count > 0) tail.write(chunk, count);
                }
            } finally {
                process.set(null);
            }
            return new Sample(tail.bytes(), tail.truncated, child.waitFor());
        }
    }

    private static void stop(java.lang.Process process) {
        if (process == null) return;
        try { process.destroy(); } catch (RuntimeException ignored) {}
        try { process.getInputStream().close(); } catch (IOException ignored) {}
        try { process.getErrorStream().close(); } catch (IOException ignored) {}
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
    }

    /** Fixed-size byte tail, not a process-wide background recorder. */
    static final class Tail {
        private final byte[] bytes;
        private int size;
        private int next;
        boolean truncated;

        Tail(int capacity) { bytes = new byte[capacity]; }

        void write(byte[] input, int length) {
            for (int i = 0; i < length; i++) {
                bytes[next] = input[i];
                next = (next + 1) % bytes.length;
                if (size < bytes.length) size++;
                else truncated = true;
            }
        }

        byte[] bytes() {
            byte[] output = new byte[size];
            int begin = (next - size + bytes.length) % bytes.length;
            for (int i = 0; i < size; i++) output[i] = bytes[(begin + i) % bytes.length];
            return output;
        }
    }

    static final class Parsed {
        final String text;
        final int records;
        final boolean truncated;

        Parsed(String text, int records, boolean truncated) {
            this.text = text;
            this.records = records;
            this.truncated = truncated;
        }
    }

    static Parsed parseRecent(byte[] bytes, boolean truncatedInput) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (truncatedInput) {
            int newline = value.indexOf('\n');
            value = newline < 0 ? "" : value.substring(newline + 1);
        }
        ArrayDeque<StringBuilder> records = new ArrayDeque<>();
        boolean extra = truncatedInput;
        for (String line : value.split("\\n", -1)) {
            if (RECORD_START.matcher(line).matches()) {
                records.addLast(new StringBuilder(line).append('\n'));
                if (records.size() > MAX_RECORDS) {
                    records.removeFirst();
                    extra = true;
                }
            } else if (!records.isEmpty()) {
                records.getLast().append(line).append('\n');
            }
        }
        StringBuilder text = new StringBuilder(Math.min(value.length(), MAX_BYTES));
        for (StringBuilder record : records) text.append(record);
        return new Parsed(text.toString(), records.size(), extra);
    }

    private static String fileName() {
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        return "logcat-" + timestamp.format(new Date()) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".txt";
    }

    private static Uri publish(Context context, String name, String content) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DiagnosticBundleStore.PUBLIC_DIRECTORY);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Unable to create log file");
            boolean published = false;
            try {
                try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
                    if (stream == null) throw new IOException("Unable to write log file");
                    stream.write(content.getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                }
                ContentValues update = new ContentValues();
                update.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, update, null, null) != 1) {
                    throw new IOException("Unable to publish log file");
                }
                published = true;
                return uri;
            } finally {
                if (!published) {
                    try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
                }
            }
        }

        @SuppressWarnings("deprecation")
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, DiagnosticBundleStore.PUBLIC_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create Downloads directory");
        }
        File target = new File(directory, name);
        File pending = new File(directory, "." + name + ".part");
        if (target.exists() || pending.exists()) throw new IOException("Log filename collision");
        boolean published = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write(content.getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            if (target.exists() || !pending.renameTo(target)) {
                throw new IOException("Unable to publish saved log");
            }
            published = true;
            return Uri.fromFile(target);
        } finally {
            if (!published) pending.delete();
        }
    }
}
