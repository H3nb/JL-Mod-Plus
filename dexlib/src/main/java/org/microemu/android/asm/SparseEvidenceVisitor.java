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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The intentionally tiny second-pass lifecycle probe transform.
 *
 * <p>This visitor does not observe fields, arrays, constructors, or method hot paths. It only
 * marks normal returns from an already-existing {@code <clinit>}; all scanner work happens later
 * in the host runtime. The caller owns the fail-open boundary and must retain the input bytes if
 * this visitor throws.</p>
 */
public final class SparseEvidenceVisitor {
    public static final String PROBE_OWNER = "javax/microedition/shell/MemoryProbe";
    public static final String PROBE_METHOD = "classInitReturned";
    public static final String PROBE_DESCRIPTOR = "(Ljava/lang/Class;I)V";

    private SparseEvidenceVisitor() {
    }

    public static byte[] instrument(
            byte[] compatibilityBytes,
            int sourceClassId,
            MemoryEditorTransformMetadata.Builder metadata) {
        if (compatibilityBytes == null) throw new IllegalArgumentException("class bytes are null");
        ClassReader reader = new ClassReader(compatibilityBytes);
        String owner = reader.getClassName();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean sawClinit;
            private int callbackCount;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<clinit>".equals(name) || !"()V".equals(descriptor)) return next;
                sawClinit = true;
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // The class literal is loader-scoped by the VM. The runtime receiver
                            // validates its loader through the weak registry before recording.
                            visitLdcInsn(Type.getObjectType(owner));
                            visitLdcInsn(sourceClassId);
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    PROBE_OWNER,
                                    PROBE_METHOD,
                                    PROBE_DESCRIPTOR,
                                    false);
                            callbackCount++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (metadata != null) metadata.markProbeInserted(sourceClassId, callbackCount);
                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }
}
