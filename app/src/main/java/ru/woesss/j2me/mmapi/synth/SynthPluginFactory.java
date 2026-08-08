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

import io.github.h3nb.jlmodplus.BuildConfig;
import io.github.h3nb.jlmodplus.R;
import ru.woesss.j2me.mmapi.Plugin;
import ru.woesss.j2me.mmapi.synth.eas.LibEAS;
import ru.woesss.j2me.mmapi.synth.tsf.LibTSF;

public class SynthPluginFactory {
	private static final String TAG = SynthPluginFactory.class.getSimpleName();
	private static final String DEBUG_FORCE_TSF_PROPERTY = "j2me.mmapi.sf2.force_tsf";

	enum Backend {
		BUILTIN,
		EAS,
		TSF
	}

	/** SONiVOX 4 is the primary synth for both supported custom bank formats. */
	static Backend backendFor(SoundBankResolver.Format format) {
		return backendFor(format, isDebugTsfForced());
	}

	/**
	 * Pure backend decision used by tests and the developer SF2 A/B switch.
	 * The force flag applies only to SF2; DLS always stays on SONiVOX.
	 */
	static Backend backendFor(SoundBankResolver.Format format, boolean forceTsfForSf2) {
		if (format == SoundBankResolver.Format.SF2 && forceTsfForSf2) {
			return Backend.TSF;
		}
		if (format == SoundBankResolver.Format.DLS || format == SoundBankResolver.Format.SF2) {
			return Backend.EAS;
		}
		return Backend.BUILTIN;
	}

	/** TinySoundFont remains a temporary compatibility fallback during SF2 parity testing. */
	static Backend fallbackBackendFor(SoundBankResolver.Format format) {
		return format == SoundBankResolver.Format.SF2 ? Backend.TSF : null;
	}

	/**
	 * Debug-only developer override for rendering the same SF2 through TSF.
	 * Release builds ignore the property so games cannot turn this into a
	 * user-visible backend selector.
	 */
	static boolean isDebugTsfForced() {
		return BuildConfig.DEBUG && Boolean.parseBoolean(System.getProperty(DEBUG_FORCE_TSF_PROPERTY, "false"));
	}

	public static void loadPlugins(List<Plugin> plugins) {
		String soundBank = MicroLoader.getSoundBank();
		SoundBankResolver.Format format = MicroLoader.getSoundBankFormat();
		Backend backend = backendFor(format);
		if (soundBank == null || backend == Backend.BUILTIN) {
			plugins.add(new MIDIDevicePlugin());
			return;
		}

		// Keep built-in EAS available as a safe fallback for device/sequenced
		// playback if a selected custom bank cannot be initialized.
		try {
			plugins.add(new MIDIDevicePlugin());
		} catch (Throwable e) {
			Log.w(TAG, "create built-in MIDI plugin failed", e);
		}

		boolean customLoaded = false;
		if (backend == Backend.TSF) {
			try {
				plugins.add(0, new SynthPlugin(new LibTSF(soundBank)));
				customLoaded = true;
				Log.i(TAG, "debug SF2 A/B override: using TinySoundFont");
			} catch (Throwable e) {
				Log.w(TAG, "create forced TinySoundFont SF2 plugin failed", e);
			}
		} else {
			try {
				plugins.add(0, new SynthPlugin(new LibEAS(soundBank)));
				customLoaded = true;
			} catch (Throwable e) {
				Log.w(TAG, "create SONiVOX " + format + " soundbank plugin failed", e);
			}

			if (!customLoaded && fallbackBackendFor(format) == Backend.TSF) {
				try {
					plugins.add(0, new SynthPlugin(new LibTSF(soundBank)));
					customLoaded = true;
					Log.w(TAG, "falling back to TinySoundFont for SF2 compatibility");
				} catch (Throwable e) {
					Log.w(TAG, "create TinySoundFont SF2 fallback failed", e);
				}
			}
		}

		if (!customLoaded) {
			ContextHolder.getActivity().toast(R.string.msg_unsupported_soundbank);
		}
		if (plugins.isEmpty()) {
			plugins.add(new MIDIDevicePlugin());
		}
	}
}
