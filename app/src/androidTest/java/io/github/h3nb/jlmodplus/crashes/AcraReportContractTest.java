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

package io.github.h3nb.jlmodplus.crashes;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.acra.ReportField;
import org.acra.data.CrashReportData;
import org.acra.file.CrashReportPersister;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the persisted report fields consumed by the crash dialog without
 * triggering a real crash or touching a user's pending-report directory.
 */
public class AcraReportContractTest {

	@Test
	public void syntheticReportRetainsStackTraceAndMidletAttachment() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File reportFile = new File(context.getCacheDir(), "acra-contract-report.stacktrace");
		if (reportFile.exists()) {
			assertTrue(reportFile.delete());
		}

		try {
			CrashReportData report = new CrashReportData();
			report.put(ReportField.STACK_TRACE, "java.lang.IllegalStateException: fixture");
			report.put(ReportField.CUSTOM_DATA, "MIDlet: Fixture\nJAR_HASH_MD5: test");
			new CrashReportPersister().store(report, reportFile);

			CrashReportData loaded = new CrashReportPersister().load(reportFile);
			assertEquals("java.lang.IllegalStateException: fixture",
					loaded.getString(ReportField.STACK_TRACE));
			assertEquals("MIDlet: Fixture\nJAR_HASH_MD5: test",
					loaded.getString(ReportField.CUSTOM_DATA));
			assertTrue(reportFile.exists());
		} finally {
			if (reportFile.exists()) {
				assertTrue(reportFile.delete());
			}
		}
		assertFalse(reportFile.exists());
	}
}
