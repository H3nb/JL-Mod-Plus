/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>

// Transitional JNI aliases for Java wrapper methods whose production implementation still lives
// under the historical native symbol. Keep these forwarding seams explicit instead of depending on
// undocumented name-resolution behavior while explicit Known search moves to ResultStore.
extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnown(
        JNIEnv *env, jclass clazz, jint valueType, jint predicate,
        jstring first, jstring second);

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(
        JNIEnv *env, jclass clazz);

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(
        JNIEnv *env, jclass clazz);

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
    Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(env, clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTargetUnchecked(
        JNIEnv *env, jclass clazz) {
    Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(env, clazz);
}
