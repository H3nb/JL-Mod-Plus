/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MidletSessionStatsAckStoreTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void orphanAckIsDeletedButAckWithCanonicalJournalIsRetained() throws Exception {
		File directory = temporaryFolder.newFolder("acks");
		File retainedAck = new File(directory, "session-a.ack");
		File orphanAck = new File(directory, "session-b.ack");
		File unrelated = new File(directory, "notes.txt");
		assertTrue(retainedAck.createNewFile());
		assertTrue(orphanAck.createNewFile());
		assertTrue(unrelated.createNewFile());
		File journal = temporaryFolder.newFile("session-a.properties");

		MidletSessionStatsAckStore.pruneOrphanFiles(directory, Arrays.asList(journal));

		assertTrue(retainedAck.isFile());
		assertFalse(orphanAck.exists());
		assertTrue(unrelated.isFile());
	}

	@Test
	public void sidecarCanonicalJournalNameAlsoRetainsAck() throws Exception {
		File directory = temporaryFolder.newFolder("acks-sidecar");
		File retainedAck = new File(directory, "session-sidecar.ack");
		assertTrue(retainedAck.createNewFile());
		// journalFiles() normally canonicalizes a .bak/.new sidecar to this logical base path.
		File canonicalBase = new File(temporaryFolder.getRoot(), "session-sidecar.properties");

		MidletSessionStatsAckStore.pruneOrphanFiles(directory, Arrays.asList(canonicalBase));

		assertTrue(retainedAck.isFile());
	}
}
