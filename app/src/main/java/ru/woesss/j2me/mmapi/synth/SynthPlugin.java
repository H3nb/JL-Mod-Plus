/*
 * Copyright 2023 Yury Kharchenko
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

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.microedition.media.Player;
import javax.microedition.media.protocol.DataSource;

import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.audio.ContentProbe;
import ru.woesss.j2me.mmapi.protocol.device.DeviceDataSource;

public class SynthPlugin implements Plugin {
	private static final String TAG = SynthPlugin.class.getSimpleName();

	private final Library library;

	public SynthPlugin(Library library) {
		this.library = library;
	}

	@Override
	public Player createPlayer(DataSource dataSource) {
		if (!acceptsSequencedData(dataSource)) {
			return null;
		}
		try {
			return new SynthPlayer(library, dataSource);
		} catch (Exception e) {
			Log.w(TAG, "createPlayer: ", e);
			return null;
		}
	}

	@Override
	public Player createPlayer(String locator) {
		try {
			return new SynthPlayer(library, new DeviceDataSource(locator));
		} catch (Exception e) {
			Log.w(TAG, "createPlayer: ", e);
			return null;
		}
	}

	/**
	 * Keep arbitrary unknown binary away from native synth parser discovery.
	 * Signature evidence wins; an explicit synth MIME remains a conservative
	 * fallback for callers that provide a valid MMAPI content type.
	 */
	private static boolean acceptsSequencedData(DataSource dataSource) {
		if (dataSource == null || dataSource.getLocator() == null) {
			return false;
		}

		try {
			ContentProbe.Kind kind = ContentProbe.probe(new File(dataSource.getLocator()));
			switch (kind) {
				case MIDI:
				case XMF:
				case RMID:
				case IMELODY:
				case RTTTL:
				case NOKIA_OTA:
					return true;
				case UNKNOWN:
					break;
				default:
					return false;
			}
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Unable to probe synth data source", e);
		}

		String contentType = dataSource.getContentType();
		if (contentType == null) {
			return false;
		}
		String mime = contentType.trim().toLowerCase(Locale.ROOT);
		int parameters = mime.indexOf(';');
		if (parameters >= 0) {
			mime = mime.substring(0, parameters).trim();
		}
		return "audio/midi".equals(mime)
				|| "audio/x-midi".equals(mime)
				|| "audio/x-tone-seq".equals(mime);
	}
}
