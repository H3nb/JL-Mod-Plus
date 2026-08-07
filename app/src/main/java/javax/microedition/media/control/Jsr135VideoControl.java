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

package javax.microedition.media.control;

import javax.microedition.lcdui.CameraPreviewItem;
import javax.microedition.lcdui.CameraPreviewView;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.media.camera.MidletMediaPermissionGate;
import javax.microedition.media.camera.SnapshotEncodingParser;
import javax.microedition.media.camera.SnapshotRequest;
import javax.microedition.util.ContextHolder;

/** Concrete JSR-135 VideoControl for preview and still snapshots. */
public final class Jsr135VideoControl implements VideoControl {
	private static final String MIDP_ITEM_CLASS = "javax.microedition.lcdui.Item";

	private final CameraPlayer player;
	private final CaptureRequest request;

	private CameraPreviewItem previewItem;
	private Canvas directCanvas;
	private Object directPreviewView;
	private boolean initialized;
	private boolean visible;
	private boolean fullScreen;
	private int mode;
	private int displayX;
	private int displayY;
	private int displayWidth;
	private int displayHeight;
	private int windowedX;
	private int windowedY;
	private int windowedWidth;
	private int windowedHeight;

	public Jsr135VideoControl(CameraPlayer player, CaptureRequest request) {
		this.player = player;
		this.request = request;
	}

	@Override
	public synchronized Object initDisplayMode(int mode, Object arg) {
		if (initialized) {
			throw new IllegalStateException("display mode was already initialized");
		}
		this.mode = mode;
		this.displayWidth = request.getWidth();
		this.displayHeight = request.getHeight();
		this.windowedWidth = displayWidth;
		this.windowedHeight = displayHeight;
		if (mode == USE_GUI_PRIMITIVE) {
			if (arg != null && (!(arg instanceof String) || !MIDP_ITEM_CLASS.equals(arg))) {
				throw new IllegalArgumentException("Unsupported GUI primitive class");
			}
			visible = true;
			previewItem = new CameraPreviewItem(
					request.getWidth(), request.getHeight(), player::attachPreview, player::detachPreview);
		} else if (mode == USE_DIRECT_VIDEO) {
			if (!(arg instanceof Canvas)) {
				throw new IllegalArgumentException("Direct video mode requires a Canvas");
			}
			directCanvas = (Canvas) arg;
			// MMAPI direct video is hidden until setVisible(true) is called.
			visible = false;
			directPreviewView = CameraPreviewView.create(
					request.getWidth(), request.getHeight(), false);
			directCanvas.attachDirectVideoView(directPreviewView);
			directCanvas.setDirectVideoViewBounds(
					displayX, displayY, displayWidth, displayHeight);
			directCanvas.setDirectVideoViewVisible(false);
			player.attachPreview(directPreviewView);
		} else {
			throw new IllegalArgumentException("Unsupported VideoControl display mode");
		}
		initialized = true;
		return mode == USE_GUI_PRIMITIVE ? previewItem : null;
	}

	@Override
	public synchronized void setDisplayLocation(int x, int y) {
		checkInitialized();
		if (mode == USE_GUI_PRIMITIVE) {
			return;
		}
		displayX = x;
		displayY = y;
		if (!fullScreen) {
			windowedX = x;
			windowedY = y;
		}
		directCanvas.setDirectVideoViewBounds(displayX, displayY, displayWidth, displayHeight);
	}

	@Override
	public synchronized void setDisplaySize(int width, int height) throws MediaException {
		checkInitialized();
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("display dimensions must be positive");
		}
		applyDisplaySize(width, height);
		if (!fullScreen) {
			windowedWidth = width;
			windowedHeight = height;
		}
	}

	@Override
	public synchronized void setDisplayFullScreen(boolean fullScreenMode) throws MediaException {
		checkInitialized();
		if (fullScreenMode == fullScreen) {
			return;
		}
		if (!fullScreenMode) {
			fullScreen = false;
			if (mode == USE_DIRECT_VIDEO) {
				displayX = windowedX;
				displayY = windowedY;
				directCanvas.setDirectVideoViewBounds(
						displayX, displayY, windowedWidth, windowedHeight);
			}
			applyDisplaySize(windowedWidth, windowedHeight);
			return;
		}

		windowedX = displayX;
		windowedY = displayY;
		windowedWidth = displayWidth;
		windowedHeight = displayHeight;
		fullScreen = true;
		int width = Displayable.getVirtualWidth();
		int height = Displayable.getVirtualHeight();
		if (width <= 0 || height <= 0) {
			width = ContextHolder.getDisplayWidth();
			height = ContextHolder.getDisplayHeight();
		}
		if (mode == USE_DIRECT_VIDEO) {
			displayX = 0;
			displayY = 0;
			directCanvas.setDirectVideoViewBounds(0, 0, width, height);
		}
		applyDisplaySize(width, height);
	}

	@Override
	public synchronized void setVisible(boolean visible) {
		checkInitialized();
		this.visible = visible;
		if (mode == USE_GUI_PRIMITIVE) {
			previewItem.setPreviewVisible(visible);
		} else {
			directCanvas.setDirectVideoViewVisible(visible);
		}
	}

	@Override
	public int getSourceWidth() {
		return request.getWidth();
	}

	@Override
	public int getSourceHeight() {
		return request.getHeight();
	}

	@Override
	public synchronized int getDisplayX() {
		checkInitialized();
		return displayX;
	}

	@Override
	public synchronized int getDisplayY() {
		checkInitialized();
		return displayY;
	}

	@Override
	public synchronized int getDisplayWidth() {
		checkInitialized();
		return displayWidth;
	}

	@Override
	public synchronized int getDisplayHeight() {
		checkInitialized();
		return displayHeight;
	}

	@Override
	public synchronized byte[] getSnapshot(String imageType) throws MediaException {
		checkInitialized();
		// Validate the MMAPI request before prompting the user for permission.
		SnapshotRequest snapshot = SnapshotEncodingParser.parse(imageType);
		// Feature-phone implementations such as Sony Ericsson rotated Java snapshots
		// to match the Java viewfinder. This hint only affects an unspecified snapshot;
		// explicit width/height requests remain literal.
		player.getCameraConfiguration().setViewfinderSize(displayWidth, displayHeight);
		MidletMediaPermissionGate.requireSnapshotPermission();
		return player.takeSnapshot(snapshot);
	}

	public synchronized boolean isVisible() {
		return visible;
	}

	/** Reattaches the direct preview after Player deallocation/re-prefetch. */
	public synchronized void attachDirectPreview() {
		if (mode == USE_DIRECT_VIDEO && directCanvas != null && directPreviewView != null) {
			directCanvas.attachDirectVideoView(directPreviewView);
			directCanvas.setDirectVideoViewBounds(
					displayX, displayY, displayWidth, displayHeight);
			directCanvas.setDirectVideoViewVisible(visible);
			player.attachPreview(directPreviewView);
		}
	}

	/** Detaches the direct preview while retaining the control for a later prefetch. */
	public synchronized void detachDirectPreview() {
		if (mode == USE_DIRECT_VIDEO && directCanvas != null && directPreviewView != null) {
			player.detachPreview(directPreviewView);
			directCanvas.detachDirectVideoView(directPreviewView);
		}
	}

	private void applyDisplaySize(int width, int height) {
		if (mode == USE_GUI_PRIMITIVE) {
			previewItem.setPreviewSize(width, height);
		} else {
			directCanvas.setDirectVideoViewBounds(displayX, displayY, width, height);
		}
		displayWidth = width;
		displayHeight = height;
	}

	private void checkInitialized() {
		if (!initialized) {
			throw new IllegalStateException("display mode has not been initialized");
		}
	}
}
