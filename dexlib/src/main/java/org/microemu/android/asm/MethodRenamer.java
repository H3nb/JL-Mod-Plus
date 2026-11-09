/*
 * Copyright 2026 JL-Mod Plus contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package org.microemu.android.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Conversion-local rename map for CLDC Thread subclass methods that collide with final
 * Android Thread methods. Apply to both declarations and references before dex assembly.
 */
public final class MethodRenamer {
    private static final String THREAD = "java/lang/Thread";
    private static final String SUFFIX = "$j2me";
    private static final Set<String> CONFLICTS = new HashSet<>(Arrays.asList(
            "resume()V", "suspend()V", "stop()V",
            "stop(Ljava/lang/Throwable;)V", "destroy()V"));

    private final Map<String, Map<String, String>> names;

    private MethodRenamer(Map<String, Map<String, String>> names) {
        this.names = names;
    }

    public String renamed(String owner, String name, String descriptor) {
        Map<String, String> entries = names.get(owner);
        return entries == null ? null : entries.get(name + descriptor);
    }

    /** Only guest methods are rewritten; java/lang/Thread and unrelated classes are untouched. */
    public ClassVisitor wrap(ClassVisitor downstream) {
        return new ClassVisitor(Opcodes.ASM9, downstream) {
            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String replacement = renamed(owner, name, descriptor);
                MethodVisitor next = super.visitMethod(access,
                        replacement == null ? name : replacement, descriptor, signature, exceptions);
                if (next == null) return null;
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitMethodInsn(int opcode, String target, String method,
                                                String desc, boolean itf) {
                        String replacement = renamed(target, method, desc);
                        super.visitMethodInsn(opcode, target,
                                replacement == null ? method : replacement, desc, itf);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(mapConstant(value));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrap, Object... arguments) {
                        Object[] mapped = new Object[arguments.length];
                        for (int i = 0; i < arguments.length; i++) {
                            mapped[i] = mapConstant(arguments[i]);
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, mapHandle(bootstrap), mapped);
                    }
                };
            }
        };
    }

    private Handle mapHandle(Handle handle) {
        if (handle.getTag() < Opcodes.H_INVOKEVIRTUAL) return handle;
        String replacement = renamed(handle.getOwner(), handle.getName(), handle.getDesc());
        if (replacement == null) return handle;
        return new Handle(handle.getTag(), handle.getOwner(), replacement,
                handle.getDesc(), handle.isInterface());
    }

    private Object mapConstant(Object value) {
        if (value instanceof Handle handle) return mapHandle(handle);
        if (value instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = mapConstant(dynamic.getBootstrapMethodArgument(i));
            }
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(),
                    mapHandle(dynamic.getBootstrapMethod()), arguments);
        }
        return value;
    }

    public static final class Builder {
        private final Map<String, String> parents = new HashMap<>();
        private final Map<String, Set<String>> declarations = new HashMap<>();

        public void addClass(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            Set<String> methods = new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    methods.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            parents.put(reader.getClassName(), reader.getSuperName());
            declarations.put(reader.getClassName(), methods);
        }

        public MethodRenamer build() {
            Set<String> descendants = new HashSet<>();
            boolean grew;
            do {
                grew = false;
                for (Map.Entry<String, String> entry : parents.entrySet()) {
                    if ((THREAD.equals(entry.getValue()) || descendants.contains(entry.getValue()))
                            && descendants.add(entry.getKey())) grew = true;
                }
            } while (grew);

            Map<String, String> renamedSignatures = new HashMap<>();
            for (String signature : CONFLICTS) {
                boolean declared = false;
                for (String type : descendants) {
                    if (declarations.get(type).contains(signature)) {
                        declared = true;
                        break;
                    }
                }
                if (!declared) continue;
                String method = signature.substring(0, signature.indexOf('('));
                String descriptor = signature.substring(signature.indexOf('('));
                String candidate = method + SUFFIX;
                boolean collision;
                do {
                    collision = false;
                    for (String type : descendants) {
                        if (declarations.get(type).contains(candidate + descriptor)) {
                            collision = true;
                            candidate += '$';
                            break;
                        }
                    }
                } while (collision);
                renamedSignatures.put(signature, candidate);
            }

            Map<String, Map<String, String>> result = new HashMap<>();
            for (String type : descendants) {
                Map<String, String> methods = new HashMap<>();
                for (Map.Entry<String, String> entry : renamedSignatures.entrySet()) {
                    String current = type;
                    while (current != null && !THREAD.equals(current)) {
                        Set<String> declared = declarations.get(current);
                        if (declared != null && declared.contains(entry.getKey())) {
                            methods.put(entry.getKey(), entry.getValue());
                            break;
                        }
                        current = parents.get(current);
                    }
                }
                if (!methods.isEmpty()) result.put(type, methods);
            }
            return new MethodRenamer(result);
        }
    }
}
