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
	private static final String FIXTURE_NAME = "Locator Lab R&D";
	private static final String FIXTURE_CLASS = "research.locator.LocatorLabMIDlet";

	private Context context;
	private SharedPreferences preferences;
	private boolean hadEmulatorDirectory;
	private String previousEmulatorDirectory;
	private File root;
	private File appDirectory;
	private long fixtureAppId;
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
		File sourceJar = new File(appDirectory, "res.jar");
		try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
				.getAssets().open("saved-locator/Locator-Lab.jar");
				OutputStream output = new FileOutputStream(sourceJar)) {
			byte[] bytes = new byte[8192];
			for (int n; (n = input.read(bytes)) != -1; ) output.write(bytes, 0, n);
		}
		// Use the exact transform pipeline used by AppInstaller; do not fake the marker
		// or substitute debug-APK classes for converted guest bytecode.
		com.android.dx.command.dexer.ConversionResult converted =
				com.android.dx.command.dexer.Main.convert(new String[]{
						"--no-optimize", "--output="
						+ new File(appDirectory, "converted.zip").getAbsolutePath(),
						sourceJar.getAbsolutePath()
				});
		assertTrue("Research MIDlet conversion failed", converted.isSuccess());
		fixtureAppId = ResearchLibraryFixture.register(context, root);
		assertTrue("Debug MIDlet must have an installed Library identity", fixtureAppId > 0L);
		writeManifest(new File(appDirectory, "converted.dex.conf"));

		ProfileModel profile = ProfileModel.fresh();
		profile.graphicsMode = 2;
		profile.showKeyboard = false;
		profile.touchInput = true;
		profile.soundBank = "";
		assertTrue("Unable to save memory IPC fixture profile",
				ProfilesManager.saveConfig(configDirectory, profile));
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
	public void missingRuntimeCompletionCarriesStructuredReason() throws Exception {
		bindEngine();
		OperationCallback callback = new OperationCallback();
		service.registerCallback(callback);
		try {
			OperationResult result = callback.await(service.startKnownSearch(0L,
						MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
						"1247", null));
			assertEquals(MemoryEngineContract.RESULT_NO_SESSION, result.resultCode);
			assertEquals(MemoryEngineContract.REASON_NO_RUNTIME,
					result.details.getInt(MemoryEngineContract.KEY_OPERATION_REASON));
			assertTrue("Legacy presentation prose must not cross the callback boundary",
					!result.details.containsKey("message"));
		} finally {
			service.unregisterCallback(callback);
		}
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
			OperationResult noHistory = activeCallback.await(service.undoSearch(runtimeToken));
			assertEquals(MemoryEngineContract.RESULT_NO_SESSION, noHistory.resultCode);
			assertEquals(MemoryEngineContract.REASON_NO_SEARCH_HISTORY,
					noHistory.details.getInt(MemoryEngineContract.KEY_OPERATION_REASON));

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

	/**
	 * R&D only. This runs the real debug guest fixture in :midlet, transports a
	 * field descriptor over Binder, atomically saves it in the test process,
	 * kills/relaunches the target, then binds Watch without a second Search.
	 * The fixture archive hash is NOT yet the production original-JAR hash.
	 */
	@Test
	public void researchDescriptorSurvivesProcessRestartAndBindsWatchWithoutNewSearch() throws Exception {
		final String expectedValue = "89";
		android.util.AtomicFile file = new android.util.AtomicFile(
				new File(context.getFilesDir(), "memory-rnd-field-locator.json"));
		file.delete();
		try {
			launchFixture();
			awaitProcess(FIXTURE_NAME);
			long tokenFirst;
			try (ResearchTargetConnection first = ResearchTargetConnection.connect(context)) {
				IMemoryTargetBridge target = first.target;
				tokenFirst = researchAwaitToken(target);
				Bundle search = target.managedStartExact(tokenFirst, MemoryEngineContract.TYPE_INT,
						MemoryEngineContract.PREDICATE_EQUAL, expectedValue, null, 0L);
				assertEquals(MemoryEngineContract.RESULT_OK,
						search.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT));
				long revision = search.getLong(MemoryEngineContract.KEY_MANAGED_REVISION);
				Bundle page = target.managedResultPage(tokenFirst, revision, 0, 100);
				long[] ids = page.getLongArray(MemoryEngineContract.KEY_RESULT_IDS);
				String[] fields = page.getStringArray(MemoryEngineContract.KEY_RESULT_SOURCE_FIELDS);
				assertNotNull(ids);
				assertNotNull(fields);
				Bundle descriptor = null;
				for (int i = 0; i < ids.length; i++) {
					if ("health".equals(fields[i])) {
						descriptor = target.researchCaptureFieldLocator(tokenFirst, revision, ids[i]);
						break;
					}
				}
				assertNotNull("Fixture field must have rooted, verified provenance", descriptor);
				assertEquals(MemoryEngineContract.RESULT_OK,
						descriptor.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT));
				assertEquals(1, descriptor.getInt("researchSchema"));
				assertEquals(1, descriptor.getStringArray("researchNames").length);
				assertEquals("health",
						descriptor.getStringArray("researchNames")[0]);

				org.json.JSONObject record = new org.json.JSONObject();
				record.put("fileSchema", 1);
				record.put("sourceJarSha256",
						researchSha256(new File(appDirectory, "res.jar")));
				record.put("rootKind", descriptor.getInt("researchRootKind"));
				record.put("rootClass", descriptor.getString("researchRootClass"));
				record.put("classes", researchJsonArray(descriptor.getStringArray("researchClasses")));
				record.put("names", researchJsonArray(descriptor.getStringArray("researchNames")));
				record.put("types", researchJsonArray(descriptor.getStringArray("researchTypes")));
				java.io.FileOutputStream output = file.startWrite();
				try {
					output.write(record.toString().getBytes(StandardCharsets.UTF_8));
					file.finishWrite(output);
				} catch (Exception | Error failure) {
					file.failWrite(output);
					throw failure;
				}
			}

			stopMidletAndTasks();
			launchFixture();
			awaitProcess(FIXTURE_NAME);
			try (ResearchTargetConnection second = ResearchTargetConnection.connect(context)) {
				IMemoryTargetBridge target = second.target;
				long tokenSecond = researchAwaitToken(target);
				assertTrue("Runtime generation must change across process restart",
						tokenFirst != tokenSecond);
				Bundle before = target.getManagedSessionInfo(tokenSecond);
				assertEquals(0L, before.getLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT));
				assertEquals(0, before.getInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT));

				org.json.JSONObject saved = new org.json.JSONObject(
						new String(file.readFully(), StandardCharsets.UTF_8));
				assertEquals(1, saved.getInt("fileSchema"));
				assertEquals("Fixture identity must survive restart",
						saved.getString("sourceJarSha256"),
						researchSha256(new File(appDirectory, "res.jar")));
				Bundle descriptor = new Bundle();
				descriptor.putInt("researchSchema", 1);
				descriptor.putInt("researchRootKind", saved.getInt("rootKind"));
				descriptor.putString("researchRootClass", saved.getString("rootClass"));
				descriptor.putStringArray("researchClasses", researchStrings(saved.getJSONArray("classes")));
				descriptor.putStringArray("researchNames", researchStrings(saved.getJSONArray("names")));
				descriptor.putStringArray("researchTypes", researchStrings(saved.getJSONArray("types")));

				Bundle forged = new Bundle(descriptor);
				forged.putStringArray("researchTypes", new String[]{"long"});
				Bundle rejected = target.researchBindFieldLocator(tokenSecond, forged, "Invalid", 0L);
				assertEquals("Changed type must fail closed, without Watch membership",
						MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
						rejected.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT));
				assertEquals(0, target.managedWatchPage(tokenSecond)
						.getLongArray(MemoryEngineContract.KEY_WATCH_IDS).length);

				Bundle bound = target.researchBindFieldLocator(tokenSecond, descriptor,
						"Saved probe", 0L);
				assertEquals("Bind failed: " + bound.getString(
						MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL, "<no diagnostic>")
						+ "; epoch=" + target.getManagedCapabilities(tokenSecond).getLong(
						MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH),
						MemoryEngineContract.RESULT_OK,
						bound.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT));
				assertEquals(0L, target.getManagedSessionInfo(tokenSecond)
						.getLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT));
				Bundle watch = target.managedWatchPage(tokenSecond);
				long[] watchIds = watch.getLongArray(MemoryEngineContract.KEY_WATCH_IDS);
				String[] watchValues = watch.getStringArray(MemoryEngineContract.KEY_WATCH_VALUES);
				assertNotNull(watchIds);
				assertEquals(1, watchIds.length);
				assertEquals(expectedValue, watchValues[0]);
				Bundle write = target.managedEditTyped(tokenSecond, 0L, watchIds,
						MemoryEngineContract.TYPE_INT, "90", true, 0L);
				assertEquals(MemoryEngineContract.RESULT_OK,
						write.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT));
				assertEquals("90", target.managedWatchPage(tokenSecond)
						.getStringArray(MemoryEngineContract.KEY_WATCH_VALUES)[0]);
			}
		} finally {
			file.delete();
		}
	}

	private static long researchAwaitToken(IMemoryTargetBridge target) throws Exception {
		long deadline = SystemClock.uptimeMillis() + 20_000L;
		long lastToken = 0L;
		boolean lastSupported = false;
		String lastDiagnostic = "";
		while (SystemClock.uptimeMillis() < deadline) {
			lastToken = target.getRuntimeToken();
			if (lastToken != 0L) {
				Bundle capabilities = target.getManagedCapabilities(lastToken);
				lastSupported = capabilities.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED);
				lastDiagnostic = capabilities.getString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL, "");
				if (lastSupported) return lastToken;
			}
			SystemClock.sleep(100L);
		}
		throw new AssertionError("Managed target not ready: token=" + lastToken
				+ " supported=" + lastSupported + " diagnostic=" + lastDiagnostic);
	}

	private static org.json.JSONArray researchJsonArray(String[] values) {
		org.json.JSONArray result = new org.json.JSONArray();
		for (String value : values) result.put(value);
		return result;
	}

	private static String[] researchStrings(org.json.JSONArray values) throws org.json.JSONException {
		String[] result = new String[values.length()];
		for (int i = 0; i < result.length; i++) result[i] = values.getString(i);
		return result;
	}

	private static String researchSha256(File file) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		try (InputStream input = new FileInputStream(file)) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) >= 0; ) {
				if (count > 0) digest.update(buffer, 0, count);
			}
		}
		StringBuilder output = new StringBuilder(64);
		for (byte value : digest.digest()) {
			int unsigned = value & 255;
			output.append(Character.forDigit(unsigned >>> 4, 16));
			output.append(Character.forDigit(unsigned & 15, 16));
		}
		return output.toString();
	}

	private static final class ResearchTargetConnection implements AutoCloseable {
		final Context context;
		final ServiceConnection connection;
		final IMemoryTargetBridge target;

		private ResearchTargetConnection(Context context, ServiceConnection connection,
		                                IMemoryTargetBridge target) {
			this.context = context;
			this.connection = connection;
			this.target = target;
		}

		static ResearchTargetConnection connect(Context context) throws Exception {
			java.util.concurrent.atomic.AtomicReference<IBinder> binder =
					new java.util.concurrent.atomic.AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			ServiceConnection connection = new ServiceConnection() {
				@Override public void onServiceConnected(ComponentName name, IBinder service) {
					binder.set(service);
					latch.countDown();
				}
				@Override public void onServiceDisconnected(ComponentName name) {}
			};
			assertTrue(context.bindService(new Intent(context, MemoryTargetBridgeService.class),
					connection, Context.BIND_AUTO_CREATE));
			try {
				assertTrue(latch.await(SERVICE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
				assertNotNull(binder.get());
				return new ResearchTargetConnection(context, connection,
						IMemoryTargetBridge.Stub.asInterface(binder.get()));
			} catch (Exception | Error failure) {
				context.unbindService(connection);
				throw failure;
			}
		}

		@Override public void close() {
			context.unbindService(connection);
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
				.putExtra(Constants.KEY_LIBRARY_APP_ID, fixtureAppId)
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
				+ "MIDlet-1: " + FIXTURE_NAME + ",," + FIXTURE_CLASS + "\n";
		io.github.h3nb.jlmodplus.jar.Descriptor descriptor =
				new io.github.h3nb.jlmodplus.jar.Descriptor(text, false);
		javax.microedition.shell.transform.MidletTransformMetadata.mark(descriptor.getAttrs());
		descriptor.writeTo(manifest);
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
		private volatile Bundle details;

		@Override
		public void onOperationProgress(long operationId, long scannedBytes, long totalBytes,
				boolean searchOperation) {
		}

		@Override
		public void onOperationFinished(long operationId, int resultCode, long resultCount,
				Bundle details, boolean passiveRefresh, boolean searchOperation) {
			this.resultCode = resultCode;
			this.resultCount = resultCount;
			this.details = details;
			finished.countDown();
		}

		OperationResult await(long expectedOperationId) throws InterruptedException {
			assertTrue("Memory operation did not finish: " + expectedOperationId,
					finished.await(OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
			return new OperationResult(resultCode, resultCount, details);
		}
	}

	private static final class OperationResult {
		final int resultCode;
		final long resultCount;
		final Bundle details;

		OperationResult(int resultCode, long resultCount, Bundle details) {
			this.resultCode = resultCode;
			this.resultCount = resultCount;
			this.details = details;
		}
	}
}
