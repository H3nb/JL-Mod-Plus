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

package jlmod.runtimefixture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

/** Debug-only MIDlet used by hosted runtime validation. */
public final class LifecycleMidlet extends MIDlet {
	public static final String CLASS_NAME = "jlmod.runtimefixture.LifecycleMidlet";
	public static final String MODE_PROPERTY = "JLMod-Runtime-Mode";
	public static final String MARKER_PROPERTY = "JLMod-Runtime-Marker";
	public static final String MODE_CRASH_INIT = "crash-init";
	public static final String MODE_CRASH_START = "crash-start";
	public static final String MODE_CRASH_WORKER = "crash-worker";
	public static final String MODE_CRASH_PAUSE = "crash-pause";
	public static final String MODE_CRASH_DESTROY = "crash-destroy";
	public static final String MODE_CLEAN = "clean";
	public static final String MODE_WORKER_DESTROY = "worker-destroy";
	public static final String MODE_HOLD = "hold";
	public static final String MODE_BACKGROUND = "background";
	public static final String MODE_END_KEY_BACKGROUND = "end-key-background";
	public static final String MODE_CSI_STYLE_EXIT = "csi-style-exit";
	public static final String MODE_STORAGE_LEASE = "storage-lease";
	public static final String MODE_FILE_CONNECTION = "file-connection-root";
	public static final String STORAGE_TRIGGER_PROPERTY = "JLMod-Storage-Trigger";
	public static final String STORAGE_WRITTEN_PROPERTY = "JLMod-Storage-Written";
	private volatile boolean destroyed;
	private boolean backgroundRequested;
	private Canvas retainedCanvas;
	public static final String INIT_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture init failure";
	public static final String START_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture start failure";
	public static final String WORKER_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture worker failure";
	public static final String PAUSE_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture pause failure";
	public static final String DESTROY_FAILURE_MARKER = "JL-Mod Plus lifecycle runtime fixture destroy failure";

	public LifecycleMidlet() {
		if (MODE_CRASH_INIT.equals(getAppProperty(MODE_PROPERTY))) {
			throw new IllegalStateException(INIT_FAILURE_MARKER);
		}
	}

	@Override
	public void startApp() {
		String mode = getAppProperty(MODE_PROPERTY);
		if (MODE_CRASH_START.equals(mode)) {
			throw new IllegalStateException(START_FAILURE_MARKER);
		}
		if (MODE_CRASH_WORKER.equals(mode)) {
			new Thread(() -> {
				try {
					Thread.sleep(100L);
				} catch (InterruptedException ignored) {}
				throw new IllegalStateException(WORKER_FAILURE_MARKER);
			}, "LifecycleFixtureWorker").start();
			return;
		}
		if (MODE_CRASH_PAUSE.equals(mode) || MODE_CRASH_DESTROY.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			return;
		}
		if (MODE_CLEAN.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			notifyDestroyed();
			return;
		}
		if (MODE_WORKER_DESTROY.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			new Thread(this::notifyDestroyed, "LifecycleFixtureDestroyWorker").start();
			return;
		}
		if (MODE_HOLD.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			return;
		}
		if (MODE_BACKGROUND.equals(mode)) {
			Display display = Display.getDisplay(this);
			if (backgroundRequested) {
				if (display.getCurrent() != retainedCanvas) {
					throw new IllegalStateException("Background request lost current Displayable");
				}
				return;
			}
			retainedCanvas = new Canvas() {
				@Override
				protected void paint(Graphics graphics) {
				}
			};
			display.setCurrent(retainedCanvas);
			writeMarker(getAppProperty(MARKER_PROPERTY));
			backgroundRequested = true;
			display.setCurrent(null);
			return;
		}
		if (MODE_END_KEY_BACKGROUND.equals(mode)) {
			Display display = Display.getDisplay(this);
			display.setCurrent(new Canvas() {
				@Override
				protected void paint(Graphics graphics) {
				}

				@Override
				protected void keyPressed(int keyCode) {
					if (keyCode == KEY_END) {
						writeMarker(getAppProperty(MARKER_PROPERTY));
						Display.getDisplay(LifecycleMidlet.this).setCurrent(null);
					}
				}
			});
			writeMarker(getAppProperty(MARKER_PROPERTY));
			return;
		}
		if (MODE_CSI_STYLE_EXIT.equals(mode)) {
			Display display = Display.getDisplay(this);
			display.setCurrent(new Canvas() {
				@Override
				protected void paint(Graphics graphics) {
				}
			});
			writeMarker(getAppProperty(MARKER_PROPERTY));
			try {
				destroyApp(true);
			} catch (MIDletStateChangeException impossible) {
				throw new IllegalStateException(impossible);
			}
			display.setCurrent(null);
			notifyDestroyed();
			return;
		}
		if (MODE_STORAGE_LEASE.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
			String triggerPath = getAppProperty(STORAGE_TRIGGER_PROPERTY);
			String writtenPath = getAppProperty(STORAGE_WRITTEN_PROPERTY);
			new Thread(() -> {
				while (!destroyed) {
					if (triggerPath != null && new File(triggerPath).exists()) {
						try (FileOutputStream output = ContextHolder.openFileOutput("old-save.rms")) {
							output.write(17);
							output.flush();
							writeMarker(writtenPath);
						} catch (IOException failure) {
							throw new IllegalStateException("Unable to write old private data", failure);
						}
						return;
					}
					try {
						Thread.sleep(50L);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}, "StorageLeaseFixtureWriter").start();
			return;
		}
		if (MODE_FILE_CONNECTION.equals(mode)) {
			String privateUri = System.getProperty("fileconn.dir.private");
			String cacheUri = System.getProperty("fileconn.dir.cache");
			FileConnection connection = null;
			try {
				connection = (FileConnection) Connector.open(
						privateUri + "/identity.bin", Connector.READ_WRITE);
				if (!connection.exists()) connection.create();
				try (OutputStream output = connection.openOutputStream()) {
					output.write(37);
				}
				writeReport(getAppProperty(MARKER_PROPERTY), privateUri + "\n" + cacheUri);
			} catch (IOException failure) {
				throw new IllegalStateException("Unable to write FileConnection private data", failure);
			} finally {
				if (connection != null) {
					try { connection.close(); } catch (IOException ignored) {}
				}
			}
			return;
		}
		throw new IllegalStateException("Unknown lifecycle runtime fixture mode: " + mode);
	}

	@Override
	public void pauseApp() {
		String mode = getAppProperty(MODE_PROPERTY);
		if (MODE_CRASH_PAUSE.equals(mode)) {
			throw new IllegalStateException(PAUSE_FAILURE_MARKER);
		}
		if (MODE_BACKGROUND.equals(mode)) {
			writeMarker(getAppProperty(MARKER_PROPERTY));
		}
	}

	@Override
	public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
		String mode = getAppProperty(MODE_PROPERTY);
		if (MODE_CSI_STYLE_EXIT.equals(mode) && destroyed) {
			throw new IllegalStateException("CSI-style fixture destroyed twice");
		}
		destroyed = true;
		if (MODE_CRASH_DESTROY.equals(mode)) {
			throw new IllegalStateException(DESTROY_FAILURE_MARKER);
		}
	}

	private static void writeMarker(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalStateException("Lifecycle runtime fixture marker path is missing");
		}
		File marker = new File(path);
		File parent = marker.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IllegalStateException("Unable to create lifecycle fixture marker directory");
		}
		try (FileOutputStream output = new FileOutputStream(marker)) {
			output.write(1);
			output.flush();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to write lifecycle fixture marker", e);
		}
	}

	private static void writeReport(String path, String value) throws IOException {
		if (path == null || path.isEmpty()) throw new IOException("Missing report path");
		try (FileOutputStream output = new FileOutputStream(path)) {
			output.write(value.getBytes(StandardCharsets.UTF_8));
		}
	}
}
