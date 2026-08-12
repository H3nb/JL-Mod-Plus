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

package jlmod.platformfixture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/** Debug-only display fixture used by Android platform compatibility validation. */
public final class PlatformCompatMidlet extends MIDlet {
	public static final String CLASS_NAME = "jlmod.platformfixture.PlatformCompatMidlet";
	public static final String MARKER_PROPERTY = "JLMod-Platform-Marker";
	public static final String DISPLAY_PROPERTY = "JLMod-Platform-Display";
	public static final String DISPLAY_FORM = "form";

	@Override
	public void startApp() {
		String markerPath = getAppProperty(MARKER_PROPERTY);
		if (DISPLAY_FORM.equals(getAppProperty(DISPLAY_PROPERTY))) {
			Form form = new Form("Platform host displayable");
			form.append(new TextField("IME probe", "", 32, TextField.ANY));
			Display.getDisplay(this).setCurrent(form);
			writeMarker(markerPath, "form\n");
		} else {
			Display.getDisplay(this).setCurrent(new ProbeCanvas(markerPath));
		}
	}

	@Override
	public void pauseApp() {
	}

	@Override
	public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
	}

	private static final class ProbeCanvas extends Canvas {
		private final File marker;

		private ProbeCanvas(String markerPath) {
			marker = markerPath == null ? null : new File(markerPath);
		}

		@Override
		protected void paint(Graphics graphics) {
		}

		@Override
		protected void showNotify() {
			writeMarker("shown\n");
		}

		@Override
		protected void sizeChanged(int width, int height) {
			writeMarker("size=" + width + "x" + height + "\n");
		}

		@Override
		protected void pointerPressed(int x, int y) {
			writeMarker("pointer=" + x + "," + y + "\n");
		}

		private void writeMarker(String value) {
			PlatformCompatMidlet.writeMarker(marker == null ? null : marker.getAbsolutePath(), value);
		}
	}

	private static void writeMarker(String markerPath, String value) {
		if (markerPath == null) {
			return;
		}
		File marker = new File(markerPath);
		File parent = marker.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IllegalStateException("Unable to create platform fixture marker directory");
		}
		try (FileOutputStream output = new FileOutputStream(marker, true)) {
			output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			output.flush();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to write platform fixture marker", e);
		}
	}
}
