/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.microedition.shell.MemoryDiscoveryBridge;

final class ManagedJavaBooleanTest {
    private static final long TOKEN = 0x424F4F4C45414EL;
    private final Flags root = new Flags();
    private final ManagedJavaMemoryEngine engine = new ManagedJavaMemoryEngine();

    static final class Flags {
        boolean enabled = true;
        boolean[] toggles = {false, true};
        static boolean globalEnabled = true;
    }

    @BeforeEach void start() {
        MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
        MemoryDiscoveryBridge.tailSeen(Flags.class);
        MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
    }

    @AfterEach void stop() {
        engine.runtimeClosed(TOKEN);
        MemoryDiscoveryBridge.close(TOKEN);
    }

    private long fieldId(ManagedJavaMemoryEngine.ManagedPage page, String name) {
        for (int i = 0; i < page.ids.length; i++) {
            if (name.equals(page.sourceFields[i])) return page.ids[i];
        }
        throw new AssertionError("No Boolean field named " + name);
    }

    @Test void explicitBooleanSearchAndFreezeUseTypedTargets() {
        ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_BOOLEAN, MemoryEngineContract.PREDICATE_EQUAL,
                "true", "", 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, search.code);
        ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
        long id = fieldId(page, "enabled");
        boolean arraySeen = false;
        for (int i = 0; i < page.ids.length; i++) {
            if (ManagedJavaMemoryIds.kind(page.ids[i]) == ManagedJavaMemoryEngine.KIND_ARRAY
                    && ManagedJavaMemoryIds.slot(page.ids[i]) == 1
                    && page.types[i] == MemoryEngineContract.TYPE_BOOLEAN
                    && "true".equals(page.values[i])) arraySeen = true;
        }
        assertTrue(arraySeen, "Boolean array elements must be scanned by their own type");
        assertEquals(MemoryEngineContract.RESULT_OK, engine.editTyped(TOKEN, search.revision,
                new long[]{id}, "false", false, 0L).code);
        assertFalse(root.enabled);
        assertEquals(MemoryEngineContract.RESULT_OK, engine.setFreezeLockTyped(TOKEN,
                search.revision, new long[]{id}, "true", false, 0L).code);
        assertFalse(root.enabled);
        assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0L).code);
        assertTrue(root.enabled);

        ManagedJavaMemoryEngine.SavedLocator locator =
                engine.captureSavedLocator(TOKEN, search.revision, id);
        assertNotNull(locator);
        assertEquals(MemoryEngineContract.RESULT_OK,
                engine.resolveSavedLocator(TOKEN, locator, "enabled", 0L).code);
        assertEquals("true", engine.readSaved(TOKEN, "enabled").value);
        assertEquals(MemoryEngineContract.RESULT_OK,
                engine.editSaved(TOKEN, "enabled", "false", 0L).code);
        assertFalse(root.enabled);
    }

    @Test void changedBooleanRefineWorksWithoutPollutingNumericAuto() {
        ManagedJavaMemoryEngine.ManagedOperationResult auto = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
                "1", "", 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, auto.code);
        assertEquals(0L, auto.resultCount, "Numeric Auto must not implicitly scan Boolean flags");

        ManagedJavaMemoryEngine.ManagedOperationResult unknown =
                engine.startUnknown(TOKEN, MemoryEngineContract.TYPE_BOOLEAN, 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
        root.enabled = false;
        ManagedJavaMemoryEngine.ManagedOperationResult changed = engine.refine(TOKEN,
                unknown.revision, MemoryEngineContract.TYPE_BOOLEAN,
                MemoryEngineContract.PREDICATE_CHANGED, MemoryEngineContract.COMPARE_PREVIOUS,
                0L, 0L, 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, changed.code);
        fieldId(engine.resultPage(TOKEN, changed.revision, 0, 100), "enabled");
    }

    @Test void booleanPredicatesAndValuesAreRestrictedToLogicalOperations() {
        long[] bits = new long[1];
        assertTrue(ManagedJavaValue.parse("true", MemoryEngineContract.TYPE_BOOLEAN, bits));
        assertEquals(1L, bits[0]);
        assertTrue(ManagedJavaValue.parse("FALSE", MemoryEngineContract.TYPE_BOOLEAN, bits));
        assertEquals(0L, bits[0]);
        assertFalse(ManagedJavaValue.parse("1", MemoryEngineContract.TYPE_BOOLEAN, bits));
        assertFalse(ManagedJavaValue.validKnownQuery(MemoryEngineContract.TYPE_BOOLEAN,
                MemoryEngineContract.PREDICATE_GREATER, 1L, 0L));
        assertFalse(ManagedJavaValue.validRelativeQuery(MemoryEngineContract.TYPE_BOOLEAN,
                MemoryEngineContract.PREDICATE_INCREASED, 0L, 0L));
    }
}
