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

package org.microemu.android.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Versioned, class-level metadata shared by the converter and the runtime Memory Editor.
 *
 * <p>The metadata is deliberately a small binary sidecar instead of a Java-serialized object.
 * It is written into the same installer staging directory as the converted payload, so a
 * published payload always has either its matching metadata or neither. The format contains
 * source-JAR declarations and the post-compatibility runtime shape separately; the latter is
 * what runtime binding is allowed to trust.</p>
 */
public final class MemoryEditorTransformMetadata {
    public static final int SCHEMA_VERSION = 1;
    public static final int PROBE_ABI_VERSION = 1;
    public static final String MAGIC = "JLMEMMETA";

    public static final String PROBE_NOT_ATTEMPTED = "NOT_ATTEMPTED";
    public static final String PROBE_NO_CLINIT = "NO_CLINIT";
    public static final String PROBE_NO_NORMAL_RETURN = "NO_NORMAL_RETURN";
    public static final String PROBE_INSERTED = "INSERTED";
    public static final String PROBE_SKIPPED = "PROBE_SKIPPED";

    private final int schemaVersion;
    private final int probeAbiVersion;
    private final String sourceJarHash;
    private final List<ClassEntry> classes;

    private MemoryEditorTransformMetadata(
            int schemaVersion,
            int probeAbiVersion,
            String sourceJarHash,
            List<ClassEntry> classes) {
        this.schemaVersion = schemaVersion;
        this.probeAbiVersion = probeAbiVersion;
        this.sourceJarHash = sourceJarHash == null ? "" : sourceJarHash;
        this.classes = Collections.unmodifiableList(new ArrayList<>(classes));
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public int getProbeAbiVersion() {
        return probeAbiVersion;
    }

    public String getSourceJarHash() {
        return sourceJarHash;
    }

    public List<ClassEntry> getClasses() {
        return classes;
    }

    public boolean isCompatible() {
        return schemaVersion == SCHEMA_VERSION && probeAbiVersion == PROBE_ABI_VERSION;
    }

    /** Returns the class IDs which have an active sparse probe in this artifact. */
    public int[] getInsertedProbeClassIds() {
        int count = 0;
        for (ClassEntry entry : classes) {
            if (PROBE_INSERTED.equals(entry.getProbeStatus())) count++;
        }
        int[] result = new int[count];
        int index = 0;
        for (ClassEntry entry : classes) {
            if (PROBE_INSERTED.equals(entry.getProbeStatus())) {
                result[index++] = entry.getSourceClassId();
            }
        }
        return result;
    }

    public static MemoryEditorTransformMetadata empty() {
        return new MemoryEditorTransformMetadata(
                SCHEMA_VERSION,
                PROBE_ABI_VERSION,
                "",
                Collections.emptyList());
    }

    /** Reads a sidecar, rejecting unknown magic or schema instead of guessing its meaning. */
    public static MemoryEditorTransformMetadata read(File file) throws IOException {
        if (file == null) throw new NullPointerException("file");
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            String magic = input.readUTF();
            if (!MAGIC.equals(magic)) {
                throw new IOException("Unknown Memory Editor metadata magic");
            }
            int schemaVersion = input.readInt();
            int probeAbiVersion = input.readInt();
            String sourceJarHash = input.readUTF();
            int classCount = input.readInt();
            if (classCount < 0 || classCount > 1_000_000) {
                throw new IOException("Invalid Memory Editor class count: " + classCount);
            }
            List<ClassEntry> classes = new ArrayList<>(classCount);
            for (int i = 0; i < classCount; i++) {
                int sourceClassId = input.readInt();
                String sourceName = input.readUTF();
                String runtimeName = input.readUTF();
                boolean patchApplied = input.readBoolean();
                boolean sourceHasClinit = input.readBoolean();
                String probeStatus = input.readUTF();
                String probeReason = readNullable(input);
                int fieldCount = input.readInt();
                if (fieldCount < 0 || fieldCount > 1_000_000) {
                    throw new IOException("Invalid Memory Editor field count: " + fieldCount);
                }
                List<FieldEntry> fields = new ArrayList<>(fieldCount);
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    fields.add(new FieldEntry(
                            input.readInt(),
                            input.readUTF(),
                            readNullable(input),
                            readNullable(input),
                            input.readInt(),
                            input.readInt()));
                }
                classes.add(new ClassEntry(
                        sourceClassId,
                        sourceName,
                        runtimeName,
                        patchApplied,
                        sourceHasClinit,
                        probeStatus,
                        probeReason,
                        fields));
            }
            return new MemoryEditorTransformMetadata(
                    schemaVersion,
                    probeAbiVersion,
                    sourceJarHash,
                    classes);
        }
    }

    private static String readNullable(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeUTF(value);
    }

    public static String sha256(File file) throws IOException {
        if (file == null) throw new NullPointerException("file");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                int unsigned = value & 0xff;
                result.append(Character.forDigit(unsigned >>> 4, 16));
                result.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return result.toString();
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to hash Memory Editor source", error);
        }
    }

    public static final class ClassEntry {
        private final int sourceClassId;
        private final String sourceInternalName;
        private final String runtimeInternalName;
        private final boolean patchApplied;
        private final boolean sourceHasClinit;
        private final String probeStatus;
        private final String probeReason;
        private final List<FieldEntry> fields;

        private ClassEntry(
                int sourceClassId,
                String sourceInternalName,
                String runtimeInternalName,
                boolean patchApplied,
                boolean sourceHasClinit,
                String probeStatus,
                String probeReason,
                List<FieldEntry> fields) {
            this.sourceClassId = sourceClassId;
            this.sourceInternalName = sourceInternalName;
            this.runtimeInternalName = runtimeInternalName;
            this.patchApplied = patchApplied;
            this.sourceHasClinit = sourceHasClinit;
            this.probeStatus = probeStatus;
            this.probeReason = probeReason;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }

        public int getSourceClassId() {
            return sourceClassId;
        }

        public String getSourceInternalName() {
            return sourceInternalName;
        }

        public String getRuntimeInternalName() {
            return runtimeInternalName;
        }

        public boolean isPatchApplied() {
            return patchApplied;
        }

        public boolean hasSourceClinit() {
            return sourceHasClinit;
        }

        public String getProbeStatus() {
            return probeStatus;
        }

        public String getProbeReason() {
            return probeReason;
        }

        public List<FieldEntry> getFields() {
            return fields;
        }
    }

    public static final class FieldEntry {
        private final int sourceFieldId;
        private final String name;
        private final String sourceDescriptor;
        private final String runtimeDescriptor;
        private final int sourceAccess;
        private final int runtimeAccess;

        private FieldEntry(
                int sourceFieldId,
                String name,
                String sourceDescriptor,
                String runtimeDescriptor,
                int sourceAccess,
                int runtimeAccess) {
            this.sourceFieldId = sourceFieldId;
            this.name = name;
            this.sourceDescriptor = sourceDescriptor;
            this.runtimeDescriptor = runtimeDescriptor;
            this.sourceAccess = sourceAccess;
            this.runtimeAccess = runtimeAccess;
        }

        public int getSourceFieldId() {
            return sourceFieldId;
        }

        public String getName() {
            return name;
        }

        public String getSourceDescriptor() {
            return sourceDescriptor;
        }

        public String getRuntimeDescriptor() {
            return runtimeDescriptor;
        }

        public int getSourceAccess() {
            return sourceAccess;
        }

        public int getRuntimeAccess() {
            return runtimeAccess;
        }
    }

    /** Mutable conversion-local collector. It is never shared between Main invocations. */
    public static final class Builder {
        private final TreeMap<String, MutableClassEntry> classes = new TreeMap<>();
        private String sourceJarHash = "";

        public synchronized void setSourceJarHash(String sourceJarHash) {
            this.sourceJarHash = sourceJarHash == null ? "" : sourceJarHash;
        }

        public synchronized int observe(byte[] sourceBytes, byte[] runtimeBytes, boolean patchApplied) {
            ParsedClass source = parse(sourceBytes);
            ParsedClass runtime = parse(runtimeBytes);
            String sourceName = source.name != null ? source.name : runtime.name;
            if (sourceName == null) {
                sourceName = "<unknown>";
            }
            int sourceClassId = stableClassId(sourceName);
            MutableClassEntry entry = new MutableClassEntry(
                    sourceClassId,
                    sourceName,
                    runtime.name == null ? sourceName : runtime.name,
                    patchApplied,
                    source.hasClinit,
                    source.fields,
                    runtime.fields);
            classes.put(sourceName, entry);
            return sourceClassId;
        }

        public synchronized int sourceClassId(String sourceInternalName) {
            return stableClassId(sourceInternalName);
        }

        public synchronized void markProbeInserted(int sourceClassId, int callbackCount) {
            MutableClassEntry entry = findById(sourceClassId);
            if (entry == null) return;
            if (callbackCount > 0) {
                entry.probeStatus = PROBE_INSERTED;
                entry.probeReason = null;
            } else {
                entry.probeStatus = entry.sourceHasClinit
                        ? PROBE_NO_NORMAL_RETURN : PROBE_NO_CLINIT;
                entry.probeReason = null;
            }
        }

        public synchronized void markProbeSkipped(int sourceClassId, String reason) {
            MutableClassEntry entry = findById(sourceClassId);
            if (entry == null) return;
            entry.probeStatus = PROBE_SKIPPED;
            entry.probeReason = reason == null || reason.isEmpty() ? "unknown" : reason;
        }

        public synchronized MemoryEditorTransformMetadata snapshot() {
            List<ClassEntry> result = new ArrayList<>(classes.size());
            for (MutableClassEntry entry : classes.values()) {
                result.add(entry.freeze());
            }
            return new MemoryEditorTransformMetadata(
                    SCHEMA_VERSION,
                    PROBE_ABI_VERSION,
                    sourceJarHash,
                    result);
        }

        public synchronized void writeTo(File file) throws IOException {
            if (file == null) throw new NullPointerException("file");
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Unable to create Memory Editor metadata directory: " + parent);
            }
            MemoryEditorTransformMetadata metadata = snapshot();
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(file)))) {
                output.writeUTF(MAGIC);
                output.writeInt(metadata.schemaVersion);
                output.writeInt(metadata.probeAbiVersion);
                output.writeUTF(metadata.sourceJarHash);
                output.writeInt(metadata.classes.size());
                for (ClassEntry entry : metadata.classes) {
                    output.writeInt(entry.sourceClassId);
                    output.writeUTF(entry.sourceInternalName);
                    output.writeUTF(entry.runtimeInternalName);
                    output.writeBoolean(entry.patchApplied);
                    output.writeBoolean(entry.sourceHasClinit);
                    output.writeUTF(entry.probeStatus);
                    writeNullable(output, entry.probeReason);
                    output.writeInt(entry.fields.size());
                    for (FieldEntry field : entry.fields) {
                        output.writeInt(field.sourceFieldId);
                        output.writeUTF(field.name);
                        writeNullable(output, field.sourceDescriptor);
                        writeNullable(output, field.runtimeDescriptor);
                        output.writeInt(field.sourceAccess);
                        output.writeInt(field.runtimeAccess);
                    }
                }
            }
        }

        private MutableClassEntry findById(int sourceClassId) {
            for (MutableClassEntry entry : classes.values()) {
                if (entry.sourceClassId == sourceClassId) return entry;
            }
            return null;
        }
    }

    private static int stableClassId(String internalName) {
        if (internalName == null) return 1;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int id = ((hash[0] & 0xff) << 24)
                    | ((hash[1] & 0xff) << 16)
                    | ((hash[2] & 0xff) << 8)
                    | (hash[3] & 0xff);
            id &= 0x7fffffff;
            return id == 0 ? 1 : id;
        } catch (Exception error) {
            throw new AssertionError("SHA-256 unavailable", error);
        }
    }

    private static final class MutableClassEntry {
        private final int sourceClassId;
        private final String sourceInternalName;
        private final String runtimeInternalName;
        private final boolean patchApplied;
        private final boolean sourceHasClinit;
        private final List<FieldEntry> fields;
        private String probeStatus;
        private String probeReason;

        private MutableClassEntry(
                int sourceClassId,
                String sourceInternalName,
                String runtimeInternalName,
                boolean patchApplied,
                boolean sourceHasClinit,
                List<ParsedField> sourceFields,
                List<ParsedField> runtimeFields) {
            this.sourceClassId = sourceClassId;
            this.sourceInternalName = sourceInternalName;
            this.runtimeInternalName = runtimeInternalName;
            this.patchApplied = patchApplied;
            this.sourceHasClinit = sourceHasClinit;
            this.fields = mergeFields(sourceClassId, sourceFields, runtimeFields);
            this.probeStatus = sourceHasClinit ? PROBE_NOT_ATTEMPTED : PROBE_NO_CLINIT;
        }

        private ClassEntry freeze() {
            return new ClassEntry(
                    sourceClassId,
                    sourceInternalName,
                    runtimeInternalName,
                    patchApplied,
                    sourceHasClinit,
                    probeStatus,
                    probeReason,
                    fields);
        }
    }

    private static List<FieldEntry> mergeFields(
            int sourceClassId,
            List<ParsedField> sourceFields,
            List<ParsedField> runtimeFields) {
        Map<String, ParsedField> runtimeByName = new LinkedHashMap<>();
        for (ParsedField field : runtimeFields) runtimeByName.put(field.name, field);
        List<FieldEntry> result = new ArrayList<>(sourceFields.size());
        for (ParsedField source : sourceFields) {
            ParsedField runtime = runtimeByName.get(source.name);
            int fieldId = stableFieldId(sourceClassId, source.name, source.descriptor);
            result.add(new FieldEntry(
                    fieldId,
                    source.name,
                    source.descriptor,
                    runtime == null ? null : runtime.descriptor,
                    source.access,
                    runtime == null ? 0 : runtime.access));
        }
        Collections.sort(result, new Comparator<FieldEntry>() {
            @Override
            public int compare(FieldEntry left, FieldEntry right) {
                int nameComparison = String.valueOf(left.getName())
                        .compareTo(String.valueOf(right.getName()));
                if (nameComparison != 0) return nameComparison;
                return String.valueOf(left.getSourceDescriptor())
                        .compareTo(String.valueOf(right.getSourceDescriptor()));
            }
        });
        return result;
    }

    private static int stableFieldId(int classId, String name, String descriptor) {
        int hash = 31 * classId + (name == null ? 0 : name.hashCode());
        hash = 31 * hash + (descriptor == null ? 0 : descriptor.hashCode());
        hash &= 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }

    private static ParsedClass parse(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("class bytes are null");
        ParsedClass result = new ParsedClass();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                result.name = name;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                result.fields.add(new ParsedField(name, descriptor, access));
                return null;
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                if ("<clinit>".equals(name) && "()V".equals(descriptor)) result.hasClinit = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static final class ParsedClass {
        private String name;
        private boolean hasClinit;
        private final List<ParsedField> fields = new ArrayList<>();
    }

    private static final class ParsedField {
        private final String name;
        private final String descriptor;
        private final int access;

        private ParsedField(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }
    }
}
