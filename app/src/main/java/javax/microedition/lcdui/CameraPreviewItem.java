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

package javax.microedition.lcdui;

import android.view.View;

import androidx.camera.view.PreviewView;

import java.util.Objects;

/** LCDUI Item bridge for the JSR-135 camera preview. */
public final class CameraPreviewItem extends Item {
	private final PreviewCallback onAttached;
	private final PreviewCallback onDetached;
	private PreviewView previewView;
	private int previewWidth;
	private int previewHeight;
	private boolean visible = true;

	public CameraPreviewItem(int width, int height, PreviewCallback onAttached,
			PreviewCallback onDetached) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("preview dimensions must be positive");
		}
		this.previewWidth = width;
		this.previewHeight = height;
		this.onAttached = Objects.requireNonNull(onAttached, "onAttached");
		this.onDetached = Objects.requireNonNull(onDetached, "onDetached");
		// The GUI primitive is a fixed-size native view. Without SHRINK, the
		// Compose LCDUI bridge expands AndroidView to the full Form width and
		// CameraX derives a ViewPort from the wrong aspect ratio.
		setLayout(LAYOUT_SHRINK | LAYOUT_VSHRINK);
		setPreferredSize(width, height);
	}

	public void setPreviewSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("preview dimensions must be positive");
		}
		previewWidth = width;
		previewHeight = height;
		setPreferredSize(width, height);
		PreviewView view = previewView;
		if (view != null) {
			ViewHandler.postEvent(() -> applySize(view));
		}
	}

	public void setPreviewVisible(boolean visible) {
		this.visible = visible;
		PreviewView view = previewView;
		if (view != null) {
			ViewHandler.postEvent(() -> view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE));
		}
	}

	@Override
	View getItemContentView() {
		if (previewView == null) {
			PreviewView view = CameraPreviewView.create(previewWidth, previewHeight, visible);
			previewView = view;
			onAttached.onPreview(view);
		}
		return previewView;
	}

	@Override
	void clearItemContentView() {
		PreviewView view = previewView;
		previewView = null;
		if (view != null) {
			onDetached.onPreview(view);
		}
	}

	private void applySize(PreviewView view) {
		CameraPreviewView.setSize(view, previewWidth, previewHeight);
	}

	@FunctionalInterface
	public interface PreviewCallback {
		void onPreview(Object view);
	}
}