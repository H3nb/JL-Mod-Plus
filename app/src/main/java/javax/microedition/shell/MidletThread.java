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
	private final AtomicBoolean fatalFailureClaimed = new AtomicBoolean();
	private final Object terminationLock = new Object();
	private final UncaughtExceptionHandler sessionUncaughtHandler = this::handleUncaughtSessionFailure;
	private final LifecycleEventObserver activityLifecycleObserver = this::onActivityStateChanged;
	private MIDlet midlet;
	private Handler handler;
	private UncaughtExceptionHandler upstreamUncaughtHandler;
	private volatile Thread primaryFailureThread;
	private volatile String primaryFailureEventId;
	private volatile MidletSessionJournal.FailureBoundary primaryFailureBoundary;
	private volatile boolean destroyCallbackInProgress;
	private MidletSessionJournal.Outcome requestedTerminationOutcome;
	private boolean intentionalTerminationFinalized;
	private int state;

	MidletThread(MicroLoader microLoader, String mainClass, MidletSessionJournal journal) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.journal = journal;
		instance = this;
	}

	public static void notifyDestroyed() {
		MidletThread current = instance;
		if (current != null && current.destroyCallbackInProgress) {
			// The shell owns completion of destroyApp(); a MIDlet callback must not terminate the
			// process from inside destroyApp() before cleanup/reporting finishes.
			return;
		}
		if (current != null) {
			if (!current.finalizeIntentionalTermination(MidletSessionJournal.Outcome.MIDLET_REQUEST)) {
				// A fatal MIDlet failure owns process teardown. Let ACRA persist the report before the
				// isolated process exits instead of racing it with an intentional kill.
				return;
			}
			current.state = DESTROYED;
		} else {
			Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.finish();
		}
		Process.killProcess(Process.myPid());
	}

	public static void notifyPaused() {
		instance.state = PAUSED;
		instance.transitionJournal(MidletSessionJournal.Stage.PAUSED);
	}

	public static void resumeRequest() {
		MicroActivity activity = ContextHolder.getActivity();
		if (instance != null && activity != null && activity.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	static void destroyApp() {
		MidletThread current = instance;
		if (current != null) {
			// This is only an in-memory intent until destroyApp(true) completes. Persisting USER_STOP
			// here would hide a real exception thrown by the MIDlet during destruction.
			current.requestIntentionalTermination(MidletSessionJournal.Outcome.USER_STOP);
		}
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {}
			MidletThread pending = instance;
			if (pending != null) {
				if (!pending.finalizeIntentionalTermination(MidletSessionJournal.Outcome.USER_STOP)) {
					return;
				}
				pending.state = DESTROYED;
			} else {
				Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
			}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			Displayable displayable = activity.getCurrent();
			if (displayable instanceof Canvas canvas) {
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		if (current != null) {
			current.handler.obtainMessage(DESTROY).sendToTarget();
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
				transitionJournal(MidletSessionJournal.Stage.INITIALIZING);
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = INITIALIZED;
				} catch (Throwable t) {
					claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_INIT);
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != INITIALIZED) {
					if (state != PAUSED) {
						break;
					} else if (microLoader.params.skipResumeCall) {
						state = STARTED;
						transitionJournal(MidletSessionJournal.Stage.RUNNING);
						break;
					}
				}
				transitionJournal(MidletSessionJournal.Stage.STARTING);
				try {
					state = STARTED;
					midlet.startApp();
					// startApp() may call notifyPaused(); preserve the state selected by the MIDlet.
					if (state == STARTED) {
						transitionJournal(MidletSessionJournal.Stage.RUNNING);
					} else if (state == PAUSED) {
						transitionJournal(MidletSessionJournal.Stage.PAUSED);
					}
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					transitionJournal(MidletSessionJournal.Stage.PAUSED);
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_START);
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				transitionJournal(MidletSessionJournal.Stage.PAUSING);
				try {
					midlet.pauseApp();
					state = PAUSED;
					transitionJournal(MidletSessionJournal.Stage.PAUSED);
				} catch (Throwable t) {
					state = DESTROYED;
					claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_PAUSE);
					try {
						invokeDestroyApp();
					} catch (MIDletStateChangeException ignored) {
						// Unconditional destroy ignores MIDletStateChangeException by MIDP contract.
					} catch (Throwable cleanupFailure) {
						Log.e(TAG, "Failed destroyApp cleanup after pauseApp failure", cleanupFailure);
					}
					throw new RuntimeException("Failed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed();
					break;
				}
				transitionJournal(MidletSessionJournal.Stage.STOPPING);
				state = DESTROYED;
				try {
					invokeDestroyApp();
				} catch (MIDletStateChangeException e) {
					// destroyApp(true) is unconditional; MIDP permits the shell to ignore this refusal.
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_DESTROY);
					throw new RuntimeException("Failed destroyApp", t);
				}
				notifyDestroyed();
				break;
		}
		return true;
	}

	private void claimLifecycleFailure(MidletSessionJournal.FailureBoundary boundary) {
		if (!beginFatalFailure(Thread.currentThread(), boundary)) {
			return;
		}
		try {
			primaryFailureEventId = journal.recordUnexpectedFailure(boundary);
			if (primaryFailureEventId == null) {
				// A completed intentional termination already owns this session. Do not convert
				// teardown noise into a fatal diagnostic event.
				clearPrimaryFailureClaim();
			}
		} catch (Throwable journalFailure) {
			// Preserve the original lifecycle failure even if correlation metadata cannot be written.
			markJournalOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
		}
	}

	private void handleUncaughtSessionFailure(Thread thread, Throwable error) {
		if (!fatalFailureClaimed.get()) {
			MidletSessionJournal.FailureBoundary boundary = classifyFailureBoundary(thread);
			if (beginFatalFailure(thread, boundary)) {
				try {
					primaryFailureEventId = journal.recordUnexpectedFailure(boundary);
					if (primaryFailureEventId == null) {
						clearPrimaryFailureClaim();
						Log.w(TAG, "Ignoring uncaught failure after intentional MIDlet termination", error);
						return;
					}
				} catch (Throwable journalFailure) {
					try {
						Log.e(TAG, "Unable to correlate uncaught MIDlet session failure", journalFailure);
					} catch (Throwable ignored) {}
				}
			}
		}

		if (thread != primaryFailureThread) {
			Log.e(TAG, "Secondary uncaught failure while primary session failure is being reported", error);
			return;
		}

		Throwable reportError = error;
		String eventId = primaryFailureEventId;
		MidletSessionJournal.FailureBoundary boundary = primaryFailureBoundary;
		if (eventId != null && boundary != null) {
			try {
				reportError = new SessionFailureException(eventId, boundary, error);
			} catch (OutOfMemoryError ignored) {
				// Preserve the original Throwable; sessionId still correlates it to the durable journal.
			}
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

	private boolean beginFatalFailure(Thread thread, MidletSessionJournal.FailureBoundary boundary) {
		synchronized (terminationLock) {
			if (intentionalTerminationFinalized || fatalFailureClaimed.get()) {
				return false;
			}
			fatalFailureClaimed.set(true);
			primaryFailureThread = thread;
			primaryFailureBoundary = boundary;
			return true;
		}
	}

	private void clearPrimaryFailureClaim() {
		synchronized (terminationLock) {
			primaryFailureThread = null;
			primaryFailureEventId = null;
			primaryFailureBoundary = null;
			fatalFailureClaimed.set(false);
		}
	}

	private void requestIntentionalTermination(MidletSessionJournal.Outcome outcome) {
		synchronized (terminationLock) {
			if (fatalFailureClaimed.get() || intentionalTerminationFinalized) {
				return;
			}
			if (requestedTerminationOutcome == null) {
				requestedTerminationOutcome = outcome;
			}
		}
	}

	private boolean finalizeIntentionalTermination(MidletSessionJournal.Outcome fallbackOutcome) {
		synchronized (terminationLock) {
			if (fatalFailureClaimed.get()) {
				return false;
			}
			if (!intentionalTerminationFinalized) {
				MidletSessionJournal.Outcome outcome = requestedTerminationOutcome == null
						? fallbackOutcome : requestedTerminationOutcome;
				completeJournal(outcome);
				intentionalTerminationFinalized = true;
			}
			Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
			return true;
		}
	}

	private void invokeDestroyApp() throws MIDletStateChangeException {
		destroyCallbackInProgress = true;
		try {
			midlet.destroyApp(true);
		} finally {
			destroyCallbackInProgress = false;
		}
	}

	private void transitionJournal(MidletSessionJournal.Stage stage) {
		try {
			journal.transition(stage);
		} catch (RuntimeException | OutOfMemoryError ignored) {
			// Diagnostics must not alter MIDlet lifecycle behavior. The last committed snapshot wins.
		}
	}

	private void markJournalOutcome(MidletSessionJournal.Outcome outcome) {
		try {
			journal.markOutcome(outcome);
		} catch (RuntimeException | OutOfMemoryError ignored) {
			// Diagnostics must not replace the original failure or intentional termination path.
		}
	}

	private void completeJournal(MidletSessionJournal.Outcome outcome) {
		try {
			journal.complete(outcome);
		} catch (RuntimeException | OutOfMemoryError ignored) {
			// Process termination must remain reliable even when diagnostics cannot allocate/write.
		}
	}

	private void onActivityStateChanged(LifecycleOwner lifecycleOwner, Lifecycle.Event event) {
		switch (event) {
			case ON_CREATE -> handler.obtainMessage(INIT).sendToTarget();
			case ON_START -> handler.obtainMessage(START).sendToTarget();
			case ON_STOP -> handler.obtainMessage(PAUSE).sendToTarget();
			case ON_DESTROY -> {
				if (fatalFailureClaimed.get()) {
					// ACRA finishes the crashing activity before persisting its report. Do not enqueue the
					// normal DESTROY path here: it can kill :midlet before ACRA writes the report file.
					break;
				}
				// Keep this as an in-memory intent until destroyApp(true) finishes successfully. A real
				// destruction failure must still win and be reported as LIFECYCLE_DESTROY.
				requestIntentionalTermination(MidletSessionJournal.Outcome.LIFECYCLE_STOP);
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
