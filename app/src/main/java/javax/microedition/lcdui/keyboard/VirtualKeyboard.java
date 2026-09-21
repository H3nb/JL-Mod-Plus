/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2021 Nikita Shakarun
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
package javax.microedition.lcdui.keyboard;

import static javax.microedition.lcdui.keyboard.KeyMapper.SE_KEY_SPECIAL_GAMING_A;
import static javax.microedition.lcdui.keyboard.KeyMapper.SE_KEY_SPECIAL_GAMING_B;

import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.lcdui.overlay.Overlay;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.input.HostCommand;

public class VirtualKeyboard implements Overlay, Runnable {
	public interface LayoutEditObserver {
		void onLayoutEditStateChanged();
	}

	private static final String TAG = VirtualKeyboard.class.getSimpleName();

	private static final String ARROW_LEFT = "←";
	private static final String ARROW_UP = "↑";
	private static final String ARROW_RIGHT = "→";
	private static final String ARROW_DOWN = "↓";
	private static final String ARROW_UP_LEFT = "↖";
	private static final String ARROW_UP_RIGHT = "↗";
	private static final String ARROW_DOWN_LEFT = "↙";
	private static final String ARROW_DOWN_RIGHT = "↘";

	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_VERSION = 3;
	private static final int MAX_LAYOUT_BLOCKS = 1024;
	private static final int KEY_RECORD_SIZE_V1 = 20;
	private static final int KEY_RECORD_SIZE_V2 = 21;
	public static final int LAYOUT_EOF = -1;
	public static final int LAYOUT_KEYS = 0;
	public static final int LAYOUT_SCALES = 1;
	@SuppressWarnings("unused")
	public static final int LAYOUT_COLORS = 2;
	public static final int LAYOUT_TYPE = 3;

	private static final int SHAPE_OVAL = 0;
	private static final int SHAPE_RECT = 1;
	public static final int SHAPE_ROUND_RECT = 2;

	public static final int TYPE_CUSTOM = 0;
	private static final int TYPE_PHONE = 1;
	private static final int TYPE_PHONE_ARROWS = 2;
	private static final int TYPE_NUM_ARR = 3;
	private static final int TYPE_ARR_NUM = 4;
	private static final int TYPE_NUMBERS = 5;
	private static final int TYPE_ARROWS = 6;

	private static final float PHONE_KEY_ROWS = 5;
	private static final float PHONE_KEY_SCALE_X = 2.0f;
	private static final float PHONE_KEY_SCALE_Y = 0.75f;

	private static final int SCREEN = -1;
	private static final int KEY_NUM1 = 0;
	private static final int KEY_NUM2 = 1;
	private static final int KEY_NUM3 = 2;
	private static final int KEY_NUM4 = 3;
	private static final int KEY_NUM5 = 4;
	private static final int KEY_NUM6 = 5;
	private static final int KEY_NUM7 = 6;
	private static final int KEY_NUM8 = 7;
	private static final int KEY_NUM9 = 8;
	private static final int KEY_NUM0 = 9;
	private static final int KEY_STAR = 10;
	private static final int KEY_POUND = 11;
	private static final int KEY_SOFT_LEFT = 12;
	private static final int KEY_SOFT_RIGHT = 13;
	private static final int KEY_D = 14;
	private static final int KEY_C = 15;
	private static final int KEY_UP_LEFT = 16;
	private static final int KEY_UP = 17;
	private static final int KEY_UP_RIGHT = 18;
	private static final int KEY_LEFT = 19;
	private static final int KEY_RIGHT = 20;
	private static final int KEY_DOWN_LEFT = 21;
	private static final int KEY_DOWN = 22;
	private static final int KEY_DOWN_RIGHT = 23;
	private static final int KEY_FIRE = 24;
	private static final int KEY_A = 25;
	private static final int KEY_B = 26;
	private static final int KEY_MENU = 27;
	private static final int KEYBOARD_SIZE = 28;
	private static final int[] CONTROLLER_KEYPAD_ORDER = {
			KEY_NUM1, KEY_NUM2, KEY_NUM3,
			KEY_NUM4, KEY_NUM5, KEY_NUM6,
			KEY_NUM7, KEY_NUM8, KEY_NUM9,
			KEY_NUM0, KEY_STAR, KEY_POUND,
			KEY_SOFT_LEFT, KEY_SOFT_RIGHT,
	};

	private static final float SCALE_SNAP_RADIUS = 0.05f;

	private static final int FEEDBACK_DURATION = 50;

	private final float[] keyScales = {
			1, 1,
			1, 1,
			1, 1,
			1, 1,
			1, 1,
			1, 1,
	};
	private final int[][] keyScaleGroups = {{
			KEY_UP_LEFT,
			KEY_UP,
			KEY_UP_RIGHT,
			KEY_LEFT,
			KEY_RIGHT,
			KEY_DOWN_LEFT,
			KEY_DOWN,
			KEY_DOWN_RIGHT
	}, {
			KEY_SOFT_LEFT,
			KEY_SOFT_RIGHT
	}, {
			KEY_A,
			KEY_B,
			KEY_C,
			KEY_D,
	}, {
			KEY_NUM1,
			KEY_NUM2,
			KEY_NUM3,
			KEY_NUM4,
			KEY_NUM5,
			KEY_NUM6,
			KEY_NUM7,
			KEY_NUM8,
			KEY_NUM9,
			KEY_NUM0,
			KEY_STAR,
			KEY_POUND
	}, {
			KEY_FIRE
	}, {
			KEY_MENU
	}};
	private final VirtualKey[] keypad = new VirtualKey[KEYBOARD_SIZE];
	// the average user usually has no more than 10 fingers...
	private final VirtualKey[] associatedKeys = new VirtualKey[10];
	/** Source identity is per contact, not just the reusable Android pointer id. */
	private final String[] associatedSources = new String[10];
	private final int[] snapStack = new int[KEYBOARD_SIZE];

	private final Handler handler;
	private final File saveFile;
	private final ProfileModel settings;
	private final RectF virtualScreen = new RectF();

	private Canvas target;
	private View overlayView;
	private boolean obscuresVirtualScreen;
	private boolean visible = true;
	private int layoutEditMode = LAYOUT_EOF;
	private int editedIndex;
	private float offsetX;
	private float offsetY;
	private float prevScaleX;
	private float prevScaleY;
	private RectF screen;
	private float keySize =
			Math.min(ContextHolder.getDisplayWidth(), ContextHolder.getDisplayHeight()) / 6.0f;
	private float snapRadius;
	private int layoutVariant;
	private boolean controllerKeypadVisible;
	private int controllerKeypadSelection;
	private VirtualKey controllerKeypadPressed;
	private long pointerSourceSequence;
	private LayoutEditObserver layoutEditObserver;

	public VirtualKeyboard(ProfileModel settings) {
		this.settings = settings;
		this.saveFile = new File(settings.dir + Config.MIDLET_KEY_LAYOUT_FILE);

		for (int i = KEY_NUM1; i < 9; i++) {
			keypad[i] = new VirtualKey(Canvas.KEY_NUM1 + i, Integer.toString(1 + i));
		}

		keypad[KEY_NUM0] = new VirtualKey(Canvas.KEY_NUM0, "0");
		keypad[KEY_STAR] = new VirtualKey(Canvas.KEY_STAR, "*");
		keypad[KEY_POUND] = new VirtualKey(Canvas.KEY_POUND, "#");

		keypad[KEY_SOFT_LEFT] = new VirtualKey(Canvas.KEY_SOFT_LEFT, "L");
		keypad[KEY_SOFT_RIGHT] = new VirtualKey(Canvas.KEY_SOFT_RIGHT, "R");

		keypad[KEY_A] = new VirtualKey(SE_KEY_SPECIAL_GAMING_A, "A");
		keypad[KEY_B] = new VirtualKey(SE_KEY_SPECIAL_GAMING_B, "B");
		keypad[KEY_C] = new VirtualKey(Canvas.KEY_END, "C");
		keypad[KEY_D] = new VirtualKey(Canvas.KEY_SEND, "D");

		keypad[KEY_UP_LEFT] = new DualKey(Canvas.KEY_UP, Canvas.KEY_LEFT, ARROW_UP_LEFT);
		keypad[KEY_UP] = new VirtualKey(Canvas.KEY_UP, ARROW_UP);
		keypad[KEY_UP_RIGHT] = new DualKey(Canvas.KEY_UP, Canvas.KEY_RIGHT, ARROW_UP_RIGHT);

		keypad[KEY_LEFT] = new VirtualKey(Canvas.KEY_LEFT, ARROW_LEFT);
		keypad[KEY_RIGHT] = new VirtualKey(Canvas.KEY_RIGHT, ARROW_RIGHT);

		keypad[KEY_DOWN_LEFT] = new DualKey(Canvas.KEY_DOWN, Canvas.KEY_LEFT, ARROW_DOWN_LEFT);
		keypad[KEY_DOWN] = new VirtualKey(Canvas.KEY_DOWN, ARROW_DOWN);
		keypad[KEY_DOWN_RIGHT] = new DualKey(Canvas.KEY_DOWN, Canvas.KEY_RIGHT, ARROW_DOWN_RIGHT);

		keypad[KEY_FIRE] = new VirtualKey(Canvas.KEY_FIRE, "F");
		keypad[KEY_MENU] = new MenuKey();

		layoutVariant = readLayoutType();

		if (layoutVariant == -1) {
			layoutVariant = settings.vkType;
			if (layoutVariant == TYPE_CUSTOM) {
				layoutVariant = TYPE_NUM_ARR;
			}
		}
		resetLayout(layoutVariant);
		if (layoutVariant == TYPE_CUSTOM) {
			try {
				readLayout();
			} catch (IOException e) {
				e.printStackTrace();
				resetLayout(TYPE_NUM_ARR);
				layoutVariant = TYPE_NUM_ARR;
				saveLayout();
			}
		}
		HandlerThread thread = new HandlerThread("MidletVirtualKeyboard");
		thread.start();
		handler = new Handler(thread.getLooper());
	}

	public void onLayoutChanged(int variant) {
		if (variant == TYPE_CUSTOM && isPhone()) {
			float min = screen.width();
			float max = screen.height();
			if (min > max) {
				float tmp = max;
				max = min;
				min = tmp;
			}

			float oldSize = min / 6.0f;
			float newSize = Math.min(oldSize, max / 12.0f);
			float s = oldSize / newSize;
			for (int i = 0; i < keyScales.length; i++) {
				keyScales[i] *= s;
			}
		}
		layoutVariant = variant;
		saveLayout();
		if (target != null && target.isShown()) {
			target.updateSize();
		}
	}

	private void resetLayout(int variant) {
		switch (variant) {
			case TYPE_PHONE -> {
				for (int j = 0, len = keyScales.length; j < len; ) {
					keyScales[j++] = PHONE_KEY_SCALE_X;
					keyScales[j++] = PHONE_KEY_SCALE_Y;
				}

				setSnap(KEY_NUM0, SCREEN, RectSnap.INT_SOUTH, true);
				setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST, true);
				setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST, true);
				setSnap(KEY_SOFT_LEFT, KEY_NUM1, RectSnap.EXT_NORTH, true);
				setSnap(KEY_FIRE, KEY_NUM2, RectSnap.EXT_NORTH, true);
				setSnap(KEY_SOFT_RIGHT, KEY_NUM3, RectSnap.EXT_NORTH, true);

				setSnap(KEY_A, SCREEN, RectSnap.INT_NORTHWEST, false);
				setSnap(KEY_B, SCREEN, RectSnap.INT_NORTHEAST, false);
				setSnap(KEY_C, KEY_A, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_D, KEY_B, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_UP_LEFT, KEY_C, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_UP, KEY_C, RectSnap.EXT_SOUTHEAST, false);
				setSnap(KEY_UP_RIGHT, KEY_D, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_LEFT, KEY_UP_LEFT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_MENU, KEY_UP, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_LEFT, KEY_LEFT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN, KEY_MENU, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_RIGHT, KEY_RIGHT, RectSnap.EXT_SOUTH, false);
			}
			case TYPE_PHONE_ARROWS -> {
				for (int j = 0, len = keyScales.length; j < len; ) {
					keyScales[j++] = PHONE_KEY_SCALE_X;
					keyScales[j++] = PHONE_KEY_SCALE_Y;
				}

				setSnap(KEY_NUM0, SCREEN, RectSnap.INT_SOUTH, true);
				setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST, true);
				setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH, true);
				setSnap(KEY_DOWN, KEY_NUM7, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM9, KEY_DOWN, RectSnap.EXT_EAST, true);
				setSnap(KEY_LEFT, KEY_NUM7, RectSnap.EXT_NORTH, true);
				setSnap(KEY_FIRE, KEY_LEFT, RectSnap.EXT_EAST, true);
				setSnap(KEY_RIGHT, KEY_FIRE, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM1, KEY_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP, KEY_NUM1, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM3, KEY_UP, RectSnap.EXT_EAST, true);
				setSnap(KEY_SOFT_LEFT, KEY_NUM1, RectSnap.EXT_NORTH, true);
				setSnap(KEY_MENU, KEY_UP, RectSnap.EXT_NORTH, true);
				setSnap(KEY_SOFT_RIGHT, KEY_NUM3, RectSnap.EXT_NORTH, true);

				setSnap(KEY_A, SCREEN, RectSnap.INT_NORTHWEST, false);
				setSnap(KEY_B, SCREEN, RectSnap.INT_NORTHEAST, false);
				setSnap(KEY_C, KEY_A, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_D, KEY_B, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_UP_LEFT, KEY_C, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM2, KEY_C, RectSnap.EXT_SOUTHEAST, false);
				setSnap(KEY_UP_RIGHT, KEY_D, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM4, KEY_UP_LEFT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM5, KEY_NUM2, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM6, KEY_UP_RIGHT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_LEFT, KEY_NUM4, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_RIGHT, KEY_NUM6, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM8, KEY_NUM5, RectSnap.EXT_SOUTH, false);
			}
			// case TYPE_NUM_ARR,
			default -> {
				Arrays.fill(keyScales, 1.0f);

				setSnap(KEY_DOWN_RIGHT, SCREEN, RectSnap.INT_SOUTHEAST, true);
				setSnap(KEY_DOWN, KEY_DOWN_RIGHT, RectSnap.EXT_WEST, true);
				setSnap(KEY_DOWN_LEFT, KEY_DOWN, RectSnap.EXT_WEST, true);
				setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST, true);
				setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST, true);
				setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST, true);
				setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_STAR, SCREEN, RectSnap.INT_SOUTHWEST, true);
				setSnap(KEY_NUM0, KEY_STAR, RectSnap.EXT_EAST, true);
				setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST, true);

				setSnap(KEY_D, KEY_NUM1, RectSnap.EXT_NORTH, false);
				setSnap(KEY_C, KEY_NUM3, RectSnap.EXT_NORTH, false);
				setSnap(KEY_A, SCREEN, RectSnap.INT_NORTHWEST, false);
				setSnap(KEY_B, SCREEN, RectSnap.INT_NORTHEAST, false);
				setSnap(KEY_MENU, KEY_UP, RectSnap.EXT_NORTH, false);
			}
			case TYPE_ARR_NUM -> {
				Arrays.fill(keyScales, 1);

				setSnap(KEY_DOWN_LEFT, SCREEN, RectSnap.INT_SOUTHWEST, true);
				setSnap(KEY_DOWN, KEY_DOWN_LEFT, RectSnap.EXT_EAST, true);
				setSnap(KEY_DOWN_RIGHT, KEY_DOWN, RectSnap.EXT_EAST, true);
				setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST, true);
				setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST, true);
				setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST, true);
				setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_POUND, SCREEN, RectSnap.INT_SOUTHEAST, true);
				setSnap(KEY_NUM0, KEY_POUND, RectSnap.EXT_WEST, true);
				setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST, true);
				setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST, true);

				setSnap(KEY_D, KEY_NUM1, RectSnap.EXT_NORTH, false);
				setSnap(KEY_C, KEY_NUM3, RectSnap.EXT_NORTH, false);
				setSnap(KEY_A, SCREEN, RectSnap.INT_NORTHWEST, false);
				setSnap(KEY_B, SCREEN, RectSnap.INT_NORTHEAST, false);
				setSnap(KEY_MENU, KEY_UP, RectSnap.EXT_NORTH, false);
			}
			case TYPE_NUMBERS -> {
				Arrays.fill(keyScales, 1);

				setSnap(KEY_NUM0, SCREEN, RectSnap.INT_SOUTH, true);
				setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST, true);
				setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH, true);
				setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST, true);
				setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST, true);
				setSnap(KEY_SOFT_LEFT, KEY_NUM1, RectSnap.EXT_WEST, true);
				setSnap(KEY_SOFT_RIGHT, KEY_NUM3, RectSnap.EXT_EAST, true);

				setSnap(KEY_UP, SCREEN, RectSnap.INT_NORTH, false);
				setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST, false);
				setSnap(KEY_UP_RIGHT, KEY_UP, RectSnap.EXT_EAST, false);
				setSnap(KEY_FIRE, KEY_UP, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_LEFT, KEY_UP_LEFT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_LEFT, KEY_LEFT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN_RIGHT, KEY_RIGHT, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_DOWN, KEY_DOWN_LEFT, RectSnap.EXT_EAST, false);
				setSnap(KEY_A, KEY_NUM4, RectSnap.EXT_WEST, false);
				setSnap(KEY_B, KEY_NUM6, RectSnap.EXT_EAST, false);
				setSnap(KEY_C, KEY_NUM7, RectSnap.EXT_WEST, false);
				setSnap(KEY_D, KEY_NUM9, RectSnap.EXT_EAST, false);
				setSnap(KEY_MENU, SCREEN, RectSnap.INT_NORTHEAST, false);
			}
			case TYPE_ARROWS -> {
				Arrays.fill(keyScales, 1);

				setSnap(KEY_DOWN, SCREEN, RectSnap.INT_SOUTH, true);
				setSnap(KEY_DOWN_RIGHT, KEY_DOWN, RectSnap.EXT_EAST, true);
				setSnap(KEY_DOWN_LEFT, KEY_DOWN, RectSnap.EXT_WEST, true);
				setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH, true);
				setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST, true);
				setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST, true);
				setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST, true);
				setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_WEST, true);
				setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_EAST, true);

				setSnap(KEY_NUM1, KEY_NUM2, RectSnap.EXT_WEST, false);
				setSnap(KEY_NUM2, SCREEN, RectSnap.INT_NORTH, false);
				setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST, false);
				setSnap(KEY_NUM4, KEY_NUM1, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM5, KEY_NUM2, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM6, KEY_NUM3, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM7, KEY_NUM4, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM8, KEY_NUM5, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM9, KEY_NUM6, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_STAR, KEY_NUM7, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_NUM0, KEY_NUM8, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_POUND, KEY_NUM9, RectSnap.EXT_SOUTH, false);
				setSnap(KEY_A, KEY_LEFT, RectSnap.EXT_WEST, false);
				setSnap(KEY_B, KEY_RIGHT, RectSnap.EXT_EAST, false);
				setSnap(KEY_C, KEY_DOWN_LEFT, RectSnap.EXT_WEST, false);
				setSnap(KEY_D, KEY_DOWN_RIGHT, RectSnap.EXT_EAST, false);
				setSnap(KEY_MENU, SCREEN, RectSnap.INT_NORTHEAST, false);
			}
		}
	}

	public int getLayout() {
		return layoutVariant;
	}

	public void setLayoutEditObserver(LayoutEditObserver observer) {
		layoutEditObserver = observer;
	}

	protected final void notifyLayoutEditStateChanged() {
		LayoutEditObserver observer = layoutEditObserver;
		if (observer != null) observer.onLayoutEditStateChanged();
	}

	public boolean isLayoutManipulationActive() {
		return editedIndex >= 0;
	}

	public RectF getLayoutEditBounds() {
		return screen == null ? null : new RectF(screen);
	}

	public List<RectF> getVisibleLayoutControlBounds() {
		ArrayList<RectF> bounds = new ArrayList<>();
		for (VirtualKey key : keypad) {
			if (key.visible) bounds.add(new RectF(key.rect));
		}
		return bounds;
	}

	public VirtualKeyboardLayoutSnapshot captureLayoutSnapshot() {
		boolean[] visible = new boolean[keypad.length];
		int[] snapOrigins = new int[keypad.length];
		int[] snapModes = new int[keypad.length];
		float[] snapOffsetX = new float[keypad.length];
		float[] snapOffsetY = new float[keypad.length];
		for (int i = 0; i < keypad.length; i++) {
			VirtualKey key = keypad[i];
			visible[i] = key.visible;
			snapOrigins[i] = key.snapOrigin;
			snapModes[i] = key.snapMode;
			snapOffsetX[i] = key.snapOffset.x;
			snapOffsetY[i] = key.snapOffset.y;
		}
		return VirtualKeyboardLayoutSnapshot.legacy(
				layoutVariant,
				visible,
				snapOrigins,
				snapModes,
				snapOffsetX,
				snapOffsetY,
				keyScales);
	}

	public void restoreLayoutSnapshot(VirtualKeyboardLayoutSnapshot snapshot) {
		if (snapshot == null || !snapshot.matchesLegacyShape(keypad.length, keyScales.length)) return;
		layoutVariant = snapshot.layoutVariant;
		for (int i = 0; i < keypad.length; i++) {
			VirtualKey key = keypad[i];
			key.visible = snapshot.visible[i];
			key.snapOrigin = snapshot.snapOrigins[i];
			key.snapMode = snapshot.snapModes[i];
			key.snapOffset.set(snapshot.snapOffsetX[i], snapshot.snapOffsetY[i]);
			key.snapValid = false;
		}
		System.arraycopy(snapshot.keyScales, 0, keyScales, 0, keyScales.length);
		for (int group = 0; group < keyScaleGroups.length; group++) {
			resizeKeyGroup(group);
		}
		if (screen != null) snapKeys();
		if (overlayView != null) overlayView.postInvalidate();
		if (target != null && target.isShown()) target.updateSize();
		notifyLayoutEditStateChanged();
	}

	public float getPhoneKeyboardHeight(float w, float h) {
		return PHONE_KEY_ROWS * getKeySize(w, h) * PHONE_KEY_SCALE_Y;
	}

	public void setLayout(int variant) {
		applyLayout(variant, true);
	}

	/** Applies a layout for the active editor transaction without writing it to persistent storage. */
	public void setLayoutForEditing(int variant) {
		applyLayout(variant, false);
	}

	protected final void setLayoutVariantInMemory(int variant) {
		layoutVariant = variant;
	}

	private void applyLayout(int variant, boolean persist) {
		int previousVariant = layoutVariant;
		resetLayout(variant);
		if (variant == TYPE_CUSTOM) {
			try {
				readLayout();
			} catch (IOException ioe) {
				ioe.printStackTrace();
				resetLayout(previousVariant);
				return;
			}
		}
		layoutVariant = variant;
		if (persist) onLayoutChanged(variant);
		for (int group = 0; group < keyScaleGroups.length; group++) {
			resizeKeyGroup(group);
		}
		if (screen != null) snapKeys();
		if (overlayView != null) overlayView.postInvalidate();
		if (target != null && target.isShown()) {
			target.updateSize();
		}
		notifyLayoutEditStateChanged();
	}

	private void saveLayout() {
		int variant = layoutVariant;
		if (variant == TYPE_CUSTOM && !prepareCustomLayoutForSave()) {
			Log.w(TAG, "Refusing to persist Custom layout with unreconstructible key geometry");
			return;
		}
		try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
			if (variant != TYPE_CUSTOM && raf.length() > 16) {
				try {
					if (raf.readInt() != LAYOUT_SIGNATURE) {
						throw new IOException("file signature not found");
					}
					int version = raf.readInt();
					if (version < 1 || version > LAYOUT_VERSION) {
						throw new IOException("incompatible file version");
					}
					loop:while (true) {
						int block = raf.readInt();
						int length = raf.readInt();
						if (length < 0) break;
						switch (block) {
							case LAYOUT_EOF:
								raf.seek(raf.getFilePointer() - 8);
								raf.writeInt(LAYOUT_TYPE);
								raf.writeInt(1);
								raf.write(variant);
								raf.writeInt(LAYOUT_EOF);
								raf.writeInt(0);
								return;
							case LAYOUT_TYPE:
								if (length < 1) break loop;
								raf.write(variant);
								return;
							case LAYOUT_KEYS:
								if (version >= 2) {
									if (length < 4) break loop;
									int count = raf.readInt();
									if (count < 0 || count > KEYBOARD_SIZE) break loop;
									length = count * KEY_RECORD_SIZE_V2;
								}
							default:
								if (raf.skipBytes(length) != length) {
									break loop;
								}
								break;
						}
					}
				} catch (IOException e) {
					Log.w(TAG, "Could not update existing layout type in place", e);
				}
			}
			raf.seek(0);
			raf.writeInt(LAYOUT_SIGNATURE);
			raf.writeInt(LAYOUT_VERSION);
			raf.writeInt(LAYOUT_TYPE);
			raf.writeInt(1);
			raf.write(variant);
			if (variant != TYPE_CUSTOM) {
				raf.writeInt(LAYOUT_EOF);
				raf.writeInt(0);
				raf.setLength(raf.getFilePointer());
				return;
			}
			raf.writeInt(LAYOUT_KEYS);
			raf.writeInt(keypad.length * KEY_RECORD_SIZE_V2 + 4);
			raf.writeInt(keypad.length);
			for (VirtualKey key : keypad) {
				raf.writeInt(key.hashCode());
				raf.writeBoolean(key.visible);
				raf.writeInt(key.snapOrigin);
				raf.writeInt(key.snapMode);
				PointF snapOffset = key.snapOffset;
				raf.writeFloat(snapOffset.x);
				raf.writeFloat(snapOffset.y);
			}
			raf.writeInt(LAYOUT_SCALES);
			raf.writeInt(keyScales.length * 4 + 4);
			raf.writeInt(keyScales.length);
			for (float keyScale : keyScales) {
				raf.writeFloat(keyScale);
			}
			raf.writeInt(LAYOUT_EOF);
			raf.writeInt(0);
			raf.setLength(raf.getFilePointer());
		} catch (IOException e) {
			Log.e(TAG, "Failed to save virtual keyboard layout", e);
		}
	}

	private boolean prepareCustomLayoutForSave() {
		for (VirtualKey key : keypad) {
			if (key.snapMode == RectSnap.NO_SNAP && !materializeKeyPositionAgainstScreen(key)) {
				return false;
			}
		}
		return hasValidSnapTopology(keypad);
	}

	private boolean materializeKeyPositionAgainstScreen(VirtualKey key) {
		if (key == null || !isUsableScreen(screen) || !isFiniteRect(key.rect)) return false;
		key.snapOrigin = SCREEN;
		key.snapMode = RectSnap.getSnap(key.rect, screen, key.snapOffset);
		key.snapValid = false;
		return isPersistableSnapMode(key.snapMode) &&
				Float.isFinite(key.snapOffset.x) && Float.isFinite(key.snapOffset.y);
	}

	private static boolean isUsableScreen(RectF value) {
		return value != null && Float.isFinite(value.left) && Float.isFinite(value.top) &&
				Float.isFinite(value.right) && Float.isFinite(value.bottom) &&
				value.width() > 0.0f && value.height() > 0.0f;
	}

	private static boolean isFiniteRect(RectF value) {
		return value != null && Float.isFinite(value.left) && Float.isFinite(value.top) &&
				Float.isFinite(value.right) && Float.isFinite(value.bottom) &&
				value.width() > 0.0f && value.height() > 0.0f;
	}

	private static boolean isPersistableSnapMode(int mode) {
		if (mode == RectSnap.NO_SNAP || (mode & ~RectSnap.FINE_MASK) != 0) return false;
		int horizontal = mode & RectSnap.HORIZONTAL_MASK;
		int vertical = mode & RectSnap.VERTICAL_MASK;
		return Integer.bitCount(horizontal) == 1 && Integer.bitCount(vertical) == 1;
	}

	private boolean hasValidSnapTopology(VirtualKey[] keys) {
		int[] origins = new int[keys.length];
		int[] modes = new int[keys.length];
		float[] offsetsX = new float[keys.length];
		float[] offsetsY = new float[keys.length];
		for (int i = 0; i < keys.length; i++) {
			origins[i] = keys[i].snapOrigin;
			modes[i] = keys[i].snapMode;
			offsetsX[i] = keys[i].snapOffset.x;
			offsetsY[i] = keys[i].snapOffset.y;
		}
		return isValidSnapTopology(origins, modes, offsetsX, offsetsY);
	}

	private static boolean isValidSnapTopology(
			int[] origins, int[] modes, float[] offsetsX, float[] offsetsY) {
		int size = origins.length;
		for (int i = 0; i < size; i++) {
			if (!isPersistableSnapMode(modes[i]) ||
					!Float.isFinite(offsetsX[i]) || !Float.isFinite(offsetsY[i])) {
				return false;
			}
			int origin = origins[i];
			if (origin != SCREEN && (origin < 0 || origin >= size || origin == i)) return false;
		}

		for (int i = 0; i < size; i++) {
			boolean[] visited = new boolean[size];
			int current = i;
			while (current != SCREEN) {
				if (current < 0 || current >= size || visited[current]) return false;
				visited[current] = true;
				current = origins[current];
			}
		}
		return true;
	}

	private int readLayoutType() {
		try (DataInputStream dis = new DataInputStream(new FileInputStream(saveFile))) {
			if (dis.readInt() != LAYOUT_SIGNATURE) {
				throw new IOException("file signature not found");
			}
			int version = dis.readInt();
			if (version < 1 || version > LAYOUT_VERSION) {
				throw new IOException("incompatible file version");
			}
			int custom = 0;
			for (int blockIndex = 0; blockIndex < MAX_LAYOUT_BLOCKS; blockIndex++) {
				int block = dis.readInt();
				int length = dis.readInt();
				if (length < 0) return -1;
				switch (block) {
					case LAYOUT_EOF -> {
						return length == 0 && custom == 3 ? TYPE_CUSTOM : -1;
					}
					case LAYOUT_TYPE -> {
						if (length < 1) return -1;
						int variant = dis.readUnsignedByte();
						skipFully(dis, length - 1);
						return variant;
					}
					case LAYOUT_KEYS -> {
						if (version >= 2) {
							if (length < 4) return -1;
							int count = dis.readInt();
							long expected = 4L + (long) count * KEY_RECORD_SIZE_V2;
							if (count < 0 || count > KEYBOARD_SIZE || expected != length) return -1;
							skipFully(dis, length - 4);
						} else {
							skipFully(dis, length);
						}
						custom |= 1;
					}
					case LAYOUT_SCALES -> {
						skipFully(dis, length);
						custom |= 2;
					}
					default -> skipFully(dis, length);
				}
			}
			return -1;
		} catch (FileNotFoundException e) {
			Log.w(TAG, "readLayoutType() threw an FileNotFoundException: " + e.getMessage());
		} catch (IOException e) {
			Log.w(TAG, "Could not read virtual keyboard layout type", e);
		}
		return -1;
	}

	private void readLayout() throws IOException {
		boolean[] stagedVisible = new boolean[keypad.length];
		int[] stagedOrigins = new int[keypad.length];
		int[] stagedModes = new int[keypad.length];
		float[] stagedOffsetX = new float[keypad.length];
		float[] stagedOffsetY = new float[keypad.length];
		for (int i = 0; i < keypad.length; i++) {
			VirtualKey key = keypad[i];
			stagedVisible[i] = key.visible;
			stagedOrigins[i] = key.snapOrigin;
			stagedModes[i] = key.snapMode;
			stagedOffsetX[i] = key.snapOffset.x;
			stagedOffsetY[i] = key.snapOffset.y;
		}
		float[] stagedScales = keyScales.clone();

		try (DataInputStream dis = new DataInputStream(new FileInputStream(saveFile))) {
			if (dis.readInt() != LAYOUT_SIGNATURE) {
				throw new IOException("file signature not found");
			}
			int version = dis.readInt();
			if (version < 1 || version > LAYOUT_VERSION) {
				throw new IOException("incompatible file version");
			}
			for (int blockIndex = 0; blockIndex < MAX_LAYOUT_BLOCKS; blockIndex++) {
				int block = dis.readInt();
				int length = dis.readInt();
				if (length < 0) throw new IOException("negative layout block length");
				switch (block) {
					case LAYOUT_EOF -> {
						if (length != 0) throw new IOException("invalid layout end block");
						if (!isValidSnapTopology(
								stagedOrigins, stagedModes, stagedOffsetX, stagedOffsetY)) {
							throw new IOException("invalid key snap topology");
						}
						applyStagedLayout(
								stagedVisible, stagedOrigins, stagedModes,
								stagedOffsetX, stagedOffsetY, stagedScales);
						return;
					}
					case LAYOUT_TYPE -> {
						if (length < 1) throw new IOException("empty layout type block");
						dis.readUnsignedByte();
						skipFully(dis, length - 1);
					}
					case LAYOUT_KEYS -> readKeyBlock(
							dis, version, length,
							stagedVisible, stagedOrigins, stagedModes,
							stagedOffsetX, stagedOffsetY);
					case LAYOUT_SCALES -> readScaleBlock(dis, version, length, stagedScales);
					default -> skipFully(dis, length);
				}
			}
			throw new IOException("layout contains too many blocks");
		}
	}

	private void readKeyBlock(
			DataInputStream dis,
			int version,
			int length,
			boolean[] visible,
			int[] origins,
			int[] modes,
			float[] offsetX,
			float[] offsetY) throws IOException {
		int itemSize = version >= 2 ? KEY_RECORD_SIZE_V2 : KEY_RECORD_SIZE_V1;
		int count = readCount(dis, length, itemSize);
		if (count < 0 || count > KEYBOARD_SIZE) {
			throw new IOException("invalid layout key count");
		}
		for (int i = 0; i < count; i++) {
			int hash = dis.readInt();
			boolean keyVisible = version >= 2 ? dis.readBoolean() : false;
			int origin = dis.readInt();
			int mode = dis.readInt();
			float x = dis.readFloat();
			float y = dis.readFloat();

			if (!Float.isFinite(x) || !Float.isFinite(y)) {
				throw new IOException("non-finite key snap offset");
			}
			if (mode != RectSnap.NO_SNAP &&
					(!isPersistableSnapMode(mode) ||
							(origin != SCREEN && (origin < 0 || origin >= KEYBOARD_SIZE)))) {
				throw new IOException("invalid key snap state");
			}

			int keyIndex = findKeyIndexByHash(hash);
			if (keyIndex < 0) continue;
			if (version >= 2) visible[keyIndex] = keyVisible;

			// Older broken Standard-derived Custom files may contain SCREEN + NO_SNAP. The raw
			// RectF was never persisted, so the exact lost position is unrecoverable. Keep the safe
			// resetLayout(TYPE_CUSTOM) fallback topology instead of replacing it with NO_SNAP.
			if (mode == RectSnap.NO_SNAP) continue;
			if (origin == keyIndex) throw new IOException("self-referencing key snap state");

			origins[keyIndex] = origin;
			modes[keyIndex] = mode;
			offsetX[keyIndex] = x;
			offsetY[keyIndex] = y;
		}
	}

	private void readScaleBlock(
			DataInputStream dis, int version, int length, float[] scales) throws IOException {
		int count = readCount(dis, length, 4);
		int maxScales = version >= 3 ? scales.length : scales.length / 2;
		if (count < 0 || count > maxScales) {
			throw new IOException("invalid layout scale count");
		}
		if (version >= 3) {
			for (int i = 0; i < count; i++) {
				float value = dis.readFloat();
				if (!Float.isFinite(value) || value <= 0.0f) {
					throw new IOException("invalid key scale");
				}
				scales[i] = value;
			}
		} else {
			for (int i = 0; i < count; i++) {
				float value = dis.readFloat();
				if (!Float.isFinite(value) || value <= 0.0f) {
					throw new IOException("invalid legacy key scale");
				}
				scales[i * 2] = value;
				scales[i * 2 + 1] = value;
			}
		}
	}

	private int findKeyIndexByHash(int hash) {
		for (int i = 0; i < keypad.length; i++) {
			if (keypad[i].hashCode() == hash) return i;
		}
		return -1;
	}

	private void applyStagedLayout(
			boolean[] visible,
			int[] origins,
			int[] modes,
			float[] offsetX,
			float[] offsetY,
			float[] scales) {
		for (int i = 0; i < keypad.length; i++) {
			VirtualKey key = keypad[i];
			key.visible = visible[i];
			key.snapOrigin = origins[i];
			key.snapMode = modes[i];
			key.snapOffset.set(offsetX[i], offsetY[i]);
			key.snapValid = false;
		}
		System.arraycopy(scales, 0, keyScales, 0, keyScales.length);
	}

	private static int readCount(DataInputStream dis, int length, int itemSize) throws IOException {
		if (length < 4) return -1;
		int count = dis.readInt();
		if (count < 0) return -1;
		long expected = 4L + (long) count * itemSize;
		return expected == length ? count : -1;
	}

	private static void skipFully(DataInputStream dis, int bytes) throws IOException {
		if (bytes < 0) throw new IOException("negative layout payload");
		int remaining = bytes;
		while (remaining > 0) {
			int skipped = dis.skipBytes(remaining);
			if (skipped <= 0) throw new IOException("truncated layout payload");
			remaining -= skipped;
		}
	}

	public String[] getKeyNames() {
		String[] names = new String[KEYBOARD_SIZE];
		for (int i = 0; i < KEYBOARD_SIZE; i++) {
			names[i] = keypad[i].label;
		}
		return names;
	}

	public boolean[] getKeysVisibility() {
		boolean[] states = new boolean[KEYBOARD_SIZE];
		for (int i = 0; i < KEYBOARD_SIZE; i++) {
			states[i] = !keypad[i].visible;
		}
		return states;
	}

	public void setKeysVisibility(boolean[] states) {
		for (int i = 0; i < KEYBOARD_SIZE; i++) {
			keypad[i].visible = !states[i];
		}
		if (overlayView != null) overlayView.postInvalidate();
		if (layoutEditMode != LAYOUT_EOF) notifyLayoutEditStateChanged();
	}

	@Override
	public void setTarget(Canvas canvas) {
		if (target != canvas) {
			cancel();
		}
		target = canvas;
		highlightGroup(-1);
	}

	/** Opens the controller-owned numeric keypad without changing the touch keyboard layout. */
	public void openControllerKeypad() {
		cancel();
		controllerKeypadVisible = true;
		controllerKeypadSelection = 0;
		visible = true;
		selectControllerKeypadKey();
		if (overlayView != null) overlayView.postInvalidate();
	}

	public boolean isControllerKeypadVisible() {
		return controllerKeypadVisible;
	}

	/** Handles generic host navigation; normal Canvas/keypad routing is blocked while open. */
	public boolean handleHostCommand(HostCommand command, boolean pressed) {
		if (!controllerKeypadVisible) return false;
		HostCommand navigationCommand = switch (command) {
			case PreviousTab -> HostCommand.NavigateLeft;
			case NextTab -> HostCommand.NavigateRight;
		default -> command;
		};
		if (navigationCommand == HostCommand.NavigateUp || navigationCommand == HostCommand.NavigateDown
				|| navigationCommand == HostCommand.NavigateLeft
				|| navigationCommand == HostCommand.NavigateRight) {
			if (pressed) {
				moveControllerKeypadSelection(navigationCommand);
			}
			return true;
		}
		if (command == HostCommand.Activate) {
			if (pressed) {
				if (controllerKeypadPressed == null) {
					controllerKeypadPressed = keypad[CONTROLLER_KEYPAD_ORDER[controllerKeypadSelection]];
					controllerKeypadPressed.onDown("controller-keypad");
					if (overlayView != null) overlayView.postInvalidate();
				}
			} else if (controllerKeypadPressed != null) {
				controllerKeypadPressed.onUp("controller-keypad");
				controllerKeypadPressed = null;
				if (overlayView != null) overlayView.postInvalidate();
			}
			return true;
		}
		if ((command == HostCommand.Back || command == HostCommand.OpenMenu
				|| command == HostCommand.OpenKeypad) && pressed) {
			closeControllerKeypad();
			return true;
		}
		return true;
	}


	private void moveControllerKeypadSelection(HostCommand direction) {
		int size = CONTROLLER_KEYPAD_ORDER.length;
		int row = controllerKeypadSelection / 3;
		int column = controllerKeypadSelection % 3;
		int lastRow = (size - 1) / 3;
		switch (direction) {
			case NavigateLeft -> controllerKeypadSelection =
					Math.max(row * 3, controllerKeypadSelection - 1);
			case NavigateRight -> controllerKeypadSelection =
					Math.min(Math.min(size - 1, row * 3 + 2), controllerKeypadSelection + 1);
			case NavigateUp -> {
				int targetRow = Math.max(0, row - 1);
				controllerKeypadSelection = Math.min(size - 1, targetRow * 3 + column);
			}
			case NavigateDown -> {
				int targetRow = Math.min(lastRow, row + 1);
				controllerKeypadSelection = Math.min(size - 1, targetRow * 3 + column);
			}
			default -> {
				return;
			}
		}
		selectControllerKeypadKey();
		if (overlayView != null) overlayView.postInvalidate();
	}

	private void selectControllerKeypadKey() {
		for (int index : CONTROLLER_KEYPAD_ORDER) keypad[index].selected = false;
		keypad[CONTROLLER_KEYPAD_ORDER[controllerKeypadSelection]].selected = true;
	}

	private void closeControllerKeypad() {
		if (controllerKeypadPressed != null) {
			controllerKeypadPressed.onUp("controller-keypad");
			controllerKeypadPressed = null;
		}
		controllerKeypadVisible = false;
		for (int index : CONTROLLER_KEYPAD_ORDER) keypad[index].selected = false;
		if (overlayView != null) overlayView.postInvalidate();
	}

	private String sourceForPointer(int pointer) {
		String source = associatedSources[pointer];
		return source == null ? "touch:" + pointer + ":orphan" : source;
	}

	private String newSourceForPointer(int pointer) {
		pointerSourceSequence = pointerSourceSequence == Long.MAX_VALUE
				? 1L : pointerSourceSequence + 1L;
		return "touch:" + pointer + ":" + pointerSourceSequence;
	}

	private void setSnap(int key, int origin, int mode, boolean visible) {
		VirtualKey vKey = keypad[key];
		vKey.snapOrigin = origin;
		vKey.snapMode = mode;
		vKey.snapOffset.set(0, 0);
		vKey.snapValid = false;
		vKey.visible = visible;
	}

	private boolean findSnap(int target, int origin) {
		VirtualKey tk = keypad[target];
		VirtualKey ok = keypad[origin];
		tk.snapMode = RectSnap.getSnap(tk.rect, ok.rect, snapRadius, RectSnap.COARSE_MASK, true);
		if (tk.snapMode != RectSnap.NO_SNAP) {
			tk.snapOrigin = origin;
			tk.snapOffset.set(0, 0);
			for (int i = 0; i < keypad.length; i++) {
				origin = keypad[origin].snapOrigin;
				if (origin == SCREEN) {
					return true;
				}
			}
		}
		return false;
	}

	private void snapKey(int key, int level) {
		if (level >= snapStack.length) {
			Log.d(TAG, "Snap loop detected: ");
			for (int i = 1; i < snapStack.length; i++) {
				System.out.print(snapStack[i]);
				System.out.print(", ");
			}
			Log.d(TAG, String.valueOf(key));
			return;
		}
		snapStack[level] = key;
		VirtualKey vKey = keypad[key];
		if (vKey.snapOrigin == SCREEN) {
			RectSnap.snap(vKey.rect, screen, vKey.snapMode, vKey.snapOffset);
		} else {
			if (vKey.snapOrigin < 0 || vKey.snapOrigin >= keypad.length ||
					vKey.snapOrigin == key) {
				Log.w(TAG, "Ignoring invalid snap origin " + vKey.snapOrigin + " for key " + key);
				vKey.snapValid = true;
				return;
			}
			if (!keypad[vKey.snapOrigin].snapValid) {
				snapKey(vKey.snapOrigin, level + 1);
			}
			RectSnap.snap(vKey.rect, keypad[vKey.snapOrigin].rect,
					vKey.snapMode, vKey.snapOffset);
		}
		vKey.snapValid = true;
	}

	private void snapKeys() {
		obscuresVirtualScreen = false;
		for (int i = 0; i < keypad.length; i++) {
			snapKey(i, 0);
			VirtualKey key = keypad[i];
			RectF rect = key.rect;
			key.corners = (int) (Math.min(rect.width(), rect.height()) * 0.25F);
			if (RectF.intersects(rect, virtualScreen)) {
				if (key.visible) {
					obscuresVirtualScreen = true;
				}
				key.opaque = false;
			} else {
				key.opaque = settings.vkForceOpacity;
			}
		}
	}

	public boolean isPhone() {
		return layoutVariant == TYPE_PHONE || layoutVariant == TYPE_PHONE_ARROWS;
	}

	private void highlightGroup(int group) {
		for (VirtualKey aKeypad : keypad) {
			aKeypad.selected = false;
		}
		if (group >= 0) {
			for (int key = 0; key < keyScaleGroups[group].length; key++) {
				keypad[keyScaleGroups[group][key]].selected = true;
			}
		}
	}

	public int getLayoutEditMode() {
		return layoutEditMode;
	}

	public void setLayoutEditMode(int mode) {
		layoutEditMode = mode;
		editedIndex = -1;
		highlightGroup(-1);
		handler.removeCallbacks(this);
		visible = true;
		if (overlayView != null) overlayView.postInvalidate();
		hide();
		notifyLayoutEditStateChanged();
	}

	private void resizeKey(int key, float w, float h) {
		VirtualKey vKey = keypad[key];
		vKey.resize(w, h);
		vKey.snapValid = false;
	}

	private void resizeKeyGroup(int group) {
		float sizeX = keySize * keyScales[group * 2];
		float sizeY = keySize * keyScales[group * 2 + 1];
		for (int key = 0; key < keyScaleGroups[group].length; key++) {
			resizeKey(keyScaleGroups[group][key], sizeX, sizeY);
		}
	}

	/**
	 * Applies a persisted scale pair to the group containing the named legacy key. Standard
	 * templates use this to establish non-square defaults before their keys are repositioned.
	 */
	protected final boolean setKeyGroupScaleByLabel(String label, float scaleX, float scaleY) {
		if (label == null || scaleX <= 0.0f || scaleY <= 0.0f) return false;
		int keyIndex = -1;
		for (int i = 0; i < keypad.length; i++) {
			if (label.equals(keypad[i].label)) {
				keyIndex = i;
				break;
			}
		}
		if (keyIndex < 0) return false;
		for (int group = 0; group < keyScaleGroups.length; group++) {
			for (int key : keyScaleGroups[group]) {
				if (key == keyIndex) {
					keyScales[group * 2] = scaleX;
					keyScales[group * 2 + 1] = scaleY;
					resizeKeyGroup(group);
					return true;
				}
			}
		}
		return false;
	}

	/** Places a built-in-template key by center without routing through the interactive editor. */
	protected final boolean setKeyCenterByLabel(String label, float centerX, float centerY) {
		if (label == null || !isUsableScreen(screen) ||
				!Float.isFinite(centerX) || !Float.isFinite(centerY)) {
			return false;
		}
		for (VirtualKey key : keypad) {
			if (!label.equals(key.label)) continue;
			float width = key.rect.width();
			float height = key.rect.height();
			key.rect.set(
					centerX - width * 0.5f,
					centerY - height * 0.5f,
					centerX + width * 0.5f,
					centerY + height * 0.5f);
			return materializeKeyPositionAgainstScreen(key);
		}
		return false;
	}

	/** Recomputes corner radii, overlap state, and drawing after direct template placement. */
	protected final void refreshDirectKeyLayout() {
		snapKeys();
		if (overlayView != null) overlayView.postInvalidate();
	}

	@Override
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		this.screen = screen;
		virtualScreen.set(left, top, right, bottom);
		snapRadius = keyScales[0];
		for (int i = 1; i < keyScales.length; i++) {
			if (keyScales[i] < snapRadius) {
				snapRadius = keyScales[i];
			}
		}

		float keySize = getKeySize(screen.width(), screen.height());
		snapRadius = keySize * snapRadius / 8;
		this.keySize = keySize;
		for (int group = 0; group < keyScaleGroups.length; group++) {
			resizeKeyGroup(group);
		}
		snapKeys();
		overlayView.postInvalidate();
		int delay = settings.vkHideDelay;
		if (delay > 0 && obscuresVirtualScreen && layoutEditMode == LAYOUT_EOF) {
			for (VirtualKey key : associatedKeys) {
				if (key != null) {
					return;
				}
			}
			handler.postDelayed(this, delay);
		}
		if (layoutEditMode != LAYOUT_EOF) notifyLayoutEditStateChanged();
	}

	private float getKeySize(float screenWidth, float screenHeight) {
		if (isPhone()) {
			return screenWidth / 6.0f;
		} else if (screenWidth > screenHeight) {
			return Math.min(screenWidth / 12.0f, screenHeight / 6.0f);
		} else {
			return Math.min(screenWidth / 6.0f, screenHeight / 12.0f);
		}
	}

	/** Current base size used by legacy key scale groups. */
	protected final float getCurrentKeySize() {
		return keySize;
	}

	@Override
	public void paint(CanvasWrapper g) {
		if (visible && (layoutEditMode != LAYOUT_EOF || settings.vkAlpha > 0)) {
			for (VirtualKey key : keypad) {
				if (key.visible) {
					key.paint(g);
				}
			}
		}
		if (controllerKeypadVisible && screen != null) {
			paintControllerKeypad(g);
		}
	}

	private void paintControllerKeypad(CanvasWrapper g) {
		float width = Math.min(screen.width() * 0.82f, keySize * 4.0f);
		float cellWidth = width / 3.0f;
		float cellHeight = Math.max(keySize * 0.72f, screen.height() / 12.0f);
		int rows = (CONTROLLER_KEYPAD_ORDER.length + 2) / 3;
		float height = rows * cellHeight;
		float left = screen.centerX() - width / 2.0f;
		float top = screen.centerY() - height / 2.0f;
		g.setFillColor(0xD9000000);
		g.fillRoundRect(new RectF(left - cellWidth * 0.12f, top - cellHeight * 0.55f,
				left + width + cellWidth * 0.12f, top + height + cellHeight * 0.12f),
				Math.max(8, (int) (cellWidth * 0.10f)), Math.max(8, (int) (cellWidth * 0.10f)));
		g.setTextColor(0xFFFFFFFF);
		g.drawString(ContextHolder.getActivity().getString(R.string.config_gamepad_keypad_title),
				screen.centerX(), top - cellHeight * 0.28f);
		for (int i = 0; i < CONTROLLER_KEYPAD_ORDER.length; i++) {
			int column = i % 3;
			int row = i / 3;
			RectF rect = new RectF(
					left + column * cellWidth + 3.0f,
					top + row * cellHeight + 3.0f,
					left + (column + 1) * cellWidth - 3.0f,
					top + (row + 1) * cellHeight - 3.0f);
			boolean selected = i == controllerKeypadSelection;
			g.setFillColor((selected ? 0xFF000080 : 0xE0D0D0D0));
			g.setDrawColor(0xFFFFFFFF);
			g.setTextColor(selected ? 0xFFFFFFFF : 0xFF000080);
			g.fillRoundRect(rect, Math.max(6, (int) (cellWidth * 0.08f)),
					Math.max(6, (int) (cellWidth * 0.08f)));
			g.drawRoundRect(rect, Math.max(6, (int) (cellWidth * 0.08f)),
					Math.max(6, (int) (cellWidth * 0.08f)));
			g.drawString(keypad[CONTROLLER_KEYPAD_ORDER[i]].label, rect.centerX(), rect.centerY());
		}
	}

	@Override
	public boolean pointerPressed(int pointer, float x, float y) {
		if (controllerKeypadVisible) return true;
		boolean consumed = false;
		switch (layoutEditMode) {
		case LAYOUT_EOF -> {
				if (pointer < 0 || pointer >= associatedKeys.length) {
					return false;
				}
				VirtualKey previous = associatedKeys[pointer];
				if (previous != null) {
					String previousSource = sourceForPointer(pointer);
					associatedKeys[pointer] = null;
					associatedSources[pointer] = null;
					previous.onUp(previousSource);
				}
				for (VirtualKey key : keypad) {
					if (key.contains(x, y)) {
						vibrate();
						associatedKeys[pointer] = key;
						associatedSources[pointer] = newSourceForPointer(pointer);
							key.onDown(sourceForPointer(pointer));
							overlayView.postInvalidate();
							consumed = true;
							break;
					}
				}
			}
			case LAYOUT_KEYS -> {
				editedIndex = -1;
				for (int i = 0; i < keypad.length; i++) {
					if (keypad[i].contains(x, y)) {
						editedIndex = i;
							RectF rect = keypad[i].rect;
							offsetX = x - rect.left;
							offsetY = y - rect.top;
							consumed = true;
							break;
					}
				}
			}
			case LAYOUT_SCALES -> {
				int index = -1;
				for (int group = 0; group < keyScaleGroups.length && index < 0; group++) {
					for (int key = 0; key < keyScaleGroups[group].length && index < 0; key++) {
						if (keypad[keyScaleGroups[group][key]].contains(x, y)) {
							index = group;
						}
					}
				}
				if (editedIndex == index) {
					editedIndex = -1;
					highlightGroup(-1);
					overlayView.postInvalidate();
				} else if (index >= 0) {
					editedIndex = index;
					highlightGroup(index);
					overlayView.postInvalidate();
				}
					if (editedIndex >= 0) {
						prevScaleX = keyScales[editedIndex * 2];
						prevScaleY = keyScales[editedIndex * 2 + 1];
					}
					consumed = index >= 0;
				offsetX = x;
				offsetY = y;
			}
		}
		if (consumed && layoutEditMode != LAYOUT_EOF) notifyLayoutEditStateChanged();
		return consumed;
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		if (controllerKeypadVisible) return true;
		boolean consumed = false;
		switch (layoutEditMode) {
		case LAYOUT_EOF -> {
				if (pointer < 0 || pointer >= associatedKeys.length) {
					return false;
				}
				VirtualKey aKey = associatedKeys[pointer];
				if (aKey == null) {
					consumed = pointerPressed(pointer, x, y);
				} else if (!aKey.contains(x, y)) {
					String source = sourceForPointer(pointer);
					associatedKeys[pointer] = null;
					associatedSources[pointer] = null;
					aKey.onUp(source);
					overlayView.postInvalidate();
					consumed = pointerPressed(pointer, x, y) || consumed;
				} else {
					consumed = true;
				}
			}
			case LAYOUT_KEYS -> {
				if (editedIndex >= 0) {
					consumed = true;
					VirtualKey key = keypad[editedIndex];
					RectF rect = key.rect;
					rect.offsetTo(x - offsetX, y - offsetY);
					key.snapMode = RectSnap.NO_SNAP;
					for (int i = 0; i < keypad.length; i++) {
						if (i != editedIndex && findSnap(editedIndex, i)) {
							break;
						}
					}
					if (key.snapMode == RectSnap.NO_SNAP) {
						key.snapMode = RectSnap.getSnap(rect, screen, key.snapOffset);
						key.snapOrigin = SCREEN;
						if (Math.abs(key.snapOffset.x) <= snapRadius) {
							key.snapOffset.x = 0;
						}
						if (Math.abs(key.snapOffset.y) <= snapRadius) {
							key.snapOffset.y = 0;
						}
					}
					snapKey(editedIndex, 0);
					overlayView.postInvalidate();
				}
			}
			case LAYOUT_SCALES -> {
				if (editedIndex == -1) {
					break;
				}
				consumed = true;
				float dx = x - offsetX;
				float dy = offsetY - y;
				int index = editedIndex * 2;
				float scale = prevScaleX + dx / Math.min(screen.centerX(), screen.centerY());
				if (scale <= 0.0f) {
					scale = Float.MIN_VALUE;
				}
				if (Math.abs(1 - scale) <= SCALE_SNAP_RADIUS) {
					scale = 1;
				} else {
					for (int i = 0; i < keyScales.length; i += 2) {
						if (i != index && Math.abs(keyScales[i] - scale) <= SCALE_SNAP_RADIUS) {
							scale = keyScales[i];
							break;
						}
					}
				}
				keyScales[index++] = scale;
				scale = prevScaleY + dy / Math.min(screen.centerX(), screen.centerY());
				if (scale <= 0.0f) {
					scale = Float.MIN_VALUE;
				}
				if (Math.abs(1 - scale) <= SCALE_SNAP_RADIUS) {
					scale = 1;
				} else {
					for (int i = 1; i < keyScales.length; i += 2) {
						if (i != index && Math.abs(keyScales[i] - scale) <= SCALE_SNAP_RADIUS) {
							scale = keyScales[i];
							break;
						}
					}
				}
				keyScales[index] = scale;
				resizeKeyGroup(editedIndex);
				snapKeys();
				overlayView.postInvalidate();
			}
		}
		if (consumed && layoutEditMode != LAYOUT_EOF) notifyLayoutEditStateChanged();
		return consumed;
	}

	@Override
	public boolean pointerReleased(int pointer, float x, float y) {
		if (controllerKeypadVisible) return true;
		boolean consumed = false;
		if (layoutEditMode == LAYOUT_EOF) {
			if (pointer < 0 || pointer >= associatedKeys.length) {
				return false;
			}
			VirtualKey key = associatedKeys[pointer];
			if (key != null) {
				String source = sourceForPointer(pointer);
				associatedKeys[pointer] = null;
				associatedSources[pointer] = null;
					key.onUp(source);
					overlayView.postInvalidate();
					consumed = true;
				}
		} else if (layoutEditMode == LAYOUT_KEYS) {
			consumed = editedIndex >= 0;
			for (int key = 0; key < keypad.length; key++) {
				VirtualKey vKey = keypad[key];
				if (vKey.snapOrigin == editedIndex) {
					vKey.snapMode = RectSnap.NO_SNAP;
					for (int i = 0; i < KEYBOARD_SIZE; i++) {
						if (i != key && findSnap(key, i)) {
							break;
						}
					}
					if (vKey.snapMode == RectSnap.NO_SNAP) {
						vKey.snapMode = RectSnap.getSnap(vKey.rect, screen, vKey.snapOffset);
						vKey.snapOrigin = SCREEN;
						if (Math.abs(vKey.snapOffset.x) <= snapRadius) {
							vKey.snapOffset.x = 0;
						}
						if (Math.abs(vKey.snapOffset.y) <= snapRadius) {
							vKey.snapOffset.y = 0;
						}
					}
					snapKey(key, 0);
				}
			}
			snapKeys();
			editedIndex = -1;
		} else if (layoutEditMode == LAYOUT_SCALES) {
			consumed = editedIndex >= 0;
			editedIndex = -1;
			highlightGroup(-1);
		}
		if (consumed && layoutEditMode != LAYOUT_EOF) notifyLayoutEditStateChanged();
		return consumed;
	}

	@Override
	public void show() {
		if (controllerKeypadVisible) {
			visible = true;
			return;
		}
		if (settings.vkHideDelay > 0 && obscuresVirtualScreen) {
			handler.removeCallbacks(this);
			if (!visible) {
				visible = true;
				overlayView.postInvalidate();
			}
		}
	}

	@Override
	public void hide() {
		if (controllerKeypadVisible) return;
		long delay = settings.vkHideDelay;
		if (delay > 0 && obscuresVirtualScreen && layoutEditMode == LAYOUT_EOF) {
			handler.postDelayed(this, delay);
		}
	}

	@Override
	public void cancel() {
		closeControllerKeypad();
		editedIndex = -1;
		highlightGroup(-1);
		for (int pointer = 0; pointer < associatedKeys.length; pointer++) {
			VirtualKey key = associatedKeys[pointer];
			if (key != null) {
				String source = sourceForPointer(pointer);
				associatedKeys[pointer] = null;
				associatedSources[pointer] = null;
				if (key instanceof MenuKey menuKey) {
					menuKey.selected = false;
					handler.removeCallbacks(menuKey);
				} else {
					key.onUp(source);
				}
			}
		}
		for (VirtualKey key : keypad) {
			key.selected = false;
		}
		if (overlayView != null) overlayView.postInvalidate();
		notifyLayoutEditStateChanged();
	}

	@Override
	public void run() {
		visible = false;
		overlayView.postInvalidate();
	}

	@Override
	public boolean keyPressed(int keyCode) {
		int hashCode = 31 * (31 + keyCode);
		for (VirtualKey key : keypad) {
			if (key.hashCode() == hashCode) {
				key.selected = true;
				overlayView.postInvalidate();
				break;
			}
		}
		return false;
	}

	@Override
	public boolean keyRepeated(int keyCode) {
		return false;
	}

	@Override
	public boolean keyReleased(int keyCode) {
		int hashCode = 31 * (31 + keyCode);
		for (VirtualKey key : keypad) {
			if (key.hashCode() == hashCode) {
				key.selected = false;
				overlayView.postInvalidate();
				break;
			}
		}
		return false;
	}

	private void vibrate() {
		if (settings.vkFeedback) ContextHolder.vibrateKey(FEEDBACK_DURATION);
	}

	public void setView(View view) {
		overlayView = view;
	}

	public int getKeyStatesVodafone() {
		int keyStates = 0;
		for (int i = 0; i < keypad.length; i++) {
			VirtualKey key = keypad[i];
			if (key.selected) {
				keyStates |= getKeyBit(i);
			}
		}
		return keyStates;
	}

	private int getKeyBit(int vKey) {
		return switch (vKey) {
			case KEY_NUM0       -> 1      ; //  0 0
			case KEY_NUM1       -> 1 <<  1; //  1 1
			case KEY_NUM2       -> 1 <<  2; //  2 2
			case KEY_NUM3       -> 1 <<  3; //  3 3
			case KEY_NUM4       -> 1 <<  4; //  4 4
			case KEY_NUM5       -> 1 <<  5; //  5 5
			case KEY_NUM6       -> 1 <<  6; //  6 6
			case KEY_NUM7       -> 1 <<  7; //  7 7
			case KEY_NUM8       -> 1 <<  8; //  8 8
			case KEY_NUM9       -> 1 <<  9; //  9 9
			case KEY_STAR       -> 1 << 10; // 10 *
			case KEY_POUND      -> 1 << 11; // 11 #
			case KEY_UP         -> 1 << 12; // 12 Up
			case KEY_LEFT       -> 1 << 13; // 13 Left
			case KEY_RIGHT      -> 1 << 14; // 14 Right
			case KEY_DOWN       -> 1 << 15; // 15 Down
			case KEY_FIRE       -> 1 << 16; // 16 Select
			case KEY_SOFT_LEFT  -> 1 << 17; // 17 Softkey 1
			case KEY_SOFT_RIGHT -> 1 << 18; // 18 Softkey 2
			// TODO: 05.08.2020 Softkey3 mapped to KEY_C
			case KEY_C          -> 1 << 19; // 19 Softkey 3
			case KEY_UP_RIGHT   -> 1 << 20; // 20 Upper Right
			case KEY_UP_LEFT    -> 1 << 21; // 21 Upper Left
			case KEY_DOWN_RIGHT -> 1 << 22; // 22 Lower Right
			case KEY_DOWN_LEFT  -> 1 << 23; // 23 Lower Left
			default             -> 0      ;
		};
	}

	public void saveScreenParams() {
		float scale = virtualScreen.width() / screen.width();
		settings.screenScaleRatio = Math.round(scale * 100);
		settings.screenGravity = 1;
		ProfilesManager.saveConfig(settings);
	}

	private class VirtualKey {
		final String label;
		final int keyCode;
		final RectF rect = new RectF();
		final PointF snapOffset = new PointF();
		int snapOrigin;
		int snapMode;
		boolean snapValid;
		boolean selected;
		boolean visible = true;
		boolean opaque = true;
		int corners;
		private final int hashCode;

		VirtualKey(int keyCode, String label) {
			this.keyCode = keyCode;
			this.label = label;
			hashCode = 31 * (31 + this.keyCode);
		}

		void resize(float width, float height) {
			rect.right = rect.left + width;
			rect.bottom = rect.top + height;
		}

		boolean contains(float x, float y) {
			return visible && rect.contains(x, y);
		}

		void paint(CanvasWrapper g) {
			int bgColor;
			int fgColor;
			if (selected) {
				bgColor = settings.vkBgColorSelected;
				fgColor = settings.vkFgColorSelected;
			} else {
				bgColor = settings.vkBgColor;
				fgColor = settings.vkFgColor;
			}
			int alpha = (opaque || layoutEditMode != LAYOUT_EOF ? 0xFF : settings.vkAlpha) << 24;
			g.setFillColor((layoutEditMode != LAYOUT_EOF ? (0xFF / 3) << 24 : alpha) | bgColor);
			g.setTextColor(alpha | fgColor);
			g.setDrawColor(alpha | settings.vkOutlineColor);

			switch (settings.vkButtonShape) {
				case SHAPE_ROUND_RECT -> {
					g.fillRoundRect(rect, corners, corners);
					g.drawRoundRect(rect, corners, corners);
				}
				case SHAPE_RECT -> {
					g.fillRect(rect);
					g.drawRect(rect);
				}
				case SHAPE_OVAL -> {
					g.fillArc(rect, 0, 360);
					g.drawArc(rect, 0, 360);
				}
			}
			g.drawString(label, rect.centerX(), rect.centerY());
		}

		@NonNull
		public String toString() {
			return "[" + label + ": " + rect.left + ", " + rect.top + ", " + rect.right + ", " + rect.bottom + "]";
		}

		public int hashCode() {
			return hashCode;
		}

		protected void onDown(String source) {
			selected = true;
			if (target != null) {
				target.inputPressed("vk@" + Integer.toHexString(System.identityHashCode(VirtualKeyboard.this)),
						target.inputGeneration(), "virtual-keypad", source, keyCode);
			}
		}

		public void onUp(String source) {
			selected = false;
			if (target != null) {
				target.inputReleased("vk@" + Integer.toHexString(System.identityHashCode(VirtualKeyboard.this)),
						target.inputGeneration(), "virtual-keypad", source);
			}
		}
	}

	private class DualKey extends VirtualKey {

		final int secondKeyCode;
		private final int hashCode;

		DualKey(int keyCode, int secondKeyCode, String label) {
			super(keyCode, label);
			if (secondKeyCode == 0) throw new IllegalArgumentException();
			this.secondKeyCode = secondKeyCode;
			hashCode = 31 * (31 + this.keyCode) + this.secondKeyCode;
		}

		@Override
		protected void onDown(String source) {
			super.onDown(source);
			if (target != null) {
				target.inputPressed("vk@" + Integer.toHexString(System.identityHashCode(VirtualKeyboard.this)),
						target.inputGeneration(), "virtual-keypad", source + ":second", secondKeyCode);
			}
		}

		@Override
		public void onUp(String source) {
			super.onUp(source);
			if (target != null) {
				target.inputReleased("vk@" + Integer.toHexString(System.identityHashCode(VirtualKeyboard.this)),
						target.inputGeneration(), "virtual-keypad", source + ":second");
			}
		}

		@Override
		public int hashCode() {
			return hashCode;
		}
	}

	private class MenuKey extends VirtualKey implements Runnable {

		MenuKey() {
			super(KeyMapper.KEY_OPTIONS_MENU, "M");
		}

		@Override
		protected void onDown(String source) {
			selected = true;
			handler.postDelayed(this, 500);
		}

		@Override
		public void onUp(String source) {
			if (selected) {
				selected = false;
				handler.removeCallbacks(this);
				MicroActivity activity = ContextHolder.getActivity();
				if (activity != null) {
					activity.openOptionsMenu();
				}
			}
		}

		@Override
		public void run() {
			selected = false;
			MicroActivity activity = ContextHolder.getActivity();
			if (activity != null) {
				activity.runOnUiThread(activity::showExitConfirmation);
			}
		}
	}
}
