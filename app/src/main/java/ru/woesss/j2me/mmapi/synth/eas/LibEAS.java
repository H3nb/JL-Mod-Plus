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

package ru.woesss.j2me.mmapi.synth.eas;

import android.content.Context;

import androidx.preference.PreferenceManager;

import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.util.Constants;
import ru.woesss.j2me.mmapi.synth.Library;

/** SONiVOX-backed synth library with per-instance soundbank and render-rate selection. */
public abstract class LibEAS implements Library {
	public static final int SAMPLE_RATE_22050 = 22050;
	public static final int SAMPLE_RATE_44100 = 44100;

	private String soundBank;

	protected LibEAS() {
		soundBank = null;
	}

	public static LibEAS create() {
		return create(null);
	}

	public static LibEAS create(String soundBank) {
		LibEAS library = selectedSampleRate() == SAMPLE_RATE_44100
				? new LibEAS44()
				: new LibEAS22();
		library.loadSoundBank(soundBank);
		return library;
	}

	static int normalizeSampleRate(String value) {
		return "44100".equals(value) ? SAMPLE_RATE_44100 : SAMPLE_RATE_22050;
	}

	private static int selectedSampleRate() {
		try {
			Context context = ContextHolder.getAppContext();
			if (context != null) {
				return normalizeSampleRate(PreferenceManager.getDefaultSharedPreferences(context)
						.getString(Constants.PREF_MIDI_SAMPLE_RATE, "22050"));
			}
		} catch (RuntimeException ignored) {
			// Keep the compatibility rate if app context is unavailable during startup/tests.
		}
		return SAMPLE_RATE_22050;
	}

	@Override
	public final void loadSoundBank(String soundBank) {
		if (soundBank == null || soundBank.isEmpty()) {
			this.soundBank = null;
			return;
		}

		// Validate before replacing the active bank. Callers can catch this and
		// fall back to the built-in SONiVOX bank without leaving partial state.
		nativeValidateSoundBank(soundBank);
		this.soundBank = soundBank;
	}

	@Override
	public final long createPlayer(String locator) {
		return nativeCreatePlayer(locator, soundBank);
	}

	@Override public final void finalize(long handle) { nativeFinalize(handle); }
	@Override public final void realize(long handle) { nativeRealize(handle); }
	@Override public final void prefetch(long handle) { nativePrefetch(handle); }
	@Override public final void start(long handle) { nativeStart(handle); }
	@Override public final void pause(long handle) { nativePause(handle); }
	@Override public final void deallocate(long handle) { nativeDeallocate(handle); }
	@Override public final void close(long handle) { nativeClose(handle); }
	@Override public final long setMediaTime(long handle, long now) { return nativeSetMediaTime(handle, now); }
	@Override public final long getMediaTime(long handle) { return nativeGetMediaTime(handle); }
	@Override public final void setRepeat(long handle, int count) { nativeSetRepeat(handle, count); }
	@Override public final void setVolume(long handle, float left, float right) { nativeSetVolume(handle, left, right); }
	@Override public final long getDuration(long handle) { return nativeGetDuration(handle); }
	@Override public final void setListener(long handle, Object listener) { nativeSetListener(handle, listener); }
	@Override public final void setDataSource(long handle, byte[] data) { nativeSetDataSource(handle, data); }
	@Override public final int writeMIDI(long handle, byte[] data, int offset, int length) {
		return nativeWriteMIDI(handle, data, offset, length);
	}
	@Override public final boolean hasToneControl() { return true; }

	protected abstract void nativeValidateSoundBank(String soundBank);
	protected abstract long nativeCreatePlayer(String locator, String soundBank);
	protected abstract void nativeFinalize(long handle);
	protected abstract void nativeRealize(long handle);
	protected abstract void nativePrefetch(long handle);
	protected abstract void nativeStart(long handle);
	protected abstract void nativePause(long handle);
	protected abstract void nativeDeallocate(long handle);
	protected abstract void nativeClose(long handle);
	protected abstract long nativeSetMediaTime(long handle, long now);
	protected abstract long nativeGetMediaTime(long handle);
	protected abstract void nativeSetRepeat(long handle, int count);
	protected abstract void nativeSetVolume(long handle, float left, float right);
	protected abstract long nativeGetDuration(long handle);
	protected abstract void nativeSetListener(long handle, Object listener);
	protected abstract void nativeSetDataSource(long handle, byte[] data);
	protected abstract int nativeWriteMIDI(long handle, byte[] data, int offset, int length);

	protected static void loadCommonLibraries() {
		System.loadLibrary("c++_shared");
		System.loadLibrary("oboe");
		System.loadLibrary("mmapi_common");
	}
}

final class LibEAS22 extends LibEAS {
	static {
		loadCommonLibraries();
		System.loadLibrary("mmapi_eas_22k");
	}
	@Override protected native void nativeValidateSoundBank(String soundBank);
	@Override protected native long nativeCreatePlayer(String locator, String soundBank);
	@Override protected native void nativeFinalize(long handle);
	@Override protected native void nativeRealize(long handle);
	@Override protected native void nativePrefetch(long handle);
	@Override protected native void nativeStart(long handle);
	@Override protected native void nativePause(long handle);
	@Override protected native void nativeDeallocate(long handle);
	@Override protected native void nativeClose(long handle);
	@Override protected native long nativeSetMediaTime(long handle, long now);
	@Override protected native long nativeGetMediaTime(long handle);
	@Override protected native void nativeSetRepeat(long handle, int count);
	@Override protected native void nativeSetVolume(long handle, float left, float right);
	@Override protected native long nativeGetDuration(long handle);
	@Override protected native void nativeSetListener(long handle, Object listener);
	@Override protected native void nativeSetDataSource(long handle, byte[] data);
	@Override protected native int nativeWriteMIDI(long handle, byte[] data, int offset, int length);
}

final class LibEAS44 extends LibEAS {
	static {
		loadCommonLibraries();
		System.loadLibrary("mmapi_eas_44k");
	}
	@Override protected native void nativeValidateSoundBank(String soundBank);
	@Override protected native long nativeCreatePlayer(String locator, String soundBank);
	@Override protected native void nativeFinalize(long handle);
	@Override protected native void nativeRealize(long handle);
	@Override protected native void nativePrefetch(long handle);
	@Override protected native void nativeStart(long handle);
	@Override protected native void nativePause(long handle);
	@Override protected native void nativeDeallocate(long handle);
	@Override protected native void nativeClose(long handle);
	@Override protected native long nativeSetMediaTime(long handle, long now);
	@Override protected native long nativeGetMediaTime(long handle);
	@Override protected native void nativeSetRepeat(long handle, int count);
	@Override protected native void nativeSetVolume(long handle, float left, float right);
	@Override protected native long nativeGetDuration(long handle);
	@Override protected native void nativeSetListener(long handle, Object listener);
	@Override protected native void nativeSetDataSource(long handle, byte[] data);
	@Override protected native int nativeWriteMIDI(long handle, byte[] data, int offset, int length);
}
