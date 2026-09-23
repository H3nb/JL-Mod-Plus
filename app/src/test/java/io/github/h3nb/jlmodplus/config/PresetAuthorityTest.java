/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PresetAuthorityTest {
    private static final String STORAGE_KEY = "fixture";

    @Test
    public void initialUnknownIdentityAdoptsCurrentLibraryAppId() throws Exception {
        Fixture fixture = fixture(41L);

        PresetAuthority.PrepareResult result =
                fixture.authority.prepareRuntime(fixture.appPath, 0L);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(41L, result.appId);
    }

    @Test
    public void expectedIdentityMatchAllowsRuntimePrepare() throws Exception {
        Fixture fixture = fixture(42L);

        PresetAuthority.PrepareResult result =
                fixture.authority.prepareRuntime(fixture.appPath, 42L);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(42L, result.appId);
    }

    @Test
    public void expectedIdentityMismatchIsRejectedBeforeMutation() throws Exception {
        Fixture fixture = fixture(43L);
        byte[] oldLayout = validLayout(3);
        byte[] newLayout = validLayout(4);
        write(fixture.layoutFile(), oldLayout);
        new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i");

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 99L, newLayout, null);

        assertEquals(PresetAuthorityContract.RESULT_STALE, result.code);
        assertArrayEquals(oldLayout, Files.readAllBytes(fixture.layoutFile().toPath()));
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
    }

    @Test
    public void deletedInstalledIdentityIsRejected() throws Exception {
        Fixture fixture = fixture(44L);
        fixture.resolver.remove(fixture.appPath);

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 44L, validLayout(4), null);

        assertEquals(PresetAuthorityContract.RESULT_STALE, result.code);
        assertFalse(fixture.layoutFile().exists());
    }

    @Test
    public void recreatedStorageKeyWithNewAppIdRejectsOldRuntime() throws Exception {
        Fixture fixture = fixture(45L);
        fixture.resolver.put(
                fixture.appPath,
                new PresetAuthority.InstalledApp(fixture.root, STORAGE_KEY, 46L));

        PresetAuthority.PrepareResult result =
                fixture.authority.prepareRuntime(fixture.appPath, 45L);

        assertEquals(PresetAuthorityContract.RESULT_STALE, result.code);
        assertEquals(0L, result.appId);
    }

    @Test
    public void unrelatedWorkdirWithSameStorageKeyDoesNotSatisfyIdentity() throws Exception {
        Fixture first = fixture(47L);
        File otherRoot = Files.createTempDirectory("jlmod-authority-other").toFile();
        File otherConverted = new File(otherRoot, "converted");
        File otherApp = new File(otherConverted, STORAGE_KEY);
        assertTrue(otherApp.mkdirs());
        assertTrue(new File(otherRoot, "configs").mkdir());
        assertTrue(new File(otherRoot, "templates").mkdir());
        writeConfig(new File(new File(otherRoot, "configs"), STORAGE_KEY), 176);
        first.resolver.put(
                otherApp.getAbsolutePath(),
                new PresetAuthority.InstalledApp(otherRoot, STORAGE_KEY, 48L));

        PresetAuthority.PrepareResult result =
                first.authority.prepareRuntime(otherApp.getAbsolutePath(), 47L);

        assertEquals(PresetAuthorityContract.RESULT_STALE, result.code);
    }

    @Test
    public void runtimePrepareRefreshesLinkedFollowerThroughAuthority() throws Exception {
        Fixture fixture = fixture(49L);
        File source = fixture.preset("K800i", 360);
        writeConfig(fixture.configDir, 176);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));

        PresetAuthority.PrepareResult result =
                fixture.authority.prepareRuntime(fixture.appPath, 49L);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(360, readConfig(fixture.configDir).screenWidth);
        assertEquals(360, readConfig(source).screenWidth);
    }

    @Test
    public void runtimePrepareReturnsAuthoritativeBuiltInOwnership() throws Exception {
        Fixture fixture = fixture(50L);
        assertTrue(fixture.preferences.edit()
                .putBoolean(ProfileModel.builtInThemePreferenceKey(fixture.configDir), true)
                .commit());

        PresetAuthority.PrepareResult result =
                fixture.authority.prepareRuntime(fixture.appPath, 50L);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertTrue(result.builtInThemeLinked);
    }

    @Test
    public void localOnlySaveDetachesAndPublishesInOneAuthorityOperation() throws Exception {
        Fixture fixture = fixture(51L);
        fixture.preset("K800i", 176);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));
        byte[] payload = validLayout(4);

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 51L, payload, null);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(PresetAuthorityContract.UPDATE_NONE, result.updateOutcome);
        assertArrayEquals(payload, Files.readAllBytes(fixture.layoutFile().toPath()));
        PresetLinkage linkage = new PresetLinkage(fixture.preferences, fixture.configDir);
        assertFalse(linkage.isLinked());
        assertEquals("K800i", linkage.getOrigin());
    }

    @Test
    public void mainPreferenceViewImmediatelyObservesRuntimeDetach() throws Exception {
        Fixture fixture = fixture(52L);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 52L, validLayout(3), null);

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertFalse(fixture.preferences.contains(
                PresetLinkage.linkedPreferenceKey(fixture.configDir)));
        assertEquals("K800i", fixture.preferences.getString(
                PresetLinkage.originPreferenceKey(fixture.configDir), null));
    }

    @Test
    public void runtimeUpdatePublishesOnlyLayoutThenRelinksExistingFollower() throws Exception {
        Fixture fixture = fixture(53L);
        File source = fixture.preset("K800i", 176);
        byte[] sourceConfig = Files.readAllBytes(
                new File(source, Config.MIDLET_CONFIG_FILE).toPath());
        byte[] sourceLayout = validLayout(3);
        write(new File(source, Config.MIDLET_KEY_LAYOUT_FILE), sourceLayout);
        writeConfig(fixture.configDir, 640);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));
        byte[] runtimeLayout = validLayout(4);

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 53L, runtimeLayout, "K800i");

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(PresetAuthorityContract.UPDATE_LINKED, result.updateOutcome);
        assertArrayEquals(runtimeLayout, Files.readAllBytes(fixture.layoutFile().toPath()));
        assertArrayEquals(runtimeLayout, Files.readAllBytes(
                new File(source, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        assertArrayEquals(sourceConfig, Files.readAllBytes(
                new File(source, Config.MIDLET_CONFIG_FILE).toPath()));
        assertEquals(640, readConfig(fixture.configDir).screenWidth);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
    }

    @Test
    public void customProvenanceLayoutUpdateDoesNotLinkOrReplaceLocalSettingsOnNextLoad()
            throws Exception {
        Fixture fixture = fixture(59L);
        File source = fixture.preset("K800i", 176);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir)
                .setOrigin("K800i"));
        byte[] sourceConfig = Files.readAllBytes(
                new File(source, Config.MIDLET_CONFIG_FILE).toPath());
        byte[] localConfig = Files.readAllBytes(
                new File(fixture.configDir, Config.MIDLET_CONFIG_FILE).toPath());
        byte[] layout = validLayout(5);

        PresetAuthority.SaveResult result = fixture.authority.saveVirtualKeyboardLayout(
                fixture.appPath, 59L, layout, "K800i");

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(PresetAuthorityContract.UPDATE_SAVED_UNLINKED, result.updateOutcome);
        assertArrayEquals(sourceConfig, Files.readAllBytes(
                new File(source, Config.MIDLET_CONFIG_FILE).toPath()));
        assertArrayEquals(localConfig, Files.readAllBytes(
                new File(fixture.configDir, Config.MIDLET_CONFIG_FILE).toPath()));
        assertArrayEquals(layout, Files.readAllBytes(
                new File(source, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        assertFalse(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
        assertTrue(MidletConfigLoadBoundary.prepare(
                fixture.preferences, fixture.configDir, fixture.profilesRoot));
        assertArrayEquals(localConfig, Files.readAllBytes(
                new File(fixture.configDir, Config.MIDLET_CONFIG_FILE).toPath()));
    }

    @Test
    public void staleRequestedUpdateTargetNeverUpdatesWrongSource() throws Exception {
        Fixture fixture = fixture(54L);
        File stale = fixture.preset("K800i", 176);
        File current = fixture.preset("Sony K800i", 240);
        byte[] staleBytes = validLayout(3);
        byte[] currentBytes = validLayout(4);
        write(new File(stale, Config.MIDLET_KEY_LAYOUT_FILE), staleBytes);
        write(new File(current, Config.MIDLET_KEY_LAYOUT_FILE), currentBytes);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir)
                .linkTo("Sony K800i"));
        byte[] local = validLayout(5);

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 54L, local, "K800i");

        assertEquals(PresetAuthorityContract.RESULT_OK, result.code);
        assertEquals(PresetAuthorityContract.UPDATE_FAILED, result.updateOutcome);
        assertArrayEquals(local, Files.readAllBytes(fixture.layoutFile().toPath()));
        assertArrayEquals(staleBytes, Files.readAllBytes(
                new File(stale, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        assertArrayEquals(currentBytes, Files.readAllBytes(
                new File(current, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        PresetLinkage linkage = new PresetLinkage(fixture.preferences, fixture.configDir);
        assertFalse(linkage.isLinked());
        assertEquals("Sony K800i", linkage.getOrigin());
    }

    @Test
    public void identityFailureLeavesLocalOwnershipAndSourceUntouched() throws Exception {
        Fixture fixture = fixture(55L);
        File source = fixture.preset("K800i", 176);
        byte[] sourceBytes = validLayout(3);
        byte[] localBytes = validLayout(4);
        write(new File(source, Config.MIDLET_KEY_LAYOUT_FILE), sourceBytes);
        write(fixture.layoutFile(), localBytes);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 999L, validLayout(5), "K800i");

        assertEquals(PresetAuthorityContract.RESULT_STALE, result.code);
        assertArrayEquals(localBytes, Files.readAllBytes(fixture.layoutFile().toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(
                new File(source, Config.MIDLET_KEY_LAYOUT_FILE).toPath()));
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
    }

    @Test
    public void malformedRuntimeLayoutIsRejectedBeforeOwnershipMutation() throws Exception {
        Fixture fixture = fixture(56L);
        byte[] oldLayout = validLayout(3);
        write(fixture.layoutFile(), oldLayout);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 56L, new byte[]{1, 2, 3}, null);

        assertEquals(PresetAuthorityContract.RESULT_INVALID, result.code);
        assertArrayEquals(oldLayout, Files.readAllBytes(fixture.layoutFile().toPath()));
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
    }

    @Test
    public void unrecoverableLocalTransactionDoesNotRelinkAfterPublicationFailure()
            throws Exception {
        Fixture fixture = fixture(57L);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));
        File invalidRollback = new File(fixture.configDir, ".preset-sync.rollback");
        write(invalidRollback, new byte[]{9});

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 57L, validLayout(4), null);

        assertEquals(PresetAuthorityContract.RESULT_FAILED, result.code);
        PresetLinkage linkage = new PresetLinkage(fixture.preferences, fixture.configDir);
        assertFalse(linkage.isLinked());
        assertEquals("K800i", linkage.getOrigin());
        assertTrue(invalidRollback.isFile());
    }

    @Test
    public void oversizedRuntimeLayoutIsRejectedBeforeOwnershipMutation() throws Exception {
        Fixture fixture = fixture(58L);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).linkTo("K800i"));
        byte[] oversized = new byte[PresetAuthorityContract.MAX_LAYOUT_PAYLOAD_BYTES + 1];

        PresetAuthority.SaveResult result =
                fixture.authority.saveVirtualKeyboardLayout(
                        fixture.appPath, 58L, oversized, null);

        assertEquals(PresetAuthorityContract.RESULT_INVALID, result.code);
        assertTrue(new PresetLinkage(fixture.preferences, fixture.configDir).isLinked());
        assertFalse(fixture.layoutFile().exists());
    }

    @Test
    public void installedPathDerivesCanonicalWorkdirAndStorageKey() throws Exception {
        File root = Files.createTempDirectory("jlmod-authority-path").toFile();
        File app = new File(new File(root, "converted"), "demo");
        assertTrue(app.mkdirs());

        PresetAuthority.InstalledPath plain =
                PresetAuthority.InstalledPath.parse(app.getAbsolutePath());
        PresetAuthority.InstalledPath fileUri =
                PresetAuthority.InstalledPath.parse(app.toURI().toString());

        assertEquals(root.getCanonicalFile(), plain.workDir);
        assertEquals("demo", plain.storageKey);
        assertEquals(root.getCanonicalFile(), fileUri.workDir);
        assertEquals("demo", fileUri.storageKey);
        assertNull(PresetAuthority.InstalledPath.parse(
                new File(root, "not-converted/demo").getAbsolutePath()));
        assertNull(PresetAuthority.InstalledPath.parse("content://provider/demo"));
    }

    private static Fixture fixture(long appId) throws Exception {
        File root = Files.createTempDirectory("jlmod-authority").toFile();
        File appDir = new File(new File(root, "converted"), STORAGE_KEY);
        File configDir = new File(new File(root, "configs"), STORAGE_KEY);
        File templates = new File(root, "templates");
        assertTrue(appDir.mkdirs());
        assertTrue(configDir.mkdirs());
        assertTrue(templates.mkdir());
        writeConfig(configDir, 240);
        String appPath = appDir.getAbsolutePath();
        FakePreferences preferences = new FakePreferences();
        FakeResolver resolver = new FakeResolver();
        resolver.put(appPath, new PresetAuthority.InstalledApp(root, STORAGE_KEY, appId));
        return new Fixture(root, appDir, configDir, templates, appPath, preferences, resolver);
    }

    private static void writeConfig(File dir, int width) throws Exception {
        if (!dir.isDirectory()) assertTrue(dir.mkdirs());
        ProfileModel profile = new ProfileModel();
        profile.dir = dir;
        profile.version = ProfileModel.VERSION;
        profile.screenWidth = width;
        profile.screenHeight = 320;
        profile.vkType = 3;
        profile.systemProperties = "";
        assertTrue(ProfilesManager.saveConfig(profile));
    }

    private static ProfileModel readConfig(File dir) {
        return ProfilesManager.loadPreparedMidletConfig(dir, false);
    }

    private static byte[] validLayout(int type) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
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

    private static void write(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.write(file.toPath(), bytes);
    }

    private static final class Fixture {
        final File root;
        final File appDir;
        final File configDir;
        final File profilesRoot;
        final String appPath;
        final FakePreferences preferences;
        final FakeResolver resolver;
        final PresetAuthority authority;

        Fixture(File root, File appDir, File configDir, File profilesRoot, String appPath,
                FakePreferences preferences, FakeResolver resolver) {
            this.root = root;
            this.appDir = appDir;
            this.configDir = configDir;
            this.profilesRoot = profilesRoot;
            this.appPath = appPath;
            this.preferences = preferences;
            this.resolver = resolver;
            this.authority = new PresetAuthority(preferences, resolver);
        }

        File layoutFile() {
            return new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
        }

        File preset(String name, int width) throws Exception {
            File source = new File(profilesRoot, name);
            assertTrue(source.mkdir());
            writeConfig(source, width);
            return source;
        }
    }

    private static final class FakeResolver implements PresetAuthority.IdentityResolver {
        private final Map<String, PresetAuthority.InstalledApp> apps = new HashMap<>();

        void put(String appPath, PresetAuthority.InstalledApp app) {
            apps.put(appPath, app);
        }

        void remove(String appPath) {
            apps.remove(appPath);
        }

        @Override
        public PresetAuthority.InstalledApp resolve(String appPath) {
            return apps.get(appPath);
        }
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }
        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }
        @Override @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }
        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> updates = new HashMap<>();
                private final Set<String> removals = new HashSet<>();
                private boolean clear;

                @Override public Editor putString(String key, String value) {
                    updates.put(key, value); removals.remove(key); return this;
                }
                @Override public Editor putStringSet(String key, Set<String> value) {
                    updates.put(key, value == null ? null : new HashSet<>(value));
                    removals.remove(key); return this;
                }
                @Override public Editor putInt(String key, int value) {
                    updates.put(key, value); removals.remove(key); return this;
                }
                @Override public Editor putLong(String key, long value) {
                    updates.put(key, value); removals.remove(key); return this;
                }
                @Override public Editor putFloat(String key, float value) {
                    updates.put(key, value); removals.remove(key); return this;
                }
                @Override public Editor putBoolean(String key, boolean value) {
                    updates.put(key, value); removals.remove(key); return this;
                }
                @Override public Editor remove(String key) {
                    removals.add(key); updates.remove(key); return this;
                }
                @Override public Editor clear() {
                    clear = true; return this;
                }
                @Override public boolean commit() {
                    apply(); return true;
                }
                @Override public void apply() {
                    if (clear) values.clear();
                    for (String key : removals) values.remove(key);
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        if (entry.getValue() == null) values.remove(entry.getKey());
                        else values.put(entry.getKey(), entry.getValue());
                    }
                }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
    }
}
