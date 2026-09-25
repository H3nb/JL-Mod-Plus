/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MidletLifecycleStateTest {
	@Test
	public void successfulConstructionBeginsPausedAndInitialActivationCannotBeSkipped() {
		MidletLifecycleState state = new MidletLifecycleState();

		state.onConstructed();
		state.setHostVisible(true);

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertTrue(state.canActivate());
		assertFalse(state.shouldSkipStartCallback(true));

		state.beginActivation();
		assertEquals(MidletLifecycleState.State.ACTIVE, state.state());
	}

	@Test
	public void refusedInitialStartRemainsRetryableEvenWithSkipResumeCompatibility() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setHostVisible(true);
		state.beginActivation();

		state.onStartRefused();

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertTrue(state.canActivate());
		assertFalse(state.shouldSkipStartCallback(true));
	}

	@Test
	public void successfulStartEnablesSkipOnlyForLaterActivation() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setHostVisible(true);
		state.beginActivation();

		state.onStartSucceeded();
		state.onPauseSucceeded();

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertTrue(state.canActivate());
		assertTrue(state.shouldSkipStartCallback(true));
	}

	@Test
	public void selfPauseClearsActivationUntilResumeRequest() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setHostVisible(true);
		state.beginActivation();

		assertTrue(state.notifyPaused());
		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertFalse(state.isActivationRequested());

		state.setHostVisible(false);
		state.setHostVisible(true);
		assertFalse(state.canActivate());

		assertTrue(state.resumeRequest());
		assertTrue(state.canActivate());
	}

	@Test
	public void callbackCompletionCannotOverwriteReentrantPauseOrDestroy() {
		MidletLifecycleState paused = new MidletLifecycleState();
		paused.onConstructed();
		paused.setHostVisible(true);
		paused.beginActivation();
		paused.notifyPaused();
		paused.onStartSucceeded();

		assertEquals(MidletLifecycleState.State.PAUSED, paused.state());
		assertFalse(paused.isActivationRequested());

		MidletLifecycleState destroyed = new MidletLifecycleState();
		destroyed.onConstructed();
		destroyed.setHostVisible(true);
		destroyed.beginActivation();
		destroyed.destroy();
		destroyed.onStartSucceeded();
		destroyed.onPauseSucceeded();
		destroyed.resumeRequest();

		assertEquals(MidletLifecycleState.State.DESTROYED, destroyed.state());
		assertFalse(destroyed.canActivate());
		assertFalse(destroyed.canPause());
	}

	@Test
	public void notifyPausedBeforeActiveHasNoEffect() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();

		assertFalse(state.notifyPaused());
		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertTrue(state.isActivationRequested());
	}
}
