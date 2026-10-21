/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ChoiceFitPolicyValidationTest {
	@Test
	public void validPoliciesArePreservedEvenWhenNotRendered() {
		ChoiceGroup choice = new ChoiceGroup(null, Choice.POPUP);
		for (int policy : new int[]{
				Choice.TEXT_WRAP_DEFAULT, Choice.TEXT_WRAP_ON, Choice.TEXT_WRAP_OFF}) {
			choice.setFitPolicy(policy);
			assertEquals(policy, choice.getFitPolicy());
		}
	}

	@Test
	public void rejectedPolicyDoesNotOverwriteLastValidPreference() {
		ChoiceGroup choice = new ChoiceGroup(null, Choice.POPUP);
		choice.setFitPolicy(Choice.TEXT_WRAP_ON);
		for (int policy : new int[]{-1, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
			final int invalidPolicy = policy;
			assertThrows(IllegalArgumentException.class, () -> choice.setFitPolicy(invalidPolicy));
			assertEquals(Choice.TEXT_WRAP_ON, choice.getFitPolicy());
		}
	}
}
