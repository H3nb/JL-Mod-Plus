/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>

namespace {

// Native-only deterministic fixture for device instrumentation. Production never reads or mutates
// this array. Distinct uncommon values make ordered-vs-any-order Group semantics testable without
// depending on ART object layout or allocations in the target Java heap.
alignas(32) std::array<std::int32_t, 8> gGroupProbe{
        324478056, 610800471, 271136839, 1432778632,
        -324478056, -610800471, -271136839, -1432778632,
};

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryTarget_readGroupProbe(
        JNIEnv *env, jclass) {
    constexpr std::size_t kHeader = 2U;
    constexpr std::size_t kCount = gGroupProbe.size();
    std::array<jlong, kHeader + kCount> output{};
    output[0] = static_cast<jlong>(reinterpret_cast<std::uintptr_t>(gGroupProbe.data()));
    output[1] = static_cast<jlong>(kCount);
    for (std::size_t index = 0U; index < kCount; ++index) {
        output[kHeader + index] = static_cast<jlong>(gGroupProbe[index]);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryTarget_writeGroupProbe(
        JNIEnv *env, jclass, jintArray rawValues) {
    if (rawValues == nullptr ||
        env->GetArrayLength(rawValues) != static_cast<jsize>(gGroupProbe.size())) {
        return JNI_FALSE;
    }
    std::array<jint, gGroupProbe.size()> values{};
    env->GetIntArrayRegion(rawValues, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    for (std::size_t index = 0U; index < values.size(); ++index) {
        gGroupProbe[index] = static_cast<std::int32_t>(values[index]);
    }
    return JNI_TRUE;
}
