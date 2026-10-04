/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
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

import android.util.Log;

import javax.microedition.lcdui.Canvas;
import javax.microedition.shell.timing.PerformanceDiagnostics;
import javax.microedition.util.ArrayStack;

public class CanvasEvent extends Event {
	private static final String TAG = "CanvasEvent";

	public static final int KEY_PRESSED = 0;
	public static final int KEY_REPEATED = 1;
	public static final int KEY_RELEASED = 2;
	public static final int POINTER_PRESSED = 3;
	public static final int POINTER_DRAGGED = 4;
	public static final int POINTER_RELEASED = 5;
	public static final int SHOW_NOTIFY = 6;
	public static final int HIDE_NOTIFY = 7;
	public static final int SIZE_CHANGED = 8;

	private static final ArrayStack<CanvasEvent> recycled = new ArrayStack<>();

	private Canvas canvas;
	private int eventType;

	private int keyCode;

	private int pointer, x, y;

	private int width, height;
	private long queuedAtNanos;
	private long queuedGeneration;

	private CanvasEvent() {}

	public static Event getInstance(Canvas canvas, int eventType) {
		return obtain(canvas, eventType);
	}

	public static Event getInstance(Canvas canvas, int eventType, int keyCode) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.keyCode = keyCode;
		return instance;
	}

	public static Event getInstance(Canvas canvas, int eventType, int pointer, int x, int y) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.pointer = pointer;
		instance.x = x;
		instance.y = y;
		return instance;
	}

	public static Event getInstance(Canvas canvas, int eventType, int width, int height) {
		CanvasEvent instance = obtain(canvas, eventType);
		instance.width = width;
		instance.height = height;
		return instance;
	}

	private static CanvasEvent obtain(Canvas canvas, int eventType) {
		CanvasEvent instance = recycled.pop();
		if (instance == null) {
			instance = new CanvasEvent();
		}
		instance.canvas = canvas;
		instance.eventType = eventType;
		instance.queuedAtNanos = 0L;
		instance.queuedGeneration = 0L;
		instance.asGuestCallback();
		return instance;
	}

	@Override
	public void process() {
		if (queuedAtNanos != 0L && queuedGeneration == canvas.getPerformanceGeneration()) {
			PerformanceDiagnostics diagnostics = canvas.getPerformanceDiagnostics();
			if (diagnostics != null && diagnostics.enabled(PerformanceDiagnostics.INPUT_QUEUE)) {
				diagnostics.recordDuration(PerformanceDiagnostics.INPUT_QUEUE,
						queuedAtNanos, System.nanoTime());
			}
		}
		queuedAtNanos = 0L;
		switch (eventType) {
			case KEY_PRESSED -> {
				try {
					canvas.doKeyPressed(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyPressed: ", e);
				}
			}
			case KEY_REPEATED -> {
				try {
					canvas.doKeyRepeated(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyRepeated: ", e);
				}
			}
			case KEY_RELEASED -> {
				try {
					canvas.doKeyReleased(keyCode);
				} catch (Exception e) {
					Log.e(TAG, "keyReleased: ", e);
				}
			}
			case POINTER_PRESSED -> {
				try {
					canvas.pointerPressed(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerPressed: ", e);
				}
			}
			case POINTER_DRAGGED -> {
				try {
					canvas.pointerDragged(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerDragged: ", e);
				}
			}
			case POINTER_RELEASED -> {
				try {
					canvas.pointerReleased(pointer, x, y);
				} catch (Exception e) {
					Log.e(TAG, "pointerReleased: ", e);
				}
			}
			case SHOW_NOTIFY -> {
				try {
					canvas.doShowNotify();
				} catch (Exception e) {
					Log.e(TAG, "showNotify: ", e);
				}
			}
			case HIDE_NOTIFY -> {
				try {
					canvas.doHideNotify();
				} catch (Exception e) {
					Log.e(TAG, "hideNotify: ", e);
				}
			}
			case SIZE_CHANGED -> {
				try {
					canvas.doSizeChanged(width, height);
				} catch (Exception e) {
					Log.e(TAG, "sizeChanged: ", e);
				}
			}
		}
	}

	@Override
	protected void recycleEvent() {
		canvas = null;
		queuedAtNanos = 0L;
		queuedGeneration = 0L;
		recycled.push(this);
	}

	@Override
	public void enterQueue() {
		if (eventType >= KEY_PRESSED && eventType <= POINTER_RELEASED) {
			PerformanceDiagnostics diagnostics = canvas.getPerformanceDiagnostics();
			if (diagnostics != null && diagnostics.enabled(PerformanceDiagnostics.INPUT_QUEUE)) {
				queuedGeneration = canvas.getPerformanceGeneration();
				queuedAtNanos = System.nanoTime();
			}
		}
	}

	@Override
	public void leaveQueue() {
	}

	@Override
	public boolean placeableAfter(Event event) {
		return true;
	}
}
