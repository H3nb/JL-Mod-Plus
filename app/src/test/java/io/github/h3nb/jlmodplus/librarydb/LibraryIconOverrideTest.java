/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class LibraryIconOverrideTest {
    @Test
    public void revisionAdvancesWithoutReusingPreviousCacheKeys() {
        long revision = LibraryIconRevision.fromFile(new File("missing.png"));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        assertTrue(seen.add(revision));
        for (int i = 0; i < 256; i++) {
            revision = LibraryIconRevision.nextAfterEdit(revision);
            assertTrue("Artwork edit reused an old revision", seen.add(revision));
        }
    }

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
    @Test
    public void portraitOverrideDoesNotReplaceCanonicalIconAndCanBeRemoved() throws Exception {
        File root = Files.createTempDirectory("jlmod-portrait-override").toFile();
        File source = new File(root, "artwork.png");
        File canonical = new File(root, "converted/demo/icon.png");
        assertTrue(canonical.getParentFile().mkdirs());
        byte[] primary = new byte[]{3, 2, 1};
        byte[] artwork = new byte[]{9, 8, 7};
        Files.write(canonical.toPath(), primary);
        Files.write(source.toPath(), artwork);

        long revision = LibraryIconOverride.installPortraitPrepared(root, "demo", source);
        File portrait = LibraryIconOverride.portraitOverrideFile(root, "demo");

        assertNotEquals(0L, revision);
        assertTrue(portrait.isFile());
        assertArrayEquals(artwork, Files.readAllBytes(portrait.toPath()));
        assertArrayEquals(primary, Files.readAllBytes(canonical.toPath()));
        LibraryIconOverride.resetPortrait(root, "demo");
        assertFalse(portrait.exists());
        assertArrayEquals(primary, Files.readAllBytes(canonical.toPath()));
    }

    @Test
    public void draftPublishCanRestoreBothMainAndPortraitAfterMetadataFailure() throws Exception {
        File root = Files.createTempDirectory("jlmod-icon-draft").toFile();
        File cache = new File(root, "cache");
        assertTrue(cache.mkdirs());
        File staged = new File(cache, "staged.png");
        Files.write(staged.toPath(), new byte[]{7, 7, 7});

        File effective = LibraryIconOverride.effectiveIconFile(root, "demo");
        File durable = LibraryIconOverride.persistedOverrideFile(root, "demo");
        File portrait = LibraryIconOverride.portraitOverrideFile(root, "demo");
        assertTrue(effective.getParentFile().mkdirs());
        assertTrue(durable.getParentFile().mkdirs());
        Files.write(effective.toPath(), new byte[]{1, 2});
        Files.write(durable.toPath(), new byte[]{1, 2});
        Files.write(portrait.toPath(), new byte[]{3, 4});

        LibraryIconOverride.PublishedDrafts published = LibraryIconOverride.publishDrafts(
                root, "demo", cache,
                new LibraryIconDraft(false, staged.getAbsolutePath()),
                new LibraryIconDraft(true, null));
        try {
            assertArrayEquals(new byte[]{7, 7, 7}, Files.readAllBytes(effective.toPath()));
            assertArrayEquals(new byte[]{7, 7, 7}, Files.readAllBytes(durable.toPath()));
            assertFalse(portrait.exists());
            published.restore();
        } finally {
            published.close();
        }
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(effective.toPath()));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(durable.toPath()));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(portrait.toPath()));
    }

    @Test
    public void invalidSecondDraftDoesNotPartiallyPublishFirstIcon() throws Exception {
        File root = Files.createTempDirectory("jlmod-icon-draft-error").toFile();
        File cache = new File(root, "cache");
        assertTrue(cache.mkdirs());
        File staged = new File(cache, "main.png");
        Files.write(staged.toPath(), new byte[]{8, 9});
        File effective = LibraryIconOverride.effectiveIconFile(root, "demo");
        File durable = LibraryIconOverride.persistedOverrideFile(root, "demo");
        assertTrue(effective.getParentFile().mkdirs());
        assertTrue(durable.getParentFile().mkdirs());
        Files.write(effective.toPath(), new byte[]{1});
        Files.write(durable.toPath(), new byte[]{1});

        boolean failed = false;
        try {
            LibraryIconOverride.publishDrafts(
                    root, "demo", cache,
                    new LibraryIconDraft(false, staged.getAbsolutePath()),
                    new LibraryIconDraft(false, new File(root, "outside.png").getAbsolutePath()));
        } catch (java.io.IOException expected) {
            failed = true;
        }
        assertTrue("Invalid portrait staging should fail", failed);
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(effective.toPath()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(durable.toPath()));
    }
}
