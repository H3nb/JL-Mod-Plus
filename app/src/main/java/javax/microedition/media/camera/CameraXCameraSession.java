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

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ExposureState;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

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

/** CameraX adapter for preview and JPEG image capture. */
public final class CameraXCameraSession implements CameraSession, CameraRecordingSession,
		CameraHardwareSession {
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
	private Camera camera;
	private Preview preview;
	private ImageCapture imageCapture;
	private VideoCapture<Recorder> videoCapture;
	private Recorder recorder;
	private Recording recording;
	private boolean prepared;
	private boolean started;
	private boolean bound;
	private boolean previewBound;
	private boolean recordingBound;
	private FileCaptureResult activeCapture;
	private FileRecordingResult activeRecording;
	private int flashMode = javax.microedition.amms.control.camera.FlashControl.OFF;
	private int focus = javax.microedition.amms.control.camera.FocusControl.AUTO;
	private boolean macro;

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
			ImageCapture capture = new ImageCapture.Builder()
					.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
					.setJpegQuality(90)
					// Keep the physical capture portrait so orientation normalization does not
					// discard the portrait field of view before the J2ME resize/crop step.
					.setTargetResolution(new Size(CaptureRequest.PHYSICAL_CAPTURE_WIDTH,
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
		if (isRecording()) {
			stopRecording();
		}
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
			if (recording != null) {
				throw new MediaException("Camera snapshot is unavailable while recording");
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
			ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(captureFile).build();
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
	public void startRecording(File outputFile, boolean withAudio, long fileSizeLimit)
			throws MediaException {
		startRecording(outputFile, withAudio, fileSizeLimit,
				request.getWidth(), request.getHeight());
	}

	@Override
	public void startRecording(File outputFile, boolean withAudio, long fileSizeLimit,
			int width, int height)
				throws MediaException {
		if (outputFile == null) {
			throw new IllegalArgumentException("recording output must not be null");
		}
		final MicroActivity activity;
		synchronized (this) {
			if (!started || cameraProvider == null) {
				throw new MediaException("Camera Player is not started");
			}
			if (recording != null) {
				return;
			}
			activity = requireActivity();
		}
		if (withAudio && ContextCompat.checkSelfPermission(
				activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			throw new SecurityException("Microphone permission was revoked");
		}
		onMainThread(activity, () -> {
			synchronized (CameraXCameraSession.this) {
				if (!started || cameraProvider == null) {
					throw new IllegalStateException("Camera session is not started");
				}
				if (recording != null) {
					return null;
				}
				if (bound) {
					unbindUseCases();
				}
				Quality quality = recordingQuality(width, height);
				Recorder newRecorder = new Recorder.Builder()
						.setQualitySelector(QualitySelector.from(
								quality,
								FallbackStrategy.lowerQualityOrHigherThan(quality)))
						.build();
				VideoCapture<Recorder> newVideoCapture = VideoCapture.withOutput(newRecorder);
				bindRecordingUseCases(activity, newRecorder, newVideoCapture);

				FileOutputOptions.Builder outputBuilder = new FileOutputOptions.Builder(outputFile);
				if (fileSizeLimit < Long.MAX_VALUE) {
					outputBuilder.setFileSizeLimit(fileSizeLimit);
				}
				PendingRecording pending = newRecorder.prepareRecording(
						activity, outputBuilder.build());
				if (withAudio) {
					pending = pending.withAudioEnabled();
				}
				FileRecordingResult result = new FileRecordingResult();
				activeRecording = result;
				try {
					recording = pending.start(callbackExecutor, event -> {
						if (event instanceof VideoRecordEvent.Finalize) {
							result.complete((VideoRecordEvent.Finalize) event);
						}
					});
				} catch (RuntimeException e) {
					activeRecording = null;
					unbindUseCases();
					bindUseCases(activity);
					throw e;
				}
				return null;
			}
		});
	}

	@Override
	public void stopRecording() throws MediaException {
		final MicroActivity activity;
		final Recording currentRecording;
		final FileRecordingResult result;
		synchronized (this) {
			currentRecording = recording;
			result = activeRecording;
			if (currentRecording == null || result == null) {
				return;
			}
			activity = requireActivity();
		}
		onMainThread(activity, () -> {
			currentRecording.stop();
			return null;
		});
		VideoRecordEvent.Finalize finalized;
		try {
			finalized = result.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Video recording stop was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Video recording did not finalize");
		} catch (ExecutionException e) {
			throw new MediaException("Video recording failed to finalize");
		}
		onMainThread(activity, () -> {
			synchronized (CameraXCameraSession.this) {
				if (recording == currentRecording) {
					unbindUseCases();
					recording = null;
					activeRecording = null;
					recordingBound = false;
					if (started) {
						bindUseCases(activity);
					}
				}
			}
			return null;
		});
		if (finalized.hasError()) {
			throw new MediaException("Video recording failed: " + finalized.getError());
		}
	}

	@Override
	public synchronized boolean isRecording() {
		return recording != null;
	}

	@Override
	public synchronized int getCameraRotation() throws MediaException {
		int degrees = requireCameraInfo().getSensorRotationDegrees();
		return switch (degrees) {
			case 90 -> javax.microedition.amms.control.camera.CameraControl.ROTATE_RIGHT;
			case 270 -> javax.microedition.amms.control.camera.CameraControl.ROTATE_LEFT;
			case 0, 180 -> javax.microedition.amms.control.camera.CameraControl.ROTATE_NONE;
			default -> javax.microedition.amms.control.camera.CameraControl.UNKNOWN;
		};
	}

	@Override
	public synchronized boolean hasFlashUnit() throws MediaException {
		return requireCameraInfo().hasFlashUnit();
	}

	@Override
	public synchronized int getFlashMode() {
		return flashMode;
	}

	@Override
	public void setFlashMode(int mode) throws MediaException {
		if (mode != javax.microedition.amms.control.camera.FlashControl.OFF
				&& mode != javax.microedition.amms.control.camera.FlashControl.AUTO
				&& mode != javax.microedition.amms.control.camera.FlashControl.FORCE) {
			throw new IllegalArgumentException("unsupported flash mode: " + mode);
		}
		final MicroActivity activity = requireActivity();
		onMainThread(activity, () -> {
			synchronized (CameraXCameraSession.this) {
				if (!requireCameraInfo().hasFlashUnit()
						&& mode != javax.microedition.amms.control.camera.FlashControl.OFF) {
					throw new IllegalStateException("camera has no flash unit");
				}
				if (imageCapture == null) {
					throw new IllegalStateException("image capture is unavailable");
				}
				imageCapture.setFlashMode(toImageCaptureFlashMode(mode));
				flashMode = mode;
			}
			return null;
		});
	}

	@Override
	public synchronized boolean isAutoFocusSupported() throws MediaException {
		if (camera == null) {
			throw new MediaException("Camera is not bound");
		}
		return true;
	}

	@Override
	public int setFocus(int distance) throws MediaException {
		if (distance != javax.microedition.amms.control.camera.FocusControl.AUTO
				&& distance != javax.microedition.amms.control.camera.FocusControl.AUTO_LOCK) {
			throw new MediaException("Manual focus is not supported by the CameraX backend");
		}
		final MicroActivity activity = requireActivity();
		FocusMeteringAction action = onMainThread(activity,
				() -> createCenterFocusAction(distance ==
						javax.microedition.amms.control.camera.FocusControl.AUTO_LOCK));
		try {
			androidx.camera.core.FocusMeteringResult result = camera.getCameraControl()
					.startFocusAndMetering(action).get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			if (result == null || !result.isFocusSuccessful()) {
				throw new MediaException("Camera autofocus did not succeed");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Camera autofocus was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Camera autofocus timed out");
		} catch (ExecutionException e) {
			throw new MediaException("Camera autofocus failed: " + e.getMessage());
		}
		synchronized (this) {
			focus = distance;
		}
		return distance;
	}

	@Override
	public synchronized int getFocus() {
		return focus;
	}

	@Override
	public synchronized boolean isMacroSupported() {
		return false;
	}

	@Override
	public synchronized void setMacro(boolean enable) throws MediaException {
		if (enable) {
			throw new MediaException("Macro focus is not supported by the CameraX backend");
		}
		macro = false;
	}

	@Override
	public synchronized boolean getMacro() {
		return macro;
	}

	@Override
	public synchronized int[] getSupportedExposureCompensations() throws MediaException {
		ExposureState state = requireCameraInfo().getExposureState();
		return exposureValues(state);
	}

	@Override
	public synchronized int getExposureCompensation() throws MediaException {
		ExposureState state = requireCameraInfo().getExposureState();
		if (!state.isExposureCompensationSupported()) {
			return 0;
		}
		return exposureValue(state, state.getExposureCompensationIndex());
	}

	@Override
	public void setExposureCompensation(int value) throws MediaException {
		final CameraInfo info;
		synchronized (this) {
			info = requireCameraInfo();
		}
		ExposureState state = info.getExposureState();
		int[] supported = exposureValues(state);
		int selectedIndex = -1;
		for (int i = 0; i < supported.length; i++) {
			if (supported[i] == value) {
				selectedIndex = exposureIndexAt(state, i);
				break;
			}
		}
		if (selectedIndex == -1) {
			throw new MediaException("Unsupported exposure compensation: " + value);
		}
		try {
			camera.getCameraControl().setExposureCompensationIndex(selectedIndex)
					.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Exposure compensation was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Exposure compensation timed out");
		} catch (ExecutionException e) {
			throw new MediaException("Exposure compensation failed: " + e.getMessage());
		}
	}

	@Override
	public synchronized int getDigitalZoom() throws MediaException {
		androidx.camera.core.ZoomState state = requireZoomState();
		return Math.round(state.getZoomRatio() * 100f);
	}

	@Override
	public synchronized int getMaxDigitalZoom() throws MediaException {
		return Math.round(requireZoomState().getMaxZoomRatio() * 100f);
	}

	@Override
	public synchronized int getDigitalZoomLevels() throws MediaException {
		androidx.camera.core.ZoomState state = requireZoomState();
		return Math.max(1, Math.round((state.getMaxZoomRatio() - state.getMinZoomRatio()) * 10f) + 1);
	}

	@Override
	public int setDigitalZoom(int level) throws MediaException {
		final CameraInfo info;
		final androidx.camera.core.ZoomState state;
		synchronized (this) {
			info = requireCameraInfo();
			state = requireZoomState();
		}
		int current = Math.round(state.getZoomRatio() * 100f);
		int max = Math.round(state.getMaxZoomRatio() * 100f);
		int step = Math.max(1, Math.round((max - 100) / (float)
				Math.max(1, getDigitalZoomLevels() - 1)));
		if (level == javax.microedition.amms.control.camera.ZoomControl.NEXT) {
			level = Math.min(max, current + step);
		} else if (level == javax.microedition.amms.control.camera.ZoomControl.PREVIOUS) {
			level = Math.max(100, current - step);
		}
		if (level < 100 || level > max) {
			throw new IllegalArgumentException("unsupported digital zoom: " + level);
		}
		float ratio = Math.max(state.getMinZoomRatio(),
				Math.min(state.getMaxZoomRatio(), level / 100f));
		try {
			camera.getCameraControl().setZoomRatio(ratio)
					.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MediaException("Digital zoom was interrupted");
		} catch (TimeoutException e) {
			throw new MediaException("Digital zoom timed out");
		} catch (ExecutionException e) {
			throw new MediaException("Digital zoom failed: " + e.getMessage());
		}
		return Math.round(ratio * 100f);
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
			if (isRecording()) {
				stopRecording();
			}
			stop();
		} catch (MediaException ignored) {
			// Activity destruction can invalidate the UI executor; release is best effort.
		}
		synchronized (this) {
			camera = null;
			cameraProvider = null;
			imageCapture = null;
			preview = null;
			videoCapture = null;
			recorder = null;
			recording = null;
			activeRecording = null;
			prepared = false;
			started = false;
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
			// The Player remains recoverable; CameraX will report a later availability error.
		}
	}

	private void bindUseCases(MicroActivity activity) {
		if (bound) {
			return;
		}
		PreviewView view = previewView;
		CameraSelector selector = selectCamera();
		if (view != null) {
			preview.setSurfaceProvider(view.getSurfaceProvider());
			camera = cameraProvider.bindToLifecycle(activity, selector,
					preview, imageCapture);
			previewBound = true;
		} else {
			camera = cameraProvider.bindToLifecycle(activity, selector,
					imageCapture);
			previewBound = false;
		}
		bound = true;
		recordingBound = false;
	}

	private void bindRecordingUseCases(MicroActivity activity, Recorder newRecorder,
			VideoCapture<Recorder> newVideoCapture) {
		PreviewView view = previewView;
		CameraSelector selector = selectCamera();
		if (view != null) {
			preview.setSurfaceProvider(view.getSurfaceProvider());
			camera = cameraProvider.bindToLifecycle(activity, selector, preview, newVideoCapture);
			previewBound = true;
		} else {
			camera = cameraProvider.bindToLifecycle(activity, selector, newVideoCapture);
			previewBound = false;
		}
		recorder = newRecorder;
		videoCapture = newVideoCapture;
		bound = true;
		recordingBound = true;
	}

	private static Quality recordingQuality(int width, int height) {
		int longestSide = Math.max(width, height);
		if (longestSide > 1280) {
			return Quality.FHD;
		}
		if (longestSide > 720) {
			return Quality.HD;
		}
		return Quality.SD;
	}

	private CameraSelector selectCamera() {
		try {
			if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
				return CameraSelector.DEFAULT_BACK_CAMERA;
			}
			if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
				return CameraSelector.DEFAULT_FRONT_CAMERA;
			}
		} catch (Exception e) {
			throw new IllegalStateException("Camera availability could not be queried", e);
		}
		throw new IllegalStateException("No usable camera is available");
	}

	private void unbindUseCases() {
		if (!bound || cameraProvider == null) {
			return;
		}
		if (recordingBound) {
			if (previewBound) {
				cameraProvider.unbind(preview, videoCapture);
			} else {
				cameraProvider.unbind(videoCapture);
			}
		} else if (previewBound) {
			cameraProvider.unbind(preview, imageCapture);
		} else {
			cameraProvider.unbind(imageCapture);
		}
		previewBound = false;
		recordingBound = false;
		bound = false;
		camera = null;
	}

	private synchronized CameraInfo requireCameraInfo() throws MediaException {
		if (camera == null) {
			throw new MediaException("Camera is not bound");
		}
		return camera.getCameraInfo();
	}

	private synchronized androidx.camera.core.ZoomState requireZoomState() throws MediaException {
		androidx.lifecycle.LiveData<androidx.camera.core.ZoomState> liveData =
				requireCameraInfo().getZoomState();
		androidx.camera.core.ZoomState state = liveData == null ? null : liveData.getValue();
		if (state == null) {
			throw new MediaException("Camera zoom state is unavailable");
		}
		return state;
	}

	private static int[] exposureValues(ExposureState state) {
		if (!state.isExposureCompensationSupported()) {
			return new int[]{0};
		}
		Range<Integer> range = state.getExposureCompensationRange();
		int count = range.getUpper() - range.getLower() + 1;
		int[] values = new int[count];
		for (int i = 0; i < count; i++) {
			values[i] = exposureValue(state, range.getLower() + i);
		}
		return values;
	}

	private static int exposureValue(ExposureState state, int index) {
		Rational step = state.getExposureCompensationStep();
		return Math.round(index * step.floatValue() * 100f);
	}

	private static int exposureIndexAt(ExposureState state, int ordinal) {
		return state.getExposureCompensationRange().getLower() + ordinal;
	}

	private synchronized FocusMeteringAction createCenterFocusAction(boolean lock) {
		MeteringPointFactory factory = previewView == null
				? new SurfaceOrientedMeteringPointFactory(1f, 1f)
				: previewView.getMeteringPointFactory();
		FocusMeteringAction.Builder builder = new FocusMeteringAction.Builder(
				factory.createPoint(0.5f, 0.5f), FocusMeteringAction.FLAG_AF);
		if (lock) {
			builder.disableAutoCancel();
		} else {
			builder.setAutoCancelDuration(3, TimeUnit.SECONDS);
		}
		return builder.build();
	}

	private static int toImageCaptureFlashMode(int mode) {
		return switch (mode) {
			case javax.microedition.amms.control.camera.FlashControl.OFF -> ImageCapture.FLASH_MODE_OFF;
			case javax.microedition.amms.control.camera.FlashControl.AUTO -> ImageCapture.FLASH_MODE_AUTO;
			case javax.microedition.amms.control.camera.FlashControl.FORCE -> ImageCapture.FLASH_MODE_ON;
			default -> throw new IllegalArgumentException("unsupported flash mode: " + mode);
		};
	}

	private static MicroActivity requireActivity() throws MediaException {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			throw new MediaException("MIDlet Activity is unavailable");
		}
		return activity;
	}

	private static <T> T onMainThread(MicroActivity activity, java.util.concurrent.Callable<T> action)
			throws MediaException {
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

	/** API-23-compatible one-shot recording finalization result. */
	private static final class FileRecordingResult
				extends FutureTask<VideoRecordEvent.Finalize> {
		FileRecordingResult() {
			super(() -> null);
		}

		void complete(VideoRecordEvent.Finalize event) {
			set(event);
		}
	}
}
