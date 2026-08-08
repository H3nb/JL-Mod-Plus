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

import io.github.h3nb.jlmodplus.crashes.runtime.CrashBreadcrumbStore;
import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.audio.AudioFailure;
import ru.woesss.j2me.mmapi.audio.AudioFailureReporter;
import ru.woesss.j2me.mmapi.synth.SynthPluginFactory;

public class Manager {
	public static final String TONE_DEVICE_LOCATOR = "device://tone";
	public static final String MIDI_DEVICE_LOCATOR = "device://midi";

	private static final String RESOURCE_LOCATOR = "resource://";
	private static final String FILE_LOCATOR = "file://";
	private static final String CAPTURE_LOCATOR_PREFIX = "capture://";
	private static final TimeBase DEFAULT_TIMEBASE = () -> System.nanoTime() / 1000L;
	private static final List<Plugin> PLUGINS = new ArrayList<>();
	private static volatile TimeBase systemTimeBase = DEFAULT_TIMEBASE;

	public static Player createPlayer(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException();
		}
		if (MIDI_DEVICE_LOCATOR.equals(locator) || TONE_DEVICE_LOCATOR.equals(locator)) {
			breadcrumb("mmapi_locator_create device=" + locator);
			for (Plugin plugin : PLUGINS) {
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
			String device = CaptureLocatorParser.deviceOf(locator);
			breadcrumb("mmapi_capture_create device=" + device);
			if (CaptureRequest.DEVICE_VIDEO.equals(device)
					|| CaptureRequest.DEVICE_IMAGE.equals(device)
					|| CaptureRequest.DEVICE_REAR.equals(device)
					|| CaptureRequest.DEVICE_FRONT.equals(device)
					|| CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)) {
				return new CameraPlayer(locator);
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
		breadcrumb("mmapi_datasource_create type=" + safeType(type));
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
				Player pluginPlayer = createPluginPlayer(datasource);
				return pluginPlayer == null ? new MicroPlayer(datasource) : pluginPlayer;
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
		breadcrumb("mmapi_stream_create type=" + safeType(type));
		InternalDataSource datasource;
		try {
			datasource = new InternalDataSource(stream, type);
		} catch (IOException | RuntimeException e) {
			AudioFailureReporter.report(null, type, "MMAPI stream", AudioFailure.Phase.CREATE,
					"STREAM_CACHE_FAILED", e);
			throw e;
		}
		Player pluginPlayer = createPluginPlayer(datasource);
		if (pluginPlayer != null) {
			return pluginPlayer;
		}
		if (isAudioSource(type)) {
			breadcrumb("mmapi_fallback_player type=" + safeType(type));
			return new MicroPlayer(datasource);
		} else {
			datasource.disconnect();
			return new BasePlayer();
		}
	}

	private static Player createPluginPlayer(DataSource datasource) {
		String type = safeType(datasource.getContentType());
		for (Plugin plugin : PLUGINS) {
			String pluginName = plugin.getClass().getSimpleName();
			breadcrumb("mmapi_plugin_try plugin=" + pluginName + " type=" + type);
			Player player = plugin.createPlayer(datasource);
			if (player != null) {
				breadcrumb("mmapi_plugin_selected plugin=" + pluginName + " type=" + type);
				return player;
			}
		}
		return null;
	}

	private static boolean isAudioSource(String type) {
		return type == null || type.toLowerCase(Locale.ROOT).startsWith("audio/");
	}

	private static String safeType(String type) {
		return type == null || type.trim().isEmpty() ? "unknown" : type;
	}

	private static void breadcrumb(String event) {
		try {
			CrashBreadcrumbStore.record(ContextHolder.getAppContext(), event);
		} catch (RuntimeException ignored) {
			// Crash diagnostics must not change MMAPI behavior.
		}
	}

	public static String[] getSupportedContentTypes(String str) {
		String[] audioTypes = new String[]{"audio/wav", "audio/x-wav", "audio/midi", "audio/x-midi",
				"audio/mpeg", "audio/aac", "audio/amr", "audio/amr-wb", "audio/mp3",
				"audio/mp4", "audio/mmf", "audio/x-tone-seq"};
		if ("capture".equals(str)) {
			return new String[]{CaptureRequest.CONTENT_TYPE};
		}
		if (str == null) {
			String[] all = Arrays.copyOf(audioTypes, audioTypes.length + 1);
			all[audioTypes.length] = CaptureRequest.CONTENT_TYPE;
			return all;
		}
		return audioTypes;
	}

	public static String[] getSupportedProtocols(String str) {
		return new String[]{"device", "file", "http", "resource", "capture"};
	}

	public static TimeBase getSystemTimeBase() {
		return systemTimeBase;
	}

	/** Installs the session clock used by players that do not have an explicit master time base. */
	public static void setSystemTimeBase(TimeBase timeBase) {
		systemTimeBase = Objects.requireNonNull(timeBase, "timeBase");
	}

	public synchronized static void playTone(int note, int duration, int volume)
			throws MediaException {
		ToneManager.getInstance().playTone(note, duration, volume);
	}

	static {
		SynthPluginFactory.loadPlugins(PLUGINS);
	}
}
