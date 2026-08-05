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

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

import javax.microedition.util.ContextHolder;

/** Bridges the Android microphone grant for combined audio/video capture. */
public final class MicrophonePermissionBroker {
	public enum Result {
		GRANTED,
		DENIED,
		UNAVAILABLE
	}

	public Result request() {
		if (isGranted()) {
			return Result.GRANTED;
		}
		if (ContextHolder.getActivity() == null) {
			return Result.UNAVAILABLE;
		}
		return ContextHolder.requestPermission(Manifest.permission.RECORD_AUDIO)
				? Result.GRANTED : Result.DENIED;
	}

	public boolean isGranted() {
		return ContextHolder.getActivity() != null
				&& ActivityCompat.checkSelfPermission(
						ContextHolder.getActivity(), Manifest.permission.RECORD_AUDIO)
						== PackageManager.PERMISSION_GRANTED;
	}
}
