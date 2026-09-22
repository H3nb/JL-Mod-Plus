/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Non-exported synchronous IPC endpoint hosted by the default/main application process. */
public final class PresetAuthorityProvider extends ContentProvider {
	private PresetAuthority authority;

	@Override
	public boolean onCreate() {
		if (getContext() == null) return false;
		authority = PresetAuthority.create(getContext());
		return true;
	}

	@Nullable
	@Override
	public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
		if (authority == null || extras == null) return result(PresetAuthorityContract.RESULT_INVALID);
		String appPath = extras.getString(PresetAuthorityContract.KEY_APP_PATH);
		long expectedAppId = extras.getLong(PresetAuthorityContract.KEY_EXPECTED_APP_ID, -1L);
		if (appPath == null || appPath.trim().isEmpty()) {
			return result(PresetAuthorityContract.RESULT_INVALID);
		}
		try {
			return switch (method) {
				case PresetAuthorityContract.METHOD_PREPARE_RUNTIME ->
						prepare(appPath, expectedAppId);
				case PresetAuthorityContract.METHOD_RESOLVE_UPDATE_TARGET ->
						resolve(appPath, expectedAppId);
				case PresetAuthorityContract.METHOD_SAVE_VIRTUAL_KEYBOARD_LAYOUT ->
						save(appPath, expectedAppId, extras);
				default -> result(PresetAuthorityContract.RESULT_INVALID);
			};
		} catch (RuntimeException failure) {
			return result(PresetAuthorityContract.RESULT_FAILED);
		}
	}

	private Bundle prepare(String appPath, long expectedAppId) {
		PresetAuthority.PrepareResult prepared = authority.prepareRuntime(appPath, expectedAppId);
		Bundle result = result(prepared.code);
		if (prepared.appId > 0L) result.putLong(PresetAuthorityContract.KEY_APP_ID, prepared.appId);
		result.putBoolean(
				PresetAuthorityContract.KEY_BUILT_IN_THEME_LINKED, prepared.builtInThemeLinked);
		return result;
	}

	private Bundle resolve(String appPath, long expectedAppId) {
		PresetAuthority.TargetResult resolved = authority.resolveUpdateTarget(appPath, expectedAppId);
		Bundle result = result(resolved.code);
		if (resolved.name != null) {
			result.putString(PresetAuthorityContract.KEY_UPDATE_TARGET, resolved.name);
		}
		return result;
	}

	private Bundle save(String appPath, long expectedAppId, Bundle extras) {
		byte[] payload = extras.getByteArray(PresetAuthorityContract.KEY_LAYOUT_PAYLOAD);
		String requested = extras.getString(PresetAuthorityContract.KEY_UPDATE_TARGET);
		if (payload == null || payload.length == 0
				|| payload.length > PresetAuthorityContract.MAX_LAYOUT_PAYLOAD_BYTES) {
			return result(PresetAuthorityContract.RESULT_INVALID);
		}
		PresetAuthority.SaveResult saved =
				authority.saveVirtualKeyboardLayout(appPath, expectedAppId, payload, requested);
		Bundle result = result(saved.code);
		result.putInt(PresetAuthorityContract.KEY_UPDATE_OUTCOME, saved.updateOutcome);
		return result;
	}

	private static Bundle result(int code) {
		Bundle result = new Bundle();
		result.putInt(PresetAuthorityContract.KEY_RESULT, code);
		return result;
	}

	@Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
			@Nullable String selection, @Nullable String[] selectionArgs,
			@Nullable String sortOrder) { return null; }
	@Nullable @Override public String getType(@NonNull Uri uri) { return null; }
	@Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
	@Override public int delete(@NonNull Uri uri, @Nullable String selection,
			@Nullable String[] selectionArgs) { return 0; }
	@Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
			@Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
