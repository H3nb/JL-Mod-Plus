package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LocalCrashReportStoreTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void retentionKeepsNewestReportsWithinCountLimit() throws IOException {
		long now = 1_000_000L;
		List<File> reports = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			reports.add(createReport("report-" + i, 1, now - 10_000L - i));
		}

		LocalCrashReportStore.pruneReports(reports, now, 3, 100, 100_000, 1_000);

		assertEquals(3, existingCount(reports));
		assertTrue(reports.get(0).exists());
		assertTrue(reports.get(1).exists());
		assertTrue(reports.get(2).exists());
	}

	@Test
	public void retentionPreservesRecentReportsDuringGracePeriod() throws IOException {
		long now = 1_000_000L;
		List<File> reports = Arrays.asList(
				createReport("recent-1", 5, now - 100),
				createReport("recent-2", 5, now - 200),
				createReport("recent-3", 5, now - 300)
		);

		LocalCrashReportStore.pruneReports(reports, now, 1, 5, 100, 1_000);

		assertEquals(3, existingCount(reports));
	}

	@Test
	public void retentionAlwaysKeepsNewestReport() throws IOException {
		long now = 1_000_000L;
		File newest = createReport("newest", 20, now - 50_000);
		File older = createReport("older", 1, now - 60_000);
		List<File> reports = Arrays.asList(newest, older);

		LocalCrashReportStore.pruneReports(reports, now, 1, 10, 10_000, 1_000);

		assertTrue(newest.exists());
		assertEquals(1, existingCount(reports));
	}

	@Test
	public void retentionIgnoresNonFilesWithoutMutatingCallerList() throws IOException {
		long now = 1_000_000L;
		File newest = createReport("valid-newest", 1, now - 10_000);
		File older = createReport("valid-older", 1, now - 20_000);
		File directory = temporaryFolder.newFolder("not-a-report");
		List<File> reports = Arrays.asList(older, null, directory, newest);
		List<File> originalOrder = new ArrayList<>(reports);

		LocalCrashReportStore.pruneReports(reports, now, 1, 100, 100_000, 1_000);

		assertEquals(originalOrder, reports);
		assertTrue(newest.exists());
		assertFalse(older.exists());
		assertTrue(directory.exists());
	}

	private File createReport(String name, long size, long modified) throws IOException {
		File file = temporaryFolder.newFile(name);
		try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
			randomAccessFile.setLength(size);
		}
		if (!file.setLastModified(modified)) {
			throw new IOException("Unable to set report timestamp");
		}
		return file;
	}

	private static int existingCount(List<File> files) {
		int count = 0;
		for (File file : files) {
			if (file != null && file.exists()) {
				count++;
			}
		}
		return count;
	}
}
