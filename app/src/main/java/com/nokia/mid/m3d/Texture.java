/*
 *  Copyright 2023-2024 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.nokia.mid.m3d;

import static android.opengl.GLES20.*;

import android.util.Log;

import java.nio.ByteBuffer;

import javax.microedition.lcdui.Image;

public class Texture {
	private static final String TAG = "nokia.m3d.Texture";
	private static int lastId;
	private final int[] id = {-1};
	private final int target;
	private final int format;
	private final int width;
	private final int height;
	private final ByteBuffer buffer;

	public Texture(int target, int format, Image image) {
		if (format != M3D.LUMINANCE8) {
			Log.e(TAG, "Not supported texture format: " + format);
			throw new RuntimeException("Not supported texture format: " + format);
		}
		this.target = target;
		this.format = GL_LUMINANCE;
		width = image.getWidth();
		height = image.getHeight();
		int[] pixels = new int[width * height];
		image.getRGB(pixels, 0, width, 0, 0, width, height);
		buffer = ByteBuffer.allocateDirect(width * height);
		// fill texData for luminance format
		for (int p : pixels) {
			int r = p >> 16 & 0xFF;
			int g = p >> 8 & 0xFF;
			int b = p & 0xFF;
			buffer.put((byte) (0xff - (0x4CB2 * r + 0x9691 * g + 0x1D3E * b >> 16)));
		}
	}

	int glId() {
		if (glIsTexture(id[0])) {
			return id[0];
		}
		synchronized (Texture.class) {
			while (id[0] <= lastId) {
				glGenTextures(1, id, 0);
			}
			lastId = id[0];
		}
		glBindTexture(target, id[0]);
		glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(target, 0, format, width, height, 0, format, GL_UNSIGNED_BYTE, buffer.rewind());
		return id[0];
	}
}
