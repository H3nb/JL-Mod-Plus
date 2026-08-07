/*
 *  Copyright 2020-2026 Yury Kharchenko
 *  Copyright 2026 H3NB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import org.acra.ACRA;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;
import javax.microedition.shell.time.EmulationTime;
import javax.microedition.shell.time.EmulationTimeController;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final long FATAL_ACTIVITY_FINISH_TIMEOUT_MS = 100L;
	private static final UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);
	private static final AtomicBoolean fatalErrorHandling = new AtomicBoolean();
	private static final UncaughtExceptionHandler midletUncaughtExceptionHandler =
			MidletThread::handleFatalMidletError;

	private static final int INIT = 0;
	private static final int START = 1;
	private static final int PAUSE = 2;
	private static final int DESTROY = 3;
	private static final int UNINITIALIZED = 0;
	private static final int INITIALIZED = 1;
	private static final int STARTED = 2;
	private static final int PAUSED = 3;
	private static final int DESTROYED = 4;
	private static MidletThread instance;
	private final MicroLoader microLoader;
	private final String mainClass;
	private final EmulationTimeController emulationTimeController;
	private final LifecycleEventObserver activityLifecycleObserver = this::onActivityStateChanged;
	private MIDlet midlet;
	private Handler handler;
	private int state;

	MidletThread(MicroLoader microLoader, String mainClass, boolean timedWaitEnabled) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.emulationTimeController = new EmulationTimeController();
		EmulationTime.install(emulationTimeController);
		emulationTimeController.setTimedWaitEnabled(timedWaitEnabled);
		emulationTimeController.setMonitorFallbackListener(
				microLoader::recordMonitorFallback);
		instance = this;
	}

	public static EmulationTimeController getEmulationTimeController() {
		return instance == null ? null : instance.emulationTimeController;
	}

	public static void notifyDestroyed() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		if (instance != null) {
			instance.state = DESTROYED;
			instance.emulationTimeController.stop();
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.finish();
		}
		Process.killProcess(Process.myPid());
	}

	public static void notifyPaused() {
		instance.state = PAUSED;
	}

	public static void resumeRequest() {
		MicroActivity activity = ContextHolder.getActivity();
		if (instance != null && activity != null && activity.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	static void destroyApp() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			Displayable current = activity.getCurrent();
			if (current instanceof Canvas canvas) {
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		if (instance != null) {
			instance.handler.obtainMessage(DESTROY).sendToTarget();
		}
	}

	@Override
	public void start() {
		// The :midlet process is a disposable runtime boundary. Report fatal MIDlet
		// failures through ACRA without letting Android classify the whole package as
		// an uncaught app crash, then terminate only this process in the handler.
		Thread.setDefaultUncaughtExceptionHandler(midletUncaughtExceptionHandler);
		super.start();
		handler = new Handler(getLooper(), this);
		ContextHolder.getActivity().getLifecycle().addObserver(activityLifecycleObserver);
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		switch (msg.what) {
			case INIT:
				if (state != UNINITIALIZED) {
					break;
				}
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = INITIALIZED;
				} catch (Throwable t) {
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != INITIALIZED) {
					if (state != PAUSED) {
						break;
					} else if (microLoader.params.skipResumeCall) {
						state = STARTED;
						break;
					}
				}
				try {
					state = STARTED;
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				try {
					midlet.pauseApp();
					state = PAUSED;
				} catch (Throwable t) {
					state = DESTROYED;
					try {
						midlet.destroyApp(true);
					} catch (MIDletStateChangeException ignored) {}
					throw new RuntimeException("Filed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed();
					break;
				}
				state = DESTROYED;
				try {
					midlet.destroyApp(true);
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					Log.e(TAG, "Filed destroyApp:", t);
				}
				notifyDestroyed();
				break;
		}
		return true;
	}

	private static void handleFatalMidletError(Thread thread, Throwable error) {
		if (!fatalErrorHandling.compareAndSet(false, true)) {
			Log.e(TAG, "Additional fatal error while terminating MIDlet process", error);
			Process.killProcess(Process.myPid());
			return;
		}

		// Prevent cleanup failures from recursively entering the crash reporter.
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		Log.e(TAG, "Fatal error in MIDlet session thread: \"" + thread.getName() + "\"", error);

		MidletThread current = instance;
		if (current != null) {
			current.state = DESTROYED;
			current.emulationTimeController.stop();
		}

		finishMidletActivityBeforeReport();

		try {
			// ACRA 5.x treats this as a handled report. Explicitly keep
			// endApplication=false so ACRA does not finish/kill the runtime as an
			// Android application crash; the disposable :midlet process is ended below.
			ACRA.getErrorReporter().handleException(error, false);
		} catch (Throwable reportError) {
			Log.e(TAG, "Unable to create MIDlet crash report", reportError);
		}

		Process.killProcess(Process.myPid());
	}

	private static void finishMidletActivityBeforeReport() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null || activity.isDestroyed()) {
			return;
		}

		CountDownLatch destroyed = new CountDownLatch(1);
		LifecycleEventObserver observer = (source, event) -> {
			if (event == Lifecycle.Event.ON_DESTROY) {
				destroyed.countDown();
			}
		};

		activity.runOnUiThread(() -> {
			if (activity.isDestroyed()) {
				destroyed.countDown();
				return;
			}
			activity.getLifecycle().addObserver(observer);
			activity.finish();
		});

		try {
			destroyed.await(FATAL_ACTIVITY_FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void onActivityStateChanged(LifecycleOwner lifecycleOwner, Lifecycle.Event event) {
		switch (event) {
			case ON_CREATE -> handler.obtainMessage(INIT).sendToTarget();
			case ON_START -> handler.obtainMessage(START).sendToTarget();
			case ON_STOP -> handler.obtainMessage(PAUSE).sendToTarget();
			case ON_DESTROY -> handler.obtainMessage(DESTROY).sendToTarget();
		}
	}
}
