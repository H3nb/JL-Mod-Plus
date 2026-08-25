/*
 * Copyright 2026 JL-Mod Plus contributors
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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.Test;
import org.microemu.android.asm.MemoryEditorTransformMetadata;

import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;

public class MemoryEditorSessionTest {
    @Test
    public void scansDeclaredFieldsAndPrimitiveArrayAsCompactRegion() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        builder.observe(classBytes(Fixture.class), classBytes(Fixture.class), false);
        MemoryEditorSession session = new MemoryEditorSession(
                fixture, Fixture.class.getClassLoader(), builder.snapshot(), null);
        try {
            MemoryEditorSession.ScanResult result = session.scanNow(MemoryEditorSession.Query.all());
            assertFalse(result.isCancelled());
            assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                    candidate.getPath().endsWith(".value")));
            MemoryEditorSession.Candidate region = result.getCandidates().stream()
                    .filter(MemoryEditorSession.Candidate::isRegion)
                    .findFirst()
                    .orElseThrow(AssertionError::new);
            assertEquals(4, region.getRegionLength());
            assertEquals("2", region.readRegionElement(1));
            MemoryEditorSession.WriteResult write = session.writeRegionElementNow(
                    region.getId(), 1, "9");
            assertTrue(write.isSuccess());
            assertEquals(9, fixture.bytes[1]);
        } finally {
            session.close();
        }
    }

    @Test
    public void refinesPrimitiveArrayRegionAtEveryChangedIndex() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        builder.observe(classBytes(Fixture.class), classBytes(Fixture.class), false);
        MemoryEditorSession session = new MemoryEditorSession(
                fixture, Fixture.class.getClassLoader(), builder.snapshot(), null);
        try {
            MemoryEditorSession.ScanResult initial = session.scanNow(MemoryEditorSession.Query.all());
            MemoryEditorSession.Candidate region = initial.getCandidates().stream()
                    .filter(MemoryEditorSession.Candidate::isRegion)
                    .findFirst()
                    .orElseThrow(AssertionError::new);
            fixture.bytes[2] = 9;
            MemoryEditorSession.ScanResult changed = session.scanNow(MemoryEditorSession.Query.changed());
            assertTrue(changed.getCandidates().stream().anyMatch(candidate ->
                    candidate.getPath().contains("[2]") && "9".equals(candidate.getValue())));
            assertFalse(changed.getCandidates().stream().anyMatch(candidate ->
                    candidate.getId() == region.getId()));
        } finally {
            session.close();
        }
    }

    @Test
    public void freezeReappliesDesiredValueAfterRuntimeMutation() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        builder.observe(classBytes(Fixture.class), classBytes(Fixture.class), false);
        MemoryEditorSession session = new MemoryEditorSession(
                fixture, Fixture.class.getClassLoader(), builder.snapshot(), null);
        try {
            MemoryEditorSession.Candidate value = session.scanNow(MemoryEditorSession.Query.all())
                    .getCandidates().stream()
                    .filter(candidate -> candidate.getPath().endsWith(".value"))
                    .findFirst()
                    .orElseThrow(AssertionError::new);
            assertTrue(session.freeze(value.getId()));
            fixture.value = 99;
            Thread.sleep(100L);
            assertEquals(1, fixture.value);
            assertTrue(session.isFrozen(value.getId()));
        } finally {
            session.close();
        }
    }

    @Test
    public void searchSessionRemainsAvailableAfterAResultIsReturned() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        builder.observe(classBytes(Fixture.class), classBytes(Fixture.class), false);
        MemoryEditorSession session = new MemoryEditorSession(
                fixture, Fixture.class.getClassLoader(), builder.snapshot(), null);
        try {
            MemoryEditorSession.ScanResult result = session.scanNow(MemoryEditorSession.Query.all());
            assertTrue(session.hasSearchSession());
            assertEquals(result, session.getLastScanResult());
            assertEquals(MemoryEditorSession.SearchMode.ALL, session.getLastQuery().getMode());
            assertFalse(session.getLastScanResult().getCandidates().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void staticFieldRequiresObservedClinitEvidence() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        int classId = builder.observe(classBytes(Fixture.class), classBytes(Fixture.class), false);
        builder.markProbeInserted(classId, 1);
        MemoryEditorTransformMetadata metadata = builder.snapshot();
        MemoryProbe.Ledger ledger = MemoryProbe.bind(Fixture.class.getClassLoader(), new int[]{classId});
        try {
            MemoryEditorSession withoutEvidence = new MemoryEditorSession(
                    fixture, Fixture.class.getClassLoader(), metadata, ledger);
            try {
                MemoryEditorSession.ScanResult result = withoutEvidence.scanNow(MemoryEditorSession.Query.all());
                assertTrue(result.getDiagnostics().stream().anyMatch(value ->
                        value.startsWith("STATIC_INIT_UNKNOWN:")));
            } finally {
                withoutEvidence.close();
            }
            MemoryProbe.classInitReturned(Fixture.class, classId);
            MemoryEditorSession withEvidence = new MemoryEditorSession(
                    fixture, Fixture.class.getClassLoader(), metadata, ledger);
            try {
                assertTrue(withEvidence.scanNow(MemoryEditorSession.Query.all()).getCandidates().stream()
                        .anyMatch(candidate -> candidate.getPath().contains("staticValue")));
            } finally {
                withEvidence.close();
            }
        } finally {
            MemoryProbe.unbind(Fixture.class.getClassLoader(), ledger);
        }
    }

    @Test
    public void observedLoadedStaticClassIsASeparateDiscoveryRoot() throws Exception {
        Fixture fixture = new Fixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        int classId = builder.observe(
                classBytes(HiddenStatic.class), classBytes(HiddenStatic.class), false);
        builder.markProbeInserted(classId, 1);
        MemoryEditorTransformMetadata metadata = builder.snapshot();
        MemoryProbe.Ledger ledger = MemoryProbe.bind(
                HiddenStatic.class.getClassLoader(), new int[]{classId});
        try {
            MemoryProbe.classInitReturned(HiddenStatic.class, classId);
            String hiddenName = HiddenStatic.class.getName().replace('.', '/');
            MemoryEditorSession session = new MemoryEditorSession(
                    fixture,
                    HiddenStatic.class.getClassLoader(),
                    metadata,
                    ledger,
                    name -> hiddenName.equals(name) ? HiddenStatic.class : null);
            try {
                assertTrue(session.scanNow(MemoryEditorSession.Query.all()).getCandidates().stream()
                        .anyMatch(candidate -> candidate.getPath().contains("hiddenValue")));
            } finally {
                session.close();
            }
        } finally {
            MemoryProbe.unbind(HiddenStatic.class.getClassLoader(), ledger);
        }
    }

    @Test
    public void hostDisplayableBridgeReachesApplicationOwnedCommandListener() throws Exception {
        HostFixture fixture = new HostFixture();
        MemoryEditorTransformMetadata.Builder builder = new MemoryEditorTransformMetadata.Builder();
        builder.observe(classBytes(HostFixture.class), classBytes(HostFixture.class), false);
        builder.observe(classBytes(HiddenCommandListener.class),
                classBytes(HiddenCommandListener.class), false);
        MemoryEditorSession session = new MemoryEditorSession(
                fixture, HostFixture.class.getClassLoader(), builder.snapshot(), null);
        try {
            assertTrue(session.scanNow(MemoryEditorSession.Query.all()).getCandidates().stream()
                    .anyMatch(candidate -> candidate.getPath().contains("hiddenValue")));
        } finally {
            session.close();
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException(resource);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) != -1; ) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    public static final class Fixture extends MIDlet {
        int value = 1;
        Integer boxed = 7;
        byte[] bytes = new byte[]{1, 2, 3, 4};
        static int staticValue;

        static {
            staticValue = 11;
        }

        @Override
        public void startApp() throws MIDletStateChangeException {
        }

        @Override
        public void pauseApp() {
        }

        @Override
        public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        }
    }

    public static final class HiddenStatic {
        static int hiddenValue = 23;
    }

    public static final class HostFixture extends MIDlet {
        final Displayable displayable;

        HostFixture() {
            displayable = new Displayable() {
            };
            displayable.setCommandListener(new HiddenCommandListener());
        }

        @Override
        public void startApp() throws MIDletStateChangeException {
        }

        @Override
        public void pauseApp() {
        }

        @Override
        public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        }
    }

    public static final class HiddenCommandListener implements CommandListener {
        int hiddenValue = 29;

        @Override
        public void commandAction(Command command, Displayable displayable) {
        }
    }
}
