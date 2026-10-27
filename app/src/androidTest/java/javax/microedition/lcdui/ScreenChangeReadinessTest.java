/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import io.github.h3nb.jlmodplus.config.ProfileModel;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ScreenChangeReadinessTest {
	@Test
	public void unavailableSurfaceDeadlineReleasesCallbacksWithoutShowingCanvas() throws Exception {
		Field settingsField = Canvas.class.getDeclaredField("settings");
		settingsField.setAccessible(true);
		ProfileModel previousSettings = (ProfileModel) settingsField.get(null);
		Display.initDisplay();
		Display.ScreenChange change = null;
		Canvas canvas = null;
		Display display = null;
		Field activeField = Display.class.getDeclaredField("screenChange");
		activeField.setAccessible(true);
		Runnable deadline = null;
		try {
			if (previousSettings == null) Canvas.setSettings(new ProfileModel());
			canvas = new Canvas() {
				@Override
				protected void paint(Graphics graphics) {}
			};
			display = Display.getDisplay(new FixtureMidlet());
			// Fault injection at the host boundary: mounting finishes, but surfaceCreated()
			// never arrives. No app-visible fake visibility or production test switch.
			change = display.new ScreenChange(canvas, 1L, null);
			activeField.set(display, change);
			canvas.beginScreenChange(change);
			change.mounted();
			Display.postSerializedEvent(change);
			CountDownLatch callback = new CountDownLatch(1);
			display.callSerially(callback::countDown);
			assertFalse("LCDUI callback bypassed the unresolved screen change",
					callback.await(100, TimeUnit.MILLISECONDS));
			Field deadlineField = change.getClass().getDeclaredField("readinessDeadline");
			deadlineField.setAccessible(true);
			deadline = (Runnable) deadlineField.get(change);
			ViewHandler.postDelayed(deadline, 100L);
			assertTrue("A missing surface must not hold the LCDUI queue indefinitely",
					callback.await(5, TimeUnit.SECONDS));
			assertFalse("The host timeout must not fabricate guest visibility", canvas.isShown());
		} finally {
			if (deadline != null) ViewHandler.removeCallbacks(deadline);
			if (change != null) {
				change.cancel();
				if (canvas != null) canvas.clearScreenChange(change);
			}
			if (display != null) activeField.set(display, null);
			Display.initDisplay();
			settingsField.set(null, previousSettings);
		}
	}

	private static final class FixtureMidlet extends MIDlet {
		@Override public void startApp() throws MIDletStateChangeException {}
		@Override public void pauseApp() {}
		@Override public void destroyApp(boolean unconditional) throws MIDletStateChangeException {}
	}
}
