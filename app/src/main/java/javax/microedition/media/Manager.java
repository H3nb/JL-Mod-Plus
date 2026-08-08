/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2021-2023 Yury Kharchenko
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

package javax.microedition.media;

import android.Manifest;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.ContentConnection;
import javax.microedition.io.InputConnection;
import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;
import javax.microedition.media.tone.ToneManager;
import javax.microedition.util.ContextHolder;
import javax.microedition.media.camera.CaptureLocatorParser;
import javax.microedition.media.camera.CaptureRequest;

import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.audio.AudioFailure;
import ru.woesss.j2me.mmapi.audio.AudioFailureReporter;
import ru.woesss.j2me.mmapi.audio.ContentProbe;
import ru.woesss.j2me.mmapi.audio.MediaRouter;
import ru.woesss.j2me.mmapi.audio.WavPlayer;
import ru.woesss.j2me.mmapi.synth.SynthPluginFactory;

public class Manager {
	public static final String TONE_DEVICE_LOCATOR = "device://tone";
	public static final String MIDI_DEVICE_LOCATOR = "device://midi";

	private static final String RESOURCE_LOCATOR = "resource://";
	private static final String FILE_LOCATOR = "file://";
	private static final String HTTP_LOCATOR = "http://";
	private static final String HTTPS_LOCATOR = "https://";
	private static final String CAPTURE_LOCATOR_PREFIX = "capture://";

	/* Only formats with an explicit, tested backend are advertised. */
	private static final String[] AUDIO_CONTENT_TYPES = new String[]{
			"audio/wav", "audio/x-wav", "audio/midi", "audio/x-midi",
			"audio/mpeg", "audio/aac", "audio/amr", "audio/amr-wb", "audio/mp3",
			"audio/mp4", "audio/x-tone-seq"};
	private static final String[] DEVICE_CONTENT_TYPES = new String[]{
			"audio/midi", "audio/x-midi", "audio/x-tone-seq"};
	private static final String[] STREAM_PROTOCOLS = new String[]{
			"file", "http", "https", "resource"};
	private static final String[] SEQUENCED_PROTOCOLS = new String[]{
			"device", "file", "http", "https", "resource"};
	private static final String[] ALL_PROTOCOLS = new String[]{
			"device", "file", "http", "https", "resource", "capture"};

	private static final TimeBase DEFAULT_TIMEBASE = () -> System.nanoTime() / 1000L;
	private static volatile TimeBase systemTimeBase = DEFAULT_TIMEBASE;

	private static final class PluginHolder {
		private static final List<Plugin> PLUGINS = loadPlugins();

		private static List<Plugin> loadPlugins() {
			List<Plugin> plugins = new ArrayList<>();
			SynthPluginFactory.loadPlugins(plugins);
			return plugins;
		}
	}

	private static List<Plugin> plugins() {
		return PluginHolder.PLUGINS;
	}

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
			if (TONE_DEVICE_LOCATOR.equals(locator)) {
				// Retain the Java tone-to-MIDI fallback if no synth plugin can supply
				// the dedicated ToneControl device.
				return new MicroPlayer(locator);
			}
			throw new MediaException("No MIDI device backend is available");
		}

		if (locator.startsWith(FILE_LOCATOR)
				|| locator.startsWith(RESOURCE_LOCATOR)
				|| locator.startsWith(HTTP_LOCATOR)
				|| locator.startsWith(HTTPS_LOCATOR)) {
			return createPlayerFromConnection(locator);
		}

		if (locator.startsWith(CAPTURE_LOCATOR_PREFIX)) {
			String device;
			try {
				device = CaptureLocatorParser.deviceOf(locator);
			} catch (IllegalArgumentException e) {
				throw new MediaException("Invalid capture locator: " + e.getMessage());
			}
			if (CaptureRequest.DEVICE_VIDEO.equals(device)
					|| CaptureRequest.DEVICE_IMAGE.equals(device)
					|| CaptureRequest.DEVICE_REAR.equals(device)
					|| CaptureRequest.DEVICE_FRONT.equals(device)
					|| CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)) {
				try {
					return new CameraPlayer(locator);
				} catch (IllegalArgumentException e) {
					throw new MediaException("Invalid capture locator: " + e.getMessage());
				}
			} else if ("audio".equals(device)) {
				if (!ContextHolder.requestPermission(Manifest.permission.RECORD_AUDIO)) {
					throw new SecurityException("Microphone permission was denied");
				}
				return new RecordPlayer();
			}
			throw new MediaException("Unsupported capture device: " + device);
		}

		throw new MediaException("Unsupported media locator: " + locator);
	}

	private static Player createPlayerFromConnection(String locator) throws IOException, MediaException {
		Connection connection = Connector.open(locator, Connector.READ);
		try {
			if (!(connection instanceof InputConnection inputConnection)) {
				throw new MediaException("Media locator is not readable: " + locator);
			}

			String type = null;
			if (connection instanceof ContentConnection contentConnection) {
				type = normalizeMime(contentConnection.getType());
			}
			if (type == null) {
				type = mimeFromLocator(locator);
			}

			try (InputStream stream = inputConnection.openInputStream()) {
				return createPlayer(stream, type);
			}
		} finally {
			connection.close();
		}
	}

	private static String mimeFromLocator(String locator) {
		int end = locator.length();
		int query = locator.indexOf('?');
		if (query >= 0) {
			end = Math.min(end, query);
		}
		int fragment = locator.indexOf('#');
		if (fragment >= 0) {
			end = Math.min(end, fragment);
		}
		int slash = locator.lastIndexOf('/', end - 1);
		int dot = locator.lastIndexOf('.', end - 1);
		if (dot <= slash || dot + 1 >= end) {
			return null;
		}
		String extension = locator.substring(dot + 1, end).toLowerCase(Locale.ROOT);
		return normalizeMime(MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
	}

	public static Player createPlayer(DataSource source) throws IOException, MediaException {
		if (source == null) {
			throw new IllegalArgumentException();
		}

		String type = normalizeMime(source.getContentType());
		String locator = source.getLocator();
		try {
			source.connect();
			SourceStream[] sourceStreams = source.getStreams();
			if (sourceStreams == null || sourceStreams.length == 0) {
				throw new MediaException("Media source has no streams");
			}
			InputStream stream = new InternalSourceStream(sourceStreams[0]);
			InternalDataSource datasource = new InternalDataSource(stream, type);
			return createCachedPlayer(datasource, type);
		} catch (IOException | MediaException | RuntimeException e) {
			AudioFailureReporter.report(locator, type, "MMAPI source", AudioFailure.Phase.CREATE,
					e instanceof MediaException && "Media source has no streams".equals(e.getMessage())
							? "NO_SOURCE_STREAM" : "SOURCE_CREATE_FAILED", e);
			throw e;
		} finally {
			source.disconnect();
		}
	}

	public static Player createPlayer(final InputStream stream, String type)
			throws IOException, MediaException {
		if (stream == null) {
			throw new IllegalArgumentException();
		}
		type = normalizeMime(type);
		InternalDataSource datasource;
		try {
			datasource = new InternalDataSource(stream, type);
		} catch (IOException | RuntimeException e) {
			AudioFailureReporter.report(null, type, "MMAPI stream", AudioFailure.Phase.CREATE,
					"STREAM_CACHE_FAILED", e);
			throw e;
		}
		return createCachedPlayer(datasource, type);
	}

	/**
	 * Routes cached media by its actual signature before consulting MIME hints.
	 * WAV has a dedicated decoder and must never enter a synth backend. Known
	 * compressed formats use Android MediaPlayer. Unknown data gets one chance at
	 * legacy SONiVOX parsers and, only when explicitly declared audio/*, Android's
	 * platform decoder; otherwise creation fails instead of returning a no-op Player.
	 */
	private static Player createCachedPlayer(InternalDataSource datasource, String type)
			throws IOException, MediaException {
		ContentProbe.Kind kind = ContentProbe.probe(new File(datasource.getLocator()));
		MediaRouter.Backend backend = MediaRouter.route(kind, type);

		if (backend == MediaRouter.Backend.WAV) {
			try {
				return new WavPlayer(datasource);
			} catch (MediaException | RuntimeException e) {
				AudioFailureReporter.report(datasource.getLocator(), type, "dr_wav",
						AudioFailure.Phase.CREATE, "WAV_CREATE_FAILED", e);
				datasource.disconnect();
				throw e;
			}
		}

		if (backend != MediaRouter.Backend.PLATFORM_AUDIO) {
			Player pluginPlayer = createPluginPlayer(datasource);
			if (pluginPlayer != null) {
				return pluginPlayer;
			}
		}

		if (backend == MediaRouter.Backend.PLATFORM_AUDIO || isDeclaredAudio(type)) {
			return new MicroPlayer(datasource);
		}

		datasource.disconnect();
		throw new MediaException("Unsupported media content"
				+ (type == null ? "" : ": " + type));
	}

	private static Player createPluginPlayer(DataSource datasource) {
		for (Plugin plugin : plugins()) {
			Player player = plugin.createPlayer(datasource);
			if (player != null) {
				return player;
			}
		return null;
	}

	private static boolean isDeclaredAudio(String type) {
		return type != null && type.startsWith("audio/");
	}

	private static String normalizeMime(String type) {
		if (type == null) {
			return null;
		}
		String normalized = type.trim().toLowerCase(Locale.ROOT);
		int parameters = normalized.indexOf(';');
		if (parameters >= 0) {
			normalized = normalized.substring(0, parameters).trim();
		}
		return normalized.isEmpty() ? null : normalized;
	}

	private static boolean isSupportedAudioContentType(String type) {
		type = normalizeMime(type);
		if (type == null) {
			return false;
		}
		for (String supported : AUDIO_CONTENT_TYPES) {
			if (supported.equals(type)) {
				return true;
			}
		return false;
	}

	private static boolean isDeviceContentType(String type) {
		type = normalizeMime(type);
		if (type == null) {
			return false;
		}
		for (String supported : DEVICE_CONTENT_TYPES) {
			if (supported.equals(type)) {
				return true;
			}
		return false;
	}

	public static String[] getSupportedContentTypes(String protocol) {
		if (protocol != null) {
			protocol = protocol.toLowerCase(Locale.ROOT);
		}
		if ("capture".equals(protocol)) {
			// capture://audio remains routable for compatibility, but RecordPlayer
			// is not complete enough to advertise as a stable JSR-135 capability.
			return new String[]{CaptureRequest.CONTENT_TYPE};
		}
		if (protocol == null) {
			String[] all = Arrays.copyOf(AUDIO_CONTENT_TYPES, AUDIO_CONTENT_TYPES.length + 1);
			all[AUDIO_CONTENT_TYPES.length] = CaptureRequest.CONTENT_TYPE;
			return all;
		}
		if ("device".equals(protocol)) {
			return Arrays.copyOf(DEVICE_CONTENT_TYPES, DEVICE_CONTENT_TYPES.length);
		}
		for (String supportedProtocol : STREAM_PROTOCOLS) {
			if (supportedProtocol.equals(protocol)) {
				return Arrays.copyOf(AUDIO_CONTENT_TYPES, AUDIO_CONTENT_TYPES.length);
			}
		}
		return new String[0];
	}

	public static String[] getSupportedProtocols(String contentType) {
		if (contentType == null) {
			return Arrays.copyOf(ALL_PROTOCOLS, ALL_PROTOCOLS.length);
		}
		String normalized = normalizeMime(contentType);
		if (CaptureRequest.CONTENT_TYPE.equalsIgnoreCase(normalized)) {
			return new String[]{"capture"};
		}
		if (isDeviceContentType(normalized)) {
			return Arrays.copyOf(SEQUENCED_PROTOCOLS, SEQUENCED_PROTOCOLS.length);
		}
		if (isSupportedAudioContentType(normalized)) {
			return Arrays.copyOf(STREAM_PROTOCOLS, STREAM_PROTOCOLS.length);
		}
		return new String[0];
	}

	public static TimeBase getSystemTimeBase() {
		return systemTimeBase;
	}

	/**
	 * Installs the session clock used by players that do not have an explicit
	 * master time base. This is an emulator extension; the default remains the
	 * host clock until a MIDlet session installs its controller.
	 */
	public static void setSystemTimeBase(TimeBase timeBase) {
		systemTimeBase = Objects.requireNonNull(timeBase, "timeBase");
	}

	public synchronized static void playTone(int note, int duration, int volume)
			throws MediaException {
		ToneManager.getInstance().playTone(note, duration, volume);
	}
}
