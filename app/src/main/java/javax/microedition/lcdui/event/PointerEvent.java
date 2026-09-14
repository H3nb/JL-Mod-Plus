/*
 * Copyright 2024 Yury Kharchenko
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

package javax.microedition.lcdui.event;

import static javax.microedition.lcdui.event.CanvasEvent.POINTER_DRAGGED;
import static javax.microedition.lcdui.event.CanvasEvent.POINTER_PRESSED;
import static javax.microedition.lcdui.event.CanvasEvent.POINTER_RELEASED;

import android.graphics.Point;
import android.util.SparseArray;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;

public class PointerEvent {
	private static final Object LOCK = new Object();
	private static final SparseArray<PointerState> POINTERS = new SparseArray<>();

	private PointerEvent() {}

	public static void sendPressed(Canvas canvas, int pointer, int x, int y) {
		EventPair replacement = null;
		boolean duplicate = false;
		synchronized (LOCK) {
			PointerState previous = POINTERS.get(pointer);
			if (previous != null && previous.active && previous.canvas == canvas) {
				// Android can repeat a down while a modal/overlay is handing a pointer back. A
				// second guest press would violate the one-contact contract, so keep the original
				// coordinates and let the matching release close it.
				duplicate = true;
			} else if (previous != null && previous.active && previous.canvas != canvas) {
				replacement = new EventPair(
						CanvasEvent.getInstance(previous.canvas, POINTER_RELEASED, pointer,
								previous.point.x, previous.point.y),
						CanvasEvent.getInstance(canvas, POINTER_PRESSED, pointer, x, y));
			} else {
				replacement = new EventPair(null,
						CanvasEvent.getInstance(canvas, POINTER_PRESSED, pointer, x, y));
			}
			if (previous == null) {
				previous = new PointerState();
				POINTERS.put(pointer, previous);
			}
			previous.canvas = canvas;
			previous.point.set(x, y);
			previous.active = true;
		}
		if (!duplicate) {
			post(replacement);
		}
	}

	public static void sendDragged(Canvas canvas, int pointer, int x, int y) {
		Point point;
		boolean active;
		synchronized (LOCK) {
			PointerState state = POINTERS.get(pointer);
			if (state == null || !state.active || state.canvas != canvas) {
				active = false;
				point = null;
			} else {
				active = true;
				point = new Point(state.point);
			}
		}
		if (!active) {
			if (x >= 0 && x < canvas.getWidth() && y >= 0 && y < canvas.getHeight()) {
				sendPressed(canvas, pointer, x, y);
			}
			return;
		}
		if (point.equals(x, y)) {
			return;
		}
		int width = canvas.getWidth();
		int height = canvas.getHeight();
		boolean inArea = x >= 0 && x < width && y >= 0 && y < height;
		if (point.x >= 0 && point.x < width && point.y >= 0 && point.y < height) {
			if (inArea) {
				Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_DRAGGED, pointer, x, y));
				synchronized (LOCK) {
					PointerState state = POINTERS.get(pointer);
					if (state != null && state.active && state.canvas == canvas) {
						state.point.set(x, y);
					}
				}
			} else {
				if (x < 0) {
					x = 0;
				} else if (x >= width) {
					x = width - 1;
				}
				if (y < 0) {
					y = 0;
				} else if (y >= height) {
					y = height - 1;
				}
				sendReleased(canvas, pointer, x, y);
			}
		} else if (inArea) {
			// Preserve the existing re-entry behavior: a contact that crossed the guest
			// boundary without producing a release becomes a new press on re-entry.
			sendPressed(canvas, pointer, x, y);
		}
	}

	public static void sendReleased(Canvas canvas, int pointer, int x, int y) {
		boolean shouldPost;
		synchronized (LOCK) {
			PointerState state = POINTERS.get(pointer);
			shouldPost = state != null && state.active && state.canvas == canvas;
			if (shouldPost) {
				state.active = false;
				state.point.set(-1, -1);
			}
		}
		if (shouldPost) {
			Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_RELEASED, pointer, x, y));
		}
	}

	/**
	 * Returns whether this Canvas currently owns any guest pointer contact. The controller adapter
	 * uses this when it attaches after a physical touch has already begun, before its lease has seen
	 * the contact. This preserves the MIDP single-pointer channel without inventing a guest ID.
	 */
	public static boolean hasActivePointer(Canvas canvas) {
		synchronized (LOCK) {
			for (int index = 0; index < POINTERS.size(); index++) {
				PointerState state = POINTERS.valueAt(index);
				if (state.active && state.canvas == canvas) {
					return true;
				}
			}
			return false;
		}
	}

	/** Cancels every active pointer owned by this Canvas at a target/lifecycle boundary. */
	public static void cancel(Canvas canvas) {
		SparseArray<Point> releases = new SparseArray<>();
		synchronized (LOCK) {
			for (int index = POINTERS.size() - 1; index >= 0; index--) {
				int pointer = POINTERS.keyAt(index);
				PointerState state = POINTERS.valueAt(index);
				if (state.active && state.canvas == canvas) {
					releases.put(pointer, new Point(state.point));
					state.active = false;
					state.point.set(-1, -1);
				}
			}
		}
		for (int index = 0; index < releases.size(); index++) {
			int pointer = releases.keyAt(index);
			Point point = releases.valueAt(index);
			Display.postEvent(CanvasEvent.getInstance(canvas, POINTER_RELEASED, pointer,
					point.x, point.y));
		}
	}

	private static void post(EventPair pair) {
		if (pair.first != null) Display.postEvent(pair.first);
		if (pair.second != null) Display.postEvent(pair.second);
	}

	private static final class PointerState {
		Canvas canvas;
		final Point point = new Point(-1, -1);
		boolean active;
	}

	private static final class EventPair {
		final Event first;
		final Event second;

		EventPair(Event first, Event second) {
			this.first = first;
			this.second = second;
		}
	}
}
