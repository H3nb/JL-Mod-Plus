/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutSnapshot;

public class VirtualKeyboardEditTransactionTest {
	@Test
	public void enteringEditorCapturesOriginalBaseline() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		assertSame(baseline, transaction.baseline());
		assertTrue(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void cleanFinishNeedsNoConfirmationAndClosesTransaction() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CLEAN,
				transaction.requestFinish(snapshot(3.0f)));
		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void actualSemanticChangeRequestsConfirmation() {
		VirtualKeyboardEditTransaction transaction =
				new VirtualKeyboardEditTransaction(snapshot(3.0f));

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CONFIRM,
				transaction.requestFinish(snapshot(8.0f)));
		assertTrue(transaction.isActive());
		assertTrue(transaction.isFinishPending());
	}

	@Test
	public void saveClosesDirtyTransactionWithoutReplacingBaseline() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(snapshot(8.0f));

		transaction.save();

		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
		assertSame(baseline, transaction.baseline());
	}

	@Test
	public void discardReturnsExactOriginalBaselineAndClosesTransaction() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(snapshot(8.0f));

		assertSame(baseline, transaction.discard());
		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void continueKeepsEditsLiveAndOriginalBaselineUntouched() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardLayoutSnapshot edited = snapshot(8.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(edited);

		transaction.continueEditing();

		assertTrue(transaction.isActive());
		assertFalse(transaction.isFinishPending());
		assertSame(baseline, transaction.baseline());
		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CONFIRM,
				transaction.requestFinish(edited));
	}

	@Test
	public void repeatedContinueThenDiscardStillRestoresPreSessionBaseline() {
		VirtualKeyboardLayoutSnapshot baseline = snapshot(3.0f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		transaction.requestFinish(snapshot(8.0f));
		transaction.continueEditing();
		transaction.requestFinish(snapshot(14.0f));
		transaction.continueEditing();
		transaction.requestFinish(snapshot(21.0f));

		assertSame(baseline, transaction.discard());
	}

	@Test
	public void viewportOnlySemanticEquivalenceFinishesClean() {
		VirtualKeyboardLayoutSnapshot portrait = standardSnapshot(3.0f, 0.20f, 0.82f);
		VirtualKeyboardLayoutSnapshot landscape = standardSnapshot(120.0f, 0.14f, 0.55f);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(portrait);

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CLEAN,
				transaction.requestFinish(landscape));
	}

	private static VirtualKeyboardLayoutSnapshot snapshot(float offset) {
		return snapshot(3, offset).withGroupedControls(
				true, false,
				0.20f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				false, false);
	}

	private static VirtualKeyboardLayoutSnapshot standardSnapshot(
			float offset,
			float centerX,
			float centerY) {
		return snapshot(7, offset).withGroupedControls(
				true, false,
				centerX, centerY, 0.16f,
				centerX, centerY, 0.16f,
				true, false);
	}

	private static VirtualKeyboardLayoutSnapshot snapshot(int variant, float offset) {
		return VirtualKeyboardLayoutSnapshot.legacy(
				variant,
				new boolean[] { true, false },
				new int[] { -1, 0 },
				new int[] { 2, 4 },
				new float[] { offset, 0.0f },
				new float[] { 0.0f, 1.0f },
				new float[] { 1.0f, 1.0f });
	}
}
