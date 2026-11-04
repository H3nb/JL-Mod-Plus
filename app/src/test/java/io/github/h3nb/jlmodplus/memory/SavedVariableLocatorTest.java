package io.github.h3nb.jlmodplus.memory;

import org.junit.jupiter.api.Test;
import javax.microedition.shell.MemoryDiscoveryBridge;
import static org.junit.jupiter.api.Assertions.*;

/** Focused guest-runtime Saved locator regression tests. */
public class SavedVariableLocatorTest {
    private static final long FIRST = 1000021L, NEXT = 1000022L;
    static final class GuestRoot { GuestPlayer player = new GuestPlayer(); }
    private static ManagedJavaMemoryEngine.SavedLocator savedCoinsLocator() {
        return new ManagedJavaMemoryEngine.SavedLocator(
                ManagedJavaMemoryEngine.SavedLocator.ROOT_MIDLET, GuestRoot.class.getName(),
                new ManagedJavaMemoryEngine.SavedStep[]{
                        ManagedJavaMemoryEngine.SavedStep.field(
                                new ManagedJavaMemoryEngine.SavedField(
                                        GuestRoot.class.getName(), "player", GuestPlayer.class.getName())),
                        ManagedJavaMemoryEngine.SavedStep.field(
                                new ManagedJavaMemoryEngine.SavedField(
                                        GuestPlayer.class.getName(), "coins", "int"))
                });
    }


    static final class GuestPlayer { int coins = 585858; int flag = 272727; static int shared = 9001; }
    static final class AliasRoot {
        GuestPlayer pathOne = new GuestPlayer();
        GuestPlayer pathTwo = pathOne;
    }


    @Test public void directSavedStaticVariableDoesNotCreateWatchEvenAfterSearch() {
        ClassLoader loader = getClass().getClassLoader();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, loader);
        MemoryDiscoveryBridge.setMidletRoot(FIRST, new GuestRoot());
        MemoryDiscoveryBridge.tailSeen(GuestPlayer.class);
        final int oldValue = GuestPlayer.shared;
        try {
            GuestPlayer.shared = 9001;
            ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(FIRST,
                    MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
                    9001L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            ManagedJavaMemoryEngine.SavedLocator saved = null;
            ManagedJavaMemoryEngine.ManagedPage page =
                    engine.resultPage(FIRST, search.revision, 0, 100);
            for (long id : page.ids) {
                ManagedJavaMemoryEngine.SavedLocator candidate =
                        engine.captureSavedLocator(FIRST, search.revision, id);
                if (candidate != null
                        && candidate.rootKind ==
                            ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC
                        && candidate.steps.length == 1
                        && "shared".equals(candidate.steps[0].field.name)) saved = candidate;
            }
            assertNotNull(saved, "A searched static field must expose a saved descriptor");
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(FIRST, saved,
                            "test-static-identity", 0L).code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            assertEquals("9001", engine.readSaved(FIRST, "test-static-identity").value);
            assertEquals(MemoryEngineContract.RESULT_OK, engine.editSaved(FIRST, "test-static-identity", "9002", 0L).code);
            assertEquals(9002, GuestPlayer.shared);
            assertEquals("9002", engine.readSaved(FIRST, "test-static-identity").value);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
        } finally {
            GuestPlayer.shared = oldValue;
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    @Test public void savedDirectInstanceEditRejectsReplacedOwner() {
        ClassLoader loader = getClass().getClassLoader();
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, loader);
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.SavedLocator locator =
                    savedCoinsLocator();
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(
                    FIRST, locator, "test-player-identity", 0L).code);
            GuestPlayer original = root.player;
            root.player = new GuestPlayer();
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, engine.readSaved(FIRST, "test-player-identity").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, engine.editSaved(FIRST, "test-player-identity", "1234", 0L).code);
            assertEquals(585858, root.player.coins);
            assertEquals(585858, original.coins);
            // A later automatic retry may intentionally bind the new game object.
            // Existing bindings must never redirect before being released.
            root.player.coins = 222;
            engine.releaseSaved(FIRST, "test-player-identity");
            assertEquals(MemoryEngineContract.RESULT_NO_SESSION,
                    engine.readSaved(FIRST, "test-player-identity").code);
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(
                    FIRST, locator, "test-player-identity", 0L).code);
            assertEquals("222", engine.readSaved(FIRST, "test-player-identity").value);
            assertEquals(585858, original.coins);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    @Test public void exactStaticFieldDescriptorResolvesDespiteDuplicateObfuscatedName() throws Exception {
        final String classfile =
                "yv66vgAAADQADQEAAWIHAAEBABBqYXZhL2xhbmcvT2JqZWN0BwADAQAGPGluaXQ+"
                + "AQADKClWAQAEQ29kZQwABQAGCgAEAAgBAAFmAQABSQEAAUoAIQACAAQAAAACAAkA"
                + "CgALAAAACQAKAAwAAAABAAEABQAGAAEABwAAABEAAQABAAAABSq3AAmxAAAAAAAA";
        final byte[] bytes = java.util.Base64.getDecoder().decode(classfile);
        class GuestLoader extends ClassLoader {
            GuestLoader() { super(SavedVariableLocatorTest.class.getClassLoader()); }
            Class<?> defineGuest() { return defineClass("b", bytes, 0, bytes.length); }
        }
        GuestLoader loader = new GuestLoader();
        Class<?> guest = loader.defineGuest();
        assertEquals(2, guest.getDeclaredFields().length, "fixture has duplicate field names");
        java.lang.reflect.Field ambiguous = guest.getDeclaredField("f");
        java.lang.reflect.Field target = null;
        for (java.lang.reflect.Field field : guest.getDeclaredFields()) {
            if (field.getType() != ambiguous.getType()) {
                target = field;
                break;
            }
        }
        assertNotNull(target);
        final int type = target.getType() == int.class
                ? MemoryEngineContract.TYPE_INT : MemoryEngineContract.TYPE_LONG;
        for (java.lang.reflect.Field field : guest.getDeclaredFields()) {
            if (field.getType() == int.class) field.setInt(null, 12345);
            else field.setLong(null, 5566778899L);
        }
        final long queryValue = type == MemoryEngineContract.TYPE_INT ? 12345L : 5566778899L;
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, loader);
        MemoryDiscoveryBridge.setMidletRoot(FIRST, guest.getDeclaredConstructor().newInstance());
        MemoryDiscoveryBridge.tailSeen(guest);
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(FIRST,
                    type, MemoryEngineContract.PREDICATE_EQUAL, queryValue, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertEquals(1L, search.resultCount);
            long id = engine.resultPage(FIRST, search.revision, 0, 10).ids[0];
            ManagedJavaMemoryEngine.SavedLocator saved =
                    engine.captureSavedLocator(FIRST, search.revision, id);
            assertNotNull(saved);
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC, saved.rootKind);
            assertEquals("b", saved.rootClass);
            assertEquals("f", saved.steps[0].field.name);
            assertEquals(target.getType().getName(), saved.steps[0].field.type);
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(
                    FIRST, saved, "duplicate-static-field", 0L).code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            assertEquals(Long.toString(queryValue), engine.readSaved(FIRST, "duplicate-static-field").value);
            final String replacement = "67890";
            assertEquals(MemoryEngineContract.RESULT_OK, engine.editSaved(FIRST, "duplicate-static-field", replacement, 0L).code);
            assertEquals(67890L, type == MemoryEngineContract.TYPE_INT
                    ? target.getInt(null) : target.getLong(null));
            assertEquals(0, engine.watchPage(FIRST).ids.length);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    static final class StaticNeeds {
        static byte[] hunger = new byte[64];
    }


    @Test public void directStaticPrimitiveArrayIndexSurvivesRestartWithoutWatch() {
        ClassLoader loader = getClass().getClassLoader();
        byte[] original = StaticNeeds.hunger;
        long firstToken = FIRST;
        long secondToken = NEXT;
        ManagedJavaMemoryEngine.SavedLocator descriptor;
        StaticNeeds.hunger = new byte[64];
        StaticNeeds.hunger[34] = 95;
        MemoryDiscoveryBridge.install(firstToken, loader);
        MemoryDiscoveryBridge.setMidletRoot(firstToken, new GuestRoot());
        MemoryDiscoveryBridge.tailSeen(StaticNeeds.class);
        ManagedJavaMemoryEngine first = new ManagedJavaMemoryEngine();
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = first.startExact(firstToken,
                    MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.PREDICATE_EQUAL,
                    95L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertEquals(1, search.resultCount);
            long id = first.resultPage(firstToken, search.revision, 0, 10).ids[0];
            descriptor = first.captureSavedLocator(firstToken, search.revision, id);
            assertNotNull(descriptor, "Exact, live static array owner must be capturable");
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC, descriptor.rootKind);
            assertEquals(34, descriptor.steps[1].index);
            assertEquals("[B", descriptor.steps[0].field.type);
            assertEquals(StaticNeeds.class.getName(), descriptor.rootClass);
            assertEquals(MemoryEngineContract.RESULT_OK, first.resolveSavedLocator(firstToken, descriptor,
                            "static-array-hunger", 0L).code);
            assertEquals(0, first.watchPage(firstToken).ids.length);
            assertEquals("95", first.readSaved(firstToken, "static-array-hunger").value);
            assertEquals(MemoryEngineContract.RESULT_OK, first.editSaved(firstToken, "static-array-hunger", "40", 0L).code);
            assertEquals(40, StaticNeeds.hunger[34]);
            byte[] previous = StaticNeeds.hunger;
            StaticNeeds.hunger = new byte[64];
            StaticNeeds.hunger[34] = 6;
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, first.readSaved(firstToken, "static-array-hunger").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, first.editSaved(firstToken, "static-array-hunger", "77", 0L).code);
            assertEquals(40, previous[34]);
            assertEquals(6, StaticNeeds.hunger[34]);
            assertEquals(0, first.watchPage(firstToken).ids.length);
        } finally {
            first.runtimeClosed(firstToken);
            MemoryDiscoveryBridge.close(firstToken);
        }
        ManagedJavaMemoryEngine second = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(secondToken, loader);
        MemoryDiscoveryBridge.setMidletRoot(secondToken, new GuestRoot());
        MemoryDiscoveryBridge.tailSeen(StaticNeeds.class);
        try {
            assertEquals(MemoryEngineContract.RESULT_OK, second.resolveSavedLocator(secondToken, descriptor,
                            "static-array-hunger", 0L).code);
            assertEquals("6", second.readSaved(secondToken, "static-array-hunger").value);
            assertEquals(MemoryEngineContract.RESULT_OK, second.editSaved(secondToken, "static-array-hunger", "30", 0L).code);
            assertEquals(30, StaticNeeds.hunger[34]);
            assertEquals(0, second.watchPage(secondToken).ids.length);
        } finally {
            second.runtimeClosed(secondToken);
            MemoryDiscoveryBridge.close(secondToken);
            StaticNeeds.hunger = original;
        }
    }

    static final class PartyStats { short hp; PartyStats(int hp) { this.hp = (short) hp; } }
    static final class PartyUnit { PartyStats stats; PartyUnit(int hp) { stats = new PartyStats(hp); } }
    static final class PartyHolder {
        static PartyHolder active;
        PartyUnit[] party = new PartyUnit[] {
            new PartyUnit(100), new PartyUnit(120), new PartyUnit(195),
            new PartyUnit(80), new PartyUnit(60)
        };
    }


    @Test public void rootedObjectArrayIndexRebindsShortHpWithoutSearchOrWatch() {
        final PartyHolder original = PartyHolder.active;
        ManagedJavaMemoryEngine.SavedLocator path;
        ManagedJavaMemoryEngine first = new ManagedJavaMemoryEngine();
        PartyHolder.active = new PartyHolder();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, new GuestRoot());
        MemoryDiscoveryBridge.tailSeen(PartyHolder.class);
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = first.startExact(FIRST,
                    MemoryEngineContract.TYPE_SHORT, MemoryEngineContract.PREDICATE_EQUAL,
                    195L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertEquals(1, search.resultCount);
            long id = first.resultPage(FIRST, search.revision, 0, 10).ids[0];
            path = first.captureSavedLocator(FIRST, search.revision, id);
            assertNotNull(path, "Search-selected character must have a rooted path");
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC, path.rootKind);
            assertEquals(5, path.steps.length);
            assertEquals(ManagedJavaMemoryEngine.SavedStep.FIELD, path.steps[0].kind);
            assertEquals(ManagedJavaMemoryEngine.SavedStep.FIELD, path.steps[1].kind);
            assertEquals(2, path.steps[2].index);
            assertEquals(ManagedJavaMemoryEngine.SavedStep.FIELD, path.steps[3].kind);
            assertEquals(ManagedJavaMemoryEngine.SavedStep.FIELD, path.steps[4].kind);
            assertEquals("hp", path.steps[4].field.name);
            assertEquals(MemoryEngineContract.RESULT_OK, first.resolveSavedLocator(
                    FIRST, path, "ninja-hp-owner", 0L).code);
            assertEquals("195", first.readSaved(FIRST, "ninja-hp-owner").value);
            assertEquals(0, first.watchPage(FIRST).ids.length);
            assertEquals(MemoryEngineContract.RESULT_OK, first.editSaved(
                    FIRST, "ninja-hp-owner", "180", 0L).code);
            assertEquals(180, PartyHolder.active.party[2].stats.hp);
            PartyUnit originalUnit = PartyHolder.active.party[2];
            PartyHolder.active.party[2] = new PartyUnit(195);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, first.readSaved(FIRST, "ninja-hp-owner").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, first.editSaved(FIRST, "ninja-hp-owner", "50", 0L).code);
            assertEquals(180, originalUnit.stats.hp);
            assertEquals(195, PartyHolder.active.party[2].stats.hp);
        } finally {
            first.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
        // Restart creates different objects; only structural identity is established.
        PartyHolder.active = new PartyHolder();
        ManagedJavaMemoryEngine second = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(NEXT, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(NEXT, new GuestRoot());
        MemoryDiscoveryBridge.tailSeen(PartyHolder.class);
        try {
            assertEquals(MemoryEngineContract.RESULT_OK, second.resolveSavedLocator(
                    NEXT, path, "ninja-hp-owner", 0L).code);
            assertEquals("195", second.readSaved(NEXT, "ninja-hp-owner").value);
            assertEquals(0, second.watchPage(NEXT).ids.length);
        } finally {
            second.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
            PartyHolder.active = original;
        }
    }


    @Test public void frozenSavedReferenceStopsWritingAfterOwnerReplacement() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.SavedLocator locator =
                    savedCoinsLocator();
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(FIRST, locator, "saved-coins-freeze", 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.freezeSaved(FIRST, "saved-coins-freeze", "Coins", "585858", 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            assertEquals(1, engine.watchPage(FIRST).freezeModes.length);
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.editSaved(FIRST, "saved-coins-freeze", "700", 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.freezeSaved(FIRST, "saved-coins-freeze", "Coins", "700", 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            root.player.coins = 99;
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(700, root.player.coins);
            GuestPlayer oldOwner = root.player;
            root.player = new GuestPlayer();
            root.player.coins = 55;
            oldOwner.coins = 66;
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, engine.freezeTick(FIRST, 0L).code);
            long watchId = engine.watchPage(FIRST).ids[0];
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.editTyped(FIRST, 0L, new long[]{watchId},
                    MemoryEngineContract.TYPE_INT, "555", true, 0L).code);
            assertEquals(66, oldOwner.coins);
            assertEquals(55, root.player.coins);
            assertTrue(engine.watchPage(FIRST).freezePaused[0]);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }


    @Test public void savedFreezeRejectsInterveningGuestWriteInsteadOfFreezingWrongValue() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.SavedLocator locator =
                    savedCoinsLocator();
            String savedId = "saved-freeze-expected";
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, locator, savedId, 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, savedId, "700", 0L).code);
            root.player.coins = 99; // The guest writes between the two UI IPC calls.
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    engine.freezeSaved(FIRST, savedId, "Coins", "700", 0L).code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            assertEquals(99, root.player.coins);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, savedId, "800", 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.freezeSaved(FIRST, savedId, "Coins", "800", 0L).code);
            root.player.coins = 50;
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(800, root.player.coins);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    @Test public void savedFreezeExplicitlyRebindsExistingWatchToReplacementOwner() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        ManagedJavaMemoryEngine.SavedLocator locator =
                savedCoinsLocator();
        try {
            String savedId = "hp"; // Portable identifiers have no arbitrary minimum length.
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, locator, savedId, 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.freezeSaved(FIRST, savedId, "HP", "585858", 0L).code);
            GuestPlayer original = root.player;
            root.player = new GuestPlayer();
            root.player.coins = 222;
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    engine.freezeTick(FIRST, 0L).code);
            engine.releaseSaved(FIRST, savedId);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, locator, savedId, 0L).code);
            // Passive rebind leaves the old Freeze paused and the replacement untouched.
            root.player.coins = 33;
            engine.freezeTick(FIRST, 0L);
            assertEquals(33, root.player.coins);
            assertTrue(engine.watchPage(FIRST).freezePaused[0]);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, savedId, "700", 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.freezeSaved(FIRST, savedId, "HP", "700", 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            root.player.coins = 123;
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.freezeTick(FIRST, 0L).code);
            assertEquals(700, root.player.coins);
            assertEquals(585858, original.coins);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    static final class ArrayRoot {
        int[] health = { 8, 678901, 16 };
        byte[][] inventory = { { 2, 7 }, { 3, 95 } };
    }

    @Test public void rootedPrimitiveArraysAndNestedIndicesPersistThroughRestart() {
        ArrayRoot root = new ArrayRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        ManagedJavaMemoryEngine.SavedLocator oneDimensional = null, nested = null;
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult found = engine.startExact(FIRST,
                    MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
                    678901L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, found.code);
            for (long id : engine.resultPage(FIRST, found.revision, 0, 100).ids) {
                ManagedJavaMemoryEngine.SavedLocator captured =
                        engine.captureSavedLocator(FIRST, found.revision, id);
                if (captured != null && captured.steps.length == 2
                        && captured.steps[1].kind == ManagedJavaMemoryEngine.SavedStep.INDEX)
                    oneDimensional = captured;
            }
            assertNotNull(oneDimensional, "Instance primitive array must be bookmarkable");
            assertEquals(ManagedJavaMemoryEngine.SavedStep.INDEX, oneDimensional.steps[1].kind);
            assertEquals("[I", oneDimensional.steps[1].arrayType);
            assertEquals(1, oneDimensional.steps[1].index);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, oneDimensional, "array-int", 0L).code);
            assertEquals("678901", engine.readSaved(FIRST, "array-int").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, "array-int", "1234", 0L).code);
            assertEquals(1234, root.health[1]);

            found = engine.startExact(FIRST, MemoryEngineContract.TYPE_BYTE,
                    MemoryEngineContract.PREDICATE_EQUAL, 95L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, found.code);
            for (long id : engine.resultPage(FIRST, found.revision, 0, 100).ids) {
                ManagedJavaMemoryEngine.SavedLocator captured =
                        engine.captureSavedLocator(FIRST, found.revision, id);
                if (captured != null && captured.steps.length == 3
                        && captured.steps[2].kind == ManagedJavaMemoryEngine.SavedStep.INDEX)
                    nested = captured;
            }
            assertNotNull(nested, "Nested primitive array must have a typed rooted path");
            assertEquals("[[B", nested.steps[1].arrayType);
            assertEquals("[B", nested.steps[2].arrayType);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, nested, "array-nested", 0L).code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, "array-nested", "20", 0L).code);
            assertEquals(20, root.inventory[1][1]);
            root.inventory = new byte[][]{{2, 7}, {3, 66}};
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    engine.readSaved(FIRST, "array-nested").code);
            engine.releaseSaved(FIRST, "array-nested");
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, nested, "array-nested", 0L).code);
            assertEquals("66", engine.readSaved(FIRST, "array-nested").value);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
        root.health[1] = 678901;
        root.inventory = new byte[][]{{2, 7}, {3, 95}};
        ManagedJavaMemoryEngine nextEngine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(NEXT, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(NEXT, root);
        try {
            assertEquals(MemoryEngineContract.RESULT_OK,
                    nextEngine.resolveSavedLocator(NEXT, oneDimensional, "array-int", 0L).code);
            assertEquals("678901", nextEngine.readSaved(NEXT, "array-int").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    nextEngine.resolveSavedLocator(NEXT, nested, "array-nested", 0L).code);
            assertEquals("95", nextEngine.readSaved(NEXT, "array-nested").value);
        } finally {
            nextEngine.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
        }
    }

    @Test public void savedInspectorReadsSiblingsAndCapturesTheirTypedPath() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.SavedLocator locator =
                    savedCoinsLocator();
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(FIRST, locator, "saved-coins-inspect", 0L).code);
            ManagedJavaMemoryEngine.ManagedInspection inspected =
                engine.inspectSaved(FIRST, "saved-coins-inspect", 10);
            assertEquals(MemoryEngineContract.RESULT_OK, inspected.code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            int sibling = -1;
            for (int i = 0; i < inspected.labels.length; i++) {
                if (inspected.labels[i].contains("flag")) sibling = inspected.relativeOffsets[i];
            }
            assertTrue(sibling != -1, "Inspector must include a real primitive sibling");
            ManagedJavaMemoryEngine.SavedLocator adjacent =
                engine.captureSavedNeighborLocator(FIRST, "saved-coins-inspect", sibling);
            assertNotNull(adjacent);
            assertEquals("flag", adjacent.steps[adjacent.steps.length - 1].field.name);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(FIRST, adjacent, "saved-player-flag", 0L).code);
            assertEquals("272727", engine.readSaved(FIRST, "saved-player-flag").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.editSavedInspector(FIRST, "saved-coins-inspect", sibling,
                    MemoryEngineContract.TYPE_INT, 272727L, "1234", 0L).code);
            assertEquals(1234, root.player.flag);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.editSavedInspector(FIRST, "saved-coins-inspect", sibling,
                    MemoryEngineContract.TYPE_INT, 272727L, "4444", 0L).code);
            assertEquals(1234, root.player.flag);
            GuestPlayer previous = root.player;
            root.player = new GuestPlayer();
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.editSavedInspector(FIRST, "saved-coins-inspect", sibling,
                    MemoryEngineContract.TYPE_INT, 272727L, "7777", 0L).code);
            assertEquals(1234, previous.flag);
            assertEquals(272727, root.player.flag);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    @Test public void savedInspectorCanWatchSiblingAndFreezeEditedValue() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.SavedLocator locator =
                    savedCoinsLocator();
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(FIRST, locator, "watched-inspector-owner", 0L).code);
            ManagedJavaMemoryEngine.ManagedInspection inspected =
                engine.inspectSaved(FIRST, "watched-inspector-owner", 8);
            int offset = -999;
            for (int i = 0; i < inspected.labels.length; i++)
                if (inspected.labels[i].contains("flag")) offset = inspected.relativeOffsets[i];
            assertTrue(offset != -999);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.watchInspector(FIRST, "watched-inspector-owner", 0L, 0L,
                    false, offset, MemoryEngineContract.TYPE_INT, 272727L, false, 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            assertEquals(-1, engine.watchPage(FIRST).freezeModes[0]);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.editInspectorAndFreeze(FIRST, "watched-inspector-owner", 0L, 0L,
                    false, offset, MemoryEngineContract.TYPE_INT, 272727L, "456", 0L).code);
            assertEquals(456, root.player.flag);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            root.player.flag = 9;
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(456, root.player.flag);
            GuestPlayer old = root.player;
            root.player = new GuestPlayer();
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.freezeTick(FIRST, 0L).code);
            assertEquals(456, old.flag);
            assertEquals(272727, root.player.flag);
            engine.releaseSaved(FIRST, "watched-inspector-owner");
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(FIRST, locator, "watched-inspector-owner", 0L).code);
            inspected = engine.inspectSaved(FIRST, "watched-inspector-owner", 8);
            assertEquals(MemoryEngineContract.RESULT_OK, inspected.code);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.watchInspector(FIRST, "watched-inspector-owner", 0L, 0L,
                    false, offset, MemoryEngineContract.TYPE_INT, 272727L, true, 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            root.player.flag = 17;
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(272727, root.player.flag);
            assertEquals(456, old.flag);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }

    @Test public void searchInspectorSiblingCanJoinWatchAndFreezeWithoutBecomingSearchResult() {
        GuestRoot root = new GuestRoot();
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST, getClass().getClassLoader());
        MemoryDiscoveryBridge.setMidletRoot(FIRST, root);
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(FIRST,
                MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
                585858L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            long selected = engine.resultPage(FIRST, search.revision, 0, 25).ids[0];
            ManagedJavaMemoryEngine.ManagedInspection inspected =
                engine.inspect(FIRST, search.revision, selected, 8, false);
            assertEquals(MemoryEngineContract.RESULT_OK, inspected.code);
            int offset = -999;
            for (int i = 0; i < inspected.labels.length; i++)
                if (inspected.labels[i].contains("flag")) offset = inspected.relativeOffsets[i];
            assertTrue(offset != -999);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.watchInspector(FIRST, null, search.revision, selected, false,
                    offset, MemoryEngineContract.TYPE_INT, 272727L, false, 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.editInspectorAndFreeze(FIRST, null, search.revision, selected,
                    false, offset, MemoryEngineContract.TYPE_INT, 272727L, "888", 0L).code);
            assertEquals(1, engine.watchPage(FIRST).ids.length);
            root.player.flag = 1;
            assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(FIRST, 0L).code);
            assertEquals(888, root.player.flag);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.watchInspector(FIRST, null, search.revision, selected,
                    false, offset, MemoryEngineContract.TYPE_INT, 272727L, false, 0L).code);
        } finally {
            engine.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }
    }
}
