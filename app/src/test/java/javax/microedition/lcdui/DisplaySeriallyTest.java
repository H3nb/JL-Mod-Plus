/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import org.junit.Test;

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
