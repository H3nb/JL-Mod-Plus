/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2019-2025 Yury Kharchenko
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

package javax.microedition.lcdui;

import static android.opengl.GLES20.*;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.h3nb.jlmodplus.input.HostCommand;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.lcdui.commands.AbstractSoftKeysBar;
import javax.microedition.lcdui.event.CanvasEvent;
import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventFilter;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.event.PointerEvent;
import javax.microedition.lcdui.graphics.CanvasView;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.lcdui.graphics.GlesView;
import javax.microedition.lcdui.graphics.ShaderProgram;
import javax.microedition.lcdui.keyboard.KeyMapper;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.lcdui.overlay.FpsCounter;
import javax.microedition.lcdui.overlay.Layer;
import javax.microedition.lcdui.overlay.Overlay;
import javax.microedition.lcdui.overlay.OverlayView;
import javax.microedition.lcdui.skin.SkinLayer;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.AutoSpeedController;
import javax.microedition.shell.timing.FramePacer;
import javax.microedition.shell.timing.FrameMetrics;
import javax.microedition.shell.timing.PresentationMailbox;
import javax.microedition.util.ContextHolder;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.BackgroundMode;
import io.github.h3nb.jlmodplus.ui.LegacyThemeColors;
import io.github.h3nb.jlmodplus.ui.AppBackgroundColors;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.input.GuestKeyLedgerAdapter;
import io.github.h3nb.jlmodplus.input.ControllerPointerConsumer;
import javax.microedition.lcdui.graphics.AmbientCanvasRenderer;
import javax.microedition.lcdui.graphics.AmbientColorField;
import javax.microedition.lcdui.graphics.AmbientColorSampler;
import javax.microedition.lcdui.graphics.AmbientMesh;
import javax.microedition.lcdui.graphics.AmbientGlRenderer;

@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class Canvas extends Displayable {
	private static final String TAG = Canvas.class.getName();
	private static final int MAX_SYNCHRONOUS_DRAIN = 4;
	private static final long AMBIENT_HOST_INTERVAL_NS = 33_333_333L;

	public static final int KEY_POUND = 35;
	public static final int KEY_STAR = 42;
	public static final int KEY_NUM0 = 48;
	public static final int KEY_NUM1 = 49;
	public static final int KEY_NUM2 = 50;
	public static final int KEY_NUM3 = 51;
	public static final int KEY_NUM4 = 52;
	public static final int KEY_NUM5 = 53;
	public static final int KEY_NUM6 = 54;
	public static final int KEY_NUM7 = 55;
	public static final int KEY_NUM8 = 56;
	public static final int KEY_NUM9 = 57;

	public static final int KEY_UP = -1;
	public static final int KEY_DOWN = -2;
	public static final int KEY_LEFT = -3;
	public static final int KEY_RIGHT = -4;
	public static final int KEY_FIRE = -5;
	public static final int KEY_SOFT_LEFT = -6;
	public static final int KEY_SOFT_RIGHT = -7;
	public static final int KEY_CLEAR = -8;
	public static final int KEY_SEND = -10;
	public static final int KEY_END = -11;

	public static final int UP = 1;
	public static final int LEFT = 2;
	public static final int RIGHT = 5;
	public static final int DOWN = 6;
	public static final int FIRE = 8;
	public static final int GAME_A = 9;
	public static final int GAME_B = 10;
	public static final int GAME_C = 11;
	public static final int GAME_D = 12;

	private static ProfileModel settings;
	private static boolean parallelRedraw;
	private static int fpsLimit;
	private static boolean screenshotRawMode;
	private static boolean timingOverlayEnabled;

	private final Object bufferLock = new Object();
	private final Object surfaceLock = new Object();
	private final PaintEvent paintEvent = new PaintEvent();
	private final SoftBar softBar = new SoftBar();
	private final CanvasWrapper canvasWrapper = new CanvasWrapper(settings.screenFilter);
	private final RectF virtualScreen = new RectF();

	protected int width, height;
	protected int maxHeight;
	private LinearLayout layout;
	private SurfaceView innerView;
	private Surface surface;
	private GLRenderer renderer;
	private int displayWidth;
	private int displayHeight;
	private boolean fullscreen;
	private final Object visibilityLock = new Object();
	private boolean currentDisplayable;
	private boolean hostVisible;
	private boolean surfaceUsable;
	private volatile boolean visible;
	private boolean sizeChangedCalled;
	private static Image offscreen;
	private Image offscreenCopy;
	private volatile long publishedFrameSequence;
	private volatile FrameMetrics frameMetrics;
	private AutoSpeedController autoSpeedController;
	private final PresentationMailbox presentationMailbox = new PresentationMailbox();
	private int onX, onY, onWidth, onHeight;
	private final FramePacer framePacer = new FramePacer(GuestTimingBridge.activeSession());
	private final Object ambientLock = new Object();
	private final AmbientColorSampler ambientSampler = new AmbientColorSampler();
	private AmbientColorField ambientField;
	private AmbientColorField ambientGlField;
	private AmbientCanvasRenderer ambientCanvasRenderer;
	private final AmbientMesh ambientMesh = new AmbientMesh();
    private final float[] ambientGlX = new float[AmbientMesh.MAX_VERTEX_COUNT];
    private final float[] ambientGlY = new float[AmbientMesh.MAX_VERTEX_COUNT];
    private final int[] ambientTargetArgb = new int[AmbientColorField.GRID_COLOR_COUNT];
	private final RectF ambientSurface = new RectF();
	private final RectF ambientGameRect = new RectF();
	private volatile int themeBackgroundArgb;
	private volatile long lastAmbientSampleSequence = Long.MIN_VALUE;
	private volatile long nextAmbientSampleNs;
	private volatile long nextAmbientBitmapUpdateNs;
	private volatile boolean ambientGeometryDirty = true;
	private volatile boolean ambientLayoutValid;
	private volatile boolean ambientHostScheduled;
	private final Runnable ambientHostRunnable = this::runAmbientHostTick;
	private Handler uiHandler;
	private Overlay overlay;
	private OverlayView controllerOverlayView;
	private final ControllerJoystickOverlay controllerJoystickOverlay =
			new ControllerJoystickOverlay();
	/** Optional controller-owned virtual pointer arbiter; null preserves the legacy touch path. */
	private volatile ControllerPointerConsumer controllerPointerConsumer;
	/** Callback state survives surface reuse, so lifecycle boundaries must be able to cancel it. */
	private ViewCallbacks viewCallbacks;
	/** All host/keyboard/virtual-keypad key producers share this per-Canvas ledger. */
	private final GuestKeyLedgerAdapter guestKeyLedger = new GuestKeyLedgerAdapter(this);
	private FpsCounter fpsCounter;
	private boolean skipLeftSoft;
	private boolean skipRightSoft;

	protected Canvas() {
		this(settings.forceFullscreen);
	}

	protected Canvas(boolean fullscreen) {
		this.fullscreen = fullscreen;
		themeBackgroundArgb = AppBackgroundColors.argb(
				ProfileModel.isDarkTheme(ContextHolder.getAppContext()));
		super.softBar = softBar;
		if (settings.graphicsMode == 1) {
			renderer = new GLRenderer();
		}
		if (parallelRedraw) {
			uiHandler = new Handler(Looper.getMainLooper(), msg -> repaintScreen());
		}
		displayWidth = ContextHolder.getDisplayWidth();
		displayHeight = ContextHolder.getDisplayHeight();
		ensureAmbientState();
		updateSize();
	}

	public static void setLimitFps(int fpsLimit) {
		Canvas.fpsLimit = fpsLimit == -1 ? settings.fpsLimit : fpsLimit;
	}

	public static void setScreenshotRawMode(boolean enable) {
		screenshotRawMode = enable;
	}

	public static void setSettings(ProfileModel settings) {
		Canvas.settings = settings;
		fpsLimit = settings.fpsLimit;
		int mode = settings.graphicsMode;
		parallelRedraw = (mode == 0 || mode == 3) && settings.parallelRedrawScreen;
	}

	/** Receives a host-owned theme snapshot; render paths never read preferences or Compose state. */
	public void updateBackgroundTheme(int argb) {
		themeBackgroundArgb = argb | Color.BLACK;
		if (!isImmersiveMode()) return;
		long now = SystemClock.elapsedRealtimeNanos();
		synchronized (ambientLock) {
			ensureAmbientStateLocked();
			ambientField.setBaseColor(themeBackgroundArgb, now, false);
			ambientGlField.setBaseColor(themeBackgroundArgb, now, false);
		}
		invalidateAmbientHost();
	}

	private boolean isImmersiveMode() {
		return BackgroundMode.sanitize(settings.screenBackgroundMode) == BackgroundMode.IMMERSIVE;
	}

	private int effectiveBackgroundArgb() {
		return isImmersiveMode() || BackgroundMode.sanitize(settings.screenBackgroundMode)
				== BackgroundMode.THEME
				? themeBackgroundArgb
				: settings.screenBackgroundColor | Color.BLACK;
	}

	private void ensureAmbientState() {
		if (!isImmersiveMode()) return;
		synchronized (ambientLock) {
			ensureAmbientStateLocked();
		}
	}

	private void ensureAmbientStateLocked() {
		if (ambientField == null) {
			ambientField = new AmbientColorField();
			ambientGlField = new AmbientColorField();
			ambientField.resetToBase(themeBackgroundArgb);
			ambientGlField.resetToBase(themeBackgroundArgb);
			ambientCanvasRenderer = new AmbientCanvasRenderer(ambientField, themeBackgroundArgb);
		}
	}

	private void configureAmbientGeometry() {
		if (!isImmersiveMode()) {
			synchronized (ambientLock) {
				ambientLayoutValid = false;
				ambientGeometryDirty = true;
			}
			return;
		}
		int padding = SkinLayer.getInstance() != null && SkinLayer.getInstance().hasDisplayFrame()
				? 0 : Math.max(settings.screenPadding, 0);
		boolean layoutValid;
		synchronized (ambientLock) {
			ambientLayoutValid = false;
			ambientSurface.set(0.0f, 0.0f, displayWidth, displayHeight);
			ambientGameRect.set(virtualScreen);
			if (!ambientGameRect.intersect(padding, padding,
					displayWidth - padding, displayHeight - padding)) {
				ambientGameRect.setEmpty();
			}
			layoutValid = ambientSurface.width() > 0.0f && ambientSurface.height() > 0.0f
					&& ambientGameRect.width() > 0.0f && ambientGameRect.height() > 0.0f
					&& (ambientGameRect.left > ambientSurface.left
							|| ambientGameRect.top > ambientSurface.top
							|| ambientGameRect.right < ambientSurface.right
							|| ambientGameRect.bottom < ambientSurface.bottom);
			ambientGeometryDirty = true;
			if (layoutValid) {
				ensureAmbientStateLocked();
				ambientCanvasRenderer.configure(displayWidth, displayHeight, ambientGameRect);
				ambientMesh.build(displayWidth, displayHeight, ambientGameRect);
				for (int i = 0; i < ambientMesh.vertexCount(); i++) {
					ambientGlX[i] = ambientMesh.vertexX(i) / displayWidth;
					ambientGlY[i] = ambientMesh.vertexY(i) / displayHeight;
				}
				ambientGlField.configureNodes(ambientGlX, ambientGlY, ambientMesh.vertexCount(),
						ambientGameRect.left / displayWidth, ambientGameRect.top / displayHeight,
						ambientGameRect.right / displayWidth, ambientGameRect.bottom / displayHeight,
						displayWidth / (float) displayHeight);
			}
			ambientLayoutValid = layoutValid;
		}
		lastAmbientSampleSequence = Long.MIN_VALUE;
		nextAmbientSampleNs = 0L;
		nextAmbientBitmapUpdateNs = 0L;
		if (layoutValid) {
			invalidateAmbientHost();
		} else {
			cancelAmbientHostTick();
		}
	}

	private boolean sampleAmbientIfDue(long nowNs) {
		if (!isImmersiveMode() || !visible || !ambientLayoutValid || nowNs < nextAmbientSampleNs) {
			return false;
		}
		boolean sampled = false;
		long sampledSequence = Long.MIN_VALUE;
		synchronized (bufferLock) {
			if (offscreenCopy != null) {
				long sequence = publishedFrameSequence;
				if (sequence != 0L && (ambientGeometryDirty || sequence != lastAmbientSampleSequence)) {
					Rect bounds = offscreenCopy.getBounds();
					sampled = ambientSampler.captureGrid(offscreenCopy.getBitmap(), bounds.right,
							bounds.bottom);
					if (sampled) sampledSequence = sequence;
				}
			}
		}
		nextAmbientSampleNs = nowNs + AmbientColorField.SAMPLE_INTERVAL_NS;
		if (!sampled) return false;
		ambientSampler.toneCapturedGrid(themeBackgroundArgb, ambientTargetArgb);
		synchronized (ambientLock) {
			if (ambientField == null || !ambientLayoutValid || !isImmersiveMode()) return false;
			ambientField.setTargetGrid(ambientTargetArgb, themeBackgroundArgb, nowNs, false);
			ambientGlField.setTargetGrid(ambientTargetArgb, themeBackgroundArgb, nowNs, false);
		}
		lastAmbientSampleSequence = sampledSequence;
		ambientGeometryDirty = false;
		return true;
	}

	private boolean updateAmbientCanvas(long nowNs) {
		if (!isImmersiveMode() || !ambientLayoutValid) return false;
		sampleAmbientIfDue(nowNs);
		synchronized (ambientLock) {
			if (ambientCanvasRenderer == null) return false;
			if (nowNs >= nextAmbientBitmapUpdateNs) {
				boolean active = ambientCanvasRenderer.update(nowNs);
				nextAmbientBitmapUpdateNs = nowNs + 33_333_333L;
				return active;
			}
			return ambientField != null && ambientField.isTransitioning();
		}
	}

	private void drawAmbient(android.graphics.Canvas canvas, long nowNs) {
		if (!isImmersiveMode() || !ambientLayoutValid) return;
		updateAmbientCanvas(nowNs);
		synchronized (ambientLock) {
			if (ambientCanvasRenderer != null) {
				ambientCanvasRenderer.draw(canvas, ambientSurface, ambientGameRect);
			}
		}
	}

	private void invalidateAmbientHost() {
		if (!isImmersiveMode() || !visible || !ambientLayoutValid) return;
		long generation = presentationMailbox.generation();
		if (presentationMailbox.invalidateHost(generation) != 0L) {
			requestFlushToScreen();
		}
	}

	private void runAmbientHostTick() {
		ambientHostScheduled = false;
		if (!visible || !isImmersiveMode() || !ambientLayoutValid) return;
		long now = SystemClock.elapsedRealtimeNanos();
		boolean active;
		synchronized (ambientLock) {
			active = ambientField != null && ambientField.isTransitioning();
		}
		boolean samplePending = hasAmbientSamplePending();
		if ((samplePending && now >= nextAmbientSampleNs) || active) invalidateAmbientHost();
		if (active || (samplePending && now < nextAmbientSampleNs)) {
			scheduleAmbientHostTick(active ? AMBIENT_HOST_INTERVAL_NS : nextAmbientSampleNs - now);
		}
	}

	private void scheduleAmbientHostTick(long delayNs) {
		if (ambientHostScheduled || innerView == null || !isImmersiveMode() || !visible
				|| (!hasAmbientSamplePending() && !isAmbientTransitioning())) return;
		ambientHostScheduled = true;
		long delayMs = Math.max(1L, Math.min(1000L, (delayNs + 999_999L) / 1_000_000L));
		innerView.postDelayed(ambientHostRunnable, delayMs);
	}

	private boolean hasAmbientSamplePending() {
		long sequence;
		synchronized (bufferLock) {
			sequence = publishedFrameSequence;
		}
		return sequence != 0L && (ambientGeometryDirty || sequence != lastAmbientSampleSequence);
	}

	private boolean isAmbientTransitioning() {
		synchronized (ambientLock) {
			return ambientField != null && ambientField.isTransitioning();
		}
	}

	private void cancelAmbientHostTick() {
		if (innerView != null) innerView.removeCallbacks(ambientHostRunnable);
		ambientHostScheduled = false;
	}

	/** Enables the diagnostic only when the loaded artifact has the current universal transform. */
	public static void setTimingOverlayEnabled(boolean enabled) {
		timingOverlayEnabled = enabled;
	}

	public int getKeyCode(int gameAction) {
		int res = KeyMapper.getKeyCode(gameAction);
		if (res != Integer.MAX_VALUE) {
			return res;
		} else {
			throw new IllegalArgumentException("unknown game action " + gameAction);
		}
	}

	public int getGameAction(int keyCode) {
		return KeyMapper.getGameAction(keyCode);
	}

	public String getKeyName(int keyCode) {
		String res = KeyMapper.getKeyName(keyCode);
		if (res != null) {
			return res;
		} else {
			throw new IllegalArgumentException("unknown keycode " + keyCode);
		}
	}

	public void postKeyPressed(int keyCode) {
		if (keyCode == KEY_SOFT_LEFT && softBar.fireLeftSoft()) {
			skipLeftSoft = true;
			return;
		} else if (keyCode == KEY_SOFT_RIGHT && softBar.fireRightSoft()) {
			skipRightSoft = true;
			return;
		}
		Display.postEvent(CanvasEvent.getInstance(this,
				CanvasEvent.KEY_PRESSED,
				KeyMapper.convertKeyCode(keyCode)));
	}

	public void postKeyReleased(int keyCode) {
		if (keyCode == KEY_SOFT_LEFT && skipLeftSoft) {
			skipLeftSoft = false;
			return;
		} else if (keyCode == KEY_SOFT_RIGHT && skipRightSoft) {
			skipRightSoft = false;
			return;
		}
		Display.postEvent(CanvasEvent.getInstance(this,
				CanvasEvent.KEY_RELEASED,
				KeyMapper.convertKeyCode(keyCode)));
	}

	public void postKeyRepeated(int keyCode) {
		if (keyCode == KEY_SOFT_LEFT && skipLeftSoft) {
			return;
		} else if (keyCode == KEY_SOFT_RIGHT && skipRightSoft) {
			return;
		}
		Display.postEvent(CanvasEvent.getInstance(this,
				CanvasEvent.KEY_REPEATED,
				KeyMapper.convertKeyCode(keyCode)));
	}

	/** Enters the shared ownership path for a non-legacy input producer. */
	public void inputPressed(String deviceId, long sessionId, String kind, String channel,
			int... keyCodes) {
		guestKeyLedger.press(deviceId, sessionId, kind, channel, keyCodes, true);
	}

	/** Enters the ownership path with an explicit producer repeat policy. */
	public void inputPressedWithRepeat(String deviceId, long sessionId, String kind, String channel,
			boolean repeat, int... keyCodes) {
		guestKeyLedger.press(deviceId, sessionId, kind, channel, keyCodes, repeat);
	}

	/** Updates a producer's output set, used by diagonal/analog transitions. */
	public void inputUpdated(String deviceId, long sessionId, String kind, String channel,
			int... keyCodes) {
		guestKeyLedger.update(deviceId, sessionId, kind, channel, keyCodes, true);
	}

	/** Releases the exact source snapshot captured by its DOWN event. */
	public void inputReleased(String deviceId, long sessionId, String kind, String channel) {
		guestKeyLedger.release(deviceId, sessionId, kind, channel);
	}

	/** Releases physical-key ownership for one disconnected Android input device. */
	public void releaseInputDevice(int deviceId) {
		guestKeyLedger.releaseDevice(Integer.toString(deviceId));
	}

	/** Invalidates all producer state at a visibility/target boundary. */
	public void clearInputState() {
		guestKeyLedger.endVisibility();
	}

	/** Returns the source-session generation used to reject late releases from an old view. */
	public long inputGeneration() {
		return guestKeyLedger.currentGeneration();
	}

	/** Reveals an auto-hidden keypad only after a controller event was actually accepted. */
	public void controllerInputAccepted() {
		if (overlay != null) {
			overlay.show();
		}
	}

	/** Opens the controller-owned numeric keypad modal when the current overlay supports it. */
	public void openControllerKeypad() {
		if (overlay instanceof VirtualKeyboard keyboard) {
			keyboard.openControllerKeypad();
		}
	}

	public boolean isControllerKeypadVisible() {
		return overlay instanceof VirtualKeyboard keyboard && keyboard.isControllerKeypadVisible();
	}

	/** Gives the host keypad modal first refusal over normal guest key routing. */
	public boolean handleHostCommand(HostCommand command, boolean pressed) {
		return overlay instanceof VirtualKeyboard keyboard
				&& keyboard.handleHostCommand(command, pressed);
	}


	/** Installs the current controller pointer arbiter for this Canvas target. */
	public void setControllerPointerConsumer(ControllerPointerConsumer consumer) {
		controllerPointerConsumer = consumer;
	}

	/** Draws the explicitly configured touch-joystick affordance in guest-relative coordinates. */
	public void setControllerJoystickOverlay(boolean visible, float centerX, float centerY,
			float radius, float thumbX, float thumbY, boolean active) {
		controllerJoystickOverlay.set(visible, centerX, centerY, radius, thumbX, thumbY, active);
		OverlayView view = controllerOverlayView;
		if (view != null) view.postInvalidate();
	}

	public void doShowNotify() {
		showNotify();
		if (visible) {
			scheduleAmbientHostTick(AMBIENT_HOST_INTERVAL_NS);
		}
	}

	public void doHideNotify() {
		hideNotify();
	}

	static boolean isPresentationVisible(
			boolean currentDisplayable, boolean hostVisible, boolean surfaceUsable) {
		return currentDisplayable && hostVisible && surfaceUsable;
	}

	/**
	 * Reconciles guest visibility from Display ownership and Android host visibility. Surface
	 * usability is owned by the View callback below; only an effective edge emits a MIDP callback.
	 */
	void updatePresentationState(boolean currentDisplayable, boolean hostVisible) {
		boolean nextVisible;
		boolean changed;
		synchronized (visibilityLock) {
			this.currentDisplayable = currentDisplayable;
			this.hostVisible = hostVisible;
			nextVisible = isPresentationVisible(
					this.currentDisplayable, this.hostVisible, surfaceUsable);
			changed = visible != nextVisible;
			if (changed) {
				visible = nextVisible;
			}
		}
		if (changed) {
			onEffectiveVisibilityChanged(nextVisible);
		}
	}

	private void updateSurfaceUsable(boolean usable) {
		boolean nextVisible;
		boolean changed;
		synchronized (visibilityLock) {
			surfaceUsable = usable;
			nextVisible = isPresentationVisible(currentDisplayable, hostVisible, surfaceUsable);
			changed = visible != nextVisible;
			if (changed) {
				visible = nextVisible;
			}
		}
		if (changed) {
			onEffectiveVisibilityChanged(nextVisible);
		}
	}

	private void onEffectiveVisibilityChanged(boolean shown) {
		if (shown) {
			guestKeyLedger.resetForShow();
			AutoSpeedController controller = autoSpeedController;
			if (controller != null) {
				controller.setFrameSourceActive(true);
			}
			Display.postEvent(CanvasEvent.getInstance(this, CanvasEvent.SHOW_NOTIFY));
			repaintInternal();
			return;
		}
		PointerEvent.cancel(this);
		guestKeyLedger.endVisibility();
		resetControllerBoundaryState();
		AutoSpeedController controller = autoSpeedController;
		if (controller != null) {
			controller.setFrameSourceActive(false);
		}
		cancelAmbientHostTick();
		Display.postEvent(CanvasEvent.getInstance(this, CanvasEvent.HIDE_NOTIFY));
	}

	public void onDraw(android.graphics.Canvas canvas) {
		if (settings.graphicsMode != 2) return; // Fix for Android Pie
		if (!visible) {
			presentationMailbox.releaseAfterFailure(presentationMailbox.generation());
			return;
		}
		FrameMetrics metrics = frameMetrics;
		long frameSequence = 0L;
		long presentationGeneration = presentationMailbox.generation();
		long hostRevision = presentationMailbox.captureHostRevision(presentationGeneration);
		boolean presented = false;
		CanvasWrapper g = canvasWrapper;
		try {
			long nowNs = SystemClock.elapsedRealtimeNanos();
			g.bind(canvas);
			g.clear(effectiveBackgroundArgb());
			drawAmbient(canvas, nowNs);
			SkinLayer skinLayer = SkinLayer.getInstance();
			int p = skinLayer != null && skinLayer.hasDisplayFrame() ? 0 : settings.screenPadding;
			canvas.clipRect(p, p, displayWidth - p, displayHeight - p);
			synchronized (bufferLock) {
				offscreenCopy.getBitmap().prepareToDraw();
				g.drawImage(offscreenCopy, virtualScreen);
				frameSequence = publishedFrameSequence;
			}
			presented = true;
			if (metrics != null) {
				metrics.recordRender(frameSequence);
			}
		} finally {
			boolean needsAnother = presented
					? presentationMailbox.complete(presentationGeneration, frameSequence, hostRevision)
					: presentationMailbox.releaseAfterFailure(presentationGeneration);
			if (needsAnother) {
				requestAnotherPresentation(!presented);
			}
			if (presented && isImmersiveMode()) {
				scheduleAmbientHostTick(AMBIENT_HOST_INTERVAL_NS);
			}
		}
	}

	public Single<Bitmap> getScreenshot() {
		if (renderer != null && !screenshotRawMode) {
			return renderer.takeScreenShot();
		}
		return Single.create(emitter -> {
			Bitmap bitmap;
			if (screenshotRawMode) {
				synchronized (bufferLock) {
					bitmap = Bitmap.createBitmap(offscreenCopy.getBitmap(), 0, 0,
							offscreenCopy.getWidth(), offscreenCopy.getHeight());
				}
			} else {
				bitmap = Bitmap.createBitmap(onWidth, onHeight, Bitmap.Config.ARGB_8888);
				canvasWrapper.bind(new android.graphics.Canvas(bitmap));
				synchronized (bufferLock) {
					canvasWrapper.drawImage(offscreenCopy, new RectF(0, 0, onWidth, onHeight));
				}
			}
			emitter.onSuccess(bitmap);
		});
	}

	private boolean checkSizeChanged() {
		int tmpWidth = width;
		int tmpHeight = height;
		updateSize();
		return width != tmpWidth || height != tmpHeight;
	}

	/**
	 * Update the size and position of the virtual screen relative to the real one.
	 */
	public void updateSize() {
		// We turn the sizes of the virtual screen into the sizes of the visible canvas.
		// At the same time, we take into account that one or both virtual sizes can be less
		// than zero, which means auto-selection of this size so that the resulting canvas
		// has the same aspect ratio as the actual screen of the device.
		int scaledDisplayWidth;
		int scaledDisplayHeight;

		SkinLayer skinLayer = SkinLayer.getInstance();
		if (skinLayer != null && skinLayer.hasDisplayFrame()) {
			skinLayer.resize(virtualScreen, 0, 0, displayWidth, displayHeight);
			scaledDisplayWidth = (int) virtualScreen.width();
			scaledDisplayHeight = (int) virtualScreen.height();
		} else {
			scaledDisplayWidth = displayWidth - settings.screenPadding * 2;
			VirtualKeyboard vk = ContextHolder.getVk();
			boolean isPhoneSkin = vk != null && vk.isPhone();

			// if phone keyboard layout is active, then scale down the virtual screen
			if (isPhoneSkin) {
				float vkHeight = vk.getPhoneKeyboardHeight(displayWidth, displayHeight);
				scaledDisplayHeight = (int) (displayHeight - vkHeight - 1) - settings.screenPadding;
			} else {
				scaledDisplayHeight = displayHeight - settings.screenPadding * 2;
			}
		}

		if (settings.screenWidth > 0) {
			if (settings.screenHeight > 0) {
				// the width and height of the canvas are strictly set
				width = settings.screenWidth;
				height = settings.screenHeight;
			} else {
				// only the canvas width is set
				// height is selected by the ratio of the real screen
				width = settings.screenWidth;
				height = scaledDisplayHeight * settings.screenWidth / scaledDisplayWidth;
			}
		} else {
			if (settings.screenHeight > 0) {
				// only the canvas height is set
				// width is selected by the ratio of the real screen
				width = scaledDisplayWidth * settings.screenHeight / scaledDisplayHeight;
				height = settings.screenHeight;
			} else {
				// nothing is set - screen-sized canvas
				width = scaledDisplayWidth;
				height = scaledDisplayHeight;
			}
		}

		// We turn the size of the canvas into the size of the image
		// that will be displayed on the screen of the device.
		int scaleRatio = settings.screenScaleRatio;
		switch (settings.screenScaleType) {
			case 0 -> {
				// without scaling
				onWidth = width;
				onHeight = height;
			}
			case 1 -> {
				// try to fit in width
				onWidth = scaledDisplayWidth;
				onHeight = height * scaledDisplayWidth / width;
				if (onHeight > scaledDisplayHeight) {
					// if height is too big, then fit in height
					onHeight = scaledDisplayHeight;
					onWidth = width * scaledDisplayHeight / height;
				}
				if (scaleRatio > 100) {
					scaleRatio = 100;
				}
			}
			case 2 -> {
				// scaling without preserving the aspect ratio:
				// just stretch the picture to full screen
				onWidth = scaledDisplayWidth;
				onHeight = scaledDisplayHeight;
				if (scaleRatio > 100) {
					scaleRatio = 100;
				}
			}
		}

		onWidth = onWidth * scaleRatio / 100;
		onHeight = onHeight * scaleRatio / 100;

		switch (settings.screenGravity) {
			case 0 -> { // left
				onX = 0;
				onY = (scaledDisplayHeight - onHeight) / 2;
			}
			case 1 -> { // top
				onX = (scaledDisplayWidth - onWidth) / 2;
				onY = 0;
			}
			case 2 -> { // center
				onX = (scaledDisplayWidth - onWidth) / 2;
				onY = (scaledDisplayHeight - onHeight) / 2;
			}
			case 3 -> { // right
				onX = scaledDisplayWidth - onWidth;
				onY = (scaledDisplayHeight - onHeight) / 2;
			}
			case 4 -> { // bottom
				onX = (scaledDisplayWidth - onWidth) / 2;
				onY = scaledDisplayHeight - onHeight;
			}
		}

		if (skinLayer != null && skinLayer.hasDisplayFrame()) {
			onX += virtualScreen.left;
			onY += virtualScreen.top;
		} else {
			onX += settings.screenPadding;
			onY += settings.screenPadding;
		}

		// calculate the maximum height
		maxHeight = height;

		// calculate the current height
		softBar.resize();
		float softBarHeight = softBar.bounds.height();
		if (softBarHeight > 0) {
			float scaleY = (float) onHeight / height;
			height = (int) (height - softBarHeight / scaleY);
			onHeight -= softBarHeight;
		}

		RectF screen = new RectF(0, 0, displayWidth, displayHeight);
		virtualScreen.set(onX, onY, onX + onWidth, onY + onHeight);

		synchronized (bufferLock) {
			if (offscreenCopy == null) {
				offscreenCopy = Image.createImage(width, maxHeight);
			}
			if (offscreenCopy.getWidth() != width || offscreenCopy.getHeight() != height) {
				offscreenCopy.setSize(width, height);
			}
		}
		configureAmbientGeometry();
		if (overlay != null) {
			overlay.resize(screen, onX, onY, onX + onWidth, onY + onHeight + softBarHeight);
		}
		if (skinLayer != null && !skinLayer.hasDisplayFrame()) {
			skinLayer.resize(virtualScreen, 0, 0, displayWidth, displayHeight);
		}

		if (settings.graphicsMode == 1) {
			float gl = 2.0f * virtualScreen.left / displayWidth - 1.0f;
			float gt = 1.0f - 2.0f * virtualScreen.top / displayHeight;
			float gr = 2.0f * virtualScreen.right / displayWidth - 1.0f;
			float gb = 1.0f - 2.0f * virtualScreen.bottom / displayHeight;
			float th = (float) height / offscreenCopy.getBitmap().getHeight();
			float tw = (float) width / offscreenCopy.getBitmap().getWidth();
			renderer.updateSize(gl, gt, gr, gb, th, tw);
		}
		repaintInternal();
	}

	/**
	 * Convert the screen coordinates of the pointer into the virtual ones.
	 *
	 * @param x the pointer coordinate on the real screen
	 * @return the corresponding pointer coordinate on the virtual screen
	 */
	private int convertPointerX(float x) {
		return (int) ((x - onX) * width / onWidth);
	}

	/**
	 * Convert the screen coordinates of the pointer into the virtual ones.
	 *
	 * @param y the pointer coordinate on the real screen
	 * @return the corresponding pointer coordinate on the virtual screen
	 */
	private int convertPointerY(float y) {
		return (int) ((y - onY) * height / onHeight);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public View getDisplayableView() {
		if (layout == null) {
			layout = (LinearLayout) super.getDisplayableView();
			MicroActivity activity = ContextHolder.getActivity();
			if (settings.graphicsMode == 1) {
				GlesView glesView = new GlesView(activity);
				glesView.setRenderer(renderer);
				glesView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
				renderer.setView(glesView);
				innerView = glesView;
			} else {
				CanvasView canvasView = new CanvasView(this, activity);
				if (settings.graphicsMode == 2) {
					canvasView.setWillNotDraw(false);
				}
				canvasView.getHolder().setFormat(PixelFormat.RGBA_8888);
				innerView = canvasView;
			}
			viewCallbacks = new ViewCallbacks(innerView);
			innerView.getHolder().addCallback(viewCallbacks);
			innerView.setOnTouchListener(viewCallbacks);
			innerView.setOnKeyListener(viewCallbacks);
			innerView.setFocusableInTouchMode(true);
			layout.addView(innerView);
			innerView.requestFocus();
		}
		return layout;
	}

	@Override
	public void clearDisplayableView() {
		updateSurfaceUsable(false);
		super.clearDisplayableView();
		layout = null;
		innerView = null;
		viewCallbacks = null;
	}

	public void setFullScreenMode(boolean flag) {
		if (fullscreen == flag) {
			return;
		}
		fullscreen = flag;
		updateSize();
		if (!visible) {
			return;
		}
		Display.postEvent(CanvasEvent.getInstance(this, CanvasEvent.SIZE_CHANGED, width, height));
		repaintInternal();
	}

	public boolean hasPointerEvents() {
		return settings.touchInput;
	}

	public boolean hasPointerMotionEvents() {
		return settings.touchInput;
	}

	public boolean hasRepeatEvents() {
		return true;
	}

	public boolean isDoubleBuffered() {
		return true;
	}

	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public int getHeight() {
		return height;
	}

	protected abstract void paint(Graphics g);

	public final void repaint() {
		repaint(0, 0, width, height);
	}

	public final void repaint(int x, int y, int width, int height) {
		if (!visible || !hasVisibleRegion(x, y, width, height)) {
			return;
		}
		limitFps();
		boolean post;
		synchronized (paintEvent.clip) {
			post = paintEvent.invalidateClip(
					this, x, y, safeRegionEnd(x, width), safeRegionEnd(y, height))
					&& !paintEvent.isPending;
			if (post) {
				paintEvent.isPending = true;
			}
		}
		if (post) {
			Display.postEvent(paintEvent);
		}
	}

	private void repaintInternal() {
		synchronized (paintEvent.clip) {
			paintEvent.invalidateClip(this, 0, 0, width, height);
		}
		Display.postEvent(paintEvent);
	}

	// GameCanvas
	protected void flushBuffer(Image image, int x, int y, int width, int height) {
		if (!visible || !hasVisibleRegion(x, y, width, height)) {
			return;
		}
		limitFps();
		synchronized (bufferLock) {
			if (Thread.holdsLock(paintEvent)) {
				offscreen.getSingleGraphics().flush(image, x, y, width, height);
				return;
			}
			offscreenCopy.getSingleGraphics().flush(image, x, y, width, height);
			publishFrameLocked();
		}
		requestFlushToScreen();
	}

	private boolean hasVisibleRegion(int x, int y, int regionWidth, int regionHeight) {
		if (regionWidth <= 0 || regionHeight <= 0 || this.width <= 0 || this.height <= 0) {
			return false;
		}
		long right = (long) x + regionWidth;
		long bottom = (long) y + regionHeight;
		return right > 0L && bottom > 0L && x < this.width && y < this.height;
	}

	private static int safeRegionEnd(int start, int size) {
		long end = (long) start + size;
		return end >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) end;
	}

	// ExtendedImage
	public void flushBuffer(Image image, int x, int y) {
		limitFps();
		synchronized (bufferLock) {
			image.copyTo(offscreenCopy, x, y);
			publishFrameLocked();
		}
		requestFlushToScreen();
	}

	private void limitFps() {
		framePacer.pace(fpsLimit, !EventQueue.isInCallback());
	}

	private void publishFrameLocked() {
		long sequence = presentationMailbox.publish();
		if (sequence == 0L) {
			return;
		}
		publishedFrameSequence = sequence;
		FrameMetrics metrics = frameMetrics;
		if (metrics != null) {
			metrics.recordGameFrame();
		}
	}

	@SuppressLint("NewApi")
	private boolean repaintScreen() {
		long presentationGeneration = presentationMailbox.generation();
		PresentationResult result = presentToSurface();
		if (!result.surfaceAvailable && parallelRedraw) {
			presentationMailbox.close();
		}
		if (parallelRedraw && uiHandler != null) {
			boolean needsAnother = result.presented
					? presentationMailbox.complete(presentationGeneration, result.frameSequence,
							result.hostRevision)
					: presentationMailbox.releaseAfterFailure(presentationGeneration);
			if (needsAnother) {
				requestAnotherPresentation(!result.presented);
			}
		}
		return true;
	}

	/**
	 * Presents all frames that were published while one synchronous surface presentation was in
	 * flight. The loop is deliberately local and non-recursive: synchronous Surface rendering is
	 * still completed by the caller, while the mailbox closes the same lost-wakeup race as the
	 * asynchronous backends.
	 */
	private void requestSynchronousPresentation() {
		long presentationGeneration = presentationMailbox.generation();
		if (!presentationMailbox.trySchedule(presentationGeneration)) {
			return;
		}
		int drained = 0;
		while (true) {
			PresentationResult result = presentToSurface();
			if (!result.presented) {
				boolean retry = presentationMailbox.releaseAfterFailure(presentationGeneration);
				if (retry && result.surfaceAvailable) {
					postSynchronousRetry(presentationGeneration, 16L);
				}
				return;
			}
			drained++;
			if (drained >= MAX_SYNCHRONOUS_DRAIN) {
				if (presentationMailbox.completeAndRelease(
						presentationGeneration, result.frameSequence, result.hostRevision)) {
					postSynchronousRetry(presentationGeneration, 0L);
				}
				return;
			}
			if (!presentationMailbox.complete(presentationGeneration, result.frameSequence,
					result.hostRevision)) {
				return;
			}
		}
	}

	private void postSynchronousRetry(long presentationGeneration, long delayMillis) {
		if (innerView == null) {
			return;
		}
		Runnable retry = () -> {
			if (presentationMailbox.generation() == presentationGeneration) {
				requestSynchronousPresentation();
			}
		};
		if (delayMillis > 0L) {
			innerView.postDelayed(retry, delayMillis);
		} else {
			innerView.post(retry);
		}
	}

	@SuppressLint("NewApi")
	private PresentationResult presentToSurface() {
		FrameMetrics metrics = frameMetrics;
		long frameSequence = 0L;
		long presentationGeneration = presentationMailbox.generation();
		long hostRevision = presentationMailbox.captureHostRevision(presentationGeneration);
		Surface surface = this.surface;
		if (surface == null || !surface.isValid()) {
			return PresentationResult.surfaceUnavailable();
		}
		android.graphics.Canvas lockedCanvas = null;
		try {
			synchronized (surfaceLock) {
				lockedCanvas = settings.graphicsMode == 3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
						surface.lockHardwareCanvas() : surface.lockCanvas(null);
				if (lockedCanvas == null) {
					return PresentationResult.surfaceReady();
				}
				CanvasWrapper g = this.canvasWrapper;
				g.bind(lockedCanvas);
				g.clear(effectiveBackgroundArgb());
				drawAmbient(lockedCanvas, SystemClock.elapsedRealtimeNanos());
				SkinLayer skinLayer = SkinLayer.getInstance();
				int p = skinLayer != null && skinLayer.hasDisplayFrame() ? 0 : settings.screenPadding;
				lockedCanvas.clipRect(p, p, displayWidth - p, displayHeight - p);
				synchronized (bufferLock) {
					g.drawImage(offscreenCopy, virtualScreen);
					frameSequence = publishedFrameSequence;
				}
				try {
					surface.unlockCanvasAndPost(lockedCanvas);
				} finally {
					// An unlock attempt transfers ownership to Surface even when Android throws. Do
					// not submit the same canvas a second time from the outer cleanup path.
					lockedCanvas = null;
				}
			}
			if (metrics != null) {
				metrics.recordRender(frameSequence);
			}
			return PresentationResult.presented(frameSequence, hostRevision);
		} catch (Exception e) {
			Log.w(TAG, "repaintScreen: " + e);
			return PresentationResult.surfaceReady();
		} finally {
			if (lockedCanvas != null) {
				synchronized (surfaceLock) {
					try {
						surface.unlockCanvasAndPost(lockedCanvas);
					} catch (Exception e) {
						Log.w(TAG, "repaintScreen: failed to unlock surface canvas", e);
					}
				}
			}
		}
	}

	private static final class PresentationResult {
		final boolean surfaceAvailable;
		final boolean presented;
		final long frameSequence;
		final long hostRevision;

		private PresentationResult(boolean surfaceAvailable, boolean presented, long frameSequence,
				long hostRevision) {
			this.surfaceAvailable = surfaceAvailable;
			this.presented = presented;
			this.frameSequence = frameSequence;
			this.hostRevision = hostRevision;
		}

		static PresentationResult surfaceUnavailable() {
			return new PresentationResult(false, false, 0L, 0L);
		}

		static PresentationResult surfaceReady() {
			return new PresentationResult(true, false, 0L, 0L);
		}

		static PresentationResult presented(long frameSequence, long hostRevision) {
			return new PresentationResult(true, true, frameSequence, hostRevision);
		}
	}

	/**
	 * After calling this method, an immediate redraw is guaranteed to occur,
	 * and the calling thread is blocked until it is completed.
	 */
	public final void serviceRepaints() {
		if (visible) {
			Display.getEventQueue().serviceRepaints(paintEvent);
		}
	}

	@Override
	public boolean isShown() {
		return visible;
	}

	protected void showNotify() {
	}

	protected void hideNotify() {
	}

	protected void keyPressed(int keyCode) {
	}

	protected void keyRepeated(int keyCode) {
	}

	protected void keyReleased(int keyCode) {
	}

	public void pointerPressed(int pointer, int x, int y) {
		if (pointer == 0) {
			pointerPressed(x, y);
		}
	}

	public void pointerDragged(int pointer, int x, int y) {
		if (pointer == 0) {
			pointerDragged(x, y);
		}
	}

	public void pointerReleased(int pointer, int x, int y) {
		if (pointer == 0) {
			pointerReleased(x, y);
		}
	}

	protected void pointerPressed(int x, int y) {
	}

	protected void pointerDragged(int x, int y) {
	}

	protected void pointerReleased(int x, int y) {
	}

	void setInvisible() {
		boolean currentHostVisible;
		synchronized (visibilityLock) {
			currentHostVisible = hostVisible;
		}
		updatePresentationState(false, currentHostVisible);
	}

	/**
	 * Clears state owned by the current Canvas boundary after the shared ledger has emitted its
	 * final releases. This is also used by hide/target replacement paths that do not destroy the
	 * Android surface, so a VirtualKeyboard contact or soft-key suppression flag cannot leak into
	 * the next visible target.
	 */
	private void resetControllerBoundaryState() {
		skipLeftSoft = false;
		skipRightSoft = false;
		ViewCallbacks callbacks = viewCallbacks;
		if (callbacks != null) callbacks.cancelPointerOwnership();
		if (overlay != null) {
			overlay.cancel();
		}
		setControllerJoystickOverlay(false, 0f, 0f, 0f, 0f, 0f, false);
	}

	public void doKeyPressed(int keyCode) {
		keyPressed(keyCode);
	}

	public void doKeyRepeated(int keyCode) {
		keyRepeated(keyCode);
	}

	public void doKeyReleased(int keyCode) {
		keyReleased(keyCode);
	}

	private class GLRenderer implements GLSurfaceView.Renderer {
		private final FloatBuffer vbo = ByteBuffer.allocateDirect(8 * 2 * 4)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		private GLSurfaceView mView;
		private final int[] bgTextureId = new int[1];
		private ShaderProgram program;
		private AmbientGlRenderer ambientRenderer;
		private long lastUploadedSequence = Long.MIN_VALUE;
		private int lastUploadedWidth;
		private int lastUploadedHeight;
		private boolean textureValid;
		private boolean isStarted;

		@Override
		public void onSurfaceCreated(GL10 gl, EGLConfig config) {
			program = new ShaderProgram(settings.shader);
			program.use();
			int c = effectiveBackgroundArgb();
			glClearColor((c >> 16 & 0xff) / 255.0f, (c >> 8 & 0xff) / 255.0f,
					(c & 0xff) / 255.0f, 1.0f);
			glDisable(GL_BLEND);
			glDisable(GL_DEPTH_TEST);
			glDepthMask(false);
			initTex();
			Bitmap bitmap = offscreenCopy.getBitmap();
			textureValid = false;
			lastUploadedSequence = Long.MIN_VALUE;
			lastUploadedWidth = bitmap.getWidth();
			lastUploadedHeight = bitmap.getHeight();
			program.loadVbo(vbo, bitmap.getWidth(), bitmap.getHeight());
			if (settings.shader != null && settings.shader.values != null && program.uSetting >= 0) {
				glUniform4fv(program.uSetting, 1, settings.shader.values, 0);
			}
			if (isImmersiveMode()) {
				ambientRenderer = new AmbientGlRenderer();
				if (!ambientRenderer.initialize()) {
					Log.w(TAG, "Immersive background shader unavailable; using Theme for this GL session");
					ambientRenderer = null;
				}
			}
			isStarted = true;
		}

		@Override
		public void onSurfaceChanged(GL10 gl, int width, int height) {
			glViewport(0, 0, width, height);
			SkinLayer skinLayer = SkinLayer.getInstance();
			int p = skinLayer != null && skinLayer.hasDisplayFrame() ? 0 : settings.screenPadding;
			glScissor(p, p, Math.max(0, width - 2 * p), Math.max(0, height - 2 * p));
			program.use();
			if (program.uPixelDelta >= 0) {
				glUniform2f(program.uPixelDelta, 1.0f / width, 1.0f / height);
			}
		}

		@Override
		public void onDrawFrame(GL10 gl) {
			FrameMetrics metrics = frameMetrics;
			long frameSequence = 0L;
			long presentationGeneration = presentationMailbox.generation();
			long hostRevision = presentationMailbox.captureHostRevision(presentationGeneration);
			boolean presented = false;
			try {
				long nowNs = SystemClock.elapsedRealtimeNanos();
				glDisable(GL_SCISSOR_TEST);
				int c = effectiveBackgroundArgb();
				glClearColor((c >> 16 & 0xff) / 255.0f, (c >> 8 & 0xff) / 255.0f,
						(c & 0xff) / 255.0f, 1.0f);
				glClear(GL_COLOR_BUFFER_BIT);
				if (ambientRenderer != null && ambientLayoutValid) {
					sampleAmbientIfDue(nowNs);
					synchronized (ambientLock) {
						ambientRenderer.draw(ambientGlField, ambientMesh, nowNs,
								displayWidth, displayHeight);
					}
				}
				glEnable(GL_SCISSOR_TEST);
				program.use();
				glActiveTexture(GL_TEXTURE0);
				glBindTexture(GL_TEXTURE_2D, bgTextureId[0]);
				synchronized (bufferLock) {
					Bitmap bitmap = offscreenCopy.getBitmap();
					frameSequence = publishedFrameSequence;
					if (!textureValid || frameSequence != lastUploadedSequence
							|| bitmap.getWidth() != lastUploadedWidth
							|| bitmap.getHeight() != lastUploadedHeight) {
						GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
						textureValid = true;
						lastUploadedSequence = frameSequence;
						lastUploadedWidth = bitmap.getWidth();
						lastUploadedHeight = bitmap.getHeight();
					}
				}
				synchronized (vbo) {
					program.loadVbo(vbo, lastUploadedWidth, lastUploadedHeight);
				}
				glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
				presented = true;
				if (metrics != null) {
					metrics.recordRender(frameSequence);
				}
			} finally {
				boolean needsAnother = presented
						? presentationMailbox.complete(presentationGeneration, frameSequence, hostRevision)
						: presentationMailbox.releaseAfterFailure(presentationGeneration);
				if (needsAnother) {
					requestAnotherPresentation(!presented);
				}
				if (presented && isImmersiveMode()) {
					scheduleAmbientHostTick(AMBIENT_HOST_INTERVAL_NS);
				}
			}
		}

		private void initTex() {
			program.use();
			glGenTextures(1, bgTextureId, 0);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, bgTextureId[0]);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, settings.screenFilter ? GL_LINEAR : GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, settings.screenFilter ? GL_LINEAR : GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glUniform1i(program.uTextureUnit, 0);
		}

		public void updateSize(float gl, float gt, float gr, float gb, float th, float tw) {
			synchronized (vbo) {
				FloatBuffer vertex_bg = vbo;
				vertex_bg.rewind();
				vertex_bg.put(gl).put(gt).put(0.0f).put(0.0f);// lt
				vertex_bg.put(gl).put(gb).put(0.0f).put(th);// lb
				vertex_bg.put(gr).put(gt).put(tw).put(0.0f);// rt
				vertex_bg.put(gr).put(gb).put(tw).put(th);// rb
			}
			if (isStarted) {
				mView.queueEvent(() -> {
					if (!isStarted || program == null) return;
					program.use();
					Bitmap bitmap = offscreenCopy.getBitmap();
					synchronized (vbo) {
						program.loadVbo(vbo, bitmap.getWidth(), bitmap.getHeight());
					}
				});
			}
		}

		public void requestRender() {
			mView.requestRender();
		}

		public void setView(GLSurfaceView mView) {
			this.mView = mView;
		}

		public void stop() {
			isStarted = false;
			mView.onPause();
		}

		public void start() {
			mView.onResume();
		}

		private Single<Bitmap> takeScreenShot() {
			return Single.<int[]>create(emitter -> {
						IntBuffer buf = IntBuffer.allocate(onWidth * onHeight);
						mView.requestRender();
						mView.queueEvent(() -> {
							try {
								glReadPixels(displayWidth - onWidth - onX, displayHeight - onHeight - onY, onWidth, onHeight, GL_RGBA, GL_UNSIGNED_BYTE, buf);
								int error = glGetError();
								if (error != GL_NO_ERROR) {
									emitter.onError(new RuntimeException(GLU.gluErrorString(error)));
								} else {
									emitter.onSuccess(buf.array());
								}
							} catch (Throwable e) {
								emitter.onError(e);
							}
						});
					}).timeout(3, TimeUnit.SECONDS)
					.subscribeOn(Schedulers.computation())
					.observeOn(Schedulers.computation())
					.map(pixels -> {
						for (int i = 0, len = pixels.length; i < len; i++) {
							int p = pixels[i];
							pixels[i] = (p & 0xff00ff00) | ((p & 0xff0000) >> 16) | ((p & 0xff) << 16);
						}
						return Bitmap.createBitmap(pixels, onWidth * (onHeight - 1), -onWidth, onWidth, onHeight, Bitmap.Config.ARGB_8888);
					});
		}
	}

	private class PaintEvent extends Event implements EventFilter {
		final Rect clip = new Rect();

		boolean isPending;

		private int enqueued = 0;

		@Override
		public synchronized void process() {
			if (!visible) {
				synchronized (clip) {
					isPending = false;
					clip.setEmpty();
				}
				return;
			}
			int l, t, r, b;
			synchronized (clip) {
				isPending = false;
				l = clip.left;
				t = clip.top;
				r = clip.right;
				b = clip.bottom;
				clip.setEmpty();
			}
			if (l >= r || t >= b) {
				return;
			}
			if (offscreen == null) {
				offscreen = Image.createImage(width, maxHeight);
			}
			if (offscreen.getWidth() != width || offscreen.getHeight() != height) {
				offscreen.setSize(width, height);
			}
			Graphics g = offscreen.getSingleGraphics();
			g.reset(l, t, r, b);
			try {
				paint(g);
			} catch (Throwable e) {
				Log.e(TAG, "Error in paint()", e);
			}
			synchronized (bufferLock) {
				offscreen.copyTo(offscreenCopy);
				publishFrameLocked();
			}
			if (surface == null || !surface.isValid()) {
				return;
			}
			requestFlushToScreen();
		}

		@Override
		public void recycle() {
		}

		@Override
		public void enterQueue() {
			enqueued++;
		}

		@Override
		public void leaveQueue() {
			enqueued--;
		}

		/**
		 * The queue should contain no more than two repaint events
		 * <p>
		 * One won't be smooth enough, and if you add more than two,
		 * then how to determine exactly how many of them need to be added?
		 */
		@Override
		public boolean placeableAfter(Event event) {
			return event != this;
		}

		@Override
		public boolean accept(Event event) {
			return event == this;
		}

		private boolean invalidateClip(Canvas canvas, int l, int t, int r, int b) {
			boolean empty = clip.left >= clip.right || clip.top >= clip.bottom;
			if (empty) {
				clip.left = l;
				clip.top = t;
				clip.right = r;
				clip.bottom = b;
			} else {
				if (clip.left > l) clip.left = l;
				if (clip.top > t) clip.top = t;
				if (clip.right < r) clip.right = r;
				if (clip.bottom < b) clip.bottom = b;
			}
			if (clip.left < 0) clip.left = 0;
			if (clip.top < 0) clip.top = 0;
			if (clip.right > width) clip.right = width;
			if (clip.bottom > height) clip.bottom = height;
			return empty;
		}
	}

	private class ViewCallbacks implements View.OnTouchListener, SurfaceHolder.Callback, View.OnKeyListener {
		private final View mView;
		private final Set<Integer> overlayPointers = new HashSet<>();
		private final Set<Integer> controllerPointers = new HashSet<>();
		OverlayView overlayView;

		public ViewCallbacks(View view) {
			mView = view;
			overlayView = ContextHolder.getActivity().findViewById(R.id.overlay);
		}

		private void cancelPointerOwnership() {
			ControllerPointerConsumer pointerConsumer = controllerPointerConsumer;
			if (pointerConsumer != null) pointerConsumer.onPhysicalPointerCancelled();
			overlayPointers.clear();
			controllerPointers.clear();
		}

		@Override
		public boolean onKey(View v, int keyCode, KeyEvent event) {
			switch (event.getAction()) {
				case KeyEvent.ACTION_DOWN -> {
					return onKeyDown(keyCode, event);
				}
				case KeyEvent.ACTION_UP -> {
					return onKeyUp(keyCode, event);
				}
				case KeyEvent.ACTION_MULTIPLE -> {
					if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
						return onKeyDown(keyCode, event);
					}
					String characters = event.getCharacters();
					for (int i = 0, len = characters.length(); i < len; ) {
						int cp = characters.codePointAt(i);
						String channel = "ime:" + i + ":" + cp;
						inputPressed("ime:" + event.getDeviceId(), inputGeneration(),
								"ime", channel, cp);
						inputReleased("ime:" + event.getDeviceId(), inputGeneration(),
								"ime", channel);
						i += Character.charCount(cp);
					}
					return true;
				}
			}
			return false;
		}

		public boolean onKeyDown(int keyCode, KeyEvent event) {
			int androidKeyCode = keyCode;
			keyCode = KeyMapper.convertAndroidKeyCode(keyCode, event);
			if (keyCode == 0) {
				return false;
			}
			if (event.getRepeatCount() == 0) {
				if (overlay == null || !overlay.keyPressed(keyCode)) {
					inputPressedWithRepeat(Integer.toString(event.getDeviceId()), inputGeneration(),
							"keyboard", Integer.toString(androidKeyCode), false, keyCode);
				}
			} else {
				// Android owns physical-key timing, but a repeat is valid only while the same
				// source still owns a guest DOWN. Host/modal boundaries may have released it.
				boolean overlayConsumed = overlay != null && overlay.keyRepeated(keyCode);
				if (!overlayConsumed && guestKeyLedger.isActive(
						Integer.toString(event.getDeviceId()), inputGeneration(),
						"keyboard", Integer.toString(androidKeyCode))) {
					postKeyRepeated(keyCode);
				}
			}
			return true;
		}

		public boolean onKeyUp(int keyCode, KeyEvent event) {
			int androidKeyCode = keyCode;
			int midpKeyCode = KeyMapper.convertAndroidKeyCode(keyCode, event);
			if (midpKeyCode == 0) {
				return false;
			}
			if (overlay == null || !overlay.keyReleased(midpKeyCode)) {
				inputReleased(Integer.toString(event.getDeviceId()), inputGeneration(),
						"keyboard", Integer.toString(androidKeyCode));
			}
			return true;
		}

		@Override
		@SuppressLint("ClickableViewAccessibility")
		public boolean onTouch(View v, MotionEvent event) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					if (overlay != null) {
						overlay.show();
					}
				case MotionEvent.ACTION_POINTER_DOWN: {
					int index = event.getActionIndex();
					int id = event.getPointerId(index);
					float x = event.getX(index);
					float y = event.getY(index);
					boolean overlayConsumed = overlay != null && overlay.pointerPressed(id, x, y);
					if (overlayConsumed) overlayPointers.add(id);
					boolean controllerConsumed = false;
					ControllerPointerConsumer pointerConsumer = controllerPointerConsumer;
					if (!overlayConsumed && pointerConsumer != null) {
						controllerConsumed = pointerConsumer.onPhysicalPointerPressed(
								id, convertPointerX(x), convertPointerY(y));
						if (controllerConsumed) controllerPointers.add(id);
					}
					if (!overlayConsumed && !controllerConsumed && settings.touchInput && virtualScreen.contains(x, y)) {
						PointerEvent.sendPressed(Canvas.this,
								id,
								convertPointerX(x),
								convertPointerY(y));
					}
					break;
				}
				case MotionEvent.ACTION_MOVE: {
					int pointerCount = event.getPointerCount();
					int historySize = event.getHistorySize();
					for (int h = 0; h < historySize; h++) {
						for (int p = 0; p < pointerCount; p++) {
							int id = event.getPointerId(p);
							float x = event.getHistoricalX(p, h);
							float y = event.getHistoricalY(p, h);
							boolean overlayConsumed = overlayPointers.contains(id);
							if (overlayConsumed && overlay != null) overlay.pointerDragged(id, x, y);
							boolean wasController = controllerPointers.contains(id);
							boolean controllerConsumed = false;
							ControllerPointerConsumer pointerConsumer = controllerPointerConsumer;
							if (!overlayConsumed && pointerConsumer != null) {
								controllerConsumed = pointerConsumer.onPhysicalPointerDragged(
										id, convertPointerX(x), convertPointerY(y));
								if (wasController && !controllerConsumed) controllerPointers.remove(id);
							}
							if (!overlayConsumed && !controllerConsumed && !wasController && settings.touchInput) {
								PointerEvent.sendDragged(Canvas.this,
										id,
										convertPointerX(x),
										convertPointerY(y));
							}
						}
					}
					for (int p = 0; p < pointerCount; p++) {
						int id = event.getPointerId(p);
						float x = event.getX(p);
						float y = event.getY(p);
						boolean overlayConsumed = overlayPointers.contains(id);
						if (overlayConsumed && overlay != null) overlay.pointerDragged(id, x, y);
						boolean wasController = controllerPointers.contains(id);
						boolean controllerConsumed = false;
						ControllerPointerConsumer pointerConsumer = controllerPointerConsumer;
						if (!overlayConsumed && pointerConsumer != null) {
							controllerConsumed = pointerConsumer.onPhysicalPointerDragged(
									id, convertPointerX(x), convertPointerY(y));
							if (wasController && !controllerConsumed) controllerPointers.remove(id);
						}
						if (!overlayConsumed && !controllerConsumed && !wasController && settings.touchInput) {
							PointerEvent.sendDragged(Canvas.this,
									id,
									convertPointerX(x),
									convertPointerY(y));
						}
					}
					break;
				}
				case MotionEvent.ACTION_UP:
					if (overlay != null) {
						overlay.hide();
					}
				case MotionEvent.ACTION_POINTER_UP: {
					int index = event.getActionIndex();
					int id = event.getPointerId(index);
					float x = event.getX(index);
					float y = event.getY(index);
					boolean overlayConsumed = overlayPointers.remove(id);
					if (overlayConsumed && overlay != null) overlay.pointerReleased(id, x, y);
					boolean wasController = controllerPointers.remove(id);
					boolean controllerConsumed = false;
					ControllerPointerConsumer pointerConsumer = controllerPointerConsumer;
					if (!overlayConsumed && pointerConsumer != null) {
						controllerConsumed = pointerConsumer.onPhysicalPointerReleased(
								id, convertPointerX(x), convertPointerY(y));
					}
					if (!overlayConsumed && !controllerConsumed && !wasController && settings.touchInput) {
						PointerEvent.sendReleased(Canvas.this,
								id,
								convertPointerX(x),
								convertPointerY(y));
					}
					break;
				}
				case MotionEvent.ACTION_CANCEL:
					cancelPointerOwnership();
					PointerEvent.cancel(Canvas.this);
					if (overlay != null) {
						overlay.cancel();
					}
					break;
				default:
					return false;
			}
			return true;
		}

		@Override
		public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int newWidth, int newHeight) {
			if (displayWidth > displayHeight) {
				if (newWidth < newHeight) {
					softBar.closeMenu();
				}
			} else if (newWidth > newHeight) {
				softBar.closeMenu();
			}
			displayWidth = newWidth;
			displayHeight = newHeight;
			if (checkSizeChanged() || !sizeChangedCalled) {
				Display.postEvent(CanvasEvent.getInstance(Canvas.this,
						CanvasEvent.SIZE_CHANGED,
						width,
						height));
				repaintInternal();
				sizeChangedCalled = true;
			}
		}

		@Override
		public void surfaceCreated(@NonNull SurfaceHolder holder) {
			presentationMailbox.begin();
			if (renderer != null) {
				renderer.start();
			}
			surface = holder.getSurface();
			autoSpeedController = GuestTimingBridge.activeSpeedController();
			if (settings.showFps || autoSpeedController != null) {
				frameMetrics = autoSpeedController == null
						? new FrameMetrics() : autoSpeedController.frameMetrics();
			}
			if (settings.showFps) {
				fpsCounter = new FpsCounter(
						overlayView,
						frameMetrics,
						timingOverlayEnabled ? autoSpeedController : null);
				overlayView.addLayer(fpsCounter);
			}
			overlayView.addLayer(softBar, 0);
		controllerOverlayView = overlayView;
		overlayView.addLayer(controllerJoystickOverlay);
			overlayView.setVisibility(true);
			overlay = ContextHolder.getVk();
			if (overlay != null) {
				overlay.setTarget(Canvas.this);
			}
			updateSurfaceUsable(true);
		}

		@Override
		public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
			updateSurfaceUsable(false);
			if (autoSpeedController != null) {
				autoSpeedController.setFrameSourceActive(false);
				autoSpeedController = null;
			}
			presentationMailbox.close();
			if (renderer != null) {
				renderer.stop();
			}
			synchronized (surfaceLock) {
				surface = null;
			}
			if (fpsCounter != null) {
				fpsCounter.stop();
				overlayView.removeLayer(fpsCounter);
				fpsCounter = null;
			}
			frameMetrics = null;
			publishedFrameSequence = 0L;
			overlayView.removeLayer(softBar);
			overlayView.removeLayer(controllerJoystickOverlay);
		controllerOverlayView = null;
			softBar.closeMenu();
			overlayView.setVisibility(false);
			if (overlay != null) {
				overlay.setTarget(null);
				overlay = null;
			}
		}

	}

	private void requestFlushToScreen() {
		if (settings.graphicsMode == 1) {
			long generation = presentationMailbox.generation();
			if (innerView != null && presentationMailbox.trySchedule(generation)) {
				renderer.requestRender();
			}
		} else if (settings.graphicsMode == 2) {
			long generation = presentationMailbox.generation();
			if (innerView != null && presentationMailbox.trySchedule(generation)) {
				innerView.postInvalidate();
			}
		} else if (!parallelRedraw) {
			requestSynchronousPresentation();
		} else if (uiHandler != null) {
			long generation = presentationMailbox.generation();
			if (presentationMailbox.trySchedule(generation)) {
				uiHandler.sendEmptyMessage(0);
			}
		}
	}

	private void requestAnotherPresentation(boolean delayed) {
		long retryDelayMillis = delayed ? 16L : 0L;
		if (delayed) {
			long generation = presentationMailbox.generation();
			if (!presentationMailbox.trySchedule(generation)) {
				return;
			}
		}
		if (settings.graphicsMode == 1) {
			if (innerView != null && renderer != null) {
				if (delayed) {
					innerView.postDelayed(renderer::requestRender, retryDelayMillis);
				} else {
					renderer.requestRender();
				}
			}
		} else if (settings.graphicsMode == 2) {
			if (innerView != null) {
				if (delayed) {
					innerView.postInvalidateDelayed(retryDelayMillis);
				} else {
					innerView.postInvalidate();
				}
			}
		} else if (parallelRedraw && uiHandler != null) {
			if (delayed) {
				uiHandler.sendEmptyMessageDelayed(0, retryDelayMillis);
			} else {
				uiHandler.sendEmptyMessage(0);
			}
		}
	}

	private final class ControllerJoystickOverlay implements Layer {
		private volatile boolean visible;
		private volatile float centerX;
		private volatile float centerY;
		private volatile float radius;
		private volatile float thumbX;
		private volatile float thumbY;
		private volatile boolean active;

		private void set(boolean visible, float centerX, float centerY, float radius,
				float thumbX, float thumbY, boolean active) {
			this.visible = visible;
			this.centerX = centerX;
			this.centerY = centerY;
			this.radius = radius;
			this.thumbX = thumbX;
			this.thumbY = thumbY;
			this.active = active;
		}

		@Override
		public void paint(CanvasWrapper g) {
			if (!visible || radius <= 0f || onWidth <= 0 || onHeight <= 0) return;
			float scaleX = onWidth / (float) Math.max(1, width);
			float scaleY = onHeight / (float) Math.max(1, height);
			float scale = Math.min(scaleX, scaleY);
			float cx = onX + centerX * scaleX;
			float cy = onY + centerY * scaleY;
			float rr = radius * scale;
			RectF ring = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
			g.setDrawColor(Color.argb(190, 255, 255, 255));
			g.drawArc(ring, 0, 360);
			float tx = onX + thumbX * scaleX;
			float ty = onY + thumbY * scaleY;
			float tr = Math.max(6f, rr * 0.28f);
			g.setFillColor(active ? Color.argb(150, 80, 160, 255)
					: Color.argb(90, 255, 255, 255));
			g.fillArc(new RectF(tx - tr, ty - tr, tx + tr, ty + tr), 0, 360);
		}
	}

	private class SoftBar extends AbstractSoftKeysBar implements Layer {
		private final OverlayView overlayView;
		private final float padding;
		private final int textColor;
		private final int bgColor;
		private final RectF bounds = new RectF();

		private String leftLabel;
		private String rightLabel;
		private float textScale = 1.0f;

		private SoftBar() {
			super(Canvas.this);
			MicroActivity activity = ContextHolder.getActivity();
			this.overlayView = activity.findViewById(R.id.overlay);
			DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
			padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, metrics);
			textColor = LegacyThemeColors.accent(activity);
			bgColor = ContextCompat.getColor(activity, R.color.background);
		}

		private void showPopup() {
			PopupWindow popup = prepareMenu(fullscreen ? 0 : 1);
			popup.setWidth(Math.min(displayWidth, displayHeight) / 2);
			popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
			int x = (int) (displayWidth - bounds.right);
			int y = (int) (displayHeight - bounds.top);
			popup.showAtLocation(overlayView, Gravity.END | Gravity.BOTTOM, x, y);
		}

		@Override
		protected void onCommandsChanged(List<Command> list) {
			closeMenu();
			List<Command> commands = this.commands;
			commands.clear();
			commands.addAll(list);
			if (!fullscreen) {
				int size = commands.size();
				switch (size) {
					case 0:
						break;
					case 1:
						leftLabel = commands.get(0).getAndroidLabel();
						rightLabel = null;
						break;
					case 2:
						leftLabel = commands.get(0).getAndroidLabel();
						rightLabel = commands.get(1).getAndroidLabel();
						break;
					default:
						leftLabel = commands.get(0).getAndroidLabel();
						rightLabel = overlayView.getResources().getString(R.string.cmd_menu);
				}
			}
			overlayView.postInvalidate();
		}

		private boolean fireLeftSoft() {
			int size = commands.size();
			if (size == 0) {
				return false;
			}
			if (fullscreen) {
				if (size == 1) {
					return false;
				}
				if (listener != null) {
					showPopup();
				}
				return true;
			}
			fireCommandAction(commands.get(0));
			return true;
		}

		private boolean fireRightSoft() {
			int size = commands.size();
			if (size == 0) {
				return false;
			}
			if (fullscreen && listener == null) {
				return false;
			}
			if (fullscreen || size > 2) {
				showPopup();
				return true;
			}
			if (size == 1) {
				return false;
			}
			if (listener != null) {
				fireCommandAction(commands.get(1));
			}
			return true;
		}

		@Override
		public void paint(CanvasWrapper g) {
			if (bounds.isEmpty() || commands.isEmpty()) {
				return;
			}
			g.setFillColor(bgColor);
			g.fillRect(bounds);

			if (leftLabel == null) {
				return;
			}
			g.setTextAlign(Paint.Align.LEFT);
			g.setTextScale(textScale);
			g.setTextColor(textColor);
			float y = bounds.centerY();
			g.drawString(leftLabel, bounds.left + padding * textScale, y);
			if (rightLabel != null) {
				g.setTextAlign(Paint.Align.RIGHT);
				g.drawString(rightLabel, bounds.right - padding * textScale, y);
			}

			g.setTextAlign(Paint.Align.CENTER);
			g.setTextScale(1.0f);
		}

		public void resize() {
			float left;
			float right;
			float bottom;
			VirtualKeyboard vk = ContextHolder.getVk();
			if (vk != null && vk.isPhone()) {
				float vkTop = displayHeight - vk.getPhoneKeyboardHeight(displayWidth, displayHeight) - 1;
				if (onWidth < displayWidth / 2.0f || onWidth > displayWidth) {
					textScale = 1.0f;
					left = 0;
					right = displayWidth;
					bottom = vkTop;
				} else {
					textScale = (float) onWidth / displayWidth;
					canvasWrapper.setTextScale(textScale);
					left = onX;
					right = onX + onWidth;
					bottom = onY + onHeight;
					if (bottom > vkTop) {
						bottom = vkTop;
					}
				}
			} else {
				float width = onWidth;
				float minSide = Math.min(displayWidth, displayHeight);
				if (width <= minSide) {
					textScale = width / minSide;
					canvasWrapper.setTextScale(textScale);
					left = onX;
				} else {
					left = (onX + onWidth) / 2.0f - minSide / 2.0f;
					if (left + minSide > displayWidth) {
						left = displayWidth / 2.0f - minSide / 2.0f;
					}
					width = minSide;
				}
				bottom = onY + onHeight;
				right = left + width;
				if (bottom > displayHeight) {
					bottom = displayHeight;
				}
			}
			float top = fullscreen ? bottom : bottom - canvasWrapper.getTextHeight();
			bounds.set(left, top, right, bottom);
			canvasWrapper.setTextScale(1.0f);
			overlayView.postInvalidate();
		}
	}
}
