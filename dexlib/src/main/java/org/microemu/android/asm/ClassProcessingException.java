/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.microemu.android.asm;

/** Typed boundary between invalid guest input and failures introduced by the JL-Mod transform. */
public final class ClassProcessingException extends RuntimeException {
	public enum Kind {
		UNREADABLE_SOURCE,
		IDENTITY_MISMATCH,
		TRANSFORM_FAILURE,
	}

	private final Kind kind;
	private final String classFileName;

	public ClassProcessingException(Kind kind, String classFileName, String message, Throwable cause) {
		super(message + ": " + classFileName, cause);
		this.kind = kind;
		this.classFileName = classFileName;
	}

	public Kind getKind() {
		return kind;
	}

	public String getClassFileName() {
		return classFileName;
	}
}
