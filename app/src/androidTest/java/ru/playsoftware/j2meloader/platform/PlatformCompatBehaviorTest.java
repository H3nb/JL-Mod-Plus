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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.After;
import org.junit.Before;
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
	private Context context;
	private SharedPreferences preferences;
	private String midletProcessName;
	private boolean hadEmulatorDirectory;
	private String previousEmulatorDirectory;
	private boolean hadToolbar;
	private boolean previousToolbar;
	private boolean hadStatusBar;
	private boolean previousStatusBar;
	private File root;
	private File appDir;
	private File marker;

	@Before
	public void setUpFixture() throws Exception {
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
		midletProcessName = context.getPackageName() + ":midlet";
		stopFixtureBeforeTest();
		hadEmulatorDirectory = preferences.contains(Constants.PREF_EMULATOR_DIR);
		previousEmulatorDirectory = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
		hadToolbar = preferences.contains(Constants.PREF_TOOLBAR);
		previousToolbar = preferences.getBoolean(Constants.PREF_TOOLBAR, false);
		hadStatusBar = preferences.contains(Constants.PREF_STATUSBAR);
		previousStatusBar = preferences.getBoolean(Constants.PREF_STATUSBAR, false);
		root = new File(context.getFilesDir(), FIXTURE_ROOT);
		appDir = new File(new File(root, "converted"), "fixture");
		marker = new File(root, "platform.marker");
		deleteRecursively(root);
		prepareFixture(context, root, appDir, marker);
		assertTrue("Unable to select platform compatibility fixture",
				preferences.edit()
						.putString(Constants.PREF_EMULATOR_DIR, root.getAbsolutePath())
						.commit());
	}

	@After
	public void tearDownFixture() {
		stopFixtureAfterTest();
		restorePreference(preferences, Constants.PREF_EMULATOR_DIR,
				hadEmulatorDirectory, previousEmulatorDirectory);
		restorePreference(preferences, Constants.PREF_TOOLBAR, hadToolbar, previousToolbar);
		restorePreference(preferences, Constants.PREF_STATUSBAR, hadStatusBar, previousStatusBar);
		deleteRecursivelyBestEffort(root);
	}

	@Test
	public void systemBackOpensMenuWithoutRestartingCanvasMidlet() throws Exception {
		launchFixture(context, appDir);
		awaitMarker(marker, "shown");
		awaitMarker(marker, "size=");
		int sizeEventsBeforeBack = awaitStableSizeEventCount(marker);
		invokeGlobalBack();
		awaitAccessibilityText(context.getString(R.string.exit));
		awaitMicroActivityTask(context);
		invokeGlobalBack();
		awaitAccessibilityTextAbsent(context.getString(R.string.exit));
		assertEquals("Opening system Back menu must not resize the Java ME Canvas",
				sizeEventsBeforeBack, awaitStableSizeEventCount(marker));
	}

	@Test
	public void adbLongPressMenuKeyOpensMenuWithoutExiting() throws Exception {
		launchFixture(context, appDir);
		awaitMarker(marker, "shown");
		executeShellCommand("input keyevent --longpress KEYCODE_MENU");
		awaitAccessibilityText(context.getString(R.string.exit));
		invokeGlobalBack();
		awaitAccessibilityTextAbsent(context.getString(R.string.exit));
		assertTrue("Long-press menu must not stop the MIDlet process",
				processPid(context, midletProcessName) != 0);
	}

	@Test
	public void toolbarAndStatusBarStatesKeepCanvasAlive() throws Exception {
		boolean[][] barStates = {{false, false}, {true, false}, {false, true}, {true, true}};
		for (boolean[] barState : barStates) {
			clearMarker(marker);
			assertTrue("Unable to configure platform compatibility bar state",
					preferences.edit()
							.putBoolean(Constants.PREF_TOOLBAR, barState[0])
							.putBoolean(Constants.PREF_STATUSBAR, barState[1])
							.commit());
			launchFixture(context, appDir);
			awaitMarker(marker, "shown");
			awaitMarker(marker, "size=");
			executeShellCommand("input tap 100 100");
			awaitMarker(marker, "pointer=");
			if (!barState[0] && !barState[1]) {
				int sizeEventsBeforeTransientBars = awaitStableSizeEventCount(marker);
				int[] displaySize = physicalDisplaySize();
				executeShellCommand("input touchscreen swipe " + displaySize[0] / 2 + " "
						+ (displaySize[1] - 1) + " " + displaySize[0] / 2 + " "
						+ displaySize[1] / 2 + " 300");
				assertEquals("Transient system bars must overlay instead of resizing the Canvas",
						sizeEventsBeforeTransientBars, awaitStableSizeEventCount(marker));
			}
			assertTrue("MIDlet process must remain alive for toolbar/status-bar state",
					processPid(context, midletProcessName) != 0);
			killFixtureProcess();
		}
	}

	@Test
	public void canvasFormImeCanvasTransitionPreservesGeometry() throws Exception {
		writeManifest(appDir, marker, PlatformCompatMidlet.DISPLAY_TRANSITION);
		launchFixture(context, appDir);
		awaitMarker(marker, "shown");
		awaitMarker(marker, "size=");
		String initialSize = lastMarkerValue(marker, "size=");
		executeShellCommand("input tap 100 100");
		awaitMarker(marker, "form");
		awaitAccessibilityText("IME probe");
		focusFirstEditableNode();
		awaitImeShown();
		appendMarker(marker, PlatformCompatMidlet.RETURN_REQUEST + "\n");
		awaitMarker(marker, "returned");
		awaitMarkerOccurrences(marker, "shown", 2);
		assertEquals("Canvas geometry must survive Canvas-to-Form-to-Canvas transition",
				initialSize, lastMarkerValue(marker, "size="));
		assertTrue("Host Displayable must remain alive after Canvas switching coverage",
				processPid(context, midletProcessName) != 0);
	}

	private void killFixtureProcess() {
		killProcessBestEffort(context, midletProcessName);
		awaitProcessStops(context, midletProcessName);
	}

	private void stopFixtureBeforeTest() {
		finishMicroActivityTasks(context);
		killProcessBestEffort(context, midletProcessName);
		awaitProcessStops(context, midletProcessName);
		finishMicroActivityTasks(context);
	}

	private void stopFixtureAfterTest() {
		killProcessBestEffort(context, midletProcessName);
		awaitProcessStopsBestEffort(context, midletProcessName);
		finishMicroActivityTasks(context);
	}

	private static void invokeGlobalBack() {
		assertTrue("System Back action was rejected",
				InstrumentationRegistry.getInstrumentation().getUiAutomation()
						.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
		InstrumentationRegistry.getInstrumentation().waitForIdleSync();
	}

	private static void executeShellCommand(String command) throws IOException {
		executeShellCommandForOutput(command);
	}

	private static String executeShellCommandForOutput(String command) throws IOException {
		ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
				.getUiAutomation().executeShellCommand(command);
		StringBuilder output = new StringBuilder();
		if (descriptor != null) {
			try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
				byte[] buffer = new byte[256];
				for (int read; (read = input.read(buffer)) != -1; ) {
					output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
				}
			}
		}
		InstrumentationRegistry.getInstrumentation().waitForIdleSync();
		return output.toString();
	}

	private static int[] physicalDisplaySize() throws IOException {
		String output = executeShellCommandForOutput("wm size");
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)x(\\d+)")
				.matcher(output);
		if (!matcher.find()) {
			throw new IOException("Unable to read physical display size: " + output);
		}
		return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
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

	private static void awaitAccessibilityTextAbsent(String expected) {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		do {
			AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
					.getUiAutomation().getRootInActiveWindow();
			if (root == null || !containsText(root, expected)) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Accessibility text remained visible: " + expected);
	}

	private static void focusFirstEditableNode() throws IOException {
		AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
				.getUiAutomation().getRootInActiveWindow();
		AccessibilityNodeInfo node = root == null ? null : findEditableNode(root);
		if (node == null) {
			fail("Unable to locate the host Displayable's editable field");
		}
		node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
		node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
		Rect bounds = new Rect();
		node.getBoundsInScreen(bounds);
		executeShellCommand("input tap " + bounds.centerX() + " " + bounds.centerY());
	}

	private static boolean containsText(AccessibilityNodeInfo root, String expected) {
		return findText(root, expected) != null;
	}

	private static AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String expected) {
		ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			AccessibilityNodeInfo node = pending.removeFirst();
			CharSequence text = node.getText();
			CharSequence description = node.getContentDescription();
			if ((text != null && expected.contentEquals(text))
					|| (description != null && expected.contentEquals(description))) {
				return node;
			}
			for (int i = 0; i < node.getChildCount(); i++) {
				AccessibilityNodeInfo child = node.getChild(i);
				if (child != null) {
					pending.addLast(child);
				}
			}
		}
		return null;
	}

	private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo root) {
		ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			AccessibilityNodeInfo node = pending.removeFirst();
			if (node.isEditable()) {
				return node;
			}
			for (int i = 0; i < node.getChildCount(); i++) {
				AccessibilityNodeInfo child = node.getChild(i);
				if (child != null) {
					pending.addLast(child);
				}
			}
		}
		return null;
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
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
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

	private static void awaitMarkerOccurrences(File marker, String expected, int minimum) {
		long deadline = SystemClock.uptimeMillis() + FIXTURE_TIMEOUT_MILLIS;
		do {
			try {
				if (marker.isFile() && countOccurrences(readFile(marker), expected) >= minimum) {
					return;
				}
			} catch (IOException ignored) {
				// The fixture can be writing the marker concurrently; retry the read.
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("Platform compatibility fixture marker count did not reach " + minimum
				+ " for: " + expected);
	}

	private static void awaitImeShown() throws IOException {
		long deadline = SystemClock.uptimeMillis() + FIXTURE_TIMEOUT_MILLIS;
		do {
			String state = executeShellCommandForOutput("dumpsys input_method");
			if (state.contains("mInputShown=true") || state.contains("isInputViewShown=true")
					|| state.contains("inputShown=true")) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		fail("IME did not become visible for the host TextField");
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

	private static void finishMicroActivityTasks(Context context) {
		ActivityManager activityManager =
				(ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (activityManager == null) {
			return;
		}
		for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
			ActivityManager.RecentTaskInfo info;
			try {
				info = task.getTaskInfo();
			} catch (RuntimeException ignored) {
				continue;
			}
			if (info == null || (!isMicroActivity(info.baseActivity)
					&& !isMicroActivity(info.topActivity))) {
				continue;
			}
			try {
				task.finishAndRemoveTask();
			} catch (RuntimeException ignored) {
				// The task may disappear while the fixture process is being stopped.
			}
		}
	}

	private static boolean isMicroActivity(ComponentName component) {
		return component != null && MicroActivity.class.getName().equals(component.getClassName());
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

	private static void awaitProcessStopsBestEffort(Context context, String processName) {
		long deadline = SystemClock.uptimeMillis() + BACK_TIMEOUT_MILLIS;
		do {
			if (processPid(context, processName) == 0) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
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

	private static void appendMarker(File marker, String value) throws IOException {
		try (FileOutputStream output = new FileOutputStream(marker, true)) {
			output.write(value.getBytes(StandardCharsets.UTF_8));
			output.flush();
		}
	}

	private static String lastMarkerValue(File marker, String prefix) throws IOException {
		String result = null;
		for (String line : readFile(marker).split("\\R")) {
			if (line.startsWith(prefix)) {
				result = line;
			}
		}
		if (result == null) {
			fail("Platform compatibility fixture did not write value: " + prefix);
		}
		return result;
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
