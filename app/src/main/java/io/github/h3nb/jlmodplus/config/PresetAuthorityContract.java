/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

/** Small Bundle contract shared by the main-process authority provider and the runtime client. */
public final class PresetAuthorityContract {
	public static final String AUTHORITY_SUFFIX = ".preset-authority";

	public static final String METHOD_PREPARE_RUNTIME = "prepareRuntime";
	public static final String METHOD_RESOLVE_UPDATE_TARGET = "resolveUpdateTarget";
	public static final String METHOD_SAVE_VIRTUAL_KEYBOARD_LAYOUT = "saveVirtualKeyboardLayout";

	public static final String KEY_APP_PATH = "appPath";
	public static final String KEY_EXPECTED_APP_ID = "expectedAppId";
	public static final String KEY_RESULT = "result";
	public static final String KEY_APP_ID = "appId";
	public static final String KEY_BUILT_IN_THEME_LINKED = "builtInThemeLinked";
	public static final String KEY_UPDATE_TARGET = "updateTarget";
	public static final String KEY_LAYOUT_PAYLOAD = "layoutPayload";
	public static final String KEY_UPDATE_OUTCOME = "updateOutcome";

	public static final int RESULT_OK = 0;
	public static final int RESULT_STALE = 1;
	public static final int RESULT_INVALID = 2;
	public static final int RESULT_FAILED = 3;

	public static final int UPDATE_NONE = 0;
	public static final int UPDATE_LINKED = 1;
	public static final int UPDATE_SAVED_UNLINKED = 2;
	public static final int UPDATE_FAILED = 3;

	/** Binder payload guard; real keyboard layouts are far smaller than this ceiling. */
	public static final int MAX_LAYOUT_PAYLOAD_BYTES = 256 * 1024;

	private PresetAuthorityContract() {
	}
}
