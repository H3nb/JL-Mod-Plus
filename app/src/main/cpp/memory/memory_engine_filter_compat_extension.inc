/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Keep the established Candidate primitives byte-for-byte intact while Java owns ResultStore
// lifecycle bookkeeping around operations that replace SearchState. Delete these aliases once
// filter/watch state is natively split from ordinary search membership.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filter(
        JNIEnv *, jclass, jlongArray, jboolean);
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pin(
        JNIEnv *, jclass, jlongArray, jboolean);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filterUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jboolean keep) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filter(
            env, clazz, rawIds, keep);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pinUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jboolean add) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pin(
            env, clazz, rawIds, add);
}
