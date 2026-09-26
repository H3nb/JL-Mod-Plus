/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
			queue.postBarrier(() -> {
				order.add("background");
				backgroundPosted.countDown();
			});

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
	public void legacyPauseCleanupCannotOverlapFrameworkHide() throws Exception {
		EventQueue queue = new EventQueue();
		ExecutorService midletMain = Executors.newSingleThreadExecutor();
		CountDownLatch hideEntered = new CountDownLatch(1);
		CountDownLatch releaseHide = new CountDownLatch(1);
		CountDownLatch pauseFinished = new CountDownLatch(1);
		AtomicBoolean cleanupActive = new AtomicBoolean();
		AtomicBoolean overlap = new AtomicBoolean();
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		queue.startProcessing();
		try {
			queue.postEvent(RunnableEvent.getInstance(() -> {
				order.add("hide.begin");
				if (!cleanupActive.compareAndSet(false, true)) {
					overlap.set(true);
				}
				hideEntered.countDown();
				await(releaseHide);
				order.add("hide.end");
				cleanupActive.set(false);
			}));
			queue.postBarrier(() -> midletMain.execute(() -> {
				order.add("pause.begin");
				order.add("manualHide.begin");
				if (!cleanupActive.compareAndSet(false, true)) {
					overlap.set(true);
				}
				order.add("manualHide.end");
				cleanupActive.set(false);
				order.add("pause.end");
				pauseFinished.countDown();
			}));

			assertTrue(hideEntered.await(1, TimeUnit.SECONDS));
			assertEquals(1L, pauseFinished.getCount());

			releaseHide.countDown();
			assertTrue(pauseFinished.await(1, TimeUnit.SECONDS));
			assertFalse(overlap.get());
			assertEquals(Arrays.asList(
					"hide.begin", "hide.end",
					"pause.begin", "manualHide.begin", "manualHide.end", "pause.end"), order);
		} finally {
			releaseHide.countDown();
			midletMain.shutdownNow();
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
			queue.postBarrier(() -> order.add("background"));
			queue.postBarrier(() -> {
				order.add("foreground");
				complete.countDown();
			});

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
