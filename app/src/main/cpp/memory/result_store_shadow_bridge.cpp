/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_cursor.h"
#include "result_store_scan.h"

#include <jni.h>
#include <sys/uio.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <vector>

namespace {

constexpr jint kOk = 0;
constexpr jint kInvalidRequest = 2;
constexpr jint kResourceLimit = 3;
constexpr jint kTargetLost = 5;
constexpr jint kTypeAuto = 0;
constexpr jint kTypeByte = 1;
constexpr jint kTypeShort = 2;
constexpr jint kTypeChar = 3;
constexpr jint kTypeInt = 4;
constexpr jint kTypeLong = 5;
constexpr jint kTypeFloat = 6;
constexpr jint kTypeDouble = 7;
constexpr std::size_t kMaxTargetRuns = 4'096U;

struct ShadowTarget {
    pid_t pid = 0;
    jlong runtimeToken = 0;
    std::uint64_t generation = 0U;
    std::vector<jlmem::v2::ScanRange> ranges;
};

std::mutex gShadowMutex;
ShadowTarget gShadowTarget;

[[nodiscard]] bool readExact(pid_t pid, std::uintptr_t address, void *output,
                             std::size_t size) noexcept {
    iovec local{output, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    ssize_t result;
    do {
        result = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    } while (result < 0 && errno == EINTR);
    return result == static_cast<ssize_t>(size);
}

[[nodiscard]] bool typeInfo(jint valueType, jlmem::v2::ResultPlane &plane,
                            std::size_t &width) noexcept {
    switch (valueType) {
    case kTypeByte:
        plane = jlmem::v2::ResultPlane::Byte;
        width = 1U;
        return true;
    case kTypeShort:
        plane = jlmem::v2::ResultPlane::Short;
        width = 2U;
        return true;
    case kTypeChar:
        plane = jlmem::v2::ResultPlane::Char;
        width = 2U;
        return true;
    case kTypeInt:
        plane = jlmem::v2::ResultPlane::Int;
        width = 4U;
        return true;
    case kTypeLong:
        plane = jlmem::v2::ResultPlane::Long;
        width = 8U;
        return true;
    case kTypeFloat:
        plane = jlmem::v2::ResultPlane::Float;
        width = 4U;
        return true;
    case kTypeDouble:
        plane = jlmem::v2::ResultPlane::Double;
        width = 8U;
        return true;
    default:
        return false;
    }
}

[[nodiscard]] bool equalBits(jint valueType, std::uint64_t actual,
                             std::uint64_t expected) noexcept {
    switch (valueType) {
    case kTypeByte:
        return static_cast<std::int8_t>(actual) ==
               static_cast<std::int8_t>(expected);
    case kTypeShort:
        return static_cast<std::int16_t>(actual) ==
               static_cast<std::int16_t>(expected);
    case kTypeChar:
        return static_cast<std::uint16_t>(actual) ==
               static_cast<std::uint16_t>(expected);
    case kTypeInt:
        return static_cast<std::int32_t>(actual) ==
               static_cast<std::int32_t>(expected);
    case kTypeLong:
        return static_cast<std::int64_t>(actual) ==
               static_cast<std::int64_t>(expected);
    case kTypeFloat: {
        const std::uint32_t actualRaw = static_cast<std::uint32_t>(actual);
        const std::uint32_t expectedRaw = static_cast<std::uint32_t>(expected);
        float actualValue = 0.0F;
        float expectedValue = 0.0F;
        std::memcpy(&actualValue, &actualRaw, sizeof(actualValue));
        std::memcpy(&expectedValue, &expectedRaw, sizeof(expectedValue));
        return actualValue == expectedValue;
    }
    case kTypeDouble: {
        double actualValue = 0.0;
        double expectedValue = 0.0;
        std::memcpy(&actualValue, &actual, sizeof(actualValue));
        std::memcpy(&expectedValue, &expected, sizeof(expectedValue));
        return actualValue == expectedValue;
    }
    default:
        return false;
    }
}

[[nodiscard]] bool validateCursor(const jlmem::v2::ResultStore &store,
                                  jlmem::v2::ResultPlane plane,
                                  const jlmem::v2::KnownScanStats &stats) {
    jlmem::v2::ResultCursor cursor;
    std::uint64_t fingerprint = 1469598103934665603ULL;
    std::uint64_t count = 0U;
    const std::uint8_t expectedMask = jlmem::v2::resultPlaneBit(plane);
    while (cursor.blockIndex < store.blockCount()) {
        jlmem::v2::ResultAddressPage page;
        if (!jlmem::v2::readAddressPage(
                    store, cursor, jlmem::v2::kResultCursorPageLimit, page)) {
            return false;
        }
        if (page.rows.empty() && page.next.blockIndex == cursor.blockIndex &&
            page.next.nextByteOffset == cursor.nextByteOffset) {
            return false;
        }
        for (const jlmem::v2::ResultAddressRow &row : page.rows) {
            if (row.aliasMask != expectedMask) {
                return false;
            }
            fingerprint = jlmem::v2::appendAddressFingerprint(
                    fingerprint, row.address, plane);
            ++count;
        }
        cursor = page.next;
    }
    return count == stats.uniqueAddresses && count == stats.typedMatches &&
           fingerprint == stats.addressFingerprint;
}

[[nodiscard]] jlong saturatingJlong(std::uint64_t value) noexcept {
    constexpr std::uint64_t kMax =
            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    return value > kMax ? std::numeric_limits<jlong>::max()
                        : static_cast<jlong>(value);
}

jlongArray shadowResult(JNIEnv *env, jint status,
                        const jlmem::v2::KnownScanStats *stats = nullptr) {
    std::array<jlong, 7> values{};
    values[0] = status;
    if (stats != nullptr) {
        values[1] = saturatingJlong(stats->bytesScanned);
        values[2] = saturatingJlong(stats->typedMatches);
        values[3] = saturatingJlong(stats->uniqueAddresses);
        values[4] = saturatingJlong(static_cast<std::uint64_t>(stats->blockCount));
        values[5] = saturatingJlong(static_cast<std::uint64_t>(stats->retainedBytes));
        values[6] = static_cast<jlong>(stats->addressFingerprint);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                values.data());
    }
    return result;
}

} // namespace

extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTarget(
        JNIEnv *, jclass, jint, jint, jlong, jlongArray);
extern "C" void
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(
        JNIEnv *, jclass);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTargetUnchecked(
        JNIEnv *env, jclass clazz, jint pid, jint pageSize, jlong runtimeToken,
        jlongArray rawRuns) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTarget(
            env, clazz, pid, pageSize, runtimeToken, rawRuns);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureV2ShadowTarget(
        JNIEnv *env, jclass, jint pid, jlong runtimeToken, jlongArray rawRuns) {
    ShadowTarget next;
    if (pid > 0 && runtimeToken != 0 && rawRuns != nullptr) {
        const jsize length = env->GetArrayLength(rawRuns);
        if (length >= 4 && (length - 2) % 2 == 0) {
            std::vector<jlong> values(static_cast<std::size_t>(length));
            env->GetLongArrayRegion(rawRuns, 0, length, values.data());
            const jlong declaredRuns = values[0];
            if (!env->ExceptionCheck() && values[1] == 0 && declaredRuns > 0 &&
                static_cast<std::uint64_t>(declaredRuns) <= kMaxTargetRuns &&
                declaredRuns == (length - 2) / 2) {
                next.pid = pid;
                next.runtimeToken = runtimeToken;
                next.ranges.reserve(static_cast<std::size_t>(declaredRuns));
                std::uintptr_t previousEnd = 0U;
                bool valid = true;
                for (jsize index = 2; index < length; index += 2) {
                    if (values[index] <= 0 || values[index + 1] <= values[index] ||
                        static_cast<std::uint64_t>(values[index]) >
                                std::numeric_limits<std::uintptr_t>::max() ||
                        static_cast<std::uint64_t>(values[index + 1]) >
                                std::numeric_limits<std::uintptr_t>::max()) {
                        valid = false;
                        break;
                    }
                    const std::uintptr_t start =
                            static_cast<std::uintptr_t>(values[index]);
                    const std::uintptr_t end =
                            static_cast<std::uintptr_t>(values[index + 1]);
                    if (previousEnd != 0U && start < previousEnd) {
                        valid = false;
                        break;
                    }
                    next.ranges.push_back({start, end});
                    previousEnd = end;
                }
                if (!valid) {
                    next = {};
                }
            }
        }
    }
    std::lock_guard<std::mutex> lock(gShadowMutex);
    next.generation = gShadowTarget.generation + 1U;
    gShadowTarget = std::move(next);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTargetUnchecked(
        JNIEnv *env, jclass clazz) {
    Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(env,
                                                                          clazz);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearV2ShadowTarget(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(gShadowMutex);
    const std::uint64_t nextGeneration = gShadowTarget.generation + 1U;
    gShadowTarget = {};
    gShadowTarget.generation = nextGeneration;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2ShadowKnownEqual(
        JNIEnv *env, jclass, jint valueType, jlong expectedBits) {
    try {
        if (valueType == kTypeAuto) {
            return shadowResult(env, kInvalidRequest);
        }
        jlmem::v2::ResultPlane plane = jlmem::v2::ResultPlane::Int;
        std::size_t width = 0U;
        if (!typeInfo(valueType, plane, width)) {
            return shadowResult(env, kInvalidRequest);
        }

        ShadowTarget target;
        {
            std::lock_guard<std::mutex> lock(gShadowMutex);
            target = gShadowTarget;
        }
        if (target.pid <= 0 || target.runtimeToken == 0 || target.ranges.empty()) {
            return shadowResult(env, kTargetLost);
        }

        jlmem::v2::ResultStore store;
        jlmem::v2::KnownScanStats stats;
        std::string error;
        const std::uint64_t expected = static_cast<std::uint64_t>(expectedBits);
        const bool ok = jlmem::v2::scanKnownExplicit(
                target.ranges, {plane, width},
                [&](std::uintptr_t address, void *output, std::size_t size) {
                    return readExact(target.pid, address, output, size);
                },
                [&](std::uint64_t actual) {
                    return equalBits(valueType, actual, expected);
                },
                {}, store, stats, error);
        if (!ok) {
            return shadowResult(env,
                                error.rfind("Target range", 0U) == 0U
                                        ? kTargetLost
                                        : kInvalidRequest);
        }
        if (!validateCursor(store, plane, stats)) {
            return shadowResult(env, kInvalidRequest);
        }
        {
            std::lock_guard<std::mutex> lock(gShadowMutex);
            if (gShadowTarget.generation != target.generation ||
                gShadowTarget.pid != target.pid ||
                gShadowTarget.runtimeToken != target.runtimeToken) {
                return shadowResult(env, kTargetLost);
            }
        }
        return shadowResult(env, kOk, &stats);
    } catch (const std::bad_alloc &) {
        return shadowResult(env, kResourceLimit);
    } catch (...) {
        return shadowResult(env, kInvalidRequest);
    }
}
