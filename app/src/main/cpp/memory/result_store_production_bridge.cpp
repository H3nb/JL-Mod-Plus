/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>

// Transitional JNI seam for production ResultStore staging.
//
// The authoritative Known refine implementation still lives in memory_engine.cpp. Java wraps that
// call only so a successful immutable legacy revision can be mirrored into the bounded ResultStore
// read path. Keep the forwarding isolated and delete it when ResultStore owns production refine.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnown(
        JNIEnv *, jclass, jint, jstring, jstring);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownUnchecked(
        JNIEnv *env, jclass clazz, jint predicate, jstring first,
        jstring second) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnown(
            env, clazz, predicate, first, second);
}
