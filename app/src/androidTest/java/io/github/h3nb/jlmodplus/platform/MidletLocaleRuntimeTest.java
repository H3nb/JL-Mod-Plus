/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.os.LocaleList;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.microedition.shell.MidletLocaleProbeActivity;

@RunWith(AndroidJUnit4.class)
public class MidletLocaleRuntimeTest {
    private static final long TIMEOUT_MILLIS = 20_000L;
    private static final String MARKER_NAME = "midlet-locale-runtime.marker";

    @Test
    public void coldSecondaryProcessReceivesApplicationLocaleAndJavaMeIdentity() throws Exception {
        Assume.assumeTrue("Per-app locale runtime fixture requires Android 13+",
                android.os.Build.VERSION.SDK_INT >= 33);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocaleManager localeManager = context.getSystemService(LocaleManager.class);
        assertTrue("LocaleManager must be available on Android 13+", localeManager != null);

        String probeProcess = context.getPackageName() + ":locale_probe";
        stopProcess(context, probeProcess);
        File marker = new File(context.getFilesDir(), MARKER_NAME);
        if (marker.exists() && !marker.delete()) {
            fail("Unable to clear locale probe marker");
        }

        LocaleList previousLocales = localeManager.getApplicationLocales();
        String testLanguage = chooseApplicationLanguageAbsentFromSystemLocales(
                localeManager.getSystemLocales());
        try {
            localeManager.setApplicationLocales(LocaleList.forLanguageTags(testLanguage));
            assertEquals("Framework per-app locale must be committed before cold process start",
                    testLanguage, localeManager.getApplicationLocales().toLanguageTags());

            context.startActivity(new Intent(context, MidletLocaleProbeActivity.class)
                    .putExtra(MidletLocaleProbeActivity.EXTRA_MARKER_PATH, marker.getAbsolutePath())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitMarker(marker);

            String hostLocale = markerValue(marker, "host-locale=");
            assertTrue("Cold secondary process must receive selected app language: " + hostLocale,
                    hostLocale.equals("host-locale=" + testLanguage)
                            || hostLocale.startsWith("host-locale=" + testLanguage + "-"));
            assertEquals("Java ME locale must preserve language-only identity without"
                            + " a compatible system region",
                    "locale=" + testLanguage, markerValue(marker, "locale="));
            assertEquals("Host System property must receive the resolved Java ME locale",
                    "system-property=" + testLanguage,
                    markerValue(marker, "system-property="));
            assertEquals("Transformed MIDlet property delegate must receive the same locale",
                    "midlet-property=" + testLanguage,
                    markerValue(marker, "midlet-property="));
        } finally {
            stopProcess(context, probeProcess);
            localeManager.setApplicationLocales(previousLocales);
            if (marker.exists()) marker.delete();
        }
    }

    private static String chooseApplicationLanguageAbsentFromSystemLocales(LocaleList systemLocales) {
        String[] candidates = {"fr", "de", "es", "it"};
        for (String candidate : candidates) {
            boolean present = false;
            for (int i = 0; i < systemLocales.size(); i++) {
                Locale locale = systemLocales.get(i);
                if (candidate.equals(locale.getLanguage())) {
                    present = true;
                    break;
                }
            }
            if (!present) return candidate;
        }
        throw new AssertionError("Locale probe needs one supported language absent from system locales");
    }

    private static void awaitMarker(File marker) throws IOException {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        do {
            if (marker.isFile() && marker.length() > 0) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Locale probe did not publish evidence");
    }

    private static String markerValue(File marker, String prefix) throws IOException {
        String content = readFile(marker);
        for (String line : content.split("\\R")) {
            if (line.startsWith(prefix)) return line;
        }
        fail("Locale probe did not publish value: " + prefix);
        return null;
    }

    private static String readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static void stopProcess(Context context, String processName) {
        int pid = processPid(context, processName);
        if (pid != 0) Process.killProcess(pid);
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (processPid(context, processName) == 0) return;
            SystemClock.sleep(100L);
        }
        fail("Locale probe process did not stop");
    }

    private static int processPid(Context context, String processName) {
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return 0;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (processName.equals(process.processName)) return process.pid;
        }
        return 0;
    }
}
