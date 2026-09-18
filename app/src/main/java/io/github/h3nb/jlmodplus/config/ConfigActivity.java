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
import static io.github.h3nb.jlmodplus.util.Constants.KEY_MIDLET_NAME;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;
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
	private static final String STATE_PROFILE_DRAFT_PATH = "profile_edit_draft_path";
	private static final String STATE_PROFILE_DRAFT_DIRTY = "profile_edit_draft_dirty";

	private final ArrayList<Size> screenPresets = new ArrayList<>();
	private final ArrayList<Size> removableScreenPresets = new ArrayList<>();
	private final ArrayList<ConfigUiState.FontPreset> fontPresets = new ArrayList<>();
	private final ArrayList<String> skinOptions = new ArrayList<>();
	private final ArrayList<String> soundBankOptions = new ArrayList<>();

	private File keylayoutFile;
	private File dataDir;
	private ProfileModel params;
	private boolean isProfile;
	private File appDir;
	private Display display;
	private File configDir;
	private String defProfile;
	private ArrayList<ShaderInfo> shaders;
	private String workDir;
	private boolean needShow;
	/** Captured before initialization so partial existing setup is never mistaken for a new app. */
	private boolean setupArtifactExistedBeforeInitialization;
	private boolean initializationDecisionMade;
	private boolean operationRunning;
	/** Target and isolated staging directory used while editing a reusable preset. */
	@Nullable private Profile profileEditTarget;
	@Nullable private File profileEditDraftDir;
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
			};
	private List<ProfileConfigMatcher.Candidate> profileCandidates = Collections.emptyList();
	private List<ProfilesManager.ProfileInfo> inspectedProfiles = Collections.emptyList();
	private byte[] currentKeyLayoutSnapshot;
	private List<String> profileNames = Collections.emptyList();
	@Nullable private String cachedDefaultProfileName;
	private int profileCacheGeneration;
	private final ExecutorService profileMetadataExecutor = Executors.newSingleThreadExecutor();
	@Nullable private String profileOrigin;
	@Nullable private GamepadCalibrationSession gamepadCalibration;
	@Nullable private ControllerInputRouter controllerInputRouter;
	private long controllerTargetGeneration = 1L;
	@Nullable private String gamepadCalibrationSignature;
	@Nullable private InputDevice gamepadCalibrationDevice;
	private int gamepadCalibrationSource;
	private boolean gamepadCalibrationPending;
	private Set<CalibrationChannel> gamepadCalibrationChannels = Collections.emptySet();
	private Map<CalibrationChannel, Float> lastCalibrationValues = Collections.emptyMap();
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
		public boolean onSaveTemplate(@NonNull String name, boolean includeKeyboard) {
			return saveTemplate(name, includeKeyboard);
		}

		@Override
		public void onSaveKeyboardLayout() {
			showSaveKeyboardLayout();
		}

		@Override
		public boolean onSaveKeyboardLayout(@NonNull String name) {
			return saveKeyboardLayout(name);
		}

		@Override
		public void onChooseKeyboardLayout() {
			showKeyboardLayoutPicker();
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
		String path = intent.getDataString();
		if (path == null) {
			needShow = false;
			finish();
			return;
		}
		if (isProfile) {
			profileEditTarget = new Profile(path);
			String savedDraftPath = savedInstanceState == null
					? null : savedInstanceState.getString(STATE_PROFILE_DRAFT_PATH);
			try {
				if (savedDraftPath != null) {
					File savedDraft = new File(savedDraftPath);
					profileEditDraftDir = savedDraft.isDirectory()
							? savedDraft : createProfileEditDraft(profileEditTarget);
				} else {
					profileEditDraftDir = createProfileEditDraft(profileEditTarget);
				}
			} catch (IOException e) {
				Log.e(TAG, "createProfileEditDraft", e);
				ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
				finish();
				return;
			}
			configDir = profileEditDraftDir;
			profileDraftDirty = savedInstanceState != null
					&& savedInstanceState.getBoolean(STATE_PROFILE_DRAFT_DIRTY, false);
			workDir = Config.getEmulatorDir();
			setTitle(getString(R.string.preset_edit_title, path));
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
			dataDir.mkdirs();
			configDir = new File(workDir + Config.MIDLET_CONFIGS_DIR + appDir.getName());
		}
		configDir.mkdirs();
		hostPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		hostPreferences.registerOnSharedPreferenceChangeListener(hostThemeListener);
		profileOrigin = readProfileOrigin();
		setupArtifactExistedBeforeInitialization = hasConfigArtifact(configDir)
				|| new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE).isFile()
				|| profileOrigin != null
				|| (!isProfile && hostPreferences.getBoolean(
						ProfileModel.builtInThemePreferenceKey(configDir), false));
		builtInDefaultParams = newBuiltInProfile();

		defProfile = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
				.getString(PREF_DEFAULT_PROFILE, null);
		loadConfig();
		if (!params.isNew && !needShow) {
			startMIDlet();
			return;
		}
		loadKeyLayout();
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
							FileUtils.clearDirectory(dataDir);
						}
					}

				@Override
				public void onResetSettings() {
					if (operationRunning) return;
					operationRunning = true;
					try {
						if (isProfile) profileDraftDirty = true;
							setProfileOrigin(null);
							params = newBuiltInProfile();
							setBuiltInThemeLinked(true);
							loadParams(false);
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
							if (keylayoutFile != null) {
								//noinspection ResultOfMethodCallIgnored
								keylayoutFile.delete();
							}
							loadKeyLayout();
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

			@Override
			public boolean onControllerModalMotion(@NonNull MotionEvent event) {
				return isControllerModalActive();
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
		lastCalibrationValues = Collections.emptyMap();
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
		gamepadCalibrationSource = source;
		gamepadCalibrationChannels = channels;
		gamepadCalibrationSignature = ControllerInputRouter.capabilitySignatureFor(device, source);
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
		lastCalibrationValues = values;
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
		lastCalibrationValues = Collections.emptyMap();
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
					ControllerInputRouter.capabilitySignatureFor(device, calibrationSource(device))))
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
		if (isProfile && profileEditDraftDir != null) {
			outState.putString(STATE_PROFILE_DRAFT_PATH, profileEditDraftDir.getAbsolutePath());
			outState.putBoolean(STATE_PROFILE_DRAFT_DIRTY, profileDraftDirty);
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

	void loadConfig() {
		boolean mayInitializeNewApp = !initializationDecisionMade
				&& !setupArtifactExistedBeforeInitialization;
		initializationDecisionMade = true;
		String configuredDefault = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
				.getString(PREF_DEFAULT_PROFILE, null);
		Profile configuredProfile = isProfile ? null : ProfilesManager.findProfile(configuredDefault);
		ProfilesManager.ProfileInfo configuredInfo = configuredProfile == null
				? null : ProfilesManager.inspectProfile(configuredProfile);
		Profile validDefault = configuredInfo != null && configuredInfo.settings.isReady()
				? configuredProfile : null;
		Profile keyboardOnlyDefault = configuredInfo != null
				&& configuredInfo.settings.status == ProfilesManager.CapabilityStatus.ABSENT
				&& configuredInfo.keyboardLayout.isReady() ? configuredProfile : null;
		defProfile = validDefault == null ? null : validDefault.getName();
		cachedDefaultProfileName = defProfile;
		params = ProfilesManager.loadConfig(
				configDir,
				true,
				isProfile ? ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE
						: ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
				!isProfile && profileOrigin == null && readBuiltInThemeLinked());
		boolean loadedDefaultProfile = false;
		boolean loadedLegacyDefaultLayout = false;
		if (params == null && mayInitializeNewApp && validDefault != null) {
			try {
				boolean hasKeyboardLayout = configuredInfo != null
						&& configuredInfo.keyboardLayout.isReady();
				ProfilesManager.load(validDefault, configDir.getPath(), true, hasKeyboardLayout);
			} catch (IOException | RuntimeException e) {
				Log.e(TAG, "loadConfig: default preset", e);
			}
			params = ProfilesManager.loadConfig(
					configDir,
					true,
					isProfile ? ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE
							: ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
					!isProfile && profileOrigin == null && readBuiltInThemeLinked());
			loadedDefaultProfile = params != null;
			if (loadedDefaultProfile) {
				setProfileOrigin(validDefault.getName());
			}
		} else if (params == null && mayInitializeNewApp && keyboardOnlyDefault != null) {
			// Preserve the legacy default keyboard-only preference for new applications while keeping
			// the built-in application settings as the explicit configuration source.
			params = newBuiltInProfile();
			if (ProfilesManager.saveConfig(params)) {
				try {
					ProfilesManager.load(keyboardOnlyDefault, configDir.getPath(), false, true);
					loadedLegacyDefaultLayout = true;
				} catch (IOException | RuntimeException e) {
					Log.e(TAG, "loadConfig: legacy default keyboard layout", e);
				}
			}
			if (loadedLegacyDefaultLayout) setProfileOrigin(null);
		}
		if (params == null) {
			params = newBuiltInProfile();
			setBuiltInThemeLinked(!isProfile && !loadedDefaultProfile
					&& !setupArtifactExistedBeforeInitialization && profileOrigin == null);
			return;
		}
		if (isProfile) {
			builtInThemeLinked = false;
			return;
		}

		boolean linked = readBuiltInThemeLinked();
		if (profileOrigin != null || loadedDefaultProfile) {
			linked = false;
		}
		if (loadedLegacyDefaultLayout) {
			setBuiltInThemeLinked(true);
			return;
		}
		setBuiltInThemeLinked(linked);
		if (builtInThemeLinked) {
			ProfileModel.applyBuiltInTheme(params, isDarkTheme());
		}
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

	private void loadKeyLayout() {
		File file = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
		keylayoutFile = file;
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
		if (isProfile && profileEditDraftDir != null && !isChangingConfigurations()) {
			FileUtils.deleteDirectory(profileEditDraftDir);
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
		if (reloadFromFile) {
			loadConfig();
		}
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private boolean saveParams() {
		try {
			if (currentForm != null) {
				reconcileBuiltInThemeLink();
				currentForm.applyTo(params);
			}
			return ProfilesManager.saveConfig(params);
		} catch (Throwable t) {
			Log.e(TAG, "saveParams", t);
			return false;
		}
	}

	private File createProfileEditDraft(@NonNull Profile profile) throws IOException {
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
		copyIfFile(profile.getConfig(), new File(draft, "config.json"));
		copyIfFile(new File(profile.getDir(), "config.xml"), new File(draft, "config.xml"));
		copyIfFile(profile.getKeyLayout(), new File(draft, "VirtualKeyboardLayout"));
		return draft;
	}

	private static void copyIfFile(@NonNull File source, @NonNull File destination) throws IOException {
		if (source.isFile()) FileUtils.copyFileUsingChannel(source, destination);
	}

	private void handleBackRequest() {
		finish();
	}

	private void saveProfileDraft() {
		if (!isProfile || operationRunning || profileEditTarget == null || profileEditDraftDir == null) {
			return;
		}
		operationRunning = true;
		try {
			if (!saveParams()) throw new IOException("Unable to save preset editor draft");
			ProfilesManager.saveEditedSnapshot(profileEditTarget, profileEditDraftDir.getPath());
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
			saveParams();
		}
		boolean reconversionShown = Config.startApp(
				this,
				getIntent().getStringExtra(KEY_MIDLET_NAME),
				getIntent().getData());
		if (!reconversionShown) finish();
	}

	private void openKeyMappings() {
		Intent i = new Intent(getIntent().getAction(), Uri.parse(configDir.getPath()),
				this, KeyMapperActivity.class);
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
			setProfileOrigin(null);
			params = newBuiltInProfile();
			setBuiltInThemeLinked(true);
			currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
			if (!saveParams()) {
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

	private ProfileModel newBuiltInProfile() {
		return ProfileModel.createBuiltIn(configDir, isDarkTheme());
	}

	/** Re-derives theme-owned built-in colors without turning the profile into a custom snapshot. */
	private void syncLinkedBuiltInTheme() {
		if (isProfile || !builtInThemeLinked || params == null || configDir == null) return;
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

	private boolean applyTemplate(@NonNull String name,
			@NonNull ConfigFormEvents.PresetApplyScope scope) {
		if (operationRunning) return false;
		Profile profile = ProfilesManager.findProfile(name);
		ProfilesManager.ProfileInfo inspected = profile == null
				? null : ProfilesManager.inspectProfile(profile);
		boolean applySettings = scope != ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT;
		boolean applyKeyboard = scope != ConfigFormEvents.PresetApplyScope.SETTINGS;
		if (inspected == null || (applySettings && !inspected.settings.isReady())
				|| (applyKeyboard && !inspected.keyboardLayout.isReady())) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		operationRunning = true;
		try {
			ProfilesManager.load(profile, configDir.getPath(), applySettings, applyKeyboard);
			// A source with any layout artifact is a combined preset, even when that artifact is
			// unavailable. Applying only its settings is still a partial, standalone setup.
			boolean sourceHasKeyboardArtifact =
					inspected.keyboardLayout.status != ProfilesManager.CapabilityStatus.ABSENT;
			boolean appliesCompleteSource = scope == ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD
					|| (scope == ConfigFormEvents.PresetApplyScope.SETTINGS && !sourceHasKeyboardArtifact);
			if (appliesCompleteSource) {
				setProfileOrigin(profile.getName());
			} else {
				// A partial combination is a standalone setup, not a live link to either source.
				setProfileOrigin(null);
			}
			setBuiltInThemeLinked(false);
			loadParams(true);
			return true;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "applyTemplate: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		} finally {
			operationRunning = false;
		}
	}

	private boolean saveTemplate(@NonNull String rawName, boolean includeKeyboard) {
		String name = rawName.trim();
		if (!Profile.isValidName(name)) {
			ThemedToast.show(this, R.string.preset_invalid_name, Toast.LENGTH_SHORT);
			return false;
		}
		if (ProfilesManager.profileNameExists(name)) {
			ThemedToast.show(this, R.string.profile_name_exists, Toast.LENGTH_SHORT);
			return false;
		}
		Profile profile = new Profile(name);
		try {
			if (!saveParams()) {
				throw new IOException("Unable to save current application configuration");
			}
			ProfilesManager.saveSnapshot(profile, configDir.getPath(), includeKeyboard);
			setProfileOrigin(name);
			refreshProfileMatchCache();
			if (composeController != null) composeController.update(createUiState());
			return true;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "saveTemplate: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
	}

	private String profileOriginKey() {
		return "config_profile_origin:" + configDir.getAbsolutePath();
	}

	private String builtInThemeKey() {
		return ProfileModel.builtInThemePreferenceKey(configDir);
	}

	private boolean readBuiltInThemeLinked() {
		if (isProfile || configDir == null) return false;
		return PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(builtInThemeKey(), false);
	}

	private void setBuiltInThemeLinked(boolean linked) {
		builtInThemeLinked = linked;
		if (isProfile || configDir == null) return;
		SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
		if (linked) editor.putBoolean(builtInThemeKey(), true);
		else editor.remove(builtInThemeKey());
		editor.apply();
	}

	@Nullable
	private String readProfileOrigin() {
		if (isProfile || configDir == null) return null;
		return PreferenceManager.getDefaultSharedPreferences(this).getString(profileOriginKey(), null);
	}

	private void setProfileOrigin(@Nullable String name) {
		profileOrigin = name;
		if (isProfile || configDir == null) return;
		if (name != null) {
			setBuiltInThemeLinked(false);
		}
		SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
		if (name == null) editor.remove(profileOriginKey());
		else editor.putString(profileOriginKey(), name);
		editor.apply();
	}

	private void showKeyboardLayoutPicker() {
		if (keylayoutFile == null) return;
		LoadProfileAlert.newInstance()
				.show(getSupportFragmentManager(), "load_keyboard_layout");
	}

	/** Applies only a saved keyboard artifact and leaves the current application-settings draft intact. */
	boolean applyKeyboardLayout(@NonNull String name) {
		if (operationRunning) return false;
		Profile profile = ProfilesManager.findProfile(name);
		ProfilesManager.ProfileInfo inspected = profile == null ? null
				: ProfilesManager.inspectProfile(profile);
		if (inspected == null || !inspected.keyboardLayout.isReady()) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
		}
		operationRunning = true;
		try {
			ProfilesManager.load(profile, configDir.getPath(), false, true);
			setProfileOrigin(null);
			setBuiltInThemeLinked(false);
			loadKeyLayout();
			refreshProfileMatchCache();
			if (composeController != null) composeController.update(createUiState());
			return true;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "applyKeyboardLayout: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			if (composeController != null) composeController.update(createUiState());
			return false;
		} finally {
			operationRunning = false;
		}
	}

	private void showSaveKeyboardLayout() {
		if (keylayoutFile == null) return;
		SaveProfileAlert.newInstance()
				.show(getSupportFragmentManager(), "save_keyboard_layout");
	}

	boolean saveKeyboardLayout(@NonNull String rawName) {
		String name = rawName.trim();
		if (!Profile.isValidName(name)) {
			ThemedToast.show(this, R.string.preset_invalid_name, Toast.LENGTH_SHORT);
			return false;
		}
		try {
			ProfilesManager.saveLayoutSnapshot(new Profile(name), configDir.getPath());
			refreshProfileMatchCache();
			if (composeController != null) composeController.update(createUiState());
			return true;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "saveKeyboardLayout: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return false;
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
			reconcileBuiltInThemeLink();
			if (composeController != null) {
				composeController.update(createUiState());
			}
		}
	}

	private void refreshProfileMatchCache() {
		if (isProfile) {
			profileCandidates = Collections.emptyList();
			inspectedProfiles = Collections.emptyList();
			currentKeyLayoutSnapshot = null;
			profileNames = Collections.emptyList();
			cachedDefaultProfileName = null;
			return;
		}
		final int generation = ++profileCacheGeneration;
		final File currentLayout = keylayoutFile;
		final String configuredDefault = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(PREF_DEFAULT_PROFILE, null);
		profileMetadataExecutor.execute(() -> {
			ArrayList<Profile> profiles = ProfilesManager.getProfiles();
			Collections.sort(profiles);
			List<ProfilesManager.ProfileInfo> inspected = ProfilesManager.inspectProfiles(profiles);
			List<ProfileConfigMatcher.Candidate> candidates =
					ProfileConfigMatcher.loadCandidatesFromInspection(inspected);
			byte[] keyboard = ProfileConfigMatcher.readKeyboard(currentLayout);
			String defaultName = null;
			if (configuredDefault != null) {
				for (ProfileConfigMatcher.Candidate candidate : candidates) {
					if (configuredDefault.equals(candidate.profile.getName())) {
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
				profileCandidates = candidates;
				inspectedProfiles = inspected;
				currentKeyLayoutSnapshot = keyboard;
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
		String defaultProfile = isProfile ? null : cachedDefaultProfileName;
		ProfileConfigMatcher.Candidate originCandidate = !isProfile && profileOrigin != null
				? findProfileCandidate(profileOrigin) : null;
		ConfigUiState.ProfileStatus profileStatus;
		if (originCandidate != null && ProfileConfigMatcher.matchesCandidate(
				params, state, originCandidate, currentKeyLayoutSnapshot)) {
			profileStatus = ConfigUiState.ProfileStatus.active(profileOrigin, defaultProfile);
		} else if (originCandidate != null) {
			profileStatus = ConfigUiState.ProfileStatus.modified(profileOrigin, defaultProfile);
		} else if (!isProfile && builtInThemeLinked && builtInDefaultParams != null
				&& ProfileConfigMatcher.sameEffectiveConfig(params, state, builtInDefaultParams)) {
			profileStatus = ConfigUiState.ProfileStatus.builtInDefault(defaultProfile);
		} else {
			profileStatus = ConfigUiState.ProfileStatus.custom(defaultProfile);
		}
		ArrayList<ConfigUiState.ProfileTemplate> templates = new ArrayList<>();
		ArrayList<ConfigUiState.ProfileTemplate> keyboardLayouts = new ArrayList<>();
		for (ProfilesManager.ProfileInfo info : inspectedProfiles) {
			if (info.keyboardLayout.isReady()) {
				String name = info.profile.getName();
				keyboardLayouts.add(new ConfigUiState.ProfileTemplate(
						name,
						name.equals(defaultProfile),
						true,
						info.config == null ? 0 : info.config.screenWidth,
						info.config == null ? 0 : info.config.screenHeight,
						info.config == null ? 0 : info.config.orientation));
			}
		}
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
				KeyboardLayoutValidator.validate(keylayoutFile) == null,
				profileNames, keyboardLayouts, firstControllerDevice() != null);
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

	private static boolean hasConfigArtifact(@NonNull File dir) {
		return new File(dir, Config.MIDLET_CONFIG_FILE).exists()
				|| new File(dir, "config.xml").exists();
	}

	@Nullable
	private ProfileConfigMatcher.Candidate findProfileCandidate(@NonNull String name) {
		for (ProfileConfigMatcher.Candidate candidate : profileCandidates) {
			if (candidate.profile.getName().equals(name)) return candidate;
		}
		return null;
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
		reconcileBuiltInThemeLink();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private void reconcileBuiltInThemeLink() {
		if (!builtInThemeLinked || isProfile || params == null || currentForm == null
				|| builtInDefaultParams == null) {
			return;
		}
		if (!ProfileConfigMatcher.sameEffectiveConfig(params, currentForm, builtInDefaultParams)) {
			setBuiltInThemeLinked(false);
		}
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
