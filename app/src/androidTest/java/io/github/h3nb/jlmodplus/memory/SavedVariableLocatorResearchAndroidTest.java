// R&D-only Android ART experiment. Keep this test off the production branch.
// No game assets and no persistent feature implementation in this test.
package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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

    @Test public void androidArtUsesExistingManagedEngineToCaptureAndRebind() throws Exception {
        ManagedJavaMemoryEngine.ResearchFieldLocator saved = null;
        Root first = new Root();
        ManagedJavaMemoryEngine initial = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(FIRST_TOKEN, Root.class.getClassLoader());
        try {
            MemoryDiscoveryBridge.setMidletRoot(FIRST_TOKEN, first);
            MemoryDiscoveryBridge.tailSeen(Root.class);
            MemoryDiscoveryBridge.tailSeen(Leaf.class);
            ManagedJavaMemoryEngine.ManagedOperationResult search =
                    initial.startExact(FIRST_TOKEN, MemoryEngineContract.TYPE_INT,
                            MemoryEngineContract.PREDICATE_EQUAL, DISTINCT_VALUE, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertTrue("fixture should contain several equal-valued fields", search.resultCount >= 3L);
            ManagedJavaMemoryEngine.ManagedPage page =
                    initial.resultPage(FIRST_TOKEN, search.revision, 0, 100);
            for (long id : page.ids) {
                ManagedJavaMemoryEngine.ManagedInspection inspected =
                        initial.inspect(FIRST_TOKEN, search.revision, id, 1);
                if ("coins".equals(inspected.source.fieldName)
                        && inspected.path.status == MemoryEngineContract.PATH_VERIFIED
                        && inspected.path.path.contains("player")) {
                    saved = initial.researchCaptureFieldLocator(FIRST_TOKEN, search.revision, id);
                    break;
                }
            }
            assertNotNull("The existing Inspector must supply a verified root-to-target path", saved);
            saved = saved.copyStringsOnly();
            assertEquals(ManagedJavaMemoryEngine.ResearchFieldLocator.ROOT_MIDLET, saved.rootKind);
            assertEquals(2, saved.fields.length);
        } finally {
            initial.runtimeClosed(FIRST_TOKEN);
            MemoryDiscoveryBridge.close(FIRST_TOKEN);
        }

        Root second = new Root();
        ManagedJavaMemoryEngine restarted = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(SECOND_TOKEN, Root.class.getClassLoader());
        try {
            MemoryDiscoveryBridge.setMidletRoot(SECOND_TOKEN, second);
            MemoryDiscoveryBridge.tailSeen(Root.class);
            MemoryDiscoveryBridge.tailSeen(Leaf.class);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    restarted.researchBindFieldLocator(SECOND_TOKEN, saved, "Coins", 0L).code);
            assertEquals(0L, restarted.session(SECOND_TOKEN).resultCount);
            ManagedJavaMemoryEngine.ManagedPage watch = restarted.watchPage(SECOND_TOKEN);
            assertEquals(1, watch.ids.length);
            assertEquals("Coins", watch.labels[0]);
            assertEquals(Integer.toString(DISTINCT_VALUE), watch.values[0]);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    restarted.editTyped(SECOND_TOKEN, 0L, watch.ids, MemoryEngineContract.TYPE_INT,
                            Integer.toString(DISTINCT_VALUE + 1), true, 0L).code);
            assertEquals(DISTINCT_VALUE + 1, second.player.coins);
            assertEquals(DISTINCT_VALUE, first.player.coins);
            assertEquals(DISTINCT_VALUE, second.decoy);
            assertEquals(MemoryEngineContract.RESULT_OK,
                    restarted.setFreezeLockTyped(SECOND_TOKEN, 0L, watch.ids,
                            "818181", true, 0L).code);
            Leaf originalPlayer = second.player;
            second.player = new Leaf(); // Same value, different logical owner.
            assertEquals(MemoryEngineContract.PATH_CHANGED,
                    restarted.inspect(SECOND_TOKEN, 0L, watch.ids[0], 1, true).path.status);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    restarted.editTyped(SECOND_TOKEN, 0L, watch.ids, MemoryEngineContract.TYPE_INT,
                            "919191", true, 0L).code);
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                    restarted.freezeTick(SECOND_TOKEN, 0L).code);
            assertTrue(restarted.watchPage(SECOND_TOKEN).freezePaused[0]);
            assertEquals(DISTINCT_VALUE + 1, originalPlayer.coins);
            assertEquals(DISTINCT_VALUE, second.player.coins);
        } finally {
            restarted.runtimeClosed(SECOND_TOKEN);
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
