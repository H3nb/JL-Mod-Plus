/*
 * Copyright 2019-2023 Yury Kharchenko
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

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import java.util.Collections;
import java.util.List;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Screen;

public class ScreenSoftBar extends AbstractSoftKeysBar {
	private final ScreenSoftBarComposeView composeView;

	public ScreenSoftBar(Screen target, ViewGroup root, List<Command> commands) {
		super(target);
		composeView = new ScreenSoftBarComposeView(root.getContext(), command -> {
			if (command == null) {
				showMenu();
			} else {
				target.fireCommandAction(command);
			}
		});
		root.addView(composeView, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		Collections.sort(commands);
		onCommandsChanged(commands);
	}

	@Override
	protected void onCommandsChanged(List<Command> list) {
		List<Command> commands = this.commands;
		commands.clear();
		commands.addAll(list);
		composeView.setCommands(commands);
	}

	static boolean shouldShowMenu(int commandCount) {
		return commandCount > 3;
	}

	public void showMenu() {
		if (!shouldShowMenu(commands.size())) {
			return;
		}

		PopupWindow popup = prepareMenu(2);
		int y = composeView.getHeight();
		View rootView = composeView.getRootView();
		popup.setWidth(Math.min(rootView.getWidth(), rootView.getHeight()) / 2);
		popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
		popup.showAtLocation(rootView, Gravity.RIGHT | Gravity.BOTTOM, 0, y);
	}
}
