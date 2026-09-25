/*
 * Modified for JL-Mod Plus.
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
import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.crashes.MidletSessionJournal;
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore;
import io.github.h3nb.jlmodplus.runtime.MidletKeepAliveService;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler POST_DESTROY_UNCAUGHT_HANDLER = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after MIDlet termination", e);

	private static final int INIT = 0;
	private static final int HOST_VISIBLE = 1;
	private static final int HOST_HIDDEN = 2;
	private static final int PAUSE = 3;
	private static final int DESTROY = 4;
	private static final int GUEST_PAUSED = 5;
	private static final int GUEST_DESTROYED = 6;
	private static final int RESUME_REQUEST = 7;

	private static volatile MidletThread instance;

	private final MicroLoader microLoader;
	private final String mainClass;
	private final MidletSessionJournal journal;
	private final MidletLifecycleState lifecycle = new MidletLifecycleState();
	private final AtomicBoolean fatalFailureClaimed = new AtomicBoolean();
	private final Object terminationLock = new Object();
	private final UncaughtExceptionHandler sessionUncaughtHandler = this::handleUncaughtSessionFailure;

	private MIDlet midlet;
	private volatile Handler handler;
	private UncaughtExceptionHandler upstreamUncaughtHandler;
	private volatile Thread primaryFailureThread;
	private volatile String primaryFailureEventId;
	private volatile MidletSessionJournal.FailureBoundary primaryFailureBoundary;
	private boolean lifecycleCallbackInProgress;
	private boolean destroyCallbackInProgress;
	private boolean terminalRequestedDuringCallback;
	private boolean activationCheckAfterCallback;
	private boolean destructionWasNotified;
	private MidletSessionJournal.Outcome requestedTerminationOutcome;
	private boolean intentionalTerminationFinalized;

	MidletThread(MicroLoader microLoader, String mainClass, MidletSessionJournal journal) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.journal = journal;
		instance = this;
	}

	public static void notifyDestroyed() {
		signalGuest(GUEST_DESTROYED);
	}

	public static void notifyPaused() {
		signalGuest(GUEST_PAUSED);
	}

	public static void resumeRequest() {
		signalGuest(RESUME_REQUEST);
	}

	@Nullable
	static MicroLoader findLiveRuntime(String appPath, long appId, @Nullable String requestedMainClass) {
		MidletThread current = instance;
		if (current == null || !current.lifecycle.isConstructed() || current.lifecycle.isDestroyed()
				|| !current.microLoader.matchesRuntime(appPath, appId)) {
			return null;
		}
		if (requestedMainClass != null && !requestedMainClass.isEmpty()
				&& !requestedMainClass.equals(current.mainClass)) {
			return null;
		}
		return current.microLoader;
	}

	static void hostVisible(MicroActivity activity) {
		MidletThread current = instance;
		if (current != null && ContextHolder.getActivity() == activity) {
			current.send(HOST_VISIBLE);
		}
	}

	static void hostHidden(MicroActivity activity) {
		MidletThread current = instance;
		if (current != null && ContextHolder.getActivity() == activity) {
			current.send(HOST_HIDDEN);
		}
	}

	static void hostDetached(MicroActivity activity) {
		// Detachment is presentation ownership, not MIDP destruction. Ensure the current host can no
		// longer count as visible; duplicate HOST_HIDDEN signals are harmless.
		hostHidden(activity);
	}

	private static void signalGuest(int what) {
		MidletThread current = instance;
		if (current == null) {
			return;
		}
		Handler currentHandler = current.handler;
		if (currentHandler == null) {
			return;
		}
		// Reentrant lifecycle calls from startApp()/pauseApp() must be observed before the outer
		// callback returns. Calls from any other guest thread stay asynchronous and never wait for
		// the lifecycle thread, avoiding join/callback deadlocks.
		if (Thread.currentThread() == current) {
			current.handleSignal(what);
		} else {
			currentHandler.sendEmptyMessage(what);
		}
	}

	static void requestPause() {
		MidletThread current = instance;
		if (current != null) {
			current.send(PAUSE);
		}
	}

	static void destroyApp() {
		MidletThread current = instance;
		if (current == null) {
			return;
		}
		// This is only an in-memory intent until destroyApp(true) has been attempted. Persisting
		// USER_STOP here would hide a real start/pause failure which wins before teardown.
		current.requestIntentionalTermination(MidletSessionJournal.Outcome.USER_STOP);
		current.startForceDestroyWatchdog();

		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			Displayable displayable = activity.getCurrent();
			if (displayable instanceof Canvas canvas) {
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		current.send(DESTROY);
	}

	private void startForceDestroyWatchdog() {
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				return;
			}
			synchronized (terminationLock) {
				if (fatalFailureClaimed.get() || intentionalTerminationFinalized) {
					return;
				}
			}
			if (!finalizeIntentionalTermination(MidletSessionJournal.Outcome.USER_STOP)) {
				return;
			}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
	}

	@Override
	public void start() {
		super.start();
		upstreamUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(sessionUncaughtHandler);
		handler = new Handler(getLooper(), this);
		send(INIT);
	}

	private void send(int what) {
		Handler currentHandler = handler;
		if (currentHandler != null) {
			currentHandler.sendEmptyMessage(what);
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		handleSignal(msg.what);
		return true;
	}

	private void handleSignal(int what) {
		switch (what) {
			case INIT -> initializeMidlet();
			case HOST_VISIBLE -> {
				lifecycle.setHostVisible(true);
				activateIfNeeded();
			}
			case HOST_HIDDEN -> {
				lifecycle.setHostVisible(false);
				pauseIfNeeded();
			}
			case PAUSE -> pauseIfNeeded();
			case DESTROY -> destroyMidlet();
			case GUEST_PAUSED -> handleGuestPaused();
			case GUEST_DESTROYED -> handleGuestDestroyed();
			case RESUME_REQUEST -> handleResumeRequest();
		}
	}

	private void initializeMidlet() {
		if (lifecycle.isConstructed() || lifecycle.isDestroyed()) {
			return;
		}
		transitionJournal(MidletSessionJournal.Stage.INITIALIZING);
		lifecycleCallbackInProgress = true;
		try {
			midlet = microLoader.loadMIDlet(mainClass);
			lifecycle.onConstructed();
			if (!lifecycle.isDestroyed()) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (Throwable t) {
			lifecycle.destroy();
			claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_INIT);
			throw new RuntimeException("Init midlet failed", t);
		} finally {
			lifecycleCallbackInProgress = false;
		}
		if (finishDeferredTerminal()) {
			return;
		}
		activateIfNeeded();
	}

	private void activateIfNeeded() {
		if (lifecycleCallbackInProgress || !lifecycle.canActivate()) {
			return;
		}
		lifecycle.beginActivation();
		if (lifecycle.shouldSkipStartCallback(microLoader.params.skipResumeCall)) {
			transitionJournal(MidletSessionJournal.Stage.RUNNING);
			return;
		}

		transitionJournal(MidletSessionJournal.Stage.STARTING);
		lifecycleCallbackInProgress = true;
		try {
			midlet.startApp();
			lifecycle.onStartSucceeded();
			if (lifecycle.state() == MidletLifecycleState.State.ACTIVE) {
				transitionJournal(MidletSessionJournal.Stage.RUNNING);
			} else if (lifecycle.state() == MidletLifecycleState.State.PAUSED) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (MIDletStateChangeException refused) {
			lifecycle.onStartRefused();
			if (!lifecycle.isDestroyed()) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
			Log.w(TAG, "MIDlet refused startApp()", refused);
		} catch (Throwable primaryFailure) {
			activationCheckAfterCallback = false;
			terminalRequestedDuringCallback = false;
			claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_START);
			destroyAfterCallbackFailure("startApp", primaryFailure);
			throw new RuntimeException("Failed startApp", primaryFailure);
		} finally {
			lifecycleCallbackInProgress = false;
		}
		if (finishDeferredTerminal()) {
			return;
		}
		finishDeferredActivationCheck();
	}

	private void pauseIfNeeded() {
		if (lifecycleCallbackInProgress || !lifecycle.canPause()) {
			return;
		}
		transitionJournal(MidletSessionJournal.Stage.PAUSING);
		lifecycleCallbackInProgress = true;
		try {
			midlet.pauseApp();
			lifecycle.onPauseSucceeded();
			if (lifecycle.state() == MidletLifecycleState.State.PAUSED) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (Throwable primaryFailure) {
			activationCheckAfterCallback = false;
			terminalRequestedDuringCallback = false;
			claimLifecycleFailure(MidletSessionJournal.FailureBoundary.LIFECYCLE_PAUSE);
			destroyAfterCallbackFailure("pauseApp", primaryFailure);
			throw new RuntimeException("Failed pauseApp", primaryFailure);
		} finally {
			lifecycleCallbackInProgress = false;
		}
		if (finishDeferredTerminal()) {
			return;
		}
		finishDeferredActivationCheck();
	}

	private void destroyAfterCallbackFailure(String callbackName, Throwable primaryFailure) {
		if (!destructionWasNotified && midlet != null) {
			invokeUnconditionalDestroy("cleanup after " + callbackName + " failure");
		}
		lifecycle.destroy();
		Log.e(TAG, callbackName + " failed; MIDlet terminated after best-effort cleanup",
				primaryFailure);
	}

	private void destroyMidlet() {
		if (lifecycle.isDestroyed()) {
			if (!fatalFailureClaimed.get()) {
				terminateIntentional(MidletSessionJournal.Outcome.USER_STOP);
			}
			return;
		}
		transitionJournal(MidletSessionJournal.Stage.STOPPING);
		lifecycleCallbackInProgress = true;
		invokeUnconditionalDestroy("normal MIDlet destruction");
		lifecycleCallbackInProgress = false;
		lifecycle.destroy();
		terminateIntentional(MidletSessionJournal.Outcome.USER_STOP);
	}

	private void invokeUnconditionalDestroy(String reason) {
		destroyCallbackInProgress = true;
		try {
			midlet.destroyApp(true);
		} catch (MIDletStateChangeException ignored) {
			Log.w(TAG, "Ignoring MIDletStateChangeException from unconditional destroyApp(true): "
					+ reason, ignored);
		} catch (Throwable ignored) {
			// MIDP 2.0 defines unconditional destruction as terminal even when destroyApp throws.
			Log.w(TAG, "Ignoring exception from unconditional destroyApp(true): " + reason, ignored);
		} finally {
			destroyCallbackInProgress = false;
		}
	}

	private void handleGuestPaused() {
		if (destroyCallbackInProgress) {
			return;
		}
		if (lifecycle.notifyPaused()) {
			transitionJournal(MidletSessionJournal.Stage.PAUSED);
		}
	}

	private void handleGuestDestroyed() {
		if (destroyCallbackInProgress || lifecycle.isDestroyed()) {
			return;
		}
		destructionWasNotified = true;
		lifecycle.destroy();
		requestIntentionalTermination(MidletSessionJournal.Outcome.MIDLET_REQUEST);
		if (lifecycleCallbackInProgress) {
			terminalRequestedDuringCallback = true;
			return;
		}
		terminateIntentional(MidletSessionJournal.Outcome.MIDLET_REQUEST);
	}

	private void handleResumeRequest() {
		if (!lifecycle.resumeRequest()) {
			return;
		}
		if (lifecycleCallbackInProgress) {
			activationCheckAfterCallback = true;
		} else {
			activateIfNeeded();
		}
	}

	private boolean finishDeferredTerminal() {
		if (!terminalRequestedDuringCallback) {
			return false;
		}
		terminalRequestedDuringCallback = false;
		activationCheckAfterCallback = false;
		if (!fatalFailureClaimed.get()) {
			terminateIntentional(MidletSessionJournal.Outcome.MIDLET_REQUEST);
		}
		return true;
	}

	private void finishDeferredActivationCheck() {
		if (!activationCheckAfterCallback) {
			return;
		}
		activationCheckAfterCallback = false;
		activateIfNeeded();
	}

	private void terminateIntentional(MidletSessionJournal.Outcome fallbackOutcome) {
		if (!finalizeIntentionalTermination(fallbackOutcome)) {
			return;
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.finish();
		}
		Process.killProcess(Process.myPid());
	}

	private void claimLifecycleFailure(MidletSessionJournal.FailureBoundary boundary) {
		if (!beginFatalFailure(Thread.currentThread(), boundary)) {
			return;
		}
		try {
			primaryFailureEventId = journal.recordUnexpectedFailure(boundary);
			if (primaryFailureEventId == null) {
				clearPrimaryFailureClaim();
			}
		} catch (Throwable journalFailure) {
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
		}
		microLoader.closeTimingSession();
		clearActiveSession();
		return true;
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
		}
		microLoader.closeTimingSession();
		clearActiveSession();
		return true;
	}

	private static void clearActiveSession() {
		try {
			android.content.Context context = ContextHolder.getAppContext();
			MidletSessionStore.clear(context);
			MidletKeepAliveService.stop(context);
		} catch (Throwable ignored) {
			// Runtime cleanup must never replace the original lifecycle/crash outcome.
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

	private static final class SessionFailureException extends RuntimeException {
		SessionFailureException(String eventId, MidletSessionJournal.FailureBoundary boundary,
				Throwable cause) {
			super("JL-Mod Plus session failure; eventId=" + eventId + "; boundary=" + boundary.name(), cause);
		}
	}
}
