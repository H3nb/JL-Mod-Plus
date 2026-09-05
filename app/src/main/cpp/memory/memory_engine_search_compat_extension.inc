/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transactional Java wrappers still need the established primitives for Unknown baseline capture
// and the watch-only Nearby fallback. Group Search is candidate-free in production and no longer
// gets a second legacy JNI alias here.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
        JNIEnv *, jclass, jint);
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearby(
        JNIEnv *, jclass, jlong, jint, jint, jint, jstring, jstring);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknownUnchecked(
        JNIEnv *env, jclass clazz, jint type) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
            env, clazz, type);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearbyUnchecked(
        JNIEnv *env, jclass clazz, jlong anchorCandidateId, jint radius, jint type,
        jint predicate, jstring first, jstring second) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearby(
            env, clazz, anchorCandidateId, radius, type, predicate, first, second);
}
