/*
 * Copyright 2020-2026 Yury Kharchenko
 *
 * Modified by JL-Mod Plus contributors; original upstream attribution is retained.
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
import java.io.EOFException;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;

import io.reactivex.SingleEmitter;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.librarydb.LibraryAppRow;
import ru.playsoftware.j2meloader.librarydb.LibraryGenerationLease;
import ru.playsoftware.j2meloader.librarydb.LibraryGenerationToken;
import ru.playsoftware.j2meloader.librarydb.LibraryIconOverride;
import ru.playsoftware.j2meloader.librarydb.LibraryIconRevision;
import ru.playsoftware.j2meloader.librarydb.LibraryInstallRecovery;
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.playsoftware.j2meloader.util.ConverterException;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;
import ru.woesss.util.TextUtils;
import ru.woesss.util.zip.ZipFile;
import javax.microedition.shell.timing.TimingTransformMetadata;

public class AppInstaller {
    private static final String TAG = AppInstaller.class.getSimpleName();
    private static final long NO_ID = -1L;
    private static final long NO_GENERATION = Long.MIN_VALUE;
    private static final int NO_STATUS = Integer.MIN_VALUE;
    static final int STATUS_OLDER = -1;
    static final int STATUS_EQUAL = 0;
    static final int STATUS_NEWER = 1;
    static final int STATUS_NEW = 2;
    static final int STATUS_UNMATCHED = 3;
    static final int STATUS_SUCCESS = 4;
    static final int STATUS_SAME = 5;

    private final long id;
    private final LibraryViewModel libraryViewModel;
    private final long requestedGeneration;
    private final File requestedWorkdir;
    private final String requestedStorageKey;
    private final InstallerScratch scratch;
    private final File resolvedJar;

    private Uri uri;
    private Descriptor manifest;
    private Descriptor newDesc;
    private String appDirName;
    private File targetDir;
    private File srcJar;
    private File tmpDir;
    private LibraryAppRow currentApp;
    private File srcFile;
    private long expectedGeneration = NO_GENERATION;
    private File expectedWorkdir;
    private long installedId = NO_ID;
    private String installedTitle;
    private String installedPath;
    private int loadedStatus = NO_STATUS;

    AppInstaller(File jar, Uri uri, LibraryViewModel libraryViewModel) {
        id = NO_ID;
        requestedGeneration = NO_GENERATION;
        requestedWorkdir = null;
        requestedStorageKey = null;
        this.libraryViewModel = libraryViewModel;
        scratch = new InstallerScratch();
        resolvedJar = null;
        if (jar != null) srcFile = jar;
        this.uri = uri;
    }

    AppInstaller(File source, File resolvedJar, LibraryViewModel libraryViewModel,
            InstallerScratch scratch) {
        id = NO_ID;
        requestedGeneration = NO_GENERATION;
        requestedWorkdir = null;
        requestedStorageKey = null;
        this.libraryViewModel = libraryViewModel;
        this.scratch = scratch;
        this.resolvedJar = resolvedJar;
        srcFile = source;
        uri = Uri.fromFile(source);
    }

    public AppInstaller(long id, long requestedGeneration, File requestedWorkdir,
            String requestedStorageKey, LibraryViewModel libraryViewModel) {
        this.id = id;
        this.requestedGeneration = requestedGeneration;
        this.requestedWorkdir = requestedWorkdir;
        this.requestedStorageKey = requestedStorageKey;
        this.libraryViewModel = libraryViewModel;
        scratch = new InstallerScratch();
        resolvedJar = null;
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

    LibraryAppRow getCurrentApp() {
        return currentApp;
    }

    long getExpectedGeneration() {
        return expectedGeneration;
    }

    File getExpectedWorkdir() {
        return expectedWorkdir;
    }

    /** Load and check app info from source against one captured READY Library generation. */
    void loadInfo(SingleEmitter<Integer> emitter) throws IOException, ConverterException {
        bindReadyGeneration();
        if (id != NO_ID) {
            verifyRequestedGeneration();
            currentApp = requireRequestedAppIdentity();
            appDirName = currentApp.getStorageKey();
            targetDir = new File(appsDir(), appDirName);
            srcJar = child(targetDir, Config.MIDLET_RES_FILE);
            if (!srcJar.isFile()) {
                throw new IOException("Retained JAR is unavailable for reinstall: " + appDirName);
            }
            newDesc = new Descriptor(child(targetDir, Config.MIDLET_MANIFEST_FILE), false);
            emitLoadedStatus(emitter, STATUS_EQUAL);
            return;
        }

        boolean isLocal = srcFile != null;
        if (!isLocal && uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            downloadJad();
            isLocal = false;
        } else if (!isLocal && uri != null) {
            srcFile = scratch.materialize(uri);
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
            if (resolvedJar != null) {
                srcJar = resolvedJar;
                manifest = loadManifest(resolvedJar);
                if (!manifest.equals(newDesc)) {
                    emitLoadedStatus(emitter, STATUS_UNMATCHED);
                    return;
                }
            } else if (isLocal && scheme == null && host == null) {
                boolean matches = this.uri != null && "content".equals(this.uri.getScheme())
                        ? checkContentUriJar(this.uri, srcFile)
                        : checkJarFile(srcFile);
                if (!matches) {
                    emitLoadedStatus(emitter, STATUS_UNMATCHED);
                    return;
                }
            } else if (isLocal && "content".equals(scheme)) {
                if (!checkContentUriJar(this.uri, srcFile)) {
                    emitLoadedStatus(emitter, STATUS_UNMATCHED);
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
        emitLoadedStatus(emitter, checkDescriptor());
    }

    private void emitLoadedStatus(SingleEmitter<Integer> emitter, int status) {
        loadedStatus = status;
        emitter.onSuccess(status);
    }

    private void bindReadyGeneration() throws IOException {
        LibraryGenerationToken generation = libraryViewModel.readyGeneration();
        if (generation == null) throw new IOException("Library is not READY for installation");
        expectedGeneration = generation.getGeneration();
        expectedWorkdir = generation.getEmulatorDir().getCanonicalFile();
    }

    private void verifyRequestedGeneration() throws IOException {
        if (requestedGeneration == NO_GENERATION || requestedGeneration != expectedGeneration ||
                requestedWorkdir == null ||
                !requestedWorkdir.getCanonicalFile().equals(expectedWorkdir)) {
            throw new IOException("Library generation changed before opening reinstall target");
        }
    }

    private void verifyActiveGeneration() throws IOException {
        if (!libraryViewModel.isReadyGeneration(expectedGeneration, expectedWorkdir)) {
            throw new IOException("Library generation changed while installer was running");
        }
    }

    private LibraryAppRow requireRequestedAppIdentity() throws IOException {
        LibraryAppRow app;
        try {
            app = libraryViewModel.getApp(expectedGeneration, expectedWorkdir, id);
        } catch (IllegalStateException e) {
            throw new IOException("Library generation changed while resolving reinstall target", e);
        }
        if (app == null) throw new IOException("Library app no longer exists: " + id);
        if (requestedStorageKey == null || !requestedStorageKey.equals(app.getStorageKey())) {
            throw new IOException("Library reinstall target changed while installer was running");
        }
        return app;
    }

    private void parseKjx() throws ConverterException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(srcFile))) {
            byte[] magic = new byte[3];
            dis.readFully(magic);
            if (!Arrays.equals(magic, "KJX".getBytes(StandardCharsets.US_ASCII))) {
                throw new ConverterException("Magic KJX does not match: " +
                        new String(magic, StandardCharsets.US_ASCII));
            }
            dis.readUnsignedByte();
            int kjxFileNameLength = dis.readUnsignedByte();
            skipFully(dis, kjxFileNameLength);
            int jadContentLength = dis.readUnsignedShort();
            int jadFileNameLength = dis.readUnsignedByte();
            if (jadFileNameLength <= 4) throw new ConverterException("Invalid KJX JAD filename length");
            byte[] jadFileName = new byte[jadFileNameLength];
            dis.readFully(jadFileName);
            String jadName = new String(jadFileName, StandardCharsets.UTF_8);
            String lowerJadName = jadName.toLowerCase(Locale.ROOT);
            if (!lowerJadName.endsWith(".jad") || jadName.contains("/") || jadName.contains("\\") ||
                    jadName.equals(".") || jadName.equals("..")) {
                throw new ConverterException("Unsafe KJX JAD filename: " + jadName);
            }

            File jadFile = scratch.file(jadName);
            byte[] buffer = new byte[2048];
            try (FileOutputStream output = new FileOutputStream(jadFile, false)) {
                int remaining = jadContentLength;
                while (remaining > 0) {
                    int read = dis.read(buffer, 0, Math.min(remaining, buffer.length));
                    if (read < 0) throw new EOFException("Truncated KJX JAD payload");
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            String jarName = jadName.substring(0, jadName.length() - 4) + ".jar";
            File jarFile = scratch.file(jarName);
            long jarBytes = 0L;
            try (FileOutputStream output = new FileOutputStream(jarFile, false)) {
                int read;
                while ((read = dis.read(buffer)) != -1) {
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    jarBytes += read;
                }
            }
            if (jarBytes <= 0L) throw new ConverterException("KJX does not contain a JAR payload");
            srcFile = jadFile;
            srcJar = jarFile;
        } catch (ConverterException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ConverterException("Can't parse KJX", error);
        }
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new EOFException("Truncated KJX header");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void downloadJad() throws ConverterException {
        try {
            srcFile = scratch.file("download.jad");
        } catch (IOException error) {
            throw new ConverterException("Can't prepare JAD scratch file", error);
        }
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
        throw new ConverterException("Can't download jad", exception);
    }

    /** Finalize converted files first, then publish a generation-bound Room3 mutation asynchronously. */
    void install(SingleEmitter<Integer> emitter) throws ConverterException, IOException {
        final InstallerExecutionCoordinator.Permit executionPermit = InstallerExecutionCoordinator.acquire();
        boolean handedOff = false;
        try {
            verifyActiveGeneration();
            if (id != NO_ID) {
                verifyRequestedGeneration();
                requireRequestedAppIdentity();
            } else {
                int currentStatus = checkDescriptor();
                if (loadedStatus != NO_STATUS && currentStatus != loadedStatus) {
                    // Source inspection and user confirmation happen outside the global permit. If a
                    // previous installer changed the Library while this request was pending, surface
                    // the new classification instead of applying a stale NEW/UPDATE/REINSTALL decision.
                    emitter.onSuccess(currentStatus);
                    return;
                }
            }

            LibraryInstallRecovery.discardStaging(expectedWorkdir);
            tmpDir = LibraryInstallRecovery.stagingDirectory(expectedWorkdir);
            if (!tmpDir.mkdirs()) {
                throw new ConverterException("Can't create staging directory: '" + tmpDir + "'");
            }
            if (srcJar == null) {
                try {
                    srcJar = scratch.file("download.jar");
                } catch (IOException error) {
                    throw new ConverterException("Can't prepare JAR scratch file", error);
                }
                downloadJar();
                manifest = loadManifest(srcJar);
                if (!manifest.equals(newDesc)) {
                    emitter.onSuccess(STATUS_UNMATCHED);
                    return;
                }
            }
            try {
                Main.main(new String[]{"--no-optimize", "--memory-metadata="
                        + tmpDir + Config.MIDLET_MEMORY_METADATA,
                        "--output=" + tmpDir + Config.MIDLET_DEX_ARCH,
                        srcJar.getAbsolutePath()});
            } catch (Throwable e) {
                throw new ConverterException("Dexing error", e);
            }
            File memoryMetadata = child(tmpDir, Config.MIDLET_MEMORY_METADATA);
            if (!memoryMetadata.isFile() || memoryMetadata.length() <= 0L) {
                throw new ConverterException("DX produced no Memory Editor metadata");
            }
            if (manifest != null) {
                manifest.merge(newDesc);
                newDesc = manifest;
            }
            TimingTransformMetadata.mark(newDesc.getAttrs());

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

            // DX/copy can run for a while. Revalidate the generation again immediately before publish.
            verifyActiveGeneration();
            if (id != NO_ID) {
                verifyRequestedGeneration();
                requireRequestedAppIdentity();
            }

            File replacementBackup = null;
            // The generation lease stays short. The process-wide execution permit already serializes
            // the physical converter/staging lifetime; the lease additionally excludes generation-bound
            // Library filesystem mutations while the final target directory is published.
            try (LibraryGenerationLease ignored = libraryViewModel.acquireGenerationLease(
                    expectedGeneration,
                    expectedWorkdir)) {
                if (currentApp != null) {
                    LibraryIconOverride.applyPersistedOverride(expectedWorkdir, appDirName, tmpDir);
                }
                if (targetDir.exists()) {
                    replacementBackup = LibraryInstallRecovery.createBackup(
                            expectedWorkdir,
                            appDirName,
                            targetDir);
                }
                if (!tmpDir.renameTo(targetDir)) {
                    if (replacementBackup != null &&
                            !LibraryInstallRecovery.restoreBackup(targetDir, replacementBackup)) {
                        Log.e(TAG,
                                "Replacement publish failed and immediate backup rollback also failed: " + appDirName);
                    }
                    throw new ConverterException("Can't move '" + tmpDir + "' to '" + targetDir + "'");
                }
            }

            String sourceTitle = newDesc.getName();
            String sourceVendor = newDesc.getVendor();
            String sourceVersion = newDesc.getVersion();
            String sourceDescription = newDesc.getAttrs().get(Descriptor.MIDLET_DESCRIPTION);
            Long existingId = currentApp == null ? null : currentApp.getId();
            installedTitle = currentApp == null ? sourceTitle : currentApp.getTitle();
            installedPath = targetDir.getAbsolutePath();
            long iconRevision = LibraryIconRevision.fromFile(child(targetDir, Config.MIDLET_ICON_FILE));
            final File recoveryBackup = replacementBackup;

            clearCache();
            deleteTemp();
            libraryViewModel.recordInstalledApp(
                    expectedGeneration,
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
                        if (error != null) {
                            executionPermit.close();
                            // Keep recoveryBackup. Startup reconciliation will refresh this exact storage key
                            // without deleting the successfully published replacement.
                            if (!emitter.isDisposed()) emitter.onError(error);
                            return;
                        }
                        if (value == null) {
                            executionPermit.close();
                            if (!emitter.isDisposed()) {
                                emitter.onError(new IllegalStateException(
                                        "Library install mutation returned no app id"));
                            }
                            return;
                        }
                        installedId = value;
                        if (recoveryBackup != null &&
                                !LibraryInstallRecovery.discardBackup(expectedWorkdir, appDirName)) {
                            Log.w(TAG, "Installed app committed but recovery backup cleanup failed: " + appDirName);
                        }
                        InstallerExecutionCoordinator.awaitVisible(
                                libraryViewModel,
                                expectedGeneration,
                                expectedWorkdir,
                                installedId,
                                appDirName,
                                visibilityError -> {
                                    executionPermit.close();
                                    if (emitter.isDisposed()) return;
                                    if (visibilityError != null) emitter.onError(visibilityError);
                                    else emitter.onSuccess(STATUS_SUCCESS);
                                });
                    });
            handedOff = true;
        } finally {
            if (!handedOff) executionPermit.close();
        }
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
        File jar = scratch.materialize(jarUri);
        if (!jar.exists()) throw new ConverterException("Jar-file not found for uri: " + jarUri);
        srcJar = jar;
        manifest = loadManifest(jar);
        return manifest.equals(newDesc);
    }

    private int checkDescriptor() throws IOException {
        String name = newDesc.getName();
        String vendor = newDesc.getVendor();
        List<LibraryAppRow> candidates;
        try {
            candidates = libraryViewModel.findBySourceIdentity(
                    expectedGeneration,
                    expectedWorkdir,
                    name,
                    vendor);
        } catch (IllegalStateException e) {
            throw new IOException("Library generation changed while matching installer identity", e);
        }
        currentApp = candidates.size() == 1 ? candidates.get(0) : null;
        if (currentApp == null) {
            Set<String> indexedStorageKeys;
            try {
                indexedStorageKeys = libraryViewModel.storageKeys(expectedGeneration, expectedWorkdir);
            } catch (IllegalStateException e) {
                throw new IOException("Library generation changed while selecting installer storage key", e);
            }
            generatePathName(
                    name.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim(),
                    indexedStorageKeys);
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
        if (targetJar.exists()) {
            try {
                if (filesHaveSameContents(srcJar, targetJar)) return STATUS_SAME;
            } catch (IOException e) {
                Log.e(TAG, "checkDescriptor: io error when compare files", e);
            }
        }
        return STATUS_EQUAL;
    }

    static boolean filesHaveSameContents(File first, File second) throws IOException {
        if (!first.isFile() || !second.isFile() || first.length() != second.length()) return false;
        try (FileInputStream one = new FileInputStream(first);
                FileInputStream two = new FileInputStream(second)) {
            byte[] oneBuffer = new byte[8192];
            byte[] twoBuffer = new byte[8192];
            while (true) {
                int oneRead = one.read(oneBuffer);
                int twoRead = two.read(twoBuffer);
                if (oneRead != twoRead) return false;
                if (oneRead < 0) return true;
                for (int i = 0; i < oneRead; i++) {
                    if (oneBuffer[i] != twoBuffer[i]) return false;
                }
            }
        }
    }

    private void generatePathName(String name, Set<String> indexedStorageKeys) {
        File dir = chooseTargetDirectory(appsDir(), name, indexedStorageKeys);
        appDirName = dir.getName();
        targetDir = dir;
    }

    /** Compatibility helper retained for focused path-selection unit tests. */
    static File chooseTargetDirectory(File appsDir, String name) {
        return chooseTargetDirectory(appsDir, name, Collections.emptySet());
    }

    /** Pure path selection boundary: neither recovery names nor indexed identities may be reused. */
    static File chooseTargetDirectory(File appsDir, String name, Set<String> indexedStorageKeys) {
        String safeName = name == null ? "" : name.trim();
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
            safeName = "MIDlet";
        }
        File dir = new File(appsDir, safeName);
        for (int i = 1;
                LibraryInstallRecovery.isReservedStorageKey(dir.getName()) ||
                        dir.exists() || indexedStorageKeys.contains(dir.getName());
                i++) {
            dir = new File(appsDir, safeName + "_" + i);
        }
        return dir;
    }

    private void downloadJar() throws ConverterException {
        Uri jarUri = Uri.parse(newDesc.getJarUrl());
        if (jarUri.getScheme() == null) {
            String schemeOfJadSource = this.uri == null ? null : this.uri.getScheme();
            if ("http".equals(schemeOfJadSource) || "https".equals(schemeOfJadSource)) {
                List<String> pathSegments = uri.getPathSegments();
                if (pathSegments.isEmpty()) throw new ConverterException("Can't resolve relative JAR URL");
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
        scratch.clear();
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
