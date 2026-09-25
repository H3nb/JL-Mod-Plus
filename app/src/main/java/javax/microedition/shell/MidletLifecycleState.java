/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

/**
 * Pure MIDP lifecycle state owned by {@link MidletThread}.
 *
 * <p>Callback execution stages are deliberately not states here. Android host visibility and
 * activation interest are orthogonal inputs used by the runtime thread to decide when an
 * eligible PAUSED MIDlet may become ACTIVE.</p>
 */
final class MidletLifecycleState {
	enum State {
		PAUSED,
		ACTIVE,
		DESTROYED
	}

	private volatile State state = State.PAUSED;
	private volatile boolean constructed;
	private boolean hostVisible;
	private boolean activationRequested = true;
	private boolean startSucceeded;

	void onConstructed() {
		constructed = true;
	}

	State state() {
		return state;
	}

	boolean isConstructed() {
		return constructed;
	}

	boolean isDestroyed() {
		return state == State.DESTROYED;
	}

	void setHostVisible(boolean visible) {
		hostVisible = visible;
	}

	boolean isHostVisible() {
		return hostVisible;
	}

	boolean isActivationRequested() {
		return activationRequested;
	}

	boolean canActivate() {
		return constructed && state == State.PAUSED && hostVisible && activationRequested;
	}

	void beginActivation() {
		if (!canActivate()) {
			throw new IllegalStateException("MIDlet is not eligible for activation");
		}
		state = State.ACTIVE;
	}

	boolean shouldSkipStartCallback(boolean skipResumeCall) {
		return skipResumeCall && startSucceeded;
	}

	void onStartSucceeded() {
		startSucceeded = true;
	}

	void onStartRefused() {
		if (state == State.ACTIVE) {
			state = State.PAUSED;
		}
	}

	boolean canPause() {
		return constructed && state == State.ACTIVE;
	}

	void onPauseSucceeded() {
		if (state == State.ACTIVE) {
			state = State.PAUSED;
		}
	}

	boolean notifyPaused() {
		if (!constructed || state != State.ACTIVE) {
			return false;
		}
		state = State.PAUSED;
		activationRequested = false;
		return true;
	}

	boolean resumeRequest() {
		if (!constructed || state == State.DESTROYED) {
			return false;
		}
		boolean changed = !activationRequested;
		activationRequested = true;
		return changed;
	}

	void destroy() {
		state = State.DESTROYED;
	}

	boolean hasSuccessfulStart() {
		return startSucceeded;
	}
}
