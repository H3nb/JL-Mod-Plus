/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "known_query_plan.h"
#include "result_store.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace jlmem::v2 {

struct ScanRange {
    std::uintptr_t start = 0U;
    std::uintptr_t end = 0U;
};

// Transitional source alias: scan and refine now consume the same canonical parsed plan. Keep the
// old name while the legacy engine is still the production owner so this refactor does not create
// unrelated call-site churn.
using KnownScanRequest = KnownQueryPlan;

// Compatibility request retained while the existing equality-only diagnostics remain the first
// production caller of the generic known-predicate kernel.
struct KnownEqualScanRequest {
    ResultPlane plane = ResultPlane::Int;
    std::uint64_t expectedBits = 0U;
};

struct KnownScanStats {
    std::uint64_t bytesScanned = 0U;
    std::uint64_t typedMatches = 0U;
    std::uint64_t uniqueAddresses = 0U;
    std::uint64_t addressFingerprint = 1469598103934665603ULL;
    std::size_t blockCount = 0U;
    std::size_t retainedBytes = 0U;
};

using RemoteReadFn = std::function<bool(std::uintptr_t, void *, std::size_t)>;
using CancelledFn = std::function<bool()>;

// Optional production observer. The ResultStore kernel remains the only component deciding
// membership; the observer merely materializes compatibility metadata from each accepted slot and
// receives chunk-level progress. Raw chunk context lets the legacy Candidate mirror snapshot its
// passive identity fingerprint without issuing one remote read per result.
struct KnownScanMatchView {
    std::uintptr_t address = 0U;
    std::uint64_t bits = 0U;
    const std::uint8_t *chunkBytes = nullptr;
    std::size_t chunkSize = 0U;
    std::size_t chunkOffset = 0U;
    std::size_t width = 0U;
};

using KnownMatchObserverFn = bool (*)(void *, const KnownScanMatchView &);
using KnownProgressObserverFn = void (*)(void *, std::size_t);

struct KnownScanObserver {
    void *opaque = nullptr;
    KnownMatchObserverFn onMatch = nullptr;
    KnownProgressObserverFn onProgress = nullptr;
};

[[nodiscard]] constexpr std::uint64_t appendAddressFingerprint(
        std::uint64_t fingerprint, std::uintptr_t address,
        ResultPlane plane) noexcept {
    constexpr std::uint64_t kFnvPrime = 1099511628211ULL;
    fingerprint ^= static_cast<std::uint64_t>(address);
    fingerprint *= kFnvPrime;
    fingerprint ^= static_cast<std::uint64_t>(planeIndex(plane) + 1U);
    fingerprint *= kFnvPrime;
    return fingerprint;
}

// Explicit-type known-value kernel used by debug shadow and production ownership paths. Type and
// predicate dispatch both occur once before scanning; the hot slot loop is specialized for one
// primitive representation and one known predicate.
[[nodiscard]] bool scanKnownExplicit(
        const std::vector<ScanRange> &ranges,
        const KnownScanRequest &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const KnownScanObserver *observer = nullptr);

// Existing equality diagnostics call this wrapper; it deliberately routes through the generic
// kernel so every current parity run also exercises the shared predicate implementation.
[[nodiscard]] bool scanKnownEqualExplicit(
        const std::vector<ScanRange> &ranges,
        const KnownEqualScanRequest &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error);

} // namespace jlmem::v2
