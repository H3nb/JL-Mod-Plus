/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2020-2026 Yury Kharchenko
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

import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.MemoryDiscoveryBridge;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;
import javax.microedition.util.ContextHolder;
import io.github.h3nb.jlmodplus.ui.LegacyThemeColors;

@SuppressWarnings("unused")
public class Display {
	public static final int LIST_ELEMENT = 1;
	public static final int CHOICE_GROUP_ELEMENT = 2;
	public static final int ALERT = 3;

	public static final int COLOR_BACKGROUND = 0;
	public static final int COLOR_FOREGROUND = 1;
	public static final int COLOR_HIGHLIGHTED_BACKGROUND = 2;
	public static final int COLOR_HIGHLIGHTED_FOREGROUND = 3;
	public static final int COLOR_BORDER = 4;
	public static final int COLOR_HIGHLIGHTED_BORDER = 5;

	private static final int[] COLORS =
			{
					0xFFD0D0D0,
					0xFF000080,
					0xFF000080,
					0xFFFFFFFF,
					0xFFFFFFFF,
					0xFF000080
			};

	private static Display instance;
	/** MIDP foreground-display request/grant for the one runtime in this isolated process. */
	private static long runtimeForegroundGeneration;
	private static boolean runtimeForegroundRequested;
	private static boolean runtimeForegroundGranted;
	static EventQueue queue = new EventQueue();

	static {
		queue.startProcessing();
	}

	private volatile Displayable current;
	/** UI-confirmed presentation for non-Canvas Displayables. */
	private Displayable presented;
	/** Invalidates queued show requests when the displayable changes, including reusing an Alert. */
	private final AtomicLong currentRequestGeneration = new AtomicLong();
	/** Serializes current-screen and host-presentation facts. */
	private final Object stateLock = new Object();
	private boolean hostVisible;
	private boolean displayForeground;
	/** Rejects presentation updates from superseded Android foreground edges. */
	private long appliedForegroundGeneration;
	private ScreenChange screenChange;
	/**
	 * A missing Android surface must not indefinitely block all subsequent LCDUI callbacks.
	 * Normal screen changes complete on the host's readiness signal, not on this deadline.
	 */
	private static final long SCREEN_CHANGE_MAX_WAIT_MS = 3000L;

	/** A queue position that becomes runnable only after this Canvas is mount/surface-ready. */
	final class ScreenChange extends SimpleEvent {
		private final Canvas canvas;
		private final long requestGeneration;
		private final MicroActivity host;
		private volatile boolean ready;
		private boolean cancelled;
		private boolean mounted;
		private long showGeneration;
		private final Runnable readinessDeadline = this::onReadinessDeadline;

		private void onReadinessDeadline() {
			synchronized (stateLock) {
				if (screenChange != this) return;
				if (isReady()) {
					// An earlier guest callback can still occupy the event queue. Keep watching
					// until this ready transaction is consumed or its surface becomes unavailable.
					ViewHandler.postDelayed(readinessDeadline, SCREEN_CHANGE_MAX_WAIT_MS);
					return;
				}
				Log.w("Display", "Canvas presentation did not become ready; releasing LCDUI queue");
				cancelScreenChangeLocked();
			}
		}

		ScreenChange(Canvas canvas, long requestGeneration, MicroActivity host) {
			this.canvas = canvas;
			this.requestGeneration = requestGeneration;
			this.host = host;
			asGuestCallback();
		}

		@Override
		public boolean isReady() {
			return ready;
		}

		private void updateReady() {
			ready = cancelled || (mounted && showGeneration != 0L);
			if (ready) queue.signalEventReady();
		}

		synchronized void mounted() {
			if (cancelled) return;
			mounted = true;
			updateReady();
		}

		synchronized void surfaceReady(long generation) {
			if (cancelled) return;
			showGeneration = generation;
			updateReady();
		}

		synchronized void surfaceUnavailable() {
			if (cancelled) return;
			showGeneration = 0L;
			updateReady();
		}

		synchronized void cancel() {
			cancelled = true;
			updateReady();
		}

		@Override
		public void process() {
			long generation;
			synchronized (stateLock) {
				if (screenChange != this) return;
				screenChange = null;
				ViewHandler.removeCallbacks(readinessDeadline);
				canvas.clearScreenChange(this);
				synchronized (this) {
					if (cancelled || current != canvas
							|| currentRequestGeneration.get() != requestGeneration
							|| ContextHolder.getActivity() != host) return;
					generation = showGeneration;
				}
			}
			canvas.finishScreenChange(generation);
		}
	}

	private void cancelScreenChangeLocked() {
		if (screenChange == null) return;
		ScreenChange old = screenChange;
		screenChange = null;
		ViewHandler.removeCallbacks(old.readinessDeadline);
		old.cancel();
		old.canvas.clearScreenChange(old);
	}

	private void beginScreenChangeLocked(Displayable target, long generation) {
		if (!(target instanceof Canvas canvas) || !hostVisible || !displayForeground) return;
		MicroActivity host = ContextHolder.getActivity();
		if (host == null) return;
		ScreenChange change = new ScreenChange(canvas, generation, host);
		screenChange = change;
		canvas.beginScreenChange(change);
		queue.postQueuedEvent(change);
		// A mounted SurfaceView is not guaranteed to produce a surfaceCreated callback.
		// Preserve normal startup ordering but bound the queue's head-of-line wait.
		ViewHandler.postDelayed(change.readinessDeadline, SCREEN_CHANGE_MAX_WAIT_MS);
	}

	/** Accept only a mount result from the host which owns the pending request. */
	public void onHostMounted(Displayable target, long generation, MicroActivity host) {
		synchronized (stateLock) {
			ScreenChange change = screenChange;
			if (change != null && change.canvas == target
					&& change.requestGeneration == generation && change.host == host) {
				change.mounted();
			}
		}
	}

	public void onHostMountFailed(Displayable target, long generation, MicroActivity host) {
		synchronized (stateLock) {
			ScreenChange change = screenChange;
			if (change != null && change.canvas == target
					&& change.requestGeneration == generation && change.host == host) {
				cancelScreenChangeLocked();
			}
		}
	}


	/**
	 * Applies the Display-owned presentation facts while their transaction lock is still held.
	 * This prevents a guest-thread setCurrent() snapshot from arriving after a newer host/UI edge.
	 */
	private void reconcileCanvasLocked(Displayable displayable, boolean isCurrent) {
		if (displayable instanceof Canvas canvas) {
			canvas.updatePresentationState(isCurrent, hostVisible, displayForeground);
		}
	}

	public static synchronized Display getDisplay(MIDlet midlet) {
		if (instance == null && midlet != null) {
			instance = new Display(runtimeForegroundGranted, runtimeForegroundGeneration);
		}
		return instance;
	}

	private Display(boolean foregroundGranted, long generation) {
		displayForeground = foregroundGranted;
		appliedForegroundGeneration = generation;
		MicroActivity activity = ContextHolder.getActivity();
		hostVisible = activity != null && activity.isVisible();
	}

	public static synchronized void initDisplay() {
		instance = null;
		runtimeForegroundGeneration = 0L;
		runtimeForegroundRequested = false;
		runtimeForegroundGranted = false;
	}

	/**
	 * Starts a new host foreground edge. It remains pending until the LCDUI barrier
	 * grants this exact generation, independently of guest startApp() completion.
	 */
	public static long requestForeground() {
		Display current;
		long generation;
		synchronized (Display.class) {
			runtimeForegroundRequested = true;
			runtimeForegroundGranted = false;
			generation = ++runtimeForegroundGeneration;
			current = instance;
		}
		if (current != null) {
			current.updateForegroundGranted(false, generation);
		}
		return generation;
	}

	/**
	 * Revokes display ownership synchronously at the Android visibility edge and invalidates every
	 * older foreground grant attempt.
	 */
	public static void revokeForeground() {
		Display current;
		long generation;
		synchronized (Display.class) {
			runtimeForegroundRequested = false;
			runtimeForegroundGranted = false;
			generation = ++runtimeForegroundGeneration;
			current = instance;
		}
		if (current != null) {
			current.updateForegroundGranted(false, generation);
		}
	}

	/** Returns only foreground ownership committed by the LCDUI barrier, not a pending request. */
	public static synchronized long currentGrantedForegroundGeneration() {
		return runtimeForegroundRequested && runtimeForegroundGranted
				? runtimeForegroundGeneration : 0L;
	}

	/**
	 * Grants display ownership only if the activation still belongs to the latest foreground edge.
	 * This fences rapid Home/return even when startApp() has not returned.
	 */
	public static synchronized long currentForegroundRequestGeneration() {
		return runtimeForegroundRequested ? runtimeForegroundGeneration : 0L;
	}

	public static synchronized boolean isForegroundRequestCurrent(long generation) {
		return generation != 0L
				&& runtimeForegroundRequested
				&& generation == runtimeForegroundGeneration;
	}

	public static boolean grantForeground(long generation) {
		Display current;
		synchronized (Display.class) {
			if (!runtimeForegroundRequested || generation != runtimeForegroundGeneration) {
				return false;
			}
			runtimeForegroundGranted = true;
			current = instance;
		}
		if (current != null) {
			current.updateForegroundGranted(true, generation);
		}
		return true;
	}

	private void updateForegroundGranted(boolean granted, long generation) {
		synchronized (stateLock) {
			if (generation < appliedForegroundGeneration) {
				return;
			}
			appliedForegroundGeneration = generation;
			if (displayForeground == granted) {
				return;
			}
			displayForeground = granted;
			if (!granted) cancelScreenChangeLocked();
			reconcileCanvasLocked(current, true);
		}
	}

	/**
	 * Attaches the persisted guest Display state to a replacement Android host. The new Activity is
	 * still in onCreate, so presentation remains hidden until its onStart edge arrives.
	 */
	public void attachHost(MicroActivity activity) {
		Displayable target;
		long requestGeneration;
		synchronized (stateLock) {
			presented = null;
			hostVisible = false;
			cancelScreenChangeLocked();
			target = current;
			requestGeneration = currentRequestGeneration.incrementAndGet();
			reconcileCanvasLocked(target, true);
		}
		if (target instanceof Alert alert) {
			alert.detachHost();
			final long generation = requestGeneration;
			ViewHandler.postEvent(() -> showAlert(alert, generation));
		} else if (target != null) {
			target.clearDisplayableView();
			activity.setCurrent(target, requestGeneration);
		}
	}

	public void detachHost() {
		Displayable target;
		synchronized (stateLock) {
			presented = null;
			hostVisible = false;
			cancelScreenChangeLocked();
			target = current;
			currentRequestGeneration.incrementAndGet();
			reconcileCanvasLocked(target, true);
		}
		if (target instanceof Alert alert) {
			alert.detachHost();
		} else if (target != null) {
			target.clearDisplayableView();
		}
	}

	/** Updates host foreground access without changing the guest's current Displayable. */
	public void setHostVisible(boolean visible) {
		Canvas hiddenCanvas = null;
		synchronized (stateLock) {
			if (hostVisible == visible) {
				return;
			}
			hostVisible = visible;
			if (!visible) {
				cancelScreenChangeLocked();
				if (current instanceof Canvas canvas) hiddenCanvas = canvas;
			}
			reconcileCanvasLocked(current, true);
		}
		// MicroActivity calls this on Android main. Finish UI-owned cancellation now,
		// rather than allowing a rapid Home/return to bypass its queued cleanup.
		if (hiddenCanvas != null) hiddenCanvas.endHostPresentation();
	}

	public static void postEvent(Event event) {
		queue.postEvent(event);
	}

	/** Enqueues a presentation callback without executing it under host ownership locks. */
	public static void postSerializedEvent(Event event) {
		queue.postQueuedEvent(event);
	}

	/**
	 * Posts a non-blocking barrier after already queued LCDUI callbacks. Its continuation may
	 * commit host presentation ownership or signal AMS, but never executes guest lifecycle code.
	 */
	public static void postAfterPendingCallbacks(Runnable runnable) {
		queue.postBarrier(runnable);
	}

	static EventQueue getEventQueue() {
		return queue;
	}

	public void setCurrent(Displayable displayable) {
		if (displayable == null) {
			// MIDP defines null as an AMS background request; guest current state is retained.
			MidletThread.requestBackground();
			return;
		}
		Displayable previous;
		long requestGeneration = 0L;
		Alert alert = null;
		boolean showCanvasAfterClosingAlert;
		synchronized (stateLock) {
			previous = this.current;
			if (previous instanceof Alert && displayable instanceof Alert) {
				throw new IllegalArgumentException();
			}
			if (displayable == previous) {
				// MIDP treats this as a foreground request. JL-Mod deliberately leaves that request
				// to AMS policy; guest code must never foreground the Android task directly.
				return;
			}
			requestGeneration = currentRequestGeneration.incrementAndGet();
			// The former host mount no longer certifies presentation for a new request.
			presented = null;
			cancelScreenChangeLocked();
			this.current = displayable;
			MemoryDiscoveryBridge.setCurrentDisplayable(displayable);
			if (displayable instanceof Alert nextAlert) {
				alert = nextAlert;
				alert.setNextDisplayable(previous);
			}
			showCanvasAfterClosingAlert =
					previous instanceof Alert && displayable instanceof Canvas;
			reconcileCanvasLocked(previous, false);
			beginScreenChangeLocked(displayable, requestGeneration);
			if (!showCanvasAfterClosingAlert) {
				reconcileCanvasLocked(displayable, true);
			}
		}
		if (previous instanceof Alert previousAlert) {
			previousAlert.close();
		}
		if (showCanvasAfterClosingAlert) {
			synchronized (stateLock) {
				if (current == displayable
						&& currentRequestGeneration.get() == requestGeneration) {
					reconcileCanvasLocked(displayable, true);
				}
			}
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			return;
		}
		if (alert != null) {
			Alert requestedAlert = alert;
			final long generation = requestGeneration;
			ViewHandler.postEvent(() -> showAlert(requestedAlert, generation));
		} else {
			activity.setCurrent(displayable, requestGeneration);
		}
	}

	public void setCurrent(Alert alert, Displayable displayable) {
		if (alert == null || displayable == null) {
			throw new NullPointerException();
		} else if (displayable instanceof Alert) {
			throw new IllegalArgumentException();
		}
		Displayable previous;
		long requestGeneration;
		synchronized (stateLock) {
			if (current instanceof Alert && current != alert) {
				// Display.setCurrent(Alert, ...) has the same restriction as the single-argument
				// overload: an Alert cannot replace another currently displayed Alert directly.
				throw new IllegalArgumentException();
			}
			if (current == alert) {
				alert.setNextDisplayable(displayable);
				MemoryDiscoveryBridge.setCurrentDisplayable(displayable);
				return;
			}
			previous = current;
			alert.setNextDisplayable(displayable);
			requestGeneration = currentRequestGeneration.incrementAndGet();
			// The former host mount no longer certifies presentation for a new request.
			presented = null;
			cancelScreenChangeLocked();
			current = alert;
			MemoryDiscoveryBridge.setCurrentDisplayable(displayable);
			reconcileCanvasLocked(previous, false);
		}
		if (previous instanceof Alert previousAlert) {
			previousAlert.close();
		}
		if (ContextHolder.getActivity() != null) {
			ViewHandler.postEvent(() -> showAlert(alert, requestGeneration));
		}
	}

	private void showAlert(Alert expectedAlert, long requestGeneration) {
		synchronized (stateLock) {
			if (current != expectedAlert || currentRequestGeneration.get() != requestGeneration) {
				return;
			}
			AlertDialog alertDialog = expectedAlert.prepareDialog();
			if (current != expectedAlert || currentRequestGeneration.get() != requestGeneration) {
				expectedAlert.close();
				return;
			}
			alertDialog.show();
			presented = expectedAlert;
			styleAlertDialog(alertDialog);
			expectedAlert.onDialogShown(alertDialog);
			if (current != expectedAlert || currentRequestGeneration.get() != requestGeneration) {
				alertDialog.dismiss();
			}
		}
	}

	/** Reconciles a visible Alert while sharing the same transaction lock as setCurrent(). */
	void refreshAlert(Alert expectedAlert, AlertDialog expectedDialog) {
		synchronized (stateLock) {
			if (current != expectedAlert) {
				return;
			}
			expectedAlert.rebuildDialog(expectedDialog);
		}
	}

	/**
	 * Restores the pre-Alert startup state. This is intentionally separate from setCurrent(null):
	 * MIDP defines the latter as a background request and it must not clear the guest current
	 * Displayable.
	 */
	void restoreAfterAlert(Alert expectedAlert) {
		MicroActivity activity;
		long requestGeneration;
		synchronized (stateLock) {
			if (current != expectedAlert) {
				return;
			}
			requestGeneration = currentRequestGeneration.incrementAndGet();
			current = null;
			presented = null;
			activity = ContextHolder.getActivity();
		}
		if (activity != null) {
			activity.setCurrent(null, requestGeneration);
		}
	}

	static void styleAlertDialog(AlertDialog alertDialog) {
		int accent = LegacyThemeColors.accent(alertDialog.getContext());
		if (alertDialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
			alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
		}
		if (alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
			alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
		}
		if (alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
			alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(accent);
		}
	}

	/** Reports the mounted Displayable; a stale host completion cannot publish an old screen. */
	public void onHostPresented(Displayable displayable, long requestGeneration) {
		synchronized (stateLock) {
			if (current == displayable && currentRequestGeneration.get() == requestGeneration) {
				presented = displayable;
			}
		}
	}

	/** Clears a dismissed Alert which may remain current while command handling proceeds. */
	void onHostPresentationEnded(Displayable displayable) {
		synchronized (stateLock) {
			if (presented == displayable) presented = null;
		}
	}

	boolean isDisplayableShown(Displayable displayable) {
		synchronized (stateLock) {
			return current == displayable && presented == displayable
					&& hostVisible && displayForeground;
		}
	}

	public Displayable getCurrent() {
		return current;
	}

	public void callSerially(Runnable r) {
		postEvent(RunnableEvent.getInstance(r).asGuestCallback());
	}

	public boolean flashBacklight(int duration) {
		return false;
	}

	/** @since MIDP 2.0 */
	public boolean vibrate(int duration) {
		return ContextHolder.vibrate(duration);
	}

	public void setCurrentItem(Item item) {
		Screen owner = item.getOwner();
		if (owner instanceof Form) {
			setCurrent(owner);
		} else {
			throw new IllegalStateException("Item is not owned by a Form");
		}
	}

	public int numAlphaLevels() {
		return 256;
	}

	public int numColors() {
		return Integer.MAX_VALUE;
	}

	public int getBestImageHeight(int imageType) {
		return 0;
	}

	public int getBestImageWidth(int imageType) {
		return 0;
	}

	public int getBorderStyle(boolean highlighted) {
		return highlighted ? Graphics.SOLID : Graphics.DOTTED;
	}

	public int getColor(int colorSpecifier) {
		return COLORS[colorSpecifier];
	}

	public boolean isColor() {
		return true;
	}
}
