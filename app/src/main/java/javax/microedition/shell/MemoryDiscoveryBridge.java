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

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Parent-owned discovery bridge for managed guest-memory roots and converted class lifecycle tails.
 *
 * <p>Converted guest {@code <clinit>} methods publish completed class identities through
 * {@link #tailSeen(Class)}, while parent-owned display code can publish guest-defined
 * {@code Displayable} instances that escape into framework UI state. The bridge records only
 * identities and roots; managed guest-state traversal remains worker-side. Keeping this bridge in
 * the parent loader also prevents a guest archive from shadowing the converter ABI.</p>
 */
public final class MemoryDiscoveryBridge {
	private static final Object LOCK = new Object();
	private static final int MAX_TAIL_CLASSES = 4096;
	private static final Class<?>[] TAIL_CLASSES = new Class<?>[MAX_TAIL_CLASSES];
	private static long activeToken;
	private static ClassLoader activeLoader;
	private static Object midletRoot;
	private static WeakReference<Object> currentDisplayableRoot;
	private static int tailCount;
	private static boolean available;
	private static String failure;

	private MemoryDiscoveryBridge() {
	}

	/** Installs the registry for one runtime before the guest main class is loaded. */
	public static void install(long token, ClassLoader loader) {
		if (token == 0L || loader == null) {
			throw new IllegalArgumentException("A non-zero runtime token and guest loader are required");
		}
		synchronized (LOCK) {
			clearLocked();
			activeToken = token;
			activeLoader = loader;
			available = true;
		}
	}

	/** Publishes the successfully constructed MIDlet as the primary graph root for this runtime. */
	public static void setMidletRoot(long token, Object root) {
		synchronized (LOCK) {
			if (activeToken != token || token == 0L) {
				return;
			}
			if (root == null || root.getClass().getClassLoader() != activeLoader) {
				markUnavailableLocked("MIDlet root was not defined by the active guest loader");
				return;
			}
			midletRoot = root;
		}
	}

	/**
	 * Publishes a guest-defined Displayable that escaped into parent-owned UI state.
	 *
	 * <p>Parent-owned objects are ignored so transient framework screens do not replace the last
	 * guest root. The bridge retains this auxiliary root weakly because Display already owns any
	 * live screen.</p>
	 */
	public static void setCurrentDisplayable(Object displayable) {
		if (displayable == null) {
			return;
		}
		synchronized (LOCK) {
			if (activeToken == 0L || !available) {
				return;
			}
			ClassLoader loader;
			try {
				loader = displayable.getClass().getClassLoader();
			} catch (RuntimeException ignored) {
				return;
			}
			if (loader != activeLoader) {
				return;
			}
			Object current = currentDisplayableRoot == null ? null : currentDisplayableRoot.get();
			if (current != displayable) {
				currentDisplayableRoot = new WeakReference<>(displayable);
			}
		}
	}

	/**
	 * Called by converted guest code immediately before every normal {@code <clinit>} return.
	 * This method must stay allocation-free in the normal publication path.
	 */
	public static void tailSeen(Class<?> type) {
		if (type == null) {
			return;
		}
		synchronized (LOCK) {
			if (activeToken == 0L || !available) {
				return;
			}
			try {
				if (type.getClassLoader() != activeLoader) {
					markUnavailableLocked("A tail was published by a non-guest class loader");
					return;
				}
				for (int index = 0; index < tailCount; index++) {
					if (TAIL_CLASSES[index] == type) {
						return;
					}
				}
				if (tailCount >= TAIL_CLASSES.length) {
					markUnavailableLocked("The bounded managed class registry is full");
					return;
				}
				TAIL_CLASSES[tailCount++] = type;
			} catch (RuntimeException error) {
				markUnavailableLocked("Managed class publication failed");
			}
		}
	}

	/** Returns a detached snapshot. No caller may hold the registry lock during guest reads. */
	public static Snapshot snapshot(long token) {
		synchronized (LOCK) {
			if (token == 0L || activeToken != token) {
				return new Snapshot(token, null, null, null, new Class<?>[0], false,
						"Managed runtime token is not active");
			}
			Class<?>[] classes = Arrays.copyOf(TAIL_CLASSES, tailCount);
			Object displayableRoot =
					currentDisplayableRoot == null ? null : currentDisplayableRoot.get();
			boolean rootReady = midletRoot != null;
			String stateFailure = failure;
			if (!rootReady && stateFailure == null) {
				stateFailure = "MIDlet root has not been published";
			}
			return new Snapshot(token, activeLoader, midletRoot, displayableRoot, classes,
					available && rootReady, stateFailure);
		}
	}

	/** Returns whether the registry still belongs to {@code token}. */
	public static boolean isActive(long token) {
		synchronized (LOCK) {
			return token != 0L && activeToken == token && available;
		}
	}

	/** Invalidates only the registry owned by {@code token}; a newer runtime is left untouched. */
	public static void close(long token) {
		synchronized (LOCK) {
			if (token != 0L && activeToken == token) {
				clearLocked();
			}
		}
	}

	private static void markUnavailableLocked(String reason) {
		available = false;
		if (failure == null) {
			failure = reason;
		}
	}

	private static void clearLocked() {
		Arrays.fill(TAIL_CLASSES, 0, tailCount, null);
		tailCount = 0;
		activeToken = 0L;
		activeLoader = null;
		midletRoot = null;
		currentDisplayableRoot = null;
		available = false;
		failure = null;
	}

	/** Detached lifecycle state consumed by the managed target worker. */
	public static final class Snapshot {
		private final long token;
		private final ClassLoader loader;
		private final Object root;
		private final Object displayableRoot;
		private final Class<?>[] classes;
		private final boolean available;
		private final String failure;

		private Snapshot(long token, ClassLoader loader, Object root, Object displayableRoot,
		                 Class<?>[] classes, boolean available, String failure) {
			this.token = token;
			this.loader = loader;
			this.root = root;
			this.displayableRoot = displayableRoot;
			this.classes = classes;
			this.available = available;
			this.failure = failure;
		}

		public long token() {
			return token;
		}

		public ClassLoader loader() {
			return loader;
		}

		public Object root() {
			return root;
		}

		public Object displayableRoot() {
			return displayableRoot;
		}

		public Class<?>[] classes() {
			return classes.clone();
		}

		public boolean isAvailable() {
			return available;
		}

		public String failure() {
			return failure;
		}
	}
}
