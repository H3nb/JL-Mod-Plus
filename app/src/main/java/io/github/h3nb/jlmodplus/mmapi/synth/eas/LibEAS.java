/*
 * Copyright 2023-2024 Yury Kharchenko
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

package io.github.h3nb.jlmodplus.mmapi.synth.eas;

import io.github.h3nb.jlmodplus.mmapi.synth.Library;
import io.github.h3nb.jlmodplus.mmapi.RuntimeAudioCoordinator;
import java.nio.charset.StandardCharsets;

public class LibEAS implements Library {
	private final String soundBank;
	private final long sessionId;
	private final boolean sampled;
    private final boolean videoAudio;

	public LibEAS() {
		this(null);
	}

	public LibEAS(String soundBank) { this(soundBank, false); }
	public static LibEAS sampled() { return new LibEAS(null, true); }
    public static LibEAS videoAudio() { return new LibEAS(null, true, true); }
	private LibEAS(String soundBank, boolean sampled) { this(soundBank, sampled, false); }
    private LibEAS(String soundBank, boolean sampled, boolean videoAudio) {
        this.sampled = sampled;
        this.videoAudio = videoAudio;
        this.soundBank = soundBank;
        RuntimeAudioCoordinator audio = RuntimeAudioCoordinator.current();
        sessionId = audio.sessionId();
        audio.attachOutputGate(new RuntimeAudioCoordinator.OutputGate() {
            public void update(long epoch, long minimum, boolean allowed) {
                setPolicy(sessionId, epoch, minimum, allowed);
            }
            public void close() { closeSession(sessionId); }
        });
	}
	@Override
	public long createPlayer(String locator) {
		return createNative(locator, soundBank, sessionId, sampled, videoAudio);
	}
	private native long createNative(String locator, String bank, long sessionId, boolean sampled, boolean videoAudio);
	private static native void setPolicy(long session, long epoch, long minimum, boolean allowed);
	private static native void closeSession(long session);
	private native void activateNative(long handle, boolean midiOnly, long requestEpoch);
	@Override public void start(long handle, long epoch) { activateNative(handle, false, epoch); }
	@Override public void activateMidi(long handle, long epoch) { activateNative(handle, true, epoch); }
	@Override public native long getOutputIdentity(long handle);
	@Override public native boolean outputFailed(long handle);
	public native long[] runtimeDiagnostics(long handle);
    public native void setTimelineOrigin(long handle, long origin);
    /** Independent bounded PCM scan; no output context or playback mutation. */
    public static native long inspectAudioDuration(String path, long origin,
            java.util.function.BooleanSupplier cancelled);
    /** Media us, monotonic ns, generation, output epoch, timestamp available, uncertainty us, bus frame, mapped. */
    public native long[] presentation(long handle);
    public native long[] presentationAt(long handle, long monotonicNanos);
    public native long[] decoderDiagnostics(long handle);
    @Override public boolean isSynthesis() { return !sampled; }
    @Override public native boolean isOutputSuspended(long handle);
    @Override public native String contentType(long handle);
    private native byte[][] metadataBytes(long handle);
    @Override public String[] metadata(long handle) {
        if (!sampled) return null;
        byte[][] bytes = metadataBytes(handle);
        String[] values = new String[bytes.length];
        for (int i = 0; i < bytes.length; ++i) values[i] = new String(bytes[i], StandardCharsets.UTF_8);
        return values;
    }
	@Override
	public native void realize(long handle);
	@Override
	public native void prefetch(long handle);
	@Override
	public native void start(long handle);
	@Override
	public native void pause(long handle);
	@Override
	public native void deallocate(long handle);
	@Override
	public native void close(long handle);
	@Override
	public native long setMediaTime(long handle, long now);
	@Override
	public native long getMediaTime(long handle);
	@Override
	public native void setRepeat(long handle, int count);
	@Override
	public native void setVolume(long handle, float left, float right);
	@Override
	public native long getDuration(long handle);
	@Override
	public native void setDataSource(long handle, byte[] data);
	@Override
	public native int writeMIDI(long handle, byte[] data, int offset, int length);
	@Override
	public native long[] pollEvent(long handle);
	@Override
	public native long getGeneration(long handle);
	@Override
	public native void recoverOutput(long handle);
	@Override
	public native void activateMidi(long handle);
	@Override
	public native void suspendOutput(long handle);
	@Override
	public native void resumeOutput(long handle);
	/** Frames, callbacks, nonzero samples, opens, disconnects, xruns, rate, device, generation. */
	public native long[] diagnostics(long handle);
	public static native int liveHandles();
    public static native String audioDecoderVersion();
    /** Converts only legacy ADPCM MMF into the caller-owned empty WAV file. */
    public static boolean convertLegacySmaf(String input, String output)
            throws javax.microedition.media.MediaException {
        return convertLegacySmafNative(input.getBytes(StandardCharsets.UTF_8),
                output.getBytes(StandardCharsets.UTF_8));
    }
    private static native boolean convertLegacySmafNative(byte[] input, byte[] output)
            throws javax.microedition.media.MediaException;

	static {
		System.loadLibrary("c++_shared");
		System.loadLibrary("oboe");
		System.loadLibrary("mmapi_eas");
	}
}
