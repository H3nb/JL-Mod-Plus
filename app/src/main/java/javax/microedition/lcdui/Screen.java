/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2018 Nikita Shakarun
 * Copyright 2022-2026 Yury Kharchenko
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

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;

import javax.microedition.lcdui.commands.ScreenSoftBar;
import io.github.h3nb.jlmodplus.input.HostCommand;

public abstract class Screen extends Displayable {

	private LinearLayout layout;

	@Override
	public View getDisplayableView() {
		if (layout == null) {
			layout = (LinearLayout) super.getDisplayableView();

			View screenView = getScreenView();
			screenView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
			layout.addView(screenView);
			softBar = new ScreenSoftBar(this, layout, new ArrayList<>(commands));
		}

		return layout;
	}

	@Override
	public void clearDisplayableView() {
		super.clearDisplayableView();
		layout = null;
		if (softBar != null) {
			softBar.closeMenu();
			softBar = null;
		}
		clearScreenView();
	}

	/** Controller boundary for an app-owned Screen soft-menu modal. */
	public boolean isControllerModalActive() {
		ScreenSoftBar controllerBar = controllerSoftBar();
		return controllerBar != null && controllerBar.isControllerMenuVisible();
	}

	/** Routes host navigation directly to the app-owned soft-menu surface. */
	public boolean handleHostCommand(HostCommand command, boolean pressed) {
		ScreenSoftBar controllerBar = controllerSoftBar();
		if (controllerBar == null) return false;
		if (command == HostCommand.Back && !controllerBar.isControllerMenuVisible()) {
			return pressed && controllerBar.handleControllerBack();
		}
		return controllerBar.handleHostCommand(command, pressed);
	}

	public boolean handleControllerMotion(MotionEvent event) {
		ScreenSoftBar controllerBar = controllerSoftBar();
		return controllerBar != null && controllerBar.handleControllerMotion(event);
	}

	/** Gives a presented BACK/EXIT command first refusal of the controller's contextual Back. */
	public boolean handleControllerBack() {
		ScreenSoftBar controllerBar = controllerSoftBar();
		return controllerBar != null && controllerBar.handleControllerBack();
	}

	@Nullable
	private ScreenSoftBar controllerSoftBar() {
		return softBar instanceof ScreenSoftBar ? (ScreenSoftBar) softBar : null;
	}

	abstract View getScreenView();

	abstract void clearScreenView();
}
