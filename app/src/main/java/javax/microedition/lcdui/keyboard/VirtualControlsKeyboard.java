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

import java.util.List;
import java.util.Set;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.ContextHolder;

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
 * as two cohesive widgets: one D-pad and one analog stick. Each widget has one center and one
 * radius, is moved/resized as a whole, and persists normalized geometry in the MIDlet profile.
 */
public final class VirtualControlsKeyboard extends VirtualKeyboard {
	private static final float DEFAULT_DPAD_CENTER_X = 0.82f;
	private static final float DEFAULT_DPAD_CENTER_Y = 0.78f;
	private static final float DEFAULT_DPAD_RADIUS = 0.16f;
	private static final float DEFAULT_ANALOG_CENTER_X = 0.18f;
	private static final float DEFAULT_ANALOG_CENTER_Y = 0.78f;
	private static final float DEFAULT_ANALOG_RADIUS = 0.16f;
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

	private VirtualAnalogStick analogStick;
	private Canvas target;
	private View overlayView;
	private RectF screenBounds;
	private GuestViewport viewport;
	private boolean overlayVisible = true;

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

	public VirtualControlsKeyboard(ProfileModel settings) {
		super(settings);
		this.settings = settings;
		sanitizeStoredGeometry();
		rebuildAnalogStick();
	}

	public boolean isVirtualDpadEnabled() {
		return settings.virtualDpadEnabled;
	}

	public boolean isVirtualAnalogEnabled() {
		return settings.virtualAnalogEnabled;
	}

	public void setVirtualDpadEnabled(boolean enabled) {
		if (settings.virtualDpadEnabled == enabled) return;
		if (!enabled) endDpad();
		settings.virtualDpadEnabled = enabled;
		ProfilesManager.saveConfig(settings);
		invalidateOverlay();
	}

	public void setVirtualAnalogEnabled(boolean enabled) {
		if (settings.virtualAnalogEnabled == enabled) return;
		if (!enabled) endAnalog();
		settings.virtualAnalogEnabled = enabled;
		ProfilesManager.saveConfig(settings);
		invalidateOverlay();
	}

	@Override
	public void setView(View view) {
		super.setView(view);
		overlayView = view;
		hideLegacyDirectionButtons();
	}

	/**
	 * The old eight independent arrow buttons are implementation details of VirtualKeyboard.
	 * VirtualControlsKeyboard replaces them with one grouped D-pad, so they stay hidden even when
	 * the generic hide/show dialog modifies other virtual-key visibility.
	 */
	@Override
	public void setKeysVisibility(boolean[] states) {
		boolean[] next = states == null ? null : states.clone();
		if (next != null) forceLegacyDirectionsHidden(next);
		super.setKeysVisibility(next == null ? states : next);
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
		super.resize(screen, left, top, right, bottom);
		screenBounds = new RectF(screen);
		viewport = new GuestViewport(
				Math.max(1, Math.round(screen.width())),
				Math.max(1, Math.round(screen.height())));
		rebuildAnalogStick();
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
		if (getLayoutEditMode() != LAYOUT_EOF) {
			if (beginGroupedPinch(pointer, x, y)) return true;
			if (beginGroupedEdit(pointer, x, y)) return true;
		}
		if (getLayoutEditMode() == LAYOUT_EOF) {
			if (beginDpad(pointer, x, y)) return true;
			if (beginAnalog(pointer, x, y)) return true;
		}
		return super.pointerPressed(pointer, x, y);
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		if (editControl != EditControl.NONE) {
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
		if (screenBounds == null || pointer < 0 || editPointer >= 0) return false;
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
		if (editControl == EditControl.ANALOG) rebuildAnalogStick();
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
		ProfilesManager.saveConfig(settings);
		invalidateOverlay();
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
		int fill = selected ? settings.vkBgColorSelected : settings.vkBgColor;
		int text = selected ? settings.vkFgColorSelected : settings.vkFgColor;
		graphics.setFillColor((alpha << 24) | (fill & 0x00FFFFFF));
		graphics.setDrawColor((alpha << 24) | (settings.vkOutlineColor & 0x00FFFFFF));
		graphics.setTextColor((alpha << 24) | (text & 0x00FFFFFF));
		float arm = r * 0.38f;
		int round = Math.max(4, (int) (r * 0.16f));
		RectF horizontal = new RectF(cx - r, cy - arm, cx + r, cy + arm);
		RectF vertical = new RectF(cx - arm, cy - r, cx + arm, cy + r);
		graphics.fillRoundRect(horizontal, round, round);
		graphics.fillRoundRect(vertical, round, round);
		graphics.drawRoundRect(horizontal, round, round);
		graphics.drawRoundRect(vertical, round, round);
		graphics.drawString("↑", cx, cy - r * 0.62f);
		graphics.drawString("↓", cx, cy + r * 0.62f);
		graphics.drawString("←", cx - r * 0.62f, cy);
		graphics.drawString("→", cx + r * 0.62f, cy);
	}

	private void paintAnalog(CanvasWrapper graphics) {
		VirtualAnalogVisualState visual = analogStick.visualState(viewport);
		float centerX = screenBounds.left + visual.getCenterX();
		float centerY = screenBounds.top + visual.getCenterY();
		float radius = visual.getRadius();
		int alpha = controlAlpha();
		boolean selected = editControl == EditControl.ANALOG;
		int base = selected ? settings.vkBgColorSelected : settings.vkBgColor;
		graphics.setFillColor((alpha << 24) | (base & 0x00FFFFFF));
		graphics.setDrawColor((alpha << 24) | (settings.vkOutlineColor & 0x00FFFFFF));
		RectF ring = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
		graphics.fillArc(ring, 0, 360);
		graphics.drawArc(ring, 0, 360);

		float thumbRadius = radius * 0.42f;
		float thumbX = screenBounds.left + visual.getThumbX();
		float thumbY = screenBounds.top + visual.getThumbY();
		RectF thumb = new RectF(
				thumbX - thumbRadius, thumbY - thumbRadius,
				thumbX + thumbRadius, thumbY + thumbRadius);
		int thumbColor = visual.getActive() ? settings.vkBgColorSelected : settings.vkBgColor;
		graphics.setFillColor((alpha << 24) | (thumbColor & 0x00FFFFFF));
		graphics.fillArc(thumb, 0, 360);
		graphics.setDrawColor((alpha << 24) | (settings.vkFgColor & 0x00FFFFFF));
		graphics.drawArc(thumb, 0, 360);
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

	private void hideLegacyDirectionButtons() {
		boolean[] hidden = getKeysVisibility();
		forceLegacyDirectionsHidden(hidden);
		super.setKeysVisibility(hidden);
	}

	private void forceLegacyDirectionsHidden(boolean[] hidden) {
		if (hidden == null) return;
		String[] names = getKeyNames();
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
