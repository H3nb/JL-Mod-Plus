/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.rms;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RmsSnapshotManagerTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void snapshotsAreBoundedAndRestoreVerifiesContent() throws Exception {
		File root = temporaryFolder.newFolder("snapshots");
		File rms = temporaryFolder.newFolder("rms");
		File record = new File(rms, "scores.rsh");
		Files.write(record.toPath(), "one".getBytes(StandardCharsets.UTF_8));
		RmsSnapshotManager.create(rms, root, "first");
		Files.write(record.toPath(), "two".getBytes(StandardCharsets.UTF_8));
		RmsSnapshotManager.create(rms, root, "second");
		Files.write(record.toPath(), "three".getBytes(StandardCharsets.UTF_8));
		RmsSnapshotManager.create(rms, root, "third");

		List<RmsSnapshotManager.Snapshot> snapshots = RmsSnapshotManager.list(root);
		assertEquals(RmsSnapshotManager.MAX_SNAPSHOTS, snapshots.size());
		RmsSnapshotManager.Snapshot second = snapshots.stream()
				.filter(snapshot -> "second".equals(snapshot.label))
				.findFirst()
				.orElseThrow();
		RmsSnapshotManager.restore(second, rms);
		assertEquals("two", new String(Files.readAllBytes(record.toPath()),
				StandardCharsets.UTF_8));
	}

	@Test
	public void corruptArchiveIsNotListedOrRestored() throws Exception {
		File root = temporaryFolder.newFolder("corrupt");
		File rms = temporaryFolder.newFolder("rms-corrupt");
		Files.write(new File(root, "broken.zip").toPath(), new byte[]{1, 2, 3});
		assertTrue(RmsSnapshotManager.list(root).isEmpty());
		assertFalse(new File(rms, "missing.rsh").exists());
	}

	@Test
	public void oversizedArchiveIsNotOfferedAsRestorePoint() throws Exception {
		File root = temporaryFolder.newFolder("oversized");
		File oversized = new File(root, "oversized.zip");
		try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(oversized, "rw")) {
			file.setLength(RmsSnapshotManager.MAX_SNAPSHOT_BYTES + 1);
		}

		assertTrue(RmsSnapshotManager.list(root).isEmpty());
	}

	@Test
	public void creatingSnapshotRemovesCorruptArchivesFromTheBoundedStore()
			throws Exception {
		File root = temporaryFolder.newFolder("cleanup-corrupt");
		File corrupt = new File(root, "broken.zip");
		Files.write(corrupt.toPath(), new byte[]{1, 2, 3});
		File rms = temporaryFolder.newFolder("cleanup-rms");
		Files.write(new File(rms, "save.rms").toPath(),
				"safe".getBytes(StandardCharsets.UTF_8));

		RmsSnapshotManager.create(rms, root, "valid");

		assertFalse(corrupt.exists());
		assertEquals(1, RmsSnapshotManager.list(root).size());
	}

	@Test
	public void restoreKeepsAutomaticBackupOfTheReplacedSave() throws Exception {
		File root = temporaryFolder.newFolder("restore-safety");
		File rms = temporaryFolder.newFolder("restore-safety-rms");
		File record = new File(rms, "save.rms");
		Files.write(record.toPath(), "old".getBytes(StandardCharsets.UTF_8));
		RmsSnapshotManager.Snapshot old = RmsSnapshotManager.create(rms, root, "old");
		Files.write(record.toPath(), "current".getBytes(StandardCharsets.UTF_8));

		RmsSnapshotManager.Snapshot safety = RmsSnapshotManager.restoreWithBackup(
				old, rms, root, "before restore");
		assertEquals("old", new String(Files.readAllBytes(record.toPath()),
				StandardCharsets.UTF_8));

		RmsSnapshotManager.restore(safety, rms);
		assertEquals("current", new String(Files.readAllBytes(record.toPath()),
				StandardCharsets.UTF_8));
		assertEquals(RmsSnapshotManager.MAX_SNAPSHOTS,
				RmsSnapshotManager.list(root).size());
	}
}
