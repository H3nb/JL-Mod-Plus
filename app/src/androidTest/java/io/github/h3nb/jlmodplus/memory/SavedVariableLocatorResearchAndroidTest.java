// R&D-only Android ART experiment. Keep this test off the production branch.
// No game assets and no persistent feature implementation in this test.
package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import javax.microedition.shell.MemoryDiscoveryBridge;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SavedVariableLocatorResearchAndroidTest {
    private static final long FIRST_TOKEN = 0x4d454d3100L;
    private static final long SECOND_TOKEN = 0x4d454d3200L;
    private static final int DISTINCT_VALUE = 617344;

    static final class Leaf {
        private int coins = DISTINCT_VALUE;
    }
    static final class Root {
        private Leaf player = new Leaf();
        private Leaf[] party = {new Leaf(), new Leaf()};
        private int decoy = DISTINCT_VALUE;
        static int shared = DISTINCT_VALUE;
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field f = owner.getDeclaredField(name);
        assertSame(type, f.getType());
        f.setAccessible(true);
        return f;
    }

    // The persistent recipe stores only two class/field names, never the actual Java objects.
    private static Leaf resolvePlayer(Object root) throws Exception {
        if (root == null || root.getClass() != Root.class) throw new IllegalArgumentException();
        Field player = field(Root.class, "player", Leaf.class);
        return (Leaf) player.get(root);
    }

    @Test public void androidArtFindsManagedCandidateAndRebindsToSecondRuntime() throws Exception {
        MemoryDiscoveryBridge.install(FIRST_TOKEN, Root.class.getClassLoader());
        try {
            Root first = new Root();
            MemoryDiscoveryBridge.setMidletRoot(FIRST_TOKEN, first);
            MemoryDiscoveryBridge.tailSeen(Root.class);
            MemoryDiscoveryBridge.tailSeen(Leaf.class);
            ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
            ManagedJavaMemoryEngine.ManagedOperationResult search =
                    engine.startExact(FIRST_TOKEN, MemoryEngineContract.TYPE_INT,
                            MemoryEngineContract.PREDICATE_EQUAL, DISTINCT_VALUE, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertTrue("fixture should contain several equal-valued fields", search.resultCount >= 3L);
            ManagedJavaMemoryEngine.ManagedPage page =
                    engine.resultPage(FIRST_TOKEN, search.revision, 0, 100);
            boolean foundVerifiedCoins = false;
            for (long id : page.ids) {
                ManagedJavaMemoryEngine.ManagedInspection inspected =
                        engine.inspect(FIRST_TOKEN, search.revision, id, 1);
                if ("coins".equals(inspected.source.fieldName)
                        && inspected.path.status == MemoryEngineContract.PATH_VERIFIED
                        && inspected.path.path.contains("player")) {
                    foundVerifiedCoins = true;
                    break;
                }
            }
            assertTrue("existing scanner/Inspector must expose a rooted numeric field",
                    foundVerifiedCoins);
            assertEquals(DISTINCT_VALUE, field(Leaf.class, "coins", int.class)
                    .getInt(resolvePlayer(first)));
        } finally {
            MemoryDiscoveryBridge.close(FIRST_TOKEN);
        }

        MemoryDiscoveryBridge.install(SECOND_TOKEN, Root.class.getClassLoader());
        try {
            Root second = new Root();
            MemoryDiscoveryBridge.setMidletRoot(SECOND_TOKEN, second);
            // Resolve a typed structural path directly, with no second memory scan.
            Object liveRoot = MemoryDiscoveryBridge.snapshot(SECOND_TOKEN).root();
            Leaf resolved = resolvePlayer(liveRoot);
            Field coins = field(Leaf.class, "coins", int.class);
            assertEquals(DISTINCT_VALUE, coins.getInt(resolved));
            coins.setInt(resolved, DISTINCT_VALUE + 1);
            assertEquals(DISTINCT_VALUE + 1, second.player.coins);
            assertEquals(DISTINCT_VALUE, second.decoy);
        } finally {
            MemoryDiscoveryBridge.close(SECOND_TOKEN);
        }
    }

    @Test public void arrayPathCanRemainValidButChangeSemanticOwner() throws Exception {
        Root root = new Root();
        Leaf original = root.party[0];
        Leaf newFirst = root.party[1];
        assertNotSame(original, newFirst);
        assertEquals(original.coins, newFirst.coins);
        root.party[0] = newFirst;
        root.party[1] = original;
        // Never silently claim that an array index uniquely identifies the same entity.
        assertNotSame(original, root.party[0]);
        assertEquals(original.coins, root.party[0].coins);
    }
}
