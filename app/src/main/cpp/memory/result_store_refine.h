/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "result_store_scan.h"

#include <cstdint>
#include <string>

namespace jlmem::v2 {

// Scan and refine intentionally share one canonical parsed Known-query representation. This keeps
// type/predicate/threshold semantics identical across revisions and gives the production legacy
// parser one eventual hand-off contract into v2.
using KnownRefineRequest = KnownQueryPlan;

struct KnownEqualRefineRequest {
    ResultPlane plane = ResultPlane::Int;
    std::uint64_t expectedBits = 0U;
};

// Transactionally refines one explicit primitive plane. The published source revision is never
// mutated: a working ResultStore copy is edited by clearing failed membership bits and is only
// moved to `out` after every required target read succeeds. Empty blocks and allocated plane
// payloads remain in place so revision-local block/payload indices stay stable.
[[nodiscard]] bool refineKnownExplicit(
        const ResultStore &source,
        const KnownRefineRequest &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error);

// Compatibility wrapper used by the current equality shadow diagnostics. It routes through the
// generic predicate kernel so equality parity continuously exercises the new implementation.
[[nodiscard]] bool refineKnownEqualExplicit(
        const ResultStore &source,
        const KnownEqualRefineRequest &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error);

} // namespace jlmem::v2
