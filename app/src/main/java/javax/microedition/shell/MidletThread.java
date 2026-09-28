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
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.crashes.CrashReporter;
import io.github.h3nb.jlmodplus.crashes.MidletSessionJournal;
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore;
import io.github.h3nb.jlmodplus.runtime.MidletKeepAliveService;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler POST_DESTROY_UNCAUGHT_HANDLER = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after MIDlet termination", e);

	private static final int INIT = 0;
	private static final int AMS_FOREGROUND = 1;
	private static final int AMS_BACKGROUND = 2;
	private static final int PAUSE = 3;
	private static final int DESTROY = 4;
	private static final int GUEST_PAUSED = 5;
	private static final int GUEST_DESTROYED = 6;
	private static final int RESUME_REQUEST = 7;

	private static volatile MidletThread instance;
	private static final InheritableThreadLocal<Boolean> GUEST_EXECUTION =
			new InheritableThreadLocal<>();
	private static final ThreadLocal<Throwable> ESCAPING_GUEST_FAILURE = new ThreadLocal<>();

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
	private volatile Throwable primaryFailureThrowable;
	private boolean primaryFailureCaptureAttempted;
	private MidletSessionJournal.Outcome requestedTerminationOutcome;
	private long displayForegroundGeneration;
	private volatile boolean runtimeSelected = true;
	private boolean returnToLibraryOnTermination = true;
	private boolean terminalFinalized;

	MidletThread(MicroLoader microLoader, String mainClass, MidletSessionJournal journal) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.journal = journal;
	}

	/**
	 * Marks a known guest callback boundary. Guest-created worker threads inherit this signal,
	 * covering callbacks executed on emulator-owned threads without changing their classloader.
	 */
	public static boolean enterGuestExecution() {
		if (instance == null || Boolean.TRUE.equals(GUEST_EXECUTION.get())) {
			return false;
		}
		GUEST_EXECUTION.set(Boolean.TRUE);
		return true;
	}

	public static void exitGuestExecution(boolean entered) {
		if (entered) {
			GUEST_EXECUTION.remove();
		}
	}

	public static void markEscapingGuestFailure(Throwable error) {
		if (error == null || !Boolean.TRUE.equals(GUEST_EXECUTION.get())) {
			return;
		}
		try {
			ESCAPING_GUEST_FAILURE.set(error);
		} catch (Throwable ignored) {
			// The original fatal Throwable must continue even if ownership marking cannot allocate.
		}
	}

	public static void notifyDestroyed() {
		MidletThread current = instance;
		if (current != null && current.lifecycle.notifyDestroyed()) {
			current.send(GUEST_DESTROYED);
		}
	}

	public static void notifyPaused() {
		MidletThread current = instance;
		if (current != null
				&& current.lifecycle.notifyPaused() != MidletLifecycleState.PauseNotification.NO_EFFECT) {
			current.send(GUEST_PAUSED);
		}
	}

	public static void resumeRequest() {
		MidletThread current = instance;
		if (current != null && current.lifecycle.resumeRequest()) {
			current.send(RESUME_REQUEST);
		}
	}

	static boolean hasLiveRuntime() {
		MidletThread current = instance;
		return current != null && !current.lifecycle.isDestroyed();
	}

	/** Returns the in-process AMS foreground selection for the current live runtime. */
	static boolean isRuntimeSelected() {
		MidletThread current = instance;
		return current != null && !current.lifecycle.isDestroyed() && current.runtimeSelected;
	}

	/** Handles MIDP Display.setCurrent(null) as an emulator/AMS background request. */
	public static void requestBackground() {
		MidletThread current = instance;
		if (current == null || current.lifecycle.isDestroyed()) {
			return;
		}
		boolean returnToLibrary = current.isLibraryReturnAllowedInternal();
		// Linearize loss of LCDUI foreground before returning to guest code. This invalidates
		// every older activation generation, including one currently executing startApp().
		Display.revokeForeground();
		current.setRuntimeSelected(false);
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.leaveRuntimeHost(returnToLibrary, null);
		}
	}

	/** Marks an explicitly selected live runtime as the emulator foreground destination. */
	static void selectRuntimeForForeground() {
		MidletThread current = instance;
		if (current != null && !current.lifecycle.isDestroyed()) {
			current.setRuntimeSelected(true);
		}
	}

	private void setRuntimeSelected(boolean selected) {
		runtimeSelected = selected;
		MidletSessionStore.setRuntimeSelected(
				ContextHolder.getAppContext(), journal.getSessionId(), selected);
	}

	/** Returns whether the current runtime policy still permits a Library host destination. */
	static boolean isLibraryReturnAllowed() {
		MidletThread current = instance;
		return current == null || current.isLibraryReturnAllowedInternal();
	}

	private boolean isLibraryReturnAllowedInternal() {
		synchronized (terminationLock) {
			return returnToLibraryOnTermination;
		}
	}

	@Nullable
	static MicroLoader findLiveRuntime(String appPath, long appId, @Nullable String requestedMainClass) {
		MidletThread current = instance;
		if (current == null || current.lifecycle.isDestroyed()
				|| !current.microLoader.matchesRuntime(appPath, appId)) {
			return null;
		}
		if (requestedMainClass != null && !requestedMainClass.isEmpty()
				&& !requestedMainClass.equals(current.mainClass)) {
			return null;
		}
		return current.microLoader;
	}

	static void amsForeground(long foregroundGeneration) {
		MidletThread current = instance;
		if (current != null) {
			current.send(AMS_FOREGROUND, foregroundGeneration);
		}
	}

	static void amsBackground() {
		MidletThread current = instance;
		if (current != null) {
			current.send(AMS_BACKGROUND);
		}
	}

	static void requestPause() {
		MidletThread current = instance;
		if (current != null) {
			current.send(PAUSE);
		}
	}

	static boolean destroyApp() {
		return destroyApp(true);
	}

	static boolean destroyApp(boolean returnToLibrary) {
		MidletThread current = instance;
		if (current == null) {
			return false;
		}
		// The host has requested destruction, but USER_STOP does not own the terminal outcome until
		// MidletMain commits the DESTROY callback. A synchronous guest notifyDestroyed() can still
		// linearize first. Destination policy is orthogonal: Settings handoff must remain sticky.
		if (!returnToLibrary) {
			current.suppressLibraryReturn();
		}

		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			Displayable displayable = activity.getCurrent();
			if (displayable instanceof Canvas canvas) {
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		current.send(DESTROY);
		return true;
	}

	private void startForceDestroyWatchdog() {
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				return;
			}
			MidletSessionJournal.Outcome outcome;
			synchronized (terminationLock) {
				// The watchdog is strictly for a destroyApp(true) callback which is still executing
				// past the existing timeout. Successful teardown clears the callback phase first.
				if (!lifecycle.isDestroyCallbackInProgress()
						|| fatalFailureClaimed.get() || terminalFinalized) {
					return;
				}
				outcome = requestedTerminationOutcome == null
						? MidletSessionJournal.Outcome.USER_STOP : requestedTerminationOutcome;
				terminalFinalized = true;
				if (instance == this) {
					instance = null;
				}
			}
			finalizeSessionState(outcome);
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
	}

	@Override
	public void start() {
		super.start();
		upstreamUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(sessionUncaughtHandler);
		handler = new Handler(getLooper(), this);
		instance = this;
		send(INIT);
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null && activity.isVisible()) {
			long foregroundGeneration = Display.currentForegroundRequestGeneration();
			if (foregroundGeneration != 0L) {
				send(AMS_FOREGROUND, foregroundGeneration);
			}
		}
	}

	private void send(int what) {
		Handler currentHandler = handler;
		if (currentHandler != null) {
			currentHandler.sendEmptyMessage(what);
		}
	}

	private void send(int what, long value) {
		Handler currentHandler = handler;
		if (currentHandler != null) {
			Message message = currentHandler.obtainMessage(what, Long.valueOf(value));
			message.sendToTarget();
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		if (msg.what == AMS_FOREGROUND && msg.obj instanceof Long generation) {
			if (!runtimeSelected || !Display.isForegroundRequestCurrent(generation)) {
				return true;
			}
			displayForegroundGeneration = generation;
		}
		handleSignal(msg.what);
		return true;
	}

	private void handleSignal(int what) {
		switch (what) {
			case INIT -> initializeMidlet();
			case AMS_FOREGROUND -> {
				lifecycle.setAmsForeground(true);
				activateAndReconcileDisplay();
			}
			case AMS_BACKGROUND -> {
				lifecycle.setAmsForeground(false);
				pauseIfNeeded();
			}
			case PAUSE -> pauseIfNeeded();
			case DESTROY -> destroyMidlet();
			case GUEST_PAUSED -> {
				if (lifecycle.state() == MidletLifecycleState.State.PAUSED) {
					transitionJournal(MidletSessionJournal.Stage.PAUSED);
				}
			}
			case GUEST_DESTROYED -> finishSelfDestructionIfNeeded();
			case RESUME_REQUEST -> activateAndReconcileDisplay();
		}
	}

	private void initializeMidlet() {
		if (lifecycle.isConstructed() || lifecycle.isDestroyed()) {
			return;
		}
		transitionJournal(MidletSessionJournal.Stage.INITIALIZING);
		try {
			midlet = microLoader.loadMIDlet(mainClass);
			lifecycle.onConstructed();
			if (!lifecycle.isDestroyed()) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (Throwable t) {
			lifecycle.tryBeginDestroy();
			lifecycle.completeDestroy();
			Throwable primaryFailure = t;
			if (t instanceof InvocationTargetException
					&& ((InvocationTargetException) t).getCause() != null) {
				// Constructor.newInstance() is the reflection boundary; preserve the guest
				// Throwable as primary while retaining InvocationTargetException in raw evidence.
				primaryFailure = ((InvocationTargetException) t).getCause();
			}
			if (claimLifecycleFailure(
					MidletSessionJournal.FailureBoundary.LIFECYCLE_INIT, primaryFailure)) {
				capturePrimarySessionFailure(Thread.currentThread(), t);
				terminateFatalRuntime();
			}
			return;
		}
		if (finishSelfDestructionIfNeeded()) {
			return;
		}
		activateAndReconcileDisplay();
	}

	private void activateAndReconcileDisplay() {
		if (!runtimeSelected) {
			return;
		}
		activateIfNeeded();
		if (!runtimeSelected || lifecycle.isDestroyed() || !lifecycle.isAmsForeground()) {
			return;
		}
		MidletLifecycleState.State state = lifecycle.state();
		if (state == MidletLifecycleState.State.ACTIVE
				|| (state == MidletLifecycleState.State.PAUSED && lifecycle.isResumeRequired())) {
			Display.grantForeground(displayForegroundGeneration);
		}
	}

	private void activateIfNeeded() {
		MidletLifecycleState.StartAction action =
				lifecycle.tryBeginStart(microLoader.params.skipResumeCall);
		if (action == MidletLifecycleState.StartAction.NONE) {
			return;
		}
		if (action == MidletLifecycleState.StartAction.SKIP_CALLBACK) {
			transitionJournal(MidletSessionJournal.Stage.RUNNING);
			return;
		}

		transitionJournal(MidletSessionJournal.Stage.STARTING);
		try {
			midlet.startApp();
			lifecycle.completeStartSuccess();
			if (finishSelfDestructionIfNeeded()) {
				return;
			}
			if (lifecycle.state() == MidletLifecycleState.State.ACTIVE) {
				transitionJournal(MidletSessionJournal.Stage.RUNNING);
			} else if (lifecycle.state() == MidletLifecycleState.State.PAUSED) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (MIDletStateChangeException refused) {
			lifecycle.completeStartRefused();
			if (finishSelfDestructionIfNeeded()) {
				return;
			}
			if (!lifecycle.isDestroyed()) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
			Log.w(TAG, "MIDlet refused startApp()", refused);
		} catch (Throwable primaryFailure) {
			lifecycle.completeStartFailure();
			boolean cleanupCommitted = lifecycle.tryBeginDestroy();
			boolean claimed = claimLifecycleFailure(
					MidletSessionJournal.FailureBoundary.LIFECYCLE_START, primaryFailure);
			if (claimed) {
				capturePrimarySessionFailure(Thread.currentThread(), primaryFailure);
			}
			if (cleanupCommitted && midlet != null) {
				invokeUnconditionalDestroy("cleanup after startApp failure", false);
				lifecycle.completeDestroy();
			}
			Log.e(TAG, "startApp failed; MIDlet terminated after best-effort cleanup", primaryFailure);
			if (claimed) {
				terminateFatalRuntime();
			}
			return;
		}
	}

	private void pauseIfNeeded() {
		if (!lifecycle.tryBeginPause()) {
			return;
		}
		transitionJournal(MidletSessionJournal.Stage.PAUSING);
		try {
			midlet.pauseApp();
			lifecycle.completePauseSuccess();
			if (finishSelfDestructionIfNeeded()) {
				return;
			}
			if (lifecycle.state() == MidletLifecycleState.State.PAUSED) {
				transitionJournal(MidletSessionJournal.Stage.PAUSED);
			}
		} catch (Throwable primaryFailure) {
			lifecycle.completePauseFailure();
			boolean cleanupCommitted = lifecycle.tryBeginDestroy();
			boolean claimed = claimLifecycleFailure(
					MidletSessionJournal.FailureBoundary.LIFECYCLE_PAUSE, primaryFailure);
			if (claimed) {
				capturePrimarySessionFailure(Thread.currentThread(), primaryFailure);
			}
			if (cleanupCommitted && midlet != null) {
				invokeUnconditionalDestroy("cleanup after pauseApp failure", false);
				lifecycle.completeDestroy();
			}
			Log.e(TAG, "pauseApp failed; MIDlet terminated after best-effort cleanup", primaryFailure);
			if (claimed) {
				terminateFatalRuntime();
			}
			return;
		}
	}

	private void destroyMidlet() {
		if (!lifecycle.tryBeginDestroy()) {
			if (lifecycle.isSelfDestroyed()) {
				terminateIntentional(MidletSessionJournal.Outcome.MIDLET_REQUEST);
			}
			return;
		}
		requestIntentionalTermination(MidletSessionJournal.Outcome.USER_STOP, true);
		transitionJournal(MidletSessionJournal.Stage.STOPPING);
		invokeUnconditionalDestroy("normal MIDlet destruction", true);
		lifecycle.completeDestroy();
		terminateIntentional(MidletSessionJournal.Outcome.USER_STOP);
	}

	private void invokeUnconditionalDestroy(String reason, boolean armWatchdog) {
		if (armWatchdog) {
			startForceDestroyWatchdog();
		}
		try {
			midlet.destroyApp(true);
		} catch (MIDletStateChangeException ignored) {
			Log.w(TAG, "Ignoring MIDletStateChangeException from unconditional destroyApp(true): "
					+ reason, ignored);
		} catch (Throwable ignored) {
			// MIDP 2.0 defines unconditional destruction as terminal even when destroyApp throws.
			Log.w(TAG, "Ignoring exception from unconditional destroyApp(true): " + reason, ignored);
		}
	}

	private boolean finishSelfDestructionIfNeeded() {
		if (!lifecycle.isSelfDestroyed() || fatalFailureClaimed.get()) {
			return false;
		}
		requestIntentionalTermination(MidletSessionJournal.Outcome.MIDLET_REQUEST, true);
		terminateIntentional(MidletSessionJournal.Outcome.MIDLET_REQUEST);
		return true;
	}

	private void terminateIntentional(MidletSessionJournal.Outcome fallbackOutcome) {
		boolean returnToLibrary;
		synchronized (terminationLock) {
			returnToLibrary = returnToLibraryOnTermination;
		}
		if (!finalizeIntentionalTermination(fallbackOutcome)) {
			return;
		}
		Runnable processCleanup = () -> Process.killProcess(Process.myPid());
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			// A live MIDlet may already have yielded its Activity to Library. With no host left to
			// tear down, keep process lifetime outside the guest callback by returning to main Looper.
			new Handler(Looper.getMainLooper()).post(processCleanup);
			return;
		}
		activity.leaveRuntimeHost(returnToLibrary, processCleanup);
	}

	private boolean claimLifecycleFailure(
			MidletSessionJournal.FailureBoundary boundary, Throwable primaryFailure) {
		if (!beginFatalFailure(Thread.currentThread(), boundary)) {
			return false;
		}
		primaryFailureThrowable = primaryFailure;
		try {
			primaryFailureEventId = journal.recordUnexpectedFailure(boundary);
			if (primaryFailureEventId == null) {
				clearPrimaryFailureClaim();
				return false;
			}
		} catch (Throwable journalFailure) {
			markJournalOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
		}
		return true;
	}

	private void handleUncaughtSessionFailure(Thread thread, Throwable error) {
		if (!fatalFailureClaimed.get()) {
			MidletSessionJournal.FailureBoundary boundary = classifyFailureBoundary(thread);
			if (!isClaimableSessionUncaught(thread, error, boundary)) {
				delegateProcessUnhandled(thread, error);
				return;
			}
			if (beginFatalFailure(thread, boundary)) {
				primaryFailureThrowable = error;
				try {
					primaryFailureEventId = journal.recordUnexpectedFailure(boundary);
					if (primaryFailureEventId == null) {
						clearPrimaryFailureClaim();
						Log.w(TAG, "Ignoring uncaught failure after intentional MIDlet termination", error);
						return;
					}
				} catch (Throwable journalFailure) {
					markJournalOutcome(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
					try {
						Log.e(TAG, "Unable to correlate uncaught MIDlet session failure", journalFailure);
					} catch (Throwable ignored) {}
				}
			}
		}

		if (thread != primaryFailureThread) {
			if (!hasProvenGuestOwnership(thread, error)) {
				delegateProcessUnhandled(thread, error);
				return;
			}
			Log.e(TAG, "Secondary uncaught guest failure while primary session failure is being reported",
					error);
			return;
		}

		capturePrimarySessionFailure(thread, error);
		terminateFatalRuntime();
	}

	private boolean isClaimableSessionUncaught(Thread thread, Throwable error,
			MidletSessionJournal.FailureBoundary boundary) {
		if (boundary != MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD) {
			// Lifecycle failures are claimed explicitly at their callback boundaries. A generic
			// MidletMain crash outside those boundaries remains a true process-unhandled failure.
			return false;
		}
		return hasProvenGuestOwnership(thread, error);
	}

	private boolean hasProvenGuestOwnership(Thread thread, Throwable error) {
		if (thread == Thread.currentThread()) {
			Throwable marked = ESCAPING_GUEST_FAILURE.get();
			if (marked == error) {
				ESCAPING_GUEST_FAILURE.remove();
				return true;
			}
			if (Boolean.TRUE.equals(GUEST_EXECUTION.get())) {
				return true;
			}
		}
		return microLoader.ownsGuestThread(thread);
	}

	private void capturePrimarySessionFailure(Thread thread, Throwable reported) {
		Throwable primary;
		synchronized (terminationLock) {
			if (!fatalFailureClaimed.get() || thread != primaryFailureThread
					|| primaryFailureCaptureAttempted) {
				return;
			}
			primaryFailureCaptureAttempted = true;
			primary = primaryFailureThrowable == null ? reported : primaryFailureThrowable;
		}
		if (!CrashReporter.captureMidletSessionFailure(thread, journal, reported, primary)) {
			try {
				Log.e(TAG, "MIDlet session failure Java evidence could not be persisted");
			} catch (Throwable ignored) {}
		}
	}

	private void delegateProcessUnhandled(Thread thread, Throwable error) {
		UncaughtExceptionHandler upstream = upstreamUncaughtHandler;
		if (upstream != null && upstream != sessionUncaughtHandler) {
			upstream.uncaughtException(thread, error);
			return;
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
			if (terminalFinalized || fatalFailureClaimed.get()) {
				return false;
			}
			fatalFailureClaimed.set(true);
			primaryFailureThread = thread;
			primaryFailureBoundary = boundary;
			if (instance == this) {
				instance = null;
			}
		}
		return true;
	}

	private void clearPrimaryFailureClaim() {
		synchronized (terminationLock) {
			primaryFailureThread = null;
			primaryFailureEventId = null;
			primaryFailureBoundary = null;
			primaryFailureThrowable = null;
			primaryFailureCaptureAttempted = false;
			fatalFailureClaimed.set(false);
		}
	}

	private void suppressLibraryReturn() {
		synchronized (terminationLock) {
			if (!terminalFinalized) {
				// An explicit handoff such as Settings must never be replaced by the library.
				returnToLibraryOnTermination = false;
			}
		}
	}

	private void requestIntentionalTermination(
			MidletSessionJournal.Outcome outcome, boolean returnToLibrary) {
		synchronized (terminationLock) {
			if (fatalFailureClaimed.get() || terminalFinalized) {
				return;
			}
			if (requestedTerminationOutcome == null) {
				requestedTerminationOutcome = outcome;
			}
			if (!returnToLibrary) {
				// Destination policy is monotonic and independent of which terminal outcome wins.
				returnToLibraryOnTermination = false;
			}
		}
	}

	private boolean finalizeIntentionalTermination(MidletSessionJournal.Outcome fallbackOutcome) {
		MidletSessionJournal.Outcome outcome;
		synchronized (terminationLock) {
			if (fatalFailureClaimed.get() || terminalFinalized) {
				return false;
			}
			outcome = requestedTerminationOutcome == null ? fallbackOutcome : requestedTerminationOutcome;
			terminalFinalized = true;
			if (instance == this) {
				instance = null;
			}
			Thread.setDefaultUncaughtExceptionHandler(POST_DESTROY_UNCAUGHT_HANDLER);
		}
		finalizeSessionState(outcome);
		return true;
	}

	private void terminateFatalRuntime() {
		boolean returnToLibrary;
		synchronized (terminationLock) {
			returnToLibrary = returnToLibraryOnTermination;
		}
		finalizeFatalRuntime();
		Runnable processCleanup = () -> Process.killProcess(Process.myPid());
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			// Keep process death outside the failing guest callback/uncaught-handler stack.
			new Handler(Looper.getMainLooper()).post(processCleanup);
			return;
		}
		activity.leaveRuntimeHost(returnToLibrary, processCleanup);
	}

	private void finalizeFatalRuntime() {
		synchronized (terminationLock) {
			if (!fatalFailureClaimed.get() || terminalFinalized) {
				return;
			}
			terminalFinalized = true;
			if (instance == this) {
				instance = null;
			}
		}
		finalizeSessionState(MidletSessionJournal.Outcome.UNEXPECTED_FAILURE);
	}

	private void finalizeSessionState(MidletSessionJournal.Outcome outcome) {
		completeJournal(outcome);
		microLoader.closeTimingSession();
		try {
			android.content.Context context = ContextHolder.getAppContext();
			MidletSessionStore.clear(context, journal.getSessionId());
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
}
