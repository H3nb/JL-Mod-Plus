/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import java.util.Objects;

import javax.microedition.lcdui.keyboard.VirtualKeyboardLayoutSnapshot;

/**
 * One in-memory virtual-controls edit transaction. It deliberately owns only transaction state;
 * rendering, persistence, and layout restoration remain with the existing runtime/keyboard owners.
 */
final class VirtualKeyboardEditTransaction {
	enum FinishRequest { CLEAN, CONFIRM }

	private final VirtualKeyboardLayoutSnapshot baseline;
	private boolean active = true;
	private boolean finishPending;

	VirtualKeyboardEditTransaction(VirtualKeyboardLayoutSnapshot baseline) {
		this.baseline = Objects.requireNonNull(baseline);
	}

	FinishRequest requestFinish(VirtualKeyboardLayoutSnapshot current) {
		if (!active) throw new IllegalStateException("Layout edit transaction is already closed");
		if (baseline.equals(Objects.requireNonNull(current))) {
			active = false;
			finishPending = false;
			return FinishRequest.CLEAN;
		}
		finishPending = true;
		return FinishRequest.CONFIRM;
	}

	void continueEditing() {
		if (!active) return;
		finishPending = false;
	}

	void save() {
		if (!active) return;
		active = false;
		finishPending = false;
	}

	VirtualKeyboardLayoutSnapshot discard() {
		if (!active) throw new IllegalStateException("Layout edit transaction is already closed");
		active = false;
		finishPending = false;
		return baseline;
	}

	boolean isActive() {
		return active;
	}

	boolean isFinishPending() {
		return finishPending;
	}

	VirtualKeyboardLayoutSnapshot baseline() {
		return baseline;
	}
}
