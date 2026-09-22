/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ProfilesManagerPreparedRuntimeTest {
    private static final Gson GSON = new Gson();

    @Test
    public void preparedRuntimeLoadDoesNotRecoverAtomicSidecars() throws Exception {
        File dir = Files.createTempDirectory("jlmod-prepared-load").toFile();
        File config = new File(dir, Config.MIDLET_CONFIG_FILE);
        File next = new File(config.getPath() + ".new");
        File backup = new File(config.getPath() + ".bak");
        byte[] committed = configBytes(240, ProfileModel.VERSION);
        byte[] staged = configBytes(360, ProfileModel.VERSION);
        byte[] old = configBytes(176, ProfileModel.VERSION);
        Files.write(config.toPath(), committed);
        Files.write(next.toPath(), staged);
        Files.write(backup.toPath(), old);

        ProfileModel loaded = ProfilesManager.loadPreparedMidletConfig(dir, false);

        assertEquals(240, loaded.screenWidth);
        assertArrayEquals(committed, Files.readAllBytes(config.toPath()));
        assertArrayEquals(staged, Files.readAllBytes(next.toPath()));
        assertArrayEquals(old, Files.readAllBytes(backup.toPath()));
    }

    @Test
    public void preparedRuntimeLoadNormalizesMigrationOnlyInMemory() throws Exception {
        File dir = Files.createTempDirectory("jlmod-prepared-migration").toFile();
        File config = new File(dir, Config.MIDLET_CONFIG_FILE);
        byte[] legacy = configBytes(240, 1);
        Files.write(config.toPath(), legacy);

        ProfileModel loaded = ProfilesManager.loadPreparedMidletConfig(dir, false);

        assertEquals(ProfileModel.VERSION, loaded.version);
        assertArrayEquals(legacy, Files.readAllBytes(config.toPath()));
    }

    @Test
    public void runtimeLayoutPublicationUsesUnifiedLocalTransactionAndPreservesConfig()
            throws Exception {
        File dir = Files.createTempDirectory("jlmod-runtime-layout").toFile();
        File config = new File(dir, Config.MIDLET_CONFIG_FILE);
        byte[] configBytes = configBytes(240, ProfileModel.VERSION);
        Files.write(config.toPath(), configBytes);
        byte[] layout = validLayout(4);

        ProfilesManager.publishRuntimeLayout(dir, layout);

        assertArrayEquals(configBytes, Files.readAllBytes(config.toPath()));
        assertArrayEquals(layout, Files.readAllBytes(
                new File(dir, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        assertTrue(!new File(dir, ".preset-sync.rollback").exists());
        assertTrue(!new File(dir, ".preset-sync.tmp").exists());
    }

    @Test
    public void invalidRuntimeLayoutLeavesCommittedDestinationUnchanged() throws Exception {
        File dir = Files.createTempDirectory("jlmod-runtime-layout-invalid").toFile();
        File layoutFile = new File(dir, Config.MIDLET_KEY_LAYOUT_FILE);
        byte[] oldLayout = validLayout(3);
        Files.write(layoutFile.toPath(), oldLayout);

        try {
            ProfilesManager.publishRuntimeLayout(dir, new byte[]{1, 2, 3});
            throw new AssertionError("Expected invalid runtime layout rejection");
        } catch (java.io.IOException expected) {
            // Expected.
        }

        assertArrayEquals(oldLayout, Files.readAllBytes(layoutFile.toPath()));
    }

    private static byte[] configBytes(int width, int version) {
        ProfileModel profile = new ProfileModel();
        profile.version = version;
        profile.screenWidth = width;
        profile.screenHeight = 320;
        profile.vkType = 3;
        profile.systemProperties = "";
        return GSON.toJson(profile).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] validLayout(int type) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
            output.writeInt(0x564B4C00);
            output.writeInt(4);
            output.writeInt(3);
            output.writeInt(1);
            output.writeByte(type);
            output.writeInt(-1);
            output.writeInt(0);
        }
        return bytes.toByteArray();
    }
}
