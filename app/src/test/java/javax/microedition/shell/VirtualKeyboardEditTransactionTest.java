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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.microedition.lcdui.keyboard.VirtualControlsKeyboard;
import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutEditState;
import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutSnapshot;
import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutState;
import javax.microedition.lcdui.keyboard.VirtualLayoutOrientation;

public class VirtualKeyboardEditTransactionTest {
	@Test
	public void enteringEditorCapturesOriginalBaseline() {
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		assertSame(baseline, transaction.baseline());
		assertTrue(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void cleanFinishNeedsNoConfirmationAndClosesTransaction() {
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CLEAN,
				transaction.requestFinish(edit(snapshot(3.0f))));
		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void actualSemanticChangeRequestsConfirmation() {
		VirtualKeyboardEditTransaction transaction =
				new VirtualKeyboardEditTransaction(edit(snapshot(3.0f)));

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CONFIRM,
				transaction.requestFinish(edit(snapshot(8.0f))));
		assertTrue(transaction.isActive());
		assertTrue(transaction.isFinishPending());
	}

	@Test
	public void saveClosesDirtyTransactionWithoutReplacingBaseline() {
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(edit(snapshot(8.0f)));

		assertTrue(transaction.commitSave(() -> true));

		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
		assertSame(baseline, transaction.baseline());
	}

	@Test
	public void failedPersistenceKeepsDirtyTransactionActiveForRetry() {
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardLayoutEditState draft = edit(snapshot(8.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(draft);

		assertFalse(transaction.commitSave(() -> false));

		assertTrue(transaction.isActive());
		assertFalse(transaction.isFinishPending());
		assertSame(baseline, transaction.baseline());
		assertTrue(transaction.commitSave(() -> true));
		assertFalse(transaction.isActive());
	}

	@Test
	public void layoutFailureKeepsTransactionActive() {
		VirtualKeyboardEditTransaction transaction =
				new VirtualKeyboardEditTransaction(edit(snapshot(3.0f)));
		transaction.requestFinish(edit(snapshot(8.0f)));
		VirtualKeyboardSaveResult result = VirtualKeyboardSaveResult.layoutFailed();

		assertFalse(transaction.commitSave(result::isLayoutCommitted));

		assertTrue(transaction.isActive());
		assertFalse(result.isLayoutCommitted());
	}

	@Test
	public void layoutAndScreenSuccessCloseTransaction() {
		VirtualKeyboardEditTransaction transaction =
				new VirtualKeyboardEditTransaction(edit(snapshot(3.0f)));
		transaction.requestFinish(edit(snapshot(8.0f)));
		VirtualKeyboardSaveResult result = VirtualKeyboardSaveResult.layoutCommitted(false);

		assertTrue(transaction.commitSave(result::isLayoutCommitted));

		assertFalse(transaction.isActive());
		assertFalse(result.isScreenParamsFailed());
	}

	@Test
	public void screenParamFailureAfterLayoutCommitStillClosesTransactionAndKeepsLayout()
			throws Exception {
		VirtualKeyboardEditTransaction transaction =
				new VirtualKeyboardEditTransaction(edit(snapshot(3.0f)));
		transaction.requestFinish(edit(snapshot(8.0f)));
		Path persistedLayout = Files.createTempFile("jlmod-layout-save", ".bin");
		Files.write(persistedLayout, "edited-layout".getBytes(StandardCharsets.UTF_8));
		VirtualKeyboardSaveResult result = VirtualKeyboardSaveResult.layoutCommitted(true);

		assertTrue(transaction.commitSave(result::isLayoutCommitted));

		assertFalse(transaction.isActive());
		assertTrue(result.isScreenParamsFailed());
		assertEquals(
				"edited-layout",
				new String(Files.readAllBytes(persistedLayout), StandardCharsets.UTF_8));
		try {
			transaction.discard();
			throw new AssertionError("Committed layout transaction must not remain discardable");
		} catch (IllegalStateException expected) {
			// Expected: the primary layout commit closed the transaction.
		} finally {
			Files.deleteIfExists(persistedLayout);
		}
	}

	@Test
	public void nonEditorOutcomeKeepsPrimarySuccessDistinctFromSecondaryFailure() {
		VirtualKeyboardSaveResult result = VirtualKeyboardSaveResult.layoutCommitted(true);

		assertTrue(result.isLayoutCommitted());
		assertTrue(result.isScreenParamsFailed());
	}

	@Test
	public void dormantCustomStateParticipatesInSingleLayoutTransactionEquality() {
		VirtualKeyboardLayoutState dormant = customState(
				snapshot(3.0f), snapshot(8.0f)).customLayout();
		VirtualKeyboardLayoutEditState baseline =
				VirtualKeyboardLayoutEditState.single(snapshot(3.0f), dormant);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CONFIRM,
				transaction.requestFinish(VirtualKeyboardLayoutEditState.single(snapshot(3.0f))));
	}

	@Test
	public void discardReturnsExactCompleteBaselineAndClosesTransaction() {
		VirtualKeyboardLayoutEditState baseline = customState(
				snapshot(3.0f), snapshot(8.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);
		transaction.requestFinish(customState(snapshot(14.0f), snapshot(21.0f)));

		assertSame(baseline, transaction.discard());
		assertFalse(transaction.isActive());
		assertFalse(transaction.isFinishPending());
	}

	@Test
	public void continueKeepsEditsLiveAndOriginalBaselineUntouched() {
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardLayoutEditState edited = edit(snapshot(8.0f));
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
		VirtualKeyboardLayoutEditState baseline = edit(snapshot(3.0f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		transaction.requestFinish(edit(snapshot(8.0f)));
		transaction.continueEditing();
		transaction.requestFinish(edit(snapshot(14.0f)));
		transaction.continueEditing();
		transaction.requestFinish(edit(snapshot(21.0f)));

		assertSame(baseline, transaction.discard());
	}

	@Test
	public void viewportOnlySemanticEquivalenceFinishesClean() {
		VirtualKeyboardLayoutEditState portrait = edit(
				standardSnapshot(3.0f, 0.20f, 0.82f));
		VirtualKeyboardLayoutEditState landscape = edit(
				standardSnapshot(120.0f, 0.14f, 0.55f));
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(portrait);

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CLEAN,
				transaction.requestFinish(landscape));
	}

	@Test
	public void landscapeChangeRemainsDirtyAfterReturningToUnchangedPortrait() {
		VirtualKeyboardLayoutSnapshot portrait = snapshot(3.0f).asCustomOverride();
		VirtualKeyboardLayoutSnapshot landscape = snapshot(8.0f).asCustomOverride();
		VirtualKeyboardLayoutEditState baseline = customState(portrait, landscape);
		VirtualKeyboardEditTransaction transaction = new VirtualKeyboardEditTransaction(baseline);

		VirtualKeyboardLayoutState changed = baseline.customLayout().withOverride(
				VirtualLayoutOrientation.LANDSCAPE,
				snapshot(99.0f).asCustomOverride());

		assertEquals(
				VirtualKeyboardEditTransaction.FinishRequest.CONFIRM,
				transaction.requestFinish(VirtualKeyboardLayoutEditState.custom(changed)));
	}

	private static VirtualKeyboardLayoutEditState edit(VirtualKeyboardLayoutSnapshot snapshot) {
		return VirtualKeyboardLayoutEditState.single(snapshot);
	}

	private static VirtualKeyboardLayoutEditState customState(
			VirtualKeyboardLayoutSnapshot portrait,
			VirtualKeyboardLayoutSnapshot landscape) {
		VirtualKeyboardLayoutState state = VirtualKeyboardLayoutState
				.forBase(VirtualControlsKeyboard.TYPE_DPAD_STANDARD)
				.withOverride(VirtualLayoutOrientation.PORTRAIT, portrait.asCustomOverride())
				.withOverride(VirtualLayoutOrientation.LANDSCAPE, landscape.asCustomOverride());
		return VirtualKeyboardLayoutEditState.custom(state);
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
