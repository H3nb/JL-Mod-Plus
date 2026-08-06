/*
 * Copyright 2022-2023 Yury Kharchenko
 * Copyright 2026 H3NB
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

package javax.microedition.lcdui.commands;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.PopupWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.util.ContextHolder;

public abstract class AbstractSoftKeysBar {
	protected final Displayable target;
	protected final List<Command> commands = new ArrayList<>();
	private PopupWindow popup;
	private SoftMenuComposeView menuView;
	private Context popupContext;

	protected AbstractSoftKeysBar(Displayable target) {
		this.target = target;
	}

	public void notifyChanged(List<Command> list) {
		Collections.sort(list);
		ViewHandler.postEvent(() -> onCommandsChanged(list));
	}

	protected static int effectiveSkip(int skip, int commandCount) {
		return Math.min(Math.max(skip, 0), commandCount);
	}

	protected PopupWindow prepareMenu(int skip) {
		Context context = ContextHolder.getActivity();
		if (context == null) {
			return null;
		}
		if (popup != null && popupContext != context) {
			releaseMenu();
		}
		if (popup == null) {
			menuView = new SoftMenuComposeView(context, command -> {
				target.fireCommandAction(command);
				if (popup != null) {
					popup.dismiss();
				}
			});
			popup = new PopupWindow(context, null, androidx.appcompat.R.attr.actionOverflowMenuStyle);
			popup.setExitTransition(null);
			popup.setOutsideTouchable(true);
			popup.setFocusable(true);
			popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			popup.setContentView(menuView);
			popup.setOnDismissListener(menuView::clearCommands);
			popupContext = context;
		}
		int safeSkip = effectiveSkip(skip, commands.size());
		menuView.setCommands(
				safeSkip == 0
						? new ArrayList<>(commands)
						: new ArrayList<>(commands.subList(safeSkip, commands.size())));
		return popup;
	}

	protected abstract void onCommandsChanged(List<Command> list);

	public void closeMenu() {
		if (popup != null && popup.isShowing()) {
			popup.dismiss();
		}
	}

	/**
	 * Detaches and drops the popup for permanent teardown. A popup built for a
	 * previous Activity keeps a stale Compose owner and window token; drop it
	 * when the soft keys bar is cleared or the owning Activity changes.
	 */
	public void releaseMenu() {
		if (popup != null) {
			popup.setOnDismissListener(null);
			if (popup.isShowing()) {
				popup.dismiss();
			}
			popup.setContentView(null);
			popup = null;
		}
		menuView = null;
		popupContext = null;
	}
}
