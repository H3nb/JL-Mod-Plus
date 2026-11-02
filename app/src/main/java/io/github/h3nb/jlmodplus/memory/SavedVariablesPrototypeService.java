// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.AtomicFile;
import androidx.annotation.Nullable;
import io.github.h3nb.jlmodplus.config.InstalledAppWriteGuard;
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Disposable, main-process Saved Variables authority for interactive R&D, not bundle format. */
public final class SavedVariablesPrototypeService extends Service {
    private static final int MAX_BYTES = 65536;
    private static final int MAX_ITEMS = 64;
    private static final String FILE = "saved-variables-prototype.json";
    private final Object lock = new Object();

    private final ISavedVariablesPrototype.Stub binder = new ISavedVariablesPrototype.Stub() {
        @Override public String list(String generation, String path, long appId, String mainClass) {
            synchronized (lock) {
                try {
                    Target target = checkedTarget(generation, path, appId, mainClass);
                    return ok(read(target).getJSONArray("items"));
                } catch (Exception error) {
                    return fail(error.getMessage());
                }
            }
        }

        @Override public String save(String generation, String path, long appId, String mainClass,
                                     String label, String descriptor) {
            synchronized (lock) {
                try {
                    final Target target = checkedTarget(generation, path, appId, mainClass);
                    if (label == null || label.trim().isEmpty() || label.length() > 64
                            || descriptor == null || descriptor.length() > 8192) {
                        return fail("Invalid name or descriptor");
                    }
                    JSONObject location = new JSONObject(descriptor);
                    validateDescriptor(location);
                    final String[] answer = new String[1];
                    InstalledAppWriteGuard.Result result = InstalledAppWriteGuard.create(
                            getApplicationContext()).run(target.path, target.appId, () -> {
                        if (!target.digest.equals(sha256(target.jar))) return false;
                        JSONObject document = read(target);
                        JSONArray items = document.getJSONArray("items");
                        if (items.length() >= MAX_ITEMS) return false;
                        location.put("id", UUID.randomUUID().toString());
                        location.put("label", label.trim());
                        items.put(location);
                        write(target, document);
                        answer[0] = ok(items);
                        return true;
                    });
                    return result == InstalledAppWriteGuard.Result.SUCCESS
                            ? answer[0] : fail("Installation changed or storage unavailable");
                } catch (Exception error) {
                    return fail(error.getMessage());
                }
            }
        }

        @Override public String remove(String generation, String path, long appId, String mainClass,
                                       String recordId) {
            synchronized (lock) {
                try {
                    Target target = checkedTarget(generation, path, appId, mainClass);
                    if (recordId == null || recordId.length() > 64) return fail("Invalid record");
                    final String[] answer = new String[1];
                    InstalledAppWriteGuard.Result result = InstalledAppWriteGuard.create(
                            getApplicationContext()).run(target.path, target.appId, () -> {
                        if (!target.digest.equals(sha256(target.jar))) return false;
                        JSONObject document = read(target);
                        JSONArray records = document.getJSONArray("items");
                        JSONArray remaining = new JSONArray();
                        boolean removed = false;
                        for (int i = 0; i < records.length(); i++) {
                            JSONObject row = records.getJSONObject(i);
                            if (recordId.equals(row.optString("id"))) removed = true;
                            else remaining.put(row);
                        }
                        if (!removed) return false;
                        document.put("items", remaining);
                        write(target, document);
                        answer[0] = ok(remaining);
                        return true;
                    });
                    return result == InstalledAppWriteGuard.Result.SUCCESS
                            ? answer[0] : fail("Record missing or installation changed");
                } catch (Exception error) {
                    return fail(error.getMessage());
                }
            }
        }
    };

    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    private Target checkedTarget(String generation, String path, long appId,
                                 String mainClass) throws Exception {
        MidletSessionStore.State active = MidletSessionStore.read(getApplicationContext());
        if (active == null || active.getGeneration() == null || generation == null
                || !generation.equals(active.getGeneration()) || appId <= 0
                || appId != active.getAppId() || path == null
                || mainClass == null || mainClass.isEmpty()
                || !mainClass.equals(active.getMainClass())) {
            throw new IOException("No matching active installed MIDlet");
        }
        File dir = new File(path).getCanonicalFile();
        if (!dir.getPath().equals(new File(active.getAppPath()).getCanonicalPath())
                || dir.getParentFile() == null
                || !"converted".equals(dir.getParentFile().getName())) {
            throw new IOException("Installed workdir differs");
        }
        if (InstalledAppWriteGuard.create(getApplicationContext())
                .resolveCurrentAppId(dir.getPath()) != appId) {
            throw new IOException("Installed Library identity changed");
        }
        File jar = new File(dir, "res.jar");
        if (!jar.isFile()) throw new IOException("Retained source JAR is missing");
        File config = new File(new File(dir.getParentFile().getParentFile(), "configs"),
                dir.getName()).getCanonicalFile();
        return new Target(dir.getPath(), appId, jar, sha256(jar),
                new AtomicFile(new File(config, FILE)), mainClass);
    }

    private static JSONObject read(Target target) throws Exception {
        JSONObject document;
        if (target.file.getBaseFile().exists() ||
                new File(target.file.getBaseFile().getPath() + ".bak").exists()) {
            byte[] bytes = target.file.readFully();
            if (bytes.length > MAX_BYTES) throw new IOException("Saved definitions exceed limit");
            document = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (document.getInt("schema") != 1
                    || !target.mainClass.equals(document.getString("mainClass"))
                    || !target.digest.equals(document.getString("jarSha256"))) {
                throw new IOException("Saved definitions belong to another game/JAR");
            }
        } else {
            document = new JSONObject().put("schema", 1)
                    .put("mainClass", target.mainClass)
                    .put("jarSha256", target.digest)
                    .put("items", new JSONArray());
        }
        JSONArray items = document.getJSONArray("items");
        if (items.length() > MAX_ITEMS) throw new IOException("Too many saved definitions");
        for (int i = 0; i < items.length(); i++) validateDescriptor(items.getJSONObject(i));
        return document;
    }

    private static void validateDescriptor(JSONObject descriptor) throws Exception {
        int kind = descriptor.getInt("rootKind");
        String root = descriptor.getString("rootClass");
        JSONArray classes = descriptor.getJSONArray("classes");
        JSONArray names = descriptor.getJSONArray("names");
        JSONArray types = descriptor.getJSONArray("types");
        int count = classes.length();
        if (kind < 1 || kind > 3 || root.isEmpty() || root.length() > 256
                || count == 0 || count > 24 || names.length() != count || types.length() != count) {
            throw new IOException("Unsupported locator structure");
        }
        for (int i = 0; i < count; i++) {
            if (classes.getString(i).isEmpty() || names.getString(i).isEmpty()
                    || types.getString(i).isEmpty() || classes.getString(i).length() > 256
                    || names.getString(i).length() > 256 || types.getString(i).length() > 256) {
                throw new IOException("Invalid locator field");
            }
        }
    }

    private static void write(Target target, JSONObject document) throws Exception {
        File dir = target.file.getBaseFile().getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Config path unavailable");
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Saved definitions exceed limit");
        FileOutputStream output = target.file.startWrite();
        try {
            output.write(bytes);
            target.file.finishWrite(output);
        } catch (Exception failure) {
            target.file.failWrite(output);
            throw failure;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) {
            out.append(Character.forDigit((b & 255) >>> 4, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    private static String ok(JSONArray records) throws Exception {
        return new JSONObject().put("ok", true).put("items", records).toString();
    }

    private static String fail(String message) {
        try { return new JSONObject().put("ok", false).put("error",
                message == null ? "Saved Variables unavailable" : message).toString(); }
        catch (Exception ignored) { return "{\"ok\":false}"; }
    }

    private static final class Target {
        final String path;
        final long appId;
        final File jar;
        final String digest;
        final AtomicFile file;
        final String mainClass;
        Target(String path, long appId, File jar, String digest, AtomicFile file, String mainClass) {
            this.path = path;
            this.appId = appId;
            this.jar = jar;
            this.digest = digest;
            this.file = file;
            this.mainClass = mainClass;
        }
    }
}
