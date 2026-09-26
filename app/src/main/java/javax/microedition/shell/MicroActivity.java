/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2021 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
 * Modified for JL-Mod Plus.
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
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Screen;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutEditState;
import javax.microedition.lcdui.skin.SkinLayer;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import io.github.h3nb.jlmodplus.BuildConfig;
import io.github.h3nb.jlmodplus.MainActivity;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.input.ControllerHostSink;
import io.github.h3nb.jlmodplus.input.ControllerInputRouter;
import io.github.h3nb.jlmodplus.input.HostCommand;
import io.github.h3nb.jlmodplus.memory.MemoryEditorBubbleController;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.util.LogUtils;
import io.github.h3nb.jlmodplus.ui.TransientNoticeComposeController;
import io.github.h3nb.jlmodplus.ui.AppBackgroundColors;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;
	private static final int MIN_RUNTIME_TOOLBAR_TOUCH_TARGET_DP = 48;
	private static final int MAX_IME_REQUEST_ATTEMPTS = 30;
	private static final long IME_REQUEST_RETRY_DELAY_MILLIS = 100L;
	private static final String STATE_EXPECTED_APP_ID = "expected_library_app_id";

	private final Object displayRequestLock = new Object();
	private volatile Displayable current;
	/** UI-thread owner of the Displayable actually mounted in displayableContainer. */
	private Displayable presentedDisplayable;
	private long displayRequestGeneration;
	private boolean runtimeToolbarEnabled;
	private boolean statusBarEnabled;
	private boolean displayCutoutEnabled;
	private boolean orientationLocked;
	private MicroLoader microLoader;
	private String appName;
	private String[] pendingMidletClasses;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private boolean menuKeyLongPressHandled;
	private int imeToggleRequest;
	private String appPath;
	private long expectedAppId;
	private PresetAuthorityClient presetAuthorityClient;
	private RuntimeHostView binding;
	private RuntimeMenuComposeController runtimeMenuController;
	private ControllerInputRouter controllerInputRouter;
	private long controllerTargetGeneration = 1L;
	private MemoryEditorBubbleController memoryEditorController;
	private TransientNoticeComposeController runtimeNoticeController;
	private WindowInsetsCompat lastWindowInsets;
	private boolean skinLayerAvailable;
	private int virtualDisplayPaddingLeft;
	private int virtualDisplayPaddingTop;
	private int virtualDisplayPaddingRight;
	private int virtualDisplayPaddingBottom;
	private View overlayAnchor;
	private SharedPreferences defaultPreferences;
	private SharedPreferences runtimePreferences;
	private VirtualKeyboardEditTransaction virtualKeyboardEditTransaction;
	private EditorDonePlacement.Box layoutEditDonePlacement;
	private EditorDonePlacement.Box layoutEditDoneEditorBounds;
	private final Runnable virtualKeyboardEditorChromeUpdate =
			this::refreshVirtualKeyboardEditorChrome;
	private final SharedPreferences.OnSharedPreferenceChangeListener canvasThemeListener =
			(sharedPreferences, key) -> {
				if (PREF_THEME.equals(key)) refreshCanvasBackground();
			};
	private final View.OnLayoutChangeListener overlayAnchorLayoutListener =
			(view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
					updateOverlayLocation();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		binding = new RuntimeHostView(this);
		setContentView(binding.getRoot());
		binding.layoutEditDone.setOnClickListener(ignored -> requestFinishVirtualKeyboardEdit());
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
		defaultPreferences = sp;
		runtimePreferences = RuntimeUiPreferences.get(this);
		sp.registerOnSharedPreferenceChangeListener(canvasThemeListener);
		runtimeToolbarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
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
		expectedAppId = savedInstanceState == null
				? intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L)
				: savedInstanceState.getLong(
						STATE_EXPECTED_APP_ID, intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L));
		presetAuthorityClient = new PresetAuthorityClient(this);
		String requestedMainClass = intent.getStringExtra(KEY_MIDLET_CLASS);
		microLoader = MidletThread.findLiveRuntime(appPath, expectedAppId, requestedMainClass);
		boolean reattachingRuntime = microLoader != null;
		if (!reattachingRuntime && MidletThread.hasLiveRuntime()) {
			// This isolated process already owns a different live Java heap. A second Activity must
			// not steal its host globals or construct another MIDlet in the same process.
			finish();
			return;
		}
		MicroActivity previousHost = ContextHolder.getActivity();
		if (reattachingRuntime && previousHost != null && previousHost != this) {
			// Release the View actually mounted by the old host before publishing this replacement.
			// This also covers an MIDP Alert overlay, where Display.current is the Alert while the
			// underlying Canvas/Screen View is still mounted in the old Activity.
			previousHost.releaseMountedPresentationForReplacement();
		}
		ContextHolder.setCurrentActivity(this);
		if (reattachingRuntime) {
			expectedAppId = microLoader.getExpectedAppId();
			if (expectedAppId > 0L) {
				intent.putExtra(KEY_LIBRARY_APP_ID, expectedAppId);
			}
		} else {
			PresetAuthorityClient.PrepareResult prepared =
					presetAuthorityClient.prepareRuntime(appPath, expectedAppId);
			if (!prepared.isSuccess()) {
				if (!prepared.isStale()) Config.openSettings(this, appName, appPath, expectedAppId);
				finish();
				return;
			}
			expectedAppId = prepared.appId();
			intent.putExtra(KEY_LIBRARY_APP_ID, expectedAppId);
			microLoader = new MicroLoader(appPath, expectedAppId, prepared.builtInThemeLinked());
			if (!microLoader.init()) {
				finish();
				return;
			}
			microLoader.applyConfiguration();
		}
		controllerInputRouter = new ControllerInputRouter(this, new ControllerHostSink() {
			@Override
			public Canvas currentCanvas() {
				return current instanceof Canvas ? (Canvas) current : null;
			}

			@Override
			public Displayable currentDisplayable() {
				return current;
			}

			@Override
			public io.github.h3nb.jlmodplus.input.ControllerHostTarget currentControllerTarget() {
				String displayableId = Integer.toHexString(System.identityHashCode(current));
				if (runtimeMenuController != null && runtimeMenuController.isMenuVisible()) {
					return new io.github.h3nb.jlmodplus.input.ControllerHostTarget(
							"runtime-host@" + displayableId, controllerTargetGeneration);
				}
				if (current instanceof Screen && ((Screen) current).isControllerModalActive()) {
					return new io.github.h3nb.jlmodplus.input.ControllerHostTarget(
							"screen-modal@" + displayableId, controllerTargetGeneration);
				}
				if (current instanceof Canvas && ((Canvas) current).isControllerKeypadVisible()) {
					return new io.github.h3nb.jlmodplus.input.ControllerHostTarget(
							"controller-keypad@" + displayableId, controllerTargetGeneration);
				}
				// Preserve the established plain Screen/Canvas routing policy. ControllerInputRouter
				// falls back to Displayable/Canvas identity for ownership without making an ordinary
				// MIDP Screen an explicit analog-host target.
				return null;
			}

			@Override
			public boolean onHostCommand(@NonNull HostCommand command, boolean pressed) {
				return handleHostCommand(command, pressed);
			}

			@Override
			public void onControllerInputAccepted() {
				Canvas canvas = currentCanvas();
				if (canvas != null) {
					canvas.controllerInputAccepted();
				}
			}

			@Override
			public void onControllerNotice(@NonNull String message) {
				toast(message);
			}

			@Override
			public boolean isControllerModalActive() {
				return (runtimeMenuController != null && runtimeMenuController.isMenuVisible())
						|| (current instanceof Screen && ((Screen) current).isControllerModalActive())
						|| (current instanceof Canvas && ((Canvas) current).isControllerKeypadVisible());
			}

		}, microLoader.getProfile());
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
			vk.setLayoutEditObserver(this::scheduleVirtualKeyboardEditorChromeUpdate);
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
				// A visible host surface keeps first refusal. On the bare editing surface, short
				// Back requests the same transactional finish flow as the floating Done control.
				if (isRuntimeMenuVisible()) {
					closeOptionsMenu();
				} else if (isVirtualKeyboardLayoutEditing()) {
					requestFinishVirtualKeyboardEdit();
				} else {
					openOptionsMenu();
				}
			}
		});
		if (reattachingRuntime) {
			Display display = Display.getDisplay(null);
			if (display != null) {
				display.attachHost(this);
			}
		} else {
			loadMIDlet();
		}
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
						updateRuntimeMenuState(current);
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
					public void onSetAutoEmulationSpeed() {
						if (microLoader == null || !microLoader.setRuntimeAutoEmulationSpeed()) {
							toast(R.string.error);
						} else {
							updateRuntimeMenuState(current);
						}
					}

					@Override
					public void onResetEmulationSpeed() {
						onSetEmulationSpeed(EmulationSpeed.NORMAL_PERCENT);
					}

					@Override
					public void onMemoryEditor() {
						memoryEditorController().toggleBubble();
						updateRuntimeMenuState(current);
					}

					@Override
					public void onEditVirtualKeyboardLayout() {
						if (runtimePreferences != null && runtimePreferences.getBoolean(
								RuntimeUiPreferences.HIDE_LAYOUT_EDIT_GUIDE, false)) {
							startVirtualKeyboardLayoutEdit();
						} else if (runtimeMenuController != null) {
							runtimeMenuController.showLayoutEditGuide();
						} else {
							startVirtualKeyboardLayoutEdit();
						}
					}


					@Override
					public void onFinishVirtualKeyboardLayout() {
						requestFinishVirtualKeyboardEdit();
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
						if (ContextHolder.getActivity() != MicroActivity.this
								|| !MidletThread.destroyApp(true)) {
							finishUnstartedRuntime(true);
						}
					}

					@Override
					public void onErrorAcknowledged() {
						if (ContextHolder.getActivity() != MicroActivity.this
								|| !MidletThread.destroyApp(true)) {
							finishUnstartedRuntime(true);
						}
					}

					@Override
					public void onExitConfirmed(boolean openSettings) {
						hideSoftInput();
						if (openSettings) {
							Config.openSettings(MicroActivity.this, appName, appPath, expectedAppId);
						}
						if (ContextHolder.getActivity() != MicroActivity.this
								|| !MidletThread.destroyApp(!openSettings)) {
							finishUnstartedRuntime(!openSettings);
						}
					}

					@Override
					public void onHideButtonsConfirmed(boolean[] states) {
						applyHiddenButtons(states);
					}

					@Override
					public void onSaveVirtualKeyboard(@Nullable String updateTarget) {
						VirtualKeyboardSaveResult result =
								applyVirtualKeyboardSave(updateTarget);
						if (!result.isLayoutCommitted()) {
							toast(R.string.virtual_controls_save_failed);
						} else {
							showVirtualKeyboardSaveWarnings(result, updateTarget);
						}
					}

					@Override
					public void onVirtualKeyboardEditSaved(@Nullable String updateTarget) {
						saveVirtualKeyboardEdit(updateTarget);
					}

					@Override
					public void onVirtualKeyboardEditDiscarded() {
						discardVirtualKeyboardEdit();
					}

					@Override
					public void onVirtualKeyboardEditContinued() {
						continueVirtualKeyboardEdit();
					}

					@Override
					public void onLayoutSelected(int index, @Nullable String updateTarget) {
						applyLayoutSelection(index, updateTarget);
					}

					@Override
					public void onLayoutEditGuideConfirmed(boolean dontShowAgain) {
						if (dontShowAgain && runtimePreferences != null) {
							runtimePreferences.edit()
									.putBoolean(RuntimeUiPreferences.HIDE_LAYOUT_EDIT_GUIDE, true)
									.apply();
						}
						startVirtualKeyboardLayoutEdit();
					}
				}, this::dispatchControllerKeyEventFromDialog,
				this::dispatchControllerGenericMotionEventFromDialog,
				this::beginControllerHostTargetChange,
				this::setRuntimeSystemScreenObscured);
		setRuntimeToolbarHeight(getRuntimeToolbarHeight(getRuntimeChrome(current)));
		updateRuntimeMenuState(current);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		if (expectedAppId > 0L) outState.putLong(STATE_EXPECTED_APP_ID, expectedAppId);
		super.onSaveInstanceState(outState);
	}

	private void setRuntimeSystemScreenObscured(boolean obscured) {
		if (ContextHolder.getActivity() != this) {
			return;
		}
		Display display = Display.getDisplay(null);
		if (display != null) {
			display.setSystemScreenObscured(obscured);
		}
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
		boolean emulationSpeedAuto = emulationSpeedAvailable
				&& microLoader.getAutoSpeedController() != null
				&& microLoader.getAutoSpeedController().isAutoEnabled();
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
				emulationSpeedPercent,
				emulationSpeedAuto,
				memoryEditorController != null && memoryEditorController.isBubbleEnabled());
	}

	private MemoryEditorBubbleController memoryEditorController() {
		if (memoryEditorController == null) {
			memoryEditorController = new MemoryEditorBubbleController(
					this, binding.memoryEditorBubble, binding.memoryEditorBubbleIcon,
					binding.memoryEditorBubbleProgress);
		}
		return memoryEditorController;
	}

	private GuestWindowPolicy.Chrome getRuntimeChrome(@Nullable Displayable displayable) {
		return GuestWindowPolicy.resolve(displayable instanceof Canvas,
				statusBarEnabled, runtimeToolbarEnabled, displayCutoutEnabled);
	}

	private int getRuntimeToolbarHeight(GuestWindowPolicy.Chrome chrome) {
		if (!chrome.toolbarVisible) {
			return 0;
		}
		int minimumTouchTarget = Math.round(MIN_RUNTIME_TOOLBAR_TOUCH_TARGET_DP
				* getResources().getDisplayMetrics().density);
		int standardHeight = Math.max(Math.round(getToolBarHeight()), minimumTouchTarget);
		// Canvas previously compressed the action row below the minimum Android touch target.
		// Keep its compact visual treatment while giving each visible action a reliable 48dp target.
		return chrome.canvas ? Math.max(standardHeight * 2 / 3, minimumTouchTarget) : standardHeight;
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
	protected void onStart() {
		super.onStart();
		if (ContextHolder.getActivity() == this) {
			// AMS foreground and LCDUI presentation are separate facts. Reasserting foreground on a
			// replacement Activity is harmless when the live MIDlet never left ACTIVE.
			MidletThread.amsForeground(this);
			Display display = Display.getDisplay(null);
			if (display != null) {
				display.setHostVisible(true);
			}
		}
	}

	@Override
	protected void onStop() {
		if (ContextHolder.getActivity() == this) {
			// The old presentation really does disappear during configuration recreation, so Canvas
			// visibility still falls even though the MIDlet remains logically foreground.
			Display display = Display.getDisplay(null);
			if (display != null) {
				display.setHostVisible(false);
			}
			if (!isChangingConfigurations()) {
				MidletThread.amsBackground(this);
			}
		}
		super.onStop();
	}

	@Override
	protected void onResume() {
		super.onResume();
		refreshCanvasBackground();
		if (memoryEditorController != null) {
			memoryEditorController.onHostResumed();
		}
	}

	@Override
	public void onPause() {
		if (controllerInputRouter != null) {
			controllerInputRouter.clear();
		}
		if (memoryEditorController != null) {
			memoryEditorController.onHostPaused();
		}
		hideSoftInput();
		super.onPause();
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		refreshCanvasBackground();
		if (binding != null) binding.getRoot().post(this::updateOverlayLocation);
		scheduleVirtualKeyboardEditorChromeUpdate();
	}

	@Override
	protected void onDestroy() {
		boolean currentHost = ContextHolder.getActivity() == this;
		if (currentHost) {
			Display display = Display.getDisplay(null);
			if (display != null) {
				display.detachHost();
			}
			clearMountedDisplayable();
			current = null;
			ContextHolder.clearCurrentActivity(this);
		}
		if (binding != null) binding.getRoot().removeCallbacks(virtualKeyboardEditorChromeUpdate);
		VirtualKeyboard vk = ContextHolder.getVk();
		if (currentHost && vk != null) vk.setLayoutEditObserver(null);
		if (controllerInputRouter != null) {
			controllerInputRouter.close();
			controllerInputRouter = null;
		}
		if (defaultPreferences != null) {
			defaultPreferences.unregisterOnSharedPreferenceChangeListener(canvasThemeListener);
			defaultPreferences = null;
		}
		runtimePreferences = null;
		if (memoryEditorController != null) {
			memoryEditorController.destroy();
			memoryEditorController = null;
		}
		// A MIDlet chooser, malformed archive, or Activity teardown can happen before a
		// MidletThread is started. In that window MicroLoader still owns any launch session.
		if (microLoader != null) {
			microLoader.closeTimingSessionIfNotTransferred();
		}
		super.onDestroy();
	}

	private void finishUnstartedRuntime(boolean returnToLibrary) {
		try {
			if (microLoader != null) {
				microLoader.closeTimingSessionIfNotTransferred();
			}
		} catch (Throwable ignored) {
			// Host cleanup must still finish even if launch-local resources cannot be released.
		}
		finishRuntime(returnToLibrary, null);
	}

	void finishRuntime(boolean returnToLibrary, @Nullable Runnable processCleanup) {
		runOnUiThread(() -> {
			boolean currentHost = ContextHolder.getActivity() == this;
			boolean wasVisible = currentHost && isVisible() && !isDestroyed();
			if (currentHost) {
				if (controllerInputRouter != null) {
					controllerInputRouter.clear();
				}
				Display display = Display.getDisplay(null);
				if (display != null) {
					display.detachHost();
				}
				clearMountedDisplayable();
				current = null;
				ContextHolder.clearCurrentActivity(this);
			}
			if (wasVisible && returnToLibrary) {
				startActivity(new Intent(this, MainActivity.class)
						.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
			}
			if (!isFinishing()) {
				finish();
			}
			if (processCleanup != null) {
				processCleanup.run();
			}
		});
	}

	private void clearMountedDisplayable() {
		Displayable mounted = presentedDisplayable;
		if (mounted != null) {
			mounted.clearDisplayableView();
		}
		presentedDisplayable = null;
	}

	private void releaseMountedPresentationForReplacement() {
		clearMountedDisplayable();
		if (binding != null) {
			binding.displayableContainer.removeAllViews();
		}
	}

	private void refreshCanvasBackground() {
		if (current instanceof Canvas canvas) {
			canvas.updateBackgroundTheme(AppBackgroundColors.argb(ProfileModel.isDarkTheme(this)));
		}
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
		int request = ++imeToggleRequest;
		binding.displayableContainer.postDelayed(
			() -> requestImeKeyboardWhenReady(request, 0), IME_REQUEST_RETRY_DELAY_MILLIS);
	}

	private void requestImeKeyboardWhenReady(int request, int attempt) {
		if (request != imeToggleRequest || isFinishing() || isDestroyed()
				|| !(current instanceof Canvas)) {
			return;
		}
		View inputTarget = findCanvasSurface(binding.displayableContainer);
		if (inputTarget == null) {
			inputTarget = binding.displayableContainer;
		}
		if (!binding.getRoot().hasWindowFocus() || !inputTarget.isShown()
				|| inputTarget.getWindowToken() == null) {
			retryImeKeyboardRequest(request, attempt);
			return;
		}
		View target = inputTarget;
		if (!target.requestFocus() || !target.isFocused()) {
			retryImeKeyboardRequest(request, attempt);
			return;
		}
		target.post(() -> {
			if (request != imeToggleRequest || isFinishing() || isDestroyed()
					|| !(current instanceof Canvas) || !binding.getRoot().hasWindowFocus()
					|| !target.isFocused()) {
				if (request == imeToggleRequest) {
					retryImeKeyboardRequest(request, attempt);
				}
				return;
			}
			IBinder windowToken = target.getWindowToken();
			if (windowToken == null) {
				retryImeKeyboardRequest(request, attempt);
				return;
			}
			inputMethodManager.restartInput(target);
			boolean imeVisible = lastWindowInsets != null
					&& lastWindowInsets.isVisible(WindowInsetsCompat.Type.ime());
			if (imeVisible) {
				inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
			} else {
				inputMethodManager.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
				getInsetsController().show(WindowInsetsCompat.Type.ime());
			}
		});
	}

	private void retryImeKeyboardRequest(int request, int attempt) {
		if (attempt < MAX_IME_REQUEST_ATTEMPTS && request == imeToggleRequest
				&& binding != null) {
			binding.displayableContainer.postDelayed(
					() -> requestImeKeyboardWhenReady(request, attempt + 1),
					IME_REQUEST_RETRY_DELAY_MILLIS);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus && controllerInputRouter != null) {
			controllerInputRouter.clear();
		}
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

	private void applyVirtualKeyboardOrientationPolicy(@Nullable VirtualKeyboard vk) {
		switch (VirtualKeyboardOrientationPolicy.resolve(
				orientationLocked, vk != null && vk.isPhone())) {
			case KEEP_CURRENT -> {
				// Runtime Orientation Lock owns requestedOrientation until explicitly disabled.
			}
			case PHONE_PORTRAIT -> setOrientation(ORIENTATION_PORTRAIT);
			case MIDLET_POLICY -> {
				if (microLoader != null) setOrientation(microLoader.getOrientation());
			}
		}
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
			if (!MidletThread.destroyApp(true)) {
				finishUnstartedRuntime(true);
			}
		}
	}

	void showErrorDialog(String message) {
		if (runtimeMenuController != null) {
			runtimeMenuController.showErrorDialog(message);
		} else if (!MidletThread.destroyApp(true)) {
			finishUnstartedRuntime(true);
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
		scheduleVirtualKeyboardEditorChromeUpdate();
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

	public void setCurrent(Displayable displayable, long requestGeneration) {
		synchronized (displayRequestLock) {
			if (ContextHolder.getActivity() != this
					|| requestGeneration <= displayRequestGeneration) {
				return;
			}
			current = displayable;
			displayRequestGeneration = requestGeneration;
			// Post while holding the request lock so accepted generations enter the UI queue in order.
			ViewHandler.postEvent(new SetCurrentEvent(displayable, requestGeneration));
		}
	}

	private boolean ownsDisplayRequest(long requestGeneration) {
		synchronized (displayRequestLock) {
			return ContextHolder.getActivity() == this
					&& displayRequestGeneration == requestGeneration;
		}
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
		if (controllerInputRouter != null && controllerInputRouter.onKeyEvent(event)) {
			return true;
		}
		// KEYCODE_MENU is a host command, not a guest Canvas key. SurfaceView consumes the
		// event before Activity.onKeyLongPress() on recent Android releases, so handle the
		// tracking sequence here before dispatching to the Canvas child.
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
			if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					menuKeyLongPressHandled = false;
					event.startTracking();
					return true;
				}
				if (event.isLongPress()) {
					menuKeyLongPressHandled = true;
					return onKeyLongPress(event.getKeyCode(), event);
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				if (menuKeyLongPressHandled) {
					menuKeyLongPressHandled = false;
					return true;
				}
				return onKeyUp(event.getKeyCode(), event);
			}
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	/** Routes only controller-owned keys from a Compose dialog window. */
	public boolean dispatchControllerKeyEventFromDialog(@NonNull KeyEvent event) {
		return controllerInputRouter != null && controllerInputRouter.onDialogKeyEvent(event);
	}

	/** Routes controller axis/HAT input from a dialog window without stealing other motion. */
	public boolean dispatchControllerGenericMotionEventFromDialog(@NonNull MotionEvent event) {
		return controllerInputRouter != null && controllerInputRouter.onDialogGenericMotionEvent(event);
	}

	private void beginControllerHostTargetChange() {
		if (controllerInputRouter != null) controllerInputRouter.onHostTargetChanging();
		controllerTargetGeneration = controllerTargetGeneration == Long.MAX_VALUE
				? 1L : controllerTargetGeneration + 1L;
		scheduleVirtualKeyboardEditorChromeUpdate();
	}

	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		if (controllerInputRouter != null && controllerInputRouter.onGenericMotionEvent(event)) {
			return true;
		}
		return super.dispatchGenericMotionEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!runtimeToolbarEnabled && current instanceof Canvas) {
			showSystemUiForMenu();
		}
		if (runtimeMenuController != null) {
			runtimeMenuController.openMenu();
			if (controllerInputRouter != null) controllerInputRouter.onHostModalChanged(true);
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
			if (controllerInputRouter != null) controllerInputRouter.onHostModalChanged(false);
		} else {
			super.closeOptionsMenu();
		}
		// The runtime menu temporarily reveals system bars for immersive Canvas screens. Restore
		// the configured chrome after dismissal so toolbar/status-bar/cutout policy stays coherent.
		if (!runtimeToolbarEnabled && current instanceof Canvas) {
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
		boolean shortPress =
				(event.getFlags() & (KeyEvent.FLAG_LONG_PRESS | KeyEvent.FLAG_CANCELED)) == 0;
		if (keyCode == KeyEvent.KEYCODE_BACK && shortPress &&
				!isRuntimeMenuVisible() && isVirtualKeyboardLayoutEditing()) {
			requestFinishVirtualKeyboardEdit();
			return true;
		}
		if ((keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
				&& shortPress) {
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
			orientationLocked = false;
			applyVirtualKeyboardOrientationPolicy(ContextHolder.getVk());
		} else {
			setRequestedOrientation(SCREEN_ORIENTATION_LOCKED);
			orientationLocked = true;
		}
		updateRuntimeMenuState(current);
	}

	private void startVirtualKeyboardLayoutEdit() {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null) return;
		if (virtualKeyboardEditTransaction == null) {
			virtualKeyboardEditTransaction =
					new VirtualKeyboardEditTransaction(vk.captureLayoutEditState());
			layoutEditDonePlacement = null;
			layoutEditDoneEditorBounds = null;
		}
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
		updateRuntimeMenuState(current);
		scheduleVirtualKeyboardEditorChromeUpdate();
	}

	private boolean isVirtualKeyboardLayoutEditing() {
		VirtualKeyboard vk = ContextHolder.getVk();
		return virtualKeyboardEditTransaction != null &&
				virtualKeyboardEditTransaction.isActive() &&
				vk != null && vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF;
	}

	private void requestFinishVirtualKeyboardEdit() {
		VirtualKeyboard vk = ContextHolder.getVk();
		VirtualKeyboardEditTransaction transaction = virtualKeyboardEditTransaction;
		if (vk == null || transaction == null || !transaction.isActive() ||
				vk.getLayoutEditMode() == VirtualKeyboard.LAYOUT_EOF) {
			return;
		}
		if (transaction.requestFinish(vk.captureLayoutEditState()) ==
				VirtualKeyboardEditTransaction.FinishRequest.CLEAN) {
			finishCleanVirtualKeyboardEdit(vk);
			return;
		}
		if (runtimeMenuController == null) {
			transaction.continueEditing();
			return;
		}
		hideVirtualKeyboardEditorDone();
		runtimeMenuController.showFinishVirtualKeyboardEdit(
				resolveRuntimePresetUpdateTarget());
	}

	private void finishCleanVirtualKeyboardEdit(VirtualKeyboard vk) {
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
		clearVirtualKeyboardEditTransaction();
		toast(R.string.layout_edit_finished);
		updateRuntimeMenuState(current);
	}

	private void saveVirtualKeyboardEdit(@Nullable String updateTarget) {
		VirtualKeyboard vk = ContextHolder.getVk();
		VirtualKeyboardEditTransaction transaction = virtualKeyboardEditTransaction;
		if (vk == null || transaction == null || !transaction.isActive()) return;
		VirtualKeyboardSaveResult[] saveResult = new VirtualKeyboardSaveResult[1];
		if (!transaction.commitSave(() -> {
			saveResult[0] = applyVirtualKeyboardSave(updateTarget);
			return saveResult[0].isLayoutCommitted();
		})) {
			toast(R.string.virtual_controls_save_failed);
			updateRuntimeMenuState(current);
			scheduleVirtualKeyboardEditorChromeUpdate();
			return;
		}
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
		clearVirtualKeyboardEditTransaction();
		if (!showVirtualKeyboardSaveWarnings(saveResult[0], updateTarget)) {
			toast(R.string.layout_edit_finished);
		}
		updateRuntimeMenuState(current);
	}

	private void discardVirtualKeyboardEdit() {
		VirtualKeyboard vk = ContextHolder.getVk();
		VirtualKeyboardEditTransaction transaction = virtualKeyboardEditTransaction;
		if (vk == null || transaction == null || !transaction.isActive()) return;
		VirtualKeyboardLayoutEditState baseline = transaction.discard();
		vk.restoreLayoutEditState(baseline);
		applyVirtualKeyboardOrientationPolicy(vk);
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
		clearVirtualKeyboardEditTransaction();
		toast(R.string.layout_edit_finished);
		updateRuntimeMenuState(current);
	}

	private void continueVirtualKeyboardEdit() {
		VirtualKeyboardEditTransaction transaction = virtualKeyboardEditTransaction;
		if (transaction == null || !transaction.isActive()) return;
		transaction.continueEditing();
		updateRuntimeMenuState(current);
		scheduleVirtualKeyboardEditorChromeUpdate();
	}

	private void clearVirtualKeyboardEditTransaction() {
		virtualKeyboardEditTransaction = null;
		layoutEditDonePlacement = null;
		layoutEditDoneEditorBounds = null;
		hideVirtualKeyboardEditorDone();
	}

	private void scheduleVirtualKeyboardEditorChromeUpdate() {
		if (binding == null) return;
		binding.getRoot().removeCallbacks(virtualKeyboardEditorChromeUpdate);
		binding.getRoot().post(virtualKeyboardEditorChromeUpdate);
	}

	private void refreshVirtualKeyboardEditorChrome() {
		if (binding == null || !isVirtualKeyboardLayoutEditing() ||
				(virtualKeyboardEditTransaction != null &&
						virtualKeyboardEditTransaction.isFinishPending()) ||
				isRuntimeMenuVisible() ||
				!(current instanceof Canvas)) {
			hideVirtualKeyboardEditorDone();
			return;
		}
		VirtualKeyboard vk = ContextHolder.getVk();
		View anchor = overlayAnchor;
		View root = binding.getRoot();
		if (vk == null || anchor == null || !anchor.isLaidOut() || !root.isLaidOut() ||
				vk.isLayoutManipulationActive()) {
			hideVirtualKeyboardEditorDone();
			return;
		}

		RectF localEditorBounds = vk.getLayoutEditBounds();
		if (localEditorBounds == null || localEditorBounds.width() <= 0.0f ||
				localEditorBounds.height() <= 0.0f) {
			hideVirtualKeyboardEditorDone();
			return;
		}

		int[] anchorLocation = new int[2];
		int[] rootLocation = new int[2];
		anchor.getLocationOnScreen(anchorLocation);
		root.getLocationOnScreen(rootLocation);
		float offsetX = anchorLocation[0] - rootLocation[0];
		float offsetY = anchorLocation[1] - rootLocation[1];

		RectF editorBounds = new RectF(localEditorBounds);
		editorBounds.offset(offsetX, offsetY);
		if (!editorBounds.intersect(0.0f, 0.0f, root.getWidth(), root.getHeight()) ||
				editorBounds.width() <= 0.0f || editorBounds.height() <= 0.0f) {
			hideVirtualKeyboardEditorDone();
			return;
		}

		EditorDonePlacement.Box usable = toPlacementBox(editorBounds);
		if (layoutEditDoneEditorBounds != null &&
				(Math.abs(layoutEditDoneEditorBounds.width() - usable.width()) > 0.5f ||
						Math.abs(layoutEditDoneEditorBounds.height() - usable.height()) > 0.5f)) {
			// Orientation/viewport-size changes get a fresh spatial choice; semantic baseline stays.
			layoutEditDonePlacement = null;
		}
		layoutEditDoneEditorBounds = usable;

		List<EditorDonePlacement.Box> obstacles = new ArrayList<>();
		for (RectF localControl : vk.getVisibleLayoutControlBounds()) {
			RectF control = new RectF(localControl);
			control.offset(offsetX, offsetY);
			obstacles.add(toPlacementBox(control));
		}

		View done = binding.layoutEditDone;
		done.measure(
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		float desiredWidth = Math.max(done.getMeasuredWidth(), dpToPx(80));
		float desiredHeight = Math.max(done.getMeasuredHeight(), dpToPx(48));
		EditorDonePlacement.Box placement = EditorDonePlacement.place(
				usable,
				desiredWidth,
				desiredHeight,
				dpToPx(10),
				obstacles,
				layoutEditDonePlacement);
		if (placement == null) {
			hideVirtualKeyboardEditorDone();
			return;
		}

		FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) done.getLayoutParams();
		params.width = Math.max(1, Math.round(placement.width()));
		params.height = Math.max(1, Math.round(placement.height()));
		done.setLayoutParams(params);
		done.setX(placement.left);
		done.setY(placement.top);
		done.setEnabled(true);
		done.setVisibility(View.VISIBLE);
		layoutEditDonePlacement = placement;
	}

	private void hideVirtualKeyboardEditorDone() {
		if (binding == null) return;
		binding.layoutEditDone.setEnabled(false);
		binding.layoutEditDone.setVisibility(View.GONE);
	}

	private EditorDonePlacement.Box toPlacementBox(RectF rect) {
		return new EditorDonePlacement.Box(rect.left, rect.top, rect.right, rect.bottom);
	}

	private int dpToPx(int value) {
		return Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP,
				value,
				getResources().getDisplayMetrics()));
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

	private void showSaveVkAlert() {
		if (ContextHolder.getVk() != null && runtimeMenuController != null) {
			runtimeMenuController.showSaveVirtualKeyboard(resolveRuntimePresetUpdateTarget());
		}
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || runtimeMenuController == null) {
			return;
		}
		// Resolve on every open so a runtime Activity never caches a renamed/deleted source.
		String updateTarget = resolveRuntimePresetUpdateTarget();
		if (isVirtualKeyboardLayoutEditing()) {
			// Layout templates are in-memory editor changes while an edit transaction is active.
			updateTarget = null;
		}
		runtimeMenuController.showLayoutSelection(
				getResources().getStringArray(R.array.PREF_VK_TYPE_ENTRIES),
				vk.getLayout(),
				updateTarget);
	}

	private void applyHiddenButtons(boolean[] changed) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || changed == null) {
			return;
		}
		boolean[] states = vk.getKeysVisibility();
		if (!RuntimeVirtualKeyboardPersistence.hiddenButtonsChanged(states, changed)) {
			return;
		}
		vk.setKeysVisibility(changed.clone());
		if (isVirtualKeyboardLayoutEditing()) {
			scheduleVirtualKeyboardEditorChromeUpdate();
		} else {
			showSaveVkAlert();
		}
	}

	private VirtualKeyboardSaveResult applyVirtualKeyboardSave(@Nullable String updateTarget) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || presetAuthorityClient == null || expectedAppId <= 0L
				|| !vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM)) {
			return VirtualKeyboardSaveResult.layoutFailed();
		}
		return saveCurrentVirtualKeyboard(vk, updateTarget);
	}

	private VirtualKeyboardSaveResult saveCurrentVirtualKeyboard(
			@NonNull VirtualKeyboard vk, @Nullable String updateTarget) {
		final byte[] payload;
		try {
			payload = vk.encodeCurrentLayoutForPersistence();
		} catch (IOException | RuntimeException encodingFailure) {
			return VirtualKeyboardSaveResult.layoutFailed();
		}
		VirtualKeyboardSaveResult result = presetAuthorityClient.saveVirtualKeyboardLayout(
				appPath, expectedAppId, payload, updateTarget);
		if (result.isLayoutCommitted()) vk.onLayoutPersistenceCommitted();
		return result;
	}

	private void applyLayoutSelection(int index, @Nullable String updateTarget) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null || index < 0 || index >= getResources()
				.getStringArray(R.array.PREF_VK_TYPE_ENTRIES).length) {
			return;
		}
		if (isVirtualKeyboardLayoutEditing()) {
			vk.setLayoutForEditing(index);
			applyVirtualKeyboardOrientationPolicy(vk);
			return;
		}
		if (!RuntimeVirtualKeyboardPersistence.layoutSelectionChanged(vk.getLayout(), index)) {
			applyVirtualKeyboardOrientationPolicy(vk);
			return;
		}
		VirtualKeyboardEditTransaction selection =
				new VirtualKeyboardEditTransaction(vk.captureLayoutEditState());
		if (!vk.setLayout(index)) {
			vk.restoreLayoutEditState(selection.discard());
			toast(R.string.virtual_controls_save_failed);
			applyVirtualKeyboardOrientationPolicy(vk);
			return;
		}
		VirtualKeyboardSaveResult[] result = new VirtualKeyboardSaveResult[1];
		if (!selection.commitSave(() -> {
			result[0] = saveCurrentVirtualKeyboard(vk, updateTarget);
			return result[0].isLayoutCommitted();
		})) {
			// One-shot template selection uses the same baseline/rollback rule as the editor.
			vk.restoreLayoutEditState(selection.discard());
			toast(R.string.virtual_controls_save_failed);
		} else {
			showVirtualKeyboardSaveWarnings(result[0], updateTarget);
		}
		applyVirtualKeyboardOrientationPolicy(vk);
	}

	@Nullable
	private String resolveRuntimePresetUpdateTarget() {
		return presetAuthorityClient == null || expectedAppId <= 0L
				? null
				: presetAuthorityClient.resolveUpdateTarget(appPath, expectedAppId);
	}

	private boolean showVirtualKeyboardSaveWarnings(
			@NonNull VirtualKeyboardSaveResult result, @Nullable String updateTarget) {
		String warning = null;
		if (updateTarget != null) {
			String presetWarning = switch (result.getPresetUpdateOutcome()) {
				case FAILED -> getString(R.string.runtime_preset_update_failed, updateTarget);
				case SAVED_UNLINKED ->
						getString(R.string.runtime_preset_update_unlinked, updateTarget);
				default -> null;
			};
			if (presetWarning != null) {
				warning = warning == null ? presetWarning : warning + "\n" + presetWarning;
			}
		}
		if (warning != null) {
			toast(warning);
			return true;
		}
		return false;
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

	private boolean handleHostCommand(@NonNull HostCommand command, boolean pressed) {
		// A visible app-owned modal owns every controller command. This keeps a command from
		// falling through to a MIDlet while the runtime menu or Screen soft menu is open.
		if (runtimeMenuController != null && runtimeMenuController.isMenuVisible()) {
			return runtimeMenuController.handleHostCommand(command, pressed);
		}
		if (current instanceof Screen && ((Screen) current).isControllerModalActive()) {
			return ((Screen) current).handleHostCommand(command, pressed);
		}
		if (current instanceof Canvas && ((Canvas) current).isControllerKeypadVisible()) {
			if (pressed && (command == HostCommand.Back || command == HostCommand.OpenMenu
					|| command == HostCommand.OpenKeypad)) {
				beginControllerHostTargetChange();
			}
			return ((Canvas) current).handleHostCommand(command, pressed);
		}
		switch (command) {
			case OpenMenu:
				if (pressed) toggleRuntimeMenuFromInput();
				return true;
			case Back:
				if (pressed && current instanceof Screen && ((Screen) current).handleHostCommand(command, true)) {
					return true;
				}
				if (pressed) getOnBackPressedDispatcher().onBackPressed();
				return true;
			case Activate:
			case NavigateUp:
			case NavigateDown:
			case NavigateLeft:
			case NavigateRight:
			case NextTab:
			case PreviousTab:
				return current instanceof Screen &&
						((Screen) current).handleHostCommand(command, pressed);
			case OpenKeypad:
				if (pressed && current instanceof Canvas) {
					beginControllerHostTargetChange();
					((Canvas) current).openControllerKeypad();
				}
				return true;
			default:
				return true;
		}
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
		private final Displayable next;
		private final long requestGeneration;

		private SetCurrentEvent(Displayable next, long requestGeneration) {
			this.next = next;
			this.requestGeneration = requestGeneration;
		}

		@Override
		public void process() {
			if (!ownsDisplayRequest(requestGeneration)) {
				return;
			}
			closeOptionsMenu();
			if (controllerInputRouter != null) {
				controllerInputRouter.onTargetChanged();
			}
			Displayable previous = presentedDisplayable;
			boolean replaceMountedView = previous != next;
			if (replaceMountedView) {
				if (previous != null) {
					previous.clearDisplayableView();
				}
				binding.displayableContainer.removeAllViews();
			}
			GuestWindowPolicy.Chrome chrome = getRuntimeChrome(next);
			applySystemUi(chrome, next);
			configureDisplayCutoutWindow(chrome.cutoutAllowed);
			setRuntimeToolbarHeight(getRuntimeToolbarHeight(chrome));
			updateRuntimeMenuState(next);
			applyGuestInsets(next);
			if (replaceMountedView && next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
			presentedDisplayable = next;
			refreshCanvasBackground();
			binding.displayableContainer.post(MicroActivity.this::updateOverlayLocation);
		}
	}
}
