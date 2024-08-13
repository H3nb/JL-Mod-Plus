/*
 * Copyright 2024 Yury Kharchenko
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

package ru.playsoftware.j2meloader.crashes.dialog;

import android.content.Context;
import android.content.Intent;
import com.google.auto.service.AutoService;
import java.io.File;

import org.acra.config.CoreConfiguration;
import org.acra.interaction.ReportInteraction;
import org.jetbrains.annotations.NotNull;

@AutoService(ReportInteraction.class)
public final class DialogInteraction implements ReportInteraction {
	public static final String EXTRA_REPORT_FILE = "REPORT_FILE";
	public static final String EXTRA_REPORT_CONFIG = "REPORT_CONFIG";

	@Override
	public boolean performInteraction(@NotNull Context context,
									  @NotNull CoreConfiguration config,
									  @NotNull File reportFile) {
		Intent intent = new Intent(context, CrashReportDialog.class);
		intent.putExtra(EXTRA_REPORT_FILE, reportFile);
		intent.putExtra(EXTRA_REPORT_CONFIG, config);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
		return false;
	}
}
