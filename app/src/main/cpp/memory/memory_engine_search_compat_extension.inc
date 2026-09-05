/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transactional Java wrappers still need the established primitive for Unknown baseline capture.
// Group Search and Nearby Search are candidate-free in production and no longer get legacy JNI
// aliases here.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
        JNIEnv *, jclass, jint);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknownUnchecked(
        JNIEnv *env, jclass clazz, jint type) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
            env, clazz, type);
}
