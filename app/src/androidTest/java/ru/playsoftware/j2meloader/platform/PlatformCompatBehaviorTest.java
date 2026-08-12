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

package ru.playsoftware.j2meloader.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

import javax.microedition.shell.MicroActivity;

import jlmod.platformfixture.PlatformCompatMidlet;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.Constants;

@RunWith(AndroidJUnit4.class)
public class PlatformCompatBehaviorTest {
	private static final long FIXTURE_TIMEOUT_MILLIS = 20_000L;
	private static final long BACK_TIMEOUT_MILLIS = 5_000L;
	private static final String FIXTURE_ROOT = "platform-compat-fixture";
	private static final String MIDLET_NAME = "JL-Mod Plus Platform Compatibility Fixture";
	private static final String MIDLET_VENDOR = "JL-Mod Plus";
	private static final String MIDLET_VERSION = "1.0";

	@Test
	public void systemBackOpensMenuWithoutRestartingCanvasMidlet() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		String midletProcessName = context.getPackageName() + ":midlet";
		boolean hadEmulatorDirectory = preferences.contains(Constants.PREF_EMULATOR_DIR);
		String previousEmulatorDirectory = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
		boolean hadToolbar = preferences.contains(Constants.PREF_TOOLBAR);
		boolean previousToolbar = preferences.getBoolean(Constants.PREF_TOOLBAR, false);
		boolean hadStatusBar = preferences.contains(Constants.PREF_STATUSBAR);
		boolean previousStatusBar = preferences.getBoolean(Constants.PREF_STATUSBAR, false);
		File root = new File(context.getFilesDir(), FIXTURE_ROOT);
		File appDir = new File(new File(root, "converted"), "fixture");
		File marker = new File(root, "platform.marker");

		try {
			deleteRecursively(root);
			prepareFixture(context, root, appDir, marker);
			assertTrue("Unable to select platform compatibility fixture",
					preferences.edit()
						.putString(Constants.PREF_EMULATOR_DIR, root.getAbsolutePath())
						.commit());

			boolean[][] barStates = {{false, false}, {true, false}, {false, true}, {true, true}};
			for (int i = 0; i < barStates.length; i++) {
				clearMarker(marker);
				assertTrue("Unable to configure platform compatibility bar state",
						preferences.edit()
								.putBoolean(Constants.PREF_TOOLBAR, barStates[i][0])
								.putBoolean(Constants.PREF_STATUSBAR, barStates[i][1])
								.commit());
				launchFixture(context, appDir);
				awaitMarker(marker, "shown");
				awaitMarker(marker, "size=");

				if (i == 0) {
					executeShellCommand("input tap 100 100");
					awaitMarker(marker, "pointer=");
					int sizeEventsBeforeBack = awaitStableSizeEventCount(marker);
					invokeSystemBack(context);
					awaitAccessibilityText(context.getString(R.string.exit));
					awaitMicroActivityTask(context);
					assertEquals("Opening system Back menu must not resize the Java ME Canvas",
							sizeEventsBeforeBack, awaitStableSizeEventCount(marker));
				}
				assertTrue("MIDlet process must remain alive for toolbar/status-bar state",
						processPid(context, midletProcessName) != 0);
				killProcessBestEffort(context, midletProcessName);
				awaitProcessStops(context, midletProcessName);
			}

			clearMarker(marker);
			writeManifest(appDir, marker, PlatformCompatMidlet.DISPLAY_FORM);
			launchFixture(context, appDir);
			awaitMarker(marker, "form");
			awaitAccessibilityText("IME probe");
			assertTrue("Host Displayable must remain alive after Canvas switching coverage",
					processPid(context, midletProcessName) != 0);
		} finally {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				executeShellCommandBestEffort("cmd overlay enable-exclusive --category "
						+ "com.android.internal.systemui.navbar.threebutton");
			}
			killProcessBestEffort(context, midletProcessName);
			restorePreference(preferences, Constants.PREF_EMULATOR_DIR,
					hadEmulatorDirectory, previousEmulatorDirectory);
			restorePreference(preferences, Constants.PREF_TOOLBAR, hadToolbar, previousToolbar);
			restorePreference(preferences, Constants.PREF_STATUSBAR, hadStatusBar, previousStatusBar);
			deleteRecursivelyBestEffort(root);
		}
	}

	private static void invokeSystemBack(Context context) throws IOException {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			executeShellCommand("cmd overlay enable-exclusive --category "
					+ "com.android.internal.systemui.navbar.gestural");
			SystemClock.sleep(500L);
			int width = context.getResources().getDisplayMetrics().widthPixels;
			int y = context.getResources().getDisplayMetrics().heightPixels / 2;
			executeShellCommand("input swipe 1 " + y + " " + (width / 3) + " " + y + " 300");
		} else {
			executeShellCommand("input keyevent KEYCODE_BACK");
		}
	}

	private static void executeShellCommand(String command) throws IOException {
		ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
				.getUiAutomation().executeShellCommand(command);
		if (descriptor != null) {
			try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
				byte[] buffer = new byte[256];
				while (input.read(buffer) != -1) {
					// Drain output so the shell command has completed before assertions run.
				}
			}
		}
		InstrumentationRegistry.getInstrumentation().waitForIdleSync();
	}

	private static void executeShellCommandBestEffort(String command) {
		try {
			executeShellCommand(command);
		} catch (IOException ignored) {
			// Teardown must not replace the validation failure.
		}
	}

	private static void awaitAccessibilityText(String expected) {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		do {
			AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
					.getUiAutomation().getRootInActiveWindow();
			if (root != null && containsText(root, expected)) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("System Back did not expose the MIDlet options menu item: " + expected);
	}

	private static boolean containsText(AccessibilityNodeInfo root, String expected) {
		ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			AccessibilityNodeInfo node = pending.removeFirst();
			CharSequence text = node.getText();
			CharSequence description = node.getContentDescription();
			if (expected.contentEquals(text) || expected.contentEquals(description)) {
				return true;
			}
			for (int i = 0; i < node.getChildCount(); i++) {
				AccessibilityNodeInfo child = node.getChild(i);
				if (child != null) {
					pending.addLast(child);
				}
			}
		}
		return false;
	}

	private static void prepareFixture(Context context, File root, File appDir, File marker)
			throws IOException {
		File configDir = new File(new File(root, "configs"), appDir.getName());
		if (!appDir.mkdirs() && !appDir.isDirectory()) {
			throw new IOException("Unable to create platform fixture app directory");
		}
		if (!configDir.mkdirs() && !configDir.isDirectory()) {
			throw new IOException("Unable to create platform fixture config directory");
		}
		copyFile(new File(context.getApplicationInfo().sourceDir), new File(appDir, "converted.zip"));
		writeManifest(appDir, marker, null);
		ProfileModel profile = new ProfileModel(configDir);
		profile.graphicsMode = 2;
		profile.showKeyboard = false;
		profile.touchInput = true;
		profile.soundBank = "";
		if (!ProfilesManager.saveConfig(profile)) {
			throw new IOException("Unable to save platform compatibility fixture profile");
		}
	}

	private static void writeManifest(File appDir, File marker, String displayMode) throws IOException {
		File manifest = new File(appDir, "converted.dex.conf");
		try (OutputStreamWriter writer = new OutputStreamWriter(
				new FileOutputStream(manifest), StandardCharsets.UTF_8)) {
			writer.write("Manifest-Version: 1.0\n");
			writer.write("MIDlet-Name: " + MIDLET_NAME + "\n");
			writer.write("MIDlet-Vendor: " + MIDLET_VENDOR + "\n");
			writer.write("MIDlet-Version: " + MIDLET_VERSION + "\n");
			writer.write("MIDlet-1: " + MIDLET_NAME + ",," + PlatformCompatMidlet.CLASS_NAME + "\n");
			writer.write(PlatformCompatMidlet.MARKER_PROPERTY + ": " + marker.getAbsolutePath() + "\n");
			if (displayMode != null) {
				writer.write(PlatformCompatMidlet.DISPLAY_PROPERTY + ": " + displayMode + "\n");
			}
		}
	}

	private static void launchFixture(Context context, File appDir) {
		Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appDir.getAbsolutePath()),
				context, MicroActivity.class)
				.putExtra(Constants.KEY_MIDLET_NAME, MIDLET_NAME)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	private static void awaitMarker(File marker, String expected) {
		long deadline = SystemClock.uptimeMillis() + FIXTURE_TIMEOUT_MILLIS;
		do {
			if (marker.isFile()) {
				try {
					String content = readFile(marker);
					if (content.contains(expected)) {
						return;
					}
				} catch (IOException ignored) {
					// The fixture can be writing the marker concurrently; retry the read.
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Platform compatibility fixture did not write marker: " + expected);
	}

	private static void awaitMicroActivityTask(Context context) {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		do {
			ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
				ActivityManager.RecentTaskInfo info = task.getTaskInfo();
				ComponentName topActivity = info.topActivity;
				if (topActivity != null && MicroActivity.class.getName().equals(topActivity.getClassName())) {
					return;
				}
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("System Back finished MicroActivity instead of opening its options menu");
	}

	private static int processPid(Context context, String processName) {
		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
		if (processes == null) {
			return 0;
		}
		for (ActivityManager.RunningAppProcessInfo process : processes) {
			if (processName.equals(process.processName)) {
				return process.pid;
			}
		}
		return 0;
	}

	private static void awaitProcessStops(Context context, String processName) {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		do {
			if (processPid(context, processName) == 0) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("MIDlet fixture process did not stop between platform states");
	}

	private static void killProcessBestEffort(Context context, String processName) {
		int pid = processPid(context, processName);
		if (pid != 0) {
			android.os.Process.killProcess(pid);
		}
	}

	private static void restorePreference(SharedPreferences preferences, String key,
			boolean hadValue, boolean value) {
		if (hadValue) {
			preferences.edit().putBoolean(key, value).commit();
		} else {
			preferences.edit().remove(key).commit();
		}
	}

	private static void restorePreference(SharedPreferences preferences, String key,
			boolean hadValue, String value) {
		if (hadValue) {
			preferences.edit().putString(key, value).commit();
		} else {
			preferences.edit().remove(key).commit();
		}
	}

	private static String readFile(File file) throws IOException {
		byte[] bytes = new byte[(int) file.length()];
		try (FileInputStream input = new FileInputStream(file)) {
			int offset = 0;
			while (offset < bytes.length) {
				int read = input.read(bytes, offset, bytes.length - offset);
				if (read < 0) {
					break;
				}
				offset += read;
			}
			return new String(bytes, 0, offset, StandardCharsets.UTF_8);
		}
	}

	private static int countOccurrences(String value, String token) {
		int count = 0;
		for (int index = 0; (index = value.indexOf(token, index)) >= 0; index += token.length()) {
			count++;
		}
		return count;
	}

	private static int awaitStableSizeEventCount(File marker) throws IOException {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		int previous = -1;
		do {
			int current = countOccurrences(readFile(marker), "size=");
			if (current == previous) {
				return current;
			}
			previous = current;
			SystemClock.sleep(300L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Java ME Canvas size did not stabilize");
		return previous;
	}

	private static void copyFile(File source, File destination) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		try (FileInputStream input = new FileInputStream(source);
				 FileOutputStream output = new FileOutputStream(destination)) {
			for (int read; (read = input.read(buffer)) != -1; ) {
				output.write(buffer, 0, read);
			}
			output.flush();
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IllegalStateException("Unable to delete platform fixture path: " + file);
		}
	}

	private static void clearMarker(File marker) {
		if (marker.exists() && !marker.delete()) {
			throw new IllegalStateException("Unable to clear platform fixture marker");
		}
	}

	private static void deleteRecursivelyBestEffort(File file) {
		try {
			deleteRecursively(file);
		} catch (RuntimeException ignored) {
			// Teardown must not replace the validation failure.
		}
	}
}
