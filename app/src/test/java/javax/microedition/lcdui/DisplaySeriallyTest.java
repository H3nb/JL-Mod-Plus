/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.lcdui.event.EventQueue;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

import org.junit.Test;

public class DisplaySeriallyTest {
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
