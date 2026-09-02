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

struct KnownExplicitScanRequest {
    ResultPlane plane = ResultPlane::Int;
    std::size_t width = 4U;
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
using MatchFn = std::function<bool(std::uint64_t)>;
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

// Shadow scanner used for differential validation against the published Candidate backend.
// Parsing and predicate semantics deliberately remain owned by memory_engine.cpp: the caller
// supplies a compiled match function so the shadow path cannot drift on signedness, hex parsing,
// floating-point, NaN, or range behavior. This function never publishes engine state.
[[nodiscard]] bool scanKnownExplicit(
        const std::vector<ScanRange> &ranges,
        const KnownExplicitScanRequest &request,
        const RemoteReadFn &read,
        const MatchFn &matches,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error);

} // namespace jlmem::v2
