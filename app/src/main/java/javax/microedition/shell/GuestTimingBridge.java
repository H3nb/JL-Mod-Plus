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

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.locks.LockSupport;

import javax.microedition.shell.timing.AutoSpeedController;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingMode;
import javax.microedition.shell.timing.TimingSnapshot;

/**
 * Parent-classloader-owned ABI for transformed guest timing calls. Guest archives must never
 * provide or shadow this class.
 */
public final class GuestTimingBridge {
	private static final Object LOCK = new Object();
	private static TimingSession activeSession;
	private static AutoSpeedController activeSpeedController;

	private GuestTimingBridge() {
	}

	/** Installs the only active session for the current MIDlet process. */
	public static void install(TimingSession session) {
		install(session, null);
	}

	/** Installs a session and its optional host-owned runtime speed controller. */
	public static void install(
			TimingSession session, @Nullable AutoSpeedController speedController) {
		if (session == null) {
			throw new NullPointerException("session");
		}
		synchronized (LOCK) {
			if (activeSession != null && activeSession != session && !activeSession.isClosed()) {
				throw new IllegalStateException("A timing session is already active");
			}
			activeSession = session;
			activeSpeedController = speedController;
		}
	}

	/** Clears only the session that the caller owns, preventing stale teardown from clearing a new one. */
	public static void clear(@Nullable TimingSession session) {
		synchronized (LOCK) {
			if (activeSession == session) {
				activeSession = null;
				AutoSpeedController speedController = activeSpeedController;
				activeSpeedController = null;
				if (speedController != null) {
					speedController.close();
				}
				if (session != null) {
					session.close();
				}
			}
		}
	}

	/** Returns the controller owned by the active transformed session, if available. */
	@Nullable
	public static AutoSpeedController activeSpeedController() {
		synchronized (LOCK) {
			return activeSpeedController;
		}
	}

	@Nullable
	public static TimingSession activeSession() {
		synchronized (LOCK) {
			return activeSession;
		}
	}

	/** Replacement for transformed guest java.lang.System.currentTimeMillis(). */
	public static long currentTimeMillis() {
		TimingSnapshot snapshot = activeSnapshot();
		return snapshot == null || snapshot.timingMode() == TimingMode.REAL_WALL_CLOCK
				? System.currentTimeMillis() : snapshot.guestWallTimeMillis();
	}

	/** Replacement for transformed guest java.lang.System.nanoTime(). */
	public static long nanoTime() {
		TimingSnapshot snapshot = activeSnapshot();
		return snapshot == null ? System.nanoTime() : snapshot.guestMonotonicNanos();
	}

	/** Replacement for a transformed no-argument java.util.Date constructor. */
	public static Date newDate() {
		return new Date(currentTimeMillis());
	}

	/** Replacement for a transformed no-argument java.util.Calendar factory. */
	public static Calendar calendarInstance() {
		return calendarAtGuestTime(Calendar.getInstance(), activeSnapshot());
	}

	/** Replacement for a transformed time-zone-aware java.util.Calendar factory. */
	public static Calendar calendarInstance(TimeZone timeZone) {
		return calendarAtGuestTime(Calendar.getInstance(timeZone), activeSnapshot());
	}

	/** Resolves legacy reflection names after java.util.Timer call-site remapping. */
	public static Class<?> forName(String name) throws ClassNotFoundException {
		return forName(name, null);
	}

	/**
	 * Resolves a guest reflection name using the loader that owns the transformed caller. A
	 * parent-owned bridge cannot use its own loader as a fallback: the MIDlet class may exist only
	 * in the child AppClassLoader. The one-argument overload remains for already converted
	 * archives, whose loader is supplied through the thread context by MicroLoader.
	 */
	public static Class<?> forName(String name, @Nullable Class<?> caller)
			throws ClassNotFoundException {
		if (name == null) {
			throw new NullPointerException("name");
		}
		String mappedName = mapReflectionName(name);
		ClassLoader loader = caller == null
				? Thread.currentThread().getContextClassLoader()
				: caller.getClassLoader();
		if (loader == null) {
			loader = GuestTimingBridge.class.getClassLoader();
		}
		return Class.forName(mappedName, true, loader);
	}

	/**
	 * Preserves the guest binary name for classes whose bytecode identity was remapped to the
	 * parent-owned Timer implementation. This is the instance-side counterpart of forName().
	 */
	public static String className(Class<?> type) {
		if (type == null) {
			throw new NullPointerException("type");
		}
		if (type == javax.microedition.shell.custom.Timer.class) {
			return "java.util.Timer";
		}
		if (type == javax.microedition.shell.custom.TimerTask.class) {
			return "java.util.TimerTask";
		}
		return mapGuestArrayName(type.getName());
	}

	/** Replacement for transformed Class.toString(), preserving the guest Timer namespace. */
	public static String classToString(Class<?> type) {
		if (type == null) {
			throw new NullPointerException("type");
		}
		String name = className(type);
		if (type.isPrimitive()) {
			return name;
		}
		return (type.isInterface() ? "interface " : "class ") + name;
	}

	/**
	 * Legacy ABI retained for already converted archives. New transforms use the caller-aware
	 * overload below so the guest caller's access checks remain intact.
	 */
	@SuppressWarnings("deprecation")
	public static Object newInstance(Class<?> type)
			throws InstantiationException, IllegalAccessException {
		if (type == null) {
			throw new NullPointerException("type");
		}
		if (type == Date.class) {
			return newDate();
		}
		return type.newInstance();
	}

	/**
	 * Caller-aware replacement for newly transformed Class.newInstance() calls. The caller token
	 * keeps access checks relative to the guest class instead of this parent-owned bridge. Date is
	 * the one special case: its empty constructor must use the guest clock, just like a direct
	 * {@code new Date()} call.
	 */
	public static Object newInstance(Class<?> type, @Nullable Class<?> caller)
			throws InstantiationException, IllegalAccessException {
		if (type == null) {
			throw new NullPointerException("type");
		}
		if (type == Date.class) {
			return newDate();
		}

		if (type.isPrimitive() || type.isArray() || type.isInterface()
				|| Modifier.isAbstract(type.getModifiers())) {
			throw new InstantiationException(type.getName());
		}

		Constructor<?> constructor;
		try {
			constructor = type.getDeclaredConstructor();
		} catch (NoSuchMethodException error) {
			InstantiationException failure = new InstantiationException(type.getName());
			failure.initCause(error);
			throw failure;
		}
		if (!isAccessibleFrom(constructor, type, caller)) {
			throw new IllegalAccessException(type.getName());
		}

		try {
			if (!Modifier.isPublic(type.getModifiers())
					|| !Modifier.isPublic(constructor.getModifiers())) {
				constructor.setAccessible(true);
			}
			return constructor.newInstance();
		} catch (InvocationTargetException error) {
			return GuestTimingBridge.<RuntimeException>rethrow(error.getCause());
		} catch (SecurityException error) {
			IllegalAccessException failure = new IllegalAccessException(type.getName());
			failure.initCause(error);
			throw failure;
		}
	}

	private static boolean isAccessibleFrom(
			Constructor<?> constructor, Class<?> type, @Nullable Class<?> caller) {
		int constructorModifiers = constructor.getModifiers();
		if (caller == null) {
			return Modifier.isPublic(type.getModifiers())
					&& Modifier.isPublic(constructorModifiers);
		}
		if (!isTypeAccessibleFrom(type, caller)) {
			return false;
		}
		if (Modifier.isPublic(constructorModifiers)) {
			return true;
		}
		if (sameNest(caller, type)) {
			return true;
		}
		if (Modifier.isPrivate(constructorModifiers)) {
			return false;
		}
		return sameRuntimePackage(caller, type)
				|| (Modifier.isProtected(constructorModifiers) && type.isAssignableFrom(caller));
	}

	private static boolean isTypeAccessibleFrom(Class<?> type, Class<?> caller) {
		if (Modifier.isPublic(type.getModifiers())) {
			return true;
		}
		if (sameNest(caller, type) || sameRuntimePackage(caller, type)) {
			return true;
		}
		return Modifier.isProtected(type.getModifiers()) && type.isAssignableFrom(caller);
	}

	/** Java ME targets predate nestmate APIs, so derive the class-file nest host from nesting. */
	private static boolean sameNest(Class<?> left, Class<?> right) {
		if (left.getClassLoader() != right.getClassLoader()) {
			return false;
		}
		return nestHost(left) == nestHost(right);
	}

	private static Class<?> nestHost(Class<?> type) {
		Class<?> enclosing = type.getEnclosingClass();
		while (enclosing != null) {
			type = enclosing;
			enclosing = type.getEnclosingClass();
		}
		return type;
	}

	private static boolean sameRuntimePackage(Class<?> left, Class<?> right) {
		if (left.getClassLoader() != right.getClassLoader()) {
			return false;
		}
		Package leftPackage = left.getPackage();
		Package rightPackage = right.getPackage();
		String leftName = leftPackage == null ? "" : leftPackage.getName();
		String rightName = rightPackage == null ? "" : rightPackage.getName();
		return leftName.equals(rightName);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> Object rethrow(Throwable error) throws T {
		throw (T) error;
	}

	private static String mapReflectionName(String name) {
		if ("java.util.Timer".equals(name)) {
			return javax.microedition.shell.custom.Timer.class.getName();
		}
		if ("java.util.TimerTask".equals(name)) {
			return javax.microedition.shell.custom.TimerTask.class.getName();
		}
		return name
				.replace("[Ljava.util.Timer;",
						"[L" + javax.microedition.shell.custom.Timer.class.getName() + ";")
				.replace("[Ljava.util.TimerTask;",
						"[L" + javax.microedition.shell.custom.TimerTask.class.getName() + ";");
	}

	private static String mapGuestArrayName(String name) {
		return name
				.replace("[L" + javax.microedition.shell.custom.Timer.class.getName() + ";",
						"[Ljava.util.Timer;")
				.replace("[L" + javax.microedition.shell.custom.TimerTask.class.getName() + ";",
						"[Ljava.util.TimerTask;");
	}

	/** Converts a guest millisecond deadline for an Android host callback. */
	public static long hostDelayMillis(long guestMillis) {
		TimingSession session = activeSession();
		return session == null ? guestMillis : session.hostDelayMillis(guestMillis);
	}

	private static Calendar calendarAtGuestTime(Calendar calendar, TimingSnapshot snapshot) {
		if (snapshot != null && snapshot.timingMode() == TimingMode.FULL_GUEST_TIME) {
			calendar.setTimeInMillis(snapshot.guestWallTimeMillis());
		}
		return calendar;
	}

	/**
	 * Selects and reads the active session as one teardown-tolerant operation. A session can be
	 * closed by lifecycle cleanup immediately after a transformed call selects it; snapshotIfOpen
	 * makes that stale call fall back to the host clock instead of leaking IllegalStateException.
	 */
	private static TimingSnapshot activeSnapshot() {
		synchronized (LOCK) {
			TimingSession session = activeSession;
			return session == null ? null : session.snapshotIfOpen();
		}
	}

	/**
	 * Compatibility replacement for transformed Thread.yield(). It never declares or throws
	 * InterruptedException, and LockSupport preserves an existing interrupt status.
	 */
	public static void yieldCompat() {
		LockSupport.parkNanos(1_000_000L);
	}

	/** Replacement for transformed guest java.lang.Thread.sleep(long). */
	public static void sleep(long guestMillis) throws InterruptedException {
		TimingSession session = activeSession();
		if (session == null) {
			Thread.sleep(guestMillis);
		} else {
			session.sleep(guestMillis);
		}
	}

	/** Replacement for transformed guest java.lang.Thread.sleep(long, int). */
	public static void sleep(long guestMillis, int guestNanos) throws InterruptedException {
		TimingSession session = activeSession();
		if (session == null) {
			Thread.sleep(guestMillis, guestNanos);
		} else {
			session.sleep(guestMillis, guestNanos);
		}
	}

	/** Replacement for transformed finite java.lang.Object.wait(long). */
	public static void waitOnMonitor(Object monitor, long guestMillis)
			throws InterruptedException {
		if (monitor == null) {
			throw new NullPointerException("monitor");
		}
		TimingSession session = activeSession();
		if (session == null) {
			monitor.wait(guestMillis);
		} else {
			session.waitOnMonitor(monitor, guestMillis);
		}
	}

	/** Replacement for transformed finite java.lang.Object.wait(long, int). */
	public static void waitOnMonitor(Object monitor, long guestMillis, int guestNanos)
			throws InterruptedException {
		if (monitor == null) {
			throw new NullPointerException("monitor");
		}
		TimingSession session = activeSession();
		if (session == null) {
			monitor.wait(guestMillis, guestNanos);
		} else {
			session.waitOnMonitor(monitor, guestMillis, guestNanos);
		}
	}
}
