/*
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

package javax.microedition.media.camera;

import java.io.File;

import javax.microedition.media.MediaException;

/** Optional resumable recording boundary layered on top of a prepared camera session. */
public interface CameraRecordingSession {
	/** Begin a new physical recording. */
	void beginRecording(File outputFile, boolean withAudio, long fileSizeLimit,
			int width, int height) throws MediaException;

	/** Pause media production without finalizing the current recording. */
	void pauseRecording() throws MediaException;

	/** Resume a previously paused recording. */
	void resumeRecording() throws MediaException;

	/**
	 * Finalize the current recording and make its container readable.
	 *
	 * @return true when a backend recording existed and was finalized successfully,
	 * false when there was no backend recording to finalize
	 */
	boolean finalizeRecording() throws MediaException;

	/** True while a recording exists, whether active or paused. */
	boolean hasRecording();

	/** True only while media is actively being written. */
	boolean isRecordingActive();
}
