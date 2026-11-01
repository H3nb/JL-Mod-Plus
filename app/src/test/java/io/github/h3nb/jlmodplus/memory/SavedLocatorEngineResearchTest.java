package io.github.h3nb.jlmodplus.memory;

import org.junit.Test;
import javax.microedition.shell.MemoryDiscoveryBridge;
import static org.junit.Assert.*;

/** Disposable R&D contract test; no production UI, file format, or Binder API. */
public class SavedLocatorEngineResearchTest {
    private static final long FIRST = 1000021L, NEXT = 1000022L;
    static final class GuestRoot { GuestPlayer player = new GuestPlayer(); }
    static final class GuestPlayer { int coins = 585858; static int shared = 9001; }

    @Test public void rebindsDetachedFieldDescriptorIntoExistingWatchWithoutSearching() {
        ClassLoader loader = getClass().getClassLoader();
        GuestRoot before = new GuestRoot();
        ManagedJavaMemoryEngine.ResearchFieldLocator descriptor;
        MemoryDiscoveryBridge.install(FIRST, loader);
        MemoryDiscoveryBridge.tailSeen(GuestRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(FIRST, before);
        ManagedJavaMemoryEngine initial = new ManagedJavaMemoryEngine();
        try {
            ManagedJavaMemoryEngine.ManagedOperationResult search = initial.startExact(FIRST,
                MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 585858L, 0L, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, search.code);
            assertEquals(1L, search.resultCount);
            long id = initial.resultPage(FIRST, search.revision, 0, 20).ids[0];
            descriptor = initial.researchCaptureFieldLocator(FIRST, search.revision, id);
            assertNotNull("Only a complete verified root-to-field path may be saved", descriptor);
            descriptor = descriptor.copyStringsOnly(); // No objects or runtime IDs carried over.
            assertEquals(ManagedJavaMemoryEngine.ResearchFieldLocator.ROOT_MIDLET,
                descriptor.rootKind);
            assertEquals(2, descriptor.fields.length);
            assertEquals("coins", descriptor.fields[1].name);
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
            assertEquals(MemoryEngineContract.RESULT_OK, restarted.researchBindFieldLocator(
                NEXT, descriptor, "Saved coins", 0L).code);
            assertEquals(0L, restarted.session(NEXT).resultCount); // No second scan.
            ManagedJavaMemoryEngine.ManagedPage watch = restarted.watchPage(NEXT);
            assertEquals(1, watch.ids.length);
            assertEquals("Saved coins", watch.labels[0]);
            assertEquals("585858", watch.values[0]);
            ManagedJavaMemoryEngine.ManagedOperationResult edited = restarted.editTyped(
                NEXT, 0L, new long[]{watch.ids[0]}, MemoryEngineContract.TYPE_INT, "424242", true, 0L);
            assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
            assertEquals(424242, after.player.coins);
            assertEquals(585858, before.player.coins);
        } finally {
            restarted.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
        }
    }

    @Test public void mismatchedPathCannotCreateWatchEvenWhenValuesAreEqual() {
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(NEXT, getClass().getClassLoader());
        MemoryDiscoveryBridge.tailSeen(GuestRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(NEXT, new GuestRoot());
        try {
            ManagedJavaMemoryEngine.ResearchFieldLocator mismatched = new
                ManagedJavaMemoryEngine.ResearchFieldLocator(
                    ManagedJavaMemoryEngine.ResearchFieldLocator.ROOT_MIDLET,
                    GuestRoot.class.getName(), new ManagedJavaMemoryEngine.ResearchField[]{
                        new ManagedJavaMemoryEngine.ResearchField(GuestRoot.class.getName(),
                            "player", GuestPlayer.class.getName()),
                        new ManagedJavaMemoryEngine.ResearchField(GuestPlayer.class.getName(),
                            "coins", "long")
                    });
            assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                engine.researchBindFieldLocator(NEXT, mismatched, "Wrong type", 0L).code);
            assertEquals(0, engine.watchPage(NEXT).ids.length);
        } finally {
            engine.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
        }
    }

    @Test public void staticRootMustBeInAlreadyInitializedGuestRegistry() {
        ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(NEXT, getClass().getClassLoader());
        MemoryDiscoveryBridge.tailSeen(GuestRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(NEXT, new GuestRoot());
        try {
            ManagedJavaMemoryEngine.ResearchFieldLocator saved = new
                ManagedJavaMemoryEngine.ResearchFieldLocator(
                    ManagedJavaMemoryEngine.ResearchFieldLocator.ROOT_INITIALIZED_STATIC,
                    GuestPlayer.class.getName(), new ManagedJavaMemoryEngine.ResearchField[]{
                        new ManagedJavaMemoryEngine.ResearchField(GuestPlayer.class.getName(),
                            "shared", "int")
                    });
            assertEquals(MemoryEngineContract.RESULT_TARGET_LOST,
                engine.researchBindFieldLocator(NEXT, saved, "Shared", 0L).code);
            assertEquals(0, engine.watchPage(NEXT).ids.length);
            MemoryDiscoveryBridge.tailSeen(GuestPlayer.class);
            assertEquals(MemoryEngineContract.RESULT_OK,
                engine.researchBindFieldLocator(NEXT, saved, "Shared", 0L).code);
            assertEquals(1, engine.watchPage(NEXT).ids.length);
        } finally {
            engine.runtimeClosed(NEXT);
            MemoryDiscoveryBridge.close(NEXT);
        }
    }
}
