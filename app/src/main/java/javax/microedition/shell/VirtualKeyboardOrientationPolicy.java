/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

/** Pure decision policy for runtime orientation changes caused by virtual-keyboard layout state. */
final class VirtualKeyboardOrientationPolicy {
	enum Target {
		KEEP_CURRENT,
		PHONE_PORTRAIT,
		MIDLET_POLICY,
	}

	private VirtualKeyboardOrientationPolicy() {}

	static Target resolve(boolean orientationLocked, boolean phoneLayout) {
		if (orientationLocked) return Target.KEEP_CURRENT;
		return phoneLayout ? Target.PHONE_PORTRAIT : Target.MIDLET_POLICY;
	}
}
