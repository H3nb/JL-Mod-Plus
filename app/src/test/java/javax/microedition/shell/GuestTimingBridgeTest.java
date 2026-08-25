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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingMode;
import javax.microedition.shell.timing.TimingTimeSource;

public class GuestTimingBridgeTest {
	@Test
	public void bridgeUsesActiveSessionWallClockAndGeneration() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(time, 200, 12L);
		GuestTimingBridge.install(session);
		try {
			time.monotonicNanos = 500_000_000L;
			assertEquals(1_700_000_001_000L, GuestTimingBridge.currentTimeMillis());
			assertEquals(1_000_000_000L, GuestTimingBridge.nanoTime());
			assertSame(session, GuestTimingBridge.activeSession());
			assertEquals(12L, GuestTimingBridge.activeSession().generation());
		} finally {
			GuestTimingBridge.clear(session);
		}
		assertNull(GuestTimingBridge.activeSession());
	}

	@Test
	public void staleClearCannotRemoveReplacementSession() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession first = new TimingSession(time, 100, 1L);
		TimingSession second = new TimingSession(time, 100, 2L);
		GuestTimingBridge.install(first);
		GuestTimingBridge.clear(first);
		GuestTimingBridge.install(second);
		try {
			GuestTimingBridge.clear(first);
			assertSame(second, GuestTimingBridge.activeSession());
		} finally {
			GuestTimingBridge.clear(second);
		}
	}

	@Test
	public void dateAndCalendarFactoriesUseGuestWallClock() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(time, 200, 1L);
		GuestTimingBridge.install(session);
		try {
			time.monotonicNanos = 500_000_000L;
			assertEquals(1_700_000_001_000L, GuestTimingBridge.newDate().getTime());
			assertEquals(1_700_000_001_000L,
					GuestTimingBridge.calendarInstance().getTimeInMillis());
			assertEquals(50L, GuestTimingBridge.hostDelayMillis(100L));
		} finally {
			GuestTimingBridge.clear(session);
		}

		Date hostDate = GuestTimingBridge.newDate();
		Calendar hostCalendar = GuestTimingBridge.calendarInstance();
		assertTrue(Math.abs(System.currentTimeMillis() - hostDate.getTime()) < 1_000L);
		assertTrue(Math.abs(System.currentTimeMillis() - hostCalendar.getTimeInMillis()) < 1_000L);
	}

	@Test
	public void realWallClockModeKeepsDateAndCalendarOnHostTime() {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(
				time, 200, 1L, TimingMode.REAL_WALL_CLOCK);
		GuestTimingBridge.install(session);
		try {
			long before = System.currentTimeMillis();
			assertTrue(Math.abs(before - GuestTimingBridge.currentTimeMillis()) < 1_000L);
			assertTrue(Math.abs(before - GuestTimingBridge.newDate().getTime()) < 1_000L);
			assertTrue(Math.abs(before - GuestTimingBridge.calendarInstance().getTimeInMillis()) < 1_000L);
		} finally {
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void yieldCompatPreservesInterruptStatus() {
		Thread.currentThread().interrupt();
		try {
			GuestTimingBridge.yieldCompat();
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void bridgeFallsBackToHostClockAfterSessionClose() {
		TimingSession session = new TimingSession(new FakeTimeSource(1_700_000_000_000L), 200, 1L);
		GuestTimingBridge.install(session);
		try {
			session.close();
			long now = System.currentTimeMillis();
			assertTrue(Math.abs(now - GuestTimingBridge.currentTimeMillis()) < 1_000L);
			assertTrue(Math.abs(System.nanoTime() - GuestTimingBridge.nanoTime()) < 1_000_000_000L);
		} finally {
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void classNameRestoresGuestTimerIdentityIncludingArrays() {
		assertEquals("java.util.Timer",
				GuestTimingBridge.className(javax.microedition.shell.custom.Timer.class));
		assertEquals("java.util.TimerTask",
				GuestTimingBridge.className(javax.microedition.shell.custom.TimerTask.class));
		assertEquals("[Ljava.util.Timer;",
				GuestTimingBridge.className(
						javax.microedition.shell.custom.Timer[].class));
		assertEquals("[[Ljava.util.TimerTask;",
				GuestTimingBridge.className(
						javax.microedition.shell.custom.TimerTask[][].class));
		assertEquals("java.lang.String",
				GuestTimingBridge.className(String.class));
		assertEquals("class java.util.Timer",
				GuestTimingBridge.classToString(javax.microedition.shell.custom.Timer.class));
		assertEquals("int", GuestTimingBridge.classToString(Integer.TYPE));
	}

	@Test
	public void reflectionUsesCallerLoaderAndMapsTimerArrays() throws Exception {
		assertSame(javax.microedition.shell.custom.Timer.class,
				GuestTimingBridge.forName("java.util.Timer", GuestTimingBridgeTest.class));
		assertSame(javax.microedition.shell.custom.Timer[][].class,
				GuestTimingBridge.forName("[[Ljava.util.Timer;", GuestTimingBridgeTest.class));

		ChildOnlyLoader loader = new ChildOnlyLoader();
		Class<?> childOnly = loader.loadGuestClass();
		assertSame(childOnly, GuestTimingBridge.forName(childOnly.getName(), childOnly));
	}

	@Test
	public void reflectiveDateConstructionUsesGuestClock() throws Exception {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(time, 200, 1L);
		GuestTimingBridge.install(session);
		try {
			time.monotonicNanos = 500_000_000L;
			assertEquals(1_700_000_001_000L,
					((Date) GuestTimingBridge.newInstance(Date.class)).getTime());
		} finally {
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void callerAwareReflectiveDateConstructionUsesGuestClock() throws Exception {
		FakeTimeSource time = new FakeTimeSource(1_700_000_000_000L);
		TimingSession session = new TimingSession(time, 200, 1L);
		GuestTimingBridge.install(session);
		try {
			time.monotonicNanos = 500_000_000L;
			assertEquals(1_700_000_001_000L,
					((Date) GuestTimingBridge.newInstance(Date.class,
							GuestTimingBridgeTest.class)).getTime());
		} finally {
			GuestTimingBridge.clear(session);
		}
	}

	@Test
	public void callerAwareReflectiveConstructionRetainsPackageAccess() throws Exception {
		assertTrue(GuestTimingBridge.newInstance(PackagePrivateGuest.class,
				GuestTimingBridgeTest.class) instanceof PackagePrivateGuest);
	}

	private static final class GuestOnly {
	}

	static final class PackagePrivateGuest {
		PackagePrivateGuest() {
		}
	}

	private static final class ChildOnlyLoader extends ClassLoader {
		private final String guestName = GuestOnly.class.getName();
		private final byte[] guestBytes;

		ChildOnlyLoader() {
			super(GuestTimingBridgeTest.class.getClassLoader());
			String resourceName = guestName.replace('.', '/') + ".class";
			try (InputStream input = getParent().getResourceAsStream(resourceName);
				 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				if (input == null) {
					throw new AssertionError("Missing test class resource " + resourceName);
				}
				byte[] buffer = new byte[256];
				for (int read; (read = input.read(buffer)) != -1; ) {
					output.write(buffer, 0, read);
				}
				guestBytes = output.toByteArray();
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		}

		Class<?> loadGuestClass() throws ClassNotFoundException {
			return loadClass(guestName, false);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve)
				throws ClassNotFoundException {
			if (guestName.equals(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = defineClass(name, guestBytes, 0, guestBytes.length);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
			return super.loadClass(name, resolve);
		}
	}

	private static final class FakeTimeSource implements TimingTimeSource {
		private long monotonicNanos;
		private final long wallTimeMillis;

		FakeTimeSource(long wallTimeMillis) {
			this.wallTimeMillis = wallTimeMillis;
		}

		@Override
		public long monotonicNanos() {
			return monotonicNanos;
		}

		@Override
		public long wallTimeMillis() {
			return wallTimeMillis;
		}
	}
}
