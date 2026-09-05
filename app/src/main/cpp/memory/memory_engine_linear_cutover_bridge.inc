/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Public JNI name remains stable while the old compact recovery implementation stays compiled as
// a differential/debug fallback under a Legacy suffix. Production Java therefore cannot
// accidentally bypass the linear broad-GC rebuild just because both implementations share the
// same transitional translation unit.
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwnerLinear(
        JNIEnv *, jclass, jint, jstring, jstring, jboolean);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwner(
        JNIEnv *env, jclass clazz, jint predicate, jstring first, jstring second,
        jboolean allowRelocationReconcile) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwnerLinear(
            env, clazz, predicate, first, second, allowRelocationReconcile);
}
