/*
 * Copyright 2018 Nikita Shakarun
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.config;

import java.io.File;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.h3nb.jlmodplus.util.FileUtils;

public class Profile implements Comparable<Profile> {

	private String name;

	public Profile(String name) {
		this.name = name;
	}

	public void create() {
		getDir().mkdirs();
	}

	public boolean renameTo(String newName) {
		File oldDir = getDir();
		File newDir = new File(Config.getProfilesDir(), newName);
		name = newName;
		return oldDir.renameTo(newDir);
	}

	public void delete() {
		FileUtils.deleteDirectory(getDir());
	}

	public String getName() {
		return name;
	}

	public File getDir() {
		return new File(Config.getProfilesDir(), name);
	}

	public File getConfig() {
		return new File(Config.getProfilesDir(), name + Config.MIDLET_CONFIG_FILE);
	}

	public File getKeyLayout() {
		return new File(Config.getProfilesDir(), name + Config.MIDLET_KEY_LAYOUT_FILE);
	}

	@Override
	public String toString() {
		return name;
	}

	@Override
	public int compareTo(@NonNull Profile o) {
		Locale locale = Locale.getDefault();
		return name.toLowerCase(locale).compareTo(o.name.toLowerCase(locale));
	}

	boolean hasConfig() {
		return getConfig().exists();
	}

	boolean hasKeyLayout() {
		return getKeyLayout().exists();
	}

	/** Returns whether the saved keyboard artifact has content that can be loaded safely. */
	boolean hasUsableKeyLayout() {
		return Config.isUsableFile(getKeyLayout());
	}

	/** Validates a user-facing name before it is used as a profile directory name. */
	static boolean isValidName(@Nullable String rawName) {
		if (rawName == null) return false;
		String value = rawName.trim();
		if (value.isEmpty() || ".".equals(value) || "..".equals(value)) return false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (character == '/' || character == '\\' || Character.isISOControl(character)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean equals(@Nullable Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof Profile)) {
			return false;
		}
		return name.equals(((Profile) obj).name);
	}

	public boolean hasOldConfig() {
		return new File(Config.getProfilesDir(), name + "/config.xml").exists();
	}
}
