/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2020-2026 Yury Kharchenko
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

package javax.microedition.lcdui;

import android.view.MenuItem;
import android.view.View;

import java.util.ArrayList;

import javax.microedition.lcdui.event.SimpleEvent;

public class Form extends Screen {
	private final ArrayList<Item> items = new ArrayList<>();
	private ItemStateListener listener;

	private J2meFormComposeView view;

	public Form(String title) {
		setTitle(title);
	}

	public Form(String title, Item[] elements) {
		setTitle(title);
		// null array is correct: create empty Form
		if (elements == null) {
			return;
		}
		for (int i = 0, elementsLength = elements.length; i < elementsLength; i++) {
			Item item = elements[i];
			if (item == null) {
				throw new NullPointerException("Item at index " + i + " is null");
			}
			if (item.hasOwner()) {
				throw new IllegalStateException("Item at index " + i + " is already owned by another container");
			}
		}
		for (Item item : elements) {
			items.add(item);
			item.setOwner(this);
		}
	}

	public Item get(int index) {
		return items.get(index);
	}

	public int size() {
		return items.size();
	}

	public int append(String text) {
		return append(new StringItem(null, text));
	}

	public int append(Image img) {
		return append(new ImageItem(null, img, ImageItem.LAYOUT_DEFAULT, null));
	}

	public int append(Item item) {
		if (item.hasOwner()) {
			throw new IllegalStateException();
		}

		items.add(item);
		item.setOwner(this);
		notifyFormView();
		return items.size() - 1;
	}

	public void insert(int index, Item item) {
		if (item.hasOwner()) {
			throw new IllegalStateException();
		}

		items.add(index, item);
		item.setOwner(this);
		notifyFormView();
	}

	public void set(int index, Item item) {
		if (item.hasOwner()) {
			throw new IllegalStateException();
		}

		items.set(index, item).setOwner(null);
		item.setOwner(this);
		notifyFormView();
	}

	public void delete(int index) {
		items.remove(index).setOwner(null);
		notifyFormView();
	}

	public void deleteAll() {
		for (Item item : items) {
			item.setOwner(null);
		}

		items.clear();
		notifyFormView();
	}

	public void setItemStateListener(ItemStateListener listener) {
		this.listener = listener;
	}

	void notifyItemStateChanged(Item item) {
		Display.postEvent(new SimpleEvent() {
			@Override
			public void process() {
				ItemStateListener l = listener;
				if (l != null) {
					l.itemStateChanged(item);
				}
			}
		});
	}

	@Override
	View getScreenView() {
		if (view == null) {
			view = new J2meFormComposeView(javax.microedition.util.ContextHolder.getActivity());
			setFormItems(view);
		}

		return view;
	}

	@Override
	void clearScreenView() {
		view = null;

		Item[] array = items.toArray(new Item[0]);
		for (Item item : array) {
			item.clearItemView();
		}
	}

	private void notifyFormView() {
		J2meFormComposeView currentView = view;
		if (currentView == null) {
			return;
		}
		ViewHandler.postEvent(() -> setFormItems(currentView));
	}

	private void setFormItems(J2meFormComposeView currentView) {
		if (view != currentView) {
			return;
		}
		currentView.setItems(new ArrayList<>(items));
	}

	public void contextMenuItemSelected(MenuItem menuitem) {
		for (Item item : items) {
			if (menuitem.getGroupId() == item.hashCode() && item.contextMenuItemSelected(menuitem)) {
				return;
			}
		}
	}
}
