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
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.util.ContextHolder;

public class Alert extends Screen {
	public static final int FOREVER = -2;
	public static final Command DISMISS_COMMAND = new Command("", Command.OK, 0);

	private String text;
	private Image image;
	private AlertType type;
	private int timeout;
	private Gauge indicator;
	private AlertDialog alertDialog;

	private Form form;
	private Displayable nextDisplayable;
	private Command positive;
	private Command negative;
	private Command neutral;

	private final SimpleEvent msgSetString = new SimpleEvent() {
		@Override
		public void process() {
			alertDialog.setMessage(text);
		}
	};

	private final SimpleEvent msgSetImage = new SimpleEvent() {
		@Override
		public void process() {
			BitmapDrawable bitmapDrawable = new BitmapDrawable(image.getBitmap());
			alertDialog.setIcon(bitmapDrawable);
		}
	};

	private final SimpleEvent msgCommandsChanged = new SimpleEvent() {
		@Override
		public void process() {
			if (listener == null) {
				alertDialog.setCancelable(true);
				alertDialog.setCanceledOnTouchOutside(true);
				return;
			}
			alertDialog.setCanceledOnTouchOutside(commands.isEmpty());
		}
	};

	public Alert(String title) {
		this(title, null, null, null);
	}

	public Alert(String title, String text, Image image, AlertType type) {
		super.setTitle(title);

		this.text = text;
		this.image = image;
		this.type = type;
		this.timeout = FOREVER;
	}

	public void setType(AlertType type) {
		this.type = type;
	}

	public AlertType getType() {
		return type;
	}

	public void setString(String str) {
		text = str;

		if (alertDialog != null) {
			ViewHandler.postEvent(msgSetString);
		}
	}

	public String getString() {
		return text;
	}

	public void setImage(Image img) {
		image = img;

		if (alertDialog != null) {
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
					indicator.commands.size() > 0 ||
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
		this.timeout = timeout;
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
		builder.setOnDismissListener(dialog -> {
			alertDialog = null;
			Gauge indicator = this.indicator;
			if (indicator != null) {
				indicator.clearItemContentView();
			}
			if (listener == null) {
				dismiss();
			} else if (commands.isEmpty()) {
				fireCommandAction(DISMISS_COMMAND);
			}
		});

		if (image != null) {
			builder.setIcon(new BitmapDrawable(context.getResources(), image.getBitmap()));
		}

		if (indicator != null) {
			View indicatorView = indicator.getItemContentView();
			TypedValue typedValue = new TypedValue();
			context.getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, typedValue, true);
			int p = (int) typedValue.getDimension(context.getResources().getDisplayMetrics());
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

		alertDialog = builder.create();
		if (listener == null) {
			alertDialog.setCancelable(true);
			alertDialog.setCanceledOnTouchOutside(true);
		} else {
			alertDialog.setCanceledOnTouchOutside(commands.isEmpty());
		}
		return alertDialog;
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
		if (commands.size() == 1 && alertDialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
		}
	}

	@Override
	public void removeCommand(Command cmd) {
		if (cmd == DISMISS_COMMAND) {
			return;
		}
		commands.remove(cmd);
		if (commands.isEmpty() && alertDialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
		}
	}

	@Override
	public void setCommandListener(CommandListener listener) {
		if (this.listener == listener) {
			return;
		}
		this.listener = listener;
		if (alertDialog != null) {
			ViewHandler.postEvent(msgCommandsChanged);
		}
	}

	@Override
	public View getScreenView() {
		if (form == null) {
			form = new Form(getTitle());

			form.append(image);
			form.append(text);
		}

		return form.getDisplayableView();
	}

	@Override
	public void clearScreenView() {
		if (form != null) {
			form.clearDisplayableView();
			form = null;
		}
	}

	void setNextDisplayable(Displayable nextDisplayable) {
		this.nextDisplayable = nextDisplayable;
	}

	void close() {
		this.nextDisplayable = null;
		AlertDialog dialog = this.alertDialog;
		if (dialog != null) {
			dialog.dismiss();
		}
	}

	private void dismiss() {
		Displayable displayable = nextDisplayable;
		if (displayable != null) {
			Display.getDisplay(null).setCurrent(displayable);
		}
	}
}
