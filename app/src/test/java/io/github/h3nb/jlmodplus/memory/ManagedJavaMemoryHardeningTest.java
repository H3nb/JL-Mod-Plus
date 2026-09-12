/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.microedition.shell.MemoryDiscoveryBridge;

public class ManagedJavaMemoryHardeningTest {
	private static final long TOKEN = 0x48415244454e494eL;

	private ManagedJavaMemoryEngine engine;
	private Root root;

	@Before
	public void setUp() {
		root = new Root();
		engine = new ManagedJavaMemoryEngine();
		installRoot(root, Root.class);
	}

	@After
	public void tearDown() {
		MemoryDiscoveryBridge.close(TOKEN);
	}

	@Test
	public void oversizedNumericInputIsRejectedBeforeNumericParsing() {
		String oversized = "1".repeat(97);
		long[] parsed = new long[1];
		assertFalse(ManagedJavaValue.parse(oversized, MemoryEngineContract.TYPE_LONG, parsed));
		assertFalse(ManagedJavaValue.parseMagnitude(
				oversized, MemoryEngineContract.TYPE_LONG, parsed));
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
				engine.startExact(TOKEN, MemoryEngineContract.TYPE_AUTO,
						MemoryEngineContract.PREDICATE_EQUAL, oversized, "", 0L).code);
	}

	@Test
	public void dequeuedReferencesDoNotConsumeTraversalBudgetForever() {
		MemoryDiscoveryBridge.close(TOKEN);
		ChainNode chain = new ChainNode();
		ChainNode cursor = chain;
		for (int index = 1; index < 80; index++) {
			cursor.next = new ChainNode();
			cursor = cursor.next;
		}
		installRoot(chain, ChainNode.class);
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 10, 10, 100, 100, 10_500L));
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				"999", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		assertEquals(0L, result.resultCount);
	}

	@Test
	public void clearedOrRemovedFreezeCannotWriteOnLaterTicks() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				"7", "", 0L);
		long id = engine.resultPage(TOKEN, search.revision, 0, 1).ids[0];

		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.setFreezeLockTyped(TOKEN, search.revision, new long[]{id},
						"9", false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearFreeze(TOKEN, new long[]{id}, 0L).code);
		root.value = 3;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0L).code);
		assertEquals(3, root.value);

		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.setFreezeLockTyped(TOKEN, search.revision, new long[]{id},
						"11", false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.removeWatch(TOKEN, new long[]{id}, 0L).code);
		root.value = 4;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0L).code);
		assertEquals(4, root.value);
	}

	private void installRoot(Object value, Class<?> type) {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(type);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, value);
	}

	static final class Root {
		int value = 7;
	}

	static final class ChainNode {
		int value = 1;
		ChainNode next;
	}
}
