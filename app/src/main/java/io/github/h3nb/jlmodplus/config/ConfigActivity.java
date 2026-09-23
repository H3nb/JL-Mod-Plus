/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2018-2019 Nikita Shakarun
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

package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT;
import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_INSTALLED_APP_PATH;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_LIBRARY_APP_ID;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_MIDLET_NAME;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_THEME;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.shell.transform.MidletTransformMetadata;
import javax.microedition.util.ContextHolder;

import kotlin.io.FilesKt;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.model.Size;
import io.github.h3nb.jlmodplus.settings.KeyMapperActivity;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.ui.ThemedToast;
import ru.woesss.util.TextUtils;
import io.github.h3nb.jlmodplus.jar.Descriptor;
import io.github.h3nb.jlmodplus.input.ControllerConfig;
import io.github.h3nb.jlmodplus.input.ControllerHostSink;
import io.github.h3nb.jlmodplus.input.ControllerHostTarget;
import io.github.h3nb.jlmodplus.input.ControllerInputRouter;
import io.github.h3nb.jlmodplus.input.CalibrationChannel;
import io.github.h3nb.jlmodplus.input.CalibrationEvent;
import io.github.h3nb.jlmodplus.input.CalibrationPhase;
import io.github.h3nb.jlmodplus.input.CalibrationStep;
import io.github.h3nb.jlmodplus.input.GamepadCalibration;
import io.github.h3nb.jlmodplus.input.GamepadCalibrationSession;
import io.github.h3nb.jlmodplus.input.HostCommand;
import io.github.h3nb.jlmodplus.input.PointerMode;
import io.github.h3nb.jlmodplus.input.ResolutionSource;
import io.github.h3nb.jlmodplus.input.StickProcessor;
import io.github.h3nb.jlmodplus.input.StickMode;
import static io.github.h3nb.jlmodplus.config.ConfigFormEvents.ColorField;

public class ConfigActivity extends AppCompatActivity implements ShaderTuneAlert.Callback {
	private static final String TAG = ConfigActivity.class.getSimpleName();

	@FunctionalInterface
	interface BooleanOperation {
		boolean run();
	}
	private static final String STATE_PROFILE_DRAFT_PATH = "profile_edit_draft_path";
	private static final String STATE_PROFILE_DRAFT_DIRTY = "profile_edit_draft_dirty";
	private static final String STATE_PROFILE_EDIT_MODE = "profile_edit_mode";
	private static final String STATE_PROFILE_EDIT_TOKEN = "profile_edit_token";
	private static final String STATE_EXPECTED_APP_ID = "expected_library_app_id";

	private final ArrayList<Size> screenPresets = new ArrayList<>();
	private final ArrayList<Size> removableScreenPresets = new ArrayList<>();
	private final ArrayList<ConfigUiState.FontPreset> fontPresets = new ArrayList<>();
	private final ArrayList<String> skinOptions = new ArrayList<>();
	private final ArrayList<String> soundBankOptions = new ArrayList<>();

	private File dataDir;
	private ProfileModel params;
	@Nullable private ProfileModel persistedBaseline;
	private boolean isProfile;
	private File appDir;
	private long expectedAppId;
	@Nullable private InstalledAppWriteGuard installedWriteGuard;
	private Display display;
	private File configDir;
	private File profilesRoot;
	private ArrayList<ShaderInfo> shaders;
	private String workDir;
	private boolean needShow;
	private boolean operationRunning;
	/** Isolated staging directory and process-local authority for a reusable preset edit. */
	@Nullable private File profileEditDraftDir;
	@Nullable private ProfilesManager.PresetEditSession profileEditSession;
	private boolean profileDraftDirty;
	private boolean profileEditorResumeObserved;
	private ConfigFormState currentForm;
	private ConfigComposeController composeController;
	private ProfileModel builtInDefaultParams;
	/** True when this app config is the built-in template and should track host theme changes. */
	private boolean builtInThemeLinked;
	private SharedPreferences hostPreferences;
	private final SharedPreferences.OnSharedPreferenceChangeListener hostThemeListener =
			(sharedPreferences, key) -> {
				if (PREF_THEME.equals(key) && builtInThemeLinked && !isProfile) {
					// AppCompat applies the new uiMode after the preference callback. Post the sync so
					// the profile palette is derived from the new configuration, not the old one.
					getWindow().getDecorView().post(this::syncLinkedBuiltInTheme);
				}
				if (PREF_EMULATOR_DIR.equals(key) && !isProfile) {
					getWindow().getDecorView().post(() -> {
						if (!isFinishing() && !isDestroyed()) refreshProfileMatchCache();
					});
				}
			};
	private List<ProfilesManager.ProfileInfo> inspectedProfiles = Collections.emptyList();
	private List<String> profileNames = Collections.emptyList();
	@Nullable private String cachedDefaultProfileName;
	private int profileCacheGeneration;
	private final ExecutorService profileMetadataExecutor = Executors.newSingleThreadExecutor();
	@Nullable private String profileOrigin;
	@Nullable private PresetLinkage presetLinkage;
	@Nullable private GamepadCalibrationSession gamepadCalibration;
	@Nullable private ControllerInputRouter controllerInputRouter;
	private long controllerTargetGeneration = 1L;
	@Nullable private String gamepadCalibrationSignature;
	@Nullable private InputDevice gamepadCalibrationDevice;
	private boolean gamepadCalibrationPending;
	private Set<CalibrationChannel> gamepadCalibrationChannels = Collections.emptySet();
	private final Runnable gamepadCalibrationTicker = new Runnable() {
		@Override
		public void run() {
			GamepadCalibrationSession calibration = gamepadCalibration;
			if (calibration == null || composeController == null) return;
			if (calibration.getPhase() == CalibrationPhase.WAIT_NEUTRAL) {
				CalibrationStep step = calibration.tickNeutral(SystemClock.elapsedRealtime());
				publishGamepadCalibration(step, null);
			}
			getWindow().getDecorView().postDelayed(this, 250L);
		}
	};

	private final ConfigFormEvents formEvents = new ConfigFormEvents() {
		@Override
		public void onFormChanged(@NonNull ConfigFormState state) {
			updateForm(state);
		}

		@Override
		public void onAddResolutionPreset(@NonNull Size size) {
			addResolutionToPresets(size);
		}

		@Override
		public void onRemoveResolutionPreset(@NonNull Size size) {
			removeResolutionPreset(size);
		}

		@Override
		public void onColorPicker(ColorField field) {
			showColorPicker(field);
		}

		@Override
		public void onColorPicked(ColorField field, @NonNull String value) {
			// The picker is host-owned and may report after a mode change. A stale result must not
			// silently turn Theme/Immersive back into a custom color edit.
			if (currentForm != null && (field != ColorField.SCREEN_BACKGROUND
					|| currentForm.screenBackgroundMode == BackgroundMode.CUSTOM)) {
				updateForm(setColorValue(currentForm, field, value));
			}
		}

		@Override
		public void onKeyMappings() {
			openKeyMappings();
		}

		@Override
		public void onEncodingPicker() {
			showCharsetPicker();
		}

		@Override
		public void onEncodingSelected(@NonNull String charset) {
			applyCharset(charset);
		}

		@Override
		public void onShaderTuning() {
			showShaderSettings();
		}

		@Override
		public void onShaderTuningComplete(@NonNull float[] values) {
			onTuneComplete(values);
		}

		@Override
		public void onManageProfiles() {
			startActivity(new Intent(ConfigActivity.this, ProfilesActivity.class));
		}

		@Override
		public boolean onApplyBuiltInTemplate(@NonNull ConfigFormEvents.PresetApplyScope scope) {
			return applyBuiltInTemplate(scope);
		}

		@Override
		public boolean onApplyTemplate(@NonNull String name,
				@NonNull ConfigFormEvents.PresetApplyScope scope) {
			return applyTemplate(name, scope);
		}

		@Override
		public boolean onSaveTemplate(@NonNull String name) {
			return saveTemplate(name);
		}

		@Override
		public boolean onUpdatePreset(@NonNull String name) {
			return updatePreset(name);
		}

		@Override
		public void onGamepadCalibration() {
			startGamepadCalibration();
		}

		@Override
		public void onGamepadCalibrationAdvance() {
			advanceGamepadCalibration();
		}

		@Override
		public void onGamepadCalibrationSave() {
			saveGamepadCalibration();
		}

		@Override
		public void onGamepadCalibrationCancel() {
			cancelGamepadCalibration();
		}

		@Override
		public void onGamepadCalibrationReset() {
			resetGamepadCalibration();
		}

		@Override
		public void onGamepadDiagnosis() {
			if (composeController != null) {
				composeController.showGamepadDiagnosis(buildConfigGamepadDiagnosis());
			}
		}

		@Override
		public void onGamepadHelp() {
			if (composeController != null) composeController.showGamepadHelp();
		}

	};

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String action = intent.getAction();
		isProfile = ACTION_EDIT_PROFILE.equals(action);
		needShow = isProfile || ACTION_EDIT.equals(action);
		Uri intentData = intent.getData();
		String path = intentData != null && "file".equals(intentData.getScheme())
				? intentData.getPath() : intent.getDataString();
		if (path == null) {
			needShow = false;
			finish();
			return;
		}
		if (isProfile) {
			File profileEditTarget = new File(path).getAbsoluteFile();
			String savedDraftPath = savedInstanceState == null
					? null : savedInstanceState.getString(STATE_PROFILE_DRAFT_PATH);
			String savedEditMode = savedInstanceState == null
					? null : savedInstanceState.getString(STATE_PROFILE_EDIT_MODE);
			String savedToken = savedInstanceState == null
					? null : savedInstanceState.getString(STATE_PROFILE_EDIT_TOKEN);
			try {
				if (savedInstanceState != null) {
					if (savedDraftPath == null) {
						throw new IOException("Missing saved preset editor draft");
					}
					File savedDraft = new File(savedDraftPath);
					ProfilesManager.ProfileEditMode restoredMode = parseProfileEditMode(savedEditMode);
					if (!savedDraft.isDirectory() || restoredMode == null) {
						throw new IOException("Preset editor draft cannot be restored");
					}
					profileEditDraftDir = savedDraft;
					profileEditSession = new ProfilesManager.PresetEditSession(
							restoredMode, profileEditTarget, savedToken);
				} else {
					profileEditDraftDir = createProfileEditDraft(profileEditTarget);
				}
			} catch (IOException | RuntimeException e) {
				if (savedToken != null) {
					ProfilesManager.releasePresetEditSession(new ProfilesManager.PresetEditSession(
							ProfilesManager.ProfileEditMode.EDIT_EXISTING,
							profileEditTarget, savedToken));
				}
				Log.e(TAG, "createProfileEditDraft", e);
				ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
				finish();
				return;
			}
			configDir = profileEditDraftDir;
			profileDraftDirty = savedInstanceState != null
					&& savedInstanceState.getBoolean(STATE_PROFILE_DRAFT_DIRTY, false);
			File root = profileEditTarget.getParentFile();
			profilesRoot = root;
			File openedWorkdir = root == null ? null : root.getParentFile();
			workDir = openedWorkdir == null ? Config.getEmulatorDir() : openedWorkdir.getPath();
			setTitle(getString(R.string.preset_edit_title, profileEditTarget.getName()));
		} else {
			setTitle(intent.getStringExtra(KEY_MIDLET_NAME));
			appDir = new File(path);
			File convertedDir = appDir.getParentFile();
			if (!appDir.isDirectory() || convertedDir == null
					|| (workDir = convertedDir.getParent()) == null) {
				needShow = false;
				String storageName = "";
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
					StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
					if (sm != null) {
						StorageVolume storageVolume = sm.getStorageVolume(appDir);
						if (storageVolume != null) {
							String desc = storageVolume.getDescription(this);
							if (desc != null) {
								storageName = "\"" + desc + "\" ";
							}
						}
					}
				}
				ComposeView errorView = new ComposeView(this);
				EdgeToEdgeCompat.enableForComposeSurface(this);
				setContentView(errorView);
				ConfigErrorComposeBridge.install(errorView,
						getString(R.string.err_missing_app, storageName),
						new ConfigErrorActions() {
							@Override
							public void onExit() {
								finish();
							}
						});
				return;
			}
			dataDir = new File(workDir + Config.MIDLET_DATA_DIR + appDir.getName());
			configDir = new File(workDir + Config.MIDLET_CONFIGS_DIR + appDir.getName());
			profilesRoot = new File(workDir, "templates");
		}
		if (isProfile) {
			configDir.mkdirs();
		} else {
			installedWriteGuard = InstalledAppWriteGuard.create(this);
			expectedAppId = savedInstanceState == null
					? intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L)
					: savedInstanceState.getLong(
							STATE_EXPECTED_APP_ID, intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L));
			if (expectedAppId <= 0L) {
				expectedAppId = installedWriteGuard.resolveCurrentAppId(appDir.getPath());
			}
			if (expectedAppId <= 0L) {
				ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
				finish();
				return;
			}
			intent.putExtra(KEY_LIBRARY_APP_ID, expectedAppId);
			if (Config.requiresSettings(configDir)) needShow = true;
		}
		hostPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		hostPreferences.registerOnSharedPreferenceChangeListener(hostThemeListener);
		presetLinkage = isProfile ? null : new PresetLinkage(hostPreferences, configDir);
		profileOrigin = presetLinkage == null ? null : presetLinkage.getOrigin();
		builtInDefaultParams = newBuiltInProfile();

		if (!loadConfig()) {
			needShow = false;
			finish();
			return;
		}
		if (!params.isNew && !needShow) {
			startMIDlet();
			return;
		}
		refreshProfileMatchCache();
		EdgeToEdgeCompat.enableForComposeSurface(this);
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		display = getWindowManager().getDefaultDisplay();

		fillScreenSizePresets(display.getWidth(), display.getHeight());

		addFontSizePreset("128 x 128", 9, 13, 15);
		addFontSizePreset("128 x 160", 13, 15, 20);
		addFontSizePreset("176 x 220", 15, 18, 22);
		addFontSizePreset("240 x 320", 18, 22, 26);
		initSoundBankOptions();
		initSkinOptions();
		initShaderSpinner();
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		composeController = new ConfigComposeController(
				composeView,
				createUiState(),
				formEvents,
				new ConfigMenuActions() {
					@Override
					public void onBack() {
						handleBackRequest();
					}

					@Override
					public void onStart() {
						startMIDlet();
					}

					@Override
					public void onClearData() {
						if (dataDir != null) {
							boolean cleared = isProfile || runInstalledWrite(() -> {
								FileUtils.clearDirectory(dataDir);
								return true;
							});
							if (!cleared) {
								ThemedToast.show(ConfigActivity.this, R.string.error, Toast.LENGTH_SHORT);
							}
						}
					}

				@Override
				public void onResetSettings() {
					if (operationRunning) return;
					operationRunning = true;
					try {
						if (isProfile) {
							profileDraftDirty = true;
							params = newBuiltInProfile();
							loadParams(false);
							builtInThemeLinked = true;
							return;
						}
						if (!replaceActiveConfigWithBuiltIn()) {
							if (composeController != null) {
								composeController.update(createUiState());
							}
							ThemedToast.show(ConfigActivity.this, R.string.error, Toast.LENGTH_SHORT);
							return;
						}
						refreshProfileMatchCache();
						if (composeController != null) {
							composeController.update(createUiState());
						}
					} finally {
						operationRunning = false;
					}
				}

				@Override
				public void onResetLayout() {
					if (operationRunning) return;
					operationRunning = true;
					try {
						if (isProfile) profileDraftDirty = true;
						boolean removed;
						if (isProfile) {
							removed = ProfilesManager.removeLocalKeyboardLayout(configDir);
						} else {
							removed = runInstalledWrite(() -> {
								synchronized (ProfilesManager.presetSourceLock()) {
									boolean effectiveLayoutExists =
											ProfilesManager.hasRecoverableLocalKeyboardLayout(configDir);
									PresetLocalOverride.Guard ownership = null;
									if (effectiveLayoutExists) {
										ownership = PresetLocalOverride.detachBeforeWrite(
												ConfigActivity.this, configDir);
										if (!ownership.canWrite()) {
											return false;
										}
										boolean published =
												ProfilesManager.removeLocalKeyboardLayout(configDir);
										if (!published) restorePresetAssociation(ownership);
										return published;
									} else {
										return true;
									}
								}
							});
						}
						if (!removed) {
							ThemedToast.show(ConfigActivity.this, R.string.error, Toast.LENGTH_SHORT);
							return;
						}
						refreshProfileMatchCache();
						if (composeController != null) {
							composeController.update(createUiState());
						}
					} finally {
						operationRunning = false;
					}
				}

					@Override
					public void onSaveProfileDraft() {
						saveProfileDraft();
					}

					@Override
					public boolean hasUnsavedProfileChanges() {
						return isProfile && profileDraftDirty;
					}

					@Override
					public void onDiscardProfileChanges() {
						discardProfileChanges();
					}

				},
				getTitle() == null ? "" : getTitle().toString(),
				isProfile);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (isProfile && composeController != null) {
					composeController.requestBack();
				} else {
					finish();
				}
			}
		});
		controllerInputRouter = new ControllerInputRouter(this, new ControllerHostSink() {
			@Override
			public javax.microedition.lcdui.Canvas currentCanvas() {
				return null;
			}

			@Override
			public javax.microedition.lcdui.Displayable currentDisplayable() {
				return null;
			}

			@Override
			public ControllerHostTarget currentControllerTarget() {
				return new ControllerHostTarget("config-activity", controllerTargetGeneration);
			}

			@Override
			public boolean onHostCommand(@NonNull HostCommand command, boolean pressed) {
				if (composeController != null && composeController.isControllerModalActive()) {
					return composeController.handleHostCommand(command, pressed);
				}
				if (command == HostCommand.Back) {
					if (pressed) getOnBackPressedDispatcher().onBackPressed();
					return true;
				}
				return composeController != null && composeController.handleHostCommand(command, pressed);
			}

			@Override
			public void onControllerInputAccepted() {
			}

			@Override
			public void onControllerNotice(@NonNull String message) {
				Toast.makeText(ConfigActivity.this, message, Toast.LENGTH_SHORT).show();
			}

			@Override
			public void onControllerAvailabilityChanged(boolean available) {
				if (!available) cancelGamepadCalibration();
				if (composeController != null) composeController.update(createUiState());
			}

			@Override
			public boolean isControllerModalActive() {
				return composeController != null && composeController.isControllerModalActive();
			}

		}, null);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (controllerInputRouter != null && controllerInputRouter.onKeyEvent(event)) {
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		if ((gamepadCalibration != null || gamepadCalibrationPending)
				&& ControllerInputRouter.isGamepadMotionEvent(event)) {
			return handleGamepadCalibrationMotion(event);
		}
		if (controllerInputRouter != null && controllerInputRouter.onGenericMotionEvent(event)) {
			return true;
		}
		return super.dispatchGenericMotionEvent(event);
	}

	private void startGamepadCalibration() {
		finishGamepadCalibration();
		gamepadCalibrationPending = true;
		gamepadCalibrationDevice = null;
		gamepadCalibrationSignature = null;
		gamepadCalibrationChannels = Collections.emptySet();
		if (composeController != null) {
			composeController.showGamepadCalibration(new GamepadCalibrationUiState(
					"WAIT_NEUTRAL",
					getString(R.string.config_gamepad_calibration_waiting_controller),
					null,
					false,
					false));
		}
	}

	private void beginGamepadCalibration(@NonNull InputDevice device, int source) {
		Set<CalibrationChannel> channels = calibrationChannels(device, source);
		gamepadCalibrationDevice = device;
		gamepadCalibrationChannels = channels;
		gamepadCalibrationSignature = ControllerInputRouter.capabilitySignatureFor(device);
		if (channels.isEmpty()) {
			gamepadCalibrationPending = false;
			gamepadCalibration = null;
			publishGamepadCalibration(null,
					getString(R.string.config_gamepad_calibration_no_analog_ranges));
			return;
		}
		ControllerConfig current = currentControllerConfig();
		GamepadCalibration previous = current.getCalibrations().get(gamepadCalibrationSignature);
		gamepadCalibration = new GamepadCalibrationSession(
				previous,
				GamepadCalibrationSession.DEFAULT_NEUTRAL_DURATION_MILLIS,
				GamepadCalibrationSession.DEFAULT_MINIMUM_SAMPLES,
				GamepadCalibrationSession.DEFAULT_MINIMUM_SPAN,
				GamepadCalibrationSession.DEFAULT_MAXIMUM_REST_SPREAD,
				channels);
		gamepadCalibrationPending = false;
		getWindow().getDecorView().removeCallbacks(gamepadCalibrationTicker);
		getWindow().getDecorView().postDelayed(gamepadCalibrationTicker, 250L);
		publishGamepadCalibration(null,
				getString(R.string.config_gamepad_calibration_start));
	}

	private boolean handleGamepadCalibrationMotion(@NonNull MotionEvent event) {
		if (event.getActionMasked() != MotionEvent.ACTION_MOVE
				&& event.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE) {
			return true;
		}
		InputDevice device = InputDevice.getDevice(event.getDeviceId());
		if (device == null) return true;
		if (gamepadCalibrationPending || gamepadCalibration == null) {
			if (calibrationChannels(device, event.getSource()).isEmpty()) {
				return true;
			}
			beginGamepadCalibration(device, event.getSource());
		}
		GamepadCalibrationSession calibration = gamepadCalibration;
		if (calibration == null || gamepadCalibrationDevice == null
				|| gamepadCalibrationDevice.getId() != device.getId()) {
			return true;
		}
		Map<CalibrationChannel, Float> values = calibrationValues(event, device, event.getSource());
		if (values.isEmpty()) return true;
		CalibrationStep step;
		if (calibration.getPhase() == CalibrationPhase.WAIT_NEUTRAL) {
			step = calibration.observeNeutral(
					SystemClock.elapsedRealtime(), values,
					isCalibrationNeutral(values, device, event.getSource()));
		} else {
			step = calibration.observeRange(values);
		}
		publishGamepadCalibration(step, null);
		return true;
	}

	private void advanceGamepadCalibration() {
		GamepadCalibrationSession calibration = gamepadCalibration;
		if (calibration == null) return;
		CalibrationStep step;
		if (calibration.getPhase() == CalibrationPhase.STICK_RANGE) {
			step = calibration.finishSticks();
			if (step.getPhase() == CalibrationPhase.TRIGGER_RANGE
					&& !containsTriggerChannel(gamepadCalibrationChannels)) {
				step = calibration.finishTriggers();
			}
		} else if (calibration.getPhase() == CalibrationPhase.TRIGGER_RANGE) {
			step = calibration.finishTriggers();
		} else {
			return;
		}
		publishGamepadCalibration(step, null);
	}

	private void saveGamepadCalibration() {
		GamepadCalibrationSession calibration = gamepadCalibration;
		GamepadCalibration profile = calibration == null ? null : calibration.getCandidateProfile();
		if (calibration == null || calibration.getPhase() != CalibrationPhase.REVIEW
				|| profile == null || gamepadCalibrationSignature == null || currentForm == null) {
			return;
		}
		try {
			ControllerConfig next = currentControllerConfig().withCalibration(
					gamepadCalibrationSignature, profile);
			updateForm(currentForm.toBuilder().controller(next.toJson()).build());
			calibration.commit();
			finishGamepadCalibration();
		} catch (RuntimeException failure) {
			Log.e(TAG, "saveGamepadCalibration", failure);
			publishGamepadCalibration(
					new CalibrationStep(
							CalibrationEvent.INVALID,
							calibration.getPhase(),
							calibration.getLastValidation(),
							profile),
					getString(R.string.config_gamepad_calibration_save_failed));
		}
	}

	private void resetGamepadCalibration() {
		if (currentForm != null && gamepadCalibrationSignature != null) {
			try {
				ControllerConfig next = currentControllerConfig()
						.withoutCalibration(gamepadCalibrationSignature);
				updateForm(currentForm.toBuilder().controller(next.toJson()).build());
			} catch (RuntimeException failure) {
				Log.e(TAG, "resetGamepadCalibration", failure);
			}
		}
		startGamepadCalibration();
	}

	private void cancelGamepadCalibration() {
		if (gamepadCalibration != null) gamepadCalibration.cancel();
		finishGamepadCalibration();
	}

	private void finishGamepadCalibration() {
		getWindow().getDecorView().removeCallbacks(gamepadCalibrationTicker);
		gamepadCalibration = null;
		gamepadCalibrationPending = false;
		gamepadCalibrationDevice = null;
		gamepadCalibrationSignature = null;
		gamepadCalibrationChannels = Collections.emptySet();
		if (composeController != null) composeController.updateGamepadCalibration(null);
	}

	private void publishGamepadCalibration(@Nullable CalibrationStep step,
			@Nullable String overrideText) {
		if (composeController == null) return;
		if (gamepadCalibration == null) {
			composeController.showGamepadCalibration(new GamepadCalibrationUiState(
					"NO_ANALOG_RANGES",
					overrideText == null
							? getString(R.string.config_gamepad_calibration_no_supported_ranges)
							: overrideText,
					gamepadCalibrationSignature,
					false,
					false));
			return;
		}
		CalibrationPhase phase = gamepadCalibration.getPhase();
		String text = overrideText;
		if (text == null) {
			if (step != null && step.getValidation() != null
					&& !step.getValidation().getAccepted()) {
				text = getString(R.string.config_gamepad_calibration_rejected,
						localizedCalibrationIssue(step.getValidation().getIssue()));
			} else {
				text = calibrationInstructions(phase);
			}
		}
		boolean canAdvance = phase == CalibrationPhase.STICK_RANGE
				|| phase == CalibrationPhase.TRIGGER_RANGE;
		boolean canSave = phase == CalibrationPhase.REVIEW
				&& gamepadCalibration.getCandidateProfile() != null;
		composeController.updateGamepadCalibration(new GamepadCalibrationUiState(
				phase.name(), text, gamepadCalibrationSignature, canAdvance, canSave));
	}

	private String calibrationInstructions(@NonNull CalibrationPhase phase) {
		switch (phase) {
			case WAIT_NEUTRAL:
				return getString(R.string.config_gamepad_calibration_neutral_instructions);
			case STICK_RANGE:
				return getString(R.string.config_gamepad_calibration_stick_instructions);
			case TRIGGER_RANGE:
				return getString(R.string.config_gamepad_calibration_trigger_instructions);
			case REVIEW:
				return getString(R.string.config_gamepad_calibration_review_instructions);
			case COMMITTED:
				return getString(R.string.config_gamepad_calibration_saved);
			case CANCELLED:
				return getString(R.string.config_gamepad_calibration_cancelled);
			default:
				return getString(R.string.config_gamepad_calibration_waiting_samples);
		}
	}

	private ControllerConfig currentControllerConfig() {
		if (currentForm == null || currentForm.controller == null
				|| !currentForm.controller.isJsonObject()) {
			return ControllerConfig.defaultNavigation();
		}
		return ControllerConfig.parse(currentForm.controller.getAsJsonObject());
	}

	private String buildConfigGamepadDiagnosis() {
		ControllerConfig controller = currentControllerConfig();
		StringBuilder text = new StringBuilder();
		text.append(getString(R.string.config_gamepad_diagnosis_header)).append('\n');
		String enabled = controller.getEnabled()
				? getString(R.string.config_gamepad_status_enabled)
				: getString(R.string.config_gamepad_status_disabled);
		text.append(getString(R.string.config_gamepad_diagnosis_mode,
				enabled, localizedResolutionSource(controller.getResolutionSource())))
				.append('\n');
		if (controller.getNotice() != null) {
			text.append(getString(R.string.config_gamepad_diagnosis_notice,
					getString(R.string.config_gamepad_unsupported_summary)))
					.append('\n');
		}
		text.append(getString(R.string.config_gamepad_diagnosis_analog_settings)).append('\n');
		text.append("  ").append(getString(R.string.config_gamepad_diagnosis_left_stick))
				.append(": ").append(localizedStickMode(controller.getLeftStick().getMode())).append('\n');
		text.append("  ").append(getString(R.string.config_gamepad_diagnosis_right_stick))
				.append(": ").append(localizedStickMode(controller.getRightStick().getMode())).append('\n');
		text.append("  ").append(getString(R.string.config_gamepad_diagnosis_triggers))
				.append(": ").append(localizedStatus(controller.getTriggers().getEnabled())).append('\n');
		text.append("  ").append(getString(R.string.config_gamepad_diagnosis_pointer))
				.append(": ").append(localizedPointerMode(controller.getPointer().getMode())).append('\n');
		text.append(getString(R.string.config_gamepad_diagnosis_capabilities)).append('\n');
		boolean found = false;
		for (int deviceId : InputDevice.getDeviceIds()) {
			InputDevice device = InputDevice.getDevice(deviceId);
			if (device == null || !ControllerInputRouter.isGamepadDevice(device)) continue;
			found = true;
			text.append("  ").append(getString(R.string.config_gamepad_diagnosis_device,
					device.getName(), deviceId)).append('\n');
			text.append("    ").append(getString(R.string.config_gamepad_diagnosis_descriptor,
					device.getDescriptor())).append('\n');
			text.append("    ").append(getString(R.string.config_gamepad_diagnosis_signature,
					ControllerInputRouter.capabilitySignatureFor(device)))
					.append('\n');
			for (InputDevice.MotionRange range : device.getMotionRanges()) {
				if (!ControllerInputRouter.isControllerSource(range.getSource())) continue;
				text.append("    ").append(getString(R.string.config_gamepad_diagnosis_axis,
						range.getAxis(),
						String.format(java.util.Locale.US, "%.3f", range.getMin()),
						String.format(java.util.Locale.US, "%.3f", range.getMax()),
						String.format(java.util.Locale.US, "%.3f", range.getFlat()),
						String.format(java.util.Locale.US, "%.3f", range.getFuzz())))
						.append('\n');
			}
		}
		if (!found) text.append("  ").append(getString(
				R.string.config_gamepad_diagnosis_no_controller)).append('\n');
		text.append(getString(R.string.config_gamepad_diagnosis_samples_not_sent));
		return text.toString();
	}

	private String localizedStatus(boolean enabled) {
		return getString(enabled ? R.string.config_gamepad_status_enabled
				: R.string.config_gamepad_status_disabled);
	}

	@NonNull
	private String localizedResolutionSource(@NonNull ResolutionSource source) {
		switch (source) {
			case CONTROLLER:
				return getString(R.string.config_gamepad_diagnosis_source_controller);
			case EXPLICIT:
				return getString(R.string.config_gamepad_diagnosis_source_explicit);
			case DEFAULT:
			default:
				return getString(R.string.config_gamepad_diagnosis_source_default);
		}
	}

	@NonNull
	private String localizedStickMode(@NonNull StickMode mode) {
		switch (mode) {
			case POINTER:
				return getString(R.string.config_gamepad_stick_pointer);
			case UNASSIGNED:
				return getString(R.string.config_gamepad_stick_unassigned);
			case DIRECTIONS:
			default:
				return getString(R.string.config_gamepad_stick_directions);
		}
	}

	@NonNull
	private String localizedPointerMode(@NonNull PointerMode mode) {
		switch (mode) {
			case CURSOR:
				return getString(R.string.config_gamepad_pointer_cursor);
			case TOUCH_JOYSTICK:
				return getString(R.string.config_gamepad_pointer_joystick);
			case OFF:
			default:
				return getString(R.string.config_gamepad_pointer_off);
		}
	}

	@NonNull
	private String localizedCalibrationIssue(@NonNull StickProcessor.CalibrationIssue issue) {
		switch (issue) {
			case NO_CHANNELS:
				return getString(R.string.config_gamepad_calibration_issue_no_channels);
			case INVALID_CONSTRAINTS:
				return getString(R.string.config_gamepad_calibration_issue_invalid_constraints);
			case NON_FINITE:
				return getString(R.string.config_gamepad_calibration_issue_non_finite);
			case INSUFFICIENT_SAMPLES:
				return getString(R.string.config_gamepad_calibration_issue_insufficient_samples);
			case INVALID_RANGE:
				return getString(R.string.config_gamepad_calibration_issue_invalid_range);
			case REST_OUTSIDE_RANGE:
				return getString(R.string.config_gamepad_calibration_issue_rest_outside_range);
			case REST_TOO_NOISY:
				return getString(R.string.config_gamepad_calibration_issue_rest_too_noisy);
			case NONE:
			default:
				return getString(R.string.config_gamepad_calibration_phase_unknown);
		}
	}

	@NonNull
	private Set<CalibrationChannel> calibrationChannels(@NonNull InputDevice device, int source) {
		EnumSet<CalibrationChannel> channels = EnumSet.noneOf(CalibrationChannel.class);
		if (hasMotionRange(device, source, MotionEvent.AXIS_X)
				&& hasMotionRange(device, source, MotionEvent.AXIS_Y)) {
			channels.add(CalibrationChannel.LEFT_X);
			channels.add(CalibrationChannel.LEFT_Y);
		}
		boolean zPair = hasMotionRange(device, source, MotionEvent.AXIS_Z)
				&& hasMotionRange(device, source, MotionEvent.AXIS_RZ);
		if ((zPair || (hasMotionRange(device, source, MotionEvent.AXIS_RX)
				&& hasMotionRange(device, source, MotionEvent.AXIS_RY)))) {
			channels.add(CalibrationChannel.RIGHT_X);
			channels.add(CalibrationChannel.RIGHT_Y);
		}
		if (hasMotionRange(device, source, MotionEvent.AXIS_LTRIGGER)
				|| hasMotionRange(device, source, MotionEvent.AXIS_BRAKE)) {
			channels.add(CalibrationChannel.LEFT_TRIGGER);
		}
		if (hasMotionRange(device, source, MotionEvent.AXIS_RTRIGGER)
				|| hasMotionRange(device, source, MotionEvent.AXIS_GAS)) {
			channels.add(CalibrationChannel.RIGHT_TRIGGER);
		}
		return Collections.unmodifiableSet(channels);
	}

	private boolean containsTriggerChannel(@NonNull Set<CalibrationChannel> channels) {
		return channels.contains(CalibrationChannel.LEFT_TRIGGER)
				|| channels.contains(CalibrationChannel.RIGHT_TRIGGER);
	}

	@NonNull
	private Map<CalibrationChannel, Float> calibrationValues(
			@NonNull MotionEvent event, @NonNull InputDevice device, int source) {
		java.util.EnumMap<CalibrationChannel, Float> values =
				new java.util.EnumMap<>(CalibrationChannel.class);
		for (CalibrationChannel channel : gamepadCalibrationChannels) {
			int axis = calibrationAxis(device, source, channel);
			if (axis == -1) continue;
			float value = event.getAxisValue(axis);
			if (!Float.isNaN(value) && !Float.isInfinite(value)) values.put(channel, value);
		}
		return values;
	}

	private int calibrationAxis(@NonNull InputDevice device, int source,
			@NonNull CalibrationChannel channel) {
		switch (channel) {
			case LEFT_X: return MotionEvent.AXIS_X;
			case LEFT_Y: return MotionEvent.AXIS_Y;
			case RIGHT_X:
				return hasMotionRange(device, source, MotionEvent.AXIS_Z)
						&& hasMotionRange(device, source, MotionEvent.AXIS_RZ)
						? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;
			case RIGHT_Y:
				return hasMotionRange(device, source, MotionEvent.AXIS_Z)
						&& hasMotionRange(device, source, MotionEvent.AXIS_RZ)
						? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
			case LEFT_TRIGGER:
				return hasMotionRange(device, source, MotionEvent.AXIS_LTRIGGER)
						? MotionEvent.AXIS_LTRIGGER : MotionEvent.AXIS_BRAKE;
			case RIGHT_TRIGGER:
				return hasMotionRange(device, source, MotionEvent.AXIS_RTRIGGER)
						? MotionEvent.AXIS_RTRIGGER : MotionEvent.AXIS_GAS;
			default: return -1;
		}
	}

	private boolean hasMotionRange(@NonNull InputDevice device, int source, int axis) {
		return device.getMotionRange(axis, source) != null || device.getMotionRange(axis) != null;
	}

	private boolean hasControllerDevice() {
		for (int deviceId : InputDevice.getDeviceIds()) {
			InputDevice device = InputDevice.getDevice(deviceId);
			if (device != null && ControllerInputRouter.isGamepadDevice(device)) return true;
		}
		return false;
	}

	private int calibrationSource(@NonNull InputDevice device) {
		for (InputDevice.MotionRange range : device.getMotionRanges()) {
			if (range.getSource() != 0
					&& ControllerInputRouter.isControllerSource(range.getSource())) {
				return range.getSource();
			}
		}
		return device.getSources();
	}

	private boolean isCalibrationNeutral(@NonNull Map<CalibrationChannel, Float> values,
			@Nullable InputDevice device, int source) {
		if (device == null || values.size() < gamepadCalibrationChannels.size()) return false;
		for (Map.Entry<CalibrationChannel, Float> entry : values.entrySet()) {
			float value = entry.getValue();
			if (entry.getKey().isStick()) {
				int axis = calibrationAxis(device, source, entry.getKey());
				InputDevice.MotionRange range = device.getMotionRange(axis, source);
				if (range == null) range = device.getMotionRange(axis);
				float center = range != null && range.getMin() >= 0.0f
						? (range.getMin() + range.getMax()) / 2.0f : 0.0f;
				float tolerance = range == null ? 0.15f
						: Math.max(0.15f, (range.getMax() - range.getMin()) * 0.15f);
				if (Math.abs(value - center) > tolerance) return false;
			} else {
				int axis = calibrationAxis(device, source, entry.getKey());
				InputDevice.MotionRange range = device.getMotionRange(axis, source);
				if (range == null) range = device.getMotionRange(axis);
				float normalized = range == null || range.getMax() <= range.getMin()
						? 0.0f : (value - range.getMin()) / (range.getMax() - range.getMin());
				if (Math.max(0.0f, Math.min(1.0f, normalized)) > 0.40f) return false;
			}
		}
		return true;
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		if (!isProfile && expectedAppId > 0L) {
			outState.putLong(STATE_EXPECTED_APP_ID, expectedAppId);
		}
		if (isProfile && profileEditDraftDir != null) {
			outState.putString(STATE_PROFILE_DRAFT_PATH, profileEditDraftDir.getAbsolutePath());
			outState.putBoolean(STATE_PROFILE_DRAFT_DIRTY, profileDraftDirty);
			if (profileEditSession != null) {
				outState.putString(STATE_PROFILE_EDIT_MODE, profileEditSession.mode.name());
				outState.putString(STATE_PROFILE_EDIT_TOKEN, profileEditSession.token);
			}
		}
		super.onSaveInstanceState(outState);
	}

	private void initSkinOptions() {
		skinOptions.clear();
		File dir = new File(workDir + Config.SKINS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		skinOptions.add(getString(R.string.pref_skin_not_set));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			skinOptions.addAll(Arrays.asList(files));
		}
	}

	private void initSoundBankOptions() {
		soundBankOptions.clear();
		File dir = new File(workDir + Config.SOUNDBANKS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		soundBankOptions.add(getString(R.string.default_label, "Android"));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			soundBankOptions.addAll(Arrays.asList(files));
		}
	}

	boolean loadConfig() {
		return isProfile
				? loadConfigWithInstalledIdentity()
				: runInstalledWrite(this::loadConfigWithInstalledIdentity);
	}

	private boolean loadConfigWithInstalledIdentity() {
		if (!isProfile) {
			if (dataDir != null && !dataDir.isDirectory() && !dataDir.mkdirs()) return false;
			if (!configDir.isDirectory() && !configDir.mkdirs()) return false;
		}
		if (!isProfile) {
			if (!MidletConfigLoadBoundary.prepare(hostPreferences, configDir, profilesRoot)) {
				Log.e(TAG, "Refusing to load MIDlet config after preset snapshot recovery failure");
				return false;
			}
			refreshProfileOriginFromMetadata();
		}
		params = ProfilesManager.loadConfig(
				configDir,
				true,
				isProfile ? ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE
						: ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
				!isProfile && profileOrigin == null && readBuiltInThemeLinked());
		if (!isProfile && params != null) {
			persistedBaseline = ProfileConfigMatcher.copyConfig(params);
		}

		if (params == null) {
			if (isProfile) {
				params = newBuiltInProfile();
				builtInThemeLinked = false;
				return true;
			}
			if (!FreshInstalledMidletInitializer.publishBuiltIn(
					hostPreferences, configDir, isDarkTheme())) {
				Log.e(TAG, "Unable to materialize Built-in recovery for installed MIDlet");
				return false;
			}
			refreshProfileOriginFromMetadata();
			builtInThemeLinked = true;
			params = ProfilesManager.loadConfig(
					configDir,
					true,
					ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
					true);
			if (params == null) return false;
			persistedBaseline = ProfileConfigMatcher.copyConfig(params);
		}
		if (isProfile) {
			builtInThemeLinked = false;
			return true;
		}

		boolean linked = readBuiltInThemeLinked();
		if (profileOrigin != null) {
			linked = false;
		}
		setBuiltInThemeLinked(linked);
		if (builtInThemeLinked) {
			ProfileModel.applyBuiltInTheme(params, isDarkTheme());
		}
		return true;
	}

	private void showShaderSettings() {
		ShaderInfo shader = currentForm == null ? null : currentForm.shader;
		if (shader == null || !shader.hasTunableSettings()) {
			return;
		}
		ensureShaderValues(shader);
		params.shader = shader;
		ShaderTuneAlert.newInstance(shader).show(getSupportFragmentManager(), "ShaderTuning");
	}

	private void initShaderSpinner() {
		if (shaders != null) {
			return;
		}
		File dir = new File(workDir + Config.SHADERS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		shaders = new ArrayList<>();
		String[] files = dir.list();
		if (files != null) {
			for (String fileName : files) {
				if (!TextUtils.endsWithIgnoreCase(fileName, ".ini")) {
					continue;
				}
				File file = new File(dir, fileName);
				String text;
				try {
					//noinspection CharsetObjectCanBeUsed
					text = FilesKt.readText(file, Charset.forName("UTF-8"));
				} catch (Exception e) {
					Log.e(TAG, "getText: " + file, e);
					continue;
				}

				String[] split = text.split("[\\n\\r]+");
				ShaderInfo info = null;
				for (String line : split) {
					if (line.startsWith("[")) {
						if (info != null && info.fragment != null && info.vertex != null) {
							shaders.add(info);
						}
						info = new ShaderInfo(line.replaceAll("[\\[\\]]", ""), "unknown");
					} else if (info != null) {
						try {
							info.set(line);
						} catch (Exception e) {
							Log.e(TAG, "initShaderSpinner: ", e);
						}
					}
				}
				if (info != null && info.fragment != null && info.vertex != null) {
					shaders.add(info);
				}
			}
			Collections.sort(shaders);
		}
		shaders.add(0, new ShaderInfo(getString(R.string.identity_filter), "woesss"));
		ShaderInfo selected = params.shader;
		if (selected != null) {
			int position = shaders.indexOf(selected);
			if (position > 0) {
				shaders.get(position).values = selected.values;
			}
		}
	}

	private void showCharsetPicker() {
		if (composeController == null) {
			return;
		}
		String[] charsets = Charset.availableCharsets().keySet().toArray(new String[0]);
		String selected = null;
		if (currentForm != null) {
			String key = "microedition.encoding:";
			for (String line : currentForm.systemProperties.split("[\\n\\r]+")) {
				if (line.startsWith(key)) {
					selected = line.substring(key.length()).trim();
					break;
				}
			}
		}
		composeController.showEncodingPicker(Arrays.asList(charsets), selected);
	}

	private void applyCharset(@NonNull String charset) {
		if (currentForm == null) {
			return;
		}
		String text = currentForm.systemProperties;
		String key = "microedition.encoding:";
		int idx = text.lastIndexOf(key);
		if (idx != -1) {
			int nl = text.indexOf('\n', idx);
			text = text.substring(0, idx + key.length()) + " " + charset
					+ (nl == -1 ? "\n" : text.substring(nl));
			updateForm(currentForm.toBuilder().systemProperties(text).build());
			return;
		}

		if (!text.endsWith("\n")) {
			text += "\n";
		}
		updateForm(currentForm.toBuilder().systemProperties(
				text + key + " " + charset + "\n").build());
	}

	@Override
	public void onPause() {
		cancelGamepadCalibration();
		if (controllerInputRouter != null) {
			controllerInputRouter.clear();
			controllerTargetGeneration = controllerTargetGeneration == Long.MAX_VALUE
					? 1L : controllerTargetGeneration + 1L;
		}
		if (needShow && configDir != null && !operationRunning) {
			saveParams();
		}
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (needShow) {
			loadParams(true);
			if (isProfile && profileEditorResumeObserved) {
				// A return from a native editor may have changed the isolated draft. Keeping the
				// editor dirty is safer than committing that writer's result implicitly.
				profileDraftDirty = true;
			}
			profileEditorResumeObserved = true;
		}
	}

	@Override
	protected void onDestroy() {
		if (controllerInputRouter != null) {
			controllerInputRouter.close();
			controllerInputRouter = null;
		}
		getWindow().getDecorView().removeCallbacks(gamepadCalibrationTicker);
		profileMetadataExecutor.shutdownNow();
		if (isProfile && !isChangingConfigurations()) {
			ProfilesManager.releasePresetEditSession(profileEditSession);
			if (profileEditDraftDir != null) FileUtils.deleteDirectory(profileEditDraftDir);
			profileEditDraftDir = null;
		}
		if (hostPreferences != null) {
			hostPreferences.unregisterOnSharedPreferenceChangeListener(hostThemeListener);
			hostPreferences = null;
		}
		super.onDestroy();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus && controllerInputRouter != null) {
			controllerInputRouter.clear();
			controllerTargetGeneration = controllerTargetGeneration == Long.MAX_VALUE
					? 1L : controllerTargetGeneration + 1L;
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (configDir != null) {
			syncLinkedBuiltInTheme();
			builtInDefaultParams = newBuiltInProfile();
		}
		if (display != null) {
			fillScreenSizePresets(display.getWidth(), display.getHeight());
			if (composeController != null) {
				composeController.update(createUiState());
			}
		}
	}

	private void fillScreenSizePresets(int w, int h) {
		ArrayList<Size> screenPresets = this.screenPresets;
		screenPresets.clear();
		removableScreenPresets.clear();

		screenPresets.add(new Size(128, 128));
		screenPresets.add(new Size(128, 160));
		screenPresets.add(new Size(132, 176));
		screenPresets.add(new Size(176, 220));
		screenPresets.add(new Size(240, 320));
		screenPresets.add(new Size(352, 416));
		screenPresets.add(new Size(640, 360));
		screenPresets.add(new Size(800, 480));

		if (w > h) {
			screenPresets.add(new Size(h * 3 / 4, h));
			screenPresets.add(new Size(h * 4 / 3, h));
		} else {
			screenPresets.add(new Size(w, w * 4 / 3));
			screenPresets.add(new Size(w, w * 3 / 4));
		}

		screenPresets.add(new Size(w, h));
		Set<String> preset = PreferenceManager.getDefaultSharedPreferences(this)
				.getStringSet("ResolutionsPreset", null);
		if (preset != null) {
			for (String s : preset) {
				Size size = Size.parse(s);
				if (size != null) {
					screenPresets.add(size);
					if (!removableScreenPresets.contains(size)) {
						removableScreenPresets.add(size);
					}
				}
			}
		}
		Collections.sort(screenPresets);
		Collections.sort(removableScreenPresets);
		Size prev = null;
		for (Iterator<Size> iterator = screenPresets.iterator(); iterator.hasNext(); ) {
			Size next = iterator.next();
			if (next.equals(prev)) iterator.remove();
			else prev = next;
		}
	}

	private void addFontSizePreset(String title, int small, int medium, int large) {
		fontPresets.add(new ConfigUiState.FontPreset(title, small, medium, large));
	}

	public void loadParams(boolean reloadFromFile) {
		loadParams(reloadFromFile, false);
	}

	private void loadParams(boolean reloadFromFile, boolean identityHeld) {
		if (reloadFromFile && !(identityHeld
				? loadConfigWithInstalledIdentity()
				: loadConfig())) {
			Log.e(TAG, "Keeping current in-memory params because persisted snapshot is unsafe");
			return;
		}
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	boolean saveParams() {
		return isProfile
				? saveParamsWithInstalledIdentity()
				: runInstalledWrite(this::saveParamsWithInstalledIdentity);
	}

	private boolean saveParamsWithInstalledIdentity() {
		try {
			if (isProfile) {
				if (currentForm != null) currentForm.applyTo(params);
				return ProfilesManager.saveConfig(params);
			}

			synchronized (ProfilesManager.presetSourceLock()) {
				ProfileModel candidate = currentForm == null
						? ProfileConfigMatcher.copyConfig(params)
						: ProfileConfigMatcher.effectiveConfig(params, currentForm);
				boolean divergent = hasEffectiveDraftDivergence(params, currentForm, persistedBaseline);
				PresetLocalOverride.Guard ownership = null;
				if (divergent) {
					ownership = PresetLocalOverride.detachBeforeWrite(this, configDir);
					if (!ownership.canWrite()) {
						Log.e(TAG, "Unable to durably detach linked preset before config save");
						return false;
					}
				}

				boolean builtInDetachRequired = shouldDetachBuiltInThemeLink(
						builtInThemeLinked, false, params, currentForm, builtInDefaultParams);
				boolean saved = persistConfigAfterBuiltInOwnershipBarrier(
						builtInDetachRequired,
						this::reconcileBuiltInThemeLink,
						() -> {
							params = candidate;
							return ProfilesManager.saveConfig(params);
						},
						() -> setBuiltInThemeLinked(true));
				if (saved) {
					persistedBaseline = ProfileConfigMatcher.copyConfig(params);
					return true;
				}
				if (ownership != null) ownership.restoreIfUnchanged();
				return false;
			}
		} catch (Throwable t) {
			Log.e(TAG, "saveParams", t);
			return false;
		}
	}

	private boolean runInstalledWrite(@NonNull BooleanOperation operation) {
		InstalledAppWriteGuard guard = installedWriteGuard;
		if (isProfile) return operation.run();
		if (guard == null || appDir == null || expectedAppId <= 0L) return false;
		InstalledAppWriteGuard.Result result = guard.run(
				appDir.getPath(), expectedAppId, operation::run);
		if (result == InstalledAppWriteGuard.Result.STALE) {
			ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
			finish();
		}
		return result == InstalledAppWriteGuard.Result.SUCCESS;
	}

	@Nullable
	static ProfilesManager.ProfileEditMode parseProfileEditMode(@Nullable String value) {
		if (value == null) return null;
		try {
			return ProfilesManager.ProfileEditMode.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private File createProfileEditDraft(@NonNull File target) throws IOException {
		File cache = getCacheDir();
		if (!cache.isDirectory() && !cache.mkdirs()) {
			throw new IOException("Unable to create preset editor cache");
		}
		File draft;
		do {
			draft = new File(cache, ".preset-edit-" + UUID.randomUUID());
		} while (draft.exists());
		if (!draft.mkdirs()) {
			throw new IOException("Unable to create preset editor draft");
		}
		try {
			profileEditSession = ProfilesManager.beginPresetEditSession(target, draft);
			return draft;
		} catch (IOException | RuntimeException failure) {
			FileUtils.clearDirectory(draft);
			draft.delete();
			throw failure;
		}
	}

	private void handleBackRequest() {
		finish();
	}

	private void saveProfileDraft() {
		if (!isProfile || operationRunning || profileEditSession == null
				|| profileEditDraftDir == null) {
			return;
		}
		operationRunning = true;
		try {
			if (!saveParams()) throw new IOException("Unable to save preset editor draft");
			ProfilesManager.saveEditedSnapshot(profileEditSession, profileEditDraftDir);
			profileDraftDirty = false;
			setResult(RESULT_OK, new Intent().setData(getIntent().getData()));
			finish();
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "saveProfileDraft", e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
		} finally {
			operationRunning = false;
		}
	}

	private void discardProfileChanges() {
		if (!isProfile) {
			finish();
			return;
		}
		profileDraftDirty = false;
		finish();
	}

	private void startMIDlet() {
		if (needShow && configDir != null) {
			boolean saved = isProfile
					? saveParams()
					: runInstalledWrite(() -> saveParamsWithInstalledIdentity()
							&& FreshInstalledMidletInitializer.markReviewed(configDir));
			if (!saved) {
				ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
				return;
			}
		}
		boolean reconversionShown = Config.startApp(
				this,
				getIntent().getStringExtra(KEY_MIDLET_NAME),
				getIntent().getData(),
				expectedAppId);
		if (!reconversionShown) finish();
	}

	private void openKeyMappings() {
		Intent i = new Intent(getIntent().getAction(), Uri.parse(configDir.getPath()),
				this, KeyMapperActivity.class);
		if (!isProfile) {
			i.putExtra(KEY_LIBRARY_APP_ID, expectedAppId);
			i.putExtra(KEY_INSTALLED_APP_PATH, appDir.getPath());
		}
		startActivity(i);
	}

	private boolean applyBuiltInTemplate(@NonNull ConfigFormEvents.PresetApplyScope scope) {
		if (operationRunning) return false;
		if (scope != ConfigFormEvents.PresetApplyScope.SETTINGS) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		operationRunning = true;
		try {
			if (isProfile) {
				ProfileModel previousParams = params == null
						? null : ProfileConfigMatcher.copyConfig(params);
				ConfigFormState previousForm = currentForm;
				boolean previousBuiltInThemeLinked = builtInThemeLinked;
				params = newBuiltInProfile();
				currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
				if (!saveParams()) {
					if (previousParams != null) params = previousParams;
					currentForm = previousForm;
					builtInThemeLinked = previousBuiltInThemeLinked;
					ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
					return false;
				}
				builtInThemeLinked = true;
				if (!setProfileOrigin(null)) {
					Log.e(TAG, "Unable to clear preset editor provenance");
				}
			} else if (!replaceActiveConfigWithBuiltIn()) {
				ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
				return false;
			}
			refreshProfileMatchCache();
			if (composeController != null) {
				composeController.update(createUiState());
			}
			return true;
		} catch (RuntimeException e) {
			Log.e(TAG, "applyBuiltInTemplate", e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		} finally {
			operationRunning = false;
		}
	}

	private boolean replaceActiveConfigWithBuiltIn() {
		return runInstalledWrite(this::replaceActiveConfigWithBuiltInUnderIdentity);
	}

	private boolean replaceActiveConfigWithBuiltInUnderIdentity() {
		ProfileModel previousParams = params == null ? null : ProfileConfigMatcher.copyConfig(params);
		ConfigFormState previousForm = currentForm;
		synchronized (ProfilesManager.presetSourceLock()) {
			PresetSourceReplacement.Guard ownership =
					PresetSourceReplacement.begin(hostPreferences, configDir);
			if (!ownership.canWrite()) {
				return false;
			}
			profileOrigin = null;
			builtInThemeLinked = false;
			params = newBuiltInProfile();
			currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
			if (!saveParamsWithInstalledIdentity()) {
				if (previousParams != null) params = previousParams;
				currentForm = previousForm;
				restoreSourceOwnership(ownership);
				return false;
			}
			if (!ownership.publishBuiltInOwnership()) {
				builtInThemeLinked = false;
				return false;
			}
			builtInThemeLinked = true;
			return true;
		}
	}

	private ProfileModel newBuiltInProfile() {
		return ProfileModel.createBuiltIn(configDir, isDarkTheme());
	}

	/** Re-derives theme-owned built-in colors without turning the profile into a custom snapshot. */
	private void syncLinkedBuiltInTheme() {
		if (isProfile || !builtInThemeLinked || params == null || configDir == null) return;
		if (currentForm != null && builtInDefaultParams != null
				&& !ProfileConfigMatcher.sameEffectiveConfig(params, currentForm, builtInDefaultParams)) {
			// The durable ownership remains built-in until persistence, but an unsaved custom draft
			// must not be overwritten by an unrelated host-theme refresh. Still advance the built-in
			// comparison baseline so a later save is judged against the current host theme.
			builtInDefaultParams = newBuiltInProfile();
			return;
		}
		ProfileModel.applyBuiltInTheme(params, isDarkTheme());
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		builtInDefaultParams = newBuiltInProfile();
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private boolean isDarkTheme() {
		return ProfileModel.isDarkTheme(this);
	}

	@NonNull
	File getProfilesRoot() {
		return profilesRoot;
	}

	private boolean isCurrentProfilesRoot() {
		try {
			return profilesRoot.getCanonicalFile()
					.equals(new File(Config.getProfilesDir()).getCanonicalFile());
		} catch (IOException | RuntimeException failure) {
			return false;
		}
	}

	private boolean applyTemplate(@NonNull String name,
			@NonNull ConfigFormEvents.PresetApplyScope scope) {
		if (operationRunning) return false;

		if (isProfile) {
			boolean applySettings = scope != ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT;
			boolean applyKeyboard = scope != ConfigFormEvents.PresetApplyScope.SETTINGS;
			operationRunning = true;
			try {
				File sourceDir = ProfilesManager.findProfileDirectory(profilesRoot, name);
				Profile profile = sourceDir == null ? null : new Profile(sourceDir.getName());
				ProfilesManager.ProfileInfo inspected = profile == null
						? null : ProfilesManager.inspectProfile(profile, sourceDir);
				if (inspected == null
						|| (applySettings && !inspected.settings.isReady())
						|| (applyKeyboard && !inspected.keyboardLayout.isReady())) {
					ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
					return false;
				}
				ProfilesManager.load(sourceDir, configDir, applySettings, applyKeyboard, null);
				boolean sourceHasKeyboardArtifact =
						inspected.keyboardLayout.status != ProfilesManager.CapabilityStatus.ABSENT;
				boolean appliesCompleteSource =
						scope == ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD
								|| (scope == ConfigFormEvents.PresetApplyScope.SETTINGS
								&& !sourceHasKeyboardArtifact);
				if (!setProfileOrigin(appliesCompleteSource ? profile.getName() : null)) {
					Log.e(TAG, "Unable to update preset editor provenance");
				}
				setBuiltInThemeLinked(false);
				loadParams(true);
				return true;
			} catch (IOException | RuntimeException failure) {
				Log.e(TAG, "applyTemplate: " + name, failure);
				ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
				return false;
			} finally {
				operationRunning = false;
			}
		}

		operationRunning = true;
		try {
			return runInstalledWrite(() -> applyTemplateWithInstalledIdentity(name, scope));
		} finally {
			operationRunning = false;
		}
	}

	private boolean applyTemplateWithInstalledIdentity(@NonNull String name,
			@NonNull ConfigFormEvents.PresetApplyScope scope) {
		try {
			LinkedPresetActivation.Result activation = null;
			boolean partialApplied = false;
			boolean sourceReady = false;
			synchronized (ProfilesManager.presetSourceLock()) {
				File sourceDir = ProfilesManager.findProfileDirectory(profilesRoot, name);
				Profile currentProfile = sourceDir == null ? null : new Profile(sourceDir.getName());
				ProfilesManager.ProfileInfo currentInfo = currentProfile == null
						? null : ProfilesManager.inspectProfile(currentProfile, sourceDir);
				if (currentInfo != null) {
					if (isCompletePresetCandidate(currentInfo, scope)) {
						sourceReady = true;
						activation = LinkedPresetActivation.activate(
								hostPreferences, configDir, sourceDir,
								currentProfile.getName());
					} else {
						boolean applySettings =
								scope != ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT;
						boolean applyKeyboard =
								scope != ConfigFormEvents.PresetApplyScope.SETTINGS;
						sourceReady = (!applySettings || currentInfo.settings.isReady())
								&& (!applyKeyboard || currentInfo.keyboardLayout.isReady());
						if (sourceReady) {
							PresetSourceReplacement.Guard ownership =
									PresetSourceReplacement.begin(hostPreferences, configDir);
							if (ownership.canWrite()) {
								profileOrigin = null;
								builtInThemeLinked = false;
								ProfilesManager.load(sourceDir, configDir,
										applySettings, applyKeyboard, null);
								partialApplied = true;
							} else {
								sourceReady = false;
							}
						}
					}
				}
			}
			if (!sourceReady) {
				ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
				return false;
			}
			if (activation != null) {
				refreshProfileOriginFromMetadata();
				builtInThemeLinked = readBuiltInThemeLinked();
				switch (activation) {
					case LINKED:
					case APPLIED_CUSTOM:
						builtInThemeLinked = false;
						loadParams(true, true);
						return true;
					case FAILED_SAFE:
						if (composeController != null) composeController.update(createUiState());
						ThemedToast.show(this, R.string.profile_template_operation_failed,
								Toast.LENGTH_SHORT);
						return false;
					case FAILED_UNSAFE:
						Log.e(TAG, "Preset activation left an unsafe local snapshot: " + name);
						if (composeController != null) composeController.update(createUiState());
						ThemedToast.show(this, R.string.profile_template_operation_failed,
								Toast.LENGTH_SHORT);
						return false;
				}
			}
			if (partialApplied) {
				loadParams(true, true);
				return true;
			}
			return false;
		} catch (IOException | RuntimeException failure) {
			// Once a partial replacement may have published, CUSTOM is the conservative state.
			Log.e(TAG, "applyTemplate: " + name, failure);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
	}

	private boolean saveTemplate(@NonNull String rawName) {
		String name = rawName.trim();
		if (!Profile.isValidName(name)) {
			ThemedToast.show(this, R.string.preset_invalid_name, Toast.LENGTH_SHORT);
			return false;
		}
		if (operationRunning) return false;
		// Keep the cheap UI-time duplicate feedback, but publication rechecks under PRESET_SOURCE_LOCK.
		if (ProfilesManager.profileNameExists(profilesRoot, name)) {
			ThemedToast.show(this, R.string.profile_name_exists, Toast.LENGTH_SHORT);
			return false;
		}
		operationRunning = true;
		try {
			return isProfile
					? saveTemplateWithInstalledIdentity(name)
					: runInstalledWrite(() -> saveTemplateWithInstalledIdentity(name));
		} finally {
			operationRunning = false;
		}
	}

	private boolean updatePreset(@NonNull String name) {
		if (operationRunning || isProfile) return false;
		operationRunning = true;
		try {
			return runInstalledWrite(() -> updatePresetWithInstalledIdentity(name));
		} finally {
			operationRunning = false;
		}
	}

	private boolean saveTemplateWithInstalledIdentity(@NonNull String name) {
		if (!saveParamsWithInstalledIdentity()) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		PresetSourceSave.Result result = PresetSourceSave.saveAsNew(
				hostPreferences, configDir, profilesRoot, name);
		return finishPresetSourceSave(name, result);
	}

	private boolean updatePresetWithInstalledIdentity(@NonNull String name) {
		// Persist the current draft first. A successful local edit is never rolled back because
		// a later named-source update fails.
		if (!saveParamsWithInstalledIdentity()) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		PresetSourceSave.Result result = PresetSourceSave.updateExisting(
				hostPreferences, configDir, profilesRoot, name);
		return finishPresetSourceSave(name, result);
	}

	private boolean finishPresetSourceSave(
			@NonNull String name, @NonNull PresetSourceSave.Result result) {
		refreshProfileOriginFromMetadata();
		builtInThemeLinked = readBuiltInThemeLinked();
		refreshProfileMatchCache();
		if (composeController != null) composeController.update(createUiState());
		if (result == PresetSourceSave.Result.FAILED) {
			Log.e(TAG, "Whole-device preset save failed: " + name);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		if (result == PresetSourceSave.Result.SAVED_UNLINKED) {
			ThemedToast.show(this, R.string.preset_saved_unlinked_warning, Toast.LENGTH_LONG);
		}
		return true;
	}

	private String builtInThemeKey() {
		return ProfileModel.builtInThemePreferenceKey(configDir);
	}

	private boolean readBuiltInThemeLinked() {
		if (isProfile || configDir == null) return false;
		return PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(builtInThemeKey(), false);
	}

	private boolean setBuiltInThemeLinked(boolean linked) {
		if (isProfile) {
			builtInThemeLinked = linked;
			return true;
		}
		if (configDir == null || hostPreferences == null) {
			return false;
		}
		if (!commitBuiltInThemeOwnership(hostPreferences, configDir, linked)) {
			return false;
		}
		builtInThemeLinked = linked;
		return true;
	}

	static boolean commitBuiltInThemeOwnership(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir,
			boolean linked) {
		SharedPreferences.Editor editor = preferences.edit();
		String key = ProfileModel.builtInThemePreferenceKey(configDir);
		if (linked) editor.putBoolean(key, true);
		else editor.remove(key);
		return editor.commit();
	}

	static boolean persistConfigAfterBuiltInOwnershipBarrier(
			boolean detachRequired,
			@NonNull BooleanOperation detachOwnership,
			@NonNull BooleanOperation writeConfig,
			@NonNull BooleanOperation restoreOwnership) {
		if (detachRequired && !detachOwnership.run()) {
			return false;
		}
		boolean saved = writeConfig.run();
		if (!saved && detachRequired) {
			restoreOwnership.run();
		}
		return saved;
	}

	private boolean setProfileOrigin(@Nullable String name) {
		if (isProfile || configDir == null || presetLinkage == null) {
			profileOrigin = name;
			return true;
		}
		if (name != null && !setBuiltInThemeLinked(false)) {
			return false;
		}
		boolean committed = name != null ? presetLinkage.setOrigin(name) : presetLinkage.clear();
		if (!committed) {
			return false;
		}
		profileOrigin = name;
		return true;
	}

	private void restorePresetAssociation(@Nullable PresetLocalOverride.Guard ownership) {
		if (ownership == null) return;
		if (ownership.restoreIfUnchanged()) {
			profileOrigin = ownership.previousOrigin();
		} else {
			profileOrigin = null;
		}
	}

	private void restoreSourceOwnership(@Nullable PresetSourceReplacement.Guard ownership) {
		if (ownership == null) return;
		if (ownership.restoreIfUnchanged()) {
			profileOrigin = ownership.previousOrigin();
			builtInThemeLinked = ownership.wasBuiltInThemeLinked();
		} else {
			profileOrigin = null;
			builtInThemeLinked = false;
		}
	}

	private void showColorPicker(ColorField field) {
		if (composeController != null && currentForm != null
				&& (field != ColorField.SCREEN_BACKGROUND
				|| currentForm.screenBackgroundMode == BackgroundMode.CUSTOM)) {
			composeController.showColorPicker(field, colorValue(currentForm, field));
		}
	}

	private void addResolutionToPresets(@NonNull Size size) {
		if (size.width <= 0 || size.height <= 0) {
			ThemedToast.show(this, R.string.invalid_resolution_not_saved, Toast.LENGTH_SHORT);
			return;
		}
		int index = Collections.binarySearch(screenPresets, size);
		if (index >= 0) {
			ThemedToast.show(this, R.string.not_saved_exists, Toast.LENGTH_SHORT);
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		Set<String> set = preferences.getStringSet("ResolutionsPreset", null);
		Set<String> presets = set == null ? new HashSet<>(1) : new HashSet<>(set);
		presets.add(size.toString());
		preferences.edit().putStringSet("ResolutionsPreset", presets).apply();
		fillScreenSizePresets(display.getWidth(), display.getHeight());
		if (composeController != null) {
			composeController.update(createUiState());
		}
		ThemedToast.show(this, getString(R.string.saved, size.toString()), Toast.LENGTH_SHORT);
	}

	private void removeResolutionPreset(Size size) {
		if (!removableScreenPresets.contains(size)) {
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		Set<String> set = preferences.getStringSet("ResolutionsPreset", null);
		if (set == null || !set.contains(size.toString())) {
			return;
		}
		Set<String> presets = new HashSet<>(set);
		presets.remove(size.toString());
		SharedPreferences.Editor editor = preferences.edit();
		if (presets.isEmpty()) {
			editor.remove("ResolutionsPreset");
		} else {
			editor.putStringSet("ResolutionsPreset", presets).apply();
		}
		if (presets.isEmpty()) {
			editor.apply();
		}
		fillScreenSizePresets(display.getWidth(), display.getHeight());
		if (composeController != null) {
			composeController.update(createUiState());
		}
		ThemedToast.show(this, getString(R.string.removed, size.toString()), Toast.LENGTH_SHORT);
	}

	@Override
	public void onTuneComplete(float[] values) {
		if (params.shader != null) {
			params.shader.values = values;
		}
		if (currentForm != null && currentForm.shader != null) {
			if (isProfile) profileDraftDirty = true;
			currentForm.shader.values = values;
			if (composeController != null) {
				composeController.update(createUiState());
			}
		}
	}

	private void refreshProfileMatchCache() {
		if (isProfile) {
			inspectedProfiles = Collections.emptyList();
			profileNames = Collections.emptyList();
			cachedDefaultProfileName = null;
			return;
		}
		final int generation = ++profileCacheGeneration;
		final File boundProfilesRoot = profilesRoot;
		final boolean showGlobalDefault = isCurrentProfilesRoot();
		final String configuredDefault = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(PREF_DEFAULT_PROFILE, null);
		profileMetadataExecutor.execute(() -> {
			ArrayList<Profile> profiles = ProfilesManager.getList(boundProfilesRoot);
			Collections.sort(profiles);
			List<ProfilesManager.ProfileInfo> inspected =
					ProfilesManager.inspectProfiles(boundProfilesRoot, profiles);
			String defaultName = null;
			if (showGlobalDefault && configuredDefault != null) {
				for (ProfilesManager.ProfileInfo info : inspected) {
					if (configuredDefault.equals(info.profile.getName())
							&& info.completeSnapshotReady) {
						defaultName = configuredDefault;
						break;
					}
				}
			}
			ArrayList<String> names = new ArrayList<>(profiles.size());
			for (Profile profile : profiles) names.add(profile.getName());
			final String resolvedDefaultName = defaultName;
			runOnUiThread(() -> {
				if (generation != profileCacheGeneration || isFinishing() || isDestroyed()) return;
				inspectedProfiles = inspected;
				profileNames = names;
				cachedDefaultProfileName = resolvedDefaultName;
				if (composeController != null) composeController.update(createUiState());
			});
		});
	}

	private ConfigUiState createUiState() {
		ConfigFormState state = currentForm == null
				? ConfigFormState.fromProfile(params, normalizedSystemProperties())
				: currentForm;
		String defaultProfile = isProfile || !isCurrentProfilesRoot()
				? null : cachedDefaultProfileName;
		boolean namedLinked = !isProfile && presetLinkage != null && presetLinkage.isLinked();
		boolean originExists = profileOrigin != null && profileNames.contains(profileOrigin);
		ConfigUiState.ProfileStatus profileStatus = resolveNamedPresetStatus(
				namedLinked, profileOrigin, originExists, defaultProfile);
		if (profileStatus == null && !isProfile && builtInThemeLinked && builtInDefaultParams != null
				&& ProfileConfigMatcher.sameEffectiveConfig(params, state, builtInDefaultParams)) {
			profileStatus = ConfigUiState.ProfileStatus.builtInDefault(defaultProfile);
		} else if (profileStatus == null) {
			profileStatus = ConfigUiState.ProfileStatus.custom(defaultProfile);
		}
		boolean draftDiverged = !isProfile
				&& hasEffectiveDraftDivergence(params, state, persistedBaseline);
		String updatePresetName = resolveUpdatePresetName(
				isProfile, profileStatus, originExists, draftDiverged);
		ArrayList<ConfigUiState.ProfileTemplate> templates = new ArrayList<>();
		for (ProfilesManager.ProfileInfo info : inspectedProfiles) {
			if (!info.settings.isReady() || info.config == null) continue;
			String name = info.profile.getName();
			templates.add(new ConfigUiState.ProfileTemplate(
				name,
				name.equals(defaultProfile),
				info.keyboardLayout.isReady(),
				info.keyboardLayout.status == ProfilesManager.CapabilityStatus.UNAVAILABLE,
				info.config.screenWidth,
				info.config.screenHeight,
				info.config.orientation));
		}
		return new ConfigUiState(state, screenPresets, fontPresets, skinOptions, soundBankOptions,
				shaders == null ? Collections.emptyList() : shaders, removableScreenPresets,
				profileStatus, templates, isProfile || hasCompatibleTimingTransform(),
				profileNames, hasControllerDevice(), updatePresetName);
	}

	private boolean hasCompatibleTimingTransform() {
		if (appDir == null) {
			return false;
		}
		try {
			Descriptor descriptor = new Descriptor(
					new File(appDir, Config.MIDLET_MANIFEST_FILE), false);
			return MidletTransformMetadata.isCompatible(descriptor.getAttrs());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	static boolean isCompletePresetCandidate(
			@NonNull ProfilesManager.ProfileInfo inspected,
			@NonNull ConfigFormEvents.PresetApplyScope scope) {
		boolean ownsKeyboardArtifact =
				inspected.keyboardLayout.status != ProfilesManager.CapabilityStatus.ABSENT;
		return ownsKeyboardArtifact
				? scope == ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD
				: scope == ConfigFormEvents.PresetApplyScope.SETTINGS;
	}

	static boolean hasEffectiveDraftDivergence(
			@Nullable ProfileModel current,
			@Nullable ConfigFormState draft,
			@Nullable ProfileModel persisted) {
		if (current == null) return false;
		ProfileModel effective = draft == null
				? ProfileConfigMatcher.copyConfig(current)
				: ProfileConfigMatcher.effectiveConfig(current, draft);
		return persisted == null || !ProfileConfigMatcher.sameConfig(effective, persisted);
	}

	@Nullable
	static String resolveUpdatePresetName(
			boolean profileEditor,
			@NonNull ConfigUiState.ProfileStatus status,
			boolean sourceExists,
			boolean draftDiverged) {
		if (profileEditor || !sourceExists || status.sourceProfile == null) return null;
		if (status.modified) return status.sourceProfile;
		if (status.activeProfile != null && draftDiverged) return status.activeProfile;
		return null;
	}

	@Nullable
	static ConfigUiState.ProfileStatus resolveNamedPresetStatus(
			boolean linked,
			@Nullable String origin,
			boolean sourceExists,
			@Nullable String defaultProfile) {
		if (linked && origin != null) {
			return ConfigUiState.ProfileStatus.active(origin, defaultProfile);
		}
		if (origin != null && sourceExists) {
			return ConfigUiState.ProfileStatus.modified(origin, defaultProfile);
		}
		return null;
	}

	private void refreshProfileOriginFromMetadata() {
		profileOrigin = profileOriginFromMetadata(hostPreferences, configDir);
	}

	@Nullable
	static String profileOriginFromMetadata(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		return new PresetLinkage(preferences, configDir).getOrigin();
	}

	private String normalizedSystemProperties() {
		String systemProperties = params == null ? null : params.systemProperties;
		if (systemProperties == null) {
			systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
		}
		return ConfigFormState.normalizeSystemProperties(systemProperties);
	}

	private void updateForm(ConfigFormState state) {
		currentForm = state;
		if (state.screenBackgroundMode != BackgroundMode.CUSTOM && composeController != null) {
			composeController.dismissColorPicker(ColorField.SCREEN_BACKGROUND);
		}
		if (isProfile) profileDraftDirty = true;
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private boolean reconcileBuiltInThemeLink() {
		if (!shouldDetachBuiltInThemeLink(
				builtInThemeLinked, isProfile, params, currentForm, builtInDefaultParams)) {
			return true;
		}
		return setBuiltInThemeLinked(false);
	}

	static boolean shouldDetachBuiltInThemeLink(
			boolean builtInThemeLinked,
			boolean profileEditor,
			@Nullable ProfileModel current,
			@Nullable ConfigFormState draft,
			@Nullable ProfileModel builtInDefault) {
		return builtInThemeLinked
				&& !profileEditor
				&& current != null
				&& draft != null
				&& builtInDefault != null
				&& !ProfileConfigMatcher.sameEffectiveConfig(current, draft, builtInDefault);
	}

	private void ensureShaderValues(ShaderInfo shader) {
		if (shader.values != null || shader.settings == null) {
			return;
		}
		float[] values = new float[4];
		boolean hasValues = false;
		for (int i = 0; i < shader.settings.length; i++) {
			ShaderInfo.Setting setting = shader.settings[i];
			if (setting != null) {
				values[i] = setting.def;
				hasValues = true;
			}
		}
		if (hasValues) {
			shader.values = values;
		}
	}

	private static String colorValue(ConfigFormState state, ColorField field) {
		return switch (field) {
			case SCREEN_BACKGROUND -> state.screenBackground;
			case VIRTUAL_KEYBOARD_BACKGROUND -> state.vkBackground;
			case VIRTUAL_KEYBOARD_FOREGROUND -> state.vkForeground;
			case VIRTUAL_KEYBOARD_SELECTED_BACKGROUND -> state.vkSelectedBackground;
			case VIRTUAL_KEYBOARD_SELECTED_FOREGROUND -> state.vkSelectedForeground;
			case VIRTUAL_KEYBOARD_OUTLINE -> state.vkOutline;
		};
	}

	private static ConfigFormState setColorValue(ConfigFormState state, ColorField field, String value) {
		ConfigFormState.Builder builder = state.toBuilder();
		switch (field) {
			case SCREEN_BACKGROUND -> builder.screenBackground(value);
			case VIRTUAL_KEYBOARD_BACKGROUND -> builder.vkBackground(value);
			case VIRTUAL_KEYBOARD_FOREGROUND -> builder.vkForeground(value);
			case VIRTUAL_KEYBOARD_SELECTED_BACKGROUND -> builder.vkSelectedBackground(value);
			case VIRTUAL_KEYBOARD_SELECTED_FOREGROUND -> builder.vkSelectedForeground(value);
			case VIRTUAL_KEYBOARD_OUTLINE -> builder.vkOutline(value);
		}
		return builder.build();
	}
}
