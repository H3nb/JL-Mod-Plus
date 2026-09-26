/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class EventQueueBarrierTest {
	@Test
	public void barrierRunsOnlyAfterEarlierCallbackCompletes() throws Exception {
		EventQueue queue = new EventQueue();
		CountDownLatch hideEntered = new CountDownLatch(1);
		CountDownLatch releaseHide = new CountDownLatch(1);
		CountDownLatch backgroundPosted = new CountDownLatch(1);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		queue.startProcessing();
		try {
			queue.postEvent(RunnableEvent.getInstance(() -> {
				order.add("hide.begin");
				hideEntered.countDown();
				await(releaseHide);
				order.add("hide.end");
			}));
			queue.postEvent(RunnableEvent.getInstance(() -> {
				order.add("background");
				backgroundPosted.countDown();
			}));

			assertTrue(hideEntered.await(1, TimeUnit.SECONDS));
			assertEquals(1L, backgroundPosted.getCount());

			releaseHide.countDown();
			assertTrue(backgroundPosted.await(1, TimeUnit.SECONDS));
			assertEquals(Arrays.asList("hide.begin", "hide.end", "background"), order);
		} finally {
			releaseHide.countDown();
			queue.stopProcessing();
		}
	}

	@Test
	public void rapidLeaveAndReturnBarriersPreserveEdgeOrder() throws Exception {
		EventQueue queue = new EventQueue();
		CountDownLatch complete = new CountDownLatch(1);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		queue.startProcessing();
		try {
			queue.postEvent(RunnableEvent.getInstance(() -> order.add("hide")));
			queue.postEvent(RunnableEvent.getInstance(() -> order.add("background")));
			queue.postEvent(RunnableEvent.getInstance(() -> {
				order.add("foreground");
				complete.countDown();
			}));

			assertTrue(complete.await(1, TimeUnit.SECONDS));
			assertEquals(Arrays.asList("hide", "background", "foreground"), order);
		} finally {
			queue.stopProcessing();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}
}
