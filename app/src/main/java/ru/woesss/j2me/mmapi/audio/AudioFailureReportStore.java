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

package ru.woesss.j2me.mmapi.audio;

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

/** Persists a small, bounded queue of audio reports in the app's private files directory. */
public final class AudioFailureReportStore {
	private static final String DIRECTORY_NAME = "audio-failure-reports";
	private static final String FILE_SUFFIX = ".txt";
	private static final int MAX_REPORTS = 20;
	private static final int MAX_REPORT_LENGTH = 8192;

	private AudioFailureReportStore() {
	}

	public static String save(File filesDirectory, AudioFailure failure) throws IOException {
		if (filesDirectory == null || failure == null) {
			throw new IllegalArgumentException("filesDirectory and failure are required");
		}
		File directory = new File(filesDirectory, DIRECTORY_NAME);
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
			throw new IOException("Unable to create audio report directory");
		}
		String reportId = Long.toUnsignedString(System.currentTimeMillis(), 36)
				+ "-" + Integer.toUnsignedString(System.identityHashCode(failure), 36);
		File reportFile = resolve(directory, reportId);
		try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(reportFile), StandardCharsets.UTF_8))) {
			String text = failure.toReportText();
			writer.write(text, 0, Math.min(text.length(), MAX_REPORT_LENGTH));
		}
		trim(directory);
		return reportId;
	}

	public static String read(File filesDirectory, String reportId) throws IOException {
		File reportFile = resolveReportFile(filesDirectory, reportId);
		if (!reportFile.isFile()) {
			throw new IOException("Audio report not found");
		}
		return readFile(reportFile);
	}

	/** Returns recent reports newest-first for the global Audio diagnostics screen. */
	public static String readAll(File filesDirectory) throws IOException {
		if (filesDirectory == null) {
			throw new IllegalArgumentException("filesDirectory is required");
		}
		File directory = new File(filesDirectory, DIRECTORY_NAME);
		File[] reports = directory.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
		if (reports == null || reports.length == 0) {
			return "";
		}
		Arrays.sort(reports, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
		StringBuilder all = new StringBuilder();
		for (File report : reports) {
			if (all.length() > 0) {
				all.append("\n\n----------------\n\n");
			}
			all.append(readFile(report));
		}
		return all.toString();
	}

	public static boolean delete(File filesDirectory, String reportId) {
		try {
			return resolveReportFile(filesDirectory, reportId).delete();
		} catch (IOException e) {
			return false;
		}
	}

	/** Deletes all persisted Audio diagnostics reports. */
	public static void clear(File filesDirectory) {
		if (filesDirectory == null) {
			throw new IllegalArgumentException("filesDirectory is required");
		}
		File directory = new File(filesDirectory, DIRECTORY_NAME);
		File[] reports = directory.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
		if (reports == null) {
			return;
		}
		for (File report : reports) {
			if (!report.delete()) {
				report.deleteOnExit();
			}
		}
	}

	private static String readFile(File reportFile) throws IOException {
		StringBuilder text = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(reportFile), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null && text.length() < MAX_REPORT_LENGTH) {
				if (text.length() > 0) {
					text.append('\n');
				}
				text.append(line);
			}
		}
		return text.toString();
	}

	private static File resolveReportFile(File filesDirectory, String reportId) throws IOException {
		if (filesDirectory == null || reportId == null || !reportId.matches("[A-Za-z0-9_-]+")) {
			throw new IOException("Invalid audio report id");
		}
		File directory = new File(filesDirectory, DIRECTORY_NAME).getCanonicalFile();
		File file = resolve(directory, reportId).getCanonicalFile();
		String directoryPath = directory.getPath();
		if (!file.getPath().startsWith(directoryPath + File.separator)) {
			throw new IOException("Invalid audio report path");
		}
		return file;
	}

	private static File resolve(File directory, String reportId) {
		return new File(directory, reportId + FILE_SUFFIX);
	}

	private static void trim(File directory) {
		File[] reports = directory.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
		if (reports == null || reports.length <= MAX_REPORTS) {
			return;
		}
		Arrays.sort(reports, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
		for (int i = MAX_REPORTS; i < reports.length; i++) {
			if (!reports[i].delete()) {
				reports[i].deleteOnExit();
			}
		}
	}
}
