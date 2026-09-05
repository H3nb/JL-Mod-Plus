/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Small JNI bridge used while Java routes production Known/Auto sessions to the candidate-free
// compact owner. Legacy forwarders call the already-compiled implementation in this same native
// translation unit; they do not copy search/mutation logic.

extern "C" jlongArray
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroups(
        JNIEnv *, jclass, jlongArray, jint);
extern "C" jlongArray
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspect(
        JNIEnv *, jclass, jlong, jint);
extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editInspectorValue(
        JNIEnv *, jclass, jlong, jint, jint, jlong, jstring);

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroupsUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jint requestedType) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroups(
            env, clazz, rawIds, requestedType);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspectUnchecked(
        JNIEnv *env, jclass clazz, jlong candidateId, jint radius) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspect(
            env, clazz, candidateId, radius);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editInspectorValueUnchecked(
        JNIEnv *env, jclass clazz, jlong anchorCandidateId, jint relativeOffset,
        jint valueType, jlong expectedBits, jstring replacementValue) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editInspectorValue(
            env, clazz, anchorCandidateId, relativeOffset, valueType,
            expectedBits, replacementValue);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2CompactOwnerStats(
        JNIEnv *env, jclass) {
    try {
        std::shared_ptr<const SearchState> state;
        std::size_t liveCount = 0U;
        std::size_t historyDepth = 0U;
        {
            std::lock_guard<std::mutex> lock(gMutex);
            state = gState;
            liveCount = gLiveCandidates.size();
            historyDepth = gHistory.size();
        }
        const auto revision = lookupCompactRevision(state);
        const std::uint64_t ordinaryCount =
                revision != nullptr && revision->ordinaryValues != nullptr
                        ? revision->ordinaryValues->size()
                        : 0U;
        const std::uint64_t uniqueCount =
                revision != nullptr && revision->store != nullptr
                        ? revision->store->uniqueAddressCount()
                        : 0U;
        const std::size_t revisionBytes =
                revision != nullptr ? revision->retainedBytes() : 0U;
        const auto clamp = [](std::size_t value) -> jlong {
            const auto maximum =
                    static_cast<std::size_t>(std::numeric_limits<jlong>::max());
            return static_cast<jlong>(std::min(value, maximum));
        };
        const std::array<jlong, 8> values{
                revision != nullptr && revision->compact() ? 1L : 0L,
                static_cast<jlong>(std::min<std::uint64_t>(
                        ordinaryCount,
                        static_cast<std::uint64_t>(
                                std::numeric_limits<jlong>::max()))),
                state != nullptr ? clamp(state->candidates.size()) : 0L,
                static_cast<jlong>(std::min<std::uint64_t>(
                        uniqueCount,
                        static_cast<std::uint64_t>(
                                std::numeric_limits<jlong>::max()))),
                clamp(revisionBytes),
                state != nullptr ? clamp(state->retainedBytes()) : 0L,
                clamp(historyDepth),
                clamp(liveCount),
        };
        jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
        if (result != nullptr) {
            env->SetLongArrayRegion(
                    result, 0, static_cast<jsize>(values.size()), values.data());
        }
        return result;
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
}
