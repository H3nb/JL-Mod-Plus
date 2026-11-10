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
        if (value instanceof Handle) return mapHandle((Handle) value);
        if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
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
        private static final class ClassInfo {
            final String superName;
            final String[] interfaces;
            final boolean isInterface;
            final Set<String> methods;

            ClassInfo(ClassReader reader, Set<String> methods) {
                superName = reader.getSuperName();
                interfaces = reader.getInterfaces();
                isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
                this.methods = methods;
            }
        }

        /** Conversion-local metadata: the actual source bytes remain owned by the DX pipeline. */
        private final Map<String, ClassInfo> classes = new HashMap<>();

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
            classes.put(reader.getClassName(), new ClassInfo(reader, methods));
        }

        public MethodRenamer build() {
            Set<String> threadTypes = new HashSet<>();
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
                    String parent = entry.getValue().superName;
                    if ((THREAD.equals(parent) || threadTypes.contains(parent))
                            && threadTypes.add(entry.getKey())) changed = true;
                }
            } while (changed);

            Map<String, Map<String, String>> result = new HashMap<>();
            for (String signature : CONFLICTS) {
                Set<String> relevantInterfaces = interfacesDefining(signature);
                Map<String, Set<String>> edges = new HashMap<>();
                for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
                    String owner = entry.getKey();
                    ClassInfo info = entry.getValue();
                    if (classes.containsKey(info.superName)) connect(edges, owner, info.superName);
                    // Do not connect via unrelated shared interfaces such as Runnable.
                    for (String iface : info.interfaces) {
                        if (relevantInterfaces.contains(iface)) connect(edges, owner, iface);
                    }
                }

                Set<String> affected = new HashSet<>();
                java.util.ArrayList<String> queue = new java.util.ArrayList<>();
                for (String type : threadTypes) {
                    if (classes.get(type).methods.contains(signature) && affected.add(type)) {
                        queue.add(type);
                    }
                }
                for (int index = 0; index < queue.size(); index++) {
                    for (String adjacent : edges.getOrDefault(
                            queue.get(index), java.util.Collections.emptySet())) {
                        if (affected.add(adjacent)) queue.add(adjacent);
                    }
                }
                if (affected.isEmpty()) continue;

                String method = signature.substring(0, signature.indexOf('('));
                String descriptor = signature.substring(signature.indexOf('('));
                String candidate = method + SUFFIX;
                boolean collision;
                do {
                    collision = false;
                    for (String type : affected) {
                        if (classes.get(type).methods.contains(candidate + descriptor)) {
                            candidate += '$';
                            collision = true;
                            break;
                        }
                    }
                } while (collision);

                for (String type : affected) {
                    ClassInfo info = classes.get(type);
                    if ((info.isInterface && relevantInterfaces.contains(type))
                            || hasGuestClassDeclaration(type, signature)) {
                        result.computeIfAbsent(type, unused -> new HashMap<>())
                                .put(signature, candidate);
                    }
                }
            }
            return new MethodRenamer(result);
        }

        /** Interfaces declaring or inheriting the relevant method, but no unrelated interfaces. */
        private Set<String> interfacesDefining(String signature) {
            Set<String> relevant = new HashSet<>();
            for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
                ClassInfo info = entry.getValue();
                if (info.isInterface && info.methods.contains(signature)) relevant.add(entry.getKey());
            }
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
                    ClassInfo info = entry.getValue();
                    if (!info.isInterface || relevant.contains(entry.getKey())) continue;
                    for (String parent : info.interfaces) {
                        if (relevant.contains(parent)) {
                            changed |= relevant.add(entry.getKey());
                            break;
                        }
                    }
                }
            } while (changed);
            return relevant;
        }

        private boolean hasGuestClassDeclaration(String type, String signature) {
            Set<String> visited = new HashSet<>();
            while (type != null && visited.add(type)) {
                ClassInfo info = classes.get(type);
                if (info == null) break;
                if (!info.isInterface && info.methods.contains(signature)) return true;
                type = info.superName;
            }
            return false;
        }

        private static void connect(Map<String, Set<String>> edges, String first, String second) {
            edges.computeIfAbsent(first, unused -> new HashSet<>()).add(second);
            edges.computeIfAbsent(second, unused -> new HashSet<>()).add(first);
        }
    }
}
