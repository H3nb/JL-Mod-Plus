/*
 * Copyright 2026 H3NB
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

package io.github.h3nb.jlmodplus.filepicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure file-picker rules kept separate from Android UI and activity state. */
public final class FilePickerModel {
	private static final List<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableList(
			Arrays.asList(".jad", ".jar", ".kjx"));

	private FilePickerModel() {
	}

	@NonNull
	public static File normalizeStartPath(@Nullable File requested, @NonNull File root) {
		File canonicalRoot = canonicalFile(root);
		if (requested == null || requested.getPath().isEmpty()) {
			return canonicalRoot;
		}

		File candidate = canonicalFile(requested);
		if (candidate.isDirectory()) {
			return isWithinRoot(candidate, canonicalRoot) ? candidate : canonicalRoot;
		}

		if (candidate.isFile()) {
			File parent = candidate.getParentFile();
			return parent != null && isWithinRoot(parent, canonicalRoot) ? parent : canonicalRoot;
		}

		// Callers persist a selected file path and may also pass a directory that
		// has not been created yet. Browse its existing parent so the user can
		// create or select that path without changing the storage contract.
		File parent = candidate.getParentFile();
		if (parent != null) {
			parent = canonicalFile(parent);
			if (parent.isDirectory() && isWithinRoot(parent, canonicalRoot)) {
				return parent;
			}
		}
		return canonicalRoot;
	}

	public static boolean isItemVisible(@Nullable File file, int mode) {
		if (file == null) {
			return false;
		}
		if (file.isDirectory()) {
			return true;
		}
		if (mode != FilePickerContract.MODE_FILE
				&& mode != FilePickerContract.MODE_FILE_AND_DIR) {
			return false;
		}
		String extension = getExtension(file);
		return extension != null && SUPPORTED_EXTENSIONS.contains(extension);
	}

	public static boolean isSelectable(@Nullable File file, int mode, boolean allowExistingFile) {
		if (file == null) {
			return false;
		}
		if (file.isDirectory()) {
			return mode == FilePickerContract.MODE_DIR
					|| mode == FilePickerContract.MODE_FILE_AND_DIR;
		}
		return mode == FilePickerContract.MODE_FILE
				|| mode == FilePickerContract.MODE_FILE_AND_DIR
				|| (mode == FilePickerContract.MODE_NEW_FILE && allowExistingFile);
	}

	@Nullable
	public static String getExtension(@NonNull File file) {
		String name = file.getName();
		int index = name.lastIndexOf('.');
		if (index < 0) {
			return null;
		}
		return name.substring(index).toLowerCase(Locale.ROOT);
	}

	public static boolean isValidDirectoryName(@Nullable String name) {
		if (name == null || name.isEmpty() || !name.equals(name.trim())
				|| ".".equals(name) || "..".equals(name)) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char character = name.charAt(i);
			if (character == '/' || character == '\\' || character == '\u0000') {
				return false;
			}
		}
		return true;
	}

	public static void sortFiles(@NonNull List<File> files) {
		Collections.sort(files, new Comparator<File>() {
			@Override
			public int compare(File left, File right) {
				boolean leftDirectory = left.isDirectory();
				boolean rightDirectory = right.isDirectory();
				if (leftDirectory != rightDirectory) {
					return leftDirectory ? -1 : 1;
				}
				int result = left.getName().compareToIgnoreCase(right.getName());
				if (result != 0) {
					return result;
				}
				result = left.getName().compareTo(right.getName());
				if (result != 0) {
					return result;
				}
				return left.getAbsolutePath().compareToIgnoreCase(right.getAbsolutePath());
			}
		});
	}

	public static boolean isSamePath(@NonNull File first, @NonNull File second) {
		return canonicalFile(first).equals(canonicalFile(second));
	}

	public static boolean isWithinRoot(@NonNull File candidate, @NonNull File root) {
		File canonicalCandidate = canonicalFile(candidate);
		File canonicalRoot = canonicalFile(root);
		String rootPath = canonicalRoot.getPath();
		if (File.separator.equals(rootPath)) {
			return true;
		}
		if (rootPath.endsWith(File.separator)) {
			return canonicalCandidate.getPath().startsWith(rootPath);
		}
		return canonicalCandidate.getPath().equals(rootPath)
				|| canonicalCandidate.getPath().startsWith(rootPath + File.separator);
	}

	@NonNull
	public static File canonicalFile(@NonNull File file) {
		try {
			return file.getCanonicalFile();
		} catch (IOException ignored) {
			return file.getAbsoluteFile();
		}
	}
}
