/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import java.util.Objects;

/**
 * Immutable orientation-aware semantic Custom layout.
 *
 * A missing orientation override derives from the built-in base when known, otherwise from the
 * shared legacy fallback carried forward from v1-v3 Custom files.
 */
public final class VirtualKeyboardLayoutState {
	public static final int BASE_UNKNOWN = -1;

	private final int baseVariant;
	private final VirtualKeyboardLayoutSnapshot legacySharedFallback;
	private final VirtualKeyboardLayoutSnapshot portraitOverride;
	private final VirtualKeyboardLayoutSnapshot landscapeOverride;

	public VirtualKeyboardLayoutState(
			int baseVariant,
			VirtualKeyboardLayoutSnapshot legacySharedFallback,
			VirtualKeyboardLayoutSnapshot portraitOverride,
			VirtualKeyboardLayoutSnapshot landscapeOverride) {
		if (baseVariant != BASE_UNKNOWN && !isSupportedBaseVariant(baseVariant)) {
			throw new IllegalArgumentException("Unsupported virtual-layout base " + baseVariant);
		}
		this.baseVariant = baseVariant;
		this.legacySharedFallback = legacySharedFallback;
		this.portraitOverride = portraitOverride;
		this.landscapeOverride = landscapeOverride;
	}

	public static VirtualKeyboardLayoutState forBase(int baseVariant) {
		return new VirtualKeyboardLayoutState(baseVariant, null, null, null);
	}

	public static VirtualKeyboardLayoutState migrated(VirtualKeyboardLayoutSnapshot sharedFallback) {
		return new VirtualKeyboardLayoutState(
				BASE_UNKNOWN, Objects.requireNonNull(sharedFallback), null, null);
	}

	public static boolean isSupportedBaseVariant(int variant) {
		return variant >= 1 && variant <= VirtualControlsKeyboard.TYPE_ANALOG_STANDARD;
	}

	public int baseVariant() {
		return baseVariant;
	}

	public boolean hasKnownBase() {
		return baseVariant != BASE_UNKNOWN;
	}

	public VirtualKeyboardLayoutSnapshot legacySharedFallback() {
		return legacySharedFallback;
	}

	public VirtualKeyboardLayoutSnapshot portraitOverride() {
		return portraitOverride;
	}

	public VirtualKeyboardLayoutSnapshot landscapeOverride() {
		return landscapeOverride;
	}

	public VirtualKeyboardLayoutSnapshot overrideFor(VirtualLayoutOrientation orientation) {
		return orientation == VirtualLayoutOrientation.LANDSCAPE
				? landscapeOverride
				: portraitOverride;
	}

	public VirtualKeyboardLayoutState withOverride(
			VirtualLayoutOrientation orientation,
			VirtualKeyboardLayoutSnapshot override) {
		Objects.requireNonNull(orientation);
		Objects.requireNonNull(override);
		VirtualKeyboardLayoutState result = orientation == VirtualLayoutOrientation.LANDSCAPE
				? new VirtualKeyboardLayoutState(
						baseVariant, legacySharedFallback, portraitOverride, override)
				: new VirtualKeyboardLayoutState(
						baseVariant, legacySharedFallback, override, landscapeOverride);
		if (result.baseVariant == BASE_UNKNOWN &&
				result.portraitOverride != null && result.landscapeOverride != null) {
			// Once both migration-era orientations have independent state, the shared fallback no
			// longer contributes to rendering and can be dropped.
			return new VirtualKeyboardLayoutState(
					BASE_UNKNOWN, null, result.portraitOverride, result.landscapeOverride);
		}
		return result;
	}

	public boolean hasRenderableSourceFor(VirtualLayoutOrientation orientation) {
		return overrideFor(orientation) != null || hasKnownBase() || legacySharedFallback != null;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof VirtualKeyboardLayoutState that)) return false;
		return baseVariant == that.baseVariant &&
				Objects.equals(legacySharedFallback, that.legacySharedFallback) &&
				Objects.equals(portraitOverride, that.portraitOverride) &&
				Objects.equals(landscapeOverride, that.landscapeOverride);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				baseVariant, legacySharedFallback, portraitOverride, landscapeOverride);
	}
}
