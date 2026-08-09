/*
 * Copyright 2023-2024 Yury Kharchenko
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.woesss.j2me.mmapi.synth;

import android.util.Log;

import java.util.List;

import javax.microedition.shell.MicroLoader;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.audio.AudioFailure;
import ru.woesss.j2me.mmapi.audio.AudioFailureReporter;
import ru.woesss.j2me.mmapi.synth.eas.LibEAS;

/** Creates SONiVOX synth plugins for the built-in or selected DLS/SF2 bank. */
public class SynthPluginFactory {
	private static final String TAG = SynthPluginFactory.class.getSimpleName();

	private SynthPluginFactory() {
	}

	public static void loadPlugins(List<Plugin> plugins) {
		String soundBank = MicroLoader.getSoundBank();
		SoundBankResolver.Format format = MicroLoader.getSoundBankFormat();

		// Always keep the embedded SONiVOX bank as a safe fallback. A bad custom
		// bank must not make otherwise valid MIDI/Tone playback unavailable.
		try {
			plugins.add(new MIDIDevicePlugin());
		} catch (Throwable error) {
			Log.e(TAG, "create built-in SONiVOX plugin failed", error);
		}

		if (soundBank == null || format == null) {
			return;
		}

		try {
			plugins.add(0, new SynthPlugin(LibEAS.create(soundBank)));
		} catch (Throwable error) {
			Log.w(TAG, "create SONiVOX " + format + " soundbank plugin failed", error);
			AudioFailureReporter.report(
					soundBank,
					format == SoundBankResolver.Format.DLS ? "audio/dls" : "audio/sf2",
					"SONiVOX",
					AudioFailure.Phase.CREATE,
					"SOUNDBANK_LOAD_FAILED",
					error
			);
			ContextHolder.getActivity().toast(R.string.msg_unsupported_soundbank);
		}

		if (plugins.isEmpty()) {
			// Preserve the old last-resort behavior if built-in initialization had
			// an unusual transient failure before custom-bank validation.
			plugins.add(new MIDIDevicePlugin());
		}
	}
}
