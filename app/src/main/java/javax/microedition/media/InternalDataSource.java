/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2023 Yury Kharchenko
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

package javax.microedition.media;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import ru.woesss.j2me.mmapi.FileCacheDataSource;

class InternalDataSource extends FileCacheDataSource {
	private static final String TAG = InternalDataSource.class.getSimpleName();

	InternalDataSource(InputStream stream, String type) throws IllegalArgumentException, IOException {
		super(type);

		final String name = mediaFile.getName();
		Log.d(TAG, "Starting media pipe: " + name);

		try (RandomAccessFile raf = new RandomAccessFile(mediaFile, "rw")) {
			int length = stream.available();
			if (length >= 0) {
				raf.setLength(length);
				Log.d(TAG, "Changing file size to " + length + " bytes: " + name);
			}
			byte[] buf = new byte[4096];
			int read;
			while ((read = stream.read(buf)) != -1) {
				raf.write(buf, 0, read);
			}
		} catch (IOException e) {
			Log.d(TAG, "Media pipe failure: " + e);
			throw e;
		}
		Log.d(TAG, "Media pipe closed: " + name);

		convert();
	}

	private void convert() {
		try {
			String path = mediaFile.getPath();
			MediaInformationSession mediaInformationSession = FFprobeKit.getMediaInformation(path);
			MediaInformation mediaInformation = mediaInformationSession.getMediaInformation();
			if (mediaInformation != null) {
				StreamInformation streamInformation = mediaInformation.getStreams().get(0);
				if (FfmpegAudioConversion.requiresPcmU8Conversion(streamInformation.getCodec())) {
					File pcmU8 = createCacheFile(null, ".wav");
					String cmd = FfmpegAudioConversion.buildPcmU8Command(path, pcmU8.getPath());
					FFmpegSession session = FFmpegKit.execute(cmd);
					ReturnCode rc = session.getReturnCode();
					if (ReturnCode.isSuccess(rc)) {
						Log.i(TAG, "FFmpeg command execution completed successfully.");
						if (!mediaFile.delete()) {
							Log.w(TAG, "convert: error delete file=" + mediaFile);
						}
						mediaFile = pcmU8;
					} else {
						Log.w(TAG, "FFmpeg command execution failed with RETURN_CODE=" + rc);
					}
				}
			}
		} catch (Throwable t) {
			Log.e(TAG, "FFmpeg error", t);
		}
	}
}
