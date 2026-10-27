/*
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EventQueueTest {
	@Test
	public void immediateModeKeepsGuestCallbacksOnEventThread() throws Exception {
		EventQueue queue = new EventQueue();
		java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<Thread> executingThread =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread postingThread = Thread.currentThread();
		queue.startProcessing();
		EventQueue.setImmediate(true);
		try {
			queue.postEvent(RunnableEvent.getInstance(() -> {
				executingThread.set(Thread.currentThread());
				delivered.countDown();
			}).asGuestCallback());
			assertTrue(delivered.await(1, java.util.concurrent.TimeUnit.SECONDS));
			org.junit.Assert.assertNotSame(postingThread, executingThread.get());
		} finally {
			EventQueue.setImmediate(false);
			queue.stopProcessing();
		}
	}

	@Test
	public void pendingHostEventIsReleasedByCancellationWithoutRunningAheadOfItsPosition()
			throws Exception {
		EventQueue queue = new EventQueue();
		java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicBoolean processedBeforeReady = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicInteger order = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
		queue.startProcessing();
		try {
			queue.postQueuedEvent(new SimpleEvent() {
				@Override
				public boolean isReady() { return ready.get(); }

				@Override
				public void process() {
					if (!ready.get()) processedBeforeReady.set(true);
					order.compareAndSet(0, 1);
				}
			});
			queue.postQueuedEvent(RunnableEvent.getInstance(() -> {
				if (order.get() == 1) order.set(2);
				completed.countDown();
			}).asGuestCallback());
			// The host cancels the mount: no visibility callback is required to release the queue.
			ready.set(true);
			queue.signalEventReady();
			assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
			assertFalse(processedBeforeReady.get());
			org.junit.Assert.assertEquals(2, order.get());
		} finally {
			queue.stopProcessing();
		}
	}

	@Test
	public void immediateEventExposesCallbackContextOnlyDuringProcessing() {
		boolean[] observed = {false};
		EventQueue.setImmediate(true);
		try {
			new EventQueue().postEvent(new SimpleEvent() {
				@Override
				public void process() {
					observed[0] = EventQueue.isInCallback();
				}
			});
		} finally {
			EventQueue.setImmediate(false);
		}

		assertTrue(observed[0]);
		assertFalse(EventQueue.isInCallback());
	}
}
