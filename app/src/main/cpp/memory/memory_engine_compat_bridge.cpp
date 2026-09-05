/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>

// Clear-session aliases remain while Java owns the v2 staging lifecycle around the legacy native
// clear entry points. Auto first search no longer needs a release compatibility alias: production
// now enters the fused ResultStore kernel directly.
extern "C" void
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(
        JNIEnv *, jclass);
extern "C" void
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(
        JNIEnv *, jclass);

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
