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
import android.content.DialogInterface;
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
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatCheckBox;
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
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private boolean orientationLocked;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;
	private RuntimeHostView binding;
	private RuntimeMenuComposeController runtimeMenuController;
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
			skinLayerAvailable = true;
			binding.overlay.addLayer(skinLayer);
			configureDisplayCutoutWindow();
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
		initializeRuntimeMenu();
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
						if (inputMethodManager != null) {
							inputMethodManager.toggleSoftInputFromWindow(
									binding.displayableContainer.getWindowToken(),
									InputMethodManager.SHOW_FORCED, 0);
						}
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
				});
		setRuntimeToolbarHeight((int) getToolBarHeight());
		updateRuntimeMenuState(current);
	}

	private void updateRuntimeMenuState(@Nullable Displayable displayable) {
		if (runtimeMenuController == null) {
			return;
		}
		boolean canvas = displayable instanceof Canvas;
		VirtualKeyboard vk = ContextHolder.getVk();
		String title = displayable != null ? displayable.getTitle() : null;
		// RuntimeMenuComposeController exposes a non-null Kotlin String. An incomplete internal
		// launch intent may omit KEY_MIDLET_NAME, so keep that malformed-input path on a safe
		// fallback instead of allowing a Java null to trip Kotlin's generated parameter check.
		String fallbackTitle = appName == null ? getString(R.string.app_name) : appName;
		runtimeMenuController.update(
				title == null ? fallbackTitle : title,
				canvas,
				!canvas || actionBarEnabled,
				inputMethodManager != null,
				vk != null,
				vk != null && vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF,
				orientationLocked);
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
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(names, (d, n) -> microLoader.loadMidlet(classes[n], appName))
				.setOnCancelListener(d -> {
					d.dismiss();
					MidletThread.notifyDestroyed();
				});
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> MidletThread.notifyDestroyed());
		builder.show();
	}

	private float getToolBarHeight() {
		TypedValue typedValue = new TypedValue();
		if (getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typedValue, true)) {
			return typedValue.getDimension(getResources().getDisplayMetrics());
		}
		return 0;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
			return;
		}
		WindowInsetsControllerCompat controller = getInsetsController();
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		controller.hide(WindowInsetsCompat.Type.navigationBars());
		if (statusBarEnabled) {
			controller.show(WindowInsetsCompat.Type.statusBars());
		} else {
			controller.hide(WindowInsetsCompat.Type.statusBars());
		}
		applyGuestInsets(current);
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
			return;
		}
		WindowInsetsControllerCompat controller = getInsetsController();
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		controller.show(WindowInsetsCompat.Type.systemBars());
		applyGuestInsets(current);
	}

	private WindowInsetsControllerCompat getInsetsController() {
		return WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
	}

	private void configureDisplayCutoutWindow() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !skinLayerAvailable
				|| statusBarEnabled || actionBarEnabled) {
			return;
		}
		WindowManager.LayoutParams attributes = getWindow().getAttributes();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
		} else {
			attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		getWindow().setAttributes(attributes);
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
			boolean canvas = displayable instanceof Canvas;
			GuestWindowPolicy.Padding guestPadding = GuestWindowPolicy.calculate(canvas,
					skinLayerAvailable, statusBarEnabled, actionBarEnabled,
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

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
	}

	public void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		DialogInterface.OnClickListener onClickListener = (d, w) -> {
			hideSoftInput();
			if (w == DialogInterface.BUTTON_NEUTRAL) {
				Config.openSettings(this, appName, appPath);
			}
			MidletThread.destroyApp();
		};
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, onClickListener)
				.setNeutralButton(R.string.action_settings, onClickListener)
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
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
		if (runtimeMenuController != null) {
			runtimeMenuController.openMenu();
		} else {
			super.openOptionsMenu();
		}
	}

	@Override
	public void closeOptionsMenu() {
		if (runtimeMenuController != null) {
			runtimeMenuController.closeMenu();
		} else {
			super.closeOptionsMenu();
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
		Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
		updateRuntimeMenuState(current);
	}

	private void finishVirtualKeyboardEdit() {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (vk == null) {
			return;
		}
		vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
		Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
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
				Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
						+ " " + s, Toast.LENGTH_LONG).show();
				MediaScannerConnection.scanFile(MicroActivity.this, new String[]{s}, null, null);
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
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
		new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(vk.getKeyNames(), changed, (dialog, which, isChecked) -> {})
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					if (!Arrays.equals(states, changed)) {
						vk.setKeysVisibility(changed);
						showSaveVkAlert(true);
					}
				}).show();
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.CONFIRMATION_REQUIRED);
		builder.setMessage(R.string.pref_vk_save_alert);
		builder.setNegativeButton(android.R.string.no, null);
		AlertDialog dialog = builder.create();

		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			AppCompatCheckBox cb = new AppCompatCheckBox(this);
			cb.setText(R.string.opt_save_screen_params);
			cb.setChecked(keepScreenPreferred);

			TypedValue out = new TypedValue();
			getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, out, true);
			int paddingH = getResources().getDimensionPixelOffset(out.resourceId);
			int paddingT = getResources().getDimensionPixelOffset(androidx.appcompat.R.dimen.abc_dialog_padding_top_material);
			dialog.setView(cb, paddingH, paddingT, paddingH, 0);

			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) -> {
				if (cb.isChecked()) {
					vk.saveScreenParams();
				}
				vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
			});
		} else {
			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) ->
					ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
		}
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(R.array.PREF_VK_TYPE_ENTRIES, vk.getLayout(), null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					vk.setLayout(((AlertDialog) d).getListView().getCheckedItemPosition());
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				});
		builder.show();
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
		runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
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
