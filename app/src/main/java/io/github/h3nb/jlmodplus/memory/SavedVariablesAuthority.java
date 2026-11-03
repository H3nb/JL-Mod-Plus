// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import io.github.h3nb.jlmodplus.config.InstalledAppWriteGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

/**
 * Main-process authority for Saved definitions. Live guest objects and operation state never
 * enter this service. The immutable source identity and an installation-local marker prevent
 * an imported config or a reused workdir from silently adopting old definitions.
 */
public final class SavedVariablesAuthority extends Service {
    static final int SCHEMA = 1;
    static final int MAX_ITEMS = 64;
    static final int MAX_BYTES = 65_536;
    private static final String FILE_NAME = "saved-variables.json";
    private static final String MARKER_DIRECTORY = "saved-variable-installations";
    private final Object lock = new Object();

    private final ISavedVariablesAuthority.Stub binder = new ISavedVariablesAuthority.Stub() {
        @Override public Bundle list(String path, long appId, String entry) {
            synchronized (lock) {
                try { return rows(load(target(path, appId, entry))); }
                catch (Exception error) { return failed(error); }
            }
        }

        @Override public Bundle save(String path, long appId, String entry, String label,
                                     Bundle locator) {
            synchronized (lock) {
                try {
                    final Target target = target(path, appId, entry);
                    final String name = checkedName(label);
                    final JSONObject descriptor = encodeLocator(locator);
                    final Bundle[] result = new Bundle[1];
                    InstalledAppWriteGuard.Result guard = InstalledAppWriteGuard.create(
                            getApplicationContext()).run(target.path, target.appId, () -> {
                        if (!target.sha.equals(sha256(target.jar))) return false;
                        ensureMarkerForNewDocument(target);
                        JSONObject data = load(target);
                        JSONArray items = data.getJSONArray("items");
                        if (items.length() >= MAX_ITEMS) return false;
                        JSONObject record = new JSONObject();
                        record.put("id", UUID.randomUUID().toString());
                        record.put("label", name);
                        record.put("locator", descriptor);
                        items.put(record);
                        write(target, data);
                        result[0] = rows(data);
                        return true;
                    });
                    return guard == InstalledAppWriteGuard.Result.SUCCESS
                            ? result[0] : failed("Installation changed or Saved storage is full");
                } catch (Exception error) { return failed(error); }
            }
        }

        @Override public Bundle rename(String path, long appId, String entry, String id,
                                       String label) {
            return renameRecord(path, appId, entry, id, checkedNameOrNull(label));
        }

        @Override public Bundle removeMany(String path, long appId, String entry, String[] ids) {
            synchronized (lock) {
                try {
                    if (ids == null || ids.length == 0 || ids.length > MAX_ITEMS) {
                        return failed("Invalid Saved selection");
                    }
                    HashSet<String> selected = new HashSet<>();
                    for (String id : ids) {
                        if (id == null || id.isEmpty() || id.length() > 64
                                || !selected.add(id)) return failed("Invalid Saved record IDs");
                    }
                    Target target = target(path, appId, entry);
                    Bundle[] response = new Bundle[1];
                    InstalledAppWriteGuard.Result guard = InstalledAppWriteGuard.create(
                            getApplicationContext()).run(target.path, target.appId, () -> {
                        if (!target.sha.equals(sha256(target.jar))) return false;
                        JSONObject data = load(target);
                        JSONArray old = data.getJSONArray("items");
                        JSONArray next = new JSONArray();
                        for (int i = 0; i < old.length(); i++) {
                            JSONObject item = old.getJSONObject(i);
                            if (selected.remove(item.getString("id"))) continue;
                            next.put(item);
                        }
                        if (!selected.isEmpty()) return false;
                        data.put("items", next);
                        write(target, data);
                        response[0] = rows(data);
                        return true;
                    });
                    return guard == InstalledAppWriteGuard.Result.SUCCESS
                            ? response[0] : failed("Saved records missing or installation changed");
                } catch (Exception error) { return failed(error); }
            }
        }
    };

    private Bundle renameRecord(String path, long appId, String entry, String id,
                                @Nullable String label) {
        synchronized (lock) {
            try {
                if (id == null || id.length() > 64 || id.isEmpty()
                        || label == null) return failed("Invalid Saved record");
                Target target = target(path, appId, entry);
                Bundle[] response = new Bundle[1];
                InstalledAppWriteGuard.Result guard = InstalledAppWriteGuard.create(
                        getApplicationContext()).run(target.path, target.appId, () -> {
                    if (!target.sha.equals(sha256(target.jar))) return false;
                    JSONObject data = load(target);
                    JSONArray old = data.getJSONArray("items");
                    JSONArray next = new JSONArray();
                    boolean found = false;
                    for (int index = 0; index < old.length(); index++) {
                        JSONObject record = old.getJSONObject(index);
                        if (id.equals(record.getString("id"))) {
                            found = true;
                            record.put("label", label);
                        }
                        next.put(record);
                    }
                    if (!found) return false;
                    data.put("items", next);
                    write(target, data);
                    response[0] = rows(data);
                    return true;
                });
                return guard == InstalledAppWriteGuard.Result.SUCCESS
                        ? response[0] : failed("Record missing or installation changed");
            } catch (Exception error) { return failed(error); }
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    private Target target(String path, long appId, String entry) throws Exception {
        if (path == null || appId <= 0 || !safeString(entry)) {
            throw new IOException("Installed game identity is unavailable");
        }
        File dir = new File(path).getCanonicalFile();
        File parent = dir.getParentFile();
        if (parent == null || !"converted".equals(parent.getName())
                || parent.getParentFile() == null) {
            throw new IOException("Installed workdir is invalid");
        }
        String canonical = dir.getPath();
        // The Library ID check is independent from active runtime routing metadata.
        if (InstalledAppWriteGuard.create(getApplicationContext())
                .resolveCurrentAppId(canonical) != appId) {
            throw new IOException("Installed Library identity changed");
        }
        File jar = new File(dir, "res.jar");
        if (!jar.isFile()) throw new IOException("Installed source JAR is missing");
        File config = new File(new File(parent.getParentFile(), "configs"), dir.getName())
                .getCanonicalFile();
        return new Target(canonical, appId, entry, jar, sha256(jar),
                new AtomicFile(new File(config, FILE_NAME)),
                new AtomicFile(new File(new File(getNoBackupFilesDir(), MARKER_DIRECTORY),
                        appId + ".id")));
    }

    private static JSONObject load(Target target) throws Exception {
        if (!exists(target.file)) return empty(target);
        byte[] bytes = target.file.readFully();
        if (bytes.length > MAX_BYTES) throw new IOException("Saved definitions exceed size limit");
        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (data.getInt("schema") != SCHEMA
                || data.getLong("appId") != target.appId
                || !target.path.equals(data.getString("path"))
                || !target.entry.equals(data.getString("entry"))
                || !target.sha.equals(data.getString("jarSha256"))
                || !exists(target.marker)
                || !marker(target).equals(data.getString("installationMarker"))) {
            throw new IOException("Saved definitions do not match this installation/JAR");
        }
        JSONArray items = data.getJSONArray("items");
        if (items.length() > MAX_ITEMS) throw new IOException("Too many Saved definitions");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.getJSONObject(i);
            String id = row.getString("id");
            if (id.isEmpty() || id.length() > 64 || !ids.add(id)) {
                throw new IOException("Invalid or duplicate Saved ID");
            }
            checkedName(row.getString("label"));
            decodeLocator(row.getJSONObject("locator"));
        }
        return data;
    }

    private static JSONObject empty(Target target) throws Exception {
        return new JSONObject().put("schema", SCHEMA)
                .put("appId", target.appId).put("path", target.path)
                .put("entry", target.entry).put("jarSha256", target.sha)
                .put("installationMarker", exists(target.marker) ? markerQuiet(target) : "")
                .put("items", new JSONArray());
    }

    private static void ensureMarkerForNewDocument(Target target) throws Exception {
        if (exists(target.file) || exists(target.marker)) return;
        File parent = target.marker.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Saved installation marker directory unavailable");
        }
        FileOutputStream output = target.marker.startWrite();
        try {
            output.write(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            target.marker.finishWrite(output);
        } catch (Exception error) {
            target.marker.failWrite(output);
            throw error;
        }
    }

    private static String marker(Target target) throws Exception {
        byte[] bytes = target.marker.readFully();
        if (bytes.length < 16 || bytes.length > 64) throw new IOException("Invalid installation marker");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String markerQuiet(Target target) {
        try { return marker(target); } catch (Exception ignored) { return ""; }
    }

    private static boolean exists(AtomicFile file) {
        return file.getBaseFile().exists()
                || new File(file.getBaseFile().getPath() + ".bak").exists();
    }

    private static void write(Target target, JSONObject data) throws Exception {
        File parent = target.file.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Saved config directory is unavailable");
        }
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Saved definitions exceed size limit");
        FileOutputStream output = target.file.startWrite();
        try {
            output.write(bytes);
            target.file.finishWrite(output);
        } catch (Exception error) {
            target.file.failWrite(output);
            throw error;
        }
    }

    private static Bundle rows(JSONObject data) throws Exception {
        Bundle response = new Bundle();
        ArrayList<Bundle> items = new ArrayList<>();
        JSONArray source = data.getJSONArray("items");
        for (int i = 0; i < source.length(); i++) {
            JSONObject record = source.getJSONObject(i);
            Bundle row = new Bundle();
            row.putString("id", record.getString("id"));
            row.putString("label", record.getString("label"));
            row.putBundle("locator", decodeLocator(record.getJSONObject("locator")));
            items.add(row);
        }
        response.putBoolean("ok", true);
        response.putParcelableArrayList("items", items);
        return response;
    }

    private static JSONObject encodeLocator(Bundle input) throws Exception {
        if (input == null || input.getInt("savedSchema", -1) != SCHEMA) {
            throw new IOException("Invalid Saved locator version");
        }
        String[] classes = input.getStringArray("savedClasses");
        String[] names = input.getStringArray("savedNames");
        String[] types = input.getStringArray("savedTypes");
        int[] indices = input.getIntArray("savedFieldIndices");
        if (classes == null || names == null || types == null
                || classes.length == 0 || classes.length > 24
                || classes.length != names.length || classes.length != types.length
                || (indices != null && indices.length != classes.length)) {
            throw new IOException("Invalid Saved locator segments");
        }
        JSONArray parts = new JSONArray();
        for (int i = 0; i < classes.length; i++) {
            parts.put(new JSONObject().put("owner", classes[i]).put("name", names[i])
                    .put("type", types[i]).put("index", indices == null ? -1 : indices[i]));
        }
        JSONObject locator = new JSONObject().put("rootKind", input.getInt("savedRootKind"))
                .put("rootClass", input.getString("savedRootClass"))
                .put("segments", parts).put("arrayIndex", input.getInt("savedArrayIndex", -1));
        decodeLocator(locator);
        return locator;
    }

    private static Bundle decodeLocator(JSONObject locator) throws Exception {
        int rootKind = locator.getInt("rootKind");
        String rootClass = locator.getString("rootClass");
        JSONArray parts = locator.getJSONArray("segments");
        int arrayIndex = locator.getInt("arrayIndex");
        if (rootKind < 1 || rootKind > 3 || !safeString(rootClass)
                || parts.length() < 1 || parts.length() > 24 || arrayIndex < -1
                || arrayIndex >= 2_000_000) throw new IOException("Invalid Saved locator");
        String[] owners = new String[parts.length()], names = new String[parts.length()],
                types = new String[parts.length()];
        int[] indices = new int[parts.length()];
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            owners[i] = part.getString("owner");
            names[i] = part.getString("name");
            types[i] = part.getString("type");
            indices[i] = part.getInt("index");
            if (!safeString(owners[i]) || !safeString(names[i]) || !safeString(types[i])
                    || indices[i] < -1 || indices[i] >= 100_000
                    || (indices[i] >= 0 && (i == parts.length() - 1
                    || !types[i].startsWith("[L")))) {
                throw new IOException("Unsupported Saved field segment");
            }
        }
        if (arrayIndex >= 0 && (rootKind != 3 || parts.length() != 1
                || !primitiveArray(types[0]) || indices[0] != -1)) {
            throw new IOException("Unsupported Saved primitive array");
        }
        Bundle descriptor = new Bundle();
        descriptor.putInt("savedSchema", SCHEMA);
        descriptor.putInt("savedRootKind", rootKind);
        descriptor.putString("savedRootClass", rootClass);
        descriptor.putStringArray("savedClasses", owners);
        descriptor.putStringArray("savedNames", names);
        descriptor.putStringArray("savedTypes", types);
        descriptor.putIntArray("savedFieldIndices", indices);
        descriptor.putInt("savedArrayIndex", arrayIndex);
        return descriptor;
    }

    private static boolean primitiveArray(String type) {
        return "[B".equals(type) || "[S".equals(type) || "[C".equals(type)
                || "[I".equals(type) || "[J".equals(type) || "[F".equals(type)
                || "[D".equals(type);
    }

    private static boolean safeString(String input) {
        return input != null && !input.isEmpty()
                && input.length() <= MemoryEngineContract.MAX_IPC_DISPLAY_CHARS;
    }

    private static String checkedName(String input) throws IOException {
        if (input == null || input.trim().isEmpty() || input.trim().length() > 64) {
            throw new IOException("Saved variable name must be 1–64 characters");
        }
        return input.trim();
    }
    @Nullable private static String checkedNameOrNull(String name) {
        try { return checkedName(name); } catch (IOException ignored) { return null; }
    }
    private static Bundle failed(Exception exception) {
        return failed(exception.getMessage() == null ? "Saved storage unavailable" : exception.getMessage());
    }
    private static Bundle failed(String message) {
        Bundle response = new Bundle();
        response.putBoolean("ok", false);
        response.putString("error", message);
        return response;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 255) >>> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static final class Target {
        final String path, entry, sha;
        final long appId;
        final File jar;
        final AtomicFile file, marker;
        Target(String path, long appId, String entry, File jar, String sha,
               AtomicFile file, AtomicFile marker) {
            this.path = path; this.appId = appId; this.entry = entry;
            this.jar = jar; this.sha = sha; this.file = file; this.marker = marker;
        }
    }
}
