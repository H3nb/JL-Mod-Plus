/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

public class LegacyPrivateFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void copiesOnlyChosenSaveAndPreservesRmsAndOriginal() throws Exception {
        File workdir = temporary.getRoot();
        File data = new File(workdir, "data/One");
        assertTrue(data.mkdirs());
        Files.write(new File(data, "save.dat").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(data, "score.rsh").toPath(), new byte[]{4});
        Files.write(new File(data, "score.1.rsr").toPath(), new byte[]{5});
        LegacyPrivateFiles importer = new LegacyPrivateFiles(workdir, "One");
        assertEquals(Collections.singletonList("save.dat"), importer.list());
        importer.copy("save.dat");
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(data, "vendor/siemens/save.dat").toPath()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(data, "save.dat").toPath()));
        assertTrue(new File(data, "score.rsh").isFile());
        assertTrue(new File(data, "score.1.rsr").isFile());
        assertTrue(importer.list().isEmpty());
        assertThrows(IOException.class, () -> importer.copy("save.dat"));
        assertThrows(IOException.class, () -> importer.copy("score.rsh"));
        assertThrows(IOException.class, () -> importer.copy("score.1.rsr"));
        assertTrue(new LegacyPrivateFiles(workdir, "Two").list().isEmpty());
        assertFalse(new File(workdir, "storage").exists());
    }

    @Test public void rejectsTraversalLinksAndPrivateNamespaces() throws Exception {
        File workdir = temporary.getRoot();
        File data = new File(workdir, "data/One");
        assertTrue(data.mkdirs());
        File other = temporary.newFolder("outside");
        Files.write(new File(other, "save.dat").toPath(), new byte[]{9});
        Files.createSymbolicLink(new File(data, "linked").toPath(), other.toPath());
        LegacyPrivateFiles importer = new LegacyPrivateFiles(workdir, "One");
        assertTrue(importer.list().isEmpty());
        for (String name : new String[]{"../Two/save.dat", "linked/save.dat", "private/save.dat", "vendor/save.dat", "cache/save.dat"}) {
            assertThrows(IOException.class, () -> importer.copy(name));
        }
        assertThrows(IOException.class, () -> new LegacyPrivateFiles(workdir, "../Two"));
    }
}
