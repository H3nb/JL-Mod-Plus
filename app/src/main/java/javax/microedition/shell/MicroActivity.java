/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2021 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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
import static ru.playsoftware.j2meloader.util.Constants.*;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.skin.SkinLayer;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.MidletSessionStore;
import ru.playsoftware.j2meloader.runtime.MidletKeepAliveService;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.LogUtils;
import ru.playsoftware.j2meloader.ui.TransientNoticeComposeController;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private boolean displayCutoutEnabled;
	private boolean orientationLocked;
	private MicroLoader microLoader;
	private String appName;
	private String[] pendingMidletClasses;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;
	private RuntimeHostView binding;
	private RuntimeMenuComposeController runtimeMenuController;
	private TransientNoticeComposeController runtimeNoticeController;
	private WindowInsetsCompat lastWindowInsets;
	private boolean skinLayerAvailable;
	private int virtualDisplayPaddingLeft;
	private int virtualDisplayPaddingTop;
	private int virtualDisplayPaddingRight;
	private int virtualDisplayPaddingBottom;
	private View overlayAnchor;
	private final View.OnLayoutChangeListener overlayAnchorLayoutListener =
			(view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
					updateOverlayLocation();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		ContextHolder.setCurrentActivity(this);
		binding = new RuntimeHostView(this);
		setContentView(binding.getRoot());
		runtimeNoticeController = new TransientNoticeComposeController(binding.notices);
		virtualDisplayPaddingLeft = binding.virtualDisplay.getPaddingLeft();
		virtualDisplayPaddingTop = binding.virtualDisplay.getPaddingTop();
		virtualDisplayPaddingRight = binding.virtualDisplay.getPaddingRight();
		virtualDisplayPaddingBottom = binding.virtualDisplay.getPaddingBottom();
		ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
			lastWindowInsets = insets;
			applyGuestInsets(current);
			return insets;
		});
		binding.displayableContainer.addOnLayoutChangeListener((view, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> updateOverlayLocation());
		binding.toolbar.addOnLayoutChangeListener((view, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> updateOverlayLocation());
		binding.overlay.addOnLayoutChangeListener((view, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> updateOverlayLocation());
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		// Keep legacy preference files safe: status bar and cutout are mutually exclusive even if
		// an older version persisted both switches as enabled.
		displayCutoutEnabled = sp.getBoolean(PREF_USE_DISPLAY_CUTOUT, true) && !statusBarEnabled;
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		// Install the Compose runtime host before intent validation so early error dialogs use the
		// same Material 3 surface as dialogs shown after a MIDlet has been loaded.
		initializeRuntimeMenu();
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog(getString(R.string.runtime_invalid_intent));
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
		updateRecentTaskDescription();
		MidletSessionStore.markPending(getApplicationContext(), appPath, appName);
		microLoader = new MicroLoader(appPath);
		if (!microLoader.init()) {
			MidletSessionStore.clear(getApplicationContext());
			MidletKeepAliveService.stop(this);
			Config.openSettings(this, appName, appPath);
			finish();
			return;
		}
		MidletKeepAliveService.start(this);
		microLoader.applyConfiguration();
		SkinLayer skinLayer = SkinLayer.getInstance();
		if (skinLayer != null) {
			skinLayerAvailable = true;
			binding.overlay.addLayer(skinLayer);
		}
		// SkinLayer is optional. Window cutout eligibility is a Canvas/window policy and
		// must be configured even when no decorative skin is active.
		configureDisplayCutoutWindow();
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
		ViewCompat.requestApplyInsets(binding.getRoot());
		binding.getRoot().post(this::updateOverlayLocation);

		getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				// Android system Back is distinct from physical/remapped key events. Keep the
				// established short-Back action without synthesizing a KEYCODE_BACK event.
				if (isRuntimeMenuVisible()) {
					closeOptionsMenu();
				} else {
					openOptionsMenu();
				}
			}
		});
		loadMIDlet();
	}

	private void initializeRuntimeMenu() {
		runtimeMenuController = new RuntimeMenuComposeController(binding.toolbar,
				new RuntimeMenuActions() {
					@Override
					public void onExit() {
						showExitConfirmation();
					}

					@Override
					public void onSaveLog() {
						saveLog();
					}

					@Override
					public void onToggleOrientationLock() {
						toggleOrientationLock();
					}

					@Override
					public void onOpenImeKeyboard() {
						showImeKeyboardAfterMenuDismissal();
					}

					@Override
					public void onTakeScreenshot() {
						takeScreenshot();
					}

					@Override
					public void onLimitFps() {
						// The Compose controller owns the Material 3 input dialog.
					}

					@Override
					public void onSetFpsLimit(int value) {
						Canvas.setLimitFps(value);
					}

					@Override
					public void onResetFpsLimit() {
						Canvas.setLimitFps(-1);
					}

					@Override
					public void onEmulationSpeed() {
						// The Compose controller owns the speed picker dialog.
					}

					@Override
					public void onSetEmulationSpeed(int value) {
						if (microLoader == null || !microLoader.setRuntimeEmulationSpeed(value)) {
							toast(R.string.error);
						} else {
							updateRuntimeMenuState(current);
						}
					}

					@Override
					public void onResetEmulationSpeed() {
						onSetEmulationSpeed(microLoader == null
								? EmulationSpeed.NORMAL_PERCENT
								: microLoader.getConfiguredEmulationSpeedPercent());
					}

					@Override
					public void onEditVirtualKeyboardLayout() {
						setVirtualKeyboardEditMode(VirtualKeyboard.LAYOUT_KEYS,
								R.string.layout_edit_mode);
					}

					@Override
					public void onResizeVirtualKeyboardLayout() {
						setVirtualKeyboardEditMode(VirtualKeyboard.LAYOUT_SCALES,
								R.string.layout_scale_mode);
					}

					@Override
					public void onFinishVirtualKeyboardLayout() {
						finishVirtualKeyboardEdit();
					}

					@Override
					public void onSwitchVirtualKeyboardLayout() {
						if (ContextHolder.getVk() != null) {
							showSetLayoutDialog();
						}
					}

					@Override
					public void onHideVirtualKeyboardButtons() {
						if (ContextHolder.getVk() != null) {
							showHideButtonDialog();
						}
					}
				},
				new RuntimeHostDialogActions() {
					@Override
					public void onMidletSelected(int index) {
						String[] classes = pendingMidletClasses;
						pendingMidletClasses = null;
						if (classes != null && index >= 0 && index < classes.length && microLoader != null) {
							microLoader.loadMidlet(classes[index], appName);
						}
					}

					@Override
					public void onMidletCancelled() {
						pendingMidletClasses = null;
						MidletThread.notifyDestroyed();
					}

					@Override
					public void onErrorAcknowledged() {
						MidletThread.notifyDestroyed();
					}

					@Override
					public void onExitConfirmed(boolean openSettings) {
						hideSoftInput();
						if (openSettings) {
							Config.openSettings(MicroActivity.this, appName, appPath);
						}
						MidletThread.destroyApp();
					}

					@Override
					public void onHideButtonsConfirmed(boolean[] states) {
						applyHiddenButtons(states);
					}

					@Override
					public void onSaveVirtualKeyboard(boolean saveScreenParams) {
						applyVirtualKeyboardSave(saveScreenParams);
					}

					@Override
					public void onLayoutSelected(int index) {
						applyLayoutSelection(index);
					}
				});
		setRuntimeToolbarHeight((int) getToolBarHeight());
		updateRuntimeMenuState(current);
	}

	private void updateRuntimeMenuState(@Nullable Displayable displayable) {
		if (runtimeMenuController == null) {
			return;
		}
		GuestWindowPolicy.Chrome chrome = getRuntimeChrome(displayable);
		VirtualKeyboard vk = ContextHolder.getVk();
		TimingSession timingSession = microLoader == null ? null : microLoader.getTimingSession();
		boolean emulationSpeedAvailable = microLoader != null
				&& microLoader.isTimingTransformCompatible()
				&& timingSession != null
				&& !timingSession.isClosed();
		int emulationSpeedPercent = emulationSpeedAvailable
				? timingSession.speedPercentOr(EmulationSpeed.NORMAL_PERCENT)
				: EmulationSpeed.NORMAL_PERCENT;
		String title = displayable != null ? displayable.getTitle() : null;
		// RuntimeMenuComposeController exposes a non-null Kotlin String. An incomplete internal
		// launch intent may omit KEY_MIDLET_NAME, so keep that malformed-input path on a safe
		// fallback instead of allowing a Java null to trip Kotlin's generated parameter check.
		String fallbackTitle = appName == null ? getString(R.string.app_name) : appName;
		runtimeMenuController.update(
				title == null ? fallbackTitle : title,
				chrome.canvas,
				chrome.toolbarVisible,
				inputMethodManager != null,
				vk != null,
				vk != null && vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF,
				orientationLocked,
				emulationSpeedAvailable,
				emulationSpeedPercent);
	}

	private GuestWindowPolicy.Chrome getRuntimeChrome(@Nullable Displayable displayable) {
		return GuestWindowPolicy.resolve(displayable instanceof Canvas,
				statusBarEnabled, actionBarEnabled, displayCutoutEnabled);
	}

	private void setRuntimeToolbarHeight(int height) {
		LinearLayout.LayoutParams layoutParams =
				(LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
		layoutParams.height = Math.max(height, 0);
		binding.toolbar.setLayoutParams(layoutParams);
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

	@Override
	protected void onDestroy() {
		// A MIDlet chooser, malformed archive, or Activity teardown can happen before a
		// MidletThread is started. In that window MicroLoader still owns any launch session.
		if (microLoader != null) {
			microLoader.closeTimingSessionIfNotTransferred();
		}
		super.onDestroy();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			if (windowToken != null) {
				inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
			}
		}
	}

	/**
	 * The Compose host menu owns a separate dialog window. Post the legacy IME toggle until that
	 * window has been dismissed so the Canvas/GLSurfaceView can regain focus and expose its existing
	 * input connection. The delayed call intentionally keeps the old toggle semantics.
	 */
	private void showImeKeyboardAfterMenuDismissal() {
		if (inputMethodManager == null || binding == null) {
			return;
		}
		binding.displayableContainer.postDelayed(() -> {
			if (isFinishing() || isDestroyed() || !(current instanceof Canvas)) {
				return;
			}
			View inputTarget = findCanvasSurface(binding.displayableContainer);
			if (inputTarget == null) {
				inputTarget = binding.displayableContainer;
			}
			inputTarget.requestFocus();
			IBinder windowToken = inputTarget.getWindowToken();
			if (windowToken != null) {
				inputMethodManager.restartInput(inputTarget);
				boolean imeVisible = lastWindowInsets != null
						&& lastWindowInsets.isVisible(WindowInsetsCompat.Type.ime());
				if (imeVisible) {
					inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
				} else {
					inputMethodManager.showSoftInput(inputTarget, InputMethodManager.SHOW_IMPLICIT);
				}
			}
		}, 100L);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && current instanceof Canvas) {
			applySystemUi(getRuntimeChrome(current), current);
		}
	}

	private void updateRecentTaskDescription() {
		String label = appName == null || appName.isEmpty()
				? getString(R.string.app_name) : appName;
		setTaskDescription(new ActivityManager.TaskDescription(label));
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
			showErrorDialog(getString(R.string.runtime_no_midlets));
		} else if (size == 1) {
			microLoader.loadMidlet(midletsClassArray[0], appName);
		} else {
			String requestedClass = getIntent().getStringExtra(KEY_MIDLET_CLASS);
			if (requestedClass != null && midlets.containsKey(requestedClass)) {
				microLoader.loadMidlet(requestedClass, appName);
			} else {
				showMidletDialog(midletsNameArray, midletsClassArray);
			}
		}
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		pendingMidletClasses = classes.clone();
		if (runtimeMenuController != null) {
			runtimeMenuController.showMidletDialog(names.clone());
		} else {
			pendingMidletClasses = null;
			MidletThread.notifyDestroyed();
		}
	}

	void showErrorDialog(String message) {
		if (runtimeMenuController != null) {
			runtimeMenuController.showErrorDialog(message);
		} else {
			MidletThread.notifyDestroyed();
		}
	}

	private float getToolBarHeight() {
		TypedValue typedValue = new TypedValue();
		if (getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
			return typedValue.getDimension(getResources().getDisplayMetrics());
		}
		return 0;
	}

	private void applySystemUi(GuestWindowPolicy.Chrome chrome) {
		applySystemUi(chrome, current);
	}

	private void applySystemUi(GuestWindowPolicy.Chrome chrome, @Nullable Displayable displayable) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			if (chrome.navigationBarVisible) {
				getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
				return;
			}
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!chrome.statusBarVisible) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
			return;
		}
		WindowInsetsControllerCompat controller = getInsetsController();
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		if (chrome.navigationBarVisible) {
			controller.show(WindowInsetsCompat.Type.navigationBars());
		} else {
			controller.hide(WindowInsetsCompat.Type.navigationBars());
		}
		if (chrome.statusBarVisible) {
			controller.show(WindowInsetsCompat.Type.statusBars());
		} else {
			controller.hide(WindowInsetsCompat.Type.statusBars());
		}
		applyGuestInsets(displayable);
	}

	private WindowInsetsControllerCompat getInsetsController() {
		return WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
	}

	private void configureDisplayCutoutWindow() {
		configureDisplayCutoutWindow(displayCutoutEnabled && !statusBarEnabled);
	}

	private void configureDisplayCutoutWindow(boolean allowWindowCutout) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			return;
		}
		WindowManager.LayoutParams attributes = getWindow().getAttributes();
		if (allowWindowCutout) {
			attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
					? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
					: WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		} else {
			// Android 15+ may force the window edge-to-edge regardless of this mode. GuestWindowPolicy
			// remains authoritative there and reserves the cutout inset when the user disables it.
			attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
		}
		getWindow().setAttributes(attributes);
		if (binding != null) {
			ViewCompat.requestApplyInsets(binding.getRoot());
		}
	}

	private void applyGuestInsets(@Nullable Displayable displayable) {
		int left = virtualDisplayPaddingLeft;
		int top = virtualDisplayPaddingTop;
		int right = virtualDisplayPaddingRight;
		int bottom = virtualDisplayPaddingBottom;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && lastWindowInsets != null) {
			Insets systemBars = lastWindowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
			Insets statusBars = lastWindowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars());
			Insets navigationBars = lastWindowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
			Insets cutout = lastWindowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
			Insets ime = lastWindowInsets.getInsets(WindowInsetsCompat.Type.ime());
			GuestWindowPolicy.Chrome chrome = getRuntimeChrome(displayable);
			GuestWindowPolicy.Padding guestPadding = GuestWindowPolicy.calculate(chrome,
					systemBars.left, statusBars.top, systemBars.right, navigationBars.bottom,
					cutout.left, cutout.top, cutout.right, cutout.bottom, ime.bottom);
			left += guestPadding.left;
			top += guestPadding.top;
			right += guestPadding.right;
			bottom += guestPadding.bottom;
		}
		binding.virtualDisplay.setPadding(left, top, right, bottom);
		updateOverlayLocation();
	}

	private void updateOverlayLocation() {
		if (binding == null || !binding.displayableContainer.isLaidOut() || !binding.overlay.isLaidOut()) {
			return;
		}
		View anchor = current instanceof Canvas
				? findCanvasSurface(binding.displayableContainer)
				: binding.displayableContainer;
		if (anchor == null || !anchor.isLaidOut()) {
			return;
		}
		if (overlayAnchor != anchor) {
			if (overlayAnchor != null) {
				overlayAnchor.removeOnLayoutChangeListener(overlayAnchorLayoutListener);
			}
			overlayAnchor = anchor;
			anchor.addOnLayoutChangeListener(overlayAnchorLayoutListener);
		}
		int[] containerLocation = new int[2];
		int[] overlayLocation = new int[2];
		anchor.getLocationOnScreen(containerLocation);
		binding.overlay.getLocationOnScreen(overlayLocation);
		binding.overlay.setLocation(
				containerLocation[0] - overlayLocation[0],
				containerLocation[1] - overlayLocation[1]);
	}

	@Nullable
	private static SurfaceView findCanvasSurface(@NonNull View view) {
		if (view instanceof SurfaceView surfaceView) {
			return surfaceView;
		}
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				SurfaceView surfaceView = findCanvasSurface(group.getChildAt(i));
				if (surfaceView != null) {
					return surfaceView;
				}
			}
		}
		return null;
	}

	public void setCurrent(Displayable displayable) {
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
	}

	/** Implements MIDP's setCurrent(null) background request without changing guest current state. */
	public void requestBackground() {
		runOnUiThread(() -> {
			if (!isFinishing() && !isDestroyed()) {
				moveTaskToBack(true);
			}
		});
	}

	/** Implements MIDP's foreground request without changing the guest Displayable. */
	public void requestForeground() {
		runOnUiThread(() -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			try {
				ActivityManager activityManager =
						(ActivityManager) getSystemService(ACTIVITY_SERVICE);
				if (activityManager != null) {
					activityManager.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_WITH_HOME);
				}
			} catch (SecurityException ignored) {
				// Foregrounding is a host convenience; Android may reject it for background starts.
			}
		});
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
	}

	public void showExitConfirmation() {
		if (runtimeMenuController != null) {
			runtimeMenuController.showExitConfirmation();
		}
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
			showSystemUiForMenu();
		}
		if (runtimeMenuController != null) {
			runtimeMenuController.openMenu();
		} else {
			super.openOptionsMenu();
		}
	}

	/** Temporarily reveals both bars while the runtime menu is open on an immersive Canvas. */
	private void showSystemUiForMenu() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
			return;
		}
		WindowInsetsControllerCompat controller = getInsetsController();
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		controller.show(WindowInsetsCompat.Type.systemBars());
		applyGuestInsets(current);
	}

	@Override
	public void closeOptionsMenu() {
		if (runtimeMenuController != null) {
			runtimeMenuController.closeMenu();
		} else {
			super.closeOptionsMenu();
		}
		// The runtime menu temporarily reveals system bars for immersive Canvas screens. Restore
		// the configured chrome after dismissal so actionbar/statusbar/cutout policy stays coherent.
		if (!actionBarEnabled && current instanceof Canvas) {
			View host = binding == null ? null : binding.displayableContainer;
			if (host != null) {
				host.post(() -> {
					if (!isFinishing() && !isDestroyed() && current instanceof Canvas) {
						applySystemUi(getRuntimeChrome(current), current);
					}
				});
			} else {
				applySystemUi(getRuntimeChrome(current), current);
			}
		}
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
			toggleRuntimeMenuFromInput();
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
			toggleRuntimeMenuFromInput();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	private boolean isRuntimeMenuVisible() {
		return runtimeMenuController != null && runtimeMenuController.isMenuVisible();
	}

	private void toggleRuntimeMenuFromInput() {
		if (isRuntimeMenuVisible()) {
			closeOptionsMenu();
		} else {
			openOptionsMenu();
		}
	}

	private void toggleOrientationLock() {
		if (orientationLocked) {
			VirtualKeyboard vk = ContextHolder.getVk();
			int orientation = vk != null && vk.isPhone()
					? ORIENTATION_PORTRAIT : microLoader.getOrientation();
			setOrientation(orientation);
			orientationLocked = false;
		} else {
			setRequestedOrientation(SCREEN_ORIENTATION_LOCKED);
			orientationLocked = true;
		}
		updateRuntimeMenuState(current);
	}

	private void setVirtualKeyboardEditMode(int mode, @StringRes int toastMessage) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null) {
			return;
		}
		vk.setLayoutEditMode(mode);
		toast(toastMessage);
		updateRuntimeMenuState(current);
	}

	private void finishVirtualKeyboardEdit() {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null) {
			return;
		}
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
		toast(R.string.layout_edit_finished);
		updateRuntimeMenuState(current);
		showSaveVkAlert(false);
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot(current, new SingleObserver<>() {
			@Override
			public void onSubscribe(@NonNull Disposable d) {
			}

			@Override
			public void onSuccess(@NonNull String s) {
				toast(getString(R.string.screenshot_saved) + " " + s);
				MediaScannerConnection.scanFile(MicroActivity.this, new String[]{s}, null, null);
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				toast(R.string.error);
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			toast(R.string.log_saved);
		} catch (IOException e) {
			e.printStackTrace();
			toast(R.string.error);
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || runtimeMenuController == null) {
			return;
		}
		boolean[] states = vk.getKeysVisibility();
		runtimeMenuController.showHideButtons(vk.getKeyNames(), states);
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk != null && runtimeMenuController != null) {
			runtimeMenuController.showSaveVirtualKeyboard(vk.isPhone(), keepScreenPreferred);
		}
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || runtimeMenuController == null) {
			return;
		}
		runtimeMenuController.showLayoutSelection(
				getResources().getStringArray(R.array.PREF_VK_TYPE_ENTRIES), vk.getLayout());
	}

	private void applyHiddenButtons(boolean[] changed) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || changed == null) {
			return;
		}
		boolean[] states = vk.getKeysVisibility();
		if (changed.length != states.length || Arrays.equals(states, changed)) {
			return;
		}
		vk.setKeysVisibility(changed.clone());
		showSaveVkAlert(true);
	}

	private void applyVirtualKeyboardSave(boolean saveScreenParams) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null) {
			return;
		}
		if (saveScreenParams && vk.isPhone()) {
			vk.saveScreenParams();
		}
		vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
	}

	private void applyLayoutSelection(int index) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || index < 0 || index >= getResources()
				.getStringArray(R.array.PREF_VK_TYPE_ENTRIES).length) {
			return;
		}
		vk.setLayout(index);
		if (vk.isPhone()) {
			setOrientation(ORIENTATION_PORTRAIT);
		} else if (microLoader != null) {
			setOrientation(microLoader.getOrientation());
		}
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

	public String getAppName() {
		return appName;
	}

	public void toast(@StringRes int message) {
		toast(getString(message));
	}

	private void toast(String message) {
		runOnUiThread(() -> {
			if (runtimeNoticeController != null) {
				runtimeNoticeController.show(message);
			}
		});
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
			closeOptionsMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			binding.displayableContainer.removeAllViews();
			GuestWindowPolicy.Chrome chrome = getRuntimeChrome(next);
			applySystemUi(chrome, next);
			configureDisplayCutoutWindow(chrome.cutoutAllowed);
			int toolbarHeight = chrome.toolbarVisible
					? (int) (chrome.canvas ? getToolBarHeight() / 1.5 : getToolBarHeight())
					: 0;
			setRuntimeToolbarHeight(toolbarHeight);
			updateRuntimeMenuState(next);
			applyGuestInsets(next);
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
			binding.displayableContainer.post(MicroActivity.this::updateOverlayLocation);
		}
	}
}
