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

import android.os.Looper;
import android.util.Rational;
import android.util.Size;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.media.MediaException;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

/** CameraX backend for JSR-135 preview and JPEG still capture. */
public final class CameraXCameraSession implements CameraSession {
	private static final long OPERATION_TIMEOUT_MS = 20_000L;

	private final CaptureRequest request;
	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "J2ME-CameraX");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean captureInProgress = new AtomicBoolean();

	private volatile PreviewView previewView;
	private ProcessCameraProvider cameraProvider;
	private Preview preview;
	private ImageCapture imageCapture;
	private boolean prepared;
	private boolean started;
	private boolean bound;
	private boolean previewBound;
	private FileCaptureResult activeCapture;

	public CameraXCameraSession(CaptureRequest request) {
		this.request = request;
	}

	@Override
	public void prepare() throws MediaException {
		MicroActivity activity = requireActivity();
		synchronized (this) {
			if (prepared) {
				return;
			}
		}
		try {
			ListenableFuture<ProcessCameraProvider> providerFuture =
					ProcessCameraProvider.getInstance(activity);
			ProcessCameraProvider provider = providerFuture.get(
					OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			selectCamera(provider);
			ImageCapture capture = new ImageCapture.Builder()
					.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
					.setJpegQuality(CameraRuntimeConfig.jpegQuality())
					.setTargetResolution(new Size(
							CaptureRequest.PHYSICAL_CAPTURE_WIDTH,
							CaptureRequest.PHYSICAL_CAPTURE_HEIGHT))
					.build();
			Preview cameraPreview = new Preview.Builder()
					.setTargetResolution(new Size(request.getWidth(), request.getHeight()))
					.build();
			synchronized (this) {
				cameraProvider = provider;
				imageCapture = capture;
				preview = cameraPreview;
				prepared = true;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Camera preparation was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Camera provider did not become ready");
		} catch (ExecutionException | RuntimeException e) {
			throw new MediaException("Camera provider is unavailable: " + e.getMessage());
		}
	}

	@Override
	public void attachPreview(Object view) {
		if (!(view instanceof PreviewView)) {
			return;
		}
		PreviewView newView = (PreviewView) view;
		PreviewView oldView;
		boolean rebind;
		synchronized (this) {
			oldView = previewView;
			previewView = newView;
			rebind = started && oldView != newView;
		}
		if (rebind) {
			rebindPreview();
		}
	}

	@Override
	public void detachPreview(Object view) {
		if (!(view instanceof PreviewView)) {
			return;
		}
		boolean rebind;
		synchronized (this) {
			if (previewView != view) {
				return;
			}
			previewView = null;
			rebind = started;
		}
		if (rebind) {
			rebindPreview();
		}
	}

	@Override
	public void start() throws MediaException {
		final MicroActivity activity;
		synchronized (this) {
			if (!prepared || cameraProvider == null || imageCapture == null || preview == null) {
				throw new MediaException("Camera session is not prepared");
			}
			if (started) {
				return;
			}
			activity = requireActivity();
		}
		onMainThread(activity, () -> {
			synchronized (CameraXCameraSession.this) {
				bindUseCases(activity);
				started = true;
			}
			return null;
		});
	}

	@Override
	public void stop() throws MediaException {
		final MicroActivity activity;
		synchronized (this) {
			if (!started) {
				return;
			}
			activity = requireActivity();
		}
		onMainThread(activity, () -> {
			synchronized (CameraXCameraSession.this) {
				unbindUseCases();
				started = false;
			}
			return null;
		});
	}

	@Override
	public byte[] capture(SnapshotRequest snapshotRequest) throws MediaException {
		final ImageCapture capture;
		FileCaptureResult result = new FileCaptureResult();
		synchronized (this) {
			if (!started || imageCapture == null) {
				throw new MediaException("Camera Player is not started");
			}
			if (!captureInProgress.compareAndSet(false, true)) {
				throw new MediaException("Another camera snapshot is already in progress");
			}
			capture = imageCapture;
			activeCapture = result;
		}

		File output = null;
		try {
			File captureFile = File.createTempFile("jlmod-camera-", ".jpg", ContextHolder.getCacheDir());
			output = captureFile;
			ImageCapture.OutputFileOptions options =
					new ImageCapture.OutputFileOptions.Builder(captureFile).build();
			capture.takePicture(options, callbackExecutor, new ImageCapture.OnImageSavedCallback() {
				@Override
				public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
					result.complete(captureFile);
				}

				@Override
				public void onError(ImageCaptureException exception) {
					result.completeExceptionally(exception);
				}
			});
			File captured = result.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			return SnapshotPipeline.encodeJpeg(captured, snapshotRequest);
		} catch (IOException e) {
			throw new MediaException("Camera snapshot file could not be created");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Camera snapshot was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Camera snapshot timed out");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new MediaException("Camera snapshot failed: "
					+ (cause == null ? "unknown error" : cause.getMessage()));
		} catch (RuntimeException e) {
			throw new MediaException("Camera snapshot was cancelled or unavailable");
		} finally {
			synchronized (this) {
				if (activeCapture == result) {
					activeCapture = null;
				}
			}
			captureInProgress.set(false);
			if (output != null && output.exists() && !output.delete()) {
				output.deleteOnExit();
			}
		}
	}

	@Override
	public void release() {
		FileCaptureResult pending;
		synchronized (this) {
			pending = activeCapture;
			activeCapture = null;
		}
		if (pending != null) {
			pending.completeExceptionally(new IOException("Camera snapshot was cancelled"));
		}
		try {
			stop();
		} catch (MediaException ignored) {
			// Activity destruction can invalidate the UI executor; release is best effort.
		}
		synchronized (this) {
			cameraProvider = null;
			imageCapture = null;
			preview = null;
			prepared = false;
			started = false;
			bound = false;
			previewBound = false;
		}
		callbackExecutor.shutdownNow();
	}

	private void rebindPreview() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			return;
		}
		try {
			onMainThread(activity, () -> {
				synchronized (CameraXCameraSession.this) {
					if (started) {
						unbindUseCases();
						bindUseCases(activity);
					}
				}
				return null;
			});
		} catch (MediaException ignored) {
			// The Player remains recoverable; a later operation reports availability.
		}
	}

	private void bindUseCases(MicroActivity activity) {
		if (bound) {
			return;
		}
		PreviewView view = previewView;
		CameraSelector selector = selectCamera(cameraProvider);
		if (view != null) {
			int targetRotation = view.getDisplay() != null
					? view.getDisplay().getRotation() : preview.getTargetRotation();
			preview.setTargetRotation(targetRotation);
			imageCapture.setTargetRotation(targetRotation);
			preview.setSurfaceProvider(view.getSurfaceProvider());

			ViewPort viewPort = view.getViewPort(targetRotation);
			if (viewPort == null) {
				int width = view.getWidth() > 0 ? view.getWidth()
						: Math.max(1, view.getMinimumWidth());
				int height = view.getHeight() > 0 ? view.getHeight()
						: Math.max(1, view.getMinimumHeight());
				if (width <= 1 || height <= 1) {
					width = Math.max(1, request.getWidth());
					height = Math.max(1, request.getHeight());
				}
				viewPort = new ViewPort.Builder(new Rational(width, height), targetRotation).build();
			}

			UseCaseGroup useCaseGroup = new UseCaseGroup.Builder()
					.addUseCase(preview)
					.addUseCase(imageCapture)
					.setViewPort(viewPort)
					.build();
			cameraProvider.bindToLifecycle(activity, selector, useCaseGroup);
			previewBound = true;
		} else {
			cameraProvider.bindToLifecycle(activity, selector, imageCapture);
			previewBound = false;
		}
		bound = true;
	}

	private CameraSelector selectCamera(ProcessCameraProvider provider) {
		try {
			LogicalCameraDevice requested = request.getLogicalCameraDevice() == LogicalCameraDevice.DEFAULT
					? CameraRuntimeConfig.defaultDevice() : request.getLogicalCameraDevice();
			return switch (requested) {
				case REAR -> requireCamera(provider, CameraSelector.DEFAULT_BACK_CAMERA, "rear");
				case FRONT -> requireCamera(provider, CameraSelector.DEFAULT_FRONT_CAMERA, "front");
				case DEFAULT -> {
					if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
						yield CameraSelector.DEFAULT_BACK_CAMERA;
					}
					if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
						yield CameraSelector.DEFAULT_FRONT_CAMERA;
					}
					throw new IllegalStateException("No usable camera is available");
				}
			};
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Camera availability could not be queried", e);
		}
	}

	private static CameraSelector requireCamera(ProcessCameraProvider provider,
			CameraSelector selector, String name) throws Exception {
		if (!provider.hasCamera(selector)) {
			throw new IllegalStateException("Requested " + name + " camera is unavailable");
		}
		return selector;
	}

	private void unbindUseCases() {
		if (!bound || cameraProvider == null) {
			return;
		}
		if (previewBound) {
			cameraProvider.unbind(preview, imageCapture);
		} else {
			cameraProvider.unbind(imageCapture);
		}
		previewBound = false;
		bound = false;
	}

	private static MicroActivity requireActivity() throws MediaException {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			throw new MediaException("MIDlet Activity is unavailable");
		}
		return activity;
	}

	private static <T> T onMainThread(MicroActivity activity,
			java.util.concurrent.Callable<T> action) throws MediaException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			try {
				return action.call();
			} catch (Exception e) {
				throw new MediaException("Camera UI operation failed: " + e.getMessage());
			}
		}
		FutureTask<T> task = new FutureTask<>(action);
		activity.runOnUiThread(task);
		try {
			return task.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Camera UI operation was interrupted");
		} catch (TimeoutException e) {
			task.cancel(true);
			throw new MediaException("Camera UI operation timed out");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new MediaException("Camera UI operation failed: "
					+ (cause == null ? "unknown error" : cause.getMessage()));
		}
	}

	/** API-23-compatible one-shot result used instead of CompletableFuture. */
	private static final class FileCaptureResult extends FutureTask<File> {
		FileCaptureResult() {
			super(() -> null);
		}

		void complete(File file) {
			set(file);
		}

		void completeExceptionally(Throwable error) {
			setException(error);
		}
	}
}
