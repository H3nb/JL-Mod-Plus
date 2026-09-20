/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VirtualKeyboardOrientationPolicyTest {
	@Test
	public void nonPhoneBaselinePolicyIsRestoredAfterDiscardingPhonePreview() {
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.PHONE_PORTRAIT,
				VirtualKeyboardOrientationPolicy.resolve(false, true));

		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.MIDLET_POLICY,
				VirtualKeyboardOrientationPolicy.resolve(false, false));
	}

	@Test
	public void phoneBaselinePolicyIsRestoredAfterDiscardingNonPhonePreview() {
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.MIDLET_POLICY,
				VirtualKeyboardOrientationPolicy.resolve(false, false));

		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.PHONE_PORTRAIT,
				VirtualKeyboardOrientationPolicy.resolve(false, true));
	}

	@Test
	public void orientationLockBlocksTemplatePreviewSideEffects() {
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.KEEP_CURRENT,
				VirtualKeyboardOrientationPolicy.resolve(true, true));
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.KEEP_CURRENT,
				VirtualKeyboardOrientationPolicy.resolve(true, false));
	}

	@Test
	public void lockedDiscardKeepsCurrentLockedOrientation() {
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.KEEP_CURRENT,
				VirtualKeyboardOrientationPolicy.resolve(true, true));
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.KEEP_CURRENT,
				VirtualKeyboardOrientationPolicy.resolve(true, false));
	}

	@Test
	public void unlockedSelectionKeepsEstablishedPhoneAndMidletPolicies() {
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.PHONE_PORTRAIT,
				VirtualKeyboardOrientationPolicy.resolve(false, true));
		assertEquals(
				VirtualKeyboardOrientationPolicy.Target.MIDLET_POLICY,
				VirtualKeyboardOrientationPolicy.resolve(false, false));
	}
}
