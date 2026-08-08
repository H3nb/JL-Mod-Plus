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

package javax.microedition.media;

import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.Lifecycle;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.media.camera.CameraLeaseManager;
import javax.microedition.media.camera.CameraConfiguration;
import javax.microedition.media.camera.CameraHardwareSession;
import javax.microedition.media.camera.CameraPermissionBroker;
import javax.microedition.media.camera.CameraRecordingSession;
import javax.microedition.media.camera.CameraSession;
import javax.microedition.media.camera.CameraXCameraSession;
import javax.microedition.media.camera.CaptureLocatorParser;
import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.media.camera.MicrophonePermissionBroker;
import javax.microedition.media.camera.SnapshotRequest;
import javax.microedition.media.camera.VirtualCameraCapabilities;
import javax.microedition.media.control.CameraRecordingControl;
import javax.microedition.media.control.Jsr135VideoControl;
import javax.microedition.media.control.RecordControl;
import javax.microedition.media.control.VideoControl;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

/** JSR-135 Player facade for the supported physical-camera slice. */
public final class CameraPlayer implements Player {
	private static final String TAG = "J2ME-CameraPlayer";

	private final CaptureRequest request;
	private final CameraConfiguration cameraConfiguration;
	private final CameraSessionFactory sessionFactory;
	private final CameraPermissionBroker permissionBroker;
	private final MicrophonePermissionBroker microphonePermissionBroker =
			new MicrophonePermissionBroker();
	private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "J2ME-CameraPlayerCallback");
		thread.setDaemon(true);
		return thread;
	});
	private final CopyOnWriteArrayList<PlayerListener> listeners = new CopyOnWriteArrayList<>();

	private volatile int state = UNREALIZED;
	private TimeBase timeBase;
	private volatile CameraSession session;
	private CameraLeaseManager.Lease lease;
	private Jsr135VideoControl videoControl;
	private CameraRecordingControl recordingControl;
	private javax.microedition.media.control.AmmsImageFormatControl imageFormatControl;
	private volatile Object previewView;

	public CameraPlayer(String locator) throws MediaException {
		this(CaptureLocatorParser.parse(locator), CameraXCameraSession::new,
				new CameraPermissionBroker());
	}

	CameraPlayer(CaptureRequest request, CameraSessionFactory sessionFactory,
			CameraPermissionBroker permissionBroker) {
		this.request = Objects.requireNonNull(request, "request");
		this.cameraConfiguration = new CameraConfiguration(request);
		this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
		this.permissionBroker = Objects.requireNonNull(permissionBroker, "permissionBroker");
	}

	@Override
	public synchronized void realize() throws MediaException {
		checkClosed();
		if (state == UNREALIZED) {
			videoControl = new Jsr135VideoControl(this, request);
			if (!CaptureRequest.DEVICE_IMAGE.equals(request.getDevice())) {
				recordingControl = new CameraRecordingControl(this, request);
			}
			state = REALIZED;
		}
	}

	@Override
	public synchronized void prefetch() throws MediaException {
		checkClosed();
		if (state == UNREALIZED) {
			realize();
		}
		if (state != REALIZED) {
			return;
		}
		requireWorkerThread();
		if (!VirtualCameraCapabilities.hasCameraFeature()) {
			throw new MediaException("No usable camera hardware is advertised");
		}

		CameraPermissionBroker.Result permission = permissionBroker.request();
		if (permission == CameraPermissionBroker.Result.DENIED) {
			throw new SecurityException("Camera permission was denied");
		}
		if (permission != CameraPermissionBroker.Result.GRANTED) {
			throw new MediaException("Camera permission is unavailable");
		}
		if (request.isAudioVideo()) {
			MicrophonePermissionBroker.Result microphone = microphonePermissionBroker.request();
			if (microphone == MicrophonePermissionBroker.Result.DENIED) {
				throw new SecurityException("Microphone permission was denied");
			}
			if (microphone != MicrophonePermissionBroker.Result.GRANTED) {
				throw new MediaException("Microphone permission is unavailable");
			}
		}

		CameraLeaseManager.Lease acquired = CameraLeaseManager.acquire();
		CameraSession prepared = null;
		try {
			if (videoControl != null) {
				videoControl.attachDirectPreview();
			}
			if (session != null) {
				lease = acquired;
				state = PREFETCHED;
				return;
			}
			prepared = sessionFactory.create(request);
			prepared.prepare();
			if (previewView != null) {
				prepared.attachPreview(previewView);
			}
			session = prepared;
			lease = acquired;
			state = PREFETCHED;
		} catch (MediaException | RuntimeException e) {
			if (prepared != null) {
				prepared.release();
			}
			acquired.close();
			if (e instanceof MediaException mediaException) {
				throw mediaException;
			}
			throw new MediaException("Camera session could not be prepared: " + e.getMessage());
		}
	}

	@Override
	public synchronized void start() throws MediaException {
		checkClosed();
		if (state == STARTED) {
			return;
		}
		requireWorkerThread();
		requireActivityResumed();
		if (state == UNREALIZED) {
			realize();
		}
		if (state == REALIZED) {
			prefetch();
		}
		if (state == PREFETCHED) {
			if (!permissionBroker.isGranted()) {
				throw new SecurityException("Camera permission was revoked");
			}
			if (request.isAudioVideo() && !microphonePermissionBroker.isGranted()) {
				throw new SecurityException("Microphone permission was revoked");
			}
			try {
				if (session == null) {
					throw new MediaException("Camera session is unavailable");
				}
				session.start();
				state = STARTED;
				postEvent(PlayerListener.STARTED, getMediaTimeUnchecked());
				if (recordingControl != null) {
					recordingControl.onPlayerStarted();
				}
			} catch (MediaException | RuntimeException e) {
				state = PREFETCHED;
				if (e instanceof MediaException mediaException) {
					throw mediaException;
				}
				throw new MediaException("Camera session could not start: " + e.getMessage());
			}
		}
	}

	@Override
	public synchronized void stop() throws MediaException {
		checkClosed();
		if (state == STARTED) {
			if (recordingControl != null) {
				recordingControl.onPlayerStopping();
			}
			if (session != null) {
				session.stop();
			}
			if (recordingControl != null) {
				recordingControl.onPlayerStopped();
			}
			state = PREFETCHED;
			postEvent(PlayerListener.STOPPED, getMediaTimeUnchecked());
		}
	}

	@Override
	public synchronized void deallocate() {
		checkClosed();
		if (state == UNREALIZED || state == REALIZED) {
			return;
		}
		try {
			if (state == STARTED) {
				stop();
			}
		} catch (MediaException e) {
			Log.w(TAG, "Camera stop during deallocate failed", e);
			if (recordingControl != null) {
				recordingControl.onPlayerDeallocated();
			}
			releaseSession();
			state = REALIZED;
			return;
		}
		// CameraX persistent recordings survive an explicit VideoCapture unbind.
		// stop() has already paused recording and unbound the camera use cases, so
		// keep the prepared session for JSR-135 standby/resume and release only the lease.
		releaseLease();
		state = REALIZED;
	}

	@Override
	public synchronized void close() {
		if (state == CLOSED) {
			return;
		}
		if (recordingControl != null) {
			recordingControl.onPlayerClosed();
		}
		try {
			if (state == STARTED && session != null) {
				session.stop();
			}
		} catch (MediaException e) {
			Log.w(TAG, "Camera stop during close failed", e);
		}
		releaseSession();
		state = CLOSED;
		postEvent(PlayerListener.CLOSED, null);
		callbackExecutor.shutdown();
	}

	@Override
	public synchronized long setMediaTime(long now) throws MediaException {
		checkRealized();
		throw new MediaException("Live camera media time cannot be set");
	}

	@Override
	public synchronized long getMediaTime() {
		checkClosed();
		return getMediaTimeUnchecked();
	}

	@Override
	public synchronized long getDuration() {
		checkClosed();
		return TIME_UNKNOWN;
	}

	@Override
	public synchronized TimeBase getTimeBase() {
		checkRealized();
		return timeBase == null ? Manager.getSystemTimeBase() : timeBase;
	}

	@Override
	public synchronized void setTimeBase(TimeBase master) throws MediaException {
		checkRealized();
		if (state == STARTED) {
			throw new IllegalStateException("time base cannot be changed while started");
		}
		timeBase = master;
	}

	@Override
	public synchronized void setLoopCount(int count) {
		checkClosed();
		if (state == STARTED) {
			throw new IllegalStateException("loop count cannot be changed while started");
		}
		if (count == 0 || count < -1) {
			throw new IllegalArgumentException("loop count must be positive or -1");
		}
	}

	@Override
	public int getState() {
		return state;
	}

	@Override
	public void addPlayerListener(PlayerListener playerListener) {
		checkClosed();
		if (playerListener != null) {
			listeners.addIfAbsent(playerListener);
		}
	}

	@Override
	public void removePlayerListener(PlayerListener playerListener) {
		checkClosed();
		listeners.remove(playerListener);
	}

	@Override
	public synchronized String getContentType() {
		checkRealized();
		return CaptureRequest.CONTENT_TYPE;
	}

	@Override
	public synchronized Control getControl(String controlType) {
		checkRealized();
		if (controlType == null) {
			throw new IllegalArgumentException("controlType must not be null");
		}
		String normalized = controlType.contains(".")
				? controlType : "javax.microedition.media.control." + controlType;
		if (Jsr135VideoControl.class.getName().equals(normalized)
				|| VideoControl.class.getName().equals(normalized)) {
			return videoControl;
		}
		if (CameraRecordingControl.class.getName().equals(normalized)
				|| RecordControl.class.getName().equals(normalized)) {
			return recordingControl;
		}
		return null;
	}

	@Override
	public synchronized Control[] getControls() {
		checkRealized();
		return recordingControl == null
				? new Control[]{videoControl}
				: new Control[]{videoControl, recordingControl};
	}

	/**
	 * Called by the LCDUI preview item without exposing Android types in the J2ME API.
	 * This path must stay lock-free: lifecycle methods may hold the Player monitor
	 * while waiting for CameraX work on the Android main thread, and preview views
	 * are attached from that main thread.
	 */
	public void attachPreview(Object view) {
		previewView = view;
		CameraSession currentSession = session;
		if (currentSession != null) {
			currentSession.attachPreview(view);
		}
	}

	/** Called by the LCDUI preview item when its host view is discarded. */
	public void detachPreview(Object view) {
		if (previewView == view) {
			previewView = null;
		}
		CameraSession currentSession = session;
		if (currentSession != null) {
			currentSession.detachPreview(view);
		}
	}

	public byte[] takeSnapshot(SnapshotRequest snapshotRequest) throws MediaException {
		CameraSession currentSession;
		synchronized (this) {
			checkClosed();
			requireWorkerThread();
			if (state != STARTED) {
				throw new MediaException("Camera Player must be started before taking a snapshot");
			}
			requireActivityResumed();
			if (!permissionBroker.isGranted()) {
				throw new SecurityException("Camera permission was revoked");
			}
			currentSession = session;
			if (currentSession == null) {
				throw new MediaException("Camera session is unavailable");
			}
		}
		SnapshotRequest effectiveRequest = cameraConfiguration.resolveSnapshot(snapshotRequest);
		if (imageFormatControl != null) {
			effectiveRequest = imageFormatControl.applyQuality(effectiveRequest);
		}
		return currentSession.capture(effectiveRequest);
	}

	public void beginCameraRecording(File outputFile, boolean withAudio, long fileSizeLimit,
			int width, int height) throws MediaException {
		CameraRecordingSession recordingSession = requireRecordingSession();
		recordingSession.beginRecording(outputFile, withAudio, fileSizeLimit, width, height);
	}

	public void pauseCameraRecording() throws MediaException {
		requireRecordingSession().pauseRecording();
	}

	public void resumeCameraRecording() throws MediaException {
		requireRecordingSession().resumeRecording();
	}

	public boolean finalizeCameraRecording() throws MediaException {
		return requireRecordingSession().finalizeRecording();
	}

	/** Returns backend recording state without taking the Player monitor. */
	public boolean hasCameraRecording() {
		CameraSession currentSession = session;
		return currentSession instanceof CameraRecordingSession
				&& ((CameraRecordingSession) currentSession).hasRecording();
	}

	public void notifyRecordingEvent(String event, Object data) {
		postEvent(event, data);
	}

	public synchronized CameraConfiguration getCameraConfiguration() {
		checkRealized();
		return cameraConfiguration;
	}

	public synchronized CameraHardwareSession getCameraHardwareSession() throws MediaException {
		checkRealized();
		if (!(session instanceof CameraHardwareSession)) {
			throw new MediaException("Camera hardware controls are unavailable before prefetch");
		}
		return (CameraHardwareSession) session;
	}

	public void notifyCameraEvent(String event, Object data) {
		postEvent(event, data);
	}

	private CameraRecordingSession requireRecordingSession() throws MediaException {
		checkRealized();
		CameraSession currentSession = session;
		if (!(currentSession instanceof CameraRecordingSession)) {
			throw new MediaException("Camera recording is unavailable before prefetch");
		}
		return (CameraRecordingSession) currentSession;
	}

	private void releaseSession() {
		if (videoControl != null) {
			videoControl.detachDirectPreview();
		}
		CameraSession currentSession = session;
		session = null;
		if (currentSession != null) {
			currentSession.release();
		}
		releaseLease();
	}

	private void releaseLease() {
		CameraLeaseManager.Lease currentLease = lease;
		lease = null;
		if (currentLease != null) {
			currentLease.close();
		}
	}

	private long getMediaTimeUnchecked() {
		return state >= PREFETCHED ? 0L : TIME_UNKNOWN;
	}

	private void postEvent(String event, Object data) {
		List<PlayerListener> snapshot = List.copyOf(listeners);
		for (PlayerListener listener : snapshot) {
			try {
				callbackExecutor.execute(() -> listener.playerUpdate(this, event, data));
			} catch (RuntimeException e) {
				Log.w(TAG, "Camera listener dispatch failed", e);
			}
		}
	}

	private void checkRealized() {
		checkClosed();
		if (state == UNREALIZED) {
			throw new IllegalStateException("call realize() before using the player");
		}
	}

	private void checkClosed() {
		if (state == CLOSED) {
			throw new IllegalStateException("player is closed");
		}
	}

	private static void requireWorkerThread() throws MediaException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new MediaException("Camera operation must not block the Android main thread");
		}
	}

	private static void requireActivityResumed() throws MediaException {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null || !activity.getLifecycle().getCurrentState()
				.isAtLeast(Lifecycle.State.RESUMED)) {
			throw new MediaException("MIDlet Activity is not resumed");
		}
	}

	@FunctionalInterface
	interface CameraSessionFactory {
		CameraSession create(CaptureRequest request);
	}
}
