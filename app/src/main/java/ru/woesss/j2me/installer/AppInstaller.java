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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import ru.playsoftware.j2meloader.librarydb.WorkDirLayout;
import ru.playsoftware.j2meloader.util.ConverterException;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;
import ru.woesss.util.TextUtils;
import ru.woesss.util.zip.ZipFile;
import javax.microedition.shell.transform.MidletTransformMetadata;

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
    static final int STATUS_AMBIGUOUS = 6;

    private final long id;
    private final LibraryViewModel libraryViewModel;
    private final long requestedGeneration;
    private final File requestedWorkdir;
    private final String requestedStorageKey;
    private final InstallerScratch scratch;
    private final File resolvedJar;

    private volatile boolean cancelled;
    private Uri uri;
    private Descriptor manifest;
    private Descriptor newDesc;
    private String appDirName;
    private File targetDir;
    private File srcJar;
    private File srcJad;
    private File tmpDir;
    private LibraryAppRow currentApp;
    private File srcFile;
    private long expectedGeneration = NO_GENERATION;
    private File expectedWorkdir;
    private long installedId = NO_ID;
    private String installedTitle;
    private String installedPath;
    private int loadedStatus = NO_STATUS;
    private String matchedIdentity = "";
    private String loadedIdentity = "";
    private volatile boolean published;
    enum Stage { READING, DOWNLOADING, WAITING, CONVERTING, SAVING }
    private volatile Stage stage = Stage.READING;
    interface Progress { void update(Stage stage); }
    private Progress progress = ignored -> {};

    void setProgress(Progress progress) { this.progress = progress; }
    Stage getStage() { return stage; }
    boolean hasPublished() { return published; }
    private void stage(Stage next) { stage = next; progress.update(next); }
    private void checkCancelled() throws java.io.InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Installation cancelled");
        }
    }

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

    long getExpectedGeneration() {
        return expectedGeneration;
    }

    File getExpectedWorkdir() {
        return expectedWorkdir;
    }

    /** Load and check app info from source against one captured READY Library generation. */
    void loadInfo(SingleEmitter<Integer> emitter) throws IOException, ConverterException {
        checkCancelled();
        stage(Stage.READING);
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
            newDesc = AppReconverter.mergeInstalledDescriptor(loadManifest(srcJar),
                    child(targetDir, Config.MIDLET_MANIFEST_FILE));
            File retainedJad = new File(targetDir, AppReconverter.RETAINED_JAD);
            srcJad = retainedJad.isFile() ? scratch.copy(retainedJad, "reviewed.jad") : null;
            srcJar = scratch.copy(srcJar, "reviewed.jar");
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
            srcJad = scratch.copy(srcFile, "reviewed.jad");
            newDesc = new Descriptor(srcJad, true);
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
            srcJad = scratch.copy(srcFile, "reviewed.jad");
            newDesc = new Descriptor(srcJad, true);
            manifest = loadManifest(srcJar);
            if (!manifest.equals(newDesc)) {
                emitLoadedStatus(emitter, STATUS_UNMATCHED);
                return;
            }
        } else {
            srcJar = scratch.copy(srcFile, "reviewed.jar");
            newDesc = loadManifest(srcJar);
        }
        if (srcJar != null && resolvedJar == null) srcJar = scratch.copy(srcJar, "reviewed.jar");
        emitLoadedStatus(emitter, checkDescriptor());
    }

    private void emitLoadedStatus(SingleEmitter<Integer> emitter, int status) {
        loadedStatus = status;
        loadedIdentity = matchedIdentity;
        emitter.onSuccess(status);
    }

    void useJarOnly(SingleEmitter<Integer> emitter) throws IOException {
        checkCancelled();
        if (srcJar == null || manifest == null) throw new IOException("JAR fallback is unavailable");
        newDesc = manifest;
        srcJad = null;
        if (srcJar != null && resolvedJar == null) {
            srcJar = scratch.copy(srcJar, "reviewed.jar");
        }
        emitLoadedStatus(emitter, checkDescriptor());
    }

    private void bindReadyGeneration() throws IOException {
        LibraryGenerationToken generation = libraryViewModel.readyGeneration();
        if (generation == null) throw new InstallerFailure("Library is not READY for installation");
        expectedGeneration = generation.getGeneration();
        expectedWorkdir = generation.getEmulatorDir().getCanonicalFile();
    }

    private void verifyRequestedGeneration() throws IOException {
        if (requestedGeneration == NO_GENERATION || requestedGeneration != expectedGeneration ||
                requestedWorkdir == null ||
                !requestedWorkdir.getCanonicalFile().equals(expectedWorkdir)) {
            throw new InstallerFailure("Library generation changed before opening reinstall target");
        }
    }

    private void verifyActiveGeneration() throws IOException {
        if (!libraryViewModel.isReadyGeneration(expectedGeneration, expectedWorkdir)) {
            throw new InstallerFailure("Library generation changed while installer was running");
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

    private void downloadJad() throws IOException {
        stage(Stage.DOWNLOADING);
        srcFile = scratch.file("download.jad");
        java.net.URI finalUri = InstallerDownload.download(
                InstallerDownload.resolve(null, uri.toString()), srcFile,
                () -> cancelled, (bytes, total) -> {});
        uri = Uri.parse(finalUri.toString());
    }
    /** Finalize converted files first, then publish a generation-bound Room3 mutation asynchronously. */
    void install(SingleEmitter<Integer> emitter) throws ConverterException, IOException {
        checkCancelled();
        // Source preparation owns only request scratch and must not block other filesystem operations.
        if (srcJar == null) {
            srcJar = scratch.file("download.jar");
            try {
                downloadJar();
                manifest = loadManifest(srcJar);
            } catch (IOException error) {
                srcJar = null;
                throw error;
            }
            if (!manifest.equals(newDesc)) {
                emitLoadedStatus(emitter, STATUS_UNMATCHED);
                return;
            }
        }
        stage(Stage.WAITING);
        final InstallerExecutionCoordinator.Permit executionPermit = InstallerExecutionCoordinator.acquire(() -> cancelled);
        boolean handedOff = false;
        try {
            checkCancelled();
            verifyActiveGeneration();
            if (id != NO_ID) {
                verifyRequestedGeneration();
                currentApp = requireRequestedAppIdentity();
                File currentJar = child(targetDir, Config.MIDLET_RES_FILE);
                Descriptor currentDescriptor = AppReconverter.mergeInstalledDescriptor(loadManifest(currentJar),
                        child(targetDir, Config.MIDLET_MANIFEST_FILE));
                File retainedJad = new File(targetDir, AppReconverter.RETAINED_JAD);
                srcJad = retainedJad.isFile() ? scratch.copy(retainedJad, "reviewed.jad") : null;
                if (!newDesc.getAttrs().equals(currentDescriptor.getAttrs()) ||
                        !filesHaveSameContents(srcJar, currentJar)) {
                    newDesc = currentDescriptor;
                    srcJar = scratch.copy(currentJar, "reviewed.jar");
                    emitLoadedStatus(emitter, STATUS_EQUAL);
                    return;
                }
            } else {
                int currentStatus = checkDescriptor();
                if (loadedStatus != NO_STATUS && (currentStatus != loadedStatus ||
                        !loadedIdentity.equals(matchedIdentity))) {
                    // Source inspection and user confirmation happen outside the global permit. If a
                    // previous installer changed the Library while this request was pending, surface
                    // the new classification instead of applying a stale NEW/UPDATE/REINSTALL decision.
                    emitLoadedStatus(emitter, currentStatus);
                    return;
                }
            }

            try {
                WorkDirLayout.prepareConverted(expectedWorkdir,
                        !libraryViewModel.storageKeys(expectedGeneration, expectedWorkdir).isEmpty());
            } catch (IOException error) {
                throw new InstallerFailure("Work directory is unavailable for installation", error);
            }
            LibraryInstallRecovery.discardStaging(expectedWorkdir);
            tmpDir = LibraryInstallRecovery.stagingDirectory(expectedWorkdir);
            if (!tmpDir.mkdirs()) {
                throw new InstallerFailure("Can't create staging directory: '" + tmpDir + "'");
            }
            stage(Stage.CONVERTING);
            try {
                Main.main(new String[]{"--no-optimize", "--output=" + tmpDir + Config.MIDLET_DEX_ARCH,
                        srcJar.getAbsolutePath()});
            } catch (Throwable e) {
                throw new ConverterException("Dexing error", e);
            }
            File payload = child(tmpDir, Config.MIDLET_DEX_ARCH);
            if (!payload.isFile() || payload.length() == 0L) {
                throw new ConverterException("DX produced no converted MIDlet payload");
            }
            if (manifest != null) {
                manifest.merge(newDesc);
                newDesc = manifest;
            }
            MidletTransformMetadata.mark(newDesc.getAttrs());

            File resJar = child(tmpDir, Config.MIDLET_RES_FILE);
            FileUtils.copyFileUsingChannel(srcJar, resJar);
            if (srcJad != null) FileUtils.copyFileUsingChannel(srcJad,
                    new File(tmpDir, AppReconverter.RETAINED_JAD));
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
            checkCancelled();
            stage(Stage.SAVING);
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
                WorkDirLayout.requireConverted(expectedWorkdir);
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
                tmpDir = null;
                published = true;
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
                            if (!emitter.isDisposed()) emitter.onError(new InstallerFailure(
                                    "Installed files need Library recovery", error));
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
                                    if (visibilityError != null) emitter.onError(new InstallerFailure(
                                            "Installed files need Library recovery", visibilityError));
                                    else emitter.onSuccess(STATUS_SUCCESS);
                                });
                    });
            handedOff = true;
        } catch (IOException error) {
            if (!(error instanceof java.io.InterruptedIOException) &&
                    (stage == Stage.WAITING || stage == Stage.SAVING)) {
                throw new InstallerFailure("Unable to finish installation in this work directory", error);
            }
            throw error;
        } finally {
            if (!handedOff) {
                deleteTemp();
                executionPermit.close();
            }
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
        srcJar = scratch.copy(jar, "reviewed.jar");
        manifest = loadManifest(srcJar);
        return manifest.equals(newDesc);
    }

    private boolean checkContentUriJar(Uri jadUri, File jadFile) throws IOException, ConverterException {
        Uri jarUri = Uri.parse(newDesc.getJarUrl());
        if (jarUri.getScheme() == null) jarUri = FileUtils.resolveSiblingUri(jadUri, jarUri);
        if (jarUri == null || !"content".equals(jarUri.getScheme())) return checkJarFile(jadFile);
        File jar = scratch.materialize(jarUri);
        if (!jar.exists()) throw new ConverterException("Jar-file not found for uri: " + jarUri);
        srcJar = scratch.copy(jar, "reviewed.jar");
        manifest = loadManifest(srcJar);
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
        List<String> identities = new java.util.ArrayList<>();
        for (LibraryAppRow app : candidates) {
            identities.add(app.getId() + ":" + app.getStorageKey() + ":" + app.getSourceVersion());
        }
        Collections.sort(identities);
        matchedIdentity = identities.toString();
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
            return candidates.isEmpty() ? STATUS_NEW : STATUS_AMBIGUOUS;
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

    private void downloadJar() throws IOException {
        stage(Stage.DOWNLOADING);
        java.net.URI base = uri == null ? null : java.net.URI.create(uri.toString());
        InstallerDownload.download(InstallerDownload.resolve(base, newDesc.getJarUrl()), srcJar,
                () -> cancelled, (bytes, total) -> {});
    }

    void cancel() { cancelled = true; }
    void deleteTemp() {
        File owned = tmpDir;
        tmpDir = null;
        if (owned != null) FileUtils.deleteDirectory(owned);
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
        return WorkDirLayout.converted(expectedWorkdir);
    }

    private static File child(File directory, String suffix) {
        return new File(directory.getAbsolutePath() + suffix);
    }
}
