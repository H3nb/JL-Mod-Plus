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

package ru.woesss.j2me.mmapi.audio;

import java.util.Locale;

/**
 * Chooses the broad audio backend family after content probing.
 *
 * <p>Stable content signatures always take precedence over a caller supplied
 * MIME type. MIME is only used as a fallback when the content is inconclusive.
 * UNKNOWN deliberately preserves the legacy fallback path until a format has
 * an explicitly tested backend.</p>
 */
public final class MediaRouter {
	public enum Backend {
		SYNTH,
		WAV,
		PLATFORM_AUDIO,
		UNKNOWN
	}

	private MediaRouter() {
	}

	public static Backend route(ContentProbe.Kind kind, String declaredMime) {
		if (kind == null) {
			kind = ContentProbe.Kind.UNKNOWN;
		}

		switch (kind) {
			case MIDI:
			case XMF:
			case RMID:
				return Backend.SYNTH;
			case WAV:
				return Backend.WAV;
			case MP3:
			case AAC:
			case AMR:
			case AMR_WB:
			case MP4:
				return Backend.PLATFORM_AUDIO;
			case UNKNOWN:
			default:
				return routeMime(declaredMime);
		}
	}

	private static Backend routeMime(String declaredMime) {
		if (declaredMime == null) {
			return Backend.UNKNOWN;
		}

		String mime = declaredMime.trim().toLowerCase(Locale.ROOT);
		int parameters = mime.indexOf(';');
		if (parameters >= 0) {
			mime = mime.substring(0, parameters).trim();
		}

		switch (mime) {
			case "audio/midi":
			case "audio/x-midi":
				return Backend.SYNTH;
			case "audio/wav":
			case "audio/x-wav":
				return Backend.WAV;
			case "audio/mpeg":
			case "audio/mp3":
			case "audio/aac":
			case "audio/amr":
			case "audio/amr-wb":
			case "audio/mp4":
				return Backend.PLATFORM_AUDIO;
			default:
				return Backend.UNKNOWN;
		}
	}
}
