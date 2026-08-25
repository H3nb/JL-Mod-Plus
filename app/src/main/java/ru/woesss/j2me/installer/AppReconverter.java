/*
 * Copyright 2026 JL-Mod Plus contributors
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

import android.util.Log;

import com.android.dx.command.dexer.Main;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;

import javax.microedition.shell.timing.TimingTransformMetadata;

import org.microemu.android.asm.MemoryEditorTransformMetadata;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.librarydb.LibraryIconOverride;
import ru.playsoftware.j2meloader.librarydb.LibraryInstallRecovery;
import ru.playsoftware.j2meloader.util.ConverterException;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;
import ru.woesss.util.zip.ZipFile;

/**
 * Repairs an installed converted MIDlet without asking the user to reinstall it.
 *
 * <p>This deliberately owns only the filesystem part of an installation. A reconversion does not
 * change the library identity, title, profile, saves, or database row; it replaces only the
 * converted payload and its descriptor marker. The same process-wide permit and recovery journal
 * used by {@link AppInstaller} keep this path serialized and crash-recoverable.</p>
 */
public final class AppReconverter {
    private static final String TAG = AppReconverter.class.getSimpleName();

    private AppReconverter() {
    }

    /** Returns true when the installed payload is absent or was produced by an old transformer. */
    public static boolean needsReconversion(File appDir) {
        if (appDir == null || !appDir.isDirectory()) return true;
        File payload = usablePayload(appDir);
        File descriptorFile = fileWithSuffix(appDir, Config.MIDLET_MANIFEST_FILE);
        File memoryMetadata = fileWithSuffix(appDir, Config.MIDLET_MEMORY_METADATA);
        if (payload == null || !Config.isUsableFile(descriptorFile)
                || !Config.isUsableFile(memoryMetadata)) return true;
        try {
            Descriptor descriptor = new Descriptor(descriptorFile, false);
            if (!TimingTransformMetadata.isCompatible(descriptor.getAttrs())) return true;
            return !MemoryEditorTransformMetadata.read(memoryMetadata).isCompatible();
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to validate converted timing marker: " + appDir, error);
            return true;
        }
    }

    /** Returns true when an installed source JAR is available for automatic reconversion. */
    public static boolean hasRetainedSource(File appDir) {
        return appDir != null && appDir.isDirectory()
                && Config.isUsableFile(fileWithSuffix(appDir, Config.MIDLET_RES_FILE));
    }

    /** Returns true when either converted payload format contains data for a legacy launch. */
    public static boolean hasUsableConvertedPayload(File appDir) {
        return usablePayload(appDir) != null;
    }

    /**
     * Rebuilds the payload in a sibling staging directory and publishes it atomically.
     *
     * @throws IOException if the target is not an installed app directory or its retained source
     *                     JAR is unavailable
     * @throws ConverterException if DX or payload publication fails
     */
    public static void reconvert(File requestedAppDir) throws IOException, ConverterException {
        File appDir = requireInstalledAppDirectory(requestedAppDir);
        File workdir = appDir.getParentFile().getParentFile();
        String storageKey = appDir.getName();
        File retainedJar = fileWithSuffix(appDir, Config.MIDLET_RES_FILE);
        if (!Config.isUsableFile(retainedJar)) {
            throw new IOException("Retained MIDlet JAR is unavailable: " + storageKey);
        }

        try (InstallerExecutionCoordinator.Permit ignored = InstallerExecutionCoordinator.acquire()) {
            // Another launch or an explicit reinstall may have repaired this app while this
            // request was waiting for the converter permit.
            if (!needsReconversion(appDir)) return;

            LibraryInstallRecovery.discardStaging(workdir);
            File staging = LibraryInstallRecovery.stagingDirectory(workdir);
            if (!staging.mkdirs()) {
                throw new ConverterException("Can't create reconversion staging directory: " + staging);
            }

            File replacementBackup = null;
            try {
                Descriptor descriptor = loadManifest(retainedJar);
                // The retained JAR manifest is not the installed descriptor. The latter contains
                // the JAD attributes that AppInstaller merged during the original install,
                // including vendor-specific values and the source URL/size. Preserve those
                // values or reconversion silently changes the installed MIDlet identity/config.
                mergeInstalledDescriptor(
                        descriptor, fileWithSuffix(appDir, Config.MIDLET_MANIFEST_FILE));
                try {
                    Main.main(new String[]{
                            "--no-optimize",
                            "--memory-metadata="
                                    + staging.getAbsolutePath() + Config.MIDLET_MEMORY_METADATA,
                            "--output=" + staging.getAbsolutePath() + Config.MIDLET_DEX_ARCH,
                            retainedJar.getAbsolutePath(),
                    });
                } catch (Throwable error) {
                    throw new ConverterException("Dexing error during automatic reconversion", error);
                }

                File generatedPayload = fileWithSuffix(staging, Config.MIDLET_DEX_ARCH);
                if (!generatedPayload.isFile() || generatedPayload.length() <= 0L) {
                    throw new ConverterException("DX produced no converted MIDlet payload");
                }
                File generatedMetadata = fileWithSuffix(staging, Config.MIDLET_MEMORY_METADATA);
                if (!generatedMetadata.isFile() || generatedMetadata.length() <= 0L) {
                    throw new ConverterException("DX produced no Memory Editor metadata");
                }
                TimingTransformMetadata.mark(descriptor.getAttrs());
                FileUtils.copyFileUsingChannel(retainedJar, fileWithSuffix(staging, Config.MIDLET_RES_FILE));
                extractIcon(descriptor, retainedJar, staging);
                descriptor.writeTo(fileWithSuffix(staging, Config.MIDLET_MANIFEST_FILE));
                LibraryIconOverride.applyPersistedOverride(workdir, storageKey, staging);

                replacementBackup = LibraryInstallRecovery.createBackup(workdir, storageKey, appDir);
                if (!staging.renameTo(appDir)) {
                    if (!LibraryInstallRecovery.restoreBackup(appDir, replacementBackup)) {
                        Log.e(TAG, "Automatic reconversion publish failed and rollback failed: " + storageKey);
                    }
                    throw new ConverterException("Can't publish automatically reconverted MIDlet: " + storageKey);
                }
            } finally {
                if (staging.exists()) FileUtils.deleteDirectory(staging);
            }

            if (replacementBackup != null &&
                    !LibraryInstallRecovery.discardBackup(workdir, storageKey)) {
                // The replacement is already valid. Leave the journal for the normal startup
                // recovery pass instead of turning a successful conversion into a launch error.
                Log.w(TAG, "Automatic reconversion succeeded but backup cleanup was deferred: " + storageKey);
            }
        }
    }

    private static File requireInstalledAppDirectory(File requestedAppDir) throws IOException {
        if (requestedAppDir == null) throw new IOException("MIDlet path is missing");
        File appDir = requestedAppDir.getCanonicalFile();
        File convertedRoot = new File(Config.getAppDir()).getCanonicalFile();
        File parent = appDir.getParentFile();
        if (parent == null || !parent.equals(convertedRoot) || !appDir.isDirectory()) {
            throw new IOException("MIDlet path is not an installed application: " + appDir);
        }
        // The recovery implementation performs the same validation, but rejecting this before
        // touching staging makes the path boundary explicit for exported legacy launch intents.
        if (LibraryInstallRecovery.isReservedStorageKey(appDir.getName())) {
            throw new IOException("MIDlet path uses a reserved storage key: " + appDir.getName());
        }
        return appDir;
    }

    private static void extractIcon(Descriptor descriptor, File retainedJar, File staging)
            throws IOException {
        String icon = descriptor.getIcon();
        File iconFile = fileWithSuffix(staging, Config.MIDLET_ICON_FILE);
        if (icon == null || icon.trim().isEmpty()) {
            // Do not carry a stale icon from an older conversion when the source JAR has no icon.
            //noinspection ResultOfMethodCallIgnored
            iconFile.delete();
            return;
        }
        try {
            ZipUtils.unzipEntry(retainedJar, icon, iconFile);
        } catch (IOException error) {
            Log.w(TAG, "Can't unzip icon during automatic reconversion: " + icon, error);
            //noinspection ResultOfMethodCallIgnored
            iconFile.delete();
        }
    }

    private static Descriptor loadManifest(File jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            FileHeader manifest = zip.getFileHeader(JarFile.MANIFEST_NAME);
            if (manifest == null) throw new IOException("JAR not have " + JarFile.MANIFEST_NAME);
            try (ZipInputStream input = zip.getInputStream(manifest)) {
                return new Descriptor(new String(IOUtils.toByteArray(input)), false);
            }
        }
    }

    /** Applies the descriptor persisted for the installed MIDlet over the source JAR manifest. */
    static Descriptor mergeInstalledDescriptor(Descriptor sourceManifest, File installedDescriptor)
            throws IOException {
        sourceManifest.merge(new Descriptor(installedDescriptor, false));
        return sourceManifest;
    }

    private static File fileWithSuffix(File directory, String suffix) {
        return new File(directory.getAbsolutePath() + suffix);
    }

    private static File usablePayload(File appDir) {
        if (appDir == null || !appDir.isDirectory()) return null;
        File archive = fileWithSuffix(appDir, Config.MIDLET_DEX_ARCH);
        if (Config.isUsableFile(archive)) return archive;
        File dex = fileWithSuffix(appDir, Config.MIDLET_DEX_FILE);
        return Config.isUsableFile(dex) ? dex : null;
    }
}
