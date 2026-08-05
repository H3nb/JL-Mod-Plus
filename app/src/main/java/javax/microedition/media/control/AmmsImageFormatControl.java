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

package javax.microedition.media.control;

import javax.microedition.amms.control.FormatControl;
import javax.microedition.amms.control.ImageFormatControl;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CameraConfiguration;
import javax.microedition.media.camera.SnapshotRequest;

/** JPEG-only ImageFormatControl for the normalized virtual camera output. */
public final class AmmsImageFormatControl implements ImageFormatControl {
	private static final String JPEG = "encoding=jpeg";

	private final CameraPlayer player;
	private final CameraConfiguration configuration;
	private String format = JPEG;
	private int quality = 90;

	public AmmsImageFormatControl(CameraPlayer player, CameraConfiguration configuration) {
		this.player = player;
		this.configuration = configuration;
	}

	@Override
	public String[] getSupportedFormats() {
		return new String[]{JPEG};
	}

	@Override
	public String[] getSupportedStrParameters() {
		return new String[0];
	}

	@Override
	public String[] getSupportedIntParameters() {
		return new String[]{FormatControl.PARAM_QUALITY};
	}

	@Override
	public String[] getSupportedStrParameterValues(String parameter) {
		if (parameter == null || !parameter.isEmpty()) {
			throw new IllegalArgumentException("unsupported image format string parameter: " + parameter);
		}
		return new String[0];
	}

	@Override
	public int[] getSupportedIntParameterRange(String parameter) {
		if (!FormatControl.PARAM_QUALITY.equals(parameter)) {
			throw new IllegalArgumentException("unsupported image format parameter: " + parameter);
		}
		return new int[]{1, 100};
	}

	@Override
	public void setFormat(String newFormat) {
		if (!JPEG.equalsIgnoreCase(newFormat)) {
			throw new IllegalArgumentException("only JPEG image format is supported");
		}
		format = JPEG;
	}

	@Override
	public String getFormat() {
		return format;
	}

	@Override
	public int setParameter(String parameter, int value) {
		if (!FormatControl.PARAM_QUALITY.equals(parameter) || value < 1 || value > 100) {
			throw new IllegalArgumentException("unsupported image format parameter");
		}
		quality = value;
		return value;
	}

	@Override
	public void setParameter(String parameter, String value) {
		throw new IllegalArgumentException("no string image format parameters are supported");
	}

	@Override
	public String getStrParameterValue(String parameter) {
		throw new IllegalArgumentException("no string image format parameters are supported");
	}

	@Override
	public int getIntParameterValue(String parameter) {
		if (!FormatControl.PARAM_QUALITY.equals(parameter)) {
			throw new IllegalArgumentException("unsupported image format parameter: " + parameter);
		}
		return quality;
	}

	@Override
	public int getEstimatedBitRate() {
		return 0;
	}

	@Override
	public void setMetadata(String key, String value) throws MediaException {
		throw new MediaException("JPEG metadata injection is not supported");
	}

	@Override
	public String[] getSupportedMetadataKeys() {
		return new String[0];
	}

	@Override
	public int getMetadataSupportMode() {
		return FormatControl.METADATA_NOT_SUPPORTED;
	}

	@Override
	public void setMetadataOverride(boolean override) {
		if (override) {
			throw new IllegalArgumentException("JPEG metadata override is not supported");
		}
	}

	@Override
	public boolean getMetadataOverride() {
		return false;
	}

	@Override
	public int getEstimatedImageSize() {
		return Math.min(Integer.MAX_VALUE, configuration.getStillWidth()
				* configuration.getStillHeight() * quality / 32);
	}

	public synchronized SnapshotRequest applyQuality(SnapshotRequest request) {
		return request.withQuality(quality);
	}
}
