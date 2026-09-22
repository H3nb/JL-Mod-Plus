/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.config.PresetAuthorityContract;

/** Narrow fail-closed runtime facade over the main-process preset authority. */
final class PresetAuthorityClient {
	static final class PrepareResult {
		private final int code;
		private final long appId;
		private final boolean builtInThemeLinked;

		PrepareResult(int code, long appId, boolean builtInThemeLinked) {
			this.code = code;
			this.appId = appId;
			this.builtInThemeLinked = builtInThemeLinked;
		}
		boolean isSuccess() { return code == PresetAuthorityContract.RESULT_OK && appId > 0L; }
		boolean isStale() { return code == PresetAuthorityContract.RESULT_STALE; }
		long appId() { return appId; }
		boolean builtInThemeLinked() { return builtInThemeLinked; }
	}

	private final ContentResolver resolver;
	private final Uri authorityUri;

	PresetAuthorityClient(@NonNull Context context) {
		Context app = context.getApplicationContext();
		resolver = app.getContentResolver();
		authorityUri = Uri.parse("content://" + app.getPackageName()
				+ PresetAuthorityContract.AUTHORITY_SUFFIX);
	}

	@NonNull
	PrepareResult prepareRuntime(@NonNull String appPath, long expectedAppId) {
		Bundle result = call(
				PresetAuthorityContract.METHOD_PREPARE_RUNTIME, identityExtras(appPath, expectedAppId));
		if (result == null) return new PrepareResult(PresetAuthorityContract.RESULT_FAILED, 0L, false);
		return new PrepareResult(
				result.getInt(PresetAuthorityContract.KEY_RESULT, PresetAuthorityContract.RESULT_FAILED),
				result.getLong(PresetAuthorityContract.KEY_APP_ID, 0L),
				result.getBoolean(PresetAuthorityContract.KEY_BUILT_IN_THEME_LINKED, false));
	}

	@Nullable
	String resolveUpdateTarget(@NonNull String appPath, long expectedAppId) {
		if (expectedAppId <= 0L) return null;
		Bundle result = call(PresetAuthorityContract.METHOD_RESOLVE_UPDATE_TARGET,
				identityExtras(appPath, expectedAppId));
		if (result == null || result.getInt(PresetAuthorityContract.KEY_RESULT,
				PresetAuthorityContract.RESULT_FAILED) != PresetAuthorityContract.RESULT_OK) return null;
		return result.getString(PresetAuthorityContract.KEY_UPDATE_TARGET);
	}

	@NonNull
	VirtualKeyboardSaveResult saveVirtualKeyboardLayout(@NonNull String appPath, long expectedAppId,
			@NonNull byte[] payload, @Nullable String requestedPresetName) {
		if (expectedAppId <= 0L || payload.length == 0
				|| payload.length > PresetAuthorityContract.MAX_LAYOUT_PAYLOAD_BYTES) {
			return VirtualKeyboardSaveResult.layoutFailed();
		}
		Bundle extras = identityExtras(appPath, expectedAppId);
		extras.putByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD, payload);
		if (requestedPresetName != null) {
			extras.putString(PresetAuthorityContract.KEY_UPDATE_TARGET, requestedPresetName);
		}
		Bundle result = call(PresetAuthorityContract.METHOD_SAVE_VIRTUAL_KEYBOARD_LAYOUT, extras);
		if (result == null) {
			return VirtualKeyboardSaveResult.layoutFailed();
		}
		int resultCode = result.getInt(
				PresetAuthorityContract.KEY_RESULT, PresetAuthorityContract.RESULT_FAILED);
		if (resultCode == PresetAuthorityContract.RESULT_STALE) {
			return VirtualKeyboardSaveResult.layoutStale();
		}
		if (resultCode != PresetAuthorityContract.RESULT_OK) {
			return VirtualKeyboardSaveResult.layoutFailed();
		}
		return VirtualKeyboardSaveResult.layoutCommitted(switch (result.getInt(
				PresetAuthorityContract.KEY_UPDATE_OUTCOME, PresetAuthorityContract.UPDATE_NONE)) {
			case PresetAuthorityContract.UPDATE_LINKED -> VirtualKeyboardSaveResult.PresetUpdateOutcome.LINKED;
			case PresetAuthorityContract.UPDATE_SAVED_UNLINKED -> VirtualKeyboardSaveResult.PresetUpdateOutcome.SAVED_UNLINKED;
			case PresetAuthorityContract.UPDATE_FAILED -> VirtualKeyboardSaveResult.PresetUpdateOutcome.FAILED;
			default -> VirtualKeyboardSaveResult.PresetUpdateOutcome.NONE;
		});
	}

	private static Bundle identityExtras(String appPath, long expectedAppId) {
		Bundle extras = new Bundle();
		extras.putString(PresetAuthorityContract.KEY_APP_PATH, appPath);
		extras.putLong(PresetAuthorityContract.KEY_EXPECTED_APP_ID, expectedAppId);
		return extras;
	}

	@Nullable
	private Bundle call(String method, Bundle extras) {
		try {
			return resolver.call(authorityUri, method, null, extras);
		} catch (RuntimeException failure) {
			return null;
		}
	}
}
