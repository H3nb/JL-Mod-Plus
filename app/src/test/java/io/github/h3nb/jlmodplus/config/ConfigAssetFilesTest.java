/*
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
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ConfigAssetFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File bank(File directory, String name, byte sample) throws IOException {
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[4]);
            output.write("sfbk".getBytes(StandardCharsets.US_ASCII));
            output.write(sample);
        }
        return file;
    }

    @Test public void importReusesSameFileAndSuffixesDifferentContent() throws Exception {
        File root = temporary.newFolder("workdir");
        File a = bank(temporary.newFolder("source-one"), "Bank.sf2", (byte) 1);
        File same = bank(temporary.newFolder("source-same"), "Bank.sf2", (byte) 1);
        File changed = bank(temporary.newFolder("source-changed"), "Bank.sf2", (byte) 2);

        ConfigAssetFiles.Imported first = ConfigAssetFiles.importFile(root,
                ConfigAssetFiles.Kind.SOUNDBANK, a);
        ConfigAssetFiles.Imported duplicate = ConfigAssetFiles.importFile(root,
                ConfigAssetFiles.Kind.SOUNDBANK, same);
        ConfigAssetFiles.Imported other = ConfigAssetFiles.importFile(root,
                ConfigAssetFiles.Kind.SOUNDBANK, changed);
        assertEquals("Bank.sf2", first.name);
        assertTrue(first.copied);
        assertEquals("Bank.sf2", duplicate.name);
        assertFalse(duplicate.copied);
        assertEquals("Bank (2).sf2", other.name);
        assertTrue(other.copied);
        assertEquals(13, new File(root, "soundbanks/Bank.sf2").length());
        ConfigAssetFiles.Imported inPlace = ConfigAssetFiles.importFile(root,
                ConfigAssetFiles.Kind.SOUNDBANK, new File(root, "soundbanks/Bank.sf2"));
        assertFalse(inPlace.copied);
    }

    @Test public void renamePreservesExtensionAndDoesNotOverwrite() throws Exception {
        File root = temporary.newFolder("workdir");
        ConfigAssetFiles.importFile(root, ConfigAssetFiles.Kind.SOUNDBANK,
                bank(temporary.newFolder("a"), "First.sf2", (byte) 1));
        ConfigAssetFiles.importFile(root, ConfigAssetFiles.Kind.SOUNDBANK,
                bank(temporary.newFolder("b"), "Second.sf2", (byte) 2));
        try {
            ConfigAssetFiles.rename(root, ConfigAssetFiles.Kind.SOUNDBANK, "First.sf2", "Second");
            fail("must refuse name conflict");
        } catch (IOException expected) {
            assertTrue(new File(root, "soundbanks/First.sf2").exists());
        }
        assertEquals("Renamed.sf2", ConfigAssetFiles.rename(
                root, ConfigAssetFiles.Kind.SOUNDBANK, "First.sf2", "Renamed"));
        assertTrue(new File(root, "soundbanks/Renamed.sf2").isFile());
        ConfigAssetFiles.delete(root, ConfigAssetFiles.Kind.SOUNDBANK, "Renamed.sf2");
        assertFalse(new File(root, "soundbanks/Renamed.sf2").exists());
    }


    @Test public void simultaneousImportsCannotOverwriteEachOther() throws Exception {
        File root = temporary.newFolder("concurrent");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ConfigAssetFiles.Imported>> tasks = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                final byte id = (byte) (i + 1);
                File source = bank(temporary.newFolder("concurrent-" + i), "Shared.sf2", id);
                tasks.add(pool.submit(() -> {
                    go.await();
                    return ConfigAssetFiles.importFile(root,
                            ConfigAssetFiles.Kind.SOUNDBANK, source);
                }));
            }
            go.countDown();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (java.util.concurrent.Future<ConfigAssetFiles.Imported> task : tasks) {
                assertTrue(names.add(task.get().name));
            }
            assertEquals(6, names.size());
            for (String name : names) {
                assertTrue(new File(root, "soundbanks/" + name).isFile());
            }
            assertEquals(6, new File(root, "soundbanks").listFiles().length);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void interruptedImportDoesNotPublishIncompleteFile() throws Exception {
        File root = temporary.newFolder("interrupt");
        File source = bank(temporary.newFolder("interrupt-source"), "Bank.sf2", (byte) 3);
        Thread.currentThread().interrupt();
        try {
            ConfigAssetFiles.importFile(root, ConfigAssetFiles.Kind.SOUNDBANK, source);
            fail("interrupted import must not publish");
        } catch (IOException expected) {
            assertEquals(0, ConfigAssetFiles.directory(
                    root, ConfigAssetFiles.Kind.SOUNDBANK).listFiles().length);
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void invalidFileDoesNotPublish() throws Exception {
        File root = temporary.newFolder("workdir");
        File source = temporary.newFile("Broken.sf2");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write("not a soundfont".getBytes(StandardCharsets.US_ASCII));
        }
        try {
            ConfigAssetFiles.importFile(root, ConfigAssetFiles.Kind.SOUNDBANK, source);
            fail("invalid content must be rejected");
        } catch (IOException expected) {
            assertEquals(0, ConfigAssetFiles.directory(root,
                    ConfigAssetFiles.Kind.SOUNDBANK).list().length);
        }
    }
}
