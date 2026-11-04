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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

/**
 * Main-process authority for Saved definitions. Live guest objects and operation state never
 * enter this service. Storage writes are guarded against installation replacement; the
 * definitions themselves are portable and are validated when resolved in the guest runtime.
 */
public final class SavedVariablesAuthority extends Service {
    static final int SCHEMA = 3;
    private static final int LOCATOR_SCHEMA = 2;
    static final int MAX_ITEMS = 64;
    static final int MAX_BYTES = 65_536;
    private static final String FILE_NAME = "saved-variables.json";
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
        return new Target(canonical, appId, new AtomicFile(new File(config, FILE_NAME)));
    }

    private static JSONObject load(Target target) throws Exception {
        if (!exists(target.file)) return empty();
        byte[] bytes = target.file.readFully();
        SavedVariablesDocument.validate(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static JSONObject empty() throws Exception {
        return new JSONObject().put("schema", SCHEMA).put("items", new JSONArray());
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
        if (data.getInt("schema") != SCHEMA) {
            JSONArray entries = data.getJSONArray("items");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject record = entries.getJSONObject(i);
                record.put("locator", encodeLocator(decodeLocator(
                        record.getJSONObject("locator"))));
            }
            data.put("schema", SCHEMA);
        }
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        SavedVariablesDocument.validate(bytes);
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

    /** Encode only canonical typed steps. Legacy data is upgraded on the storage boundary. */
    private static JSONObject encodeLocator(Bundle input) throws Exception {
        if (input == null || input.getInt("savedSchema", -1) != LOCATOR_SCHEMA)
            throw new IOException("Invalid Saved locator version");
        ArrayList<Bundle> parts = input.getParcelableArrayList("savedSteps");
        if (parts == null || parts.isEmpty() || parts.size() > 24)
            throw new IOException("Invalid Saved locator steps");
        JSONArray steps = new JSONArray();
        for (Bundle part : parts) {
            if (part == null) throw new IOException("Missing Saved step");
            int kind = part.getInt("kind", -1);
            if (kind == 1) {
                String owner = part.getString("owner"), name = part.getString("name"),
                        type = part.getString("type");
                if (!safeString(owner) || !safeString(name) || !safeString(type))
                    throw new IOException("Invalid Saved field step");
                steps.put(new JSONObject().put("kind", kind).put("owner", owner)
                        .put("name", name).put("type", type));
            } else if (kind == 2) {
                String arrayType = part.getString("arrayType");
                int index = part.getInt("index", -1);
                if (!safeString(arrayType) || !arrayType.startsWith("[")
                        || index < 0 || index >= 2_000_000)
                    throw new IOException("Invalid Saved index step");
                steps.put(new JSONObject().put("kind", kind).put("arrayType", arrayType)
                        .put("index", index));
            } else throw new IOException("Unsupported Saved step");
        }
        JSONObject locator = new JSONObject().put("rootKind", input.getInt("savedRootKind"))
                .put("rootClass", input.getString("savedRootClass")).put("steps", steps);
        decodeLocator(locator);
        return locator;
    }

    private static Bundle decodeLocator(JSONObject locator) throws Exception {
        int rootKind = locator.getInt("rootKind");
        String rootClass = locator.getString("rootClass");
        if (rootKind < 1 || rootKind > 3 || !safeString(rootClass))
            throw new IOException("Invalid Saved root");
        JSONArray encoded;
        if (locator.has("steps")) {
            encoded = locator.getJSONArray("steps");
        } else {
            // Read-only schema-2 conversion: field + optional Object[] index,
            // and the direct-static primitive array target become typed steps.
            JSONArray fields = locator.getJSONArray("segments");
            int primitiveIndex = locator.getInt("arrayIndex");
            if (fields.length() < 1 || fields.length() > 24 || primitiveIndex < -1
                    || primitiveIndex >= 2_000_000)
                throw new IOException("Invalid legacy Saved locator");
            encoded = new JSONArray();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.getJSONObject(i);
                String type = field.getString("type");
                encoded.put(new JSONObject().put("kind", 1)
                        .put("owner", field.getString("owner"))
                        .put("name", field.getString("name")).put("type", type));
                int index = field.getInt("index");
                if (index >= 0) encoded.put(new JSONObject().put("kind", 2)
                        .put("arrayType", type).put("index", index));
            }
            if (primitiveIndex >= 0) encoded.put(new JSONObject().put("kind", 2)
                    .put("arrayType", fields.getJSONObject(fields.length() - 1).getString("type"))
                    .put("index", primitiveIndex));
        }
        if (encoded.length() < 1 || encoded.length() > 24)
            throw new IOException("Invalid Saved path length");
        ArrayList<Bundle> steps = new ArrayList<>();
        for (int i = 0; i < encoded.length(); i++) {
            JSONObject part = encoded.getJSONObject(i);
            int kind = part.getInt("kind");
            Bundle step = new Bundle();
            step.putInt("kind", kind);
            if (kind == 1) {
                String owner = part.getString("owner"), name = part.getString("name"),
                        type = part.getString("type");
                if (!safeString(owner) || !safeString(name) || !safeString(type))
                    throw new IOException("Invalid Saved field");
                step.putString("owner", owner);
                step.putString("name", name);
                step.putString("type", type);
            } else if (kind == 2) {
                int index = part.getInt("index");
                String arrayType = part.getString("arrayType");
                if (index < 0 || index >= 2_000_000 || !safeString(arrayType)
                        || !arrayType.startsWith("["))
                    throw new IOException("Invalid Saved index");
                step.putInt("index", index);
                step.putString("arrayType", arrayType);
            } else throw new IOException("Unsupported Saved step");
            steps.add(step);
        }
        Bundle descriptor = new Bundle();
        descriptor.putInt("savedSchema", LOCATOR_SCHEMA);
        descriptor.putInt("savedRootKind", rootKind);
        descriptor.putString("savedRootClass", rootClass);
        descriptor.putParcelableArrayList("savedSteps", steps);
        return descriptor;
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

    private static final class Target {
        final String path;
        final long appId;
        final AtomicFile file;
        Target(String path, long appId, AtomicFile file) {
            this.path = path;
            this.appId = appId;
            this.file = file;
        }
    }
}
