/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.lcdui.graphics.CanvasWrapper;

import io.github.h3nb.jlmodplus.config.ProfileModel;

@RunWith(AndroidJUnit4.class)
public class VirtualKeyboardLegendRenderingTest {
	private static final float EPS = 0.01f;

	private VirtualKeyboard keyboard;
	private Object[] keypad;
	private File profileDir;

	@Before
	public void setUp() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		profileDir = new File(context.getCacheDir(),
				"vk-legend-rendering-" + System.nanoTime());
		assertTrue(profileDir.mkdirs());

		ProfileModel settings = new ProfileModel();
		settings.dir = profileDir;
		settings.vkType = 3;
		settings.vkFeedback = false;
		settings.vkAlpha = 255;

		keyboard = new VirtualKeyboard(settings);
		keyboard.setView(new View(context));
		keyboard.resize(new RectF(0f, 0f, 1200f, 600f), 0f, 0f, 1200f, 600f);

		Field keypadField = VirtualKeyboard.class.getDeclaredField("keypad");
		keypadField.setAccessible(true);
		keypad = (Object[]) keypadField.get(keyboard);
	}

	@After
	public void tearDown() throws Exception {
		if (keyboard != null) {
			keyboard.cancel();
			Field handlerField = VirtualKeyboard.class.getDeclaredField("handler");
			handlerField.setAccessible(true);
			Handler handler = (Handler) handlerField.get(keyboard);
			handler.getLooper().quitSafely();
		}
		if (profileDir != null) profileDir.delete();
	}

	@Test
	public void canonicalKeyNamesRemainUndecorated() {
		String[] names = keyboard.getKeyNames();
		assertEquals("2", names[1]);
		assertEquals("0", names[9]);
		assertEquals("*", names[10]);
		assertEquals("#", names[11]);
		assertFalse(Arrays.asList(names).contains("2 ABC"));
	}

	@Test
	public void actualBoundsSelectHorizontalStackedAndPrimaryOnly() throws Exception {
		RectF two = mutableRect(keyByLabel("2"));

		two.set(100f, 100f, 220f, 150f);
		RecordingCanvasWrapper wide = graphics();
		keyboard.paint(wide);
		TextDraw widePrimary = wide.find("2");
		TextDraw wideLegend = wide.find("ABC");
		assertNotNull(widePrimary);
		assertNotNull(wideLegend);
		assertEquals(widePrimary.y, wideLegend.y, EPS);
		assertTrue(widePrimary.scale <= 1.0f);

		two.set(100f, 100f, 132f, 190f);
		RecordingCanvasWrapper stacked = graphics();
		keyboard.paint(stacked);
		TextDraw stackedPrimary = stacked.find("2");
		TextDraw stackedLegend = stacked.find("ABC");
		assertNotNull(stackedPrimary);
		assertNotNull(stackedLegend);
		assertTrue(Math.abs(stackedPrimary.y - stackedLegend.y) > 1.0f);

		two.set(100f, 100f, 118f, 120f);
		RecordingCanvasWrapper tiny = graphics();
		keyboard.paint(tiny);
		TextDraw tinyPrimary = tiny.find("2");
		assertNotNull(tinyPrimary);
		assertNull(tiny.find("ABC"));
		assertTrue(tinyPrimary.scale < 1.0f);
	}

	@Test
	public void equalSizedNumericKeysUseOneSecondaryScale() {
		RecordingCanvasWrapper graphics = graphics();
		keyboard.paint(graphics);
		TextDraw abc = graphics.find("ABC");
		TextDraw pqrs = graphics.find("PQRS");
		TextDraw wxyz = graphics.find("WXYZ");
		assertNotNull(abc);
		assertNotNull(pqrs);
		assertNotNull(wxyz);
		assertEquals(abc.scale, pqrs.scale, EPS);
		assertEquals(abc.scale, wxyz.scale, EPS);
	}

	@Test
	public void isolatedStarAndZeroRemainPlainWithoutNumericContext() {
		String[] names = keyboard.getKeyNames();
		boolean[] hidden = new boolean[names.length];
		Arrays.fill(hidden, true);
		for (int i = 0; i < names.length; i++) {
			if ("*".equals(names[i]) || "0".equals(names[i])) hidden[i] = false;
		}
		keyboard.setKeysVisibility(hidden);

		assertFalse(keyboard.hasVisibleNumericKeypadContext());
		RecordingCanvasWrapper graphics = graphics();
		keyboard.paint(graphics);
		assertNotNull(graphics.find("*"));
		assertNotNull(graphics.find("0"));
		assertNull(graphics.find("SYM"));
		assertNull(graphics.find("+"));
	}

	@Test
	public void controllerKeypadUsesSameLegendRendererEvenWhenTouchDigitsAreHidden() {
		boolean[] hidden = new boolean[keyboard.getKeyNames().length];
		Arrays.fill(hidden, true);
		keyboard.setKeysVisibility(hidden);
		keyboard.openControllerKeypad();

		RecordingCanvasWrapper graphics = graphics();
		keyboard.paint(graphics);
		assertNotNull(graphics.find("ABC"));
		assertNotNull(graphics.find("PQRS"));
		assertNotNull(graphics.find("WXYZ"));
		assertNotNull(graphics.find("SYM"));
		assertNotNull(graphics.find("+"));
		assertNotNull(graphics.find("Aa"));
	}

	@Test
	public void scopedScaledTextOperationsRestorePreviousCanvasTextSize() {
		CanvasWrapper graphics = graphics();
		graphics.setTextScale(0.75f);
		float before = graphics.measureStringWidth("MMMM");

		graphics.measureStringWidth("WXYZ", 0.35f);
		graphics.getTextHeight(0.35f);
		graphics.drawString("ABC", 100f, 100f, 0.35f);

		assertEquals(before, graphics.measureStringWidth("MMMM"), EPS);
	}

	private RecordingCanvasWrapper graphics() {
		RecordingCanvasWrapper graphics = new RecordingCanvasWrapper();
		Bitmap bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888);
		graphics.bind(new android.graphics.Canvas(bitmap));
		return graphics;
	}

	private Object keyByLabel(String expected) throws Exception {
		for (Object key : keypad) {
			Field label = findField(key.getClass(), "label");
			label.setAccessible(true);
			if (expected.equals(label.get(key))) return key;
		}
		throw new AssertionError("Missing virtual key: " + expected);
	}

	private static RectF mutableRect(Object key) throws Exception {
		Field rect = findField(key.getClass(), "rect");
		rect.setAccessible(true);
		return (RectF) rect.get(key);
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static final class TextDraw {
		final String text;
		final float x;
		final float y;
		final float scale;

		TextDraw(String text, float x, float y, float scale) {
			this.text = text;
			this.x = x;
			this.y = y;
			this.scale = scale;
		}
	}

	private static final class RecordingCanvasWrapper extends CanvasWrapper {
		private final List<TextDraw> draws = new ArrayList<>();

		RecordingCanvasWrapper() {
			super(false);
		}

		@Override
		public void drawString(String text, float x, float y) {
			draws.add(new TextDraw(text, x, y, 1.0f));
			super.drawString(text, x, y);
		}

		@Override
		public void drawString(String text, float x, float y, float scale) {
			draws.add(new TextDraw(text, x, y, scale));
			super.drawString(text, x, y, scale);
		}

		TextDraw find(String text) {
			for (TextDraw draw : draws) {
				if (text.equals(draw.text)) return draw;
			}
			return null;
		}
	}
}
