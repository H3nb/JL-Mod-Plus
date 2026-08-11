/*
 *  Copyright 2020-2026 Yury Kharchenko
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

import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import ru.playsoftware.j2meloader.crashes.MidletSessionJournal;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler POST_DESTROY_UNCAUGHT_HANDLER = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);

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
	private final MidletSessionJournal journal;
	private final AtomicBoolean fatalFailureStarted = new AtomicBoolean();
	private final UncaughtExceptionHandler sessionUncaughtHandler = this::handleUncaughtSessionFailure;
	private final LifecycleEventObserver activityLifecycleObserver = this::onActivityStateChanged;
	private MIDlet midlet;
	private Handler handler;
	private UncaughtExceptionHandler upstreamUncaughtHandler;
	private int state;

	MidletThread(MicroLoader microLoader, String mainClass, MidletSessionJournal journal) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.journal = journal;
		instance = this;
	}

	public static void notifyDestroyed() {
		Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
		if (instance != null) {
			instance.journal.complete(MidletSessionJournal.Outcome.MIDLET_REQUEST);
			instance.state = DESTROYED;
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.finish();
		}
		Process.killProcess(Process.myPid());
	}

	public static void notifyPaused() {
		instance.state = PAUSED;
		instance.journal.transition(MidletSessionJournal.Stage.PAUSED);
	}

	public static void resumeRequest() {
		MicroActivity activity = ContextHolder.getActivity();
		if (instance != null && activity != null && activity.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	static void destroyApp() {
		Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
		if (instance != null) {
			instance.journal.markOutcome(MidletSessionJournal.Outcome.USER_STOP);
		}
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
		super.start();
		upstreamUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(sessionUncaughtHandler);
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
				journal.transition(MidletSessionJournal.Stage.INITIALIZING);
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = INITIALIZED;
				} catch (Throwable t) {
					journal.markOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != INITIALIZED) {
					if (state != PAUSED) {
						break;
					} else if (microLoader.params.skipResumeCall) {
						state = STARTED;
						journal.transition(MidletSessionJournal.Stage.RUNNING);
						break;
					}
				}
				journal.transition(MidletSessionJournal.Stage.STARTING);
				try {
					state = STARTED;
					midlet.startApp();
					// startApp() may call notifyPaused(); preserve the state selected by the MIDlet.
					if (state == STARTED) {
						journal.transition(MidletSessionJournal.Stage.RUNNING);
					} else if (state == PAUSED) {
						journal.transition(MidletSessionJournal.Stage.PAUSED);
					}
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					journal.transition(MidletSessionJournal.Stage.PAUSED);
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					journal.markOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				journal.transition(MidletSessionJournal.Stage.PAUSING);
				try {
					midlet.pauseApp();
					state = PAUSED;
					journal.transition(MidletSessionJournal.Stage.PAUSED);
				} catch (Throwable t) {
					state = DESTROYED;
					journal.markOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
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
				journal.transition(MidletSessionJournal.Stage.STOPPING);
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

	private void handleUncaughtSessionFailure(Thread thread, Throwable error) {
		if (!fatalFailureStarted.compareAndSet(false, true)) {
			Log.e(TAG, "Secondary uncaught failure while primary session failure is being reported", error);
			return;
		}

		Throwable reportError = error;
		try {
			MidletSessionJournal.FailureBoundary boundary = classifyFailureBoundary(thread);
			String eventId = journal.recordUnexpectedFailure(boundary);
			if (eventId == null) {
				Log.w(TAG, "Ignoring uncaught failure after intentional MIDlet termination", error);
				return;
			}
			reportError = new SessionFailureException(eventId, boundary, error);
		} catch (Throwable journalFailure) {
			// The durable journal is best-effort under severe memory/storage failure. Preserve the
			// original crash by still delegating it to the reporter captured before this wrapper.
			try {
				Log.e(TAG, "Unable to correlate uncaught MIDlet session failure", journalFailure);
			} catch (Throwable ignored) {}
		}

		UncaughtExceptionHandler reporter = upstreamUncaughtHandler;
		if (reporter != null && reporter != sessionUncaughtHandler) {
			try {
				reporter.uncaughtException(thread, reportError);
				return;
			} catch (Throwable reporterFailure) {
				try {
					Log.e(TAG, "Crash reporter failed while handling MIDlet session failure", reporterFailure);
				} catch (Throwable ignored) {}
			}
		}

		// A broken/missing upstream handler must not leave a corrupted isolated MIDlet process alive.
		Process.killProcess(Process.myPid());
	}

	private MidletSessionJournal.FailureBoundary classifyFailureBoundary(Thread thread) {
		if (thread != this) {
			return MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD;
		}
		return switch (journal.getStage()) {
			case INITIALIZING -> MidletSessionJournal.FailureBoundary.LIFECYCLE_INIT;
			case STARTING -> MidletSessionJournal.FailureBoundary.LIFECYCLE_START;
			case PAUSING -> MidletSessionJournal.FailureBoundary.LIFECYCLE_PAUSE;
			case STOPPING -> MidletSessionJournal.FailureBoundary.LIFECYCLE_DESTROY;
			default -> MidletSessionJournal.FailureBoundary.MIDLET_THREAD;
		};
	}

	private void onActivityStateChanged(LifecycleOwner lifecycleOwner, Lifecycle.Event event) {
		switch (event) {
			case ON_CREATE -> handler.obtainMessage(INIT).sendToTarget();
			case ON_START -> handler.obtainMessage(START).sendToTarget();
			case ON_STOP -> handler.obtainMessage(PAUSE).sendToTarget();
			case ON_DESTROY -> {
				if (fatalFailureStarted.get()) {
					// ACRA finishes the crashing activity before persisting its report. Do not enqueue the
					// normal DESTROY path here: it can kill :midlet before ACRA writes the report file.
					break;
				}
				Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
				journal.markOutcome(MidletSessionJournal.Outcome.LIFECYCLE_STOP);
				handler.obtainMessage(DESTROY).sendToTarget();
			}
		}
	}

	private static final class SessionFailureException extends RuntimeException {
		SessionFailureException(String eventId, MidletSessionJournal.FailureBoundary boundary,
				Throwable cause) {
			super("JL-Mod Plus session failure; eventId=" + eventId + "; boundary=" + boundary.name(), cause);
		}
	}
}
