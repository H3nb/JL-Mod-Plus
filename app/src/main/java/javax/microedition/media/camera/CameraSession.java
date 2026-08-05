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

import javax.microedition.media.MediaException;

/** Platform-neutral lifecycle boundary for a camera Player. */
public interface CameraSession {
	void prepare() throws MediaException;

	void attachPreview(Object previewView);

	void detachPreview(Object previewView);

	void start() throws MediaException;

	void stop() throws MediaException;

	byte[] capture(SnapshotRequest request) throws MediaException;

	void release();
}
