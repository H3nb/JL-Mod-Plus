#pragma once

#include "result_store.h"

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace jlmem::v2 {

struct ScanRange {
    uintptr_t start = 0;
    uintptr_t end = 0;
};

struct KnownScanRequest {
    ResultType type = ResultType::Int;
    int predicate = 0;
    std::string firstValue;
    std::string secondValue;
};

struct KnownScanStats {
    uint64_t bytesScanned = 0;
    uint64_t typedMatches = 0;
    uint64_t uniqueAddresses = 0;
};

using RemoteReadFn = std::function<bool(uintptr_t, void *, size_t)>;
using CancelledFn = std::function<bool()>;

// Shadow scanner used for differential validation against the published Candidate backend.
// It currently supports one explicit type at a time and intentionally does not publish results.
bool scanKnownExplicit(const std::vector<ScanRange> &ranges,
                       const KnownScanRequest &request,
                       const RemoteReadFn &read,
                       const CancelledFn &cancelled,
                       ResultStore &out,
                       KnownScanStats &stats,
                       std::string &error);

} // namespace jlmem::v2
