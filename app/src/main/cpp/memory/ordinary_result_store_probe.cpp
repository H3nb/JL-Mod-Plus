/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "known_query_plan.h"
#include "ordinary_result_store.h"
#include "result_alias_cursor.h"
#include "result_store.h"

#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>

namespace {

constexpr jint kOk = 0;
constexpr jint kInvalidRequest = 2;
constexpr jint kResourceLimit = 3;
constexpr std::uintptr_t kBaseAddress = 0x00100000U;

struct ExpectedAlias {
    std::uintptr_t address;
    jlmem::v2::ResultPlane plane;
};

constexpr std::array<ExpectedAlias, 7> kExpectedAliases{{
        {kBaseAddress, jlmem::v2::ResultPlane::Int},
        {kBaseAddress, jlmem::v2::ResultPlane::Float},
        {kBaseAddress, jlmem::v2::ResultPlane::Byte},
        {kBaseAddress + 2U, jlmem::v2::ResultPlane::Short},
        {kBaseAddress + 2U, jlmem::v2::ResultPlane::Char},
        {kBaseAddress + 8U, jlmem::v2::ResultPlane::Long},
        {kBaseAddress + 8U, jlmem::v2::ResultPlane::Double},
}};

[[nodiscard]] jlong saturatingJlong(std::size_t value) noexcept {
    const auto maximum = static_cast<std::size_t>(std::numeric_limits<jlong>::max());
    return static_cast<jlong>(value > maximum ? maximum : value);
}

[[nodiscard]] jlongArray makeResult(JNIEnv *env, jint status,
                                    std::uint64_t typed = 0U,
                                    std::uint64_t unique = 0U,
                                    std::size_t retained = 0U,
                                    std::uint64_t pageCount = 0U,
                                    std::uint64_t validIdentities = 0U,
                                    std::uint64_t fingerprint = 0U) {
    const std::array<jlong, 8> values{
            status,
            static_cast<jlong>(typed),
            static_cast<jlong>(unique),
            static_cast<jlong>(sizeof(jlmem::v2::OrdinaryResultRecord)),
            saturatingJlong(retained),
            static_cast<jlong>(pageCount),
            static_cast<jlong>(validIdentities),
            static_cast<jlong>(fingerprint),
    };
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

[[nodiscard]] bool buildStore(jlmem::v2::ResultStore &store) {
    jlmem::v2::ResultBlockScratch scratch;
    if (!scratch.set(jlmem::v2::ResultPlane::Int, 0U) ||
        !scratch.set(jlmem::v2::ResultPlane::Float, 0U) ||
        !scratch.set(jlmem::v2::ResultPlane::Byte, 0U) ||
        !scratch.set(jlmem::v2::ResultPlane::Short, 1U) ||
        !scratch.set(jlmem::v2::ResultPlane::Char, 1U) ||
        !scratch.set(jlmem::v2::ResultPlane::Long, 1U) ||
        !scratch.set(jlmem::v2::ResultPlane::Double, 1U)) {
        return false;
    }
    return store.appendNonEmptyBlock(kBaseAddress, scratch) &&
           store.typedCount() == kExpectedAliases.size() &&
           store.uniqueAddressCount() == 3U;
}

[[nodiscard]] bool buildOrdinaryStore(jlmem::v2::OrdinaryResultStore &ordinary) {
    if (!ordinary.reserve(kExpectedAliases.size())) {
        return false;
    }
    for (std::size_t index = 0U; index < kExpectedAliases.size(); ++index) {
        const std::uint64_t value = static_cast<std::uint64_t>(index + 1U);
        const jlmem::v2::OrdinaryResultRecord record{
                1000U + value,
                value,
                value + 10U,
                value + 20U,
                UINT64_C(0xabc0000000000000) + value,
        };
        if (!ordinary.append(record, index % 2U == 0U)) {
            return false;
        }
    }
    return ordinary.size() == kExpectedAliases.size();
}

[[nodiscard]] bool verifyAliasAndOrdinaryStores(
        const jlmem::v2::ResultStore &store,
        const jlmem::v2::OrdinaryResultStore &ordinary,
        std::uint64_t &pageCount,
        std::uint64_t &validIdentities,
        std::uint64_t &fingerprint) {
    jlmem::v2::ResultAliasCursor cursor;
    std::size_t ordinal = 0U;
    pageCount = 0U;
    validIdentities = 0U;
    fingerprint = UINT64_C(1469598103934665603);

    // A two-row page deliberately cuts the first address after Int/Float, leaving Byte pending.
    // Later pages cut Short/Char and Long/Double groups as well.
    while (ordinal < kExpectedAliases.size()) {
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(store, cursor, 2U, page) || page.rows.empty()) {
            return false;
        }
        ++pageCount;
        for (const jlmem::v2::ResultAliasRow &row : page.rows) {
            if (ordinal >= kExpectedAliases.size()) {
                return false;
            }
            const ExpectedAlias expected = kExpectedAliases[ordinal];
            const auto *record = ordinary.record(ordinal);
            if (record == nullptr || row.address != expected.address ||
                row.plane != expected.plane ||
                record->id != 1001U + ordinal ||
                record->currentBits != 21U + ordinal ||
                ordinary.identityValid(ordinal) != (ordinal % 2U == 0U)) {
                return false;
            }
            if (ordinary.identityValid(ordinal)) {
                ++validIdentities;
            }
            const int stableType = jlmem::v2::stableValueTypeFromResultPlane(row.plane);
            if (stableType <= 0) {
                return false;
            }
            fingerprint ^= static_cast<std::uint64_t>(row.address);
            fingerprint *= UINT64_C(1099511628211);
            fingerprint ^= static_cast<std::uint64_t>(stableType);
            fingerprint *= UINT64_C(1099511628211);
            ++ordinal;
        }
        cursor = page.next;
    }

    jlmem::v2::ResultAliasPage endPage;
    if (!jlmem::v2::readAliasPage(store, cursor, 1U, endPage) ||
        !endPage.rows.empty() || endPage.next.pendingAliasMask != 0U ||
        endPage.next.pendingAddress != 0U ||
        endPage.next.addressCursor.blockIndex != store.blockCount() ||
        endPage.next.addressCursor.nextByteOffset != 0U) {
        return false;
    }
    return ordinal == ordinary.size() && ordinal == store.typedCount();
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_MemoryV2OrdinaryResultStoreTest_nativeProbe(
        JNIEnv *env, jclass) {
    try {
        jlmem::v2::ResultStore store;
        jlmem::v2::OrdinaryResultStore ordinary;
        if (!buildStore(store) || !buildOrdinaryStore(ordinary)) {
            return makeResult(env, kResourceLimit);
        }
        std::uint64_t pageCount = 0U;
        std::uint64_t validIdentities = 0U;
        std::uint64_t fingerprint = 0U;
        if (!verifyAliasAndOrdinaryStores(
                    store, ordinary, pageCount, validIdentities, fingerprint)) {
            return makeResult(env, kInvalidRequest);
        }
        return makeResult(
                env, kOk, store.typedCount(), store.uniqueAddressCount(),
                ordinary.retainedBytes(), pageCount, validIdentities, fingerprint);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return makeResult(env, kResourceLimit);
    }
}
