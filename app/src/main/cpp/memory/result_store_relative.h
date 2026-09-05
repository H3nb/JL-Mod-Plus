/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "relative_query_plan.h"
#include "result_store_scan.h"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace jlmem::v2 {

// Borrowed baseline bytes captured transactionally by the target revision. The kernel never owns
// or mutates them; it streams only the current target bytes in bounded chunks.
struct RelativeBaselineRange {
    std::uintptr_t start = 0U;
    std::span<const std::uint8_t> baseline{};
};

struct RelativeScanMatchView {
    std::uintptr_t address = 0U;
    std::uint64_t initialBits = 0U;
    std::uint64_t currentBits = 0U;
    const std::uint8_t *baselineBytes = nullptr;
    std::size_t baselineSize = 0U;
    std::size_t baselineOffset = 0U;
    std::size_t width = 0U;
    ResultPlane plane = ResultPlane::Count;
};

using RelativeMatchObserverFn = bool (*)(void *, const RelativeScanMatchView &);
using RelativeProgressObserverFn = void (*)(void *, std::size_t);

struct RelativeScanObserver {
    void *opaque = nullptr;
    RelativeMatchObserverFn onMatch = nullptr;
    RelativeProgressObserverFn onProgress = nullptr;
};

// First relative scan from an Unknown baseline. ResultStore decides membership; the observer is
// only a transitional materializer for Candidate metadata and may not change predicate semantics.
[[nodiscard]] bool scanRelativeExplicit(
        const std::vector<RelativeBaselineRange> &ranges,
        const RelativeQueryPlan &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer = nullptr);

// Fused Auto variant. At each raw address compatible primitive interpretations share baseline and
// current loads, while every surviving typed interpretation becomes one ResultStore plane alias.
[[nodiscard]] bool scanRelativeAuto(
        const std::vector<RelativeBaselineRange> &ranges,
        const std::vector<RelativeQueryPlan> &requests,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer = nullptr);

} // namespace jlmem::v2
