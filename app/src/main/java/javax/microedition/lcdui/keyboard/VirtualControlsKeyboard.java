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
package javax.microedition.lcdui.keyboard;

import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.input.GuestViewport;
import io.github.h3nb.jlmodplus.input.PointerSourceKind;
import io.github.h3nb.jlmodplus.input.PointerSourceToken;
import io.github.h3nb.jlmodplus.input.StickProcessor;
import io.github.h3nb.jlmodplus.input.VirtualAnalogDirectionAdapter;
import io.github.h3nb.jlmodplus.input.VirtualAnalogSample;
import io.github.h3nb.jlmodplus.input.VirtualAnalogStick;
import io.github.h3nb.jlmodplus.input.VirtualAnalogStickMode;
import io.github.h3nb.jlmodplus.input.VirtualAnalogStickSettings;
import io.github.h3nb.jlmodplus.input.VirtualAnalogVisualState;
import io.github.h3nb.jlmodplus.input.VirtualControlGeometryResolver;
import io.github.h3nb.jlmodplus.input.VirtualDpadController;
import io.github.h3nb.jlmodplus.input.VirtualDpadDirection;
import io.github.h3nb.jlmodplus.input.VirtualDpadGeometry;

/**
 * Unified touch controls layered over the established virtual keypad.
 *
 * Numeric/soft/game buttons remain ordinary legacy virtual keys. Movement controls are owned here
 * as two cohesive widgets: one D-pad and one analog stick. The user-facing editor has one gesture
 * model for all controls: drag with one finger to move. Legacy buttons use two-finger horizontal
 * and vertical spans for independent width/height resizing, while grouped D-pad/analog controls
 * keep proportional pinch resizing. The existing keypad scale format remains unchanged.
 */
public final class VirtualControlsKeyboard extends VirtualKeyboard {
	public static final int TYPE_DPAD_STANDARD = 7;
	public static final int TYPE_ANALOG_STANDARD = 8;

	private static final int GROUPED_CONTROL_COUNT = 2;
	private static final float DEFAULT_DPAD_CENTER_X = 0.82f;
	private static final float DEFAULT_DPAD_CENTER_Y = 0.78f;
	private static final float DEFAULT_DPAD_RADIUS = 0.16f;
	private static final float DEFAULT_ANALOG_CENTER_X = 0.18f;
	private static final float DEFAULT_ANALOG_CENTER_Y = 0.78f;
	private static final float DEFAULT_ANALOG_RADIUS = 0.16f;
	private static final float CONTROL_HIT_SCALE = 1.20f;
	private static final float EDIT_SECOND_FINGER_HIT_SCALE = 1.60f;
	public static final float MIN_RADIUS_FRACTION = 0.07f;
	public static final float MAX_RADIUS_FRACTION = 0.34f;
	private static final int GRID_DIVISIONS = 24;
	private static final int FEEDBACK_DURATION_MS = 50;

	private enum EditControl { NONE, DPAD, ANALOG }
	private enum ActiveCustomSource { NONE, OVERRIDE, BASE, LEGACY_SHARED }

	private final ProfileModel settings;
	private final VirtualDpadController dpadController = new VirtualDpadController();
	private final VirtualAnalogDirectionAdapter directionAdapter = new VirtualAnalogDirectionAdapter();
	private final RectF paintRect = new RectF();
	private final Path dpadOutline = new Path();
	private final Path dpadArrow = new Path();
	private final RectF guestBounds = new RectF();

	private VirtualAnalogStick analogStick;
	private Canvas target;
	private View overlayView;
	private RectF screenBounds;
	private GuestViewport viewport;
	private boolean overlayVisible = true;
	private boolean applyingStandardTemplate;
	private boolean standardTemplateReflowPosted;
	private boolean standardTemplateEdited;
	private VirtualLayoutOrientation activeLayoutOrientation;
	private ActiveCustomSource activeCustomSource = ActiveCustomSource.NONE;
	private boolean activeOrientationDirty;
	private boolean runtimeCustomSavePending;
	private VirtualKeyboardLayoutState runtimeCustomBaselineState;

	private int dpadPointer = -1;
	private PointerSourceToken dpadToken;
	private String dpadChannel;
	private long dpadSequence;

	private int analogPointer = -1;
	private PointerSourceToken analogToken;
	private String analogChannel;
	private long analogSequence;

	private int editPointer = -1;
	private int editPinchPointer = -1;
	private EditControl editControl = EditControl.NONE;
	private float editPointerX;
	private float editPointerY;
	private float editPinchX;
	private float editPinchY;
	private float editOffsetX;
	private float editOffsetY;
	private float pinchStartRadius;
	private float pinchStartDistance;

	/*
	 * Legacy keypad buttons still use VirtualKeyboard's persisted key-scale groups. These fields
	 * only translate a two-finger pinch into that established scale engine while the public editor
	 * remains in LAYOUT_KEYS. LAYOUT_SCALES is therefore an internal compatibility detail, not a
	 * second user-visible editing mode.
	 */
	private int legacyEditPointer = -1;
	private int legacyPinchPointer = -1;
	private float legacyEditX;
	private float legacyEditY;
	private float legacyPinchX;
	private float legacyPinchY;
	private float legacyPinchOriginX;
	private float legacyPinchOriginY;
	private float legacyPinchStartSpanX;
	private float legacyPinchStartSpanY;

	public VirtualControlsKeyboard(ProfileModel settings, java.io.File configDir) {
		this(settings, configDir, true);
	}

	public VirtualControlsKeyboard(ProfileModel settings, java.io.File configDir, boolean recoverPersistentLayout) {
		super(settings, configDir, recoverPersistentLayout);
		this.settings = settings;
		boolean legacyDpadEnabled = settings.virtualDpadEnabled;
		boolean legacyAnalogEnabled = settings.virtualAnalogEnabled;
		int layout = getLayout();
		if (isStandardTemplate(layout)) {
			settings.virtualDpadEnabled = layout == TYPE_DPAD_STANDARD;
			settings.virtualAnalogEnabled = layout == TYPE_ANALOG_STANDARD;
		} else if (layout != TYPE_CUSTOM) {
			// Existing built-in templates keep their historical controls. Grouped movement belongs
			// only to the two new standard templates unless the user explicitly customizes a layout.
			settings.virtualDpadEnabled = false;
			settings.virtualAnalogEnabled = false;
		}
		sanitizeStoredGeometry();
		rebuildAnalogStick();

		// A v1-v3 file may keep its Custom KEYS/SCALES dormant while a built-in TYPE is active.
		// VirtualKeyboard stages that legacy payload before this constructor runs; enrich it with
		// grouped-control values that historically lived only in ProfileModel before any active
		// built-in normalization can erase those working flags.
		VirtualKeyboardLayoutState legacyDormantState = getStoredCustomLayoutState();
		if (getLoadedLayoutVersion() > 0 && getLoadedLayoutVersion() < 4 &&
				legacyDormantState != null &&
				legacyDormantState.baseVariant() == VirtualKeyboardLayoutState.BASE_UNKNOWN &&
				legacyDormantState.legacySharedFallback() != null &&
				!legacyDormantState.legacySharedFallback().hasGroupedControls) {
			VirtualKeyboardLayoutSnapshot enriched =
					legacyDormantState.legacySharedFallback().withGroupedControls(
							legacyDpadEnabled,
							legacyAnalogEnabled,
							settings.virtualDpadCenterX,
							settings.virtualDpadCenterY,
							settings.virtualDpadRadius,
							settings.virtualAnalogCenterX,
							settings.virtualAnalogCenterY,
							settings.virtualAnalogRadius,
							false,
							true).asCustomOverride();
			setStoredCustomLayoutState(VirtualKeyboardLayoutState.migrated(enriched));
		}

		// v1-v3 Custom artifacts had one shared semantic layout. Promote that state in memory only;
		// the file is rewritten as v4 only after an explicit Save.
		if (layout == TYPE_CUSTOM && getLoadedLayoutVersion() > 0 &&
				getLoadedLayoutVersion() < 4 && getStoredCustomLayoutState() == null) {
			setStoredCustomLayoutState(
					VirtualKeyboardLayoutState.migrated(captureCurrentCustomSnapshot()));
		}
	}





	@Override
	public void setView(View view) {
		super.setView(view);
		overlayView = view;
		int layout = getLayout();
		if (isStandardTemplate(layout)) {
			applyStandardLegacyVisibility();
			if (screenBounds != null && !standardTemplateEdited) {
				scheduleStandardTemplateReflow(layout);
			}
		}
	}

	/**
	 * Expose grouped movement controls through the same hide/show contract as legacy virtual keys.
	 * The returned visibility array keeps the historical convention: true means hidden.
	 */
	@Override
	public String[] getKeyNames() {
		String[] legacyNames = super.getKeyNames();
		String[] names = Arrays.copyOf(legacyNames, legacyNames.length + GROUPED_CONTROL_COUNT);
		names[legacyNames.length] = ContextHolder.getAppContext().getString(R.string.runtime_virtual_controls_dpad);
		names[legacyNames.length + 1] = ContextHolder.getAppContext().getString(R.string.runtime_virtual_controls_analog);
		return names;
	}

	@Override
	public boolean[] getKeysVisibility() {
		boolean[] legacyStates = super.getKeysVisibility();
		boolean[] states = Arrays.copyOf(legacyStates, legacyStates.length + GROUPED_CONTROL_COUNT);
		states[legacyStates.length] = !settings.virtualDpadEnabled;
		states[legacyStates.length + 1] = !settings.virtualAnalogEnabled;
		return states;
	}

	@Override
	public void setKeysVisibility(boolean[] states) {
		if (states == null) return;
		int legacyCount = super.getKeyNames().length;
		if (states.length < legacyCount) return;

		boolean[] before = getKeysVisibility();
		boolean standardBase = isStandardTemplate(getLayout());
		VirtualKeyboardLayoutState customState = getStoredCustomLayoutState();
		if (!standardBase && getLayout() == TYPE_CUSTOM && customState != null &&
				customState.hasKnownBase() && activeCustomSource == ActiveCustomSource.BASE) {
			standardBase = isStandardTemplate(customState.baseVariant());
		}

		boolean[] legacyStates = Arrays.copyOf(states, legacyCount);
		if (standardBase) forceLegacyDirectionsHidden(legacyStates);
		super.setKeysVisibility(legacyStates);

		if (states.length >= legacyCount + GROUPED_CONTROL_COUNT) {
			boolean nextDpadEnabled = !states[legacyCount];
			boolean nextAnalogEnabled = !states[legacyCount + 1];
			if (!nextDpadEnabled) endDpad();
			if (!nextAnalogEnabled) endAnalog();
			settings.virtualDpadEnabled = nextDpadEnabled;
			settings.virtualAnalogEnabled = nextAnalogEnabled;
		}
		if (!Arrays.equals(before, getKeysVisibility())) {
			markActiveOrientationEdited();
			invalidateOverlay();
		}
	}

	@Override
	public boolean onLayoutChanged(int variant) {
		if (variant != TYPE_CUSTOM) {
			abandonPendingRuntimeCustomization();
		}
		if (variant == TYPE_CUSTOM) {
			if (getStoredCustomLayoutState() == null) {
				int current = getLayout();
				if (VirtualKeyboardLayoutState.isSupportedBaseVariant(current)) {
					setStoredCustomLayoutState(VirtualKeyboardLayoutState.forBase(current));
					activeLayoutOrientation = VirtualLayoutOrientation.resolve(
							screenBounds, activeLayoutOrientation);
					activeOrientationDirty = true;
					activeCustomSource = ActiveCustomSource.OVERRIDE;
				} else {
					setStoredCustomLayoutState(
							VirtualKeyboardLayoutState.migrated(captureCurrentCustomSnapshot()));
				}
			}
			if (activeOrientationDirty && !stashActiveCustomDraft()) {
				return false;
			}
			setLayoutVariantInMemory(TYPE_CUSTOM);
		} else {
			activeCustomSource = ActiveCustomSource.NONE;
			activeOrientationDirty = false;
		}
		return super.onLayoutChanged(variant);
	}

	@Override
	public void onLayoutPersistenceCommitted() {
		super.onLayoutPersistenceCommitted();
		commitPendingRuntimeCustomization();
	}

	@Override
	public boolean setLayout(int variant) {
		abandonPendingRuntimeCustomization();
		if (variant != TYPE_CUSTOM) {
			activeCustomSource = ActiveCustomSource.NONE;
			activeOrientationDirty = false;
		}
		return applyControlsLayout(variant, true);
	}

	@Override
	public void setLayoutForEditing(int variant) {
		if (variant == TYPE_CUSTOM) {
			if (getLayout() == TYPE_CUSTOM && getStoredCustomLayoutState() != null) {
				// Re-selecting the already-active Custom entry must not reload the last disk state and
				// silently discard unsaved orientation drafts.
				return;
			}
			applyControlsLayout(variant, false);
			return;
		}
		if (!VirtualKeyboardLayoutState.isSupportedBaseVariant(variant)) return;
		if (getLayout() != TYPE_CUSTOM && getStoredCustomLayoutState() == null &&
				getLayout() == variant) {
			applyControlsLayout(variant, false);
			return;
		}

		cancel();
		setStoredCustomLayoutState(VirtualKeyboardLayoutState.forBase(variant));
		activeOrientationDirty = false;
		activeLayoutOrientation = VirtualLayoutOrientation.resolve(
				screenBounds, activeLayoutOrientation);

		// Reuse the established built-in generator for explicit template preview. After it has
		// produced current-orientation geometry, restore the semantic outer type to Custom.
		applyControlsLayout(variant, false);
		setLayoutVariantInMemory(TYPE_CUSTOM);
		activeCustomSource = ActiveCustomSource.BASE;
		standardTemplateEdited = false;
		invalidateOverlay();
		notifyLayoutEditStateChanged();
	}

	private void markActiveOrientationEdited() {
		beginPendingRuntimeCustomizationIfNeeded();
		activeLayoutOrientation = VirtualLayoutOrientation.resolve(
				screenBounds, activeLayoutOrientation);
		VirtualKeyboardLayoutState state = getStoredCustomLayoutState();
		if (getLayout() != TYPE_CUSTOM) {
			int base = getLayout();
			if (VirtualKeyboardLayoutState.isSupportedBaseVariant(base)) {
				state = VirtualKeyboardLayoutState.forBase(base);
			} else {
				state = VirtualKeyboardLayoutState.migrated(captureCurrentCustomSnapshot());
			}
			setStoredCustomLayoutState(state);
			setLayoutVariantInMemory(TYPE_CUSTOM);
		} else if (state == null) {
			state = VirtualKeyboardLayoutState.migrated(captureCurrentCustomSnapshot());
			setStoredCustomLayoutState(state);
		}
		activeOrientationDirty = true;
		activeCustomSource = ActiveCustomSource.OVERRIDE;
		standardTemplateEdited = false;
		notifyLayoutEditStateChanged();
	}

	private void beginPendingRuntimeCustomizationIfNeeded() {
		if (getLayoutEditMode() != LAYOUT_EOF || runtimeCustomSavePending) return;
		runtimeCustomBaselineState = getStoredCustomLayoutState();
		runtimeCustomSavePending = true;
	}

	private void commitPendingRuntimeCustomization() {
		if (!runtimeCustomSavePending) return;
		runtimeCustomSavePending = false;
		runtimeCustomBaselineState = null;
	}

	private void abandonPendingRuntimeCustomization() {
		if (!runtimeCustomSavePending) return;
		setStoredCustomLayoutState(runtimeCustomBaselineState);
		runtimeCustomSavePending = false;
		runtimeCustomBaselineState = null;
	}

	private boolean applyControlsLayout(int variant, boolean persist) {
		if (!isStandardTemplate(variant)) {
			standardTemplateEdited = false;
			endDpad();
			endAnalog();
			if (variant == TYPE_CUSTOM) {
				activeCustomSource = ActiveCustomSource.NONE;
				activeOrientationDirty = false;
			}
			if (variant != TYPE_CUSTOM) {
				settings.virtualDpadEnabled = false;
				settings.virtualAnalogEnabled = false;
			}
			boolean persisted = true;
			if (persist) persisted = super.setLayout(variant);
			else super.setLayoutForEditing(variant);
			if (variant == TYPE_CUSTOM && getLayout() == TYPE_CUSTOM) {
				activeOrientationDirty = false;
				activeCustomSource = ActiveCustomSource.NONE;
				if (getLoadedLayoutVersion() > 0 && getLoadedLayoutVersion() < 4 &&
						getStoredCustomLayoutState() == null) {
					setStoredCustomLayoutState(
							VirtualKeyboardLayoutState.migrated(captureCurrentCustomSnapshot()));
				}
				if (screenBounds != null && getStoredCustomLayoutState() != null) {
					activeLayoutOrientation = VirtualLayoutOrientation.resolve(
							screenBounds, activeLayoutOrientation);
					applyCustomOrientationState(activeLayoutOrientation);
				}
			}
			invalidateOverlay();
			notifyLayoutEditStateChanged();
			return persisted;
		}

		boolean persisted = true;
		applyingStandardTemplate = true;
		standardTemplateEdited = false;
		try {
			endDpad();
			endAnalog();
			// Build the template in memory first. Only the final Save/persistent selection writes it.
			super.setLayoutForEditing(TYPE_NUMBERS_ARROWS);
			applyStandardLegacyVisibility();
			StandardVirtualControlsLayout layout = standardTemplateLayout();
			arrangeStandardLegacyButtons(layout);

			float width = Math.max(1.0f, screenBounds == null ? 1.0f : screenBounds.width());
			float height = Math.max(1.0f, screenBounds == null ? 1.0f : screenBounds.height());
			float left = screenBounds == null ? 0.0f : screenBounds.left;
			float top = screenBounds == null ? 0.0f : screenBounds.top;
			float movementCenterX = (layout.movementCenterX - left) / width;
			float movementCenterY = (layout.movementCenterY - top) / height;
			settings.virtualDpadEnabled = variant == TYPE_DPAD_STANDARD;
			settings.virtualAnalogEnabled = variant == TYPE_ANALOG_STANDARD;
			settings.virtualDpadCenterX = clamp(movementCenterX, 0.0f, 1.0f);
			settings.virtualDpadCenterY = clamp(movementCenterY, 0.0f, 1.0f);
			float shortest = Math.max(1.0f, Math.min(width, height));
			float movementRadius = clamp(
					layout.movementRadius / shortest,
					MIN_RADIUS_FRACTION,
					MAX_RADIUS_FRACTION);
			settings.virtualDpadRadius = movementRadius;
			settings.virtualAnalogCenterX = clamp(movementCenterX, 0.0f, 1.0f);
			settings.virtualAnalogCenterY = clamp(movementCenterY, 0.0f, 1.0f);
			settings.virtualAnalogRadius = movementRadius;
			rebuildAnalogStick();
			if (persist) {
				persisted = super.onLayoutChanged(variant);
			} else {
				setLayoutVariantInMemory(variant);
			}
			invalidateOverlay();
			notifyLayoutEditStateChanged();
		} finally {
			applyingStandardTemplate = false;
		}
		return persisted;
	}

	private static boolean isStandardTemplate(int variant) {
		return variant == TYPE_DPAD_STANDARD || variant == TYPE_ANALOG_STANDARD;
	}

	private void applyStandardLegacyVisibility() {
		String[] legacyNames = super.getKeyNames();
		boolean[] hidden = new boolean[legacyNames.length];
		Arrays.fill(hidden, true);
		for (int i = 0; i < legacyNames.length; i++) {
			String name = legacyNames[i];
			if ("F".equals(name) || "L".equals(name) || "R".equals(name) ||
					"*".equals(name) || "0".equals(name) || "#".equals(name)) {
				hidden[i] = false;
			}
		}
		forceLegacyDirectionsHidden(hidden);
		super.setKeysVisibility(hidden);
	}

	private StandardVirtualControlsLayout standardTemplateLayout() {
		float screenLeft = screenBounds == null ? 0.0f : screenBounds.left;
		float screenTop = screenBounds == null ? 0.0f : screenBounds.top;
		float screenRight = screenBounds == null ? 1.0f : screenBounds.right;
		float screenBottom = screenBounds == null ? 1.0f : screenBounds.bottom;

		float guestLeft = guestBounds.width() > 0.0f ? guestBounds.left : screenLeft;
		float guestTop = guestBounds.height() > 0.0f ? guestBounds.top : screenTop;
		float guestRight = guestBounds.width() > 0.0f ? guestBounds.right : screenRight;
		float guestBottom = guestBounds.height() > 0.0f ? guestBounds.bottom : screenBottom;

		return StandardVirtualControlsLayout.resolve(
				screenLeft, screenTop, screenRight, screenBottom,
				guestLeft, guestTop, guestRight, guestBottom);
	}

	/**
	 * Lay out the standard controls as two ergonomic zones: shoulder buttons across the upper
	 * corners, movement in the lower-left, and F above *, 0, and # in the lower-right.
	 */
	private void arrangeStandardLegacyButtons(StandardVirtualControlsLayout layout) {
		if (screenBounds == null || layout == null) return;

		float baseKeySize = Math.max(1.0f, getCurrentKeySize());
		setKeyGroupScaleByLabel(
				"L",
				layout.shoulderWidth / baseKeySize,
				layout.shoulderHeight / baseKeySize);
		setKeyGroupScaleByLabel(
				"F",
				layout.fireSize / baseKeySize,
				layout.fireSize / baseKeySize);
		setKeyGroupScaleByLabel(
				"*",
				layout.keySize / baseKeySize,
				layout.keySize / baseKeySize);

		setKeyCenterByLabel("L", layout.shoulderLeftX, layout.shoulderCenterY);
		setKeyCenterByLabel("R", layout.shoulderRightX, layout.shoulderCenterY);
		setKeyCenterByLabel("F", layout.actionCenterX, layout.actionCenterY);
		setKeyCenterByLabel("*", layout.bottomLeftX, layout.bottomRowY);
		setKeyCenterByLabel("0", layout.actionCenterX, layout.bottomRowY);
		setKeyCenterByLabel("#", layout.bottomRightX, layout.bottomRowY);
		refreshDirectKeyLayout();
	}

	@Override
	public VirtualKeyboardLayoutSnapshot captureLayoutSnapshot() {
		return super.captureLayoutSnapshot().withGroupedControls(
				settings.virtualDpadEnabled,
				settings.virtualAnalogEnabled,
				settings.virtualDpadCenterX,
				settings.virtualDpadCenterY,
				settings.virtualDpadRadius,
				settings.virtualAnalogCenterX,
				settings.virtualAnalogCenterY,
				settings.virtualAnalogRadius,
				isStandardTemplate(getLayout()),
				standardTemplateEdited);
	}

	private VirtualKeyboardLayoutSnapshot captureCurrentCustomSnapshot() {
		return super.captureLayoutSnapshot().withGroupedControls(
				settings.virtualDpadEnabled,
				settings.virtualAnalogEnabled,
				settings.virtualDpadCenterX,
				settings.virtualDpadCenterY,
				settings.virtualDpadRadius,
				settings.virtualAnalogCenterX,
				settings.virtualAnalogCenterY,
				settings.virtualAnalogRadius,
				false,
				true).asCustomOverride();
	}

	@Override
	public VirtualKeyboardLayoutEditState captureLayoutEditState() {
		VirtualKeyboardLayoutState state = getStoredCustomLayoutState();
		if (getLayout() != TYPE_CUSTOM || state == null) {
			return super.captureLayoutEditState();
		}
		if (activeOrientationDirty && activeLayoutOrientation != null) {
			state = state.withOverride(
					activeLayoutOrientation, captureCurrentCustomSnapshot());
		}
		return VirtualKeyboardLayoutEditState.custom(state);
	}

	@Override
	public void restoreLayoutEditState(VirtualKeyboardLayoutEditState state) {
		if (state == null) return;
		cancel();
		if (!state.isCustom()) {
			activeCustomSource = ActiveCustomSource.NONE;
			activeOrientationDirty = false;
			super.restoreLayoutEditState(state);
			return;
		}

		setStoredCustomLayoutState(state.customLayout());
		activeOrientationDirty = false;
		setLayoutVariantInMemory(TYPE_CUSTOM);
		activeLayoutOrientation = VirtualLayoutOrientation.resolve(
				screenBounds, activeLayoutOrientation);
		if (screenBounds != null) {
			applyCustomOrientationState(activeLayoutOrientation);
		} else {
			activeCustomSource = ActiveCustomSource.NONE;
		}
		invalidateOverlay();
		notifyLayoutEditStateChanged();
	}

	@Override
	public void restoreLayoutSnapshot(VirtualKeyboardLayoutSnapshot snapshot) {
		if (snapshot == null) return;
		if (snapshot.layoutVariant != TYPE_CUSTOM) {
			activeCustomSource = ActiveCustomSource.NONE;
			activeOrientationDirty = false;
		}
		if (snapshot.isUneditedStandardTemplate()) {
			applyControlsLayout(snapshot.layoutVariant, false);
			return;
		}
		standardTemplateEdited = snapshot.standardTemplateEdited;
		applyGroupedSnapshot(snapshot);
		super.restoreLayoutSnapshot(snapshot);
		sanitizeStoredGeometry();
		rebuildAnalogStick();
		invalidateOverlay();
		notifyLayoutEditStateChanged();
	}

	private void applyGroupedSnapshot(VirtualKeyboardLayoutSnapshot snapshot) {
		if (!snapshot.hasGroupedControls) {
			settings.virtualDpadEnabled = false;
			settings.virtualAnalogEnabled = false;
			return;
		}
		settings.virtualDpadEnabled = snapshot.dpadEnabled;
		settings.virtualAnalogEnabled = snapshot.analogEnabled;
		settings.virtualDpadCenterX = snapshot.dpadCenterX;
		settings.virtualDpadCenterY = snapshot.dpadCenterY;
		settings.virtualDpadRadius = snapshot.dpadRadius;
		settings.virtualAnalogCenterX = snapshot.analogCenterX;
		settings.virtualAnalogCenterY = snapshot.analogCenterY;
		settings.virtualAnalogRadius = snapshot.analogRadius;
	}

	@Override
	public boolean isLayoutManipulationActive() {
		return super.isLayoutManipulationActive() ||
				editPointer >= 0 || editPinchPointer >= 0 ||
				legacyEditPointer >= 0 || legacyPinchPointer >= 0;
	}

	@Override
	public List<RectF> getVisibleLayoutControlBounds() {
		ArrayList<RectF> bounds = new ArrayList<>(super.getVisibleLayoutControlBounds());
		if (screenBounds == null) return bounds;
		if (settings.virtualDpadEnabled) {
			VirtualDpadGeometry geometry = dpadGeometry();
			bounds.add(new RectF(
					geometry.getCenterX() - geometry.getRadius(),
					geometry.getCenterY() - geometry.getRadius(),
					geometry.getCenterX() + geometry.getRadius(),
					geometry.getCenterY() + geometry.getRadius()));
		}
		if (settings.virtualAnalogEnabled) {
			VirtualDpadGeometry geometry = analogGeometry();
			bounds.add(new RectF(
					geometry.getCenterX() - geometry.getRadius(),
					geometry.getCenterY() - geometry.getRadius(),
					geometry.getCenterX() + geometry.getRadius(),
					geometry.getCenterY() + geometry.getRadius()));
		}
		return bounds;
	}

	@Override
	public void setTarget(Canvas canvas) {
		if (target != canvas) {
			endDpad();
			endAnalog();
		}
		super.setTarget(canvas);
		target = canvas;
	}

	@Override
	public boolean isPhone() {
		if (super.isPhone()) return true;
		VirtualKeyboardLayoutState state = getStoredCustomLayoutState();
		return getLayout() == TYPE_CUSTOM && state != null && state.hasKnownBase() &&
				(state.baseVariant() == 1 || state.baseVariant() == 2);
	}

	@Override
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		VirtualLayoutOrientation nextOrientation =
				VirtualLayoutOrientation.resolve(screen, activeLayoutOrientation);
		boolean orientationChanged = activeLayoutOrientation != null &&
				nextOrientation != activeLayoutOrientation;
		if (orientationChanged) {
			// A new control surface must never inherit ownership or edit gesture state from the old one.
			cancel();
			stashActiveCustomDraft();
		} else {
			endDpad();
			endAnalog();
		}

		boolean screenChanged = rectChanged(
				screenBounds, screen.left, screen.top, screen.right, screen.bottom);
		boolean guestChanged = rectChanged(guestBounds, left, top, right, bottom);
		int layout = getLayout();
		// Portrait Standard geometry is screen-anchored; only landscape uses guest gutters/deck.
		boolean guestAffectsGeometry = guestChanged && screen.width() > screen.height();
		boolean reflowStandardTemplate = !applyingStandardTemplate && !standardTemplateEdited &&
				isStandardTemplate(layout) && (screenChanged || guestAffectsGeometry);

		super.resize(screen, left, top, right, bottom);
		screenBounds = new RectF(screen);
		guestBounds.set(left, top, right, bottom);
		viewport = new GuestViewport(
				Math.max(1, Math.round(screen.width())),
				Math.max(1, Math.round(screen.height())));
		activeLayoutOrientation = nextOrientation;

		VirtualKeyboardLayoutState customState = getStoredCustomLayoutState();
		if (getLayout() == TYPE_CUSTOM && customState != null) {
			if (orientationChanged || activeCustomSource == ActiveCustomSource.NONE) {
				applyCustomOrientationState(nextOrientation);
			} else if (activeCustomSource == ActiveCustomSource.BASE &&
					customState.hasKnownBase() &&
					isStandardTemplate(customState.baseVariant()) &&
					(screenChanged || guestAffectsGeometry)) {
				// A missing Standard override stays derived on host/orientation changes.
				applyCustomBaseVariant(customState.baseVariant());
			} else {
				rebuildAnalogStick();
			}
			invalidateOverlay();
			return;
		}

		rebuildAnalogStick();
		if (reflowStandardTemplate) scheduleStandardTemplateReflow(layout);
	}

	private boolean stashActiveCustomDraft() {
		if (!activeOrientationDirty || activeLayoutOrientation == null) return true;
		VirtualKeyboardLayoutState state = getStoredCustomLayoutState();
		if (state == null) return false;
		boolean persistable = prepareCustomLayoutForSave();
		if (!persistable) {
			// Keep the visible draft in its orientation slot so rotation cannot lose user work.
			// The invalid snapshot remains invalid and the v4 writer will reject Save.
			android.util.Log.w(
					VirtualControlsKeyboard.class.getSimpleName(),
					"Orientation draft could not satisfy Custom persistence invariant");
		}
		setStoredCustomLayoutState(state.withOverride(
				activeLayoutOrientation, captureCurrentCustomSnapshot()));
		activeOrientationDirty = false;
		activeCustomSource = ActiveCustomSource.OVERRIDE;
		return persistable;
	}

	private void applyCustomOrientationState(VirtualLayoutOrientation orientation) {
		VirtualKeyboardLayoutState state = getStoredCustomLayoutState();
		if (state == null || orientation == null) {
			activeCustomSource = ActiveCustomSource.NONE;
			return;
		}

		VirtualKeyboardLayoutSnapshot override = state.overrideFor(orientation);
		if (override != null) {
			applyCustomSnapshot(override);
			activeCustomSource = ActiveCustomSource.OVERRIDE;
			return;
		}
		if (state.hasKnownBase()) {
			applyCustomBaseVariant(state.baseVariant());
			activeCustomSource = ActiveCustomSource.BASE;
			return;
		}
		VirtualKeyboardLayoutSnapshot legacy = state.legacySharedFallback();
		if (legacy != null) {
			applyCustomSnapshot(legacy);
			activeCustomSource = ActiveCustomSource.LEGACY_SHARED;
			return;
		}
		activeCustomSource = ActiveCustomSource.NONE;
	}

	private void applyCustomSnapshot(VirtualKeyboardLayoutSnapshot snapshot) {
		if (!applyLayoutSnapshotInMemory(snapshot)) return;
		applyGroupedSnapshot(snapshot);
		standardTemplateEdited = false;
		setLayoutVariantInMemory(TYPE_CUSTOM);
		sanitizeStoredGeometry();
		rebuildAnalogStick();
	}

	private void applyCustomBaseVariant(int variant) {
		applyingStandardTemplate = true;
		try {
			if (!isStandardTemplate(variant)) {
				applyBuiltInLayoutInMemory(variant);
				settings.virtualDpadEnabled = false;
				settings.virtualAnalogEnabled = false;
				standardTemplateEdited = false;
				setLayoutVariantInMemory(TYPE_CUSTOM);
				rebuildAnalogStick();
				return;
			}

			applyBuiltInLayoutInMemory(TYPE_NUMBERS_ARROWS);
			applyStandardLegacyVisibility();
			StandardVirtualControlsLayout layout = standardTemplateLayout();
			arrangeStandardLegacyButtons(layout);

			float width = Math.max(1.0f, screenBounds == null ? 1.0f : screenBounds.width());
			float height = Math.max(1.0f, screenBounds == null ? 1.0f : screenBounds.height());
			float originLeft = screenBounds == null ? 0.0f : screenBounds.left;
			float originTop = screenBounds == null ? 0.0f : screenBounds.top;
			float movementCenterX = (layout.movementCenterX - originLeft) / width;
			float movementCenterY = (layout.movementCenterY - originTop) / height;
			float shortest = Math.max(1.0f, Math.min(width, height));
			float movementRadius = clamp(
					layout.movementRadius / shortest,
					MIN_RADIUS_FRACTION,
					MAX_RADIUS_FRACTION);

			settings.virtualDpadEnabled = variant == TYPE_DPAD_STANDARD;
			settings.virtualAnalogEnabled = variant == TYPE_ANALOG_STANDARD;
			settings.virtualDpadCenterX = clamp(movementCenterX, 0.0f, 1.0f);
			settings.virtualDpadCenterY = clamp(movementCenterY, 0.0f, 1.0f);
			settings.virtualDpadRadius = movementRadius;
			settings.virtualAnalogCenterX = clamp(movementCenterX, 0.0f, 1.0f);
			settings.virtualAnalogCenterY = clamp(movementCenterY, 0.0f, 1.0f);
			settings.virtualAnalogRadius = movementRadius;
			standardTemplateEdited = false;
			setLayoutVariantInMemory(TYPE_CUSTOM);
			rebuildAnalogStick();
		} finally {
			applyingStandardTemplate = false;
		}
	}

	private static boolean rectChanged(RectF rect, float left, float top, float right, float bottom) {
		if (rect == null || rect.width() <= 0.0f || rect.height() <= 0.0f) return true;
		return Math.abs(rect.left - left) > 0.5f ||
				Math.abs(rect.top - top) > 0.5f ||
				Math.abs(rect.right - right) > 0.5f ||
				Math.abs(rect.bottom - bottom) > 0.5f;
	}

	private void scheduleStandardTemplateReflow(int variant) {
		if (standardTemplateReflowPosted || overlayView == null) return;
		standardTemplateReflowPosted = true;
		overlayView.post(() -> {
			standardTemplateReflowPosted = false;
			if (!applyingStandardTemplate && !standardTemplateEdited && getLayout() == variant &&
					isStandardTemplate(variant)) {
				// Viewport-driven reflow is automatic normalization, not a user-owned layout edit.
				applyControlsLayout(variant, false);
			}
		});
	}

	@Override
	public void setLayoutEditMode(int mode) {
		clearLegacyEditTracking();
		if (mode != LAYOUT_EOF) overlayVisible = true;
		super.setLayoutEditMode(mode);
	}

	@Override
	public void paint(CanvasWrapper graphics) {
		if (getLayoutEditMode() != LAYOUT_EOF && screenBounds != null) {
			paintEditGrid(graphics);
		}
		super.paint(graphics);
		if (!overlayVisible || viewport == null || screenBounds == null) return;

		if (settings.virtualDpadEnabled) paintDpad(graphics);
		if (settings.virtualAnalogEnabled) paintAnalog(graphics);
	}

	@Override
	public boolean pointerPressed(int pointer, float x, float y) {
		int mode = getLayoutEditMode();
		if (mode != LAYOUT_EOF) {
			if (legacyEditPointer >= 0 && pointer != legacyEditPointer && beginLegacyPinch(pointer, x, y)) {
				return true;
			}
			if (beginGroupedPinch(pointer, x, y)) return true;
			if (beginGroupedEdit(pointer, x, y)) return true;
			if (mode == LAYOUT_KEYS) {
				boolean consumed = super.pointerPressed(pointer, x, y);
				if (consumed) {
					legacyEditPointer = pointer;
					legacyPinchPointer = -1;
					legacyEditX = x;
					legacyEditY = y;
					notifyLayoutEditStateChanged();
				}
				return consumed;
			}
		}
		if (mode == LAYOUT_EOF) {
			if (beginDpad(pointer, x, y)) return true;
			if (beginAnalog(pointer, x, y)) return true;
		}
		return super.pointerPressed(pointer, x, y);
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		if (editControl != EditControl.NONE) {
			if (pointer == editPointer) {
				markActiveOrientationEdited();
				editPointerX = x;
				editPointerY = y;
				if (editPinchPointer >= 0) updateGroupedPinch();
				else updateGroupedMove(x, y);
				invalidateOverlay();
				return true;
			}
			if (pointer == editPinchPointer) {
				markActiveOrientationEdited();
				editPinchX = x;
				editPinchY = y;
				updateGroupedPinch();
				invalidateOverlay();
				return true;
			}
		}
		if (legacyEditPointer >= 0) {
			if (pointer == legacyEditPointer) {
				markActiveOrientationEdited();
				legacyEditX = x;
				legacyEditY = y;
				if (legacyPinchPointer >= 0) {
					updateLegacyPinch();
					return true;
				}
				return super.pointerDragged(pointer, x, y);
			}
			if (pointer == legacyPinchPointer) {
				markActiveOrientationEdited();
				legacyPinchX = x;
				legacyPinchY = y;
				updateLegacyPinch();
				return true;
			}
		}
		if (pointer == dpadPointer && dpadToken != null) {
			Set<VirtualDpadDirection> directions = dpadController.move(dpadToken, dpadGeometry(), x, y);
			updateDpadOutput(directions);
			invalidateOverlay();
			return true;
		}
		if (pointer == analogPointer && analogToken != null) {
			VirtualAnalogSample sample = analogStick.move(
					analogToken, x - screenBounds.left, y - screenBounds.top);
			if (sample != null) {
				updateAnalogOutput(sample);
				invalidateOverlay();
			}
			return true;
		}
		return super.pointerDragged(pointer, x, y);
	}

	@Override
	public boolean pointerReleased(int pointer, float x, float y) {
		if (pointer == editPinchPointer) {
			editPinchPointer = -1;
			resetMoveOffsetFromPrimary();
			invalidateOverlay();
			notifyLayoutEditStateChanged();
			return true;
		}
		if (pointer == editPointer) {
			if (editPinchPointer >= 0) {
				editPointer = editPinchPointer;
				editPointerX = editPinchX;
				editPointerY = editPinchY;
				editPinchPointer = -1;
				resetMoveOffsetFromPrimary();
				invalidateOverlay();
				return true;
			}
			finishGroupedEdit();
			return true;
		}
		if (legacyEditPointer >= 0) {
			if (legacyPinchPointer >= 0 && pointer == legacyPinchPointer) {
				finishLegacyPinchAndResumeMove(false);
				return true;
			}
			if (pointer == legacyEditPointer) {
				if (legacyPinchPointer >= 0) {
					finishLegacyPinchAndResumeMove(true);
					return true;
				}
				boolean consumed = super.pointerReleased(pointer, x, y);
				clearLegacyEditTracking();
				return consumed;
			}
		}
		if (pointer == dpadPointer) {
			endDpad();
			return true;
		}
		if (pointer == analogPointer) {
			endAnalog();
			return true;
		}
		return super.pointerReleased(pointer, x, y);
	}

	@Override
	public void cancel() {
		boolean restorePublicEditMode = getLayoutEditMode() == LAYOUT_SCALES;
		editPointer = -1;
		editPinchPointer = -1;
		editControl = EditControl.NONE;
		clearLegacyEditTracking();
		endDpad();
		endAnalog();
		super.cancel();
		if (restorePublicEditMode) {
			// LAYOUT_SCALES is only an internal bridge used during a legacy two-finger pinch.
			// A lifecycle cancellation (including rotation) must not expose it to the new surface.
			super.setLayoutEditMode(LAYOUT_KEYS);
		}
		notifyLayoutEditStateChanged();
	}

	@Override
	public void show() {
		overlayVisible = true;
		super.show();
	}

	@Override
	public void run() {
		overlayVisible = false;
		super.run();
	}

	private boolean beginDpad(int pointer, float x, float y) {
		if (!settings.virtualDpadEnabled || target == null || screenBounds == null ||
				dpadPointer >= 0 || pointer < 0 || !insideControl(x, y, dpadGeometry(), CONTROL_HIT_SCALE)) {
			return false;
		}
		long generation = target.inputGeneration();
		PointerSourceToken token = new PointerSourceToken(PointerSourceKind.VIRTUAL, pointer, target, generation);
		dpadPointer = pointer;
		dpadToken = token;
		dpadSequence = dpadSequence == Long.MAX_VALUE ? 1L : dpadSequence + 1L;
		dpadChannel = "virtual-dpad-group:" + dpadSequence + ":" + pointer;
		updateDpadOutput(dpadController.begin(token, dpadGeometry(), x, y));
		feedback();
		invalidateOverlay();
		return true;
	}

	private void endDpad() {
		PointerSourceToken token = dpadToken;
		Canvas canvas = target;
		String channel = dpadChannel;
		if (token != null) dpadController.end(token);
		if (token != null && canvas != null && channel != null) {
			canvas.inputReleased(
					"vk-dpad@" + Integer.toHexString(System.identityHashCode(this)),
					token.getGeneration(), "virtual-dpad", channel);
		}
		dpadPointer = -1;
		dpadToken = null;
		dpadChannel = null;
		invalidateOverlay();
	}

	private void updateDpadOutput(Set<VirtualDpadDirection> directions) {
		Canvas canvas = target;
		PointerSourceToken token = dpadToken;
		String channel = dpadChannel;
		if (canvas == null || token == null || channel == null) return;
		int[] keyCodes = new int[directions.size()];
		int index = 0;
		for (VirtualDpadDirection direction : directions) {
			keyCodes[index++] = switch (direction) {
				case UP -> Canvas.KEY_UP;
				case DOWN -> Canvas.KEY_DOWN;
				case LEFT -> Canvas.KEY_LEFT;
				case RIGHT -> Canvas.KEY_RIGHT;
			};
		}
		canvas.inputUpdated(
				"vk-dpad@" + Integer.toHexString(System.identityHashCode(this)),
				token.getGeneration(), "virtual-dpad", channel, keyCodes);
	}

	private boolean beginAnalog(int pointer, float x, float y) {
		if (!settings.virtualAnalogEnabled || target == null || viewport == null || screenBounds == null ||
				analogPointer >= 0 || pointer < 0 || !insideAnalog(x, y, CONTROL_HIT_SCALE)) return false;
		long generation = target.inputGeneration();
		PointerSourceToken token = new PointerSourceToken(PointerSourceKind.VIRTUAL, pointer, target, generation);
		if (analogStick.begin(token, viewport, null, null) == null) return false;
		analogPointer = pointer;
		analogToken = token;
		analogSequence = analogSequence == Long.MAX_VALUE ? 1L : analogSequence + 1L;
		analogChannel = "virtual-analog:" + analogSequence + ":" + pointer;
		feedback();
		VirtualAnalogSample sample = analogStick.move(
				token, x - screenBounds.left, y - screenBounds.top);
		if (sample != null) updateAnalogOutput(sample);
		invalidateOverlay();
		return true;
	}

	private void endAnalog() {
		PointerSourceToken token = analogToken;
		Canvas canvas = target;
		String channel = analogChannel;
		if (token == null) {
			analogPointer = -1;
			analogChannel = null;
			directionAdapter.reset();
			if (analogStick != null) analogStick.reset();
			return;
		}
		analogStick.end(token);
		directionAdapter.reset();
		if (canvas != null && channel != null) {
			canvas.inputReleased(
					"vk-analog@" + Integer.toHexString(System.identityHashCode(this)),
					token.getGeneration(), "virtual-analog", channel);
		}
		analogPointer = -1;
		analogToken = null;
		analogChannel = null;
		invalidateOverlay();
	}

	private void updateAnalogOutput(VirtualAnalogSample sample) {
		Canvas canvas = target;
		PointerSourceToken token = analogToken;
		String channel = analogChannel;
		if (canvas == null || token == null || channel == null) return;
		List<StickProcessor.DirectionKey> keys = directionAdapter.update(sample);
		int[] keyCodes = new int[keys.size()];
		for (int i = 0; i < keys.size(); i++) keyCodes[i] = guestCode(keys.get(i));
		canvas.inputUpdated(
				"vk-analog@" + Integer.toHexString(System.identityHashCode(this)),
				token.getGeneration(), "virtual-analog", channel, keyCodes);
	}

	private boolean beginGroupedEdit(int pointer, float x, float y) {
		if (screenBounds == null || pointer < 0 || editPointer >= 0 || legacyEditPointer >= 0) return false;
		EditControl selected = EditControl.NONE;
		VirtualDpadGeometry geometry = null;
		if (settings.virtualDpadEnabled && insideControl(x, y, dpadGeometry(), 1.15f)) {
			selected = EditControl.DPAD;
			geometry = dpadGeometry();
		} else if (settings.virtualAnalogEnabled && insideAnalog(x, y, 1.15f)) {
			selected = EditControl.ANALOG;
			geometry = analogGeometry();
		}
		if (selected == EditControl.NONE || geometry == null) return false;

		editPointer = pointer;
		editPinchPointer = -1;
		editControl = selected;
		editPointerX = x;
		editPointerY = y;
		editOffsetX = x - geometry.getCenterX();
		editOffsetY = y - geometry.getCenterY();
		invalidateOverlay();
		notifyLayoutEditStateChanged();
		return true;
	}

	private boolean beginGroupedPinch(int pointer, float x, float y) {
		if (screenBounds == null || pointer < 0 || editControl == EditControl.NONE ||
				editPointer < 0 || editPinchPointer >= 0 || pointer == editPointer) return false;
		VirtualDpadGeometry geometry = currentEditGeometry();
		if (geometry == null || !insideControl(x, y, geometry, EDIT_SECOND_FINGER_HIT_SCALE)) return false;
		editPinchPointer = pointer;
		editPinchX = x;
		editPinchY = y;
		pinchStartDistance = Math.max(1.0f,
				(float) Math.hypot(editPinchX - editPointerX, editPinchY - editPointerY));
		pinchStartRadius = geometry.getRadius();
		notifyLayoutEditStateChanged();
		return true;
	}

	private void updateGroupedMove(float x, float y) {
		if (screenBounds == null || editControl == EditControl.NONE) return;
		float cx = snapPixels(x - editOffsetX, gridStep());
		float cy = snapPixels(y - editOffsetY, gridStep());
		float radius = controlRadiusPixels(editControl);
		cx = clamp(cx, screenBounds.left + radius, screenBounds.right - radius);
		cy = clamp(cy, screenBounds.top + radius, screenBounds.bottom - radius);
		setControlCenter(
				editControl,
				(cx - screenBounds.left) / Math.max(1.0f, screenBounds.width()),
				(cy - screenBounds.top) / Math.max(1.0f, screenBounds.height()));
		notifyLayoutEditStateChanged();
	}

	private void updateGroupedPinch() {
		if (screenBounds == null || editControl == EditControl.NONE || editPinchPointer < 0) return;
		float distance = (float) Math.hypot(editPinchX - editPointerX, editPinchY - editPointerY);
		float radius = pinchStartRadius * distance / Math.max(1.0f, pinchStartDistance);
		float shortest = Math.max(1.0f, Math.min(screenBounds.width(), screenBounds.height()));
		setControlRadius(editControl, radius / shortest);

		// Pinch is an explicit edit. If the larger radius makes the preferred center impossible,
		// commit the viewport-safe center together with the new radius rather than persisting a
		// center/radius pair that can never be rendered fully on-screen.
		VirtualDpadGeometry resolved = resolvedControlGeometry(editControl);
		setControlCenter(
				editControl,
				(resolved.getCenterX() - screenBounds.left) /
						Math.max(1.0f, screenBounds.width()),
				(resolved.getCenterY() - screenBounds.top) /
						Math.max(1.0f, screenBounds.height()));
		notifyLayoutEditStateChanged();
	}

	private void resetMoveOffsetFromPrimary() {
		VirtualDpadGeometry geometry = currentEditGeometry();
		if (geometry == null) return;
		editOffsetX = editPointerX - geometry.getCenterX();
		editOffsetY = editPointerY - geometry.getCenterY();
	}

	private VirtualDpadGeometry currentEditGeometry() {
		if (screenBounds == null) return null;
		return switch (editControl) {
			case DPAD -> dpadGeometry();
			case ANALOG -> analogGeometry();
			default -> null;
		};
	}

	private void finishGroupedEdit() {
		if (editControl == EditControl.ANALOG) rebuildAnalogStick();
		editPointer = -1;
		editPinchPointer = -1;
		editControl = EditControl.NONE;
		invalidateOverlay();
		notifyLayoutEditStateChanged();
	}

	private boolean beginLegacyPinch(int pointer, float x, float y) {
		if (getLayoutEditMode() != LAYOUT_KEYS || legacyEditPointer < 0 ||
				legacyPinchPointer >= 0 || pointer == legacyEditPointer) return false;

		/* Finalize the current move before handing the selected key's scale group to the legacy
		 * resize engine. The second finger itself is not forwarded to VirtualKeyboard. */
		super.pointerReleased(legacyEditPointer, legacyEditX, legacyEditY);
		super.setLayoutEditMode(LAYOUT_SCALES);
		if (!super.pointerPressed(legacyEditPointer, legacyEditX, legacyEditY)) {
			super.setLayoutEditMode(LAYOUT_KEYS);
			if (!super.pointerPressed(legacyEditPointer, legacyEditX, legacyEditY)) {
				clearLegacyEditTracking();
			}
			return false;
		}

		legacyPinchPointer = pointer;
		legacyPinchX = x;
		legacyPinchY = y;
		legacyPinchOriginX = legacyEditX;
		legacyPinchOriginY = legacyEditY;
		legacyPinchStartSpanX = Math.abs(legacyPinchX - legacyEditX);
		legacyPinchStartSpanY = Math.abs(legacyPinchY - legacyEditY);
		notifyLayoutEditStateChanged();
		return true;
	}

	private void updateLegacyPinch() {
		if (legacyEditPointer < 0 || legacyPinchPointer < 0 || getLayoutEditMode() != LAYOUT_SCALES) return;
		float spanX = Math.abs(legacyPinchX - legacyEditX);
		float spanY = Math.abs(legacyPinchY - legacyEditY);
		float deltaX = spanX - legacyPinchStartSpanX;
		float deltaY = spanY - legacyPinchStartSpanY;
		/*
		 * VirtualKeyboard's legacy scale engine already persists independent X/Y scales. Feed
		 * horizontal and vertical finger separation independently so a horizontal gesture changes
		 * only width, a vertical gesture changes only height, and a diagonal gesture changes both.
		 */
		super.pointerDragged(
				legacyEditPointer,
				legacyPinchOriginX + deltaX,
				legacyPinchOriginY - deltaY);
	}

	private void finishLegacyPinchAndResumeMove(boolean primaryReleased) {
		if (getLayoutEditMode() == LAYOUT_SCALES) {
			super.pointerReleased(legacyEditPointer, legacyEditX, legacyEditY);
		}
		int nextPointer = primaryReleased ? legacyPinchPointer : legacyEditPointer;
		float nextX = primaryReleased ? legacyPinchX : legacyEditX;
		float nextY = primaryReleased ? legacyPinchY : legacyEditY;
		super.setLayoutEditMode(LAYOUT_KEYS);
		legacyEditPointer = nextPointer;
		legacyPinchPointer = -1;
		legacyEditX = nextX;
		legacyEditY = nextY;
		if (nextPointer < 0 || !super.pointerPressed(nextPointer, nextX, nextY)) {
			clearLegacyEditTracking();
		}
		invalidateOverlay();
		notifyLayoutEditStateChanged();
	}

	private void clearLegacyEditTracking() {
		legacyEditPointer = -1;
		legacyPinchPointer = -1;
		legacyEditX = 0.0f;
		legacyEditY = 0.0f;
		legacyPinchX = 0.0f;
		legacyPinchY = 0.0f;
		legacyPinchOriginX = 0.0f;
		legacyPinchOriginY = 0.0f;
		legacyPinchStartSpanX = 0.0f;
		legacyPinchStartSpanY = 0.0f;
		notifyLayoutEditStateChanged();
	}

	private void paintEditGrid(CanvasWrapper graphics) {
		float step = gridStep();
		if (step <= 1.0f) return;
		int color = (0x40 << 24) | (settings.vkOutlineColor & 0x00FFFFFF);
		graphics.setFillColor(color);
		float thickness = Math.max(1.0f, step / 24.0f);
		for (float x = screenBounds.left; x <= screenBounds.right + 0.5f; x += step) {
			graphics.fillRect(new RectF(x - thickness / 2f, screenBounds.top,
					x + thickness / 2f, screenBounds.bottom));
		}
		for (float y = screenBounds.top; y <= screenBounds.bottom + 0.5f; y += step) {
			graphics.fillRect(new RectF(screenBounds.left, y - thickness / 2f,
					screenBounds.right, y + thickness / 2f));
		}
	}

	private void paintDpad(CanvasWrapper graphics) {
		VirtualDpadGeometry geometry = dpadGeometry();
		float cx = geometry.getCenterX();
		float cy = geometry.getCenterY();
		float r = geometry.getRadius();
		int alpha = controlAlpha(cx, cy, r);
		if (alpha == 0) return;
		Set<VirtualDpadDirection> pressed = dpadToken == null
				? java.util.Collections.emptySet()
				: dpadController.state(dpadToken);
		int base = VirtualControlAppearance.background(settings, false);
		int selected = VirtualControlAppearance.background(settings, true);
		int outline = VirtualControlAppearance.outline(settings);
		int foreground = VirtualControlAppearance.foreground(settings, false);
		int selectedForeground = VirtualControlAppearance.foreground(settings, true);
		float stroke = graphics.getControlOutlineWidth();

		int layer = beginControlLayer(graphics, cx, cy, r * 1.06f, alpha);
		try {
			buildDpadOutline(dpadOutline, cx, cy, r);
			graphics.fillControlPath(dpadOutline, opaqueColor(base));
			// Inset pressed surfaces keep diagonals distinct and avoid harsh rectangular joins.
			if (pressed.contains(VirtualDpadDirection.UP)) {
				paintDpadPressed(graphics, cx, cy, r, VirtualDpadDirection.UP, selected);
			}
			if (pressed.contains(VirtualDpadDirection.DOWN)) {
				paintDpadPressed(graphics, cx, cy, r, VirtualDpadDirection.DOWN, selected);
			}
			if (pressed.contains(VirtualDpadDirection.LEFT)) {
				paintDpadPressed(graphics, cx, cy, r, VirtualDpadDirection.LEFT, selected);
			}
			if (pressed.contains(VirtualDpadDirection.RIGHT)) {
				paintDpadPressed(graphics, cx, cy, r, VirtualDpadDirection.RIGHT, selected);
			}
			graphics.strokeControlPath(dpadOutline, opaqueColor(outline), stroke);
			int hubOutline = VirtualControlAppearance.mixRgb(base, outline, 0.45f);
			fillCircle(graphics, cx, cy, r * 0.175f, base);
			graphics.strokeControlCircle(cx, cy, r * 0.175f,
					opaqueColor(hubOutline), stroke);
			if (getLayoutEditMode() != LAYOUT_EOF && editControl == EditControl.DPAD) {
				graphics.strokeControlCircle(cx, cy, r * 1.02f,
						opaqueColor(selected), stroke);
			}

			paintDpadArrow(graphics, cx, cy - r * 0.61f, r * 0.105f,
					VirtualDpadDirection.UP, pressed.contains(VirtualDpadDirection.UP),
					foreground, selectedForeground);
			paintDpadArrow(graphics, cx, cy + r * 0.61f, r * 0.105f,
					VirtualDpadDirection.DOWN, pressed.contains(VirtualDpadDirection.DOWN),
					foreground, selectedForeground);
			paintDpadArrow(graphics, cx - r * 0.61f, cy, r * 0.105f,
					VirtualDpadDirection.LEFT, pressed.contains(VirtualDpadDirection.LEFT),
					foreground, selectedForeground);
			paintDpadArrow(graphics, cx + r * 0.61f, cy, r * 0.105f,
					VirtualDpadDirection.RIGHT, pressed.contains(VirtualDpadDirection.RIGHT),
					foreground, selectedForeground);
		} finally {
			endControlLayer(graphics, layer);
		}
	}

	private void paintDpadPressed(CanvasWrapper graphics, float cx, float cy,
			float r, VirtualDpadDirection direction, int selected) {
		float arm = r * 0.34f;
		float inset = r * 0.045f;
		float inner = r * 0.235f;
		float end = r - inset;
		switch (direction) {
			case UP:
				paintRect.set(cx - arm + inset, cy - end, cx + arm - inset, cy - inner);
				break;
			case DOWN:
				paintRect.set(cx - arm + inset, cy + inner, cx + arm - inset, cy + end);
				break;
			case LEFT:
				paintRect.set(cx - end, cy - arm + inset, cx - inner, cy + arm - inset);
				break;
			case RIGHT:
				paintRect.set(cx + inner, cy - arm + inset, cx + end, cy + arm - inset);
				break;
			default:
				return;
		}
		graphics.fillControlRoundRect(paintRect, r * 0.095f, opaqueColor(selected));
	}

	private void paintDpadArrow(CanvasWrapper graphics, float x, float y, float half,
			VirtualDpadDirection direction, boolean pressed, int foreground, int selectedForeground) {
		dpadArrow.rewind();
		switch (direction) {
			case UP:
				dpadArrow.moveTo(x, y - half);
				dpadArrow.lineTo(x - half, y + half);
				dpadArrow.lineTo(x + half, y + half);
				break;
			case DOWN:
				dpadArrow.moveTo(x, y + half);
				dpadArrow.lineTo(x - half, y - half);
				dpadArrow.lineTo(x + half, y - half);
				break;
			case LEFT:
				dpadArrow.moveTo(x - half, y);
				dpadArrow.lineTo(x + half, y - half);
				dpadArrow.lineTo(x + half, y + half);
				break;
			case RIGHT:
				dpadArrow.moveTo(x + half, y);
				dpadArrow.lineTo(x - half, y - half);
				dpadArrow.lineTo(x - half, y + half);
				break;
			default:
				return;
		}
		dpadArrow.close();
		graphics.fillControlPath(dpadArrow,
				opaqueColor(pressed ? selectedForeground : foreground));
	}

	/** Rounded outer corners and softened inner notches, with one continuous outline. */
	private static void buildDpadOutline(Path path, float cx, float cy, float r) {
		float a = r * 0.34f;
		float corner = r * 0.16f;
		float inset = r * 0.09f;
		path.rewind();
		path.moveTo(cx - a + corner, cy - r);
		path.lineTo(cx + a - corner, cy - r);
		path.quadTo(cx + a, cy - r, cx + a, cy - r + corner);
		path.lineTo(cx + a, cy - a - inset);
		path.quadTo(cx + a, cy - a, cx + a + inset, cy - a);
		path.lineTo(cx + r - corner, cy - a);
		path.quadTo(cx + r, cy - a, cx + r, cy - a + corner);
		path.lineTo(cx + r, cy + a - corner);
		path.quadTo(cx + r, cy + a, cx + r - corner, cy + a);
		path.lineTo(cx + a + inset, cy + a);
		path.quadTo(cx + a, cy + a, cx + a, cy + a + inset);
		path.lineTo(cx + a, cy + r - corner);
		path.quadTo(cx + a, cy + r, cx + a - corner, cy + r);
		path.lineTo(cx - a + corner, cy + r);
		path.quadTo(cx - a, cy + r, cx - a, cy + r - corner);
		path.lineTo(cx - a, cy + a + inset);
		path.quadTo(cx - a, cy + a, cx - a - inset, cy + a);
		path.lineTo(cx - r + corner, cy + a);
		path.quadTo(cx - r, cy + a, cx - r, cy + a - corner);
		path.lineTo(cx - r, cy - a + corner);
		path.quadTo(cx - r, cy - a, cx - r + corner, cy - a);
		path.lineTo(cx - a - inset, cy - a);
		path.quadTo(cx - a, cy - a, cx - a, cy - a - inset);
		path.lineTo(cx - a, cy - r + corner);
		path.quadTo(cx - a, cy - r, cx - a + corner, cy - r);
		path.close();
	}

	private void paintAnalog(CanvasWrapper graphics) {
		boolean editingAnalog = getLayoutEditMode() != LAYOUT_EOF &&
				editControl == EditControl.ANALOG;
		float centerX;
		float centerY;
		float radius;
		float rawThumbX;
		float rawThumbY;
		boolean active;
		if (editingAnalog) {
			VirtualDpadGeometry geometry = analogGeometry();
			centerX = geometry.getCenterX();
			centerY = geometry.getCenterY();
			radius = geometry.getRadius();
			rawThumbX = centerX;
			rawThumbY = centerY;
			active = false;
		} else {
			VirtualAnalogVisualState visual = analogStick.visualState(viewport);
			centerX = screenBounds.left + visual.getCenterX();
			centerY = screenBounds.top + visual.getCenterY();
			radius = visual.getRadius();
			rawThumbX = screenBounds.left + visual.getThumbX();
			rawThumbY = screenBounds.top + visual.getThumbY();
			active = visual.getActive();
		}
		int alpha = controlAlpha(centerX, centerY, radius);
		if (alpha == 0) return;
		float thumbX = centerX + (rawThumbX - centerX) * 0.47f;
		float thumbY = centerY + (rawThumbY - centerY) * 0.47f;
		int base = VirtualControlAppearance.background(settings, false);
		int outline = VirtualControlAppearance.outline(settings);
		int thumbColor = VirtualControlAppearance.analogThumb(settings, active);
		int indicator = VirtualControlAppearance.foreground(settings, active);

		int layer = beginControlLayer(graphics, centerX, centerY, radius * 1.06f, alpha);
		try {
			float stroke = graphics.getControlOutlineWidth();
			// Classic concentric recess with palette-derived shading, composited at one opacity.
			fillCircle(graphics, centerX, centerY, radius * 0.94f, base);
			fillCircle(graphics, centerX, centerY, radius * 0.69f,
					VirtualControlAppearance.mixRgb(base, 0x000000, 0.26f));
			fillCircle(graphics, centerX, centerY, radius * 0.59f,
					VirtualControlAppearance.mixRgb(base, outline, 0.07f));
			graphics.strokeControlCircle(centerX, centerY, radius * 0.94f,
					opaqueColor(outline), stroke);

			// Recessed rim and layered thumb restore the old physical-joystick character.
			float thumbRadius = radius * 0.395f;
			fillCircle(graphics, thumbX, thumbY, thumbRadius * 1.075f,
					VirtualControlAppearance.mixRgb(thumbColor, outline, 0.38f));
			fillCircle(graphics, thumbX, thumbY, thumbRadius, thumbColor);
			fillCircle(graphics, thumbX, thumbY, thumbRadius * 0.82f,
					VirtualControlAppearance.mixRgb(thumbColor, indicator, 0.10f));
			graphics.strokeControlCircle(thumbX, thumbY, thumbRadius,
					opaqueColor(VirtualControlAppearance.mixRgb(thumbColor, outline, 0.56f)),
					stroke);
			// A subtle centered cap detail stays balanced as the thumb moves.
			fillCircle(graphics, thumbX, thumbY, thumbRadius * 0.11f,
					VirtualControlAppearance.mixRgb(thumbColor, indicator, 0.16f));
			if (editingAnalog) {
				graphics.strokeControlCircle(centerX, centerY, radius * 1.02f,
						opaqueColor(VirtualControlAppearance.background(settings, true)), stroke);
			}
		} finally {
			endControlLayer(graphics, layer);
		}
	}

	private void fillCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
		graphics.fillControlCircle(cx, cy, radius, opaqueColor(color));
	}

	private static int opaqueColor(int rgb) {
		return 0xFF000000 | rgb;
	}

	private int beginControlLayer(CanvasWrapper graphics,
			float cx, float cy, float radius, int alpha) {
		if (alpha == 255) return -1;
		paintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
		return graphics.saveAlphaLayer(paintRect, alpha);
	}

	private void endControlLayer(CanvasWrapper graphics, int layer) {
		if (layer >= 0) graphics.restoreAlphaLayer(layer);
	}

	private VirtualDpadGeometry dpadGeometry() {
		return resolveGroupedGeometry(
				settings.virtualDpadCenterX,
				settings.virtualDpadCenterY,
				settings.virtualDpadRadius);
	}

	private VirtualDpadGeometry analogGeometry() {
		return resolveGroupedGeometry(
				settings.virtualAnalogCenterX,
				settings.virtualAnalogCenterY,
				settings.virtualAnalogRadius);
	}

	private VirtualDpadGeometry resolvedControlGeometry(EditControl control) {
		return control == EditControl.DPAD ? dpadGeometry() : analogGeometry();
	}

	private VirtualDpadGeometry resolveGroupedGeometry(float centerX, float centerY, float radius) {
		VirtualDpadGeometry local = VirtualControlGeometryResolver.resolve(
				Math.max(1.0f, screenBounds.width()),
				Math.max(1.0f, screenBounds.height()),
				centerX,
				centerY,
				radius);
		return new VirtualDpadGeometry(
				screenBounds.left + local.getCenterX(),
				screenBounds.top + local.getCenterY(),
				local.getRadius());
	}

	private boolean insideAnalog(float x, float y, float scale) {
		return insideControl(x, y, analogGeometry(), scale);
	}

	private static boolean insideControl(float x, float y, VirtualDpadGeometry geometry, float scale) {
		float dx = x - geometry.getCenterX();
		float dy = y - geometry.getCenterY();
		float radius = geometry.getRadius() * scale;
		return dx * dx + dy * dy <= radius * radius;
	}

	private float controlRadiusPixels(EditControl control) {
		float shortest = Math.max(1.0f, Math.min(screenBounds.width(), screenBounds.height()));
		return (control == EditControl.DPAD ? settings.virtualDpadRadius : settings.virtualAnalogRadius) * shortest;
	}

	private void setControlCenter(EditControl control, float x, float y) {
		if (control == EditControl.DPAD) {
			settings.virtualDpadCenterX = clamp(x, 0.0f, 1.0f);
			settings.virtualDpadCenterY = clamp(y, 0.0f, 1.0f);
		} else {
			settings.virtualAnalogCenterX = clamp(x, 0.0f, 1.0f);
			settings.virtualAnalogCenterY = clamp(y, 0.0f, 1.0f);
		}
	}

	private void setControlRadius(EditControl control, float radius) {
		float safe = clamp(radius, MIN_RADIUS_FRACTION, MAX_RADIUS_FRACTION);
		if (control == EditControl.DPAD) settings.virtualDpadRadius = safe;
		else settings.virtualAnalogRadius = safe;
	}

	private float gridStep() {
		if (screenBounds == null) return 0.0f;
		return Math.max(4.0f, Math.min(screenBounds.width(), screenBounds.height()) / GRID_DIVISIONS);
	}

	private static float snapPixels(float value, float step) {
		if (step <= 0.0f) return value;
		return Math.round(value / step) * step;
	}

	private int controlAlpha(float cx, float cy, float radius) {
		boolean overGuest = guestBounds.width() <= 0f || guestBounds.height() <= 0f ||
				cx + radius > guestBounds.left && cx - radius < guestBounds.right &&
				cy + radius > guestBounds.top && cy - radius < guestBounds.bottom;
		return VirtualControlAppearance.opacity(
				settings.vkAlpha, getLayoutEditMode() != LAYOUT_EOF,
				settings.vkForceOpacity && !overGuest);
	}

	private void rebuildAnalogStick() {
		analogStick = new VirtualAnalogStick(
				new VirtualAnalogStickSettings(
						settings.virtualAnalogCenterX,
						settings.virtualAnalogCenterY,
						settings.virtualAnalogRadius,
						VirtualAnalogStickMode.FIXED));
	}

	private void sanitizeStoredGeometry() {
		settings.virtualDpadCenterX = finiteOr(settings.virtualDpadCenterX, DEFAULT_DPAD_CENTER_X);
		settings.virtualDpadCenterY = finiteOr(settings.virtualDpadCenterY, DEFAULT_DPAD_CENTER_Y);
		settings.virtualDpadRadius = finiteOr(settings.virtualDpadRadius, DEFAULT_DPAD_RADIUS);
		settings.virtualAnalogCenterX = finiteOr(settings.virtualAnalogCenterX, DEFAULT_ANALOG_CENTER_X);
		settings.virtualAnalogCenterY = finiteOr(settings.virtualAnalogCenterY, DEFAULT_ANALOG_CENTER_Y);
		settings.virtualAnalogRadius = finiteOr(settings.virtualAnalogRadius, DEFAULT_ANALOG_RADIUS);
		settings.virtualDpadCenterX = clamp(settings.virtualDpadCenterX, 0.0f, 1.0f);
		settings.virtualDpadCenterY = clamp(settings.virtualDpadCenterY, 0.0f, 1.0f);
		settings.virtualAnalogCenterX = clamp(settings.virtualAnalogCenterX, 0.0f, 1.0f);
		settings.virtualAnalogCenterY = clamp(settings.virtualAnalogCenterY, 0.0f, 1.0f);
		settings.virtualDpadRadius = clamp(settings.virtualDpadRadius, MIN_RADIUS_FRACTION, MAX_RADIUS_FRACTION);
		settings.virtualAnalogRadius = clamp(settings.virtualAnalogRadius, MIN_RADIUS_FRACTION, MAX_RADIUS_FRACTION);
	}

	private static float finiteOr(float value, float fallback) {
		return Float.isFinite(value) ? value : fallback;
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private void forceLegacyDirectionsHidden(boolean[] hidden) {
		if (hidden == null) return;
		String[] names = super.getKeyNames();
		for (int i = 0; i < Math.min(hidden.length, names.length); i++) {
			String name = names[i];
			if ("↑".equals(name) || "↓".equals(name) || "←".equals(name) || "→".equals(name) ||
					"↖".equals(name) || "↗".equals(name) || "↙".equals(name) || "↘".equals(name)) {
				hidden[i] = true;
			}
		}
	}

	private void feedback() {
		if (settings.vkFeedback) ContextHolder.vibrateKey(FEEDBACK_DURATION_MS);
	}

	private void invalidateOverlay() {
		if (overlayView != null) overlayView.postInvalidate();
	}

	private static int guestCode(StickProcessor.DirectionKey key) {
		return switch (key) {
			case UP -> Canvas.KEY_UP;
			case DOWN -> Canvas.KEY_DOWN;
			case LEFT -> Canvas.KEY_LEFT;
			case RIGHT -> Canvas.KEY_RIGHT;
			case NUM1 -> Canvas.KEY_NUM1;
			case NUM2 -> Canvas.KEY_NUM2;
			case NUM3 -> Canvas.KEY_NUM3;
			case NUM4 -> Canvas.KEY_NUM4;
			case NUM6 -> Canvas.KEY_NUM6;
			case NUM7 -> Canvas.KEY_NUM7;
			case NUM8 -> Canvas.KEY_NUM8;
			case NUM9 -> Canvas.KEY_NUM9;
		};
	}
}
