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

import javax.microedition.io.Connector;
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
import ru.woesss.j2me.mmapi.synth.SynthPluginFactory;

public class Manager {
	public static final String TONE_DEVICE_LOCATOR = "device://tone";
	public static final String MIDI_DEVICE_LOCATOR = "device://midi";

	private static final String RESOURCE_LOCATOR = "resource://";
	private static final String FILE_LOCATOR = "file://";
	private static final String CAPTURE_LOCATOR_PREFIX = "capture://";
	private static final String[] AUDIO_CONTENT_TYPES = new String[]{
			"audio/wav", "audio/x-wav", "audio/midi", "audio/x-midi",
			"audio/mpeg", "audio/aac", "audio/amr", "audio/amr-wb", "audio/mp3",
			"audio/mp4", "audio/mmf", "audio/x-tone-seq"};
	private static final String[] AUDIO_PROTOCOLS = new String[]{
			"device", "file", "http", "resource"};
	private static final String[] ALL_PROTOCOLS = new String[]{
			"device", "file", "http", "resource", "capture"};
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
			return new MicroPlayer(locator);
		} else if (locator.startsWith(FILE_LOCATOR) || locator.startsWith(RESOURCE_LOCATOR)) {
			InputStream stream = Connector.openInputStream(locator);
			String extension = locator.substring(locator.lastIndexOf('.') + 1);
			String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
			return createPlayer(stream, type);
		} else if (locator.startsWith(CAPTURE_LOCATOR_PREFIX)) {
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
		} else {
			return new BasePlayer();
		}
	}

	public static Player createPlayer(DataSource source) throws IOException, MediaException {
		if (source == null) {
			throw new IllegalArgumentException();
		}
		String type = source.getContentType();
		if (isAudioSource(type)) {
			String locator = source.getLocator();
			try {
				source.connect();
				SourceStream[] sourceStreams = source.getStreams();
				if (sourceStreams == null || sourceStreams.length == 0) {
					throw new MediaException("Audio source has no streams");
				}
				SourceStream sourceStream = sourceStreams[0];
				InputStream stream = new InternalSourceStream(sourceStream);
				InternalDataSource datasource = new InternalDataSource(stream, type);
				return createCachedPlayer(datasource, type);
			} catch (IOException | MediaException | RuntimeException e) {
				AudioFailureReporter.report(locator, type, "MMAPI source", AudioFailure.Phase.CREATE,
						e instanceof MediaException && "Audio source has no streams".equals(e.getMessage())
								? "NO_SOURCE_STREAM" : "SOURCE_CREATE_FAILED", e);
				throw e;
			} finally {
				source.disconnect();
			}
		} else {
			return new BasePlayer();
		}
	}

	public static Player createPlayer(final InputStream stream, String type)
			throws IOException, MediaException {
		if (stream == null) {
			throw new IllegalArgumentException();
		}
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
	 * Routes cached audio by its actual signature before consulting synth plugins.
	 * Known platform/decoded media (especially WAV) must never reach a generic
	 * synth plugin. Sequenced media still keeps the existing Android fallback
	 * until the built-in SONiVOX file path is made explicit in a later phase.
	 */
	private static Player createCachedPlayer(InternalDataSource datasource, String type)
			throws IOException {
		ContentProbe.Kind kind = ContentProbe.probe(new File(datasource.getLocator()));
		MediaRouter.Backend backend = MediaRouter.route(kind, type);

		if (backend != MediaRouter.Backend.PLATFORM_AUDIO) {
			Player pluginPlayer = createPluginPlayer(datasource);
			if (pluginPlayer != null) {
				return pluginPlayer;
			}
		}

		if (backend != MediaRouter.Backend.UNKNOWN || isAudioSource(type)) {
			return new MicroPlayer(datasource);
		}

		datasource.disconnect();
		return new BasePlayer();
	}

	private static Player createPluginPlayer(DataSource datasource) {
		for (Plugin plugin : plugins()) {
			Player player = plugin.createPlayer(datasource);
			if (player != null) {
				return player;
			}
		}
		return null;
	}

	private static boolean isAudioSource(String type) {
		return type == null || type.toLowerCase(Locale.ROOT).startsWith("audio/");
	}

	private static boolean isSupportedAudioContentType(String type) {
		if (type == null) {
			return false;
		}
		for (String supported : AUDIO_CONTENT_TYPES) {
			if (supported.equalsIgnoreCase(type)) {
				return true;
			}
		}
		return false;
	}

	public static String[] getSupportedContentTypes(String protocol) {
		if ("capture".equals(protocol)) {
			// Legacy capture://audio remains routable for compatibility, but its
			// RecordPlayer lifecycle is not yet complete enough to advertise as a
			// JSR-135 capability. Only the camera path is advertised here.
			return new String[]{CaptureRequest.CONTENT_TYPE};
		}
		if (protocol == null) {
			String[] all = Arrays.copyOf(AUDIO_CONTENT_TYPES, AUDIO_CONTENT_TYPES.length + 1);
			all[AUDIO_CONTENT_TYPES.length] = CaptureRequest.CONTENT_TYPE;
			return all;
		}
		if ("device".equals(protocol) || "file".equals(protocol)
				|| "http".equals(protocol) || "resource".equals(protocol)) {
			return Arrays.copyOf(AUDIO_CONTENT_TYPES, AUDIO_CONTENT_TYPES.length);
		}
		return new String[0];
	}

	public static String[] getSupportedProtocols(String contentType) {
		if (contentType == null) {
			return Arrays.copyOf(ALL_PROTOCOLS, ALL_PROTOCOLS.length);
		}
		if (CaptureRequest.CONTENT_TYPE.equalsIgnoreCase(contentType)) {
			return new String[]{"capture"};
		}
		if (isSupportedAudioContentType(contentType)) {
			return Arrays.copyOf(AUDIO_PROTOCOLS, AUDIO_PROTOCOLS.length);
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
