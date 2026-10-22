/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2021-2023 Yury Kharchenko
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

package javax.microedition.media;

import android.Manifest;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.microedition.io.Connector;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;
import javax.microedition.media.tone.ToneManager;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.mmapi.Plugin;
import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import io.github.h3nb.jlmodplus.mmapi.synth.SynthPluginFactory;
import io.github.h3nb.jlmodplus.mmapi.synth.AudioPlayer;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;
import io.github.h3nb.jlmodplus.mmapi.video.VideoFormat;
import io.github.h3nb.jlmodplus.mmapi.video.VideoLibrary;
import io.github.h3nb.jlmodplus.mmapi.camera.CameraCapturePlayer;

public class Manager {
	public static final String TONE_DEVICE_LOCATOR = "device://tone";
	public static final String MIDI_DEVICE_LOCATOR = "device://midi";

	private static final String RESOURCE_LOCATOR = "resource://";
	private static final String FILE_LOCATOR = "file://";
	private static final String CAPTURE_AUDIO_LOCATOR = "capture://audio";
	private static final TimeBase DEFAULT_TIMEBASE = () -> System.nanoTime() / 1000L;
	private static List<Plugin> plugins;
	private static RuntimeAudioCoordinator pluginSession;

	public static Player createPlayer(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException();
		}
		if (MIDI_DEVICE_LOCATOR.equals(locator) || TONE_DEVICE_LOCATOR.equals(locator)) {
			for (Plugin plugin : plugins()) {
				Player player = plugin.createPlayer(locator);
				if (player != null) {
					return player;
				}
			}
			throw new MediaException("Unsupported device locator: " + locator);
		} else if (locator.startsWith(FILE_LOCATOR) || locator.startsWith(RESOURCE_LOCATOR)) {
			String extension = locator.substring(locator.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
			String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
			try (InputStream stream = Connector.openInputStream(locator)) {
				return createPlayer(stream, type);
			}
		} else if (locator.startsWith("capture://video") || "capture://image".equals(locator) || locator.startsWith("capture://audio_video") || locator.startsWith("capture://devcam")) {
			// Construction validates the supported MMAPI locator. Hardware and camera
			// permission acquisition belong to the Player's prefetch/start lifecycle.
			return new CameraCapturePlayer(locator);
		} else if ((CAPTURE_AUDIO_LOCATOR.equals(locator) || locator.startsWith(CAPTURE_AUDIO_LOCATOR + "?"))) {
            validateAudioCaptureLocator(locator);
			return new RecordPlayer();
		} else {
			throw new MediaException("Unsupported media locator: " + locator);
		}
	}

    /** Only advertise the PCM parameters that the one audio capture backend can produce. */
    private static void validateAudioCaptureLocator(String locator) throws MediaException {
        if (CAPTURE_AUDIO_LOCATOR.equals(locator)) return;
        String query = locator.substring(CAPTURE_AUDIO_LOCATOR.length() + 1);
        if (query.isEmpty()) throw new MediaException("Empty audio capture parameters");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : query.split("&", -1)) {
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1 ||
                    part.indexOf('=', equals + 1) >= 0) {
                throw new MediaException("Malformed audio capture parameter");
            }
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            if (!seen.add(key)) throw new MediaException("Repeated audio capture parameter");
            switch (key) {
                case "encoding":
                    if (!"pcm".equalsIgnoreCase(value)) throw new MediaException("Unsupported audio encoding");
                    break;
                case "rate":
                    if (!"16000".equals(value)) throw new MediaException("Unsupported audio rate");
                    break;
                case "bits":
                    if (!"16".equals(value)) throw new MediaException("Unsupported audio bit depth");
                    break;
                case "channels":
                    if (!"1".equals(value)) throw new MediaException("Unsupported audio channels");
                    break;
                default: throw new MediaException("Unsupported audio capture parameter");
            }
        }
    }

	public static Player createPlayer(DataSource source) throws IOException, MediaException {
		if (source == null) {
			throw new IllegalArgumentException();
		}
		if (MIDI_DEVICE_LOCATOR.equals(source.getLocator()) || TONE_DEVICE_LOCATOR.equals(source.getLocator())) {
			for (Plugin plugin : plugins()) {
				Player player = plugin.createPlayer(source);
				if (player != null) return player;
			}
		}
		InternalDataSource cached = null;
		boolean transferred = false;
		try {
			source.connect();
			source.start();
			SourceStream[] streams = source.getStreams();
			if (streams == null || streams.length == 0) throw new MediaException("Media source has no streams");
			cached = new InternalDataSource(new InternalSourceStream(streams[0]), source.getContentType(), source);
			source.stop();
			Player player = createCachedPlayer(cached);
			transferred = true;
			return player;
		} finally {
			if (!transferred) {
				if (cached != null) cached.disconnect();
				else source.disconnect();
			}
		}
	}

	public static Player createPlayer(final InputStream stream, String type)
			throws IOException, MediaException {
		if (stream == null) throw new IllegalArgumentException();
		InternalDataSource cached = new InternalDataSource(stream, type);
		boolean transferred = false;
		try {
			Player player = createCachedPlayer(cached);
			transferred = true;
			return player;
		} finally { if (!transferred) cached.disconnect(); }
	}

	private static Player createCachedPlayer(InternalDataSource source) throws MediaException {
		for (Plugin plugin : plugins()) {
			Player player = plugin.createPlayer(source);
			if (player != null) return player;
		}
        if (source.isSmaf()) {
            source.prepareLegacySmaf();
            return new MicroPlayer(source);
        }
        VideoFormat video = VideoFormat.inspect(source.getLocator());
        if (video != null) return new AudioPlayer(new VideoLibrary(video), source);
        // The native retained demuxer/codec subset resolves actual structure;
        // recognized corrupt media fails here, without another parser/output fallback.
        return new AudioPlayer(LibEAS.sampled(), source);
	}

	public static String[] getSupportedContentTypes(String protocol) {
		if ("device".equals(protocol)) return new String[]{"audio/midi", "audio/x-tone-seq"};
		if ("capture".equals(protocol)) return new String[]{"video/raw", "audio/raw"};
		if (protocol != null && !"file".equals(protocol) && !"resource".equals(protocol)) return new String[0];
		String[] types = new String[]{"audio/wav", "audio/x-wav", "audio/midi", "audio/x-midi",
				"audio/mpeg", "audio/aac", "audio/amr", "audio/amr-wb", "audio/mp3",
				"audio/mp4", "audio/mmf", "audio/x-tone-seq", "audio/sp-midi", "audio/xmf",
				"audio/mobile-xmf", "audio/imelody", "text/x-imelody", "audio/rtttl", "audio/x-rtttl",
				"audio/ota", "audio/x-ota", "video/mp4", "video/3gpp"};
		if (protocol != null) return types;
		String[] allTypes = Arrays.copyOf(types, types.length + 2);
		allTypes[types.length] = "video/raw";
		allTypes[types.length + 1] = "audio/raw";
		return allTypes;
	}

	public static String[] getSupportedProtocols(String contentType) {
		if (contentType == null) return new String[]{"device", "file", "resource", "capture"};
		if ("video/raw".equalsIgnoreCase(contentType) || "audio/raw".equalsIgnoreCase(contentType))
            return new String[]{"capture"};
		if ("audio/midi".equalsIgnoreCase(contentType) ||
				"audio/x-tone-seq".equalsIgnoreCase(contentType)) return new String[]{"device", "file", "resource"};
		for (String supported : getSupportedContentTypes(null)) {
			if (supported.equalsIgnoreCase(contentType)) return new String[]{"file", "resource"};
		}
		return new String[0];
	}

	public static TimeBase getSystemTimeBase() {
		return DEFAULT_TIMEBASE;
	}

	public synchronized static void playTone(int note, int duration, int volume)
			throws MediaException {
		ToneManager.getInstance().playTone(note, duration, volume);
	}

	private static synchronized List<Plugin> plugins() {
		RuntimeAudioCoordinator session = RuntimeAudioCoordinator.current();
		if (pluginSession != session) {
			List<Plugin> loaded = new ArrayList<>();
			SynthPluginFactory.loadPlugins(loaded);
			plugins = loaded;
			pluginSession = session;
		}
		return plugins;
	}
}
