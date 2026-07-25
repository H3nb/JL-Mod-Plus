/*
 * Copyright 2026 H3NB
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

package org.microemu.android.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.junit.Test;

import javax.microedition.shell.time.EmulationSpeed;
import javax.microedition.shell.time.EmulationTime;
import javax.microedition.shell.time.TimingTransformFixture;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AndroidMethodVisitorTest {
	@Test
	public void instrumentedTimedJoinUsesVirtualTimeout() throws Exception {
		assertVirtualJoin("joinMillis", false);
	}

	@Test
	public void instrumentedTimedJoinNanosUsesVirtualTimeout() throws Exception {
		assertVirtualJoin("joinMillisNanos", true);
	}

	@Test
	public void instrumentedTimedWaitUsesVirtualTimeout() throws Exception {
		assertVirtualWait("waitMillis", false);
	}

	@Test
	public void instrumentedTimedWaitNanosUsesVirtualTimeout() throws Exception {
		assertVirtualWait("waitMillisNanos", true);
	}

	@Test
	public void instrumentedUntimedWaitRetainsNativeNotification() throws Exception {
		Class<?> transformed = loadTransformedFixture();
		Method method = transformed.getMethod("waitIndefinitely", Object.class);
		Object monitor = new Object();
		CountDownLatch entered = new CountDownLatch(1);
		AtomicBoolean returned = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			synchronized (monitor) {
				entered.countDown();
				try {
					invoke(method, new Object[]{monitor});
					returned.set(true);
				} catch (Throwable throwable) {
					failure.set(throwable);
				}
			}
		}, "instrumented-untimed-waiter");
		waiter.start();
		try {
			assertTrue(entered.await(1L, TimeUnit.SECONDS));
			waitForWaiting(waiter);
			synchronized (monitor) {
				monitor.notify();
			}
			waiter.join(1_000L);

			assertFalse(waiter.isAlive());
			assertTrue(returned.get());
			assertNull(failure.get());
		} finally {
			if (waiter.isAlive()) {
				waiter.interrupt();
				waiter.join(1_000L);
			}
		}
	}

	private static void assertVirtualJoin(String methodName, boolean nanos) throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Thread target = new Thread(() -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "instrumented-join-target");
		target.start();

		try {
			Class<?> transformed = loadTransformedFixture();
			Method method = nanos
					? transformed.getMethod(methodName, Thread.class, long.class, int.class)
					: transformed.getMethod(methodName, Thread.class, long.class);
			EmulationTime.setSpeed(EmulationSpeed.X16);
			long started = System.nanoTime();
			invoke(method, nanos
					? new Object[]{target, 1_000L, 1}
					: new Object[]{target, 1_000L});
			long elapsedNanos = System.nanoTime() - started;

			assertTrue("timed join was not virtualized", elapsedNanos < 800_000_000L);
			assertTrue(target.isAlive());
		} finally {
			release.countDown();
			target.join(1_000L);
			EmulationTime.setSpeed(EmulationSpeed.X1);
		}
		assertFalse(target.isAlive());
	}

	private static void assertVirtualWait(String methodName, boolean nanos) throws Exception {
		Class<?> transformed = loadTransformedFixture();
		Method method = nanos
				? transformed.getMethod(methodName, Object.class, long.class, int.class)
				: transformed.getMethod(methodName, Object.class, long.class);
		Object monitor = new Object();

		try {
			EmulationTime.setSpeed(EmulationSpeed.X16);
			long started = System.nanoTime();
			synchronized (monitor) {
				invoke(method, nanos
						? new Object[]{monitor, 1_000L, 1}
						: new Object[]{monitor, 1_000L});
			}
			long elapsedNanos = System.nanoTime() - started;

			assertTrue("timed wait was not virtualized", elapsedNanos < 800_000_000L);
		} finally {
			EmulationTime.setSpeed(EmulationSpeed.X1);
		}
	}

	private static Class<?> loadTransformedFixture() throws Exception {
		String resource = TimingTransformFixture.class.getName().replace('.', '/') + ".class";
		byte[] original;
		try (var input = TimingTransformFixture.class.getClassLoader().getResourceAsStream(resource)) {
			if (input == null) {
				throw new AssertionError("Fixture class resource is missing: " + resource);
			}
			original = input.readAllBytes();
		}
		ClassReader reader = new ClassReader(original);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		reader.accept(new AndroidClassVisitor(writer), ClassReader.SKIP_DEBUG);
		byte[] transformed = writer.toByteArray();
		return new FixtureClassLoader().define(transformed);
	}

	private static void invoke(Method method, Object[] arguments) throws Exception {
		try {
			method.invoke(null, arguments);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception exception) {
				throw exception;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw e;
		}
	}

	private static void waitForWaiting(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
		while (thread.isAlive() && thread.getState() != Thread.State.WAITING
				&& System.nanoTime() < deadline) {
			Thread.yield();
		}
		assertTrue(thread.getState() == Thread.State.WAITING);
	}

	private static final class FixtureClassLoader extends ClassLoader {
		FixtureClassLoader() {
			super(TimingTransformFixture.class.getClassLoader());
		}

		Class<?> define(byte[] classData) {
			return defineClass(null, classData, 0, classData.length);
		}
	}
}
