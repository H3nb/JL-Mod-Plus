/*
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

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingSnapshot;
import javax.microedition.util.ContextHolder;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Alert extends Screen {
	public static final int FOREVER = -2;
	public static final Command DISMISS_COMMAND = new Command("", Command.OK, 0);

	private static final class DialogCommandState {
		private final AtomicBoolean dispatched = new AtomicBoolean();
	}

	private volatile String text;
	private volatile Image image;
	/** Snapshot used by the Android dialog so a mutable guest image cannot race the UI thread. */
	private volatile Bitmap imageBitmap;
	private volatile AlertType type;
	private volatile int timeout = FOREVER;
	private volatile Gauge indicator;
	/** Indicator instance whose view is attached to the currently visible Android dialog. */
	private volatile Gauge dialogIndicator;
	private volatile AlertDialog dialog;
	private volatile Runnable timeoutRunnable;
	private volatile long timeoutGeneration;
	private volatile Displayable nextDisplayable;
	private volatile boolean contentModal;
	/** True when the visible dialog uses a ListView for an Alert with more than three commands. */
	private volatile boolean commandListDialog;
	/** Token belonging to the current Android dialog; old dialog callbacks cannot affect a rebuild. */
	private volatile DialogCommandState dialogCommandState;
	private final Object timeoutLock = new Object();
	private long timeoutGuestDeadlineMillis;
	private long timeoutHostDeadlineNanos;
	private TimingSession timeoutTimingSession;
	private Runnable timeoutTimingListener;

	public Alert(String title) {
		super.setTitle(title);
	}

	public Alert(String title, String text, Image image, AlertType type) {
		this(title);
		this.text = text;
		this.image = image;
		this.imageBitmap = snapshotBitmap(image);
		this.type = type;
	}

	public void setType(AlertType type) {
		this.type = type;
	}

	public AlertType getType() {
		return type;
	}

	public void setString(String str) {
		text = str;

		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			ViewHandler.postEvent(() -> {
				if (dialog == currentDialog) {
					if (commandListDialog) {
						postDialogRefresh(currentDialog);
					} else {
						currentDialog.setMessage(str);
						onDialogShown(currentDialog);
					}
				}
			});
		}
	}

	public String getString() {
		return text;
	}

	public void setImage(Image img) {
		image = img;
		imageBitmap = snapshotBitmap(img);

		AlertDialog currentDialog = dialog;
		Bitmap currentBitmap = imageBitmap;
		if (currentDialog != null) {
			ViewHandler.postEvent(() -> {
				if (dialog == currentDialog) {
					BitmapDrawable bitmapDrawable = currentBitmap == null
							? null
							: new BitmapDrawable(
									currentDialog.getContext().getResources(), currentBitmap);
					currentDialog.setIcon(bitmapDrawable);
				}
			});
		}
	}

	public Image getImage() {
		return image;
	}

	public void setIndicator(Gauge indicator) {
		if (indicator != null) {
			if (indicator.isInteractive() ||
					indicator.hasOwner() ||
					!indicator.commands.isEmpty() ||
					indicator.listener != null ||
					indicator.getLabel() != null ||
					indicator.preferredWidth != -1 ||
					indicator.preferredHeight != -1 ||
					indicator.getLayout() != Item.LAYOUT_DEFAULT) {
				throw new IllegalArgumentException();
			}
			indicator.setOwner(this);
		}
		if (this.indicator != null) {
			this.indicator.setOwner(null);
		}
		this.indicator = indicator;

		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			postDialogRefresh(currentDialog);
		}
	}

	public Gauge getIndicator() {
		return indicator;
	}

	public int getDefaultTimeout() {
		return FOREVER;
	}

	public void setTimeout(int timeout) {
		if (timeout != FOREVER && timeout <= 0) {
			throw new IllegalArgumentException("timeout must be positive or FOREVER");
		}
		synchronized (timeoutLock) {
			this.timeout = timeout;
			timeoutGuestDeadlineMillis = 0L;
			timeoutHostDeadlineNanos = 0L;
		}
		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			ViewHandler.postEvent(() -> {
				if (dialog == currentDialog) {
					scheduleTimeout(currentDialog);
				}
			});
		}
	}

	public int getTimeout() {
		return contentModal || commandCount() > 1 ? FOREVER : timeout;
	}

	private boolean finiteTimeout(ArrayList<Command> commandSnapshot) {
		return timeout > 0 && !contentModal && commandSnapshot.size() <= 1;
	}

	AlertDialog prepareDialog() {
		Context context = ContextHolder.getActivity();
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		ArrayList<Command> commandSnapshot = snapshotCommands();
		DialogCommandState commandState = new DialogCommandState();

		builder.setTitle(getTitle());
		builder.setOnDismissListener(this::onDismiss);

		if (imageBitmap != null) {
			builder.setIcon(new BitmapDrawable(context.getResources(), imageBitmap));
		}

		if (commandSnapshot.size() > 3) {
			// AppCompat does not attach setItems()'s ListView when a message or custom view is
			// also present. Build one content view that contains the message/indicator header and
			// the command list, otherwise an Alert with four or more commands can become visibly
			// stuck with no actionable control.
			commandListDialog = true;
			builder.setView(createCommandListContent(context, commandSnapshot, commandState));
		} else {
			commandListDialog = false;
			builder.setMessage(getString());
			if (indicator != null) {
				builder.setView(createIndicatorView(context));
			}

			Command positive = null;
			Command negative = null;
			Command neutral = null;

			for (Command command : commandSnapshot) {
				int cmdType = command.getCommandType();

				if (positive == null && cmdType == Command.OK) {
					positive = command;
				} else if (negative == null && cmdType == Command.CANCEL) {
					negative = command;
				} else if (neutral == null) {
					neutral = command;
				}
			}
			for (Command command : commandSnapshot) {
				if (positive == null && negative != command && neutral != command) {
					positive = command;
				} else if (negative == null && positive != command && neutral != command) {
					negative = command;
				}
			}

			if (positive == null) {
				positive = DISMISS_COMMAND;
			}
			Command positiveCommand = positive;
			builder.setPositiveButton(
					positiveCommand.getAndroidLabel(),
					(d, w) -> dispatchCommandOnce(commandState, positiveCommand));

			if (negative != null) {
				Command negativeCommand = negative;
				builder.setNegativeButton(
						negativeCommand.getAndroidLabel(),
						(d, w) -> dispatchCommandOnce(commandState, negativeCommand));
			}

			if (neutral != null) {
				Command neutralCommand = neutral;
				builder.setNeutralButton(
						neutralCommand.getAndroidLabel(),
						(d, w) -> dispatchCommandOnce(commandState, neutralCommand));
			}
		}

		contentModal = false;
		dialog = builder.create();
		dialogIndicator = indicator;
		dialogCommandState = commandState;
		boolean commandModal = commandSnapshot.size() > 1;
		if (commandModal) {
			dialog.setCancelable(false);
			dialog.setCanceledOnTouchOutside(false);
		} else if (listener == null) {
			dialog.setCancelable(true);
			dialog.setCanceledOnTouchOutside(true);
		} else {
			boolean hasExplicitCommand = !commandSnapshot.isEmpty();
			dialog.setCancelable(!hasExplicitCommand);
			dialog.setCanceledOnTouchOutside(!hasExplicitCommand);
		}
		return dialog;
	}

	private View createIndicatorView(Context context) {
		View indicatorView = indicator.getItemContentView();
		indicatorView.setPadding(dialogPadding(context), 0, dialogPadding(context), 0);
		return indicatorView;
	}

	private View createCommandListContent(
			Context context,
			ArrayList<Command> commandSnapshot,
			DialogCommandState commandState) {
		ListView listView = new ListView(context);
		String currentText = getString();
		if (currentText != null || indicator != null) {
			LinearLayout header = new LinearLayout(context);
			header.setOrientation(LinearLayout.VERTICAL);
			int padding = dialogPadding(context);
			if (currentText != null) {
				TextView message = new TextView(context);
				message.setText(currentText);
				message.setPadding(padding, padding, padding, padding);
				header.addView(message, new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			}
			if (indicator != null) {
				header.addView(createIndicatorView(context), new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			}
			listView.addHeaderView(header, null, false);
		}

		String[] labels = new String[commandSnapshot.size()];
		for (int i = 0; i < commandSnapshot.size(); i++) {
			labels[i] = commandSnapshot.get(i).getAndroidLabel();
		}
		listView.setAdapter(new ArrayAdapter<>(
				context, android.R.layout.simple_list_item_1, labels));
		listView.setOnItemClickListener((parent, view, position, id) -> {
			int commandIndex = position - listView.getHeaderViewsCount();
			if (commandIndex >= 0 && commandIndex < commandSnapshot.size()) {
				dispatchCommandOnce(commandState, commandSnapshot.get(commandIndex));
			}
			AlertDialog currentDialog = dialog;
			if (currentDialog != null) {
				currentDialog.dismiss();
			}
		});
		return listView;
	}

	private static int dialogPadding(Context context) {
		TypedValue typedValue = new TypedValue();
		boolean resolved = context.getTheme().resolveAttribute(
				android.R.attr.dialogPreferredPadding, typedValue, true);
		int padding = resolved
				? (int) typedValue.getDimension(context.getResources().getDisplayMetrics()) : 0;
		return padding > 0
				? padding
				: (int) (16f * context.getResources().getDisplayMetrics().density + 0.5f);
	}

	private ArrayList<Command> snapshotCommands() {
		return new ArrayList<>(commands);
	}

	private int commandCount() {
		return commands.size();
	}

	private void dispatchCommandOnce(DialogCommandState state, Command command) {
		if (state != null && state.dispatched.compareAndSet(false, true)) {
			fireCommandAction(command);
		}
	}

	/** Finishes Android layout before deciding whether a timed Alert must become modal. */
	void onDialogShown(AlertDialog alertDialog) {
		View decor = alertDialog.getWindow() == null
				? null : alertDialog.getWindow().getDecorView();
		Runnable measure = () -> {
			if (dialog != alertDialog) {
				return;
			}
			ArrayList<Command> commandSnapshot = snapshotCommands();
			contentModal = decor != null && contentRequiresScrolling(decor);
			boolean commandModal = commandSnapshot.size() > 1;
			if (contentModal || commandModal) {
				alertDialog.setCancelable(false);
				alertDialog.setCanceledOnTouchOutside(false);
			} else if (listener == null) {
				alertDialog.setCancelable(true);
				alertDialog.setCanceledOnTouchOutside(true);
			} else {
				boolean hasExplicitCommand = !commandSnapshot.isEmpty();
				alertDialog.setCancelable(!hasExplicitCommand);
				alertDialog.setCanceledOnTouchOutside(!hasExplicitCommand);
			}
			scheduleTimeout(alertDialog);
		};
		if (decor == null) {
			ViewHandler.postEvent(measure);
		} else {
			decor.post(measure);
		}
	}

	private static boolean contentRequiresScrolling(View view) {
		if (view.canScrollVertically(1) || view.canScrollVertically(-1)) {
			return true;
		}
		if (view instanceof TextView textView && textView.getLayout() != null) {
			int contentHeight = textView.getLayout().getHeight();
			int viewportHeight = textView.getHeight()
					- textView.getPaddingTop() - textView.getPaddingBottom();
			if (viewportHeight > 0 && contentHeight > viewportHeight) {
				return true;
			}
		}
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				if (contentRequiresScrolling(group.getChildAt(i))) {
					return true;
				}
			}
		}
		return false;
	}

	void scheduleTimeout(AlertDialog alertDialog) {
		Runnable oldCallback;
		TimingSession oldSession;
		Runnable oldListener;
		TimingSession listenerSession = null;
		Runnable listener = null;
		Runnable timeoutCallback = null;
		long hostDelay = 0L;
		long callbackGeneration = 0L;
		long listenerRevision = 0L;
		Command timeoutCommand = null;
		DialogCommandState timeoutCommandState = dialogCommandState;
		ArrayList<Command> commandSnapshot = snapshotCommands();

		synchronized (timeoutLock) {
			timeoutGeneration++;
			oldCallback = timeoutRunnable;
			oldSession = timeoutTimingSession;
			oldListener = timeoutTimingListener;
			timeoutRunnable = null;
			timeoutTimingSession = null;
			timeoutTimingListener = null;

			if (finiteTimeout(commandSnapshot)) {
				if (timeoutGuestDeadlineMillis == 0L && timeoutHostDeadlineNanos == 0L) {
					TimingSession activeSession = GuestTimingBridge.activeSession();
					TimingSnapshot snapshot = activeSession == null
							? null : activeSession.snapshotIfOpen();
					if (snapshot != null) {
						timeoutTimingSession = activeSession;
						timeoutGuestDeadlineMillis = saturatingAdd(
								snapshot.guestMonotonicNanos() / 1_000_000L, timeout);
					} else {
						timeoutHostDeadlineNanos = saturatingAdd(
								System.nanoTime(), millisToNanos(timeout));
					}
				}
				if (timeoutTimingSession == null && timeoutGuestDeadlineMillis != 0L
						&& oldSession != null && oldSession.snapshotIfOpen() != null) {
					timeoutTimingSession = oldSession;
				}

				if (timeoutTimingSession != null) {
					TimingSnapshot snapshot = timeoutTimingSession.snapshotIfOpen();
					if (snapshot == null) {
						timeoutTimingSession = null;
						timeoutGuestDeadlineMillis = 0L;
						timeoutHostDeadlineNanos = System.nanoTime();
					} else {
						long remainingGuestMillis = timeoutGuestDeadlineMillis
								- snapshot.guestMonotonicNanos() / 1_000_000L;
						hostDelay = remainingGuestMillis <= 0L
								? 0L : timeoutTimingSession.hostDelayMillis(remainingGuestMillis);
						listenerRevision = timeoutTimingSession.timingRevision();
						listenerSession = timeoutTimingSession;
						listener = () -> ViewHandler.postEvent(() -> {
							if (dialog == alertDialog) {
								scheduleTimeout(alertDialog);
							}
						});
						timeoutTimingListener = listener;
					}
				}

				if (timeoutTimingSession == null && hostDelay == 0L) {
					long remainingNanos = timeoutHostDeadlineNanos - System.nanoTime();
					if (remainingNanos > 0L) {
						hostDelay = remainingNanos / 1_000_000L;
						if (remainingNanos % 1_000_000L != 0L
								&& hostDelay < Long.MAX_VALUE) {
							hostDelay++;
						}
					}
				}

				timeoutCommand = commandSnapshot.size() == 1 ? commandSnapshot.get(0) : null;
				callbackGeneration = timeoutGeneration;
				final Command scheduledCommand = timeoutCommand;
				final DialogCommandState scheduledCommandState = timeoutCommandState;
				final long scheduledGeneration = callbackGeneration;
				Runnable callback = () -> {
					TimingSession callbackSession;
					Runnable callbackListener;
					Command command;
					synchronized (timeoutLock) {
						if (dialog != alertDialog || timeoutGeneration != scheduledGeneration) {
							return;
						}
						timeoutGeneration++;
						timeoutRunnable = null;
						callbackSession = timeoutTimingSession;
						callbackListener = timeoutTimingListener;
						timeoutTimingSession = null;
						timeoutTimingListener = null;
						timeoutGuestDeadlineMillis = 0L;
						timeoutHostDeadlineNanos = 0L;
						command = scheduledCommand;
					}
					if (callbackSession != null && callbackListener != null) {
						callbackSession.unregisterTimingChangeListener(callbackListener);
					}
					// MIDP defines timeout as equivalent to invoking the only visible command.
					if (command != null) {
						dispatchCommandOnce(scheduledCommandState, command);
					}
					alertDialog.dismiss();
				};
				timeoutCallback = callback;
				timeoutRunnable = callback;
			} else {
				// A modal Alert has no active timeout. If it later becomes non-modal, start a
				// fresh logical deadline instead of interpreting a detached guest deadline as host time.
				timeoutGuestDeadlineMillis = 0L;
				timeoutHostDeadlineNanos = 0L;
			}
		}

		if (oldCallback != null) {
			ViewHandler.removeCallbacks(oldCallback);
		}
		if (oldSession != null && oldListener != null) {
			oldSession.unregisterTimingChangeListener(oldListener);
		}
		if (listenerSession != null && listener != null) {
			listenerSession.registerTimingChangeListener(listener);
			// A speed update can race the registration window above. Requeue one UI refresh when
			// that happens; updates after registration are delivered through the listener itself.
			if (listenerSession.timingRevision() != listenerRevision) {
				ViewHandler.postEvent(() -> {
					if (dialog == alertDialog) {
						scheduleTimeout(alertDialog);
					}
				});
			}
		}
		if (timeoutCallback != null) {
			// Display.close() can race a layout callback between registration and posting. Remove
			// the newly registered callback/listener if this dialog is no longer current.
			if (dialog != alertDialog) {
				cancelTimeout();
				return;
			}
			ViewHandler.postDelayed(timeoutCallback, hostDelay);
		}
	}

	private void cancelTimeout() {
		cancelTimeout(true);
	}

	private void cancelTimeout(boolean clearDeadline) {
		Runnable callback;
		TimingSession session;
		Runnable listener;
		synchronized (timeoutLock) {
			timeoutGeneration++;
			callback = timeoutRunnable;
			timeoutRunnable = null;
			session = timeoutTimingSession;
			listener = timeoutTimingListener;
			timeoutTimingSession = null;
			timeoutTimingListener = null;
			if (clearDeadline) {
				timeoutGuestDeadlineMillis = 0L;
				timeoutHostDeadlineNanos = 0L;
			}
		}
		if (callback != null) {
			ViewHandler.removeCallbacks(callback);
		}
		if (session != null && listener != null) {
			session.unregisterTimingChangeListener(listener);
		}
	}

	private static long millisToNanos(long millis) {
		return millis > Long.MAX_VALUE / 1_000_000L
				? Long.MAX_VALUE : Math.max(0L, millis) * 1_000_000L;
	}

	private static long saturatingAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) {
			return Long.MAX_VALUE;
		}
		return left + right;
	}

	@Override
	public void addCommand(Command cmd) {
		if (cmd == null) {
			throw new NullPointerException();
		} else if (cmd == DISMISS_COMMAND) {
			return;
		}
		synchronized (commands) {
			if (commands.contains(cmd)) {
				return;
			}
			commands.add(cmd);
		}
		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			postCommandsChanged(currentDialog);
		}
	}

	@Override
	public void removeCommand(Command cmd) {
		if (cmd == DISMISS_COMMAND) {
			return;
		}
		synchronized (commands) {
			commands.remove(cmd);
		}
		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			postCommandsChanged(currentDialog);
		}
	}

	@Override
	public void setCommandListener(CommandListener listener) {
		if (this.listener == listener) {
			return;
		}
		this.listener = listener;
		AlertDialog currentDialog = dialog;
		if (currentDialog != null) {
			postCommandsChanged(currentDialog);
		}
	}

	@Override
	View getScreenView() {
		throw new IllegalStateException("Alert not support this");
	}

	@Override
	void clearScreenView() {
	}

	void setNextDisplayable(Displayable nextDisplayable) {
		this.nextDisplayable = nextDisplayable;
	}

	void onDismiss(DialogInterface dialogInterface) {
		if (dialog != dialogInterface) {
			return;
		}
		cancelTimeout();
		Gauge dismissedIndicator = dialogIndicator;
		dialogIndicator = null;
		if (dismissedIndicator != null) {
			dismissedIndicator.clearItemContentView();
		}
		dialog = null;
		DialogCommandState dismissedCommandState = dialogCommandState;
		dialogCommandState = null;
		contentModal = false;
		commandListDialog = false;
		if (listener == null) {
			Displayable displayable = nextDisplayable;
			nextDisplayable = null;
			if (displayable != null) {
				Display display = Display.getDisplay(null);
				if (display != null) {
					display.setCurrent(displayable);
				}
			} else {
				Display display = Display.getDisplay(null);
				if (display != null) {
					display.restoreAfterAlert(this);
				}
			}
		} else if (commands.isEmpty()) {
			dispatchCommandOnce(dismissedCommandState, DISMISS_COMMAND);
		}
	}

	private void postCommandsChanged(AlertDialog expectedDialog) {
		postDialogRefresh(expectedDialog);
	}

	private void postDialogRefresh(AlertDialog expectedDialog) {
		ViewHandler.postEvent(() -> {
			if (dialog != expectedDialog) {
				return;
			}
			Display display = Display.getDisplay(null);
			if (display != null) {
				display.refreshAlert(this, expectedDialog);
			} else {
				rebuildDialog(expectedDialog);
			}
		});
	}

	void rebuildDialog(AlertDialog expectedDialog) {
		if (dialog != expectedDialog) {
			return;
		}
		// Android AlertDialog cannot reliably add/remove button slots after show(). Rebuild
		// on the UI thread from an atomic command snapshot so a MIDlet changing commands from
		// a TimerTask never leaves stale or partially visible actions.
		// Detach the old Gauge's cached content before invalidating its dialog identity. A stale
		// dismiss callback must not clear the replacement dialog's indicator, and a replacement
		// dialog must receive a fresh Android View rather than a View that still has the old parent.
		Gauge oldDialogIndicator = dialogIndicator;
		if (oldDialogIndicator != null) {
			oldDialogIndicator.clearItemContentView();
		}
		dialogIndicator = null;
		dialog = null;
		dialogCommandState = null;
		cancelTimeout(true);
		expectedDialog.dismiss();
		AlertDialog replacement = prepareDialog();
		replacement.show();
		Display.styleAlertDialog(replacement);
		onDialogShown(replacement);
	}

	private static Bitmap snapshotBitmap(Image image) {
		return image == null ? null : Image.createImage(image).getBitmap();
	}

	void close() {
		cancelTimeout();
		this.nextDisplayable = null;
		AlertDialog dialog = this.dialog;
		Gauge oldDialogIndicator = dialogIndicator;
		if (oldDialogIndicator != null) {
			oldDialogIndicator.clearItemContentView();
		}
		dialogIndicator = null;
		this.dialog = null;
		this.dialogCommandState = null;
		this.contentModal = false;
		this.commandListDialog = false;
		if (dialog != null) {
			dialog.dismiss();
		}
	}
}
