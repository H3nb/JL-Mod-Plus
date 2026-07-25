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

package ru.woesss.j2me.mmapi.synth;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Resolves a user-selected soundbank without allowing the profile value to
 * escape the application's soundbank directory.
 *
 * <p>The file signature is authoritative. Extensions are deliberately not
 * used because J2ME applications and user-created soundbanks do not always
 * use conventional filenames.</p>
 */
public final class SoundBankResolver {
	private static final int RIFF_HEADER_SIZE = 12;

	private SoundBankResolver() {
	}

	public enum Format {
		DLS,
		SF2
	}

	public static final class ResolvedSoundBank {
		private final File file;
		private final Format format;

		private ResolvedSoundBank(File file, Format format) {
			this.file = file;
			this.format = format;
		}

		public File getFile() {
			return file;
		}

		public Format getFormat() {
			return format;
		}
	}

	/**
	 * Resolves a selected filename below {@code soundBankDirectory}.
	 *
	 * @return a canonical, regular DLS/SF2 file, or {@code null} for the
	 *         default bank, a missing file, an invalid path, or an unsupported
	 *         file signature
	 */
	public static ResolvedSoundBank resolve(File soundBankDirectory, String selectedName)
			throws IOException {
		if (soundBankDirectory == null || selectedName == null || selectedName.trim().isEmpty()) {
			return null;
		}

		File canonicalDirectory = soundBankDirectory.getCanonicalFile();
		if (!canonicalDirectory.isDirectory()) {
			return null;
		}

		File candidate = new File(canonicalDirectory, selectedName).getCanonicalFile();
		String directoryPath = canonicalDirectory.getPath();
		String candidatePath = candidate.getPath();
		if (!candidatePath.equals(directoryPath)
				&& !candidatePath.startsWith(directoryPath + File.separator)) {
			return null;
		}
		if (!candidate.isFile()) {
			return null;
		}

		Format format = detectFormat(candidate);
		return format == null ? null : new ResolvedSoundBank(candidate, format);
	}

	/**
	 * Detects the RIFF form used by the soundbank formats supported today.
	 */
	public static Format detectFormat(File file) throws IOException {
		byte[] header = new byte[RIFF_HEADER_SIZE];
		try (FileInputStream input = new FileInputStream(file)) {
			int offset = 0;
			while (offset < header.length) {
				int read = input.read(header, offset, header.length - offset);
				if (read < 0) {
					return null;
				}
				if (read == 0) {
					continue;
				}
				offset += read;
			}
		}

		if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
			return null;
		}

		if (header[8] == 'D' && header[9] == 'L' && header[10] == 'S' && header[11] == ' ') {
			return Format.DLS;
		}
		if (header[8] == 's' && header[9] == 'f' && header[10] == 'b' && header[11] == 'k') {
			return Format.SF2;
		}
		return null;
	}
}
