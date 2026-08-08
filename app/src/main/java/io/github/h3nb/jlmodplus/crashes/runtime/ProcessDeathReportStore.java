/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.crashes.runtime;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small bounded store for process-death reports awaiting user review. */
public final class ProcessDeathReportStore {
    private static final String DIRECTORY_NAME = "crash-runtime/process-death-reports";
    private static final String FILE_SUFFIX = ".txt";
    private static final String TRACE_SUFFIX = ".trace";
    private static final int MAX_REPORTS = 5;
    private static final int MAX_REPORT_LENGTH = 32 * 1024;
    public static final int MAX_TRACE_BYTES = 2 * 1024 * 1024;

    private ProcessDeathReportStore() {
    }

    public static String save(Context context, String reportId, String report) throws IOException {
        return save(context, reportId, report, null);
    }

    public static String save(Context context, String reportId, String report, byte[] trace)
            throws IOException {
        File directory = getDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create process death report directory");
        }
        String safeId = validateId(reportId);
        if (trace != null && trace.length > 0) {
            writeBytesAtomically(new File(directory, safeId + TRACE_SUFFIX), trace,
                    Math.min(trace.length, MAX_TRACE_BYTES));
        }

        File target = new File(directory, safeId + FILE_SUFFIX);
        File temporary = new File(directory, safeId + FILE_SUFFIX + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
            String text = report == null ? "" : report;
            writer.write(text, 0, Math.min(text.length(), MAX_REPORT_LENGTH));
        } catch (IOException | RuntimeException error) {
            deleteTraceFile(directory, safeId);
            throw error;
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            deleteTraceFile(directory, safeId);
            throw new IOException("Unable to replace existing process death report");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            deleteTraceFile(directory, safeId);
            throw new IOException("Unable to commit process death report");
        }
        trim(directory);
        return safeId;
    }

    public static String read(Context context, String reportId) throws IOException {
        File reportFile = resolve(context, reportId, FILE_SUFFIX);
        if (!reportFile.isFile()) {
            throw new IOException("Process death report not found");
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(reportFile), StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0 && text.length() < MAX_REPORT_LENGTH) {
                int remaining = MAX_REPORT_LENGTH - text.length();
                text.append(buffer, 0, Math.min(read, remaining));
            }
        }
        return text.toString();
    }

    public static boolean hasTrace(Context context, String reportId) {
        try {
            return resolve(context, reportId, TRACE_SUFFIX).isFile();
        } catch (IOException error) {
            return false;
        }
    }

    public static long getTraceSize(Context context, String reportId) {
        try {
            File trace = resolve(context, reportId, TRACE_SUFFIX);
            return trace.isFile() ? trace.length() : 0L;
        } catch (IOException error) {
            return 0L;
        }
    }

    public static Uri getTraceUri(Context context, String reportId) throws IOException {
        File trace = resolve(context, reportId, TRACE_SUFFIX);
        if (!trace.isFile()) {
            throw new IOException("Process death trace not found");
        }
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".crash-reports",
                trace
        );
    }

    public static String findLatestPendingId(Context context) {
        File directory = getDirectory(context);
        File[] reports = directory.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
        if (reports == null || reports.length == 0) {
            return null;
        }
        Arrays.sort(reports, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        String name = reports[0].getName();
        return name.substring(0, name.length() - FILE_SUFFIX.length());
    }

    public static boolean delete(Context context, String reportId) {
        try {
            String safeId = validateId(reportId);
            File directory = getDirectory(context).getCanonicalFile();
            File report = resolve(context, safeId, FILE_SUFFIX);
            File trace = resolve(context, safeId, TRACE_SUFFIX);
            boolean reportDeleted = !report.exists() || report.delete();
            boolean traceDeleted = !trace.exists() || trace.delete();
            return reportDeleted && traceDeleted;
        } catch (IOException error) {
            return false;
        }
    }

    private static void writeBytesAtomically(File target, byte[] data, int length) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data, 0, length);
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace process death trace");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Unable to commit process death trace");
        }
    }

    private static File resolve(Context context, String reportId, String suffix) throws IOException {
        String safeId = validateId(reportId);
        File directory = getDirectory(context).getCanonicalFile();
        File report = new File(directory, safeId + suffix).getCanonicalFile();
        if (!report.getPath().startsWith(directory.getPath() + File.separator)) {
            throw new IOException("Invalid process death report path");
        }
        return report;
    }

    private static String validateId(String reportId) throws IOException {
        if (reportId == null || !reportId.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Invalid process death report id");
        }
        return reportId;
    }

    private static File getDirectory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY_NAME);
    }

    private static void trim(File directory) {
        File[] reports = directory.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
        if (reports == null || reports.length <= MAX_REPORTS) {
            return;
        }
        Arrays.sort(reports, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (int i = MAX_REPORTS; i < reports.length; i++) {
            String name = reports[i].getName();
            String id = name.substring(0, name.length() - FILE_SUFFIX.length());
            reports[i].delete();
            deleteTraceFile(directory, id);
        }
    }

    private static void deleteTraceFile(File directory, String reportId) {
        File trace = new File(directory, reportId + TRACE_SUFFIX);
        if (trace.exists()) {
            trace.delete();
        }
        File temporary = new File(directory, reportId + TRACE_SUFFIX + ".tmp");
        if (temporary.exists()) {
            temporary.delete();
        }
    }
}
