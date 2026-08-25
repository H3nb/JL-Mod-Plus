/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2020-2026 Yury Kharchenko
 * Modified in 2026 for host-only Memory Editor root inspection.
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

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.event.Event;
import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.lcdui.event.RunnableEvent;
import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;
import ru.playsoftware.j2meloader.ui.LegacyThemeColors;

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
	static EventQueue queue = new EventQueue();


	static {
		queue.startProcessing();
	}

	private volatile Displayable current;
	/** Invalidates queued show requests when the displayable changes, including reusing an Alert. */
	private final AtomicLong currentRequestGeneration = new AtomicLong();
	/** Serializes display state transitions with Alert preparation/showing. */
	private final Object stateLock = new Object();

	public static Display getDisplay(MIDlet midlet) {
		if (instance == null && midlet != null) {
			instance = new Display();
		}
		return instance;
	}

	/** Returns the existing display without creating one as a scanner side effect. */
	public static Display peekDisplay() {
		return instance;
	}

	private Display() {
	}

	public static void initDisplay() {
		instance = null;
	}

	public static void postEvent(Event event) {
		queue.postEvent(event);
	}

	static EventQueue getEventQueue() {
		return queue;
	}

	/** Host-only root bridge; snapshots queued callback targets without exposing queue internals. */
	public static Object[] snapshotQueuedRunnableTargets() {
		return queue.snapshotRunnableTargets();
	}

	public void setCurrent(Displayable displayable) {
		if (displayable == null) {
			// MIDP defines null as a background hint; it must not clear the guest current
			// Displayable or close a visible Alert.
			MicroActivity activity = ContextHolder.getActivity();
			if (activity != null) {
				activity.requestBackground();
			}
			return;
		}
		Displayable previous;
		long requestGeneration = 0L;
		Alert alert = null;
			synchronized (stateLock) {
			previous = this.current;
			if (previous instanceof Alert && displayable instanceof Alert) {
				throw new IllegalArgumentException();
			}
			if (displayable == previous) {
				// MIDP defines this call as a foreground request even when the same Displayable is
				// already current. Keep the guest state untouched and ask the Android host to bring
				// its task forward on a best-effort basis.
				MicroActivity activity = ContextHolder.getActivity();
				if (activity != null) {
					activity.requestForeground();
				}
				return;
			}
			requestGeneration = currentRequestGeneration.incrementAndGet();
			this.current = displayable;
			if (displayable instanceof Alert nextAlert) {
				alert = nextAlert;
				alert.setNextDisplayable(previous);
			}
		}
		if (previous instanceof Canvas canvas) {
			canvas.setInvisible();
		} else if (previous instanceof Alert previousAlert) {
			previousAlert.close();
		}
		if (alert != null) {
			Alert requestedAlert = alert;
			final long generation = requestGeneration;
			ViewHandler.postEvent(() -> showAlert(requestedAlert, generation));
		} else {
			ContextHolder.getActivity().setCurrent(displayable);
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
				return;
			}
			previous = current;
			alert.setNextDisplayable(displayable);
			requestGeneration = currentRequestGeneration.incrementAndGet();
			current = alert;
		}
		if (previous instanceof Canvas canvas) {
			canvas.setInvisible();
		} else if (previous instanceof Alert previousAlert) {
			previousAlert.close();
		}
		ViewHandler.postEvent(() -> showAlert(alert, requestGeneration));
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
		synchronized (stateLock) {
			if (current != expectedAlert) {
				return;
			}
			currentRequestGeneration.incrementAndGet();
			current = null;
			activity = ContextHolder.getActivity();
		}
		if (activity != null) {
			activity.setCurrent(null);
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

	public Displayable getCurrent() {
		return current;
	}

	public void callSerially(Runnable r) {
		postEvent(RunnableEvent.getInstance(r));
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
