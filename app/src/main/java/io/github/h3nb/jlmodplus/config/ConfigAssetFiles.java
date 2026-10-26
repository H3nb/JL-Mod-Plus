/*
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

package io.github.h3nb.jlmodplus.config;

import androidx.annotation.NonNull;
import io.github.h3nb.jlmodplus.util.SvgRasterizer;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Workdir-scoped, non-overwriting file operations for shared skin and MIDI banks. */
public final class ConfigAssetFiles {
    public enum Kind {
        SKIN(Config.SKINS_DIR, new String[]{".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".svg"}),
        SOUNDBANK(Config.SOUNDBANKS_DIR, new String[]{".sf2", ".dls"});

        final String folder;
        public final String[] extensions;

        Kind(String folder, String[] extensions) {
            this.folder = folder;
            this.extensions = extensions;
        }
    }

    public static final class Imported {
        public final String name;
        public final boolean copied;

        Imported(String name, boolean copied) {
            this.name = name;
            this.copied = copied;
        }
    }

    public static final class NameConflictException extends IOException {
        NameConflictException(String path) { super("Asset name already used: " + path); }
    }

    private ConfigAssetFiles() {}

    public static File directory(@NonNull File workdir, @NonNull Kind kind) throws IOException {
        File dir = new File(workdir, kind.folder.substring(1));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create asset directory");
        }
        return dir.getCanonicalFile();
    }

    private static boolean allowed(Kind kind, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : kind.extensions) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static void checkName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.startsWith(".") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.endsWith(" ")) {
            throw new IOException("Invalid asset name");
        }
    }

    private static File existingName(File dir, String name) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot list asset directory");
        for (File file : files) {
            if (file.getName().equalsIgnoreCase(name)) return file;
        }
        return null;
    }

    private static File existingAsset(File dir, String name) throws IOException {
        checkName(name);
        File file = new File(dir, name);
        if (!file.isFile() || !file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) {
            throw new IOException("Asset file is missing or unsafe");
        }
        return file;
    }

    private static boolean sameContent(File a, File b) throws IOException {
        if (a.length() != b.length()) return false;
        try (FileInputStream left = new FileInputStream(a);
             FileInputStream right = new FileInputStream(b)) {
            byte[] x = new byte[16 * 1024];
            byte[] y = new byte[x.length];
            int n;
            while ((n = left.read(x)) != -1) {
                int offset = 0;
                while (offset < n) {
                    int read = right.read(y, offset, n - offset);
                    if (read < 0) return false;
                    offset += read;
                }
                for (int i = 0; i < n; i++) if (x[i] != y[i]) return false;
            }
            return right.read() == -1;
        }
    }

    /** Lightweight signature gate; the native synth or Android bitmap decoder remains authoritative. */
    private static void checkHeader(Kind kind, File file) throws IOException {
        if (kind == Kind.SKIN && SvgRasterizer.isSvg(file)) {
            SvgRasterizer.read(file);
            return;
        }
        byte[] bytes = new byte[12];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(bytes) != bytes.length) throw new IOException("Invalid asset file");
        }
        boolean riff = match(bytes, 0, "RIFF");
        boolean valid;
        if (kind == Kind.SOUNDBANK) {
            valid = riff && (match(bytes, 8, "sfbk") || match(bytes, 8, "DLS "));
        } else {
            valid = (bytes[0] == (byte) 0x89 && match(bytes, 1, "PNG"))
                    || ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8)
                    || match(bytes, 0, "GIF8") || match(bytes, 0, "BM")
                    || (riff && match(bytes, 8, "WEBP"));
        }
        if (!valid) throw new IOException("Invalid asset format");
    }

    private static boolean match(byte[] bytes, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (bytes[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    public static synchronized Imported importFile(File workdir, Kind kind, File source) throws IOException {
        File dir = directory(workdir, kind);
        String original = source.getName();
        checkName(original);
        if (!allowed(kind, original) || !source.isFile()) throw new IOException("Unsupported asset");
        checkHeader(kind, source);
        File duplicate = existingName(dir, original);
        if (duplicate != null && duplicate.isFile()) {
            if (source.getCanonicalFile().equals(duplicate.getCanonicalFile())
                    || sameContent(source, duplicate)) {
                return new Imported(duplicate.getName(), false);
            }
        }
        String candidate = original;
        String ext = extension(original);
        String stem = original.substring(0, original.length() - ext.length());
        for (int index = 2; existingName(dir, candidate) != null; index++) {
            candidate = stem + " (" + index + ")" + ext;
        }
        File staged = File.createTempFile(".asset-import-", ".tmp", dir);
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Asset operation was interrupted");
                    }
                    output.write(buffer, 0, n);
                }
                output.getFD().sync();
            }
            checkHeader(kind, staged);
            File target = new File(dir, candidate);
            publishWithoutReplacing(staged, target);
            return new Imported(candidate, true);
        } finally {
            staged.delete();
        }
    }

    /**
     * Publishes without replacing an existing target. A hard link makes completed content
     * visible atomically when the workdir filesystem supports it. External/FUSE storage
     * often does not support links, so reserve the destination with createNewFile (O_EXCL)
     * and copy the completed source only after that reservation succeeds.
     */
    private static void publishWithoutReplacing(File source, File target) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Asset operation was interrupted");
        if (target.exists()) throw new NameConflictException(target.getPath());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Os.link(source.getPath(), target.getPath());
                return;
            } catch (ErrnoException failure) {
                if (failure.errno == OsConstants.EEXIST) {
                    throw new NameConflictException(target.getPath());
                }
                // Some external/FUSE filesystems do not support links. Use O_EXCL below.
            }
        }
        if (!target.createNewFile()) throw new NameConflictException(target.getPath());
        boolean published = false;
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Asset operation was interrupted");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            published = true;
        } finally {
            if (!published) target.delete();
        }
    }

    /** The extension is retained so renaming cannot accidentally change file format. */
    public static synchronized String rename(File workdir, Kind kind, String oldName, String newStem)
            throws IOException {
        File dir = directory(workdir, kind);
        File source = existingAsset(dir, oldName);
        String trimmed = newStem.trim();
        checkName(trimmed);
        String destinationName = trimmed + extension(oldName);
        checkName(destinationName);
        if (source.getName().equals(destinationName)) return destinationName;
        File occupied = existingName(dir, destinationName);
        if (occupied != null && !occupied.equals(source)) {
            throw new IOException("Asset name already used");
        }
        File target = new File(dir, destinationName);
        publishWithoutReplacing(source, target);
        if (!source.delete()) {
            if (!target.delete()) {
                throw new IOException("Renamed copy was created but the source could not be removed");
            }
            throw new IOException("Could not remove the original asset");
        }
        return destinationName;
    }

    public static synchronized void delete(File workdir, Kind kind, String name) throws IOException {
        File source = existingAsset(directory(workdir, kind), name);
        if (!source.delete()) throw new IOException("Could not delete asset");
    }
}
