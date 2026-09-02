/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

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

// Explicit-type equality kernel used by the debug shadow path before ResultStore becomes
// authoritative. Type dispatch occurs once before scanning; the hot slot loop is specialized for
// one primitive representation and never calls a per-candidate predicate/type dispatcher.
[[nodiscard]] bool scanKnownEqualExplicit(
        const std::vector<ScanRange> &ranges,
        const KnownEqualScanRequest &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error);

} // namespace jlmem::v2
