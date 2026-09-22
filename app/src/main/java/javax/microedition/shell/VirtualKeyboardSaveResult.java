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
			new VirtualKeyboardSaveResult(false, PresetUpdateOutcome.NONE);

	private final boolean layoutCommitted;
	private final PresetUpdateOutcome presetUpdateOutcome;

	private VirtualKeyboardSaveResult(boolean layoutCommitted, PresetUpdateOutcome presetUpdateOutcome) {
		this.layoutCommitted = layoutCommitted;
		this.presetUpdateOutcome = presetUpdateOutcome;
	}

	static VirtualKeyboardSaveResult layoutFailed() {
		return LAYOUT_FAILED;
	}

	static VirtualKeyboardSaveResult layoutCommitted() {
		return layoutCommitted(PresetUpdateOutcome.NONE);
	}

	static VirtualKeyboardSaveResult layoutCommitted(PresetUpdateOutcome presetUpdateOutcome) {
		return new VirtualKeyboardSaveResult(true, presetUpdateOutcome);
	}

	boolean isLayoutCommitted() {
		return layoutCommitted;
	}

	PresetUpdateOutcome getPresetUpdateOutcome() {
		return presetUpdateOutcome;
	}
}
