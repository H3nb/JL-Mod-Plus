/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import ru.playsoftware.j2meloader.BuildConfig;

/**
 * Minimal app-owned fallback for fatal Java stacks.
 *
 * ACRA remains the primary collector. This file is written first by the uncaught-exception bridge
 * so a framework process-exit record is not the only evidence when a device terminates the process
 * before ACRA can finish its own report.
 */
final class FatalJavaCrashStore {
	private static final String TAG = FatalJavaCrashStore.class.getSimpleName();
	private static final String DIRECTORY = "diagnostics/java-fatal";
	private static final String SUFFIX = ".fatal.properties";
	private static final int MAX_STACK_CHARS = 256 * 1024;
	private static final int MAX_RECORD_COUNT = 12;
	private static final long MAX_RECORD_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;

	private FatalJavaCrashStore() {}

	static void capture(Context context, Thread thread, Throwable error, String processRole) {
		if (context == null || error == null) return;
		try {
			File directory = directory(context);
			if (!directory.isDirectory() && !directory.mkdirs()) return;
			long timestamp = System.currentTimeMillis();
			String id = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36);
			File destination = new File(directory, timestamp + "-" + id + SUFFIX);
			CrashContextStore.Snapshot appContext = CrashContextStore.currentSnapshot();
			Properties properties = new Properties();
			properties.setProperty("schemaVersion", "1");
			properties.setProperty("timestampMillis", Long.toString(timestamp));
			put(properties, "processRole", processRole);
			put(properties, "thread", threadLabel(thread));
			put(properties, "stackTrace", boundedStackTrace(error));
			put(properties, "appVersion", BuildConfig.VERSION_NAME);
			put(properties, "androidVersion", Integer.toString(Build.VERSION.SDK_INT));
			put(properties, "brand", Build.BRAND);
			put(properties, "model", Build.MODEL);
			if (appContext != null) {
				put(properties, "runId", appContext.runId);
				put(properties, "buildCommit", appContext.buildCommit);
				put(properties, "buildVariant", appContext.buildVariant);
				put(properties, "location", appContext.location);
				put(properties, "previousLocation", appContext.previousLocation);
				put(properties, "action", appContext.action);
				put(properties, "phase", appContext.phase);
				properties.setProperty("contextUpdated", Long.toString(appContext.updatedWallTimeMillis));
			}
			writeAtomic(destination, properties);
			prune(directory, timestamp);
		} catch (Throwable captureFailure) {
			try {
				Log.w(TAG, "Unable to persist fatal Java fallback", captureFailure);
			} catch (Throwable ignored) {}
		}
	}

	static List<Snapshot> load(Context context) {
		File directory = directory(context);
		prune(directory, System.currentTimeMillis());
		File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(SUFFIX));
		if (files == null || files.length == 0) return Collections.emptyList();
		Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
		ArrayList<Snapshot> snapshots = new ArrayList<>(files.length);
		for (File file : files) {
			try (FileInputStream input = new FileInputStream(file)) {
				Properties properties = new Properties();
				properties.load(input);
				if (!"1".equals(properties.getProperty("schemaVersion"))) continue;
				long timestamp = parseLong(properties.getProperty("timestampMillis"), file.lastModified());
				String runId = value(properties, "runId");
				CrashContextStore.Snapshot appContext = CrashContextStore.readForRun(context, runId);
				if (appContext == null && CrashContextStore.isSafeRunId(runId)) {
					appContext = new CrashContextStore.Snapshot(
							runId,
							value(properties, "processRole"),
							value(properties, "buildCommit"),
							value(properties, "buildVariant"),
							value(properties, "location"),
							value(properties, "previousLocation"),
							value(properties, "action"),
							value(properties, "phase"),
							parseLong(properties.getProperty("contextUpdated"), 0),
							Collections.emptyList());
				}
				snapshots.add(new Snapshot(
						file,
						timestamp,
						value(properties, "processRole"),
						value(properties, "thread"),
						value(properties, "stackTrace"),
						value(properties, "appVersion"),
						value(properties, "androidVersion"),
						value(properties, "brand"),
						value(properties, "model"),
						appContext));
			} catch (IOException | RuntimeException error) {
				Log.w(TAG, "Ignoring unreadable fatal Java fallback: " + file.getName(), error);
			}
		}
		return snapshots;
	}

	private static String boundedStackTrace(Throwable error) {
		StringWriter buffer = new StringWriter(4096);
		error.printStackTrace(new PrintWriter(buffer));
		String stack = buffer.toString();
		return stack.length() <= MAX_STACK_CHARS ? stack : stack.substring(0, MAX_STACK_CHARS)
				+ "\n[stack trace truncated]";
	}

	private static String threadLabel(Thread thread) {
		return thread == null ? null : thread.getName() + " (id=" + thread.getId() + ")";
	}

	private static void writeAtomic(File destination, Properties properties) throws IOException {
		AtomicFile atomic = new AtomicFile(destination);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			properties.store(output, null);
			atomic.finishWrite(output);
		} catch (IOException | RuntimeException error) {
			if (output != null) atomic.failWrite(output);
			throw error;
		}
	}

	private static void prune(File directory, long now) {
		File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(SUFFIX));
		if (files == null) return;
		Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
		for (int index = 0; index < files.length; index++) {
			long modified = files[index].lastModified();
			boolean expired = modified > 0 && now >= modified && now - modified > MAX_RECORD_AGE_MILLIS;
			if ((index >= MAX_RECORD_COUNT || expired) && !files[index].delete()) {
				Log.w(TAG, "Unable to prune fatal Java fallback: " + files[index].getName());
			}
		}
	}

	private static File directory(Context context) {
		return new File(context.getFilesDir(), DIRECTORY);
	}

	private static void put(Properties properties, String key, String value) {
		if (value != null && !value.trim().isEmpty()) properties.setProperty(key, value);
	}

	private static String value(Properties properties, String key) {
		String value = properties.getProperty(key);
		return value == null || value.trim().isEmpty() ? null : value;
	}

	private static long parseLong(String value, long fallback) {
		try {
			return value == null ? fallback : Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	static final class Snapshot {
		final File file;
		final long timestampMillis;
		final String processRole;
		final String thread;
		final String stackTrace;
		final String appVersion;
		final String androidVersion;
		final String brand;
		final String model;
		final CrashContextStore.Snapshot appContext;

		Snapshot(File file, long timestampMillis, String processRole, String thread,
				 String stackTrace, String appVersion, String androidVersion, String brand,
				 String model, CrashContextStore.Snapshot appContext) {
			this.file = file;
			this.timestampMillis = timestampMillis;
			this.processRole = processRole;
			this.thread = thread;
			this.stackTrace = stackTrace;
			this.appVersion = appVersion;
			this.androidVersion = androidVersion;
			this.brand = brand;
			this.model = model;
			this.appContext = appContext;
		}
	}
}
