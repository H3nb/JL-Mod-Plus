/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class LibraryIconOverrideTest {
	@Test
	public void installPublishesDurableAndEffectiveCopies() throws Exception {
		File root = Files.createTempDirectory("jlmod-icon-override").toFile();
		File prepared = new File(root, "prepared.png");
		byte[] contents = new byte[]{1, 2, 3, 4};
		Files.write(prepared.toPath(), contents);

		long revision = LibraryIconOverride.installPrepared(root, "demo", prepared);
		File durable = new File(root, "configs/demo/icon.custom.png");
		File effective = new File(root, "converted/demo/icon.png");

		assertTrue(durable.isFile());
		assertTrue(effective.isFile());
		assertArrayEquals(contents, Files.readAllBytes(durable.toPath()));
		assertArrayEquals(contents, Files.readAllBytes(effective.toPath()));
		assertNotEquals(0L, revision);
	}
}
