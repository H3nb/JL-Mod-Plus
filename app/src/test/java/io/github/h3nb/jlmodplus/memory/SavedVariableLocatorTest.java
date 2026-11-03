package io.github.h3nb.jlmodplus.memory;

import org.junit.jupiter.api.Test;
import javax.microedition.shell.MemoryDiscoveryBridge;
import static org.junit.jupiter.api.Assertions.*;

/** Focused guest-runtime Saved locator regression tests. */
public class SavedVariableLocatorTest {
    private static final long FIRST = 1000021L, NEXT = 1000022L;
    static final class GuestRoot { GuestPlayer player = new GuestPlayer(); }
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
                        && candidate.fields.length == 1
                        && "shared".equals(candidate.fields[0].name)) saved = candidate;
            }
            assertNotNull("A searched static field must expose a saved descriptor", saved);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.resolveSavedLocator(FIRST, saved,
                            "test-static-identity", 0L).code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            assertEquals("9001", engine.readSaved(FIRST, "test-static-identity").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, "test-static-identity", "9002", 0L).code);
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
                    new ManagedJavaMemoryEngine.SavedLocator(
                            ManagedJavaMemoryEngine.SavedLocator.ROOT_MIDLET,
                            GuestRoot.class.getName(),
                            new ManagedJavaMemoryEngine.SavedField[] {
                                new ManagedJavaMemoryEngine.SavedField(
                                        GuestRoot.class.getName(), "player", GuestPlayer.class.getName()),
                                new ManagedJavaMemoryEngine.SavedField(
                                        GuestPlayer.class.getName(), "coins", "int")
                            });
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(
                    FIRST, locator, "test-player-identity", 0L).code);
            GuestPlayer original = root.player;
            root.player = new GuestPlayer();
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    engine.readSaved(FIRST, "test-player-identity").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    engine.editSaved(FIRST, "test-player-identity", "1234", 0L).code);
            assertEquals(585858, root.player.coins);
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
            GuestLoader() { super(SavedLocatorEngineResearchTest.class.getClassLoader()); }
            Class<?> defineGuest() { return defineClass("b", bytes, 0, bytes.length); }
        }
        GuestLoader loader = new GuestLoader();
        Class<?> guest = loader.defineGuest();
        assertEquals("fixture has duplicate field names", 2, guest.getDeclaredFields().length);
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
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC,
                    saved.rootKind);
            assertEquals("b", saved.rootClass);
            assertEquals("f", saved.fields[0].name);
            assertEquals(target.getType().getName(), saved.fields[0].type);
            assertEquals(MemoryEngineContract.RESULT_OK, engine.resolveSavedLocator(
                    FIRST, saved, "duplicate-static-field", 0L).code);
            assertEquals(0, engine.watchPage(FIRST).ids.length);
            assertEquals(Long.toString(queryValue),
                    engine.readSaved(FIRST, "duplicate-static-field").value);
            final String replacement = "67890";
            assertEquals(MemoryEngineContract.RESULT_OK,
                    engine.editSaved(FIRST, "duplicate-static-field", replacement, 0L).code);
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
            assertNotNull("Exact, live static array owner must be capturable", descriptor);
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC,
                    descriptor.rootKind);
            assertEquals(34, descriptor.arrayIndex);
            assertEquals("[B", descriptor.fields[0].type);
            assertEquals(StaticNeeds.class.getName(), descriptor.rootClass);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    first.resolveSavedLocator(firstToken, descriptor,
                            "static-array-hunger", 0L).code);
            assertEquals(0, first.watchPage(firstToken).ids.length);
            assertEquals("95", first.readSaved(firstToken, "static-array-hunger").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    first.editSaved(firstToken, "static-array-hunger", "40", 0L).code);
            assertEquals(40, StaticNeeds.hunger[34]);
            byte[] previous = StaticNeeds.hunger;
            StaticNeeds.hunger = new byte[64];
            StaticNeeds.hunger[34] = 6;
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    first.readSaved(firstToken, "static-array-hunger").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    first.editSaved(firstToken, "static-array-hunger", "77", 0L).code);
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
            assertEquals(MemoryEngineContract.RESULT_OK,
                    second.resolveSavedLocator(secondToken, descriptor,
                            "static-array-hunger", 0L).code);
            assertEquals("6", second.readSaved(secondToken, "static-array-hunger").value);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    second.editSaved(secondToken, "static-array-hunger", "30", 0L).code);
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
            assertNotNull("Search-selected character must have a rooted path", path);
            assertEquals(ManagedJavaMemoryEngine.SavedLocator.ROOT_INITIALIZED_STATIC, path.rootKind);
            assertEquals(4, path.fields.length);
            assertEquals(4, path.fieldIndices.length);
            assertEquals(-1, path.fieldIndices[0]);
            assertEquals(2, path.fieldIndices[1]);
            assertEquals(-1, path.fieldIndices[2]);
            assertEquals(-1, path.fieldIndices[3]);
            assertEquals("hp", path.fields[3].name);
            assertEquals(MemoryEngineContract.RESULT_OK, first.resolveSavedLocator(
                    FIRST, path, "ninja-hp-owner", 0L).code);
            assertEquals("195", first.readSaved(FIRST, "ninja-hp-owner").value);
            assertEquals(0, first.watchPage(FIRST).ids.length);
            assertEquals(MemoryEngineContract.RESULT_OK, first.editSaved(
                    FIRST, "ninja-hp-owner", "180", 0L).code);
            assertEquals(180, PartyHolder.active.party[2].stats.hp);
            PartyUnit originalUnit = PartyHolder.active.party[2];
            PartyHolder.active.party[2] = new PartyUnit(195);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    first.readSaved(FIRST, "ninja-hp-owner").code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    first.editSaved(FIRST, "ninja-hp-owner", "50", 0L).code);
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

}
