/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2021 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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

package javax.microedition.shell;

import static android.content.pm.ActivityInfo.*;
import static io.github.h3nb.jlmodplus.util.Constants.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;


import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.skin.SkinLayer;
import javax.microedition.shell.time.EmulationSpeed;
import javax.microedition.shell.time.EmulationTimeController;
import javax.microedition.shell.time.SpeedSnapshot;
import javax.microedition.shell.memory.MemoryEditorRuntime;
import javax.microedition.shell.memory.ui.MemoryEditorDialogFragment;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.BuildConfig;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import io.github.h3nb.jlmodplus.util.Constants;
import io.github.h3nb.jlmodplus.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;
	private MicroActivityHost binding;
	private final ThreadPoolExecutor timingMigrationExecutor = new ThreadPoolExecutor(
			1,
			1,
			0L,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(1),
			Executors.defaultThreadFactory(),
			new ThreadPoolExecutor.AbortPolicy()
	);
	private Future<?> timingMigrationOperation;
	private volatile long timingMigrationGeneration;
	private volatile long screenshotGeneration;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);
		binding = new MicroActivityHost(this, this::onToolbarAction);
		setContentView(binding);
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog("Invalid intent: app path is null");
				return;
			}
			appPath = data.toString();
		} else {
			appName = getTitle().toString();
			appPath = getApplicationInfo().dataDir + "/files/converted/midlet";
			File dir = new File(appPath);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new RuntimeException("Can't access file system");
			}
		}
		microLoader = new MicroLoader(appPath);
		if (!microLoader.init()) {
			Config.openSettings(this, appName, appPath);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		SkinLayer skinLayer = SkinLayer.getInstance();
		if (skinLayer != null) {
			binding.overlay.addLayer(skinLayer);
			if (!statusBarEnabled && !actionBarEnabled) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
					WindowManager.LayoutParams attributes = getWindow().getAttributes();
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
						attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
					} else {
						attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
					}
					getWindow().setAttributes(attributes);
				}
			}
		}
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = microLoader.getOrientation();
		if (vk != null) {
			vk.setView(binding.overlay);
			binding.overlay.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation);
		menuKey = microLoader.getMenuKeyCode();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

		getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				// Intentionally overridden by empty due to support for back-key remapping.
			}
		});
		startOrMigrateTimingDex();
	}

	private void startOrMigrateTimingDex() {
		if (!microLoader.needsTimingMigration()) {
			loadMIDlet();
			return;
		}
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.timing_migration_title),
				getString(R.string.timing_migration_message),
				getString(R.string.timing_migration_rebuild),
				getString(android.R.string.cancel),
				null,
				true,
				this::rebuildTimingDex,
				this::finish,
				null,
				this::finish
		);
	}

	private void rebuildTimingDex() {
		android.app.Dialog progress = ComposeDialogHost.showMessage(
				this,
				getString(R.string.timing_migration_title),
				getString(R.string.timing_migration_progress),
				null,
				null,
				null,
				false,
				null,
				null,
			null
		);
		if (timingMigrationOperation != null) {
			timingMigrationOperation.cancel(true);
		}
		long generation = ++timingMigrationGeneration;
		try {
			timingMigrationOperation = timingMigrationExecutor.submit(() -> {
				try {
					microLoader.migrateTimingDex();
					runOnUiThread(() -> {
						if (generation != timingMigrationGeneration || isFinishing() || isDestroyed()) {
							return;
						}
						progress.dismiss();
						loadMIDlet();
					});
				} catch (Throwable error) {
					runOnUiThread(() -> {
						if (generation != timingMigrationGeneration || isFinishing() || isDestroyed()) {
							return;
						}
						progress.dismiss();
						microLoader.refreshTimingTransformState();
						showTimingMigrationFailure(error);
					});
				}
			});
		} catch (RejectedExecutionException error) {
			progress.dismiss();
			showTimingMigrationFailure(error);
		}
	}

	private void showTimingMigrationFailure(Throwable error) {
		String detail = error.getMessage();
		String message = getString(R.string.timing_migration_failed);
		if (!TextUtils.isEmpty(detail)) {
			message += "\n\n" + detail;
		}
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.error),
				message,
				getString(R.string.retry),
				getString(android.R.string.cancel),
				getString(R.string.timing_migration_continue),
				true,
				this::rebuildTimingDex,
				this::finish,
				this::loadMIDlet,
				this::finish
		);
	}

	@Override
	protected void onDestroy() {
		++timingMigrationGeneration;
		if (timingMigrationOperation != null) {
			timingMigrationOperation.cancel(true);
			timingMigrationExecutor.getQueue().remove(timingMigrationOperation);
			timingMigrationExecutor.purge();
			timingMigrationOperation = null;
		}
		timingMigrationExecutor.shutdownNow();
		++screenshotGeneration;
		if (microLoader != null) {
			microLoader.close();
		}
		if (isFinishing()) {
			MemoryEditorRuntime.endGame();
		}
		super.onDestroy();
	}

	public void lockNightMode() {
		int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (current == Configuration.UI_MODE_NIGHT_YES) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		}
	}

	@Override
	public void onPause() {
		hideSoftInput();
		super.onPause();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && current instanceof Canvas) {
			hideSystemUI();
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		setRequestedOrientation(switch (orientation) {
			case ORIENTATION_DEFAULT -> SCREEN_ORIENTATION_UNSPECIFIED;
			case ORIENTATION_AUTO -> SCREEN_ORIENTATION_FULL_SENSOR;
			case ORIENTATION_PORTRAIT -> SCREEN_ORIENTATION_SENSOR_PORTRAIT;
			case ORIENTATION_LANDSCAPE -> SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
			default -> SCREEN_ORIENTATION_UNSPECIFIED;
		});
	}

	private void loadMIDlet() {
		MemoryEditorRuntime.beginGame();
		Map<String, String> midlets;
		try {
			midlets = microLoader.loadMIDletList();
		} catch (IOException e) {
			showErrorDialog(e.toString());
			return;
		}
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			showErrorDialog("No MIDlets found");
		} else if (size == 1) {
			microLoader.loadMidlet(midletsClassArray[0], appName);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		ComposeDialogHost.showChoice(
				this,
				getString(R.string.select_dialog_title),
				names,
				-1,
				null,
				true,
				n -> {
					String clazz = classes[n];
					ErrorReporter errorReporter = ACRA.getErrorReporter();
					String report = errorReporter.getCustomData(Constants.KEY_CRASH_ATTACHMENT);
					StringBuilder sb = new StringBuilder();
					if (report != null) {
						sb.append(report).append("\n");
					}
					sb.append("Begin app: ").append(names[n]).append(", ").append(clazz);
					errorReporter.putCustomData(Constants.KEY_CRASH_ATTACHMENT, sb.toString());
					microLoader.loadMidlet(clazz, appName);
				},
				() -> MidletThread.notifyDestroyed()
		);
	}

	void showErrorDialog(String message) {
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.error),
				message,
				getString(android.R.string.ok),
				null,
				null,
				true,
				() -> MidletThread.notifyDestroyed(),
				null,
				null,
				() -> MidletThread.notifyDestroyed()
		);
	}

	private float getToolBarHeight() {
		TypedValue typedValue = new TypedValue();
		if (getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typedValue, true)) {
			return typedValue.getDimension(getResources().getDisplayMetrics());
		}
		return 0;
	}

	private void hideSystemUI() {
		int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
		if (!statusBarEnabled) {
			flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
					| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
		}
		getWindow().getDecorView().setSystemUiVisibility(flags);
	}

	private void showSystemUI() {
		getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
	}

	public void setCurrent(Displayable displayable) {
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
	}

	public void showExitConfirmation() {
		Runnable exit = () -> {
			hideSoftInput();
			MidletThread.destroyApp();
		};
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.CONFIRMATION_REQUIRED),
				getString(R.string.FORCE_CLOSE_CONFIRMATION),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				getString(R.string.action_settings),
				true,
				exit,
				null,
				() -> {
					hideSoftInput();
					Config.openSettings(this, appName, appPath);
					MidletThread.destroyApp();
				},
				null
		);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU)
			if (current instanceof Canvas && binding.displayableContainer.dispatchKeyEvent(event)) {
				return true;
			} else if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					event.startTracking();
					return true;
				} else if (event.isLongPress()) {
					return onKeyLongPress(event.getKeyCode(), event);
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				return onKeyUp(event.getKeyCode(), event);
			}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!actionBarEnabled && current instanceof Canvas) {
			showSystemUI();
		}
		binding.toolbar.showMenu();
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
			showExitConfirmation();
			return true;
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			return false;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
				&& (event.getFlags() & (KeyEvent.FLAG_LONG_PRESS | KeyEvent.FLAG_CANCELED)) == 0) {
			openOptionsMenu();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	private void onToolbarAction(int id) {
		if (id == R.id.action_exit_midlet) {
			showExitConfirmation();
		} else if (id == R.id.action_save_log) {
			saveLog();
		} else if (id == R.id.action_lock_orientation) {
			if (binding.toolbar.isOrientationLocked()) {
				// The Compose toolbar owns the checked state. A checked item means
				// the emulator is currently locked and should be restored now.
				VirtualKeyboard vk = ContextHolder.getVk();
				int orientation = vk != null && vk.isPhone() ? ORIENTATION_PORTRAIT : microLoader.getOrientation();
				setOrientation(orientation);
			} else {
				lockOrientation();
			}
		} else if (id == R.id.action_ime_keyboard) {
			inputMethodManager.toggleSoftInputFromWindow(binding.displayableContainer.getWindowToken(),
					InputMethodManager.SHOW_FORCED, 0);
		} else if (id == R.id.action_take_screenshot) {
			takeScreenshot();
		} else if (id == R.id.action_limit_fps) {
			showLimitFpsDialog();
		} else if (id == R.id.action_emulation_speed) {
			showEmulationSpeedDialog();
		} else if (id == R.id.action_memory_editor) {
			showMemoryEditorDialog();
		} else if (ContextHolder.getVk() != null) {
			// Handled only when virtual keyboard is enabled
			handleVkOptions(id);
		}
	}

	private void lockOrientation() {
		setRequestedOrientation(SCREEN_ORIENTATION_LOCKED);
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
			showSaveVkAlert(false);
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	private void takeScreenshot() {
		long generation = ++screenshotGeneration;
		microLoader.takeScreenshot(current, new MicroLoader.ScreenshotCallback() {
			@Override
			public void onSuccess(String path) {
				runOnUiThread(() -> {
					if (generation != screenshotGeneration || isFinishing() || isDestroyed()) {
						return;
					}
					Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
							+ " " + path, Toast.LENGTH_LONG).show();
					MediaScannerConnection.scanFile(MicroActivity.this,
							new String[]{path}, null, null);
				});
			}

			@Override
			public void onError(Throwable error) {
				runOnUiThread(() -> {
					if (generation != screenshotGeneration || isFinishing() || isDestroyed()) {
						return;
					}
					error.printStackTrace();
					Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		boolean[] states = vk.getKeysVisibility();
		boolean[] changed = states.clone();
		ComposeDialogHost.showMultiChoice(
				this,
				getString(R.string.hide_buttons),
				vk.getKeyNames(),
				changed,
				getString(android.R.string.ok),
				null,
				true,
				selected -> {
					if (!Arrays.equals(states, selected)) {
						vk.setKeysVisibility(selected);
						showSaveVkAlert(true);
					}
				}
		);
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			ComposeDialogHost.showCheckboxMessage(
					this,
					getString(R.string.CONFIRMATION_REQUIRED),
					getString(R.string.pref_vk_save_alert),
					getString(R.string.opt_save_screen_params),
					keepScreenPreferred,
					getString(android.R.string.yes),
					getString(android.R.string.no),
					true,
					checked -> {
						if (checked) {
							vk.saveScreenParams();
						}
						vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
					}
			);
		} else {
			ComposeDialogHost.showMessage(
					this,
					getString(R.string.CONFIRMATION_REQUIRED),
					getString(R.string.pref_vk_save_alert),
					getString(android.R.string.yes),
					getString(android.R.string.no),
					null,
					true,
					() -> ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM),
					null,
					null
			);
		}
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		ComposeDialogHost.showChoiceActions(
				this,
				getString(R.string.layout_switch),
				getResources().getStringArray(R.array.PREF_VK_TYPE_ENTRIES),
				vk.getLayout(),
				getString(android.R.string.ok),
				null,
				getString(android.R.string.cancel),
				true,
				false,
				index -> {
					vk.setLayout(index);
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				},
				null,
				null
		);
	}

	private void showLimitFpsDialog() {
		ComposeDialogHost.showTextInputActions(
				this,
				getString(R.string.PREF_LIMIT_FPS),
				getString(R.string.unlimited),
				"",
				true,
				getString(android.R.string.ok),
				getString(R.string.reset),
				getString(android.R.string.cancel),
				true,
				text -> {
					int fps = 0;
					try {
						fps = TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text.trim());
					} catch (NumberFormatException ignored) {
					}
					Canvas.setLimitFps(fps);
					return true;
				},
				() -> Canvas.setLimitFps(-1)
		);
	}

	private void showEmulationSpeedDialog() {
		EmulationTimeController controller = MidletThread.getEmulationTimeController();
		if (controller == null) {
			return;
		}
		boolean extremeSpeedsEnabled = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext())
				.getBoolean(PREF_EMULATION_EXTREME_SPEEDS, false);
		if (!extremeSpeedsEnabled && controller.snapshot().speed().isExperimental()) {
			controller.setSpeed(EmulationSpeed.X16);
		}
		List<EmulationSpeed> availableSpeeds = new ArrayList<>();
		for (EmulationSpeed speed : EmulationSpeed.values()) {
			if (!speed.isExperimental() || extremeSpeedsEnabled) {
				availableSpeeds.add(speed);
			}
		}
		EmulationSpeed[] speeds = availableSpeeds.toArray(new EmulationSpeed[0]);
		String[] labels = new String[speeds.length];
		int checked = 0;
		EmulationSpeed selected = controller.snapshot().speed();
		for (int i = 0; i < speeds.length; i++) {
			labels[i] = speeds[i].toString();
			if (speeds[i] == selected) {
				checked = i;
			}
		}
		ComposeDialogHost.showChoice(
				this,
				getString(R.string.emulation_speed_dialog_title),
				labels,
				checked,
				getString(android.R.string.cancel),
				true,
				which -> {
					controller.setSpeed(speeds[which]);
					refreshToolbarState(current);
				}
		);
	}

	private void showMemoryEditorDialog() {
		MemoryEditorDialogFragment.show(getSupportFragmentManager());
	}

	@Override
	public boolean onContextItemSelected(@NonNull MenuItem item) {
		if (current instanceof Form) {
			((Form) current).contextMenuItemSelected(item);
		}

		return super.onContextItemSelected(item);
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	@Override
	public void onRequestPermissionsResult(
			int requestCode,
			@NonNull String[] permissions,
			@NonNull int[] grantResults
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		ContextHolder.notifyOnRequestPermissionsResult(requestCode);
	}

	public String getAppName() {
		return appName;
	}

	public void toast(@StringRes int message) {
		runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
	}

	private void refreshToolbarState(Displayable displayable) {
		if (displayable == null) {
			binding.toolbar.setToolbarState("", false, false, false, false, false, false, "", false);
			return;
		}
		boolean canvas = displayable instanceof Canvas;
		boolean toolbarVisible = !canvas || actionBarEnabled;
		boolean timingAvailable = canvas && microLoader != null && microLoader.hasTimingTransform();
		boolean memoryEditorAvailable = canvas && microLoader != null
				&& microLoader.hasMemoryEditorTransform();
		VirtualKeyboard vk = ContextHolder.getVk();
		String speedLabel = "";
		if (timingAvailable) {
			EmulationTimeController controller = MidletThread.getEmulationTimeController();
			if (controller != null) {
				SpeedSnapshot snapshot = controller.snapshot();
				speedLabel = getString(R.string.emulation_speed) + ": " + snapshot.speed();
			}
		}
		String title = displayable.getTitle();
		binding.toolbar.setToolbarState(
				title == null ? appName : title,
				toolbarVisible,
				canvas,
				inputMethodManager != null,
				vk != null,
				timingAvailable,
				memoryEditorAvailable,
				speedLabel,
				vk != null && vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF
		);
	}

	private class SetCurrentEvent extends SimpleEvent {
		private final Displayable current;
		private final Displayable next;

		private SetCurrentEvent(Displayable current, Displayable next) {
			this.current = current;
			this.next = next;
		}

		@Override
		public void process() {
			binding.toolbar.dismissMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			binding.displayableContainer.removeAllViews();
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
			int toolbarHeight = 0;
			if (next instanceof Canvas) {
				hideSystemUI();
				if (actionBarEnabled) {
					toolbarHeight = (int) (getToolBarHeight() / 1.5);
				}
			} else {
				showSystemUI();
				toolbarHeight = (int) getToolBarHeight();
			}
			layoutParams.height = toolbarHeight;
			refreshToolbarState(next);
			binding.overlay.setLocation(0, toolbarHeight);
			binding.toolbar.setLayoutParams(layoutParams);
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
		}
	}
}
