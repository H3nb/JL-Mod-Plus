/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2024 Yury Kharchenko
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

package com.siemens.mp.lcdui;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;

import ru.playsoftware.j2meloader.util.PNGUtils;
import ru.woesss.util.TextUtils;

public class Image extends com.siemens.mp.ui.Image {

	public static javax.microedition.lcdui.Image createImageFromFile(String filename,
																	 boolean scaleToFullScreen)
			throws IOException {
		if (scaleToFullScreen) {
			return createImageFromFile(filename,
					Displayable.getVirtualWidth(),
					Displayable.getVirtualHeight());
		} else {
			return createImageFromFile(filename, 0, 0);
		}
	}

	public static javax.microedition.lcdui.Image createImageFromFile(String filename,
																	 int ScaleToWidth,
																	 int ScaleToHeight)
			throws IOException {
		if (filename == null) {
			throw new IllegalArgumentException();
		} else if (filename.startsWith("\\") || filename.startsWith("/")) {
			filename = "a:" + filename;
		}
		Bitmap bitmap;
		try (InputStream stream = Connector.openInputStream("file:///" + filename)) {
			bitmap = PNGUtils.getFixedBitmap(stream);
		}
		if (bitmap == null) {
			throw new IOException("Can't decode image");
		}
		if (ScaleToWidth > 0) {
			if (ScaleToHeight <= 0) {
				ScaleToHeight = ScaleToWidth * bitmap.getHeight() / bitmap.getWidth();
			}
			bitmap = Bitmap.createScaledBitmap(bitmap, ScaleToWidth, ScaleToHeight, false);
		} else if (ScaleToHeight > 0) {
			ScaleToWidth = ScaleToHeight * bitmap.getWidth() / bitmap.getHeight();
			bitmap = Bitmap.createScaledBitmap(bitmap, ScaleToWidth, ScaleToHeight, false);
		}
		return new javax.microedition.lcdui.Image(bitmap, false);
	}

	public static int getPixelColor(javax.microedition.lcdui.Image image, int x, int y)
			throws IllegalArgumentException {
		return image.getBitmap().getPixel(x, y);
	}

	public static void setPixelColor(javax.microedition.lcdui.Image image, int x, int y, int color)
			throws IllegalArgumentException {
		image.getBitmap().setPixel(x, y, color);
	}

	public static void writeImageToFile(javax.microedition.lcdui.Image img, String file)
			throws IOException {
		if (img == null) {
			throw new NullPointerException();
		} else if (file == null) {
			throw new IllegalArgumentException();
		} else if (file.startsWith("\\") || file.startsWith("/")) {
			file = "a:" + file;
		}
		try (OutputStream stream = Connector.openOutputStream("file:///" + file)) {
			if (TextUtils.endsWithIgnoreCase(file, ".jpg")) {
				img.getBitmap().compress(Bitmap.CompressFormat.JPEG, 100, stream);
			} else {
				img.getBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream);
			}
		}
	}

	public static void writeBmpToFile(javax.microedition.lcdui.Image image, String filename)
			throws IOException {
		writeImageToFile(image, filename);
	}
}
