/*
 * Modified for JL-Mod Plus.
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import javax.microedition.media.protocol.DataSource;

import io.github.h3nb.jlmodplus.mmapi.FileCacheDataSource;
import io.github.h3nb.jlmodplus.mmapi.synth.eas.LibEAS;

class InternalDataSource extends FileCacheDataSource {
	private static final String TAG = InternalDataSource.class.getSimpleName();
	private DataSource upstream;
	private boolean disconnected;

	InternalDataSource(InputStream stream, String type) throws IllegalArgumentException, IOException {
		super(type);

		final String name = mediaFile.getName();
		Log.d(TAG, "Starting media pipe: " + name);

		try (RandomAccessFile raf = new RandomAccessFile(mediaFile, "rw")) {
			byte[] buf = new byte[4096];
			int read;
			long cached = 0;
			while ((read = stream.read(buf)) != -1) {
				if (read == 0) {
					int single = stream.read();
					if (single == -1) break;
					buf[0] = (byte) single;
					read = 1;
				}
				if (cached + read > 64L * 1024 * 1024)
					throw new IOException("Audio cache exceeds 64 MiB");
				raf.write(buf, 0, read);
				cached += read;
			}
		} catch (IOException | RuntimeException | Error e) {
			Log.d(TAG, "Media pipe failure: " + e);
			mediaFile.delete();
			throw e;
		}
		Log.d(TAG, "Media pipe closed: " + name);
	}

	InternalDataSource(InputStream stream, String type, DataSource upstream) throws IOException {
		this(stream, type);
		this.upstream = upstream;
	}

	@Override
	public synchronized void disconnect() {
		if (disconnected) return;
		disconnected = true;
		try { if (upstream != null) upstream.disconnect(); }
		finally { super.disconnect(); }
	}

    boolean isSmaf() throws MediaException {
        try (RandomAccessFile input = new RandomAccessFile(mediaFile, "r")) {
            return input.length() >= 4 && input.readInt() == 0x4d4d4d44;
        } catch (IOException error) { throw new MediaException("Cannot read cached audio: " + error); }
    }

	synchronized void prepareLegacySmaf() {
		File converted = null;
		boolean installed = false;
		try {
			converted = createCacheFile(null, ".wav");
			if (LibEAS.convertLegacySmaf(mediaFile.getPath(), converted.getPath())) {
				File original = mediaFile;
				mediaFile = converted;
				installed = true;
				if (!original.delete()) Log.w(TAG, "Cannot delete converted SMAF cache: " + original);
			}
		} catch (IOException | MediaException error) {
			Log.w(TAG, "Legacy SMAF conversion unavailable; retaining original source", error);
		} finally {
			if (!installed && converted != null && !converted.delete())
				Log.w(TAG, "Cannot delete unused SMAF conversion: " + converted);
		}
	}
}
