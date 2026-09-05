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
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <limits>
#include <new>
#include <string>
#include <vector>

namespace {

constexpr jint kOk = 0;
constexpr jint kInvalidRequest = 2;
constexpr jint kResourceLimit = 3;
constexpr jint kTargetLost = 5;
constexpr std::size_t kMaxTargetRuns = 16'384U;

[[nodiscard]] bool readExact(pid_t pid, std::uintptr_t address, void *output,
                             std::size_t size) noexcept {
    if (pid <= 0 || (output == nullptr && size != 0U)) {
        return false;
    }
    auto *destination = static_cast<std::uint8_t *>(output);
    std::size_t completed = 0U;
    while (completed < size) {
        if (address > std::numeric_limits<std::uintptr_t>::max() - completed) {
            return false;
        }
        iovec local{destination + completed, size - completed};
        iovec remote{reinterpret_cast<void *>(address + completed), size - completed};
        ssize_t result;
        do {
            result = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        } while (result < 0 && errno == EINTR);
        if (result <= 0 || static_cast<std::size_t>(result) > size - completed) {
            return false;
        }
        completed += static_cast<std::size_t>(result);
    }
    return true;
}

[[nodiscard]] jlong saturatingJlong(std::uint64_t value) noexcept {
    constexpr std::uint64_t kMax =
            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    return value > kMax ? std::numeric_limits<jlong>::max()
                        : static_cast<jlong>(value);
}

jlongArray makeResult(JNIEnv *env, jint status,
                      const jlmem::v2::KnownScanStats *stats = nullptr) {
    std::array<jlong, 7> values{};
    values[0] = status;
    if (stats != nullptr) {
        values[1] = saturatingJlong(stats->bytesScanned);
        values[2] = saturatingJlong(stats->typedMatches);
        values[3] = saturatingJlong(stats->uniqueAddresses);
        values[4] = static_cast<jlong>(stats->addressFingerprint);
        values[5] = saturatingJlong(static_cast<std::uint64_t>(stats->blockCount));
        values[6] = saturatingJlong(static_cast<std::uint64_t>(stats->retainedBytes));
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                values.data());
    }
    return result;
}

[[nodiscard]] bool readRanges(JNIEnv *env, jlongArray rawRuns,
                              std::vector<jlmem::v2::ScanRange> &ranges) {
    if (rawRuns == nullptr) {
        return false;
    }
    const jsize length = env->GetArrayLength(rawRuns);
    if (length < 4 || (length - 2) % 2 != 0) {
        return false;
    }
    std::vector<jlong> values(static_cast<std::size_t>(length));
    env->GetLongArrayRegion(rawRuns, 0, length, values.data());
    if (env->ExceptionCheck()) {
        return false;
    }
    const jlong declared = values[0];
    if (values[1] != 0 || declared <= 0 ||
        static_cast<std::uint64_t>(declared) > kMaxTargetRuns ||
        declared != (length - 2) / 2) {
        return false;
    }
    ranges.reserve(static_cast<std::size_t>(declared));
    std::uintptr_t previousEnd = 0U;
    for (jsize index = 2; index < length; index += 2) {
        if (values[index] <= 0 || values[index + 1] <= values[index] ||
            static_cast<std::uint64_t>(values[index]) >
                    std::numeric_limits<std::uintptr_t>::max() ||
            static_cast<std::uint64_t>(values[index + 1]) >
                    std::numeric_limits<std::uintptr_t>::max()) {
            return false;
        }
        const std::uintptr_t start =
                static_cast<std::uintptr_t>(values[index]);
        const std::uintptr_t end =
                static_cast<std::uintptr_t>(values[index + 1]);
        if (previousEnd != 0U && start < previousEnd) {
            return false;
        }
        ranges.push_back({start, end});
        previousEnd = end;
    }
    return !ranges.empty();
}

[[nodiscard]] bool readPlans(JNIEnv *env, jint predicate,
                             jintArray rawTypes, jlongArray rawFirst,
                             jlongArray rawSecond,
                             std::vector<jlmem::v2::KnownScanRequest> &plans) {
    if (rawTypes == nullptr || rawFirst == nullptr || rawSecond == nullptr) {
        return false;
    }
    const jsize count = env->GetArrayLength(rawTypes);
    if (count <= 0 || count > static_cast<jsize>(jlmem::v2::kResultPlaneCount) ||
        env->GetArrayLength(rawFirst) != count ||
        env->GetArrayLength(rawSecond) != count) {
        return false;
    }
    std::vector<jint> types(static_cast<std::size_t>(count));
    std::vector<jlong> first(static_cast<std::size_t>(count));
    std::vector<jlong> second(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(rawTypes, 0, count, types.data());
    env->GetLongArrayRegion(rawFirst, 0, count, first.data());
    env->GetLongArrayRegion(rawSecond, 0, count, second.data());
    if (env->ExceptionCheck()) {
        return false;
    }
    plans.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        const auto plan = jlmem::v2::knownQueryPlanFromStableValues(
                types[static_cast<std::size_t>(index)], predicate,
                static_cast<std::uint64_t>(first[static_cast<std::size_t>(index)]),
                static_cast<std::uint64_t>(second[static_cast<std::size_t>(index)]));
        if (!plan.has_value()) {
            return false;
        }
        plans.push_back(*plan);
    }
    return true;
}

[[nodiscard]] bool verifyAutoCursor(const jlmem::v2::ResultStore &store,
                                    const jlmem::v2::KnownScanStats &stats) {
    constexpr std::array<jlmem::v2::ResultPlane, jlmem::v2::kResultPlaneCount>
            kDisplayOrder{
                    jlmem::v2::ResultPlane::Int,
                    jlmem::v2::ResultPlane::Float,
                    jlmem::v2::ResultPlane::Long,
                    jlmem::v2::ResultPlane::Double,
                    jlmem::v2::ResultPlane::Short,
                    jlmem::v2::ResultPlane::Char,
                    jlmem::v2::ResultPlane::Byte,
            };
    jlmem::v2::ResultCursor cursor;
    std::uint64_t typed = 0U;
    std::uint64_t unique = 0U;
    std::uint64_t fingerprint = 1469598103934665603ULL;
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
            if (row.aliasMask == 0U) {
                return false;
            }
            ++unique;
            for (const jlmem::v2::ResultPlane plane : kDisplayOrder) {
                if ((row.aliasMask & jlmem::v2::resultPlaneBit(plane)) == 0U) {
                    continue;
                }
                fingerprint = jlmem::v2::appendAddressFingerprint(
                        fingerprint, row.address, plane);
                ++typed;
            }
        }
        cursor = page.next;
    }
    return typed == stats.typedMatches && unique == stats.uniqueAddresses &&
           fingerprint == stats.addressFingerprint;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_MemoryV2AutoKernelParityTest_v2AutoKernelProbe(
        JNIEnv *env, jclass, jint pid, jlongArray rawRuns, jint predicate,
        jintArray rawTypes, jlongArray rawFirstBits, jlongArray rawSecondBits) {
    try {
        // This diagnostic intentionally cannot become a generic cross-process read primitive. The
        // instrumentation comparison only needs the test process' own bounded resident page.
        if (pid != static_cast<jint>(getpid())) {
            return makeResult(env, kInvalidRequest);
        }
        std::vector<jlmem::v2::ScanRange> ranges;
        std::vector<jlmem::v2::KnownScanRequest> plans;
        if (!readRanges(env, rawRuns, ranges) ||
            !readPlans(env, predicate, rawTypes, rawFirstBits, rawSecondBits,
                       plans)) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            return makeResult(env, kInvalidRequest);
        }
        jlmem::v2::ResultStore store;
        jlmem::v2::KnownScanStats stats;
        std::string error;
        const bool scanned = jlmem::v2::scanKnownAuto(
                ranges, plans,
                [&](std::uintptr_t address, void *output, std::size_t size) {
                    return readExact(pid, address, output, size);
                },
                [] { return false; }, store, stats, error, nullptr);
        if (!scanned) {
            const bool targetLost = error.rfind("Target range", 0U) == 0U;
            return makeResult(env, targetLost ? kTargetLost : kInvalidRequest);
        }
        if (!verifyAutoCursor(store, stats)) {
            return makeResult(env, kInvalidRequest);
        }
        return makeResult(env, kOk, &stats);
    } catch (const std::bad_alloc &) {
        return makeResult(env, kResourceLimit);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return makeResult(env, kInvalidRequest);
    }
}
