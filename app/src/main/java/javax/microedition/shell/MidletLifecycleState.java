/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

/**
 * Thread-safe MIDP lifecycle state shared by guest lifecycle APIs and the AMS callback executor.
 *
 * <p>The three MIDP semantic states stay deliberately small. Callback phase only records which
 * lifecycle transaction {@link MidletThread} has committed; it is not another MIDP state.</p>
 */
final class MidletLifecycleState {
	enum State {
		PAUSED,
		ACTIVE,
		DESTROYED
	}

	enum CallbackPhase {
		NONE,
		START,
		PAUSE,
		DESTROY
	}

	enum StartAction {
		NONE,
		INVOKE_CALLBACK,
		SKIP_CALLBACK
	}

	enum PauseNotification {
		NO_EFFECT,
		SELF_PAUSE,
		AMS_PAUSE_ACK
	}

	private State state = State.PAUSED;
	private CallbackPhase callbackPhase = CallbackPhase.NONE;
	private boolean constructed;
	private boolean amsForeground;
	private boolean resumeRequired;
	private boolean successfulStartSeen;
	private boolean selfDestructionNotified;
	private long activationTrigger;
	private long refusedAtActivationTrigger = Long.MIN_VALUE;

	synchronized void onConstructed() {
		constructed = true;
	}

	synchronized State state() {
		return state;
	}

	synchronized CallbackPhase callbackPhase() {
		return callbackPhase;
	}

	synchronized boolean isConstructed() {
		return constructed;
	}

	synchronized boolean isDestroyed() {
		return state == State.DESTROYED;
	}

	synchronized boolean isSelfDestroyed() {
		return state == State.DESTROYED && selfDestructionNotified;
	}

	synchronized boolean isAmsForeground() {
		return amsForeground;
	}

	synchronized boolean isResumeRequired() {
		return resumeRequired;
	}

	synchronized boolean hasSuccessfulStart() {
		return successfulStartSeen;
	}

	synchronized boolean setAmsForeground(boolean foreground) {
		if (amsForeground == foreground) {
			return false;
		}
		amsForeground = foreground;
		if (foreground) {
			activationTrigger++;
		}
		return true;
	}

	synchronized StartAction tryBeginStart(boolean skipResumeCall) {
		if (!constructed || state != State.PAUSED || callbackPhase != CallbackPhase.NONE
				|| !amsForeground || resumeRequired
				|| activationTrigger <= refusedAtActivationTrigger) {
			return StartAction.NONE;
		}
		state = State.ACTIVE;
		if (skipResumeCall && successfulStartSeen) {
			return StartAction.SKIP_CALLBACK;
		}
		callbackPhase = CallbackPhase.START;
		return StartAction.INVOKE_CALLBACK;
	}

	synchronized void completeStartSuccess() {
		if (callbackPhase != CallbackPhase.START) {
			return;
		}
		callbackPhase = CallbackPhase.NONE;
		successfulStartSeen = true;
		// Do not assign state here. notifyPaused()/notifyDestroyed() may have selected a newer
		// semantic state while startApp() was executing.
	}

	synchronized void completeStartRefused() {
		if (callbackPhase != CallbackPhase.START) {
			return;
		}
		callbackPhase = CallbackPhase.NONE;
		if (state == State.ACTIVE) {
			state = State.PAUSED;
			resumeRequired = false;
		}
		// A trigger that existed before or during this refused callback cannot immediately retry it.
		refusedAtActivationTrigger = activationTrigger;
	}

	synchronized void completeStartFailure() {
		if (callbackPhase == CallbackPhase.START) {
			callbackPhase = CallbackPhase.NONE;
		}
	}

	synchronized boolean tryBeginPause() {
		if (!constructed || state != State.ACTIVE || callbackPhase != CallbackPhase.NONE) {
			return false;
		}
		callbackPhase = CallbackPhase.PAUSE;
		return true;
	}

	synchronized void completePauseSuccess() {
		if (callbackPhase != CallbackPhase.PAUSE) {
			return;
		}
		callbackPhase = CallbackPhase.NONE;
		if (state != State.DESTROYED) {
			state = State.PAUSED;
			// notifyPaused() during the AMS-owned callback is only an acknowledgement of this
			// transition, not a persistent self-pause request.
			resumeRequired = false;
		}
	}

	synchronized void completePauseFailure() {
		if (callbackPhase == CallbackPhase.PAUSE) {
			callbackPhase = CallbackPhase.NONE;
		}
	}

	synchronized PauseNotification notifyPaused() {
		if (!constructed || state != State.ACTIVE || state == State.DESTROYED) {
			return PauseNotification.NO_EFFECT;
		}
		state = State.PAUSED;
		if (callbackPhase == CallbackPhase.PAUSE) {
			resumeRequired = false;
			return PauseNotification.AMS_PAUSE_ACK;
		}
		resumeRequired = true;
		return PauseNotification.SELF_PAUSE;
	}

	/**
	 * Records activation interest synchronously. Returning true means the AMS should reconcile;
	 * this is intentionally an event even when resumeRequired was already false.
	 */
	synchronized boolean resumeRequest() {
		if (state == State.DESTROYED) {
			return false;
		}
		resumeRequired = false;
		activationTrigger++;
		return true;
	}

	/**
	 * Commits AMS destruction before destroyApp() is invoked. A later notifyDestroyed() is then
	 * redundant and cannot steal ownership from this already-linearized destroy transaction.
	 */
	synchronized boolean tryBeginDestroy() {
		if (state == State.DESTROYED || callbackPhase != CallbackPhase.NONE) {
			return false;
		}
		state = State.DESTROYED;
		resumeRequired = false;
		callbackPhase = CallbackPhase.DESTROY;
		return true;
	}

	synchronized void completeDestroy() {
		state = State.DESTROYED;
		resumeRequired = false;
		if (callbackPhase == CallbackPhase.DESTROY) {
			callbackPhase = CallbackPhase.NONE;
		}
	}

	/**
	 * Synchronously enters DESTROYED for a genuine MIDlet self-destruction. If AMS already
	 * committed destroyApp(), the call is semantically complete but redundant for callback
	 * ownership and terminal side effects.
	 */
	synchronized boolean notifyDestroyed() {
		if (state == State.DESTROYED) {
			return false;
		}
		state = State.DESTROYED;
		resumeRequired = false;
		selfDestructionNotified = callbackPhase != CallbackPhase.DESTROY;
		return selfDestructionNotified;
	}

	synchronized boolean isDestroyCallbackInProgress() {
		return callbackPhase == CallbackPhase.DESTROY;
	}
}
