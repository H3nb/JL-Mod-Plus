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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import javax.microedition.media.MediaException;

/** Bounded JPEG decode, orientation normalization, crop, resize, and encode. */
public final class SnapshotPipeline {
	private static final long MAX_DECODED_BYTES = 64L * 1024L * 1024L;
	private static final long MAX_CAPTURE_FILE_BYTES = 32L * 1024L * 1024L;
	private static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;

	private SnapshotPipeline() {
	}

	public static byte[] encodeJpeg(File file, SnapshotRequest request) throws MediaException {
		if (file == null || !file.isFile()) {
			throw new MediaException("Camera snapshot file is unavailable");
		}
		if (file.length() > MAX_CAPTURE_FILE_BYTES) {
			throw new MediaException("Camera snapshot source exceeds the memory limit");
		}

		Bitmap source = null;
		Bitmap oriented = null;
		Bitmap cropped = null;
		Bitmap output = null;
		try {
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
				throw new MediaException("Camera returned an invalid JPEG");
			}

			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inSampleSize = boundedSampleSize(bounds.outWidth, bounds.outHeight);
			options.inPreferredConfig = Bitmap.Config.ARGB_8888;
			source = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
			if (source == null) {
				throw new MediaException("Camera JPEG could not be decoded");
			}

			// CameraX defines the saved JPEG's EXIF transform as the rotation/flip required
			// to match ImageCapture's target rotation. Normalize that transform first.
			// Snapshot dimensions control only the later crop/scale and must never decide
			// whether orientation metadata is applied.
			oriented = applyOrientation(source, readOrientation(file));
			cropped = cropToAspect(oriented, request.getWidth(), request.getHeight());
			output = Bitmap.createScaledBitmap(cropped, request.getWidth(), request.getHeight(), true);

			ByteArrayOutputStream encoded = new ByteArrayOutputStream(Math.min(
					MAX_ENCODED_BYTES, Math.max(8 * 1024, request.getWidth() * request.getHeight() / 4)));
			if (!output.compress(Bitmap.CompressFormat.JPEG, request.getQuality(), encoded)) {
				throw new MediaException("Camera JPEG encoding failed");
			}
			if (encoded.size() > MAX_ENCODED_BYTES) {
				throw new MediaException("Camera JPEG exceeds the memory limit");
			}
			return encoded.toByteArray();
		} catch (MediaException e) {
			throw e;
		} catch (OutOfMemoryError e) {
			throw new MediaException("Camera snapshot exceeds the memory limit");
		} catch (RuntimeException e) {
			throw new MediaException("Camera snapshot processing failed: " + e.getMessage());
		} finally {
			recycleDistinct(output, cropped, oriented, source);
		}
	}

	private static int boundedSampleSize(int sourceWidth, int sourceHeight) throws MediaException {
		int sample = 1;
		while (sample < 64) {
			long width = (sourceWidth + sample - 1L) / sample;
			long height = (sourceHeight + sample - 1L) / sample;
			long decodedBytes = width * height * 4L;
			if (decodedBytes <= MAX_DECODED_BYTES) {
				return sample;
			}
			sample *= 2;
		}
		throw new MediaException("Camera source exceeds the memory limit");
	}

	private static int readOrientation(File file) {
		try {
			ExifInterface exif = new ExifInterface(file.getAbsolutePath());
			return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
					ExifInterface.ORIENTATION_NORMAL);
		} catch (IOException | RuntimeException e) {
			return ExifInterface.ORIENTATION_NORMAL;
		}
	}

	private static Bitmap applyOrientation(Bitmap source, int orientation) {
		Matrix matrix = new Matrix();
		switch (orientation) {
			case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
			case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
			case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
			case ExifInterface.ORIENTATION_TRANSPOSE -> {
				matrix.setRotate(90);
				matrix.postScale(-1, 1);
			}
			case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
			case ExifInterface.ORIENTATION_TRANSVERSE -> {
				matrix.setRotate(-90);
				matrix.postScale(-1, 1);
			}
			case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90);
			default -> {
				return source;
			}
		}
		return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
	}

	private static Bitmap cropToAspect(Bitmap source, int targetWidth, int targetHeight) {
		float targetRatio = targetWidth / (float) targetHeight;
		float sourceRatio = source.getWidth() / (float) source.getHeight();
		if (Math.abs(targetRatio - sourceRatio) < 0.001f) {
			return source;
		}

		int cropWidth = source.getWidth();
		int cropHeight = source.getHeight();
		if (sourceRatio > targetRatio) {
			cropWidth = Math.max(1, Math.round(source.getHeight() * targetRatio));
		} else {
			cropHeight = Math.max(1, Math.round(source.getWidth() / targetRatio));
		}
		int left = (source.getWidth() - cropWidth) / 2;
		int top = (source.getHeight() - cropHeight) / 2;
		return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
	}

	private static void recycleDistinct(Bitmap... bitmaps) {
		for (int i = 0; i < bitmaps.length; i++) {
			Bitmap bitmap = bitmaps[i];
			if (bitmap == null || bitmap.isRecycled()) {
				continue;
			}
			boolean alreadyRecycled = false;
			for (int j = 0; j < i; j++) {
				if (bitmaps[j] == bitmap) {
					alreadyRecycled = true;
					break;
				}
			}
			if (!alreadyRecycled) {
				bitmap.recycle();
			}
		}
	}
}
