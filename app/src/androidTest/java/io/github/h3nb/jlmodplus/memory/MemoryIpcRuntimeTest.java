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

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.shell.MicroActivity;

import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.util.Constants;

/**
 * Exercises the canonical Memory Editor Binder contract against a live target
 * process. The fixture is a debug-only converted MIDlet, so the test does not
 * depend on a user's external library or mutate real MIDlet memory.
 */
@RunWith(AndroidJUnit4.class)
public final class MemoryIpcRuntimeTest {
	private static final long SERVICE_TIMEOUT_MILLIS = 10_000L;
	private static final long OPERATION_TIMEOUT_MILLIS = 30_000L;
	private static final String FIXTURE_NAME = "Memory IPC Fixture";
	private static final String FIXTURE_CLASS = "jlmod.platformfixture.PlatformCompatMidlet";

	private Context context;
	private SharedPreferences preferences;
	private boolean hadEmulatorDirectory;
	private String previousEmulatorDirectory;
	private File root;
	private File appDirectory;
	private ServiceConnection connection;
	private IMemoryEngineService service;

	@Before
	public void setUp() throws Exception {
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		preferences = PreferenceManager.getDefaultSharedPreferences(context);
		hadEmulatorDirectory = preferences.contains(Constants.PREF_EMULATOR_DIR);
		previousEmulatorDirectory = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
		stopMidletAndTasks();

		root = new File(context.getFilesDir(), "memory-ipc-fixture");
		deleteRecursively(root);
		appDirectory = new File(new File(root, "converted"), "fixture");
		File configDirectory = new File(new File(root, "configs"), "fixture");
		assertTrue(appDirectory.mkdirs() || appDirectory.isDirectory());
		assertTrue(configDirectory.mkdirs() || configDirectory.isDirectory());
		copyFile(new File(context.getApplicationInfo().sourceDir),
				new File(appDirectory, "converted.zip"));
		writeManifest(new File(appDirectory, "converted.dex.conf"));

		ProfileModel profile = new ProfileModel(configDirectory);
		profile.graphicsMode = 2;
		profile.showKeyboard = false;
		profile.touchInput = true;
		profile.soundBank = "";
		assertTrue("Unable to save memory IPC fixture profile",
				ProfilesManager.saveConfig(profile));
		assertTrue("Unable to select memory IPC fixture",
				preferences.edit().putString(Constants.PREF_EMULATOR_DIR,
						root.getAbsolutePath()).commit());
	}

	@After
	public void tearDown() {
		unbindEngine();
		stopMidletAndTasks();
		if (hadEmulatorDirectory) {
			preferences.edit().putString(Constants.PREF_EMULATOR_DIR,
					previousEmulatorDirectory).commit();
		} else {
			preferences.edit().remove(Constants.PREF_EMULATOR_DIR).commit();
		}
		deleteRecursively(root);
	}

	@Test
	public void canonicalBinderHandshakeSearchRefreshAndReconnect() throws Exception {
		launchFixture();
		awaitProcess(FIXTURE_NAME);

		long runtimeToken = 0L;
		OperationCallback activeCallback = new OperationCallback();
		bindEngine();
		service.registerCallback(activeCallback);
		try {
			Bundle capabilities = awaitCapabilities();
			assertNotNull(capabilities);
			runtimeToken = capabilities.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN);
			assertTrue("Live MIDlet runtime token must cross the Binder boundary",
					runtimeToken != 0L);
			assertTrue("Managed target must be available through the canonical service",
				capabilities.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED));

			long operationId = service.startKnownSearch(runtimeToken,
					MemoryEngineContract.TYPE_INT,
					MemoryEngineContract.PREDICATE_EQUAL,
					"0", null);
			OperationResult search = activeCallback.await(operationId);
			assertEquals("Read-only search must complete successfully",
					MemoryEngineContract.RESULT_OK, search.resultCode);

			Bundle session = service.getSearchSessionInfo(runtimeToken);
			assertEquals(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
					session.getInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE));
			Bundle page = service.getResultPage(runtimeToken, 0,
					MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
			long[] candidateIds = page.getLongArray(MemoryEngineContract.KEY_RESULT_IDS);
			assertNotNull("A controlled fixture search must return logical candidates", candidateIds);
			assertTrue("A controlled fixture search must return at least one candidate",
				candidateIds.length > 0);

			service.unregisterCallback(activeCallback);
			activeCallback = new OperationCallback();
			service.registerCallback(activeCallback);
			long refreshId = service.refreshCandidates(runtimeToken,
					new long[]{candidateIds[0]}, false);
			OperationResult refresh = activeCallback.await(refreshId);
			assertEquals("Read-only candidate refresh must cross Binder successfully",
					MemoryEngineContract.RESULT_OK, refresh.resultCode);

			unbindEngine();
			bindEngine();
			Bundle reconnected = service.getCapabilities();
			assertEquals("Reconnect must retain the live runtime token", runtimeToken,
				reconnected.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN));
			assertTrue("Reconnect must restore managed capabilities",
					reconnected.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED));
		} finally {
			if (service != null) {
				try {
					if (runtimeToken != 0L) service.clearSearch(runtimeToken);
					service.unregisterCallback(activeCallback);
				} catch (RemoteException ignored) {
					// The engine may already have gone away during a failing test.
				}
			}
		}
	}

	private Bundle awaitCapabilities() throws RemoteException {
		long deadline = SystemClock.uptimeMillis() + SERVICE_TIMEOUT_MILLIS;
		Bundle capabilities;
		do {
			capabilities = service.getCapabilities();
			if (capabilities.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN) != 0L
					&& capabilities.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED)) {
				return capabilities;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		return capabilities;
	}

	private void bindEngine() throws Exception {
		CountDownLatch connected = new CountDownLatch(1);
		connection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder binder) {
				service = IMemoryEngineService.Stub.asInterface(binder);
				connected.countDown();
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				service = null;
			}

			@Override
			public void onBindingDied(ComponentName name) {
				service = null;
			}

			@Override
			public void onNullBinding(ComponentName name) {
				service = null;
				connected.countDown();
			}
		};
		boolean bound = context.bindService(new Intent(context, MemoryEngineService.class),
				connection, Context.BIND_AUTO_CREATE);
		assertTrue("Canonical MemoryEngineService must be bindable", bound);
		assertTrue("MemoryEngineService did not connect",
				connected.await(SERVICE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
		assertNotNull("MemoryEngineService returned no Binder", service);
	}

	private void unbindEngine() {
		if (service != null) {
			service = null;
		}
		if (connection != null) {
			try {
				context.unbindService(connection);
			} catch (IllegalArgumentException ignored) {
				// The remote process may already have died.
			}
			connection = null;
		}
	}

	private void launchFixture() {
		Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appDirectory.getAbsolutePath()),
				context, MicroActivity.class)
				.putExtra(Constants.KEY_MIDLET_NAME, FIXTURE_NAME)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		context.startActivity(intent);
	}

	private void awaitProcess(String name) {
		String processName = context.getPackageName() + ":midlet";
		long deadline = SystemClock.uptimeMillis() + SERVICE_TIMEOUT_MILLIS;
		do {
			if (processPid(processName) != 0) {
				return;
			}
			SystemClock.sleep(100L);
		} while (SystemClock.uptimeMillis() < deadline);
		throw new AssertionError("MIDlet process did not start for " + name);
	}

	private void stopMidletAndTasks() {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (manager != null) {
			for (ActivityManager.AppTask task : manager.getAppTasks()) {
				ActivityManager.RecentTaskInfo info = task.getTaskInfo();
				if (info == null || !isMicroActivity(info.baseActivity)
						&& !isMicroActivity(info.topActivity)) {
					continue;
				}
				try {
					task.finishAndRemoveTask();
				} catch (RuntimeException ignored) {
					// The task can disappear while the process is stopping.
				}
			}
		}
		int pid = processPid(context.getPackageName() + ":midlet");
		if (pid != 0) {
			android.os.Process.killProcess(pid);
		}
		long deadline = SystemClock.uptimeMillis() + SERVICE_TIMEOUT_MILLIS;
		while (processPid(context.getPackageName() + ":midlet") != 0
				&& SystemClock.uptimeMillis() < deadline) {
			SystemClock.sleep(100L);
		}
	}

	private int processPid(String processName) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (manager == null) return 0;
		List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
		if (processes == null) return 0;
		for (ActivityManager.RunningAppProcessInfo process : processes) {
			if (processName.equals(process.processName)) return process.pid;
		}
		return 0;
	}

	private static boolean isMicroActivity(ComponentName component) {
		return component != null && MicroActivity.class.getName().equals(component.getClassName());
	}

	private void writeManifest(File manifest) throws IOException {
		String text = "Manifest-Version: 1.0\n"
				+ "MIDlet-Name: " + FIXTURE_NAME + "\n"
				+ "MIDlet-Vendor: JL-Mod Plus\n"
				+ "MIDlet-Version: 1.0\n"
				+ "JLMod-Transform-Version: 2\n"
				+ "MIDlet-1: " + FIXTURE_NAME + ",," + FIXTURE_CLASS + "\n";
		try (FileOutputStream output = new FileOutputStream(manifest)) {
			output.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void copyFile(File source, File destination) throws IOException {
		try (InputStream input = new FileInputStream(source);
				OutputStream output = new FileOutputStream(destination)) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) != -1; ) {
				output.write(buffer, 0, read);
			}
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		if (!file.delete() && file.exists()) {
			throw new AssertionError("Unable to remove fixture path: " + file);
		}
	}

	private static final class OperationCallback extends IMemoryEngineCallback.Stub {
		private final CountDownLatch finished = new CountDownLatch(1);
		private volatile int resultCode = Integer.MIN_VALUE;
		private volatile long resultCount;

		@Override
		public void onOperationProgress(long operationId, long scannedBytes, long totalBytes,
				boolean searchOperation) {
		}

		@Override
		public void onOperationFinished(long operationId, int resultCode, long resultCount,
				String message, boolean passiveRefresh, boolean searchOperation) {
			this.resultCode = resultCode;
			this.resultCount = resultCount;
			finished.countDown();
		}

		OperationResult await(long expectedOperationId) throws InterruptedException {
			assertTrue("Memory operation did not finish: " + expectedOperationId,
					finished.await(OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
			return new OperationResult(resultCode, resultCount);
		}
	}

	private static final class OperationResult {
		final int resultCode;
		final long resultCount;

		OperationResult(int resultCode, long resultCount) {
			this.resultCode = resultCode;
			this.resultCount = resultCount;
		}
	}
}
