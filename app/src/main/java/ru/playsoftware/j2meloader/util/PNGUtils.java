/*
 * Copyright 2020 Nikita Shakarun
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

package ru.playsoftware.j2meloader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineHelper;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReaderInt;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;
import ar.com.hjg.pngj.chunks.PngChunkTRNS;
import ar.com.hjg.pngj.chunks.PngMetadata;

public class PNGUtils {
	private static final String TAG = "PNGUtils";

	public static Bitmap getFixedBitmap(InputStream stream) throws IOException {
		byte[] data = IOUtils.toByteArray(stream);
		return getFixedBitmap(data, 0, data.length);
	}

	public static Bitmap getFixedBitmap(byte[] imageData, int imageOffset, int imageLength) {
		try (ByteArrayInputStream stream = new ByteArrayInputStream(imageData, imageOffset, imageLength)) {
			return fixPNG(stream);
		} catch (Exception e) {
			Log.w(TAG, "getFixedBitmap: " + e);
		}
		return BitmapFactory.decodeByteArray(imageData, imageOffset, imageLength);
	}

	private static Bitmap fixPNG(InputStream stream) {
		PngReaderInt reader = new PngReaderInt(stream);
		reader.setCrcCheckDisabled();
		ImageInfo imageInfo = reader.imgInfo;
		int width = imageInfo.cols;
		int height = imageInfo.rows;
		PngMetadata metadata = reader.getMetadata();
		PngChunkTRNS trns = metadata.getTRNS();
		PngChunkPLTE plte = metadata.getPLTE();
		IntBuffer pix = IntBuffer.allocate(width * height);
		for (int i = 0; reader.hasMoreRows(); i++) {
			ImageLineInt lineInt = reader.readRowInt();
			ImageLineHelper.scaleUp(lineInt);
			lineToARGB32(lineInt, plte, trns, pix);
		}
		reader.end();
		return Bitmap.createBitmap(pix.array(), width, height, Bitmap.Config.ARGB_8888);
	}

	private static void lineToARGB32(ImageLineInt line,
									 PngChunkPLTE pal,
									 PngChunkTRNS trns,
									 IntBuffer pix) {
		boolean alphachannel = line.imgInfo.alpha;
		int[] scanline = line.getScanline();
		int cols = line.imgInfo.cols;
		int index, rgb, alpha, ga, g;
		if (line.imgInfo.indexed) { // palette
			int nindexesWithAlpha = trns != null ? trns.getPalletteAlpha().length : 0;
			for (int c = 0; c < cols; c++) {
				try {
					index = scanline[c];
					rgb = pal.getEntry(index);
					alpha = index < nindexesWithAlpha ? trns.getPalletteAlpha()[index] : 255;
					pix.put((alpha << 24) | rgb);
				} catch (Exception e) {
					Log.w(TAG, "lineToARGB32: " + e);
					pix.put(0);
				}
			}
		} else if (line.imgInfo.greyscale) { // gray
			if (trns != null) {
				ga = ImageLineHelper.scaleUp(line.imgInfo.bitDepth, (byte) trns.getGray()) & 0xFF;
			} else {
				ga = -1;
			}
			for (int c = 0, c2 = 0; c < cols; c++) {
				g = scanline[c2++];
				alpha = alphachannel ? scanline[c2++] : (g != ga ? 255 : 0);
				pix.put((alpha << 24) | g | (g << 8) | (g << 16));
			}
		} else if (line.imgInfo.bitDepth == 16) { // true color
			ga = trns != null ? trns.getRGB888() : -1;
			for (int c = 0, c2 = 0; c < cols; c++) {
				rgb = ((scanline[c2++] & 0xFF00) << 8) | (scanline[c2++] & 0xFF00)
						| ((scanline[c2++] & 0xFF00) >> 8);
				alpha = alphachannel ? ((scanline[c2++] & 0xFF00) >> 8) : (rgb != ga ? 255 : 0);
				pix.put((alpha << 24) | rgb);
			}
		} else { // true color
			ga = trns != null ? trns.getRGB888() : -1;
			for (int c = 0, c2 = 0; c < cols; c++) {
				rgb = ((scanline[c2++]) << 16) | ((scanline[c2++]) << 8)
						| (scanline[c2++]);
				alpha = alphachannel ? scanline[c2++] : (rgb != ga ? 255 : 0);
				pix.put((alpha << 24) | rgb);
			}
		}
	}
}
