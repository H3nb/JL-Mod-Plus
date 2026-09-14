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

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.ContextHolder;

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

/**
 * Incremental virtual-controls extension over the established VirtualKeyboard.
 *
 * The keypad and virtual D-pad remain owned by the legacy overlay. This class adds only the
 * optional analog producer and deliberately routes its normalized vector through StickProcessor
 * before entering Canvas key ownership. It must not synthesize pointer events merely because the
 * source happens to be analog; pointer/touch-joystick behavior belongs to explicit pointer modes.
 */
public final class VirtualControlsKeyboard extends VirtualKeyboard {
	private static final float ANALOG_CENTER_X = 0.18f;
	private static final float ANALOG_CENTER_Y = 0.78f;
	private static final float ANALOG_RADIUS = 0.16f;
	private static final float ANALOG_HIT_SCALE = 1.20f;
	private static final int FEEDBACK_DURATION_MS = 50;

	private final ProfileModel settings;
	private final VirtualAnalogStick analogStick = new VirtualAnalogStick(
			new VirtualAnalogStickSettings(
					ANALOG_CENTER_X,
					ANALOG_CENTER_Y,
					ANALOG_RADIUS,
					VirtualAnalogStickMode.FIXED));
	private final VirtualAnalogDirectionAdapter directionAdapter =
			new VirtualAnalogDirectionAdapter();

	private Canvas target;
	private View overlayView;
	private RectF screenBounds;
	private GuestViewport viewport;
	private int analogPointer = -1;
	private PointerSourceToken analogToken;
	private String analogChannel;
	private long analogSequence;
	private boolean overlayVisible = true;

	public VirtualControlsKeyboard(ProfileModel settings) {
		super(settings);
		this.settings = settings;
	}

	@Override
	public void setView(View view) {
		super.setView(view);
		overlayView = view;
	}

	@Override
	public void setTarget(Canvas canvas) {
		if (target != canvas) {
			endAnalog();
		}
		super.setTarget(canvas);
		target = canvas;
	}

	@Override
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		endAnalog();
		super.resize(screen, left, top, right, bottom);
		screenBounds = new RectF(screen);
		viewport = new GuestViewport(
				Math.max(1, Math.round(screen.width())),
				Math.max(1, Math.round(screen.height())));
	}

	@Override
	public void paint(CanvasWrapper graphics) {
		super.paint(graphics);
		if (!settings.virtualAnalogEnabled || !overlayVisible || viewport == null || screenBounds == null
				|| settings.vkAlpha <= 0) {
			return;
		}
		VirtualAnalogVisualState visual = analogStick.visualState(viewport);
		float centerX = screenBounds.left + visual.getCenterX();
		float centerY = screenBounds.top + visual.getCenterY();
		float radius = visual.getRadius();
		int alpha = getLayoutEditMode() == LAYOUT_EOF ? settings.vkAlpha : 0xFF;
		alpha = Math.max(0, Math.min(0xFF, alpha));

		RectF ring = new RectF(
				centerX - radius,
				centerY - radius,
				centerX + radius,
				centerY + radius);
		graphics.setFillColor((alpha << 24) | (settings.vkBgColor & 0x00FFFFFF));
		graphics.fillArc(ring, 0, 360);
		graphics.setDrawColor((alpha << 24) | (settings.vkOutlineColor & 0x00FFFFFF));
		graphics.drawArc(ring, 0, 360);

		float thumbRadius = radius * 0.42f;
		float thumbX = screenBounds.left + visual.getThumbX();
		float thumbY = screenBounds.top + visual.getThumbY();
		RectF thumb = new RectF(
				thumbX - thumbRadius,
				thumbY - thumbRadius,
				thumbX + thumbRadius,
				thumbY + thumbRadius);
		int thumbColor = visual.getActive() ? settings.vkBgColorSelected : settings.vkBgColor;
		graphics.setFillColor((alpha << 24) | (thumbColor & 0x00FFFFFF));
		graphics.fillArc(thumb, 0, 360);
		graphics.setDrawColor((alpha << 24) | (settings.vkFgColor & 0x00FFFFFF));
		graphics.drawArc(thumb, 0, 360);
	}

	@Override
	public boolean pointerPressed(int pointer, float x, float y) {
		if (beginAnalog(pointer, x, y)) {
			return true;
		}
		return super.pointerPressed(pointer, x, y);
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		if (pointer == analogPointer && analogToken != null) {
			VirtualAnalogSample sample = analogStick.move(
					analogToken,
					x - screenBounds.left,
					y - screenBounds.top);
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
		if (pointer == analogPointer) {
			endAnalog();
			return true;
		}
		return super.pointerReleased(pointer, x, y);
	}

	@Override
	public void cancel() {
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

	private boolean beginAnalog(int pointer, float x, float y) {
		if (!settings.virtualAnalogEnabled || getLayoutEditMode() != LAYOUT_EOF || target == null
				|| viewport == null || screenBounds == null || analogPointer >= 0 || pointer < 0) {
			return false;
		}
		VirtualAnalogVisualState visual = analogStick.visualState(viewport);
		float centerX = screenBounds.left + visual.getCenterX();
		float centerY = screenBounds.top + visual.getCenterY();
		float dx = x - centerX;
		float dy = y - centerY;
		float hitRadius = visual.getRadius() * ANALOG_HIT_SCALE;
		if (dx * dx + dy * dy > hitRadius * hitRadius) {
			return false;
		}

		long generation = target.inputGeneration();
		PointerSourceToken token = new PointerSourceToken(
				PointerSourceKind.VIRTUAL,
				pointer,
				target,
				generation);
		if (analogStick.begin(token, viewport, null, null) == null) {
			return false;
		}
		analogPointer = pointer;
		analogToken = token;
		analogSequence = analogSequence == Long.MAX_VALUE ? 1L : analogSequence + 1L;
		analogChannel = "virtual-analog:" + analogSequence + ":" + pointer;
		if (settings.vkFeedback) {
			ContextHolder.vibrateKey(FEEDBACK_DURATION_MS);
		}

		VirtualAnalogSample sample = analogStick.move(
				token,
				x - screenBounds.left,
				y - screenBounds.top);
		if (sample != null) {
			updateAnalogOutput(sample);
		}
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
			analogStick.reset();
			return;
		}

		analogStick.end(token);
		directionAdapter.reset();
		if (canvas != null && channel != null) {
			canvas.inputReleased(
					"vk-analog@" + Integer.toHexString(System.identityHashCode(this)),
					token.getGeneration(),
					"virtual-analog",
					channel);
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
		if (canvas == null || token == null || channel == null) {
			return;
		}
		List<StickProcessor.DirectionKey> keys = directionAdapter.update(sample);
		int[] keyCodes = new int[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			keyCodes[i] = guestCode(keys.get(i));
		}
		canvas.inputUpdated(
				"vk-analog@" + Integer.toHexString(System.identityHashCode(this)),
				token.getGeneration(),
				"virtual-analog",
				channel,
				keyCodes);
	}

	private static int guestCode(StickProcessor.DirectionKey key) {
		return switch (key) {
			case UP -> Canvas.KEY_UP;
			case DOWN -> Canvas.KEY_DOWN;
			case LEFT -> Canvas.KEY_LEFT;
			case RIGHT -> Canvas.KEY_RIGHT;
			case NUM1 -> Canvas.KEY_NUM1;
			case NUM3 -> Canvas.KEY_NUM3;
			case NUM7 -> Canvas.KEY_NUM7;
			case NUM9 -> Canvas.KEY_NUM9;
		};
	}

	private void invalidateOverlay() {
		if (overlayView != null) {
			overlayView.postInvalidate();
		}
	}
}
