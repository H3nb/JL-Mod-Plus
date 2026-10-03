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

package io.github.h3nb.jlmodplus.mmapi.synth;

public interface Library {
    default boolean requiresAudioFocus() { return true; }
    default javax.microedition.media.control.VideoControl videoControl() { return null; }
    default void setVideoSizeListener(Runnable listener) {}
    default boolean isSynthesis() { return true; }
    default String[] metadata(long handle) { return null; }
    default String contentType(long handle) { return ""; }
    default boolean isOutputSuspended(long handle) { return false; }
	long createPlayer(String locator);
	void realize(long handle);
	void prefetch(long handle);
	void start(long handle);
	default void start(long handle, long requestEpoch) { start(handle); }
	default void activateMidi(long handle, long requestEpoch) { activateMidi(handle); }
	default long getOutputIdentity(long handle) { return 0; }
	default boolean outputFailed(long handle) { return false; }
	void pause(long handle);
	void deallocate(long handle);
	void close(long handle);
    /** Resolve finite-input seek bounds before acquiring the guest Player lock. */
    default void prepareMediaTime(long handle, long now) throws javax.microedition.media.MediaException {}
	long setMediaTime(long handle, long now);
	long getMediaTime(long handle);
	void setRepeat(long handle, int count);
	void setVolume(long handle, float left, float right);
	long getDuration(long handle);
	void setDataSource(long handle, byte[] data);
	int writeMIDI(long handle, byte[] data, int offset, int length);

	/** Management-thread events: type, media time, source generation, error code. */
	default long[] pollEvent(long handle) { return null; }
	default long getGeneration(long handle) { return 0; }
	default void recoverOutput(long handle) {}
	default void activateMidi(long handle) { start(handle); }
	default void suspendOutput(long handle) { pause(handle); }
	default void resumeOutput(long handle) { start(handle); }
}
