/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.*;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.shell.MemoryDiscoveryBridge;

@RunWith(AndroidJUnit4.class)
public class MemoryRecipeInstrumentedTest {
    private static final long TOKEN = 0x4d524543495045L;
    private static final String ROOT = MemoryRecipeInstrumentedTest.Root.class.getName().replace('.', '/');
    private static final String INVENTORY = Inventory.class.getName().replace('.', '/');
    private static final String HERO = Hero.class.getName().replace('.', '/');
    private Root root;
    private MemoryRecipeRuntime runner;

    public static final class Root {
        public Inventory inventory = new Inventory();
        public Hero[] party = {new Hero(), new Hero()};
        public int coins = 10;
        public byte[] encoded = new byte[4];
    }
    public static final class Hero {
        public short hp = 100;
        public short mp = 50;
    }
    public static final class 角色 {
        public static int 生命 = 100;
    }
    public static final class Inventory {
        public short[] slots = new short[21];
        Inventory() { slots[0] = (short) ((3 << 8) | 0x85); }
    }

    @Before public void setup() {
        root = new Root();
        runner = new MemoryRecipeRuntime();
        MemoryDiscoveryBridge.install(TOKEN, Root.class.getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
    }

    @After public void teardown() {
        runner.runtimeClosed(TOKEN);
        MemoryDiscoveryBridge.close(TOKEN);
    }

    private static String field(String owner, String name, String descriptor) {
        return "{\"kind\":\"field\",\"owner\":\"" + owner + "\",\"name\":\"" + name
                + "\",\"descriptor\":\"" + descriptor + "\"}";
    }

    private static String recipe(String actions) {
        return "{\"formatVersion\":1,\"id\":\"test.memory\","
            + "\"game\":{\"mainClass\":\"" + ROOT + "\"},"
            + "\"inputs\":{\"slot\":{\"min\":0,\"max\":20},"
            + "\"quantity\":{\"min\":1,\"max\":99},"
            + "\"coins\":{\"min\":0,\"max\":999999}},"
            + "\"root\":{\"kind\":\"midlet\",\"class\":\"" + ROOT + "\"},"
            + "\"actions\":[" + actions + "]}";
    }

    private static String inventoryPath() {
        return field(ROOT, "inventory", "L" + INVENTORY + ";") + ","
                + field(INVENTORY, "slots", "[S") + ","
                + "{\"kind\":\"index\",\"descriptor\":\"[S\",\"indexInput\":\"slot\"}";
    }

    private static String bitfieldAction() {
        return "{\"kind\":\"bitfield\",\"path\":[" + inventoryPath() + "],"
            + "\"shift\":8,\"width\":8,\"arrayLength\":21,"
            + "\"nonZeroMask\":\"0x00FF\",\"identityMask\":\"0x00FF\",\"valueInput\":\"quantity\"}";
    }

    private static Bundle inputs(int slot, int quantity, int coins) {
        Bundle bundle = new Bundle();
        bundle.putString("slot", Integer.toString(slot));
        bundle.putString("quantity", Integer.toString(quantity));
        bundle.putString("coins", Integer.toString(coins));
        return bundle;
    }

    private static int status(Bundle result) {
        return result.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT);
    }

    @Test public void selectedCharacterFieldsResolveThroughObjectArrayAndFreezeDoesNotRetarget() {
        String arrayDescriptor = "[L" + HERO + ";";
        String route = field(ROOT, "party", arrayDescriptor) + ","
                + "{\"kind\":\"index\",\"descriptor\":\"" + arrayDescriptor
                + "\",\"indexInput\":\"slot\"},";
        String hp = "{\"kind\":\"set\",\"path\":[" + route + field(HERO, "hp", "S")
                + "],\"value\":999}";
        String mp = "{\"kind\":\"set\",\"path\":[" + route + field(HERO, "mp", "S")
                + "],\"value\":333}";
        String document = recipe(hp + "," + mp);
        assertEquals(MemoryEngineContract.RESULT_OK,
                status(runner.run(TOKEN, document, inputs(1, 1, 0), true, false)));
        assertEquals(100, root.party[0].hp);
        assertEquals(50, root.party[0].mp);
        assertEquals(999, root.party[1].hp);
        assertEquals(333, root.party[1].mp);

        root.party[1] = new Hero();
        assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, status(runner.tick(TOKEN)));
        assertEquals(0, runner.activeCount(TOKEN));
        assertEquals(100, root.party[1].hp);
        assertEquals(50, root.party[1].mp);
    }

    @Test public void unicodeGuestIdentifiersRemainValidRecipeTargets() {
        角色.生命 = 100;
        MemoryDiscoveryBridge.tailSeen(角色.class);
        String owner = 角色.class.getName().replace('.', '/');
        String rootJson = "{\"kind\":\"initializedStatic\",\"class\":\"" + owner + "\"}";
        String action = "{\"kind\":\"set\",\"path\":[" + field(owner, "生命", "I")
                + "],\"value\":777}";
        String document = recipe(action).replace(
                "\"root\":{\"kind\":\"midlet\",\"class\":\"" + ROOT + "\"}",
                "\"root\":" + rootJson);
        assertEquals(MemoryEngineContract.RESULT_OK,
                status(runner.run(TOKEN, document, inputs(0, 1, 1), false, false)));
        assertEquals(777, 角色.生命);
    }

    @Test public void perGameCatalogPersistsWithoutExecutingAndRejectsDuplicateIds() throws Exception {
        java.io.File folder = new java.io.File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(), "recipe-catalog-" + System.nanoTime());
        assertTrue(folder.mkdirs());
        android.util.AtomicFile file = new android.util.AtomicFile(
                new java.io.File(folder, "memory-recipes.json"));
        try {
            String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT, "coins", "I")
                    + "],\"value\":444}";
            String document = recipe(action);
            assertEquals(0, MemoryRecipeCatalog.list(file, Root.class.getName())
                    .getParcelableArrayList("items").size());
            Bundle saved = MemoryRecipeCatalog.put(file, Root.class.getName(), document, null);
            assertEquals(1, saved.getParcelableArrayList("items").size());
            assertEquals(10, root.coins); // Persisting must not execute any guest writes.
            try {
                MemoryRecipeCatalog.put(file, Root.class.getName(), document, null);
                fail("Duplicate ID must be rejected without overwriting saved content");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("already exists"));
            }
            Bundle loaded = MemoryRecipeCatalog.list(file, Root.class.getName());
            Bundle entry = (Bundle) loaded.getParcelableArrayList("items").get(0);
            assertEquals("test.memory", entry.getString("id"));
            assertEquals(document, entry.getString("document"));
            String updated = document.replace("\"value\":444", "\"value\":555");
            MemoryRecipeCatalog.put(file, Root.class.getName(), updated, "test.memory");
            assertEquals(updated, ((Bundle) MemoryRecipeCatalog.list(file, Root.class.getName())
                    .getParcelableArrayList("items").get(0)).getString("document"));
            MemoryRecipeCatalog.delete(file, Root.class.getName(), "test.memory");
            assertEquals(0, MemoryRecipeCatalog.list(file, Root.class.getName())
                    .getParcelableArrayList("items").size());
        } finally {
            file.delete();
            folder.delete();
        }
    }

    @Test public void catalogRejectsIncompleteRecipeBeforeItCanBeSaved() throws Exception {
        java.io.File folder = new java.io.File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(), "recipe-schema-" + System.nanoTime());
        assertTrue(folder.mkdirs());
        android.util.AtomicFile file = new android.util.AtomicFile(
                new java.io.File(folder, "memory-recipes.json"));
        try {
            try {
                MemoryRecipeCatalog.put(file, Root.class.getName(), recipe("{}"), null);
                fail("An action without a kind or typed path must not be persisted");
            } catch (RuntimeException expected) {
                assertTrue(expected.getMessage().contains("Missing"));
            }
            assertEquals(0, MemoryRecipeCatalog.list(file, Root.class.getName())
                    .getParcelableArrayList("items").size());
            assertEquals(10, root.coins);
        } finally {
            file.delete();
            folder.delete();
        }
    }

    @Test public void malformedStoredCatalogCannotBeSilentlyReplaced() throws Exception {
        java.io.File folder = new java.io.File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(), "recipe-corrupt-" + System.nanoTime());
        assertTrue(folder.mkdirs());
        android.util.AtomicFile file = new android.util.AtomicFile(
                new java.io.File(folder, "memory-recipes.json"));
        try {
            byte[] invalid = "{\"schema\":900,\"items\":[]}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            java.io.FileOutputStream output = file.startWrite();
            output.write(invalid);
            file.finishWrite(output);
            String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT, "coins", "I")
                    + "],\"value\":50}";
            try {
                MemoryRecipeCatalog.put(file, Root.class.getName(), recipe(action), null);
                fail("Unsupported catalog versions may never be overwritten");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("version"));
            }
            assertArrayEquals(invalid, file.readFully());
            assertEquals(10, root.coins);
        } finally {
            file.delete();
            folder.delete();
        }
    }

    @Test public void recipeFreezeTicksWithoutSearchOrOrdinaryWatchSession() throws Exception {
        MemoryTargetBridgeService target = new MemoryTargetBridgeService();
        IMemoryTargetBridge bridge = IMemoryTargetBridge.Stub.asInterface(
                target.onBind(new android.content.Intent()));
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT, "coins", "I")
                + "],\"valueInput\":\"coins\"}";
        Bundle start = bridge.runMemoryRecipe(TOKEN, recipe(action), inputs(0, 1, 800),
                true, false);
        assertEquals(MemoryEngineContract.RESULT_OK, status(start));
        assertEquals(800, root.coins);
        root.coins = 50;
        Bundle tick = bridge.managedFreezeTick(TOKEN, 0L);
        assertEquals(800, root.coins);
        assertEquals(MemoryEngineContract.RESULT_OK, status(tick));
        assertEquals(1, tick.getInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT));
        root.coins = 70;
        bridge.managedFreezeTick(TOKEN, 0L);
        assertEquals(800, root.coins);
    }

    @Test public void readReportsLogicalBitfieldAndEncodedValuesNotRawBytes() {
        String bitfield = recipe(bitfieldAction());
        Bundle qty = runner.run(TOKEN, bitfield, inputs(0, 1, 1), false, true);
        assertEquals(MemoryEngineContract.RESULT_OK, status(qty));
        assertArrayEquals(new String[]{"quantity"}, qty.getStringArray("recipeReadInputs"));
        assertArrayEquals(new String[]{"3"}, qty.getStringArray("recipeReadValues"));
        Bundle indexOnly = new Bundle();
        indexOnly.putString("slot", "0");
        assertEquals(MemoryEngineContract.RESULT_OK,
                status(runner.run(TOKEN, bitfield, indexOnly, false, true)));
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
                status(runner.run(TOKEN, bitfield, indexOnly, false, false)));
        assertEquals(3, (root.inventory.slots[0] >>> 8) & 255);
        String encoded = "{\"kind\":\"encoded32le\",\"path\":["
                + field(ROOT, "encoded", "[B")
                + ",{\"kind\":\"slice\",\"descriptor\":\"[B\",\"start\":0,\"length\":4}],"
                + "\"valueInput\":\"coins\",\"constant\":\"58279306\"}";
        int raw = 58279306 - 7582;
        for (int i = 0; i < 4; i++) root.encoded[i] = (byte) (raw >>> (8 * i));
        Bundle money = runner.run(TOKEN, recipe(encoded), inputs(0, 1, 9000),
                false, true);
        assertEquals(MemoryEngineContract.RESULT_OK, status(money));
        assertArrayEquals(new String[]{"7582"}, money.getStringArray("recipeReadValues"));
        assertArrayEquals(new String[]{"7582"},
                runner.run(TOKEN, recipe(encoded), new Bundle(), false, true)
                        .getStringArray("recipeReadValues"));
        assertEquals(10, root.coins);
    }

    @Test public void packedShortEditPreservesItemCategoryAndId() {
        Bundle params = inputs(0, 50, 999);
        String json = recipe(bitfieldAction());
        assertEquals(MemoryEngineContract.RESULT_OK, status(runner.run(TOKEN,json,params,false,true)));
        assertEquals(3, (root.inventory.slots[0] >>> 8) & 255);
        assertEquals(MemoryEngineContract.RESULT_OK, status(runner.run(TOKEN,json,params,false,false)));
        assertEquals(50, (root.inventory.slots[0] >>> 8) & 255);
        assertEquals(0x85, root.inventory.slots[0] & 0xFF);
    }

    @Test public void packedItemFreezeRestoresZeroQuantityWithoutChangingItemId() {
        String json = recipe(bitfieldAction());
        assertEquals(MemoryEngineContract.RESULT_OK,
                status(runner.run(TOKEN, json, inputs(0, 50, 1), true, false)));
        root.inventory.slots[0] = (short) 0x0085;
        assertEquals(MemoryEngineContract.RESULT_OK, status(runner.tick(TOKEN)));
        assertEquals(50, (root.inventory.slots[0] >>> 8) & 255);
        assertEquals(0x85, root.inventory.slots[0] & 255);
        root.inventory.slots[0] = (short) 0x0086;
        assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, status(runner.tick(TOKEN)));
        assertEquals(0, runner.activeCount(TOKEN));
        assertEquals(0x0086, root.inventory.slots[0] & 0xFFFF);
    }

    @Test public void emptySlotRejectedBeforeAnyMutation() {
        Bundle result = runner.run(TOKEN,recipe(bitfieldAction()),inputs(1,99,999),false,false);
        assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,status(result));
        assertEquals(0,root.inventory.slots[1]);
    }

    @Test public void simpleGeneratorUsesExactSavedFieldAndArrayPath() {
        Bundle descriptor = new Bundle();
        descriptor.putInt("savedSchema", 2);
        descriptor.putInt("savedRootKind", 1);
        descriptor.putString("savedRootClass", Root.class.getName());
        java.util.ArrayList<Bundle> steps = new java.util.ArrayList<>();
        Bundle inventory = new Bundle();
        inventory.putInt("kind", 1);
        inventory.putString("owner", Root.class.getName());
        inventory.putString("name", "inventory");
        inventory.putString("type", Inventory.class.getName());
        steps.add(inventory);
        Bundle array = new Bundle();
        array.putInt("kind", 1);
        array.putString("owner", Inventory.class.getName());
        array.putString("name", "slots");
        array.putString("type", "[S");
        steps.add(array);
        Bundle element = new Bundle();
        element.putInt("kind", 2);
        element.putInt("index", 0);
        element.putString("arrayType", "[S");
        steps.add(element);
        descriptor.putParcelableArrayList("savedSteps", steps);
        String generated = MemoryRecipeGenerator.INSTANCE.fromSaved(
            descriptor, Root.class.getName(), "inventory-slot", "1500");
        assertEquals(MemoryEngineContract.RESULT_OK,
            status(runner.run(TOKEN, generated, new Bundle(), false, false)));
        assertEquals(1500, root.inventory.slots[0]);
    }

    @Test public void shortBitfieldCannotSpillIntoOtherBits() {
        String tooWide = bitfieldAction().replace("\"shift\":8", "\"shift\":12");
        Bundle result = runner.run(TOKEN, recipe(tooWide), inputs(0, 50, 10), false, false);
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, status(result));
        assertEquals((short) 0x0385, root.inventory.slots[0]);
    }

    @Test public void compoundRejectsOutOfRangePrimitiveBeforeFirstWrite() {
        String first = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
                + "\"valueInput\":\"coins\"}";
        String second = "{\"kind\":\"set\",\"path\":["
                + field(ROOT,"encoded","[B")
                + ",{\"kind\":\"index\",\"descriptor\":\"[B\",\"index\":0}],"
                + "\"valueInput\":\"coins\"}";
        Bundle result = runner.run(TOKEN, recipe(first + "," + second),
                inputs(0, 50, 700), false, false);
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, status(result));
        assertEquals(10, root.coins);
    }

    @Test public void freezeLimitRejectsFifthRecipeBeforeMutation() {
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
                + "\"valueInput\":\"coins\"}";
        for (int i = 0; i < 4; i++) {
            String item = recipe(action).replace("test.memory", "test.memory." + i);
            assertEquals(MemoryEngineContract.RESULT_OK,
                status(runner.run(TOKEN, item, inputs(0, 50, 700), true, false)));
        }
        assertEquals(4, runner.activeCount(TOKEN));
        root.coins = 11;
        Bundle denied = runner.run(TOKEN,
                recipe(action).replace("test.memory", "test.memory.5"),
                inputs(0, 50, 900), true, false);
        assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT, status(denied));
        assertEquals(11, root.coins);
    }

    @Test public void allTargetsPreflightBeforeCompoundWrite() {
        String setCoin = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
                + "\"valueInput\":\"coins\"}";
        String invalid = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"doesNotExist","I") + "],"
                + "\"valueInput\":\"coins\"}";
        Bundle result = runner.run(TOKEN,recipe(setCoin + "," + invalid),inputs(0,50,999),false,false);
        assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,status(result));
        assertEquals(10,root.coins);
    }

    @Test public void compoundFreezeRewritesAndStopsOnReplacement() {
        String setCoin = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
                + "\"valueInput\":\"coins\"}";
        String encoded = "{\"kind\":\"encoded32le\",\"path\":[" + field(ROOT,"encoded","[B")
                + ",{\"kind\":\"slice\",\"descriptor\":\"[B\",\"start\":0,\"length\":4}],"
                + "\"valueInput\":\"coins\",\"constant\":\"58279306\"}";
        String json = recipe(encoded + "," + setCoin);
        assertEquals(MemoryEngineContract.RESULT_OK,status(runner.run(TOKEN,json,inputs(0,1,700),true,false)));
        assertEquals(1,runner.activeCount(TOKEN));
        assertArrayEquals(new String[]{"test.memory"}, runner.activeIds(TOKEN));
        assertArrayEquals(new String[]{"test.memory"}, runner.activeNames(TOKEN));
        root.coins = 1;
        root.encoded[0] = 0;
        assertEquals(MemoryEngineContract.RESULT_OK,status(runner.tick(TOKEN)));
        assertEquals(700,root.coins);
        assertEquals((58279306 - 700) & 0xff,root.encoded[0] & 0xff);
        root.encoded = new byte[4];
        assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, status(runner.tick(TOKEN)));
        assertEquals(0,runner.activeCount(TOKEN));
        assertArrayEquals(new String[0], runner.activeIds(TOKEN));
        assertEquals(0,root.encoded[0]);
    }


    public static final class CurrencyStore {
        public static int[] aW = new int[] { 10 };
        public static byte[] h = new byte[4];
    }

    @Test public void initializedStaticRootSupportsEncodedCoins() {
        CurrencyStore.aW = new int[] { 10 };
        CurrencyStore.h = new byte[4];
        MemoryDiscoveryBridge.tailSeen(CurrencyStore.class);
        String currency = CurrencyStore.class.getName().replace('.', '/');
        String staticRoot = "{\"kind\":\"initializedStatic\",\"class\":\"" + currency + "\"}";
        String byteAction = "{\"kind\":\"encoded32le\",\"path\":["
            + field(currency, "h", "[B")
            + ",{\"kind\":\"slice\",\"descriptor\":\"[B\",\"start\":0,\"length\":4}],"
            + "\"constant\":\"58279306\",\"valueInput\":\"coins\"}";
        String moneyAction = "{\"kind\":\"set\",\"path\":["
            + field(currency,"aW","[I") + ",{\"kind\":\"index\",\"descriptor\":\"[I\",\"index\":0}],"
            + "\"valueInput\":\"coins\"}";
        String json = recipe(byteAction + "," + moneyAction)
            .replace("\"root\":{\"kind\":\"midlet\",\"class\":\"" + ROOT + "\"}",
                    "\"root\":" + staticRoot);
        assertEquals(MemoryEngineContract.RESULT_OK,
            status(runner.run(TOKEN,json,inputs(0,1,999999),false,false)));
        assertEquals(999999,CurrencyStore.aW[0]);
        int expected = 58279306 - 999999;
        for (int i = 0; i < 4; i++) {
            assertEquals((expected >>> (i * 8)) & 255,CurrencyStore.h[i] & 255);
        }
    }

    @Test public void wronglyTypedOptionalInputsCannotBeIgnored() {
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT, "coins", "I")
                + "],\"value\":101}";
        String document = recipe(action);
        int start = document.indexOf("\"inputs\":");
        int end = document.indexOf(",\"root\":", start);
        assertTrue(start >= 0 && end > start);
        document = document.substring(0, start) + "\"inputs\":false" + document.substring(end);
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
                status(runner.run(TOKEN, document, new Bundle(), false, false)));
        assertEquals(10, root.coins);
    }

    @Test public void unknownRecipePropertyRejectsBeforeMutation() {
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
                + "\"valueInput\":\"coins\",\"unexpected\":true}";
        Bundle result = runner.run(TOKEN, recipe(action), inputs(0, 50, 700), false, false);
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, status(result));
        assertEquals(10, root.coins);
    }

    @Test public void globalFreezeBudgetRejectsNewHoldBeforeAnyWrite() {
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT, "coins", "I") + "],"
                + "\"valueInput\":\"coins\"}";
        Bundle rejected = runner.run(TOKEN, recipe(action), inputs(0, 1, 700),
                true, false, MemoryEngineContract.MAX_FREEZE_RECORDS);
        assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT, status(rejected));
        assertEquals(10, root.coins);
    }

    @Test public void userCanStopRecipeFreezeWithoutReimportingRecipe() {
        String action = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","I") + "],"
            + "\"valueInput\":\"coins\"}";
        String json = recipe(action).replace("\"id\":\"test.memory\"",
            "\"id\":\"test.money\",\"name\":\"Coins\"");
        assertEquals(MemoryEngineContract.RESULT_OK,
            status(runner.run(TOKEN, json, inputs(0, 1, 500), true, false)));
        assertArrayEquals(new String[]{"test.money"}, runner.activeIds(TOKEN));
        assertArrayEquals(new String[]{"Coins"}, runner.activeNames(TOKEN));
        assertEquals(MemoryEngineContract.RESULT_OK, status(runner.clear(TOKEN, "test.money")));
        root.coins = 1;
        assertEquals(MemoryEngineContract.RESULT_OK, status(runner.tick(TOKEN)));
        assertEquals(1, root.coins);
        assertArrayEquals(new String[0], runner.activeIds(TOKEN));
    }

    @Test public void rejectsWrongDescriptorAndRuntimeGeneration() {
        String wrong = "{\"kind\":\"set\",\"path\":[" + field(ROOT,"coins","S") + "],"
            + "\"valueInput\":\"coins\"}";
        assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
            status(runner.run(TOKEN,recipe(wrong),inputs(0,50,99),false,false)));
        assertEquals(MemoryEngineContract.RESULT_TARGET_LOST,
            status(runner.run(TOKEN + 1,recipe(bitfieldAction()),inputs(0,50,99),false,false)));
    }
}
