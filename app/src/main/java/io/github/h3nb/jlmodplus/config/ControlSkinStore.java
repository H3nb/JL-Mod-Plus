package io.github.h3nb.jlmodplus.config;

import android.graphics.BitmapFactory;
import android.system.ErrnoException;
import android.system.Os;
import androidx.annotation.Nullable;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Workdir-global, folder-based virtual button skin sets. No runtime writes or UI state. */
public final class ControlSkinStore {
    private ControlSkinStore() {}

    /** Stable slot order, independent of the MIDlet's mapped key codes. */
    public static final String[] KEYS = {
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "star", "pound",
        "soft_left", "soft_right", "d", "c", "up_left", "up", "up_right",
        "left", "right", "down_left", "down", "down_right", "fire", "a", "b", "menu"
    };
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 2048;
    private static final String META = "skin.json";

    public static final class SetInfo {
        public final String id;
        public final String name;
        public final File folder;
        SetInfo(String id, File folder) {
            this.id = id;
            this.name = folder.getName();
            this.folder = folder;
        }
    }

    public static File directory(File workdir) {
        return new File(new File(workdir, "skins"), "controls");
    }

    public static boolean validKey(String key) {
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    private static String safeName(String name) throws IOException {
        String value = name.trim();
        if (!value.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ._()\\-]{0,60}")
                || value.equals(".") || value.equals("..") || value.endsWith(".")) {
            throw new IOException("Invalid set name");
        }
        return value;
    }

    private static File checkedFolder(File workdir, File folder) throws IOException {
        File root = directory(workdir).getCanonicalFile();
        if (!root.equals(folder.getCanonicalFile().getParentFile())) {
            throw new IOException("Unsafe control skin path");
        }
        return folder;
    }

    private static JSONObject metadata(File dir) {
        File file = new File(dir, META);
        if (!file.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int)Math.min(file.length(), 65536)];
            int pos = 0;
            while (pos < data.length) {
                int n = in.read(data, pos, data.length - pos);
                if (n < 0) break;
                pos += n;
            }
            return new JSONObject(new String(data, 0, pos, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String id(JSONObject json) {
        String id = json.optString("id", "");
        try { return UUID.fromString(id).toString(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static void atomicRename(File from, File to) throws IOException {
        try { Os.rename(from.getAbsolutePath(), to.getAbsolutePath()); }
        catch (ErrnoException e) { throw new IOException("Cannot publish skin asset", e); }
    }

    private static void saveMeta(File folder, JSONObject json) throws IOException {
        try {
            json.put("version", 1);
            if (id(json).isEmpty()) json.put("id", UUID.randomUUID().toString());
        } catch (org.json.JSONException e) { throw new IOException(e); }
        File temp = File.createTempFile(".skin-", ".tmp", folder);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            atomicRename(temp, new File(folder, META));
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException)e : new IOException(e);
        } finally { temp.delete(); }
    }

    public static synchronized List<SetInfo> list(File workdir) throws IOException {
        File root = directory(workdir);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create control skins directory");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) throw new IOException("Cannot list control skins");
        Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<SetInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File folder : dirs) {
            if (folder.getName().startsWith(".")) continue;
            try {
                safeName(folder.getName());
                checkedFolder(workdir, folder);
                JSONObject json = metadata(folder);
                String uuid = id(json);
                if (uuid.isEmpty() || !seen.add(uuid)) {
                    uuid = UUID.randomUUID().toString();
                    json.put("id", uuid);
                    saveMeta(folder, json);
                    seen.add(uuid);
                }
                result.add(new SetInfo(uuid, folder));
            } catch (IOException | org.json.JSONException ignored) {
                // An invalid external directory cannot hide other usable sets.
            }
        }
        return result;
    }

    @Nullable
    public static synchronized SetInfo find(File workdir, String setId) throws IOException {
        if (setId == null || setId.isEmpty()) return null;
        for (SetInfo set : list(workdir)) if (set.id.equals(setId)) return set;
        return null;
    }

    /** Read-only resolution for the isolated MIDlet process. */
    @Nullable public static SetInfo findReadonly(File workdir, String setId) {
        if (setId == null || setId.isEmpty()) return null;
        File[] folders = directory(workdir).listFiles(File::isDirectory);
        if (folders == null) return null;
        SetInfo match = null;
        for (File folder : folders) {
            try {
                checkedFolder(workdir, folder);
                if (setId.equals(id(metadata(folder)))) {
                    if (match != null) return null; // Ambiguous external duplicate.
                    match = new SetInfo(setId, folder);
                }
            } catch (IOException ignored) { }
        }
        return match;
    }

    private static SetInfo required(File root, String setId) throws IOException {
        SetInfo found = find(root, setId);
        if (found == null) throw new IOException("Control skin set not found");
        return found;
    }

    private static File availableFolder(File workdir, String name) throws IOException {
        String safe = safeName(name);
        File base = directory(workdir);
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create skin directory");
        File[] existing = base.listFiles();
        if (existing == null) throw new IOException("Cannot list sets");
        for (File file : existing) {
            if (file.getName().equalsIgnoreCase(safe)) throw new IOException("Set name already in use");
        }
        File target = new File(base, safe);
        checkedFolder(workdir, target);
        return target;
    }

    public static synchronized SetInfo create(File workdir, String name) throws IOException {
        File folder = availableFolder(workdir, name);
        if (!folder.mkdir()) throw new IOException("Cannot create set");
        JSONObject json = new JSONObject();
        try {
            json.put("id", UUID.randomUUID().toString());
            json.put("defaultMode", "icon");
            saveMeta(folder, json);
            return new SetInfo(id(json), folder);
        } catch (Exception e) {
            folder.delete();
            throw e instanceof IOException ? (IOException)e : new IOException(e);
        }
    }

    public static synchronized SetInfo rename(File workdir, String setId, String newName) throws IOException {
        SetInfo set = required(workdir, setId);
        if (set.name.equals(safeName(newName))) return set;
        File destination = availableFolder(workdir, newName);
        atomicRename(set.folder, destination);
        return new SetInfo(setId, destination);
    }

    public static synchronized SetInfo duplicate(File workdir, String setId, String name) throws IOException {
        SetInfo source = required(workdir, setId);
        SetInfo copy = create(workdir, name);
        try {
            File[] files = source.folder.listFiles();
            if (files == null) throw new IOException("Cannot read source set");
            for (File file : files) {
                if (!file.isFile() || file.getName().equals(META)) continue;
                String filename = file.getName();
                boolean allowed = false;
                for (String key : KEYS) {
                    if (filename.equals("key_" + key + ".png")
                            || filename.equals("key_" + key + ".webp")
                            || filename.equals("key_" + key + "_pressed.png")
                            || filename.equals("key_" + key + "_pressed.webp")) {
                        allowed = true;
                        break;
                    }
                }
                if (allowed) {
                    try (FileInputStream in = new FileInputStream(file);
                         FileOutputStream out = new FileOutputStream(new File(copy.folder, filename))) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        out.getFD().sync();
                    }
                }
            }
            JSONObject json = metadata(source.folder);
            json.put("id", copy.id);
            saveMeta(copy.folder, json);
            return copy;
        } catch (Exception e) {
            removeContents(copy.folder);
            copy.folder.delete();
            throw e instanceof IOException ? (IOException)e : new IOException(e);
        }
    }

    private static void removeContents(File folder) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Cannot list set");
        for (File file : files) {
            if (!file.isFile() || !file.delete()) throw new IOException("Cannot delete set asset");
        }
    }

    public static synchronized void delete(File workdir, String setId) throws IOException {
        SetInfo set = required(workdir, setId);
        removeContents(set.folder);
        if (!set.folder.delete()) throw new IOException("Cannot remove set folder");
    }

    public static String mode(File folder, String key) {
        JSONObject json = metadata(folder);
        String defaultMode = json.optString("defaultMode", "icon");
        String mode = json.optJSONObject("buttons") == null ? defaultMode :
                json.optJSONObject("buttons").optString("key_" + key, defaultMode);
        return "full".equals(mode) ? "full" : "icon";
    }

    public static synchronized void setMode(File workdir, String setId, String key, String mode) throws IOException {
        if (!validKey(key) || (!"icon".equals(mode) && !"full".equals(mode))) {
            throw new IOException("Invalid button mode");
        }
        File dir = required(workdir, setId).folder;
        JSONObject json = metadata(dir);
        JSONObject buttons = json.optJSONObject("buttons");
        if (buttons == null) buttons = new JSONObject();
        try {
            if (mode.equals(json.optString("defaultMode", "icon"))) buttons.remove("key_" + key);
            else buttons.put("key_" + key, mode);
            json.put("buttons", buttons);
            saveMeta(dir, json);
        } catch (org.json.JSONException e) { throw new IOException(e); }
    }

    @Nullable
    public static File image(File folder, String key, boolean pressed) {
        if (!validKey(key)) return null;
        String stem = "key_" + key + (pressed ? "_pressed" : "");
        File png = new File(folder, stem + ".png");
        if (png.isFile()) return png;
        File webp = new File(folder, stem + ".webp");
        return webp.isFile() ? webp : null;
    }

    public static synchronized void importImage(File workdir, String setId, String key,
            boolean pressed, InputStream source, String suffix) throws IOException {
        if (!validKey(key) || (!".png".equals(suffix) && !".webp".equals(suffix))) {
            throw new IOException("Unsupported image");
        }
        File folder = required(workdir, setId).folder;
        File temp = File.createTempFile(".skin-import-", ".tmp", folder);
        try {
            int size = 0;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = source.read(bytes)) != -1) {
                    size += n;
                    if (size > MAX_IMAGE_BYTES) throw new IOException("Image exceeds 8 MB");
                    out.write(bytes, 0, n);
                }
                out.getFD().sync();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temp.getAbsolutePath(), options);
            if (options.outWidth < 1 || options.outHeight < 1
                    || options.outWidth > MAX_DIMENSION || options.outHeight > MAX_DIMENSION
                    || !("image/png".equals(options.outMimeType) && ".png".equals(suffix)
                        || "image/webp".equals(options.outMimeType) && ".webp".equals(suffix))) {
                throw new IOException("Invalid or oversized PNG/WebP");
            }
            String stem = "key_" + key + (pressed ? "_pressed" : "");
            File target = new File(folder, stem + suffix);
            atomicRename(temp, target);
            File alternate = new File(folder, stem + (".png".equals(suffix) ? ".webp" : ".png"));
            if (alternate.exists() && !alternate.delete()) throw new IOException("Cannot remove old image format");
        } finally { temp.delete(); }
    }

    public static synchronized void removeImage(File workdir, String setId,
            String key, boolean pressed) throws IOException {
        File folder = required(workdir, setId).folder;
        for (String suffix : new String[]{".png", ".webp"}) {
            File file = new File(folder, "key_" + key + (pressed ? "_pressed" : "") + suffix);
            if (file.exists() && !file.delete()) throw new IOException("Cannot delete button image");
        }
    }
}
