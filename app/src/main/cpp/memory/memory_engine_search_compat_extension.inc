/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transactional Java wrappers own ResultStore lifecycle flags for first-search modes that still use
// the established native primitives. Keep these aliases until Unknown/Group/Nearby are fully folded
// into the final v2 search-state owner.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
        JNIEnv *, jclass, jint);
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startGroup(
        JNIEnv *, jclass, jintArray, jobjectArray, jint);
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
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startGroupUnchecked(
        JNIEnv *env, jclass clazz, jintArray rawTypes, jobjectArray rawValues,
        jint maxDistance) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startGroup(
            env, clazz, rawTypes, rawValues, maxDistance);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearbyUnchecked(
        JNIEnv *env, jclass clazz, jlong anchorCandidateId, jint radius, jint type,
        jint predicate, jstring first, jstring second) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearby(
            env, clazz, anchorCandidateId, radius, type, predicate, first, second);
}
