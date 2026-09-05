/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Watch pin/unpin still needs the established Candidate primitive when no compact revision exists.
// Ordinary result filtering is compact-only in production and no longer gets a legacy JNI alias.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pin(
        JNIEnv *, jclass, jlongArray, jboolean);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pinUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jboolean add) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pin(
            env, clazz, rawIds, add);
}
