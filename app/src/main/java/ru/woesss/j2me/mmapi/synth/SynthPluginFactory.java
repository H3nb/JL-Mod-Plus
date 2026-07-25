/*
 * Copyright 2023-2024 Yury Kharchenko
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

import java.util.List;

import javax.microedition.shell.MicroLoader;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.synth.eas.LibEAS;
import ru.woesss.j2me.mmapi.synth.tsf.LibTSF;

public class SynthPluginFactory {
	private static final String TAG = SynthPluginFactory.class.getSimpleName();

	enum Backend {
		BUILTIN,
		EAS,
		TSF
	}

	static Backend backendFor(SoundBankResolver.Format format) {
		if (format == SoundBankResolver.Format.DLS) {
			return Backend.EAS;
		}
		if (format == SoundBankResolver.Format.SF2) {
			return Backend.TSF;
		}
		return Backend.BUILTIN;
	}

	public static void loadPlugins(List<Plugin> plugins) {
		String soundBank = MicroLoader.getSoundBank();
		SoundBankResolver.Format format = MicroLoader.getSoundBankFormat();
		Backend backend = backendFor(format);
		if (soundBank == null || backend == Backend.BUILTIN) {
			plugins.add(new MIDIDevicePlugin());
			return;
		}
		// A custom SF2 backend cannot handle device://midi or the IMA WAV
		// fallback. Keep the built-in EAS device/decoder available after it.
		try {
			plugins.add(new MIDIDevicePlugin());
		} catch (Throwable e) {
			Log.w(TAG, "create built-in MIDI plugin failed", e);
		}
		try {
			if (backend == Backend.EAS) {
				plugins.add(0, new SynthPlugin(new LibEAS(soundBank)));
			} else if (backend == Backend.TSF) {
				plugins.add(0, new SynthPlugin(new LibTSF(soundBank)));
			}
		} catch (Throwable e) {
			Log.w(TAG, "create " + format + " soundbank plugin failed", e);
		}
		if (plugins.isEmpty()) {
			ContextHolder.getActivity().toast(R.string.msg_unsupported_soundbank);
			plugins.add(new MIDIDevicePlugin());
		}
	}
}
