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
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
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

	/**
	 * Maps a Storage Access Framework tree URI to the raw directory used by the
	 * emulator's existing file-based storage model.
	 *
	 * <p>Only Android's external-storage provider is accepted. Arbitrary document
	 * providers do not have a stable raw path and must not be guessed.</p>
	 */
	public static File getDirectoryForTreeUri(Context context, Uri uri) {
		if (context == null || uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
				|| !DocumentsContract.isTreeUri(uri)
				|| !"com.android.externalstorage.documents".equals(uri.getAuthority())) {
			return null;
		}
		final String documentId;
		try {
			documentId = DocumentsContract.getTreeDocumentId(uri);
		} catch (IllegalArgumentException e) {
			return null;
		}
		int separator = documentId.indexOf(':');
		if (separator <= 0) {
			return null;
		}
		String volumeId = documentId.substring(0, separator);
		String relativePath = documentId.substring(separator + 1);
		File volumeRoot = findExternalStorageRoot(context, volumeId);
		if (volumeRoot == null) {
			return null;
		}
		File candidate = relativePath.isEmpty()
				? volumeRoot
				: new File(volumeRoot, relativePath.replace('/', File.separatorChar));
		try {
			File canonicalRoot = volumeRoot.getCanonicalFile();
			File canonicalCandidate = candidate.getCanonicalFile();
			String rootPath = canonicalRoot.getPath();
			String candidatePath = canonicalCandidate.getPath();
			if (!candidatePath.equals(rootPath)
					&& !candidatePath.startsWith(rootPath + File.separatorChar)) {
				return null;
			}
			return canonicalCandidate;
		} catch (IOException e) {
			return null;
		}
	}

	private static File findExternalStorageRoot(Context context, String volumeId) {
		if ("primary".equalsIgnoreCase(volumeId)) {
			return Environment.getExternalStorageDirectory();
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			return null;
		}
		StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
		if (storageManager == null) {
			return null;
		}
		for (StorageVolume volume : storageManager.getStorageVolumes()) {
			if (volumeId.equalsIgnoreCase(volume.getUuid()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				return volume.getDirectory();
			}
		}
		return null;
	}

	/** Returns an initial SAF tree URI when {@code path} is on primary storage. */
	public static Uri getTreeUriForPath(String path) {
		if (path == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			return null;
		}
		try {
			File root = Environment.getExternalStorageDirectory().getCanonicalFile();
			File candidate = new File(path).getCanonicalFile();
			String rootPath = root.getPath();
			String candidatePath = candidate.getPath();
			if (!candidatePath.equals(rootPath)
					&& !candidatePath.startsWith(rootPath + File.separatorChar)) {
				return null;
			}
			String relative = candidatePath.substring(rootPath.length())
					.replace(File.separatorChar, '/');
			String documentId = "primary:" + (relative.startsWith("/") ? relative.substring(1) : relative);
			return DocumentsContract.buildTreeDocumentUri(
					"com.android.externalstorage.documents", documentId);
		} catch (IOException e) {
			return null;
		}
	}

	/** Retains access to a directory selected through the Storage Access Framework. */
	public static void takePersistableTreePermission(Context context, Uri uri) {
		if (context == null || uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
				|| !"content".equals(uri.getScheme())
				|| !DocumentsContract.isTreeUri(uri)) {
			return;
		}
		try {
			context.getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (SecurityException | IllegalArgumentException e) {
			try {
				context.getContentResolver().takePersistableUriPermission(uri,
						Intent.FLAG_GRANT_READ_URI_PERMISSION);
			} catch (SecurityException | IllegalArgumentException ignored) {
				// The provider did not grant persistable access; raw-path access is still validated below.
			}
		}
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
