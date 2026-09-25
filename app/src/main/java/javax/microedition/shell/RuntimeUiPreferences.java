/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Runtime-process-owned UI preferences kept separate from main-process application metadata. */
final class RuntimeUiPreferences {
	static final String FILE_NAME = "runtime_ui_preferences";
	static final String HIDE_LAYOUT_EDIT_GUIDE = "hide_layout_edit_guide";

	private RuntimeUiPreferences() {
	}

	@NonNull
	static SharedPreferences get(@NonNull Context context) {
		return context.getApplicationContext()
				.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
	}
}
