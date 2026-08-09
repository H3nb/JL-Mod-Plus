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

import ru.woesss.j2me.mmapi.FileCacheDataSource;
import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.audio.ContentProbe;
import ru.woesss.j2me.mmapi.audio.SmafFileFormat;
import ru.woesss.j2me.mmapi.audio.SmafNativeRenderer;
import ru.woesss.j2me.mmapi.audio.SmafWaveformRenderer;
import ru.woesss.j2me.mmapi.audio.WavPlayer;
import ru.woesss.j2me.mmapi.protocol.device.DeviceDataSource;

public class SynthPlugin implements Plugin {
	private static final String TAG = SynthPlugin.class.getSimpleName();

	private final Library library;

	public SynthPlugin(Library library) {
		this.library = library;
	}

	@Override
	public Player createPlayer(DataSource dataSource) {
		if (dataSource == null || dataSource.getLocator() == null) {
			return null;
		}

		ContentProbe.Kind kind = probe(dataSource);
		if (kind == ContentProbe.Kind.SMAF) {
			return createSmafPlayer(dataSource);
		}
		if (!acceptsSequencedData(dataSource, kind)) {
			return null;
		}
		try {
			return new SynthPlayer(library, dataSource);
		} catch (Exception e) {
			Log.w(TAG, "createPlayer: ", e);
			return null;
		}
	}

	private Player createSmafPlayer(DataSource source) {
		try {
			File smaf = new File(source.getLocator());
			SmafFileFormat.Info info = SmafFileFormat.inspect(smaf);
			if (info == null) {
				return null;
			}

			// Pure ATR/Awa stays on the already-validated R5 path. Do not fall
			// through to the pinned upstream parser when the fallback rejects a
			// file: that revision does not model inherited ATR/Awa metadata safely.
			if (info.hasAwa()) {
				return isPureAwa(info) ? createAwaFallbackPlayer(source, smaf) : null;
			}

			// Keep the native dependency behind the subset its pinned parser is
			// known to interpret correctly. In particular, that revision decodes
			// Mwa WaveType and the extended 10/20/40/50 ms timebase codes wrongly.
			if (!isNativeScoreSafe(info)) {
				return null;
			}

			return createNativeSmafPlayer(source, smaf);
		} catch (Exception e) {
			Log.w(TAG, "Unable to create SMAF player", e);
			return null;
		}
	}

	private static boolean isPureAwa(SmafFileFormat.Info info) {
		return info != null
				&& !info.hasScore()
				&& info.getPcmAudioTrackCount() == 1
				&& info.hasAwa()
				&& !info.hasMwa();
	}

	private static boolean isNativeScoreSafe(SmafFileFormat.Info info) {
		if (info.getScoreTrackCount() != 1
				|| info.hasPcmAudioTrack()
				|| info.hasAwa()
				|| info.hasMwa()) {
			return false;
		}

		SmafFileFormat.TrackInfo track = info.getFirstScoreTrack();
		if (track == null
				|| track.getFormatType() == SmafFileFormat.FormatType.UNKNOWN
				|| track.getSequenceType() != SmafFileFormat.SequenceType.STREAM_SEQUENCE) {
			return false;
		}

		return isNativeBasicTimeBase(track.getDurationTimeBaseMs())
				&& isNativeBasicTimeBase(track.getGateTimeTimeBaseMs());
	}

	private static boolean isNativeBasicTimeBase(int timeBaseMs) {
		return timeBaseMs == 1 || timeBaseMs == 2 || timeBaseMs == 4 || timeBaseMs == 5;
	}

	private Player createNativeSmafPlayer(DataSource source, File smaf) throws Exception {
		FileCacheDataSource rendered = new FileCacheDataSource(SmafNativeRenderer.CONTENT_TYPE, "wav");
		boolean success = false;
		try {
			if (!SmafNativeRenderer.render(smaf, new File(rendered.getLocator()))) {
				return null;
			}
			Player player = new WavPlayer(rendered, SmafNativeRenderer.CONTENT_TYPE);
			source.disconnect();
			success = true;
			return player;
		} finally {
			if (!success) {
				rendered.disconnect();
			}
		}
	}

	private Player createAwaFallbackPlayer(DataSource source, File smaf) throws Exception {
		FileCacheDataSource rendered = new FileCacheDataSource(SmafWaveformRenderer.CONTENT_TYPE, "wav");
		boolean success = false;
		try {
			if (!SmafWaveformRenderer.render(smaf, new File(rendered.getLocator()))) {
				return null;
			}
			Player player = new WavPlayer(rendered, SmafWaveformRenderer.CONTENT_TYPE);
			source.disconnect();
			success = true;
			return player;
		} finally {
			if (!success) {
				rendered.disconnect();
			}
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

	private static ContentProbe.Kind probe(DataSource dataSource) {
		try {
			return ContentProbe.probe(new File(dataSource.getLocator()));
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Unable to probe synth data source", e);
			return ContentProbe.Kind.UNKNOWN;
		}
	}

	/**
	 * Keep arbitrary unknown binary away from native synth parser discovery.
	 * Signature evidence wins; an explicit synth MIME remains a conservative
	 * fallback for callers that provide a valid MMAPI content type.
	 */
	private static boolean acceptsSequencedData(DataSource dataSource, ContentProbe.Kind kind) {
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
