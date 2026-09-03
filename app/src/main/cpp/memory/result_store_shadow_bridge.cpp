/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_cursor.h"
#include "result_store_refine.h"
#include "result_store_scan.h"

#include <jni.h>
#include <sys/uio.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
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
constexpr jint kPredicateEqual = 0;
constexpr jint kPredicateBetween = 6;
constexpr jlong kShadowOperationScan = 0;
constexpr jlong kShadowOperationRefine = 1;
constexpr std::size_t kMaxTargetRuns = 4'096U;

struct ShadowTarget {
    pid_t pid = 0;
    jlong runtimeToken = 0;
    std::uint64_t generation = 0U;
    std::vector<jlmem::v2::ScanRange> ranges;
};

struct ShadowSession {
    std::uint64_t targetGeneration = 0U;
    jlmem::v2::ResultPlane plane = jlmem::v2::ResultPlane::Count;
    std::shared_ptr<const jlmem::v2::ResultStore> store;
};

std::mutex gShadowMutex;
ShadowTarget gShadowTarget;
ShadowSession gShadowSession;

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

[[nodiscard]] bool resultPlane(jint valueType,
                               jlmem::v2::ResultPlane &plane) noexcept {
    switch (valueType) {
    case kTypeByte:
        plane = jlmem::v2::ResultPlane::Byte;
        return true;
    case kTypeShort:
        plane = jlmem::v2::ResultPlane::Short;
        return true;
    case kTypeChar:
        plane = jlmem::v2::ResultPlane::Char;
        return true;
    case kTypeInt:
        plane = jlmem::v2::ResultPlane::Int;
        return true;
    case kTypeLong:
        plane = jlmem::v2::ResultPlane::Long;
        return true;
    case kTypeFloat:
        plane = jlmem::v2::ResultPlane::Float;
        return true;
    case kTypeDouble:
        plane = jlmem::v2::ResultPlane::Double;
        return true;
    default:
        return false;
    }
}

[[nodiscard]] bool knownPredicate(
        jint predicate,
        jlmem::v2::KnownPredicate &known) noexcept {
    if (predicate < kPredicateEqual || predicate > kPredicateBetween) {
        return false;
    }
    known = static_cast<jlmem::v2::KnownPredicate>(
            static_cast<std::uint8_t>(predicate));
    return true;
}

[[nodiscard]] bool shadowTargetMatches(const ShadowTarget &target) noexcept {
    std::lock_guard<std::mutex> lock(gShadowMutex);
    return gShadowTarget.generation == target.generation &&
           gShadowTarget.pid == target.pid &&
           gShadowTarget.runtimeToken == target.runtimeToken;
}

[[nodiscard]] bool summarizeCursor(const jlmem::v2::ResultStore &store,
                                   jlmem::v2::ResultPlane plane,
                                   std::uint64_t &count,
                                   std::uint64_t &fingerprint) {
    jlmem::v2::ResultCursor cursor;
    fingerprint = 1469598103934665603ULL;
    count = 0U;
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
    return true;
}

[[nodiscard]] bool validateCursor(const jlmem::v2::ResultStore &store,
                                  jlmem::v2::ResultPlane plane,
                                  const jlmem::v2::KnownScanStats &stats) {
    std::uint64_t count = 0U;
    std::uint64_t fingerprint = 0U;
    return summarizeCursor(store, plane, count, fingerprint) &&
           count == stats.uniqueAddresses && count == stats.typedMatches &&
           fingerprint == stats.addressFingerprint;
}

[[nodiscard]] bool finishRefineStats(const jlmem::v2::ResultStore &store,
                                     jlmem::v2::ResultPlane plane,
                                     jlmem::v2::KnownScanStats &stats) {
    std::uint64_t count = 0U;
    std::uint64_t fingerprint = 0U;
    if (!summarizeCursor(store, plane, count, fingerprint) ||
        count != stats.uniqueAddresses || count != stats.typedMatches) {
        return false;
    }
    stats.addressFingerprint = fingerprint;
    return true;
}

[[nodiscard]] jlong saturatingJlong(std::uint64_t value) noexcept {
    constexpr std::uint64_t kMax =
            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    return value > kMax ? std::numeric_limits<jlong>::max()
                        : static_cast<jlong>(value);
}

jlongArray shadowResult(JNIEnv *env, jint status, jlong operation,
                        jlong expectedBits,
                        const jlmem::v2::KnownScanStats *stats = nullptr) {
    std::array<jlong, 9> values{};
    values[0] = status;
    if (stats != nullptr) {
        values[1] = saturatingJlong(stats->bytesScanned);
        values[2] = saturatingJlong(stats->typedMatches);
        values[3] = saturatingJlong(stats->uniqueAddresses);
        values[4] = saturatingJlong(static_cast<std::uint64_t>(stats->blockCount));
        values[5] = saturatingJlong(static_cast<std::uint64_t>(stats->retainedBytes));
        values[6] = static_cast<jlong>(stats->addressFingerprint);
    }
    values[7] = operation;
    values[8] = expectedBits;
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                values.data());
    }
    return result;
}

jlongArray runShadowKnown(JNIEnv *env, jint valueType, jint rawPredicate,
                          jlong scanFirstBits, jlong scanSecondBits,
                          jlong refineFirstBits, jlong refineSecondBits) {
    if (valueType == kTypeAuto) {
        return shadowResult(env, kInvalidRequest, kShadowOperationScan,
                            scanFirstBits);
    }
    jlmem::v2::ResultPlane plane = jlmem::v2::ResultPlane::Int;
    jlmem::v2::KnownPredicate predicate = jlmem::v2::KnownPredicate::Equal;
    if (!resultPlane(valueType, plane) ||
        !knownPredicate(rawPredicate, predicate)) {
        return shadowResult(env, kInvalidRequest, kShadowOperationScan,
                            scanFirstBits);
    }

    ShadowTarget target;
    std::shared_ptr<const jlmem::v2::ResultStore> priorStore;
    {
        std::lock_guard<std::mutex> lock(gShadowMutex);
        target = gShadowTarget;
        if (gShadowSession.store != nullptr &&
            gShadowSession.targetGeneration == target.generation &&
            gShadowSession.plane == plane) {
            priorStore = gShadowSession.store;
        }
    }
    if (target.pid <= 0 || target.runtimeToken == 0 || target.ranges.empty()) {
        return shadowResult(env, kTargetLost, kShadowOperationScan,
                            scanFirstBits);
    }

    const bool refining = priorStore != nullptr;
    const jlong firstBits = refining ? refineFirstBits : scanFirstBits;
    const jlong secondBits = refining ? refineSecondBits : scanSecondBits;
    const jlong operation =
            refining ? kShadowOperationRefine : kShadowOperationScan;
    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    bool ok;
    if (refining) {
        ok = jlmem::v2::refineKnownExplicit(
                *priorStore,
                {plane, predicate, static_cast<std::uint64_t>(firstBits),
                 static_cast<std::uint64_t>(secondBits)},
                [&](std::uintptr_t address, void *output, std::size_t size) {
                    return readExact(target.pid, address, output, size);
                },
                [&]() { return !shadowTargetMatches(target); },
                store, stats, error);
        if (ok && !finishRefineStats(store, plane, stats)) {
            return shadowResult(env, kInvalidRequest, operation, firstBits);
        }
    } else {
        ok = jlmem::v2::scanKnownExplicit(
                target.ranges,
                {plane, predicate, static_cast<std::uint64_t>(firstBits),
                 static_cast<std::uint64_t>(secondBits)},
                [&](std::uintptr_t address, void *output, std::size_t size) {
                    return readExact(target.pid, address, output, size);
                },
                [&]() { return !shadowTargetMatches(target); },
                store, stats, error);
        if (ok && !validateCursor(store, plane, stats)) {
            return shadowResult(env, kInvalidRequest, operation, firstBits);
        }
    }
    if (!ok) {
        const bool targetChanged =
                error == "V2 shadow scan cancelled" ||
                error == "V2 shadow refine cancelled" ||
                error.rfind("Target range", 0U) == 0U ||
                error.rfind("Target block", 0U) == 0U;
        return shadowResult(env, targetChanged ? kTargetLost
                                              : kInvalidRequest,
                            operation, firstBits);
    }
    if (!shadowTargetMatches(target)) {
        return shadowResult(env, kTargetLost, operation, firstBits);
    }

    auto published =
            std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    {
        std::lock_guard<std::mutex> lock(gShadowMutex);
        if (gShadowTarget.generation != target.generation ||
            gShadowTarget.pid != target.pid ||
            gShadowTarget.runtimeToken != target.runtimeToken ||
            (refining && gShadowSession.store != priorStore)) {
            return shadowResult(env, kTargetLost, operation, firstBits);
        }
        gShadowSession.targetGeneration = target.generation;
        gShadowSession.plane = plane;
        gShadowSession.store = std::move(published);
    }
    return shadowResult(env, kOk, operation, firstBits, &stats);
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
    try {
        if (pid > 0 && runtimeToken != 0 && rawRuns != nullptr) {
            const jsize length = env->GetArrayLength(rawRuns);
            if (length >= 4 && (length - 2) % 2 == 0) {
                std::vector<jlong> values(static_cast<std::size_t>(length));
                env->GetLongArrayRegion(rawRuns, 0, length, values.data());
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                } else {
                    const jlong declaredRuns = values[0];
                    if (values[1] == 0 && declaredRuns > 0 &&
                        static_cast<std::uint64_t>(declaredRuns) <= kMaxTargetRuns &&
                        declaredRuns == (length - 2) / 2) {
                        next.pid = pid;
                        next.runtimeToken = runtimeToken;
                        next.ranges.reserve(static_cast<std::size_t>(declaredRuns));
                        std::uintptr_t previousEnd = 0U;
                        bool valid = true;
                        for (jsize index = 2; index < length; index += 2) {
                            if (values[index] <= 0 ||
                                values[index + 1] <= values[index] ||
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
        }
    } catch (...) {
        // The v2 mirror is debug diagnostics only. Failure must never destabilize the validated
        // legacy target configuration or leak a C++ exception across JNI.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        next = {};
    }

    std::lock_guard<std::mutex> lock(gShadowMutex);
    next.generation = gShadowTarget.generation + 1U;
    gShadowTarget = std::move(next);
    gShadowSession = {};
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
    gShadowSession = {};
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2ShadowKnown(
        JNIEnv *env, jclass, jint valueType, jint predicate,
        jlong firstBits, jlong secondBits) {
    try {
        // Inputs are already canonical typed bits from the authoritative parser. Never parse query
        // strings in this shadow path.
        return runShadowKnown(env, valueType, predicate,
                              firstBits, secondBits, firstBits, secondBits);
    } catch (const std::bad_alloc &) {
        return shadowResult(env, kResourceLimit, kShadowOperationScan, firstBits);
    } catch (...) {
        return shadowResult(env, kInvalidRequest, kShadowOperationScan, firstBits);
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2ShadowKnownEqual(
        JNIEnv *env, jclass, jint valueType, jlong initialBits, jlong currentBits) {
    try {
        // Compatibility diagnostics infer the first Equal threshold from the legacy first result
        // and the later threshold from the refined result. Both routes still exercise the generic
        // canonical-plan kernel used by v2ShadowKnown().
        return runShadowKnown(env, valueType, kPredicateEqual,
                              initialBits, 0, currentBits, 0);
    } catch (const std::bad_alloc &) {
        return shadowResult(env, kResourceLimit, kShadowOperationScan, 0);
    } catch (...) {
        return shadowResult(env, kInvalidRequest, kShadowOperationScan, 0);
    }
}
