/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Keep the established native filter implementation byte-for-byte intact while Java owns the
// migration bookkeeping around Keep/Remove. Delete this forwarding seam when filtering itself is
// ResultStore-native and no longer commits a Candidate-owned revision.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filter(
        JNIEnv *, jclass, jlongArray, jboolean);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filterUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jboolean keep) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filter(
            env, clazz, rawIds, keep);
}
