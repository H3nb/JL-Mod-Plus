/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
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

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;


import org.acra.ReportField;
import org.acra.config.ConfigUtils;
import org.acra.config.CoreConfiguration;
import org.acra.config.HttpSenderConfiguration;
import org.acra.config.HttpSenderConfigurationBuilder;
import org.acra.data.CrashReportData;
import org.acra.http.DefaultHttpRequest;
import org.acra.security.TLS;
import org.acra.sender.ReportSender;
import org.acra.util.Installation;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.Constants;

public class AppCenterSender implements ReportSender {
	private static final String TAG = AppCenterSender.class.getSimpleName();
	private static final String BASE_URL = "https://in.appcenter.ms/logs?Api-Version=1.0.0";

	private final CoreConfiguration coreConfiguration;
	private final HttpSenderConfiguration httpConfig;

	public AppCenterSender(CoreConfiguration coreConfiguration) {
		this.coreConfiguration = coreConfiguration;
		httpConfig = ConfigUtils.getPluginConfiguration(coreConfiguration, HttpSenderConfiguration.class);
	}

	@NonNull
	public static HttpSenderConfiguration buildHttpSenderConfiguration(Context context) {
		HttpSenderConfigurationBuilder builder = new HttpSenderConfigurationBuilder();
		// Force TLSv1.2 for Android 4.1-4.4
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
				&& Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			builder.withCertificatePath("asset://appcenter.cer");
			builder.withTlsProtocols(TLS.V1_2);
		}
		Map<String, String> httpHeaders = new HashMap<>();
		httpHeaders.put("App-Secret", context.getString(R.string.app_center));
		httpHeaders.put("Install-ID", Installation.id(context));
		return builder.withUri(BASE_URL)
				.withHttpHeaders(httpHeaders)
				.withCompress(true)
				.withEnabled(false)
				.build();
	}

	@Override
	public void send(@NonNull Context context, @NonNull CrashReportData report) {
		final String log = (String) report.get(AppCenterCollector.APPCENTER_LOG);
		if (log == null || log.isBlank()) {
			return;
		}
		String key = context.getString(R.string.app_center);
		if (key.isBlank()) {
			saveToFile(context, report);
			return;
		}
		try {
			new DefaultHttpRequest(coreConfiguration,
					context,
					httpConfig.getHttpMethod(),
					coreConfiguration.getReportFormat().getMatchingHttpContentType(),
					null,
					null,
					httpConfig.getConnectionTimeout(),
					httpConfig.getSocketTimeout(),
					httpConfig.getHttpHeaders()
			).send(new URL(httpConfig.getUri()), log);
		} catch (Exception e) {
			Log.e(TAG, "send: " + e, e);
			saveToFile(context, report);
		}
	}

	private static void saveToFile(@NonNull Context context, @NonNull CrashReportData report) {
		String logFile = Config.getEmulatorDir() + "/crash.txt";
		String msg = "Can't send report!";
		try (FileOutputStream fos = new FileOutputStream(logFile)) {
			JSONObject o = (JSONObject) report.get(ReportField.CUSTOM_DATA.name());
			if (o != null) {
				Object od = o.opt(Constants.KEY_APPCENTER_ATTACHMENT);
				if (od != null) {
					String midlet = (String) od;
					fos.write(midlet.getBytes());
				}
			}
			String stack = report.getString(ReportField.STACK_TRACE);
			if (stack != null) {
				fos.write("\n===================Error===================\n".getBytes());
				fos.write(stack.getBytes());
			}
			String logcat = report.getString(ReportField.LOGCAT);
			if (logcat != null) {
				fos.write("\n==================More=Log=================\n".getBytes());
				fos.write(logcat.getBytes());
			}
			msg += " Saved to file:\n" + logFile;
		} catch (Exception e) {
			Log.e(TAG, "saveToFile: failed save", e);
		}
		String finalMsg = msg;
		new Handler(context.getMainLooper()).post(() ->
				Toast.makeText(context, finalMsg, Toast.LENGTH_LONG).show());
	}
}
