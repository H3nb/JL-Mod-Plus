/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2019 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
 *
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

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;

import javax.microedition.lcdui.commands.ScreenSoftBar;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.list.CompoundItem;
import javax.microedition.lcdui.list.CompoundListAdapter;
import javax.microedition.util.ContextHolder;

public class List extends Screen implements Choice {
	public static final Command SELECT_COMMAND = new Command("", Command.SCREEN, 0);

	private final CompoundListAdapter adapter;
	private final int listType;
	private final ArrayList<CompoundItem> items = new ArrayList<>();

	private ListView view;
	private int fitPolicy;
	private volatile Command selectCommand = SELECT_COMMAND;

	private final SimpleEvent msgSetSelection = new SimpleEvent() {
		@Override
		public void process() {
			AdapterView<?> v = view;
			if (v != null) {
				v.setSelection(getSelectedIndex());
			}
		}
	};

	public List(String title, int listType) {
		if (listType < EXCLUSIVE || listType > IMPLICIT) {
			throw new IllegalArgumentException("list type " + listType + " is not supported");
		}
		this.listType = listType;
		adapter = new CompoundListAdapter(listType, items);
		setTitle(title);
	}

	public List(String title, int listType, String[] stringElements, Image[] imageElements) {
		this(title, listType);
		if (stringElements == null) {
			throw new NullPointerException("String elements array is NULL");
		} else if (imageElements != null && imageElements.length != stringElements.length) {
			throw new IllegalArgumentException("String and image arrays have different length");
		}

		for (int i = 0; i < stringElements.length; i++) {
			String stringPart = stringElements[i];
			if (stringPart == null) {
				throw new NullPointerException("stringElements contains NULL value");
			}
			Image imagePart = imageElements != null ? imageElements[i] : null;
			items.add(new CompoundItem(stringPart, imagePart));
		}

		if (listType != MULTIPLE && stringElements.length > 0) {
			items.get(0).setSelected(true);
		}
	}

	public void setSelectCommand(Command cmd) {
		if (listType != IMPLICIT) {
			return;
		}
		// Command membership and the role of selection command are distinct.
		// In particular, replacing a selection command does not remove the
		// previous ordinary command from this List.
		if (cmd != null && cmd != SELECT_COMMAND) {
			addCommand(cmd);
		}
		selectCommand = cmd;
	}

	@Override
	public void removeCommand(Command cmd) {
		if (listType == IMPLICIT && selectCommand == cmd) {
			selectCommand = null;
		}
		super.removeCommand(cmd);
	}

	@Override
	public int append(String stringPart, Image imagePart) {
		if (stringPart == null) {
			throw new NullPointerException("stringPart is NULL");
		}

		int index;
		synchronized (items) {
			index = items.size();
			items.add(new CompoundItem(stringPart, imagePart, index == 0 && listType != MULTIPLE));
			adapter.notifyDataSetChanged();
		}
		if (index == 0 && listType == IMPLICIT && view != null) {
			ViewHandler.postEvent(msgSetSelection);
		}

		return index;
	}

	@Override
	public void delete(int elementNum) {
		int size;
		synchronized (items) {
			size = items.size();
			if (elementNum < 0 || elementNum >= size) {
				throw new IndexOutOfBoundsException("elementNum = " + elementNum + ", but size = " + size);
			}
			boolean wasSelected = items.remove(elementNum).isSelected();
			if (wasSelected && listType != MULTIPLE && !items.isEmpty()) {
				// Preserve the same element when possible; select the nearest
				// remaining neighbor only when the selected element was deleted.
				items.get(Math.min(elementNum, items.size() - 1)).setSelected(true);
			}
			adapter.notifyDataSetChanged();
		}
		if (listType == IMPLICIT && view != null) {
			ViewHandler.postEvent(msgSetSelection);
		}
	}

	@Override
	public void deleteAll() {
		synchronized (items) {
			items.clear();
			adapter.notifyDataSetChanged();
		}
		if (listType == IMPLICIT && view != null) {
			ViewHandler.postEvent(msgSetSelection);
		}
	}

	@Override
	public Image getImage(int elementNum) {
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				String msg = "elementNum = " + elementNum + ", but size = " + items.size();
				throw new IndexOutOfBoundsException(msg);
			}
			return items.get(elementNum).getImage();
		}
	}

	@Override
	public int getSelectedFlags(boolean[] selectedArray) {
		int index;
		int selectedCount;
		synchronized (items) {
			int size = items.size();
			if (selectedArray.length < size) {
				throw new IllegalArgumentException("return array is too short");
			}

			index = 0;
			selectedCount = 0;

			while (index < size) {
				boolean flag = items.get(index).isSelected();
				selectedArray[index++] = flag;
				if (flag) {
					selectedCount++;
				}
			}
		}

		while (index < selectedArray.length) {
			selectedArray[index++] = false;
		}

		return selectedCount;
	}

	@Override
	public int getSelectedIndex() {
		if (listType == MULTIPLE) {
			return -1;
		}
		synchronized (items) {
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i).isSelected()) {
					return i;
				}
			}
		}
		return -1;
	}

	@Override
	public String getString(int elementNum) {
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				String msg = "elementNum = " + elementNum + ", but size = " + items.size();
				throw new IndexOutOfBoundsException(msg);
			}
			return items.get(elementNum).getString();
		}
	}

	@Override
	public void insert(int elementNum, String stringPart, Image imagePart) {
		if (stringPart == null) {
			throw new NullPointerException("stringPart is NULL");
		}
		int size;
		synchronized (items) {
			size = items.size();
			if (elementNum < 0 || elementNum > size) {
				throw new IndexOutOfBoundsException("elementNum = " + elementNum + ", but size = " + size);
			}

			boolean select = size == 0 && listType != MULTIPLE;
			items.add(elementNum, new CompoundItem(stringPart, imagePart, select));
			adapter.notifyDataSetChanged();
		}
		if (listType == IMPLICIT && view != null) {
			ViewHandler.postEvent(msgSetSelection);
		}
	}

	@Override
	public boolean isSelected(int elementNum) {
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				throw new IndexOutOfBoundsException("elementNum = " + elementNum + ", but size = " + items.size());
			}
			return items.get(elementNum).isSelected();
		}
	}

	@Override
	public void set(int elementNum, String stringPart, Image imagePart) {
		if (stringPart == null) {
			throw new NullPointerException("stringPart is NULL");
		}
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				String msg = "elementNum = " + elementNum + ", but size = " + items.size();
				throw new IndexOutOfBoundsException(msg);
			}

			items.get(elementNum).set(stringPart, imagePart);
			adapter.notifyDataSetChanged();
		}
	}

	@Override
	public void setSelectedFlags(boolean[] selectedArray) {
		if (selectedArray == null) {
			throw new NullPointerException();
		}

		synchronized (items) {
			int size = items.size();
			if (selectedArray.length < size) {
				throw new IllegalArgumentException("Array is too short");
			}

			if (listType == MULTIPLE) {
				for (int i = 0; i < size; i++) {
					items.get(i).setSelected(selectedArray[i]);
				}
				adapter.notifyDataSetChanged();
				return;
			}

			if (size == 0) {
				return;
			}
			int selectedIndex = 0;
			for (int i = 0; i < size; i++) {
				if (selectedArray[i]) {
					selectedIndex = i;
					break;
				}
			}
			setSelectedIndex(selectedIndex, true);
		}
	}

	@Override
	public void setSelectedIndex(int elementNum, boolean selected) {
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				String msg = "elementNum = " + elementNum + ", but size = " + items.size();
				throw new IndexOutOfBoundsException(msg);
			}
			if (listType != MULTIPLE) {
				if (!selected) {
					return;
				}
				for (CompoundItem item : items) {
					item.setSelected(false);
				}
			}
			items.get(elementNum).setSelected(selected);
			adapter.notifyDataSetChanged();
		}
		if (listType == IMPLICIT && view != null) {
			ViewHandler.postEvent(msgSetSelection);
		}
	}

	@Override
	public void setFont(int elementNum, Font font) {
		synchronized (items) {
			if (elementNum < 0 || elementNum >= items.size()) {
				String msg = "elementNum = " + elementNum + ", but size = " + items.size();
				throw new IndexOutOfBoundsException(msg);
			}
			items.get(elementNum).setFont(font);
			adapter.notifyDataSetChanged();
		}
	}

	@Override
	public Font getFont(int elementNum) {
		synchronized (items) {
			return items.get(elementNum).getFont();
		}
	}

	@Override
	public void setFitPolicy(int fitPolicy) {
		if (fitPolicy < TEXT_WRAP_DEFAULT || fitPolicy > TEXT_WRAP_OFF) {
			throw new IllegalArgumentException("Invalid fit policy: " + fitPolicy);
		}
		this.fitPolicy = fitPolicy;
	}

	@Override
	public int getFitPolicy() {
		return fitPolicy;
	}

	@Override
	public int size() {
		synchronized (items) {
			return items.size();
		}
	}

	@Override
	View getScreenView() {
		if (view == null) {
			Context context = ContextHolder.getActivity();
			if (context == null) {
				context = ContextHolder.getAppContext();
			}
			view = new ListView(context);
			view.setAdapter(adapter);

			if (listType == IMPLICIT) {
				msgSetSelection.run();
				view.setOnItemLongClickListener(this::onItemLongClick);
				view.setOnItemSelectedListener(new ItemSelectedListener());
			}

			view.setOnItemClickListener(this::onItemClick);
		}

		return view;
	}

	@Override
	void clearScreenView() {
		view = null;
	}

	void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		synchronized (items) {
			if (position < 0 || position >= items.size()) {
				return;
			}
			CompoundItem item = items.get(position);
			if (listType == MULTIPLE) {
				item.setSelected(!item.isSelected());
				adapter.notifyDataSetChanged();
				return;
			}
			if (!item.isSelected()) {
				for (CompoundItem it : items) {
					it.setSelected(false);
				}
				item.setSelected(true);
				adapter.notifyDataSetChanged();
			}
		}
		if (listType == IMPLICIT) {
			Command command = selectCommand;
			if (command != null) {
				fireCommandAction(command);
			}
		}
	}

	boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
		synchronized (items) {
			CompoundItem item = items.get(position);
			if (!item.isSelected()) {
				for (CompoundItem it : items) {
					it.setSelected(false);
				}
				item.setSelected(true);
				adapter.notifyDataSetChanged();
			}
		}
		if (listener != null && commands.size() > 3) {
			for (int i = 2; i < commands.size(); i++) {
				if (commands.get(i).getCommandType() == Command.ITEM) {
					((ScreenSoftBar) softBar).showMenu();
					break;
				}
			}
		}
		return true;
	}

	private class ItemSelectedListener implements AdapterView.OnItemSelectedListener {
		@Override
		public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
			synchronized (items) {
				CompoundItem item = items.get(position);
				if (!item.isSelected()) {
					for (CompoundItem it : items) {
						it.setSelected(false);
					}
					item.setSelected(true);
					adapter.notifyDataSetChanged();
				}
			}
		}

		@Override
		public void onNothingSelected(AdapterView<?> parent) {}
	}
}
