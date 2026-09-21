/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

/**
 * Result of one host-owned virtual-keyboard save attempt.
 *
 * <p>The layout is the primary artifact. Optional screen parameters are secondary and cannot
 * turn an already-published layout back into an uncommitted edit transaction.</p>
 */
final class VirtualKeyboardSaveResult {
	private static final VirtualKeyboardSaveResult LAYOUT_FAILED =
			new VirtualKeyboardSaveResult(false, false);

	private final boolean layoutCommitted;
	private final boolean screenParamsFailed;

	private VirtualKeyboardSaveResult(boolean layoutCommitted, boolean screenParamsFailed) {
		this.layoutCommitted = layoutCommitted;
		this.screenParamsFailed = screenParamsFailed;
	}

	static VirtualKeyboardSaveResult layoutFailed() {
		return LAYOUT_FAILED;
	}

	static VirtualKeyboardSaveResult layoutCommitted(boolean screenParamsFailed) {
		return new VirtualKeyboardSaveResult(true, screenParamsFailed);
	}

	boolean isLayoutCommitted() {
		return layoutCommitted;
	}

	boolean isScreenParamsFailed() {
		return screenParamsFailed;
	}
}
