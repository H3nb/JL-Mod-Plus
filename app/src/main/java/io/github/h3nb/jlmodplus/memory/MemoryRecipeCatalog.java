// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory;

import android.os.Bundle;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Persistent, installed-game-scoped data-only recipe catalog. Called exclusively by the
 * main-process SavedVariablesAuthority under its storage lock and InstalledAppWriteGuard.
 * Guest object identities, execution results and Freeze state are never persisted.
 */
final class MemoryRecipeCatalog {
    static final int MAX_RECIPES = 12;
    static final int MAX_DOCUMENT_BYTES = 24_576;
    private static final int MAX_CATALOG_BYTES = 262_144;
    private static final int SCHEMA = 1;
    private static final String FILE_NAME = "memory-recipes.json";

    private MemoryRecipeCatalog() { }

    static AtomicFile fileFor(AtomicFile savedFile) {
        return new AtomicFile(new File(savedFile.getBaseFile().getParentFile(), FILE_NAME));
    }

    static Bundle list(AtomicFile file, String gameEntry) throws Exception {
        return rows(read(file, gameEntry));
    }

    static Bundle put(AtomicFile file, String gameEntry, String document,
                      String replacingId) throws Exception {
        JSONObject recipe = checkRecipe(document, gameEntry);
        String id = recipe.getString("id");
        JSONObject catalog = read(file, gameEntry);
        JSONArray existing = catalog.getJSONArray("items");
        JSONArray updated = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.getJSONObject(i);
            if (item.getString("id").equals(id)) {
                if (replacingId == null || !replacingId.equals(id)) {
                    throw new IOException("A script with this ID already exists");
                }
                updated.put(record(recipe, document));
                replaced = true;
            } else {
                updated.put(item);
            }
        }
        if (replacingId != null && !replaced) {
            throw new IOException("The script being edited no longer exists");
        }
        if (replacingId == null) {
            if (updated.length() >= MAX_RECIPES) throw new IOException("Script limit reached");
            updated.put(record(recipe, document));
        }
        catalog.put("items", updated);
        write(file, catalog);
        return rows(catalog);
    }

    static Bundle delete(AtomicFile file, String gameEntry, String id) throws Exception {
        if (id == null || id.isEmpty()) throw new IOException("Invalid script ID");
        JSONObject catalog = read(file, gameEntry);
        JSONArray existing = catalog.getJSONArray("items");
        JSONArray updated = new JSONArray();
        boolean found = false;
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.getJSONObject(i);
            if (id.equals(item.getString("id"))) found = true;
            else updated.put(item);
        }
        if (!found) throw new IOException("Script no longer exists");
        catalog.put("items", updated);
        write(file, catalog);
        return rows(catalog);
    }

    private static JSONObject checkRecipe(String document, String gameEntry) throws Exception {
        if (document == null || document.trim().isEmpty() ||
                document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IOException("Recipe document must be 1–24576 UTF-8 bytes");
        }
        JSONObject recipe = new JSONObject(document);
        if (recipe.getInt("formatVersion") != 1) throw new IOException("Unsupported recipe version");
        String id = recipe.getString("id");
        if (id.length() < 1 || id.length() > 64 || !id.matches("[A-Za-z0-9_.-]+")) {
            throw new IOException("Recipe ID must use 1–64 ASCII letters, digits, dot, underscore or dash");
        }
        String name = recipe.optString("name", id);
        if (name.trim().isEmpty() || name.length() > 80 ||
                hasControlCharacters(name)) {
            throw new IOException("Recipe name must be 1–80 printable characters");
        }
        String mainClass = recipe.getJSONObject("game").getString("mainClass");
        if (!mainClass.equals(gameEntry.replace('.', '/'))) {
            throw new IOException("Recipe targets a different MIDlet entry class");
        }
        // Reject malformed recipes before storing them, using the runner's data-only schema.
        MemoryRecipeRuntime.validateDocument(document);
        return recipe;
    }

    private static boolean hasControlCharacters(String name) {
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) return true;
        }
        return false;
    }

    private static JSONObject record(JSONObject recipe, String document) throws Exception {
        return new JSONObject().put("id", recipe.getString("id"))
                .put("name", recipe.optString("name", recipe.getString("id")))
                .put("document", document);
    }

    private static JSONObject read(AtomicFile file, String gameEntry) throws Exception {
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new JSONObject().put("schema", SCHEMA).put("items", new JSONArray());
        }
        byte[] bytes = file.readFully();
        if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Recipe catalog exceeds size limit");
        JSONObject result = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (result.getInt("schema") != SCHEMA) throw new IOException("Unsupported recipe catalog version");
        JSONArray items = result.getJSONArray("items");
        if (items.length() > MAX_RECIPES) throw new IOException("Recipe catalog has too many entries");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String document = item.getString("document");
            JSONObject recipe = checkRecipe(document, gameEntry);
            if (!item.getString("id").equals(recipe.getString("id")) ||
                    !item.getString("name").equals(
                            recipe.optString("name", recipe.getString("id"))) ||
                    !ids.add(item.getString("id"))) {
                throw new IOException("Recipe catalog contains inconsistent or duplicate entries");
            }
        }
        return result;
    }

    private static void write(AtomicFile file, JSONObject catalog) throws Exception {
        byte[] bytes = catalog.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Recipe catalog exceeds size limit");
        File parent = file.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Recipe config directory is unavailable");
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception error) {
            file.failWrite(stream);
            throw error;
        }
    }

    private static Bundle rows(JSONObject data) throws Exception {
        Bundle response = new Bundle();
        ArrayList<Bundle> entries = new ArrayList<>();
        JSONArray items = data.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            Bundle row = new Bundle();
            row.putString("id", item.getString("id"));
            row.putString("name", item.getString("name"));
            row.putString("document", item.getString("document"));
            entries.add(row);
        }
        response.putBoolean("ok", true);
        response.putParcelableArrayList("items", entries);
        return response;
    }
}
