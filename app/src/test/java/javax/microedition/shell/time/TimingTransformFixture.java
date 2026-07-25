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

package javax.microedition.shell.time;

public final class TimingTransformFixture {
	private TimingTransformFixture() {
	}

	public static void joinMillis(Thread thread, long millis) throws InterruptedException {
		thread.join(millis);
	}

	public static void joinMillisNanos(Thread thread, long millis, int nanos)
			throws InterruptedException {
		thread.join(millis, nanos);
	}
}
