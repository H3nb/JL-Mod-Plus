/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

/** Result of one host-owned virtual-keyboard save attempt. */
final class VirtualKeyboardSaveResult {
	enum PresetUpdateOutcome {
		NONE,
		LINKED,
		SAVED_UNLINKED,
		FAILED
	}

	private static final VirtualKeyboardSaveResult LAYOUT_FAILED =
			new VirtualKeyboardSaveResult(false, false, PresetUpdateOutcome.NONE);
	private static final VirtualKeyboardSaveResult LAYOUT_STALE =
			new VirtualKeyboardSaveResult(false, true, PresetUpdateOutcome.NONE);

	private final boolean layoutCommitted;
	private final boolean stale;
	private final PresetUpdateOutcome presetUpdateOutcome;

	private VirtualKeyboardSaveResult(boolean layoutCommitted, boolean stale,
			PresetUpdateOutcome presetUpdateOutcome) {
		this.layoutCommitted = layoutCommitted;
		this.stale = stale;
		this.presetUpdateOutcome = presetUpdateOutcome;
	}

	static VirtualKeyboardSaveResult layoutFailed() {
		return LAYOUT_FAILED;
	}

	static VirtualKeyboardSaveResult layoutStale() {
		return LAYOUT_STALE;
	}

	static VirtualKeyboardSaveResult layoutCommitted() {
		return layoutCommitted(PresetUpdateOutcome.NONE);
	}

	static VirtualKeyboardSaveResult layoutCommitted(PresetUpdateOutcome presetUpdateOutcome) {
		return new VirtualKeyboardSaveResult(true, false, presetUpdateOutcome);
	}

	boolean isLayoutCommitted() {
		return layoutCommitted;
	}

	boolean isStale() {
		return stale;
	}

	PresetUpdateOutcome getPresetUpdateOutcome() {
		return presetUpdateOutcome;
	}
}
