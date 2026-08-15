/*
 * Copyright 2019-2023 Yury Kharchenko
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

import android.view.ViewGroup;

import androidx.compose.ui.platform.ComposeView;

import java.util.Collections;
import java.util.List;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Screen;

public class ScreenSoftBar extends AbstractSoftKeysBar {
	private final ScreenSoftBarComposeController controller;

	public ScreenSoftBar(Screen target, ViewGroup root, List<Command> commands) {
		super(target);
		ComposeView composeView = new ComposeView(root.getContext());
		composeView.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.addView(composeView);
		controller = new ScreenSoftBarComposeController(composeView,
				command -> target.fireCommandAction(command));
		Collections.sort(commands);
		onCommandsChanged(commands);
	}

	@Override
	protected void onCommandsChanged(List<Command> list) {
		closeMenu();
		List<Command> commands = this.commands;
		commands.clear();
		commands.addAll(list);
		controller.update(ScreenSoftBarPolicy.present(commands));
	}

	public void showMenu() {
		controller.openMenu();
	}

	@Override
	public void closeMenu() {
		controller.closeMenu();
	}
}
