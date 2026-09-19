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

import android.graphics.RectF;
import android.view.View;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
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

	private static final int LEGACY_TEMPLATE_NUMBERS_ARROWS = 3;
	private static final int GROUPED_CONTROL_COUNT = 2;
	private static final float DEFAULT_DPAD_CENTER_X = 0.82f;
	private static final float DEFAULT_DPAD_CENTER_Y = 0.78f;
	private static final float DEFAULT_DPAD_RADIUS = 0.16f;
	private static final float DEFAULT_ANALOG_CENTER_X = 0.18f;
	private static final float DEFAULT_ANALOG_CENTER_Y = 0.78f;
	private static final float DEFAULT_ANALOG_RADIUS = 0.16f;
	private static final float STANDARD_MOVEMENT_RADIUS = 0.20f;
	private static final float CONTROL_HIT_SCALE = 1.20f;
	private static final float EDIT_SECOND_FINGER_HIT_SCALE = 1.60f;
	private static final float MIN_RADIUS_FRACTION = 0.07f;
	private static final float MAX_RADIUS_FRACTION = 0.34f;
	private static final int GRID_DIVISIONS = 24;
	private static final int FEEDBACK_DURATION_MS = 50;

	private enum EditControl { NONE, DPAD, ANALOG }

	private final ProfileModel settings;
	private final VirtualDpadController dpadController = new VirtualDpadController();
	private final VirtualAnalogDirectionAdapter directionAdapter = new VirtualAnalogDirectionAdapter();
	private final RectF paintRect = new RectF();
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

	public VirtualControlsKeyboard(ProfileModel settings) {
		super(settings);
		this.settings = settings;
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
		if (states.length < legacyCount) {
			super.setKeysVisibility(states);
			return;
		}

		boolean standardTemplate = isStandardTemplate(getLayout());
		if (standardTemplate) standardTemplateEdited = true;
		boolean[] legacyStates = Arrays.copyOf(states, legacyCount);
		if (standardTemplate) forceLegacyDirectionsHidden(legacyStates);
		super.setKeysVisibility(legacyStates);

		if (states.length < legacyCount + GROUPED_CONTROL_COUNT) return;
		boolean nextDpadEnabled = !states[legacyCount];
		boolean nextAnalogEnabled = !states[legacyCount + 1];
		boolean groupedChanged = nextDpadEnabled != settings.virtualDpadEnabled ||
				nextAnalogEnabled != settings.virtualAnalogEnabled;
		if (!groupedChanged) return;

		if (!nextDpadEnabled) endDpad();
		if (!nextAnalogEnabled) endAnalog();
		settings.virtualDpadEnabled = nextDpadEnabled;
		settings.virtualAnalogEnabled = nextAnalogEnabled;
		invalidateOverlay();
	}

	@Override
	public void onLayoutChanged(int variant) {
		super.onLayoutChanged(variant);
		if (!applyingStandardTemplate) ProfilesManager.saveConfig(settings);
	}

	@Override
	public void setLayout(int variant) {
		if (!isStandardTemplate(variant)) {
			standardTemplateEdited = false;
			endDpad();
			endAnalog();
			if (variant != TYPE_CUSTOM) {
				settings.virtualDpadEnabled = false;
				settings.virtualAnalogEnabled = false;
			}
			super.setLayout(variant);
			invalidateOverlay();
			return;
		}

		applyingStandardTemplate = true;
		standardTemplateEdited = false;
		try {
			endDpad();
			endAnalog();
			// Start from the long-standing Numbers & Arrows geometry, then place only the five
			// standard action keys using the free space around the actual MIDlet viewport.
			super.setLayout(LEGACY_TEMPLATE_NUMBERS_ARROWS);
			applyStandardLegacyVisibility();
			StandardVirtualControlsLayout layout = standardTemplateLayout();
			setKeyGroupScaleByLabel(
					"L",
					layout.shoulderWidth / Math.max(1.0f, layout.keySize),
					layout.shoulderHeight / Math.max(1.0f, layout.keySize));
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
			settings.virtualDpadRadius = STANDARD_MOVEMENT_RADIUS;
			settings.virtualAnalogCenterX = clamp(movementCenterX, 0.0f, 1.0f);
			settings.virtualAnalogCenterY = clamp(movementCenterY, 0.0f, 1.0f);
			settings.virtualAnalogRadius = STANDARD_MOVEMENT_RADIUS;
			rebuildAnalogStick();
			ProfilesManager.saveConfig(settings);
			super.onLayoutChanged(variant);
			invalidateOverlay();
		} finally {
			applyingStandardTemplate = false;
		}
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
					"*".equals(name) || "0".equals(name)) {
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
		float shortest = Math.max(1.0f,
				Math.min(screenRight - screenLeft, screenBottom - screenTop));
		return StandardVirtualControlsLayout.resolve(
				screenLeft, screenTop, screenRight, screenBottom,
				STANDARD_MOVEMENT_RADIUS * shortest);
	}

	/**
	 * Lay out the standard controls as two ergonomic zones: shoulder buttons across the upper
	 * corners, movement in the lower-left, and F, *, and 0 in the lower-right.
	 */
	private void arrangeStandardLegacyButtons(StandardVirtualControlsLayout layout) {
		if (screenBounds == null || overlayView == null || layout == null) return;

		setKeyGroupScaleByLabel(
				"L",
				layout.shoulderWidth / Math.max(1.0f, layout.keySize),
				layout.shoulderHeight / Math.max(1.0f, layout.keySize));
		setKeyGroupScaleByLabel(
				"F",
				layout.fireSize / Math.max(1.0f, layout.keySize),
				layout.fireSize / Math.max(1.0f, layout.keySize));

		setKeyCenterByLabel("L", layout.shoulderLeftX, layout.shoulderCenterY);
		setKeyCenterByLabel("R", layout.shoulderRightX, layout.shoulderCenterY);
		setKeyCenterByLabel("F", layout.actionCenterX, layout.actionCenterY);
		setKeyCenterByLabel("*", layout.bottomLeftX, layout.bottomRowY);
		setKeyCenterByLabel("0", layout.bottomRightX, layout.bottomRowY);
		refreshDirectKeyLayout();
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
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		endDpad();
		endAnalog();
		boolean boundsChanged = rectChanged(screenBounds, screen.left, screen.top, screen.right, screen.bottom) ||
				rectChanged(guestBounds, left, top, right, bottom);
		int layout = getLayout();
		boolean reflowStandardTemplate = !applyingStandardTemplate && !standardTemplateEdited &&
				isStandardTemplate(layout) && boundsChanged;

		super.resize(screen, left, top, right, bottom);
		screenBounds = new RectF(screen);
		guestBounds.set(left, top, right, bottom);
		viewport = new GuestViewport(
				Math.max(1, Math.round(screen.width())),
				Math.max(1, Math.round(screen.height())));
		rebuildAnalogStick();
		if (reflowStandardTemplate) scheduleStandardTemplateReflow(layout);
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
				setLayout(variant);
			}
		});
	}

	@Override
	public void setLayoutEditMode(int mode) {
		clearLegacyEditTracking();
		super.setLayoutEditMode(mode);
	}

	@Override
	public void paint(CanvasWrapper graphics) {
		if (getLayoutEditMode() != LAYOUT_EOF && screenBounds != null) {
			paintEditGrid(graphics);
		}
		super.paint(graphics);
		if (!overlayVisible || viewport == null || screenBounds == null) return;
		if (getLayoutEditMode() == LAYOUT_EOF && settings.vkAlpha <= 0) return;

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
			standardTemplateEdited |= isStandardTemplate(getLayout());
			if (pointer == editPointer) {
				editPointerX = x;
				editPointerY = y;
				if (editPinchPointer >= 0) updateGroupedPinch();
				else updateGroupedMove(x, y);
				invalidateOverlay();
				return true;
			}
			if (pointer == editPinchPointer) {
				editPinchX = x;
				editPinchY = y;
				updateGroupedPinch();
				invalidateOverlay();
				return true;
			}
		}
		if (legacyEditPointer >= 0) {
			standardTemplateEdited |= isStandardTemplate(getLayout());
			if (pointer == legacyEditPointer) {
				legacyEditX = x;
				legacyEditY = y;
				if (legacyPinchPointer >= 0) {
					updateLegacyPinch();
					return true;
				}
				return super.pointerDragged(pointer, x, y);
			}
			if (pointer == legacyPinchPointer) {
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
		editPointer = -1;
		editPinchPointer = -1;
		editControl = EditControl.NONE;
		clearLegacyEditTracking();
		endDpad();
		endAnalog();
		super.cancel();
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
	}

	private void updateGroupedPinch() {
		if (screenBounds == null || editControl == EditControl.NONE || editPinchPointer < 0) return;
		float distance = (float) Math.hypot(editPinchX - editPointerX, editPinchY - editPointerY);
		float radius = pinchStartRadius * distance / Math.max(1.0f, pinchStartDistance);
		float shortest = Math.max(1.0f, Math.min(screenBounds.width(), screenBounds.height()));
		setControlRadius(editControl, radius / shortest);
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
		int alpha = controlAlpha();
		boolean selected = editControl == EditControl.DPAD;
		Set<VirtualDpadDirection> pressed = dpadToken == null
				? java.util.Collections.emptySet()
				: dpadController.state(dpadToken);

		int base = controlBaseColor();
		int accent = controlAccentColor();
		int outline = controlOutlineColor();
		int icon = controlIconColor();
		int selectedIcon = controlSelectedIconColor();
		int shell = darkenRgb(base, 0.30f);
		int face = blendRgb(base, outline, 0.14f);
		int edge = blendRgb(outline, icon, 0.10f);
		int active = blendRgb(accent, outline, 0.08f);
		int activeFace = blendRgb(face, accent, 0.24f);

		if (selected) {
			fillCircle(graphics, cx, cy, r * 1.03f,
					colorWithAlpha(accent, scaledAlpha(alpha, 0.08f)));
		}
		fillCircle(graphics, cx, cy, r * 0.94f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.12f)));
		drawCircle(graphics, cx, cy, r * 0.94f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.18f)));

		float halfArm = r * 0.32f;
		float centerGap = r * 0.055f;
		int round = Math.max(5, Math.round(r * 0.15f));

		paintDpadButton(graphics,
				cx - halfArm, cy - r, cx + halfArm, cy - centerGap,
				round, pressed.contains(VirtualDpadDirection.UP),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx - halfArm, cy + centerGap, cx + halfArm, cy + r,
				round, pressed.contains(VirtualDpadDirection.DOWN),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx - r, cy - halfArm, cx - centerGap, cy + halfArm,
				round, pressed.contains(VirtualDpadDirection.LEFT),
				face, activeFace, edge, active, alpha, r);
		paintDpadButton(graphics,
				cx + centerGap, cy - halfArm, cx + r, cy + halfArm,
				round, pressed.contains(VirtualDpadDirection.RIGHT),
				face, activeFace, edge, active, alpha, r);

		fillCircle(graphics, cx, cy, r * 0.245f,
				colorWithAlpha(darkenRgb(base, 0.50f), scaledAlpha(alpha, 0.96f)));
		fillCircle(graphics, cx, cy, r * 0.185f,
				colorWithAlpha(face, scaledAlpha(alpha, 0.94f)));
		drawCircle(graphics, cx, cy, r * 0.245f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.30f)));

		float textScale = clamp(r / 116.0f, 0.72f, 1.12f);
		graphics.setTextScale(textScale);
		paintDpadGlyph(graphics, "▲", cx, cy - r * 0.58f,
				pressed.contains(VirtualDpadDirection.UP), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "▼", cx, cy + r * 0.58f,
				pressed.contains(VirtualDpadDirection.DOWN), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "◀", cx - r * 0.58f, cy,
				pressed.contains(VirtualDpadDirection.LEFT), icon, selectedIcon, active, alpha);
		paintDpadGlyph(graphics, "▶", cx + r * 0.58f, cy,
				pressed.contains(VirtualDpadDirection.RIGHT), icon, selectedIcon, active, alpha);
		graphics.setTextScale(1.0f);
	}

	private void paintDpadButton(
			CanvasWrapper graphics,
			float left, float top, float right, float bottom,
			int round, boolean active,
			int face, int activeFace, int edge, int accent, int alpha, float radius) {
		float shadowOffset = Math.max(1.0f, radius * 0.030f);
		paintRect.set(left, top + shadowOffset, right, bottom + shadowOffset);
		graphics.setFillColor(colorWithAlpha(darkenRgb(face, 0.72f), scaledAlpha(alpha, 0.34f)));
		graphics.fillRoundRect(paintRect, round, round);

		if (active) {
			float glow = Math.max(1.0f, radius * 0.035f);
			paintRect.set(left - glow, top - glow, right + glow, bottom + glow);
			graphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.16f)));
			graphics.fillRoundRect(paintRect, round + Math.round(glow), round + Math.round(glow));
		}

		paintRect.set(left, top, right, bottom);
		graphics.setFillColor(colorWithAlpha(active ? activeFace : face,
				scaledAlpha(alpha, active ? 0.96f : 0.91f)));
		graphics.fillRoundRect(paintRect, round, round);
		graphics.setDrawColor(colorWithAlpha(active ? accent : edge,
				scaledAlpha(alpha, active ? 0.82f : 0.48f)));
		graphics.drawRoundRect(paintRect, round, round);

		float highlightHeight = Math.max(1.0f, radius * 0.012f);
		float inset = Math.max(2.0f, radius * 0.11f);
		paintRect.set(left + inset, top + inset * 0.50f, right - inset,
				Math.min(bottom, top + inset * 0.50f + highlightHeight));
		graphics.setFillColor(colorWithAlpha(lightenRgb(face, 0.42f),
				scaledAlpha(alpha, active ? 0.10f : 0.065f)));
		graphics.fillRoundRect(paintRect, Math.max(1, round / 3), Math.max(1, round / 3));
	}

	private void paintDpadGlyph(
			CanvasWrapper graphics,
			String glyph,
			float x, float y,
			boolean active,
			int icon, int selectedIcon, int accent, int alpha) {
		int activeIcon = blendRgb(selectedIcon, accent, 0.18f);
		graphics.setTextColor(colorWithAlpha(active ? activeIcon : icon,
				scaledAlpha(alpha, active ? 0.98f : 0.90f)));
		graphics.drawString(glyph, x, y);
	}

	private void paintAnalog(CanvasWrapper graphics) {
		boolean editingAnalog = getLayoutEditMode() != LAYOUT_EOF && editControl == EditControl.ANALOG;
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
		float thumbX = centerX + (rawThumbX - centerX) * 0.47f;
		float thumbY = centerY + (rawThumbY - centerY) * 0.47f;
		boolean selected = editControl == EditControl.ANALOG;
		int alpha = controlAlpha();

		int base = controlBaseColor();
		int accent = controlAccentColor();
		int outline = controlOutlineColor();
		int icon = controlIconColor();
		int shell = darkenRgb(base, 0.34f);
		int face = blendRgb(base, outline, 0.18f);
		int inner = darkenRgb(base, 0.48f);
		int edge = blendRgb(outline, icon, 0.12f);
		int accentSoft = blendRgb(accent, outline, 0.10f);

		if (selected) {
			fillCircle(graphics, centerX, centerY, radius * 1.02f,
					colorWithAlpha(accent, scaledAlpha(alpha, 0.08f)));
		}

		fillCircle(graphics, centerX, centerY, radius * 0.93f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.84f)));
		drawCircle(graphics, centerX, centerY, radius * 0.93f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.44f)));

		fillCircle(graphics, centerX, centerY, radius * 0.73f,
				colorWithAlpha(accentSoft, scaledAlpha(alpha, active ? 0.72f : 0.43f)));
		fillCircle(graphics, centerX, centerY, radius * 0.67f,
				colorWithAlpha(shell, scaledAlpha(alpha, 0.96f)));
		fillCircle(graphics, centerX, centerY, radius * 0.57f,
				colorWithAlpha(inner, scaledAlpha(alpha, 0.91f)));
		drawCircle(graphics, centerX, centerY, radius * 0.57f,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.20f)));

		paintAnalogTicks(graphics, centerX, centerY, radius, accent, alpha);

		float thumbRadius = radius * 0.395f;
		fillCircle(graphics, thumbX, thumbY + radius * 0.035f, thumbRadius * 1.02f,
				colorWithAlpha(darkenRgb(face, 0.78f), scaledAlpha(alpha, 0.38f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius * 1.075f,
				colorWithAlpha(accentSoft, scaledAlpha(alpha, active ? 0.23f : 0.09f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius,
				colorWithAlpha(face, scaledAlpha(alpha, 0.97f)));
		fillCircle(graphics, thumbX, thumbY, thumbRadius * 0.82f,
				colorWithAlpha(lightenRgb(face, 0.065f), scaledAlpha(alpha, 0.98f)));
		drawCircle(graphics, thumbX, thumbY, thumbRadius,
				colorWithAlpha(edge, scaledAlpha(alpha, 0.34f)));
		fillCircle(graphics,
				thumbX - thumbRadius * 0.16f,
				thumbY - thumbRadius * 0.18f,
				thumbRadius * 0.23f,
				colorWithAlpha(lightenRgb(face, 0.60f), scaledAlpha(alpha, 0.08f)));
	}

	private void paintAnalogTicks(
			CanvasWrapper graphics,
			float cx, float cy, float radius,
			int accent, int alpha) {
		float offset = radius * 0.83f;
		float length = Math.max(3.0f, radius * 0.082f);
		float thickness = Math.max(1.2f, radius * 0.014f);
		graphics.setFillColor(colorWithAlpha(accent, scaledAlpha(alpha, 0.68f)));

		paintRect.set(cx - thickness / 2f, cy - offset - length / 2f,
				cx + thickness / 2f, cy - offset + length / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx - thickness / 2f, cy + offset - length / 2f,
				cx + thickness / 2f, cy + offset + length / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx - offset - length / 2f, cy - thickness / 2f,
				cx - offset + length / 2f, cy + thickness / 2f);
		graphics.fillRect(paintRect);
		paintRect.set(cx + offset - length / 2f, cy - thickness / 2f,
				cx + offset + length / 2f, cy + thickness / 2f);
		graphics.fillRect(paintRect);
	}

	private void fillCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
		paintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
		graphics.setFillColor(color);
		graphics.fillArc(paintRect, 0, 360);
	}

	private void drawCircle(CanvasWrapper graphics, float cx, float cy, float radius, int color) {
		paintRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
		graphics.setDrawColor(color);
		graphics.drawArc(paintRect, 0, 360);
	}

	private int controlBaseColor() {
		return settings.vkBgColor & 0x00FFFFFF;
	}

	private int controlAccentColor() {
		return settings.vkBgColorSelected & 0x00FFFFFF;
	}

	private int controlOutlineColor() {
		return settings.vkOutlineColor & 0x00FFFFFF;
	}

	private int controlIconColor() {
		return settings.vkFgColor & 0x00FFFFFF;
	}

	private int controlSelectedIconColor() {
		return settings.vkFgColorSelected & 0x00FFFFFF;
	}

	private static int colorWithAlpha(int rgb, int alpha) {
		return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
	}

	private static int scaledAlpha(int alpha, float factor) {
		return Math.max(0, Math.min(0xFF, Math.round(alpha * factor)));
	}

	private static int blendRgb(int first, int second, float secondWeight) {
		float weight = clamp(secondWeight, 0.0f, 1.0f);
		float firstWeight = 1.0f - weight;
		int red = Math.round(((first >> 16) & 0xFF) * firstWeight + ((second >> 16) & 0xFF) * weight);
		int green = Math.round(((first >> 8) & 0xFF) * firstWeight + ((second >> 8) & 0xFF) * weight);
		int blue = Math.round((first & 0xFF) * firstWeight + (second & 0xFF) * weight);
		return (red << 16) | (green << 8) | blue;
	}

	private static int lightenRgb(int rgb, float amount) {
		float weight = clamp(amount, 0.0f, 1.0f);
		int red = Math.round(((rgb >> 16) & 0xFF) + (0xFF - ((rgb >> 16) & 0xFF)) * weight);
		int green = Math.round(((rgb >> 8) & 0xFF) + (0xFF - ((rgb >> 8) & 0xFF)) * weight);
		int blue = Math.round((rgb & 0xFF) + (0xFF - (rgb & 0xFF)) * weight);
		return (red << 16) | (green << 8) | blue;
	}

	private static int darkenRgb(int rgb, float amount) {
		float weight = 1.0f - clamp(amount, 0.0f, 1.0f);
		int red = Math.round(((rgb >> 16) & 0xFF) * weight);
		int green = Math.round(((rgb >> 8) & 0xFF) * weight);
		int blue = Math.round((rgb & 0xFF) * weight);
		return (red << 16) | (green << 8) | blue;
	}

	private VirtualDpadGeometry dpadGeometry() {
		float shortest = Math.max(1.0f, Math.min(screenBounds.width(), screenBounds.height()));
		return new VirtualDpadGeometry(
				screenBounds.left + settings.virtualDpadCenterX * screenBounds.width(),
				screenBounds.top + settings.virtualDpadCenterY * screenBounds.height(),
				settings.virtualDpadRadius * shortest);
	}

	private VirtualDpadGeometry analogGeometry() {
		float shortest = Math.max(1.0f, Math.min(screenBounds.width(), screenBounds.height()));
		return new VirtualDpadGeometry(
				screenBounds.left + settings.virtualAnalogCenterX * screenBounds.width(),
				screenBounds.top + settings.virtualAnalogCenterY * screenBounds.height(),
				settings.virtualAnalogRadius * shortest);
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

	private int controlAlpha() {
		if (getLayoutEditMode() != LAYOUT_EOF) return 0xFF;
		return Math.max(0, Math.min(0xFF, settings.vkAlpha));
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
