/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.File;

/**
 * Orders one user-owned MIDlet mutation relative to named-preset ownership metadata.
 *
 * <p>This is intentionally a one-operation guard, not a persisted ownership state.</p>
 */
public final class PresetLocalOverride {
	private PresetLocalOverride() {
	}

	@NonNull
	public static Guard detachBeforeWrite(@NonNull Context context, @NonNull File configDir) {
		return detachBeforeWrite(
				PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()),
				configDir);
	}

	@NonNull
	public static Guard clearBeforeReplacement(@NonNull Context context, @NonNull File configDir) {
		return clearBeforeReplacement(
				PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()),
				configDir);
	}

	@FunctionalInterface
	public interface WriteOperation {
		boolean run();
	}

	/**
	 * Serializes one ordinary active-MIDlet mutation against named-preset lifecycle changes.
	 *
	 * <p>The named link is detached before the primary write and restored only when that primary
	 * publication reports failure. Optional secondary work belongs outside this operation.</p>
	 */
	public static boolean runDetachedWrite(
			@NonNull Context context,
			@NonNull File configDir,
			@NonNull WriteOperation writeOperation) {
		return runDetachedWrite(
				PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()),
				configDir,
				writeOperation);
	}

	static boolean runDetachedWrite(
			@NonNull SharedPreferences preferences,
			@NonNull File configDir,
			@NonNull WriteOperation writeOperation) {
		synchronized (ProfilesManager.presetSourceLock()) {
			Guard ownership = detachBeforeWrite(preferences, configDir);
			if (!ownership.canWrite()) {
				return false;
			}
			final boolean written;
			try {
				written = writeOperation.run();
			} catch (RuntimeException | Error failure) {
				ownership.restoreIfUnchanged();
				throw failure;
			}
			if (!written) {
				ownership.restoreIfUnchanged();
			}
			return written;
		}
	}

	@NonNull
	static Guard detachBeforeWrite(@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		return begin(new PresetLinkage(preferences, configDir), false);
	}

	@NonNull
	static Guard clearBeforeReplacement(@NonNull SharedPreferences preferences,
			@NonNull File configDir) {
		return begin(new PresetLinkage(preferences, configDir), true);
	}

	@NonNull
	private static Guard begin(@NonNull PresetLinkage linkage, boolean clearAssociation) {
		String previousOrigin = linkage.getOrigin();
		boolean previousLinked = linkage.isLinked();
		boolean transitionNeeded = clearAssociation
				? previousOrigin != null || previousLinked
				: previousLinked;
		if (!transitionNeeded) {
			return new Guard(linkage, previousOrigin, previousLinked, false, true);
		}

		boolean changed = clearAssociation ? linkage.clear() : linkage.detach();
		if (!changed) {
			// No filesystem write may follow. Best-effort restore also repairs SharedPreferences'
			// in-memory view when commit() changed memory but failed to reach durable storage.
			restoreAssociation(linkage, previousOrigin, previousLinked);
			return new Guard(linkage, previousOrigin, previousLinked, false, false);
		}
		return new Guard(linkage, previousOrigin, previousLinked, true, true);
	}

	private static boolean restoreAssociation(@NonNull PresetLinkage linkage,
			@Nullable String origin, boolean linked) {
		if (linked && origin != null) {
			if (linkage.linkTo(origin)) {
				return true;
			}
			// The preceding detach/clear was already durable. If link restoration cannot be made
			// durable, force the process-visible state back to CUSTOM rather than claiming LINKED.
			linkage.detach();
			return false;
		}
		if (origin != null) {
			return linkage.setOrigin(origin);
		}
		return linkage.clear();
	}

	public static final class Guard {
		@NonNull private final PresetLinkage linkage;
		@Nullable private final String previousOrigin;
		private final boolean previousLinked;
		private final boolean transitionPerformed;
		private final boolean writeAllowed;

		private Guard(@NonNull PresetLinkage linkage, @Nullable String previousOrigin,
				boolean previousLinked, boolean transitionPerformed, boolean writeAllowed) {
			this.linkage = linkage;
			this.previousOrigin = previousOrigin;
			this.previousLinked = previousLinked;
			this.transitionPerformed = transitionPerformed;
			this.writeAllowed = writeAllowed;
		}

		public boolean canWrite() {
			return writeAllowed;
		}

		@Nullable
		public String previousOrigin() {
			return previousOrigin;
		}

		public boolean wasLinked() {
			return previousLinked;
		}

		/**
		 * Restores the association only when the caller knows no local divergence was committed.
		 */
		public boolean restoreIfUnchanged() {
			if (!writeAllowed || !transitionPerformed) {
				return writeAllowed;
			}
			return restoreAssociation(linkage, previousOrigin, previousLinked);
		}
	}
}
