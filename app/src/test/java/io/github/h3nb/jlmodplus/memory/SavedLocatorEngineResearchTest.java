package io.github.h3nb.jlmodplus.memory;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import javax.microedition.shell.MemoryDiscoveryBridge;
import static org.junit.Assert.*;

/** Disposable R&D characterization. Reflection accesses private engine internals, not production API. */
public class SavedLocatorEngineResearchTest {
    private static final long FIRST = 1000021L, NEXT = 1000022L;
    static final class GuestRoot { GuestPlayer player = new GuestPlayer(); }
    static final class GuestPlayer { int coins = 585858; }

    @Test public void existingEngineCanEditReboundTargetWithoutSecondSearch() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ManagedJavaMemoryEngine initial = new ManagedJavaMemoryEngine();
        GuestRoot before = new GuestRoot();
        MemoryDiscoveryBridge.install(FIRST, loader);
        MemoryDiscoveryBridge.tailSeen(GuestRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(FIRST, before);
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = initial.startExact(FIRST,
                MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 585858L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertEquals(1L, search.resultCount);
            ManagedJavaMemoryEngine.ManagedPage page = initial.resultPage(FIRST, search.revision, 0, 20);
            ManagedJavaMemoryEngine.ManagedInspection ins = initial.inspect(FIRST, search.revision, page.ids[0], 2);
            assertEquals(MemoryEngineContract.PATH_VERIFIED, ins.path.status);
        } finally {
            initial.runtimeClosed(FIRST);
            MemoryDiscoveryBridge.close(FIRST);
        }

        GuestRoot after = new GuestRoot();
        ManagedJavaMemoryEngine restarted = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(NEXT, loader);
        MemoryDiscoveryBridge.tailSeen(GuestRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(NEXT, after);
        try {
            restarted.capabilities(NEXT); // Activate without Search.
            assertEquals(0L, restarted.session(NEXT).resultCount);
            Field rootField = GuestRoot.class.getDeclaredField("player");
            Field valueField = GuestPlayer.class.getDeclaredField("coins");
            rootField.setAccessible(true);
            valueField.setAccessible(true);
            Object target = rootField.get(after);
            assertSame(after.player, target);
            assertEquals(int.class, valueField.getType());
            long newId = researchBindToExistingWatch(restarted, target, valueField);
            ManagedJavaMemoryEngine.ManagedPage watch = restarted.watchPage(NEXT);
            assertEquals(1, watch.ids.length);
            assertEquals(newId, watch.ids[0]);
            assertEquals("585858", watch.values[0]);
            ManagedJavaMemoryEngine.ManagedOperationResult write = restarted.editTyped(
                NEXT, 0L, new long[]{newId}, MemoryEngineContract.TYPE_INT, "424242", true, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, write.code);
            assertEquals(424242, after.player.coins);
            assertEquals(585858, before.player.coins);
        } finally {
            restarted.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
        }
    }

    /** Test-only bridge into existing OwnerBucket/Watch machinery. */
    private static long researchBindToExistingWatch(ManagedJavaMemoryEngine engine,
                                                    Object target, Field selected) throws Exception {
        Class<?> engineType = ManagedJavaMemoryEngine.class;
        Class<?> schemaType = Class.forName(engineType.getName() + "$Schema");
        Class<?> ownerType = Class.forName(engineType.getName() + "$OwnerBucket");
        Method schemaFor = engineType.getDeclaredMethod("schemaFor", Class.class, ClassLoader.class);
        schemaFor.setAccessible(true);
        Object schema = schemaFor.invoke(engine, target.getClass(), target.getClass().getClassLoader());
        Field fields = schemaType.getDeclaredField("fields");
        fields.setAccessible(true);
        Object[] entries = (Object[]) fields.get(schema);
        int slot = -1;
        for (int index = 0; index < entries.length; index++) {
            Field field = entries[index].getClass().getDeclaredField("field");
            field.setAccessible(true);
            if (selected.equals(field.get(entries[index]))) { slot = index; break; }
        }
        assertTrue("Field must be resolved by declared identity, not slot order", slot >= 0);
        Method ownerForObject = engineType.getDeclaredMethod("ownerForObject", Object.class,
                int.class, schemaType, IdentityHashMap.class);
        ownerForObject.setAccessible(true);
        Object owner = ownerForObject.invoke(engine, target,
                ManagedJavaMemoryEngine.KIND_OBJECT_FIELD, schema, new IdentityHashMap<>());
        Field handleField = ownerType.getDeclaredField("handle");
        handleField.setAccessible(true);
        long id = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_OBJECT_FIELD,
                handleField.getLong(owner), slot);
        Field watchesField = engineType.getDeclaredField("watches");
        watchesField.setAccessible(true);
        Object watches = watchesField.get(engine);
        Method append = watches.getClass().getDeclaredMethod("append", long.class, ownerType,
                int.class, long.class, String.class);
        append.setAccessible(true);
        append.invoke(watches, id, owner, slot, (long) ((GuestPlayer) target).coins, "Saved coins");
        return id;
    }
}
