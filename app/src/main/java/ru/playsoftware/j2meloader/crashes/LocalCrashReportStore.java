/*
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

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.util.Log;

import org.acra.file.ReportLocator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** App-owned retention policy for the raw local ACRA payloads. */
final class LocalCrashReportStore {
	private static final String TAG = LocalCrashReportStore.class.getSimpleName();

	static final int MAX_REPORT_COUNT = 20;
	static final long MAX_REPORT_BYTES = 4L * 1024L * 1024L;
	static final long MAX_REPORT_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	static final long DELETE_GRACE_MILLIS = 5L * 60L * 1000L;

	private LocalCrashReportStore() {}

	static void prune(Context context) {
		ReportLocator locator = new ReportLocator(context);
		ArrayList<File> reports = new ArrayList<>();
		Collections.addAll(reports, locator.getUnapprovedReports());
		Collections.addAll(reports, locator.getApprovedReports());
		pruneReports(
				reports,
				System.currentTimeMillis(),
				MAX_REPORT_COUNT,
				MAX_REPORT_BYTES,
				MAX_REPORT_AGE_MILLIS,
				DELETE_GRACE_MILLIS
		);
	}

	static void pruneReports(List<File> reports, long now, int maxCount, long maxBytes,
								 long maxAgeMillis, long graceMillis) {
		reports.removeIf(file -> file == null || !file.isFile());
		reports.sort(Comparator.comparingLong(File::lastModified).reversed());

		int keptCount = 0;
		long keptBytes = 0;
		for (File report : reports) {
			long modified = report.lastModified();
			long age = modified > 0 && now >= modified ? now - modified : 0;
			long size = Math.max(0L, report.length());

			boolean newest = keptCount == 0;
			boolean inGracePeriod = modified > 0 && now >= modified && age < graceMillis;
			boolean expired = modified > 0 && now >= modified && age > maxAgeMillis;
			boolean overCount = keptCount >= maxCount;
			boolean overBytes = keptCount > 0 && exceedsLimit(keptBytes, size, maxBytes);
			boolean shouldDelete = !newest && !inGracePeriod && (expired || overCount || overBytes);

			if (shouldDelete && report.delete()) {
				continue;
			}
			if (shouldDelete) {
				Log.w(TAG, "Unable to delete old crash report: " + report.getName());
			}

			keptCount++;
			keptBytes = saturatedAdd(keptBytes, size);
		}
	}

	private static boolean exceedsLimit(long current, long addition, long limit) {
		if (addition > limit) {
			return true;
		}
		return current > limit - addition;
	}

	private static long saturatedAdd(long left, long right) {
		if (right > Long.MAX_VALUE - left) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}
}
