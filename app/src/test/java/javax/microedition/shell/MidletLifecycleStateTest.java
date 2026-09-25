/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class MidletLifecycleStateTest {
	private static MidletLifecycleState activeState() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setAmsForeground(true);
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(false));
		state.completeStartSuccess();
		assertEquals(MidletLifecycleState.State.ACTIVE, state.state());
		return state;
	}

	@Test
	public void successfulConstructionBeginsPausedAndInitialActivationCannotBeSkipped() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setAmsForeground(true);

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(true));
		assertEquals(MidletLifecycleState.State.ACTIVE, state.state());
	}

	@Test
	public void legacyPauseCallbackNotifyPausedRemainsAutomaticallyResumable() {
		MidletLifecycleState state = activeState();

		state.setAmsForeground(false);
		assertTrue(state.tryBeginPause());
		assertEquals(MidletLifecycleState.PauseNotification.AMS_PAUSE_ACK,
				state.notifyPaused());
		state.completePauseSuccess();

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertFalse(state.isResumeRequired());

		state.setAmsForeground(true);
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(false));
	}

	@Test
	public void genuineSelfPauseNeedsResumeRequestEvenAcrossHostHideShow() {
		MidletLifecycleState state = activeState();

		assertEquals(MidletLifecycleState.PauseNotification.SELF_PAUSE,
				state.notifyPaused());
		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertTrue(state.isResumeRequired());

		state.setAmsForeground(false);
		assertFalse(state.tryBeginPause());
		state.setAmsForeground(true);
		assertEquals(MidletLifecycleState.StartAction.NONE, state.tryBeginStart(false));

		assertTrue(state.resumeRequest());
		assertFalse(state.isResumeRequired());
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(false));
	}

	@Test
	public void refusedStartNeedsANewerActivationTrigger() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();
		state.setAmsForeground(true);
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(true));

		state.completeStartRefused();

		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertEquals(MidletLifecycleState.StartAction.NONE, state.tryBeginStart(true));

		// resumeRequest is an event even though this is not a self-pause and the boolean was false.
		assertTrue(state.resumeRequest());
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				state.tryBeginStart(true));
	}

	@Test
	public void skipResumeOnlyAppliesAfterSuccessfulStart() {
		MidletLifecycleState state = activeState();
		state.setAmsForeground(false);
		assertTrue(state.tryBeginPause());
		state.completePauseSuccess();
		state.setAmsForeground(true);

		assertEquals(MidletLifecycleState.StartAction.SKIP_CALLBACK,
				state.tryBeginStart(true));
		assertEquals(MidletLifecycleState.State.ACTIVE, state.state());
	}

	@Test
	public void workerSelfDestructionIsSynchronousAndBlocksLaterHostDestroy() throws Exception {
		MidletLifecycleState state = activeState();
		AtomicReference<Boolean> changed = new AtomicReference<>();
		Thread worker = new Thread(() -> changed.set(state.notifyDestroyed()));
		worker.start();
		worker.join();

		assertTrue(changed.get());
		assertEquals(MidletLifecycleState.State.DESTROYED, state.state());
		assertTrue(state.isSelfDestroyed());
		assertFalse(state.tryBeginDestroy());
	}

	@Test
	public void hostPauseCommittedFirstMakesConcurrentNotifyPausedAnAcknowledgement() throws Exception {
		MidletLifecycleState state = activeState();
		state.setAmsForeground(false);
		assertTrue(state.tryBeginPause());

		AtomicReference<MidletLifecycleState.PauseNotification> result = new AtomicReference<>();
		Thread worker = new Thread(() -> result.set(state.notifyPaused()));
		worker.start();
		worker.join();
		state.completePauseSuccess();

		assertEquals(MidletLifecycleState.PauseNotification.AMS_PAUSE_ACK, result.get());
		assertFalse(state.isResumeRequired());
	}

	@Test
	public void guestPauseCommittedFirstPreventsHostPauseCallback() throws Exception {
		MidletLifecycleState state = activeState();
		CountDownLatch guestCommitted = new CountDownLatch(1);
		Thread worker = new Thread(() -> {
			state.notifyPaused();
			guestCommitted.countDown();
		});
		worker.start();
		guestCommitted.await();

		state.setAmsForeground(false);
		assertFalse(state.tryBeginPause());
		worker.join();
		assertTrue(state.isResumeRequired());
	}

	@Test
	public void destroyRaceHasOneLinearizedCallbackOwner() throws Exception {
		MidletLifecycleState hostFirst = activeState();
		assertTrue(hostFirst.tryBeginDestroy());
		AtomicReference<Boolean> hostFirstNotification = new AtomicReference<>();
		Thread lateGuest = new Thread(() -> hostFirstNotification.set(hostFirst.notifyDestroyed()));
		lateGuest.start();
		lateGuest.join();
		assertFalse(hostFirstNotification.get());
		assertFalse(hostFirst.isSelfDestroyed());
		hostFirst.completeDestroy();

		MidletLifecycleState guestFirst = activeState();
		AtomicReference<Boolean> guestFirstNotification = new AtomicReference<>();
		Thread earlyGuest = new Thread(() -> guestFirstNotification.set(guestFirst.notifyDestroyed()));
		earlyGuest.start();
		earlyGuest.join();
		assertTrue(guestFirstNotification.get());
		assertTrue(guestFirst.isSelfDestroyed());
		assertFalse(guestFirst.tryBeginDestroy());
	}

	@Test
	public void callbackCompletionNeverOverwritesReentrantPauseOrDestroy() {
		MidletLifecycleState paused = new MidletLifecycleState();
		paused.onConstructed();
		paused.setAmsForeground(true);
		assertEquals(MidletLifecycleState.StartAction.INVOKE_CALLBACK,
				paused.tryBeginStart(false));
		paused.notifyPaused();
		paused.completeStartSuccess();
		assertEquals(MidletLifecycleState.State.PAUSED, paused.state());
		assertTrue(paused.isResumeRequired());

		MidletLifecycleState destroyed = activeState();
		assertTrue(destroyed.tryBeginPause());
		assertTrue(destroyed.notifyDestroyed());
		destroyed.completePauseSuccess();
		assertEquals(MidletLifecycleState.State.DESTROYED, destroyed.state());
		assertTrue(destroyed.isSelfDestroyed());
	}

	@Test
	public void replacementForegroundSignalDoesNotRestartLiveOrSelfPausedRuntime() {
		MidletLifecycleState active = activeState();
		assertFalse(active.setAmsForeground(true));
		assertEquals(MidletLifecycleState.State.ACTIVE, active.state());
		assertEquals(MidletLifecycleState.StartAction.NONE, active.tryBeginStart(false));

		MidletLifecycleState selfPaused = activeState();
		selfPaused.notifyPaused();
		assertTrue(selfPaused.isResumeRequired());
		assertFalse(selfPaused.setAmsForeground(true));
		assertEquals(MidletLifecycleState.StartAction.NONE, selfPaused.tryBeginStart(false));
		assertEquals(MidletLifecycleState.State.PAUSED, selfPaused.state());
	}

	@Test
	public void notifyPausedBeforeFirstStartHasNoEffect() {
		MidletLifecycleState state = new MidletLifecycleState();
		state.onConstructed();

		assertEquals(MidletLifecycleState.PauseNotification.NO_EFFECT, state.notifyPaused());
		assertEquals(MidletLifecycleState.State.PAUSED, state.state());
		assertFalse(state.isResumeRequired());
	}
}
