/*
 * Copyright 2020-2026 Yury Kharchenko
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

package ru.woesss.j2me.installer;

import android.net.Uri;
import android.util.Log;

import com.android.dx.command.dexer.Main;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

import io.reactivex.SingleEmitter;
import ru.playsoftware.j2meloader.EmulatorApplication;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.librarydb.LibraryAppRow;
import ru.playsoftware.j2meloader.librarydb.LibraryIconRevision;
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.playsoftware.j2meloader.util.ConverterException;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;
import ru.woesss.util.TextUtils;
import ru.woesss.util.zip.ZipFile;

public class AppInstaller {
	private static final String TAG = AppInstaller.class.getSimpleName();
	private static final long NO_ID = -1L;
	static final int STATUS_OLDER = -1;
	static final int STATUS_EQUAL = 0;
	static final int STATUS_NEWER = 1;
	static final int STATUS_NEW = 2;
	static final int STATUS_UNMATCHED = 3;
	static final int STATUS_SUCCESS = 4;
	static final int STATUS_SAME = 5;

	private final long id;
	private final LibraryViewModel libraryViewModel;
	private final File requestedWorkdir;
	private final String requestedStorageKey;
	private final File cacheDir = new File(EmulatorApplication.getInstance().getCacheDir(), "installer");

	private Uri uri;
	private Descriptor manifest;
	private Descriptor newDesc;
	private String appDirName;
	private File targetDir;
	private File srcJar;
	private File tmpDir;
	private LibraryAppRow currentApp;
	private File srcFile;
	private File expectedWorkdir;
	private long installedId = NO_ID;
	private String installedTitle;
	private String installedPath;

	AppInstaller(File jar, Uri uri, LibraryViewModel libraryViewModel) {
		id = NO_ID;
		requestedWorkdir = null;
		requestedStorageKey = null;
		this.libraryViewModel = libraryViewModel;
		if (jar != null) {
			srcFile = jar;
		}
		this.uri = uri;
	}

	public AppInstaller(long id, File requestedWorkdir, String requestedStorageKey,
			LibraryViewModel libraryViewModel) {
		this.id = id;
		this.requestedWorkdir = requestedWorkdir;
		this.requestedStorageKey = requestedStorageKey;
		this.libraryViewModel = libraryViewModel;
	}

	Descriptor getNewDescriptor() {
		return newDesc;
	}

	String getCurrentVersion() {
		return currentApp == null ? null : currentApp.getSourceVersion();
	}

	Descriptor getManifest() {
		return manifest;
	}

	/** Load and check app info from source against one captured READY workdir generation. */
	void loadInfo(SingleEmitter<Integer> emitter) throws IOException, ConverterException {
		bindReadyWorkdir();
		if (id != NO_ID) {
			verifyRequestedWorkdir();
			currentApp = requireRequestedAppIdentity();
			appDirName = currentApp.getStorageKey();
			targetDir = new File(appsDir(), appDirName);
			srcJar = child(targetDir, Config.MIDLET_RES_FILE);
			if (!srcJar.isFile()) {
				throw new IOException("Retained JAR is unavailable for reinstall: " + appDirName);
			}
			newDesc = new Descriptor(child(targetDir, Config.MIDLET_MANIFEST_FILE), false);
			emitter.onSuccess(STATUS_EQUAL);
			return;
		}

		boolean isLocal = srcFile != null;
		if (!isLocal && uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
			downloadJad();
			isLocal = false;
		} else if (!isLocal && uri != null) {
			srcFile = FileUtils.getFileForUri(uri);
			isLocal = true;
		} else if (!isLocal) {
			throw new IOException("No installer source URI");
		}

		String name = srcFile.getName();
		if (TextUtils.endsWithIgnoreCase(name, ".jad")) {
			newDesc = new Descriptor(srcFile, true);
			String url = newDesc.getJarUrl();
			if (url == null) {
				throw new ConverterException("Jad not have " + Descriptor.MIDLET_JAR_URL);
			}
			Uri jarUri = Uri.parse(url);
			String scheme = jarUri.getScheme();
			String host = jarUri.getHost();
			if (isLocal && scheme == null && host == null) {
				boolean matches = this.uri != null && "content".equals(this.uri.getScheme())
						? checkContentUriJar(this.uri, srcFile)
						: checkJarFile(srcFile);
				if (!matches) {
					emitter.onSuccess(STATUS_UNMATCHED);
					return;
				}
			} else if (isLocal && "content".equals(scheme)) {
				if (!checkContentUriJar(this.uri, srcFile)) {
					emitter.onSuccess(STATUS_UNMATCHED);
					return;
				}
			}
		} else if (TextUtils.endsWithIgnoreCase(name, ".kjx")) {
			parseKjx();
			newDesc = new Descriptor(srcFile, true);
		} else {
			srcJar = srcFile;
			newDesc = loadManifest(srcFile);
		}
		emitter.onSuccess(checkDescriptor());
	}

	private void bindReadyWorkdir() throws IOException {
		File workdir = libraryViewModel.readyWorkdir();
		if (workdir == null) {
			throw new IOException("Library is not READY for installation");
		}
		expectedWorkdir = workdir.getCanonicalFile();
	}

	private void verifyRequestedWorkdir() throws IOException {
		if (requestedWorkdir == null ||
				!requestedWorkdir.getCanonicalFile().equals(expectedWorkdir)) {
			throw new IOException("Library workdir changed before opening reinstall target");
		}
	}

	private void verifyActiveWorkdir() throws IOException {
		File activeWorkdir = libraryViewModel.readyWorkdir();
		if (activeWorkdir == null || !activeWorkdir.getCanonicalFile().equals(expectedWorkdir)) {
			throw new IOException("Library workdir changed while installer was running");
		}
	}

	private LibraryAppRow requireRequestedAppIdentity() throws IOException {
		LibraryAppRow app = libraryViewModel.getApp(id);
		if (app == null) {
			throw new IOException("Library app no longer exists: " + id);
		}
		if (requestedStorageKey == null || !requestedStorageKey.equals(app.getStorageKey())) {
			throw new IOException("Library reinstall target changed while installer was running");
		}
		return app;
	}

	private void parseKjx() throws ConverterException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		try (DataInputStream dis = new DataInputStream(new FileInputStream(srcFile))) {
			byte[] magic = new byte[3];
			dis.readFully(magic, 0, 3);
			if (!Arrays.equals(magic, "KJX".getBytes())) {
				throw new ConverterException("Magic KJX does not match: " + new String(magic));
			}
			dis.readByte();
			byte lenKjxFileName = dis.readByte();
			dis.skipBytes(lenKjxFileName);
			int lenJadFileContent = dis.readUnsignedShort();
			byte lenJadFileName = dis.readByte();
			byte[] jadFileName = new byte[lenJadFileName];
			dis.readFully(jadFileName, 0, lenJadFileName);
			String strJadFileName = new String(jadFileName);

			int bufSize = 2048;
			byte[] buf = new byte[bufSize];
			File jadFile = new File(cacheDir, strJadFileName);
			try (FileOutputStream fos = new FileOutputStream(jadFile)) {
				int restSize = lenJadFileContent;
				while (restSize > 0) {
					int readSize = dis.read(buf, 0, Math.min(restSize, bufSize));
					fos.write(buf, 0, readSize);
					restSize -= readSize;
				}
			}

			File jarFile = new File(cacheDir, strJadFileName.substring(0, strJadFileName.length() - 4) + ".jar");
			try (FileOutputStream fos = new FileOutputStream(jarFile)) {
				int length;
				while ((length = dis.read(buf)) > 0) {
					fos.write(buf, 0, length);
				}
			}
			srcFile = jadFile;
			srcJar = jarFile;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void downloadJad() throws ConverterException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		srcFile = new File(cacheDir, "tmp.jad");
		String url = uri.toString();
		Log.d(TAG, "Downloading " + url);
		Exception exception;
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setReadTimeout(3 * 60 * 1000);
			connection.setConnectTimeout(15000);
			int code = connection.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
				String urlStr = connection.getHeaderField("Location");
				connection.disconnect();
				connection = (HttpURLConnection) new URL(urlStr).openConnection();
				connection.setInstanceFollowRedirects(true);
				connection.setReadTimeout(3 * 60 * 1000);
				connection.setConnectTimeout(15000);
			}
			try (InputStream inputStream = connection.getInputStream();
				 OutputStream outputStream = new FileOutputStream(srcFile)) {
				byte[] buffer = new byte[2048];
				int length;
				while ((length = inputStream.read(buffer)) > 0) {
					outputStream.write(buffer, 0, length);
				}
			}
			connection.disconnect();
			Log.d(TAG, "Download complete");
			return;
		} catch (MalformedURLException | FileNotFoundException e) {
			exception = e;
		} catch (IOException e) {
			exception = e;
		} finally {
			if (connection != null) connection.disconnect();
		}
		deleteTemp();
		throw new ConverterException("Can't download jad", exception);
	}

	/** Finalize converted files first, then publish a targeted Room3 mutation asynchronously. */
	void install(SingleEmitter<Integer> emitter) throws ConverterException, IOException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		tmpDir = new File(appsDir(), ".tmp");
		if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
			throw new ConverterException("Can't create directory: '" + targetDir + "'");
		}
		if (srcJar == null) {
			srcJar = new File(cacheDir, "tmp.jar");
			downloadJar();
			manifest = loadManifest(srcJar);
			if (!manifest.equals(newDesc)) {
				emitter.onSuccess(STATUS_UNMATCHED);
				return;
			}
		}
		try {
			Main.main(new String[]{"--no-optimize", "--output=" + tmpDir + Config.MIDLET_DEX_ARCH,
					srcJar.getAbsolutePath()});
		} catch (Throwable e) {
			throw new ConverterException("Dexing error", e);
		}
		if (manifest != null) {
			manifest.merge(newDesc);
			newDesc = manifest;
		}

		File resJar = child(tmpDir, Config.MIDLET_RES_FILE);
		FileUtils.copyFileUsingChannel(srcJar, resJar);
		String icon = newDesc.getIcon();
		File iconFile = child(tmpDir, Config.MIDLET_ICON_FILE);
		if (icon != null) {
			try {
				ZipUtils.unzipEntry(resJar, icon, iconFile);
			} catch (IOException e) {
				Log.w(TAG, "Can't unzip icon: " + icon, e);
				//noinspection ResultOfMethodCallIgnored
				iconFile.delete();
			}
		}
		newDesc.writeTo(child(tmpDir, Config.MIDLET_MANIFEST_FILE));

		// Workdir switches are allowed while conversion runs, but the old generation must never
		// publish a filesystem result after the user has moved to another Library root.
		verifyActiveWorkdir();
		if (id != NO_ID) {
			verifyRequestedWorkdir();
			requireRequestedAppIdentity();
		}
		FileUtils.deleteDirectory(targetDir);
		if (!tmpDir.renameTo(targetDir)) {
			throw new ConverterException("Can't move '" + tmpDir + "' to '" + targetDir + "'");
		}

		String sourceTitle = newDesc.getName();
		String sourceVendor = newDesc.getVendor();
		String sourceVersion = newDesc.getVersion();
		String sourceDescription = newDesc.getAttrs().get(Descriptor.MIDLET_DESCRIPTION);
		Long existingId = currentApp == null ? null : currentApp.getId();
		installedTitle = currentApp == null ? sourceTitle : currentApp.getTitle();
		installedPath = targetDir.getAbsolutePath();
		long iconRevision = LibraryIconRevision.fromFile(child(targetDir, Config.MIDLET_ICON_FILE));

		clearCache();
		deleteTemp();
		libraryViewModel.recordInstalledApp(
				expectedWorkdir,
				existingId,
				appDirName,
				sourceTitle,
				sourceVendor,
				sourceVersion,
				sourceDescription,
				iconRevision,
				System.currentTimeMillis(),
				(value, error) -> {
					if (emitter.isDisposed()) return;
					if (error != null) {
						emitter.onError(error);
						return;
					}
					if (value == null) {
						emitter.onError(new IllegalStateException("Library install mutation returned no app id"));
						return;
					}
					installedId = value;
					emitter.onSuccess(STATUS_SUCCESS);
				}
		);
	}

	private Descriptor loadManifest(File jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			FileHeader manifest = zip.getFileHeader(JarFile.MANIFEST_NAME);
			if (manifest == null) throw new IOException("JAR not have " + JarFile.MANIFEST_NAME);
			try (ZipInputStream is = zip.getInputStream(manifest)) {
				return new Descriptor(new String(IOUtils.toByteArray(is)), false);
			}
		}
	}

	private boolean checkJarFile(File jad) throws IOException, ConverterException {
		File dir = jad.getParentFile();
		String jarUrl = newDesc.getJarUrl();
		File jar = new File(dir, jarUrl);
		if (!jar.exists()) {
			String name = jad.getName();
			jar = new File(dir, name.substring(0, name.length() - 4) + ".jar");
			if (!jar.exists()) throw new ConverterException("Jar-file not found for url: " + jarUrl);
		}
		srcJar = jar;
		manifest = loadManifest(jar);
		return manifest.equals(newDesc);
	}

	private boolean checkContentUriJar(Uri jadUri, File jadFile) throws IOException, ConverterException {
		Uri jarUri = Uri.parse(newDesc.getJarUrl());
		if (jarUri.getScheme() == null) jarUri = FileUtils.resolveSiblingUri(jadUri, jarUri);
		if (jarUri == null || !"content".equals(jarUri.getScheme())) return checkJarFile(jadFile);
		File jar = FileUtils.getFileForUri(jarUri);
		if (!jar.exists()) throw new ConverterException("Jar-file not found for uri: " + jarUri);
		srcJar = jar;
		manifest = loadManifest(jar);
		return manifest.equals(newDesc);
	}

	private int checkDescriptor() {
		String name = newDesc.getName();
		String vendor = newDesc.getVendor();
		List<LibraryAppRow> candidates = libraryViewModel.findBySourceIdentity(name, vendor);
		currentApp = candidates.size() == 1 ? candidates.get(0) : null;
		if (currentApp == null) {
			generatePathName(name.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim());
			return STATUS_NEW;
		}

		appDirName = currentApp.getStorageKey();
		targetDir = new File(appsDir(), appDirName);
		int result = newDesc.compareVersion(currentApp.getSourceVersion());
		if (result != 0) return result;
		if (srcJar == null || !srcJar.exists()) return STATUS_EQUAL;
		try {
			Descriptor oldDesc = new Descriptor(child(targetDir, Config.MIDLET_MANIFEST_FILE), false);
			if (!oldDesc.containsAllAttributes(newDesc)) return STATUS_EQUAL;
		} catch (IOException e) {
			Log.e(TAG, "checkDescriptor: error read exists app manifest", e);
		}
		File targetJar = child(targetDir, Config.MIDLET_RES_FILE);
		if (targetJar.exists() && targetJar.length() == srcJar.length()) {
			try (FileInputStream one = new FileInputStream(srcJar);
				 FileInputStream two = new FileInputStream(targetJar)) {
				if (one.read() != two.read()) return STATUS_EQUAL;
				return STATUS_SAME;
			} catch (IOException e) {
				Log.e(TAG, "checkDescriptor: io error when compare files", e);
			}
		}
		return STATUS_EQUAL;
	}

	private void generatePathName(String name) {
		File dir = new File(appsDir(), name);
		for (int i = 1; dir.exists(); i++) dir = new File(appsDir(), name + "_" + i);
		appDirName = dir.getName();
		targetDir = dir;
	}

	private void downloadJar() throws ConverterException {
		Uri jarUri = Uri.parse(newDesc.getJarUrl());
		if (jarUri.getScheme() == null) {
			String schemeOfJadSource = this.uri.getScheme();
			if ("http".equals(schemeOfJadSource) || "https".equals(schemeOfJadSource)) {
				List<String> pathSegments = uri.getPathSegments();
				StringBuilder path = new StringBuilder(pathSegments.get(0));
				for (int i = 1; i < pathSegments.size() - 1; i++) path.append('/').append(pathSegments.get(i));
				path.append('/').append(jarUri.getPath());
				jarUri = uri.buildUpon().path(path.toString()).build();
			} else {
				jarUri = jarUri.buildUpon().scheme("http").build();
			}
		}
		String url = jarUri.toString();
		Log.d(TAG, "Downloading " + url);
		Exception exception;
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setReadTimeout(3 * 60 * 1000);
			connection.setConnectTimeout(15000);
			int code = connection.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
				String urlStr = connection.getHeaderField("Location");
				connection.disconnect();
				connection = (HttpURLConnection) new URL(urlStr).openConnection();
				connection.setInstanceFollowRedirects(true);
				connection.setReadTimeout(3 * 60 * 1000);
				connection.setConnectTimeout(15000);
			}
			try (InputStream inputStream = connection.getInputStream();
				 OutputStream outputStream = new FileOutputStream(srcJar)) {
				byte[] buffer = new byte[2048];
				int length;
				while ((length = inputStream.read(buffer)) > 0) outputStream.write(buffer, 0, length);
			}
			connection.disconnect();
			Log.d(TAG, "Download complete");
			return;
		} catch (MalformedURLException | FileNotFoundException e) {
			exception = e;
		} catch (IOException e) {
			exception = e;
		} finally {
			if (connection != null) connection.disconnect();
		}
		deleteTemp();
		throw new ConverterException("Can't download jar", exception);
	}

	void deleteTemp() {
		if (tmpDir != null) FileUtils.deleteDirectory(tmpDir);
	}

	public File getJar() {
		return srcJar;
	}

	void clearCache() {
		FileUtils.deleteDirectory(cacheDir);
	}

	String getIconPath() {
		return targetDir == null ? null : child(targetDir, Config.MIDLET_ICON_FILE).getAbsolutePath();
	}

	String getInstalledTitle() {
		return installedTitle != null ? installedTitle : currentApp == null ? null : currentApp.getTitle();
	}

	String getInstalledPath() {
		if (installedPath != null) return installedPath;
		return targetDir == null ? null : targetDir.getAbsolutePath();
	}

	long getInstalledId() {
		return installedId != NO_ID ? installedId : currentApp == null ? NO_ID : currentApp.getId();
	}

	private File appsDir() {
		if (expectedWorkdir == null) throw new IllegalStateException("Installer workdir is not bound");
		return new File(expectedWorkdir, "converted");
	}

	private static File child(File directory, String suffix) {
		return new File(directory.getAbsolutePath() + suffix);
	}
}
