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
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.util.ContextHolder;

public class Alert extends Screen {
	public static final int FOREVER = -2;
	public static final Command DISMISS_COMMAND = new Command("", Command.OK, 0);

	private String text;
	private Image image;
	private AlertType type;
	private int timeout = FOREVER;
	private Gauge indicator;
	private AlertDialog dialog;
	private Runnable timeoutRunnable;
	private Displayable nextDisplayable;
	private Command positive;
	private Command negative;
	private Command neutral;

	private final SimpleEvent msgSetString = new SimpleEvent() {
		@Override
		public void process() {
			dialog.setMessage(text);
		}
	};

	private final SimpleEvent msgSetImage = new SimpleEvent() {
		@Override
		public void process() {
			BitmapDrawable bitmapDrawable = new BitmapDrawable(image.getBitmap());
			dialog.setIcon(bitmapDrawable);
		}
	};

	private final SimpleEvent msgCommandsChanged = new SimpleEvent() {
		@Override
		public void process() {
			if (listener == null) {
				dialog.setCancelable(true);
				dialog.setCanceledOnTouchOutside(true);
				return;
			}
			dialog.setCanceledOnTouchOutside(commands.isEmpty());
		}
	};

	public Alert(String title) {
		super.setTitle(title);
	}

	public Alert(String title, String text, Image image, AlertType type) {
		this(title);
		this.text = text;
		this.image = image;
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

		if (dialog != null) {
			ViewHandler.postEvent(msgSetString);
		}
	}

	public String getString() {
		return text;
	}

	public void setImage(Image img) {
		image = img;

		if (dialog != null) {
			ViewHandler.postEvent(msgSetImage);
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
		this.timeout = timeout;
		if (dialog != null) {
			scheduleTimeout(dialog);
		}
	}

	public int getTimeout() {
		return timeout;
	}

	boolean finiteTimeout() {
		return timeout > 0 && commands.isEmpty();
	}

	AlertDialog prepareDialog() {
		Context context = ContextHolder.getActivity();
		AlertDialog.Builder builder = new AlertDialog.Builder(context);

		builder.setTitle(getTitle());
		builder.setMessage(getString());
		builder.setOnDismissListener(this::onDismiss);

		if (image != null) {
			builder.setIcon(new BitmapDrawable(context.getResources(), image.getBitmap()));
		}

		if (indicator != null) {
			View indicatorView = indicator.getItemContentView();
			TypedValue typedValue = new TypedValue();
			boolean resolved = context.getTheme().resolveAttribute(android.R.attr.dialogPreferredPadding, typedValue, true);
			int p = resolved ? (int) typedValue.getDimension(context.getResources().getDisplayMetrics()) : 0;
			if (p <= 0) {
				p = (int) (16f * context.getResources().getDisplayMetrics().density + 0.5f);
			}
			indicatorView.setPadding(p, 0, p, 0);
			builder.setView(indicatorView);
		}

		positive = null;
		negative = null;
		neutral = null;

		for (Command command : commands) {
			int cmdType = command.getCommandType();

			if (positive == null && cmdType == Command.OK) {
				positive = command;
			} else if (negative == null && cmdType == Command.CANCEL) {
				negative = command;
			} else if (neutral == null) {
				neutral = command;
			}
		}
		for (Command command : commands) {
			if (positive == null && negative != command && neutral != command) {
				positive = command;
			} else if (negative == null && positive != command && neutral != command) {
				negative = command;
			}
		}

		if (positive == null) {
			positive = DISMISS_COMMAND;
		}
		builder.setPositiveButton(positive.getAndroidLabel(), (d, w) -> fireCommandAction(positive));

		if (negative != null) {
			builder.setNegativeButton(negative.getAndroidLabel(), (d, w) -> fireCommandAction(negative));
		}

		if (neutral != null) {
			builder.setNeutralButton(neutral.getAndroidLabel(), (d, w) -> fireCommandAction(neutral));
		}

		dialog = builder.create();
		if (listener == null) {
			dialog.setCancelable(true);
			dialog.setCanceledOnTouchOutside(true);
		} else {
			dialog.setCanceledOnTouchOutside(commands.isEmpty());
		}
		return dialog;
	}

	void scheduleTimeout(AlertDialog alertDialog) {
		cancelTimeout();
		if (!finiteTimeout()) {
			return;
		}
		long hostDelay = GuestTimingBridge.hostDelayMillis(timeout);
		Runnable timeoutCallback = () -> {
			if (dialog == alertDialog) {
				timeoutRunnable = null;
				alertDialog.dismiss();
			}
		};
		timeoutRunnable = timeoutCallback;
		ViewHandler.postDelayed(timeoutCallback, hostDelay);
	}

	private void cancelTimeout() {
		Runnable callback = timeoutRunnable;
		if (callback != null) {
			ViewHandler.removeCallbacks(callback);
			timeoutRunnable = null;
		}
	}

	@Override
	public void addCommand(Command cmd) {
		if (cmd == null) {
			throw new NullPointerException();
		} else if (cmd == DISMISS_COMMAND) {
			return;
		} else if (commands.contains(cmd)) {
			return;
		}
		commands.add(cmd);
		if (commands.size() == 1 && dialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
		}
	}

	@Override
	public void removeCommand(Command cmd) {
		if (cmd == DISMISS_COMMAND) {
			return;
		}
		commands.remove(cmd);
		if (commands.isEmpty() && dialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
		}
	}

	@Override
	public void setCommandListener(CommandListener listener) {
		if (this.listener == listener) {
			return;
		}
		this.listener = listener;
		if (dialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
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
		cancelTimeout();
		dialog = null;
		Gauge indicator = this.indicator;
		if (indicator != null) {
			indicator.clearItemContentView();
		}
		if (listener == null) {
			Displayable displayable = nextDisplayable;
			if (displayable != null) {
				Display.getDisplay(null).setCurrent(displayable);
			}
		} else if (commands.isEmpty()) {
			fireCommandAction(DISMISS_COMMAND);
		}
	}

	void close() {
		cancelTimeout();
		this.nextDisplayable = null;
		AlertDialog dialog = this.dialog;
		if (dialog != null) {
			dialog.dismiss();
		}
	}
}
