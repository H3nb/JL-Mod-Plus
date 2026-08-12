/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
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

// Modified for JL-Mod Plus.

package ru.playsoftware.j2meloader.util;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Locale;

import kotlin.io.FilesKt;
import ru.playsoftware.j2meloader.EmulatorApplication;
import ru.playsoftware.j2meloader.config.Config;

public class FileUtils {
	private static final String TAG = FileUtils.class.getName();
	private static final String TEMP_JAR_NAME = "tmp.jar";
	private static final String TEMP_JAD_NAME = "tmp.jad";
	private static final String TEMP_KJX_NAME = "tmp.kjx";
	private static final int BUFFER_SIZE = 1024;
	public static final String ILLEGAL_FILENAME_CHARS = "[/\\\\:*?\"<>|]";

	public static void copyFiles(File src, File dst, FilenameFilter filter) {
		if (!dst.exists() && !dst.mkdirs()) {
			Log.e(TAG, "copyFiles() failed create dir: " + dst);
			return;
		}
		File[] list = src.listFiles(filter);
		if (list == null) {
			return;
		}
		for (File file : list) {
			File to = new File(dst, file.getName());
			if (file.isDirectory()) {
				copyFiles(src, to, filter);
			} else {
				try {
					copyFileUsingChannel(file, to);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void copyFileUsingChannel(File source, File dest) throws IOException {
		try (FileInputStream fis = new FileInputStream(source);
			 FileChannel sourceChannel = fis.getChannel();
			 FileOutputStream fos = new FileOutputStream(dest);
			 FileChannel destChannel = fos.getChannel()) {
			destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
		}
	}

	public static void deleteDirectory(File dir) {
		if (dir.isDirectory()) {
			File[] listFiles = dir.listFiles();
			if (listFiles != null) {
				for (File file : listFiles) {
					deleteDirectory(file);
				}
			}
		}
		if (!dir.delete() && dir.exists()) {
			Log.w(TAG, "Can't delete file: " + dir);
		}
	}

	public static File getFileForUri(Uri uri) throws IOException {
		if (uri == null) {
			throw new NullPointerException("uri is null");
		}
		Context context = EmulatorApplication.getInstance();
		if ("file".equals(uri.getScheme())) {
			String path = uri.getPath();
			if (path != null) {
				File file = new File(path);
				if (file.exists()) {
					return file;
				}
			}
		}
		File tmpDir = new File(context.getCacheDir(), "installer");
		if (!tmpDir.exists() && !tmpDir.mkdirs()) {
			throw new IOException("Can't create directory: " + tmpDir);
		}
		File file;
		try (InputStream in = context.getContentResolver().openInputStream(uri)) {
			byte[] buf = new byte[BUFFER_SIZE];
			int len = 0;
			if (in != null) {
				while (len < buf.length) {
					int read = in.read(buf, len, buf.length - len);
					if (read < 0) {
						break;
					}
					if (read == 0) {
						break;
					}
					len += read;
					if (len >= 3) {
						break;
					}
				}
			}
			if (len <= 0)
				throw new IOException("Can't read data from uri: " + uri);
			String mimeType = context.getContentResolver().getType(uri);
			file = new File(tmpDir, getTempFileName(uri, mimeType, buf, len));
			//noinspection IOStreamConstructor
			try (OutputStream out = new FileOutputStream(file)) {
				out.write(buf, 0, len);
				while ((len = in.read(buf)) != -1) {
					if (len > 0) {
						out.write(buf, 0, len);
					}
				}
			}
		} catch (SecurityException e) {
			IOException failure = new IOException("Can't read data from uri: " + uri);
			failure.initCause(e);
			throw failure;
		}
		return file;
	}

	static String getTempFileName(Uri uri, String mimeType, byte[] prefix, int length) {
		if (length >= 3 && prefix[0] == 'K' && prefix[1] == 'J' && prefix[2] == 'X') {
			return TEMP_KJX_NAME;
		}
		if (length >= 2 && prefix[0] == 0x50 && prefix[1] == 0x4B) {
			return TEMP_JAR_NAME;
		}
		String path = uri.getPath();
		if (path != null) {
			String lowerPath = path.toLowerCase(Locale.ROOT);
			if (lowerPath.endsWith(".kjx")) {
				return TEMP_KJX_NAME;
			}
			if (lowerPath.endsWith(".jar")) {
				return TEMP_JAR_NAME;
			}
			if (lowerPath.endsWith(".jad")) {
				return TEMP_JAD_NAME;
			}
		}
		if ("application/java-archive".equalsIgnoreCase(mimeType)
				|| "application/java".equalsIgnoreCase(mimeType)
				|| "application/x-java-archive".equalsIgnoreCase(mimeType)
				|| "application/zip".equalsIgnoreCase(mimeType)) {
			return TEMP_JAR_NAME;
		}
		return TEMP_JAD_NAME;
	}

	/** Resolves a relative JAD jar URL against a path-bearing content JAD URI. */
	public static Uri resolveSiblingUri(Uri baseUri, Uri relativeUri) {
		String basePath = baseUri == null ? null : baseUri.getPath();
		if (baseUri == null || relativeUri == null || basePath == null
				|| !basePath.toLowerCase(Locale.ROOT).endsWith(".jad")
				|| relativeUri.getScheme() != null) {
			return null;
		}
		try {
			URI base = new URI(baseUri.toString());
			URI resolved = base.resolve(new URI(relativeUri.toString()));
			return Uri.parse(resolved.toString());
		} catch (URISyntaxException e) {
			return null;
		}
	}

	public static byte[] getBytes(File file) throws IOException {
		return FilesKt.readBytes(file);
	}

	public static void clearDirectory(File dir) {
		final File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				deleteDirectory(file);
			} else {
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
		}
	}

	public static String getText(String path) {
		try {
			//noinspection CharsetObjectCanBeUsed
			return FilesKt.readText(new File(path), Charset.forName("UTF-8"));
		} catch (Exception e) {
			Log.e(TAG, "getText: " + path, e);
		}
		return "";
	}

	public static boolean initWorkDir(File dir) {
		if ((dir.isDirectory() || dir.mkdirs()) && dir.canWrite()) {
			//noinspection ResultOfMethodCallIgnored
			new File(dir, Config.SHADERS_DIR).mkdir();
			//noinspection ResultOfMethodCallIgnored
			new File(dir, Config.SOUNDBANKS_DIR).mkdir();
			//noinspection ResultOfMethodCallIgnored
			new File(dir, Config.SKINS_DIR).mkdir();
			try {
				//noinspection ResultOfMethodCallIgnored
				new File(dir, MediaStore.MEDIA_IGNORE_FILENAME).createNewFile();
			} catch (Exception e) {
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}
}
