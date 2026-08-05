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

import android.graphics.Color;
import android.os.Looper;
import android.view.View;

import androidx.camera.view.PreviewView;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

/** Internal Android preview-view factory shared by GUI and direct JSR-135 modes. */
public final class CameraPreviewView {
	private static final long CREATE_TIMEOUT_MS = 10_000L;

	private CameraPreviewView() {
	}

	public static PreviewView create(int width, int height, boolean visible) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("preview dimensions must be positive");
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return createOnCurrentThread(width, height, visible);
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			throw new IllegalStateException("MIDlet Activity is unavailable");
		}
		FutureTask<PreviewView> task = new FutureTask<>(
				() -> createOnCurrentThread(width, height, visible));
		activity.runOnUiThread(task);
		try {
			return task.get(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Camera preview creation was interrupted", e);
		} catch (TimeoutException e) {
			task.cancel(true);
			throw new IllegalStateException("Camera preview creation timed out", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException("Camera preview creation failed", cause);
		}
	}

	public static void setSize(Object view, int width, int height) {
		if (!(view instanceof PreviewView) || width <= 0 || height <= 0) {
			return;
		}
		PreviewView previewView = (PreviewView) view;
		previewView.setMinimumWidth(width);
		previewView.setMinimumHeight(height);
		previewView.requestLayout();
	}

	public static void setVisible(Object view, boolean visible) {
		if (view instanceof View) {
			((View) view).setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
		}
	}

	private static PreviewView createOnCurrentThread(int width, int height, boolean visible) {
		PreviewView view = new PreviewView(ContextHolder.getActivity() != null
				? ContextHolder.getActivity() : ContextHolder.getAppContext());
		view.setBackgroundColor(Color.BLACK);
		view.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
		view.setScaleType(PreviewView.ScaleType.FILL_CENTER);
		setVisible(view, visible);
		view.setLayoutParams(new android.view.ViewGroup.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
		setSize(view, width, height);
		view.setClickable(false);
		view.setFocusable(false);
		return view;
	}
}
