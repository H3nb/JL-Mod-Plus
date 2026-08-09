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

import ru.woesss.j2me.mmapi.synth.Library;

/** SONiVOX-backed synth library using the fixed 44.1 kHz renderer. */
public final class LibEAS implements Library {
	private String soundBank;

	static {
		System.loadLibrary("c++_shared");
		System.loadLibrary("oboe");
		System.loadLibrary("mmapi_common");
		System.loadLibrary("mmapi_eas");
	}

	public LibEAS() {
		soundBank = null;
	}

	public static LibEAS create() {
		return new LibEAS();
	}

	public static LibEAS create(String soundBank) {
		LibEAS library = new LibEAS();
		library.loadSoundBank(soundBank);
		return library;
	}

	@Override
	public void loadSoundBank(String soundBank) {
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
	public long createPlayer(String locator) {
		return nativeCreatePlayer(locator, soundBank);
	}

	@Override public void finalize(long handle) { nativeFinalize(handle); }
	@Override public void realize(long handle) { nativeRealize(handle); }
	@Override public void prefetch(long handle) { nativePrefetch(handle); }
	@Override public void start(long handle) { nativeStart(handle); }
	@Override public void pause(long handle) { nativePause(handle); }
	@Override public void deallocate(long handle) { nativeDeallocate(handle); }
	@Override public void close(long handle) { nativeClose(handle); }
	@Override public long setMediaTime(long handle, long now) { return nativeSetMediaTime(handle, now); }
	@Override public long getMediaTime(long handle) { return nativeGetMediaTime(handle); }
	@Override public void setRepeat(long handle, int count) { nativeSetRepeat(handle, count); }
	@Override public void setVolume(long handle, float left, float right) { nativeSetVolume(handle, left, right); }
	@Override public long getDuration(long handle) { return nativeGetDuration(handle); }
	@Override public void setListener(long handle, Object listener) { nativeSetListener(handle, listener); }
	@Override public void setDataSource(long handle, byte[] data) { nativeSetDataSource(handle, data); }
	@Override public int writeMIDI(long handle, byte[] data, int offset, int length) {
		return nativeWriteMIDI(handle, data, offset, length);
	}
	@Override public boolean hasToneControl() { return true; }

	private native void nativeValidateSoundBank(String soundBank);
	private native long nativeCreatePlayer(String locator, String soundBank);
	private native void nativeFinalize(long handle);
	private native void nativeRealize(long handle);
	private native void nativePrefetch(long handle);
	private native void nativeStart(long handle);
	private native void nativePause(long handle);
	private native void nativeDeallocate(long handle);
	private native void nativeClose(long handle);
	private native long nativeSetMediaTime(long handle, long now);
	private native long nativeGetMediaTime(long handle);
	private native void nativeSetRepeat(long handle, int count);
	private native void nativeSetVolume(long handle, float left, float right);
	private native long nativeGetDuration(long handle);
	private native void nativeSetListener(long handle, Object listener);
	private native void nativeSetDataSource(long handle, byte[] data);
	private native int nativeWriteMIDI(long handle, byte[] data, int offset, int length);
}
