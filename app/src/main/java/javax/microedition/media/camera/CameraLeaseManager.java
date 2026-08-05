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

package javax.microedition.media.camera;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.media.MediaException;

/** Process-local lease preventing conflicting camera sessions in the MIDlet host. */
public final class CameraLeaseManager {
	private static final AtomicBoolean LEASED = new AtomicBoolean();

	private CameraLeaseManager() {
	}

	public static Lease acquire() throws MediaException {
		if (!LEASED.compareAndSet(false, true)) {
			throw new MediaException("Camera is busy in another Player");
		}
		return new Lease();
	}

	public static final class Lease implements AutoCloseable {
		private boolean released;

		private Lease() {
		}

		@Override
		public synchronized void close() {
			if (!released) {
				released = true;
				LEASED.set(false);
			}
		}
	}
}
