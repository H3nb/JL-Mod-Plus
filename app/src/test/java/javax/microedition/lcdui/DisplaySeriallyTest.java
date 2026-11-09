/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import org.junit.jupiter.api.Test;

public class DisplaySeriallyTest {
	@Test
	public void setCurrentNullKeepsGuestCurrentDisplayable() {
		Display.initDisplay();
		Display display = Display.getDisplay(new TestMidlet());
		Displayable canvasA = new Displayable() {};

		display.setCurrent(canvasA);
		display.setCurrent(null);

		assertSame(canvasA, display.getCurrent());
	}

	@Test
	public void nonCanvasVisibilityRequiresMountedCurrentScreenAndForeground() throws Exception {
		Display.initDisplay();
		Display display = Display.getDisplay(new TestMidlet());
		Displayable first = new Displayable() {};
		Displayable second = new Displayable() {};
		long generation = Display.requestForeground();
		try {
			display.setHostVisible(true);
			assertTrue(Display.grantForeground(generation));
			display.setCurrent(first);
			long firstRequest = requestGeneration(display);
			assertFalse(first.isShown());
			display.onHostPresented(first, firstRequest);
			assertTrue(first.isShown());
			display.setCurrent(second);
			long secondRequest = requestGeneration(display);
			assertFalse(first.isShown());
			assertFalse(second.isShown());
			display.onHostPresented(first, firstRequest); // stale UI completion
			assertFalse(first.isShown());
			display.onHostPresented(second, secondRequest);
			assertTrue(second.isShown());
			// Returning to the same object must not accept an older mount completion.
			display.setCurrent(first);
			long thirdRequest = requestGeneration(display);
			assertFalse(first.isShown());
			display.onHostPresented(first, firstRequest);
			assertFalse(first.isShown());
			display.onHostPresented(first, thirdRequest);
			assertTrue(first.isShown());
			Display.revokeForeground();
			assertFalse(first.isShown());
		} finally {
			Display.revokeForeground();
			Display.initDisplay();
		}
	}

	@Test
	public void callSeriallyIsSerializedWhileHostBarrierContinuesOutsideCallback() throws Exception {
		Display.initDisplay();
		Display display = Display.getDisplay(new TestMidlet());
		CountDownLatch complete = new CountDownLatch(2);
		AtomicBoolean seriallyInCallback = new AtomicBoolean();
		AtomicBoolean barrierInCallback = new AtomicBoolean(true);

		display.callSerially(() -> {
			seriallyInCallback.set(EventQueue.isInCallback());
			complete.countDown();
		});
		Display.postAfterPendingCallbacks(() -> {
			barrierInCallback.set(EventQueue.isInCallback());
			complete.countDown();
		});

		assertTrue(complete.await(1, TimeUnit.SECONDS));
		assertTrue(seriallyInCallback.get());
		assertFalse(barrierInCallback.get());
	}


	@Test
	public void dismissedAlertCannotOverrideNewerScreenOrReusedAlertPresentation() throws Exception {
		Display.initDisplay();
		try {
			Display display = Display.getDisplay(new TestMidlet());
			Displayable initial = new Displayable() {};
			Displayable newer = new Displayable() {};
			Alert alert = new Alert("message");
			display.setCurrent(initial);
			display.setCurrent(alert);
			long oldPresentation = requestGeneration(display);
			display.setCurrent(newer);
			// This used to behave like setCurrent(initial), undoing the newer request.
			display.completeAlertDismissal(alert, oldPresentation, initial);
			assertSame(newer, display.getCurrent());

			// Reusing the same Alert object must not revive a callback from its old dialog.
			display.setCurrent(alert);
			long newPresentation = requestGeneration(display);
			display.completeAlertDismissal(alert, oldPresentation, initial);
			assertSame(alert, display.getCurrent());
			display.completeAlertDismissal(alert, newPresentation, newer);
			assertSame(newer, display.getCurrent());

			// An ordinary default-listener dismissal still returns to its target.
			display.setCurrent(alert);
			long thirdPresentation = requestGeneration(display);
			display.completeAlertDismissal(alert, thirdPresentation, initial);
			assertSame(initial, display.getCurrent());
		} finally {
			Display.initDisplay();
		}
	}

	private static long requestGeneration(Display display) throws Exception {
		Field field = Display.class.getDeclaredField("currentRequestGeneration");
		field.setAccessible(true);
		return ((AtomicLong) field.get(display)).get();
	}

	private static final class TestMidlet extends MIDlet {
		@Override
		public void startApp() throws MIDletStateChangeException {
		}

		@Override
		public void pauseApp() {
		}

		@Override
		public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
		}
	}
}
