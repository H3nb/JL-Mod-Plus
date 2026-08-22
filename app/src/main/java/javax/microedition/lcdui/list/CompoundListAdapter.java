/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2018 Nikita Shakarun
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

package javax.microedition.lcdui.list;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;

import java.util.ArrayList;

import javax.microedition.lcdui.Choice;
import javax.microedition.util.ContextHolder;
import ru.playsoftware.j2meloader.ui.LegacyThemeColors;

public class CompoundListAdapter extends CompoundAdapter implements ListAdapter {
	private final int highlightColor;

	private final int listType;
	private final int viewResourceID;

	public CompoundListAdapter(int type, ArrayList<CompoundItem> items) {
		super(items);
		Context context = ContextHolder.getActivity();
		int accent = LegacyThemeColors.accent(context);
		highlightColor = (accent & 0x00FFFFFF) | (0x33 << 24);
		switch (type) {
			case Choice.IMPLICIT:
				viewResourceID = android.R.layout.simple_list_item_1;
				break;
			case Choice.EXCLUSIVE:
				viewResourceID = android.R.layout.simple_list_item_single_choice;
				break;
			case Choice.MULTIPLE:
				viewResourceID = android.R.layout.simple_list_item_multiple_choice;
				break;
			default:
				throw new IllegalArgumentException("list type " + type + " is not supported");
		}
		listType = type;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		convertView = getView(position, convertView, parent, viewResourceID);

		boolean selected = getItem(position).isSelected();
		if (listType == Choice.IMPLICIT) {
			convertView.setBackgroundColor(selected ? highlightColor : Color.TRANSPARENT);
		} else {
			CheckedTextView checkedTextView = (CheckedTextView) convertView;
			checkedTextView.setCheckMarkTintList(ColorStateList.valueOf(LegacyThemeColors.accent(
					convertView.getContext())));
			checkedTextView.setChecked(selected);
		}

		return convertView;
	}

	@Override
	public boolean areAllItemsEnabled() {
		return true;
	}

	@Override
	public boolean isEnabled(int position) {
		return true;
	}
}
