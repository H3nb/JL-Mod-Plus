/*
 * Copyright 2023 Yury Kharchenko
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

package io.github.h3nb.jlmodplus.mmapi.synth;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.protocol.DataSource;

import io.github.h3nb.jlmodplus.mmapi.Plugin;
import io.github.h3nb.jlmodplus.mmapi.protocol.device.DeviceDataSource;

public class SynthPlugin implements Plugin {
	private final Library library;

	public SynthPlugin(Library library) { this.library = library; }

	@Override
	public Player createPlayer(DataSource source) throws MediaException {
		String locator = source.getLocator();
		if (!Manager.MIDI_DEVICE_LOCATOR.equals(locator) && !Manager.TONE_DEVICE_LOCATOR.equals(locator)) {
			try {
				if (!recognizes(source)) return null;
			} catch (IOException e) {
				throw new MediaException("Cannot read synthesis source: " + e);
			}
		}
		return new AudioPlayer(library, source);
	}

	@Override
	public Player createPlayer(String locator) throws MediaException {
		if (!Manager.MIDI_DEVICE_LOCATOR.equals(locator) && !Manager.TONE_DEVICE_LOCATOR.equals(locator)) return null;
		return createPlayer(new DeviceDataSource(locator));
	}

	/** Bounded recognition; MIME is only a hint when the header is inconclusive. */
	private static boolean recognizes(DataSource source) throws IOException {
		byte[] header;
		try (FileInputStream input = new FileInputStream(source.getLocator())) {
			byte[] buffer = new byte[256];
			int size = 0;
			while (size < buffer.length) {
				int read = input.read(buffer, size, buffer.length - size);
				if (read <= 0) break;
				size += read;
			}
			header = java.util.Arrays.copyOf(buffer, size);
		}
		String text = new String(header, StandardCharsets.ISO_8859_1);
		if (text.startsWith("MThd") || text.startsWith("XMF_") || text.startsWith("BEGIN:IMELODY") ||
				text.startsWith("BEGIN:iMelody") || text.matches("(?s)[^:]{1,64}:[^:]*[dDoObB]\\s*=.*:.*")) return true;
		if (header.length >= 2 && (header[0] == (byte) 0xfe ||
				((header[0] & 255) > 1 && ((header[1] & 255) >> 1) == 0x25))) return true;
		if (text.startsWith("RIFF")) return text.length() >= 12 && text.substring(8, 12).equals("RMID");
		if (text.startsWith("ID3") || text.startsWith("OggS") || text.startsWith("fLaC") ||
				text.startsWith("#!AMR") || text.startsWith("MMMD") || text.startsWith("FORM") ||
				(header.length >= 2 && (header[0] & 255) == 255 && (header[1] & 224) == 224) ||
				(text.length() >= 8 && text.substring(4, 8).equals("ftyp"))) return false;
		String type = source.getContentType();
		if (type == null) return false;
		return switch (type.toLowerCase(Locale.ROOT)) {
			case "audio/midi", "audio/x-midi", "audio/sp-midi", "audio/x-tone-seq", "audio/xmf",
					"audio/mobile-xmf", "audio/imelody", "text/x-imelody", "audio/rtttl", "audio/x-rtttl",
					"audio/ota", "audio/x-ota" -> true;
			default -> false;
		};
	}
}
