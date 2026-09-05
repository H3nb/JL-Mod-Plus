/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>

// These three wrappers are the only legacy JNI aliases still required by production Java while
// Auto search and clear-session call sites are migrated. Shadow/parity diagnostics live in the
// debug-only result_store_shadow_bridge.cpp and must not enlarge the release JNI surface.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnown(
        JNIEnv *, jclass, jint, jint, jstring, jstring);
extern "C" void
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(
        JNIEnv *, jclass);
extern "C" void
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(
        JNIEnv *, jclass);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnownUnchecked(
        JNIEnv *env, jclass clazz, jint valueType, jint predicate,
        jstring first, jstring second) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnown(
            env, clazz, valueType, predicate, first, second);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearchUnchecked(
        JNIEnv *env, jclass clazz) {
    Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(env,
                                                                          clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTargetUnchecked(
        JNIEnv *env, jclass clazz) {
    Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(env,
                                                                          clazz);
}
