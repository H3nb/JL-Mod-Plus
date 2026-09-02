/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_scan.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <vector>

namespace jlmem::v2 {
namespace {

constexpr std::size_t kRemoteReadChunkSize = 256U * 1024U;

[[nodiscard]] std::uint64_t loadBits(const std::uint8_t *data,
                                     std::size_t width) noexcept {
    std::uint64_t bits = 0U;
    std::memcpy(&bits, data, width);
    return bits;
}

[[nodiscard]] std::uintptr_t alignUp(std::uintptr_t value,
                                     std::size_t alignment) noexcept {
    const std::uintptr_t remainder = value % alignment;
    if (remainder == 0U) {
        return value;
    }
    const std::uintptr_t delta = alignment - remainder;
    return value > std::numeric_limits<std::uintptr_t>::max() - delta
                   ? std::numeric_limits<std::uintptr_t>::max()
                   : value + delta;
}

} // namespace

bool scanKnownExplicit(const std::vector<ScanRange> &ranges,
                       const KnownExplicitScanRequest &request,
                       const RemoteReadFn &read,
                       const MatchFn &matches,
                       const CancelledFn &cancelled,
                       ResultStore &out,
                       KnownScanStats &stats,
                       std::string &error) {
    if (request.plane == ResultPlane::Count || request.width == 0U ||
        request.width != planeAlignment(request.plane) || !read || !matches) {
        error = "Invalid explicit-type v2 shadow scan request";
        return false;
    }

    ResultStore next;
    KnownScanStats nextStats;
    ResultBlockScratch scratch;
    std::vector<std::uint8_t> buffer;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    std::uintptr_t previousRangeEnd = 0U;

    const auto flushScratch = [&]() -> bool {
        if (!scratchActive) {
            return true;
        }
        if (!scratch.empty() && !next.appendNonEmptyBlock(scratchBase, scratch)) {
            error = "ResultStore rejected an ordered v2 shadow block";
            return false;
        }
        scratch.reset();
        scratchActive = false;
        scratchBase = 0U;
        return true;
    };

    for (const ScanRange &range : ranges) {
        if (range.end <= range.start) {
            continue;
        }
        if (previousRangeEnd != 0U && range.start < previousRangeEnd) {
            error = "V2 shadow scan ranges are not address ordered";
            return false;
        }
        previousRangeEnd = range.end;

        for (std::uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (cancelled && cancelled()) {
                error = "V2 shadow scan cancelled";
                return false;
            }
            const std::size_t remaining =
                    static_cast<std::size_t>(range.end - chunkStart);
            const std::size_t chunkSize =
                    std::min(remaining, kRemoteReadChunkSize);
            buffer.resize(chunkSize);
            if (!read(chunkStart, buffer.data(), chunkSize)) {
                error = "Target range changed during v2 shadow scan";
                return false;
            }
            nextStats.bytesScanned += static_cast<std::uint64_t>(chunkSize);

            std::uintptr_t address = alignUp(chunkStart, request.width);
            while (address >= chunkStart) {
                const std::size_t offset =
                        static_cast<std::size_t>(address - chunkStart);
                if (offset > chunkSize || request.width > chunkSize - offset) {
                    break;
                }

                const std::uintptr_t blockBase =
                        address & ~(static_cast<std::uintptr_t>(
                                            kResultLogicalBlockSize) -
                                    std::uintptr_t{1U});
                if (!scratchActive || blockBase != scratchBase) {
                    if (!flushScratch()) {
                        return false;
                    }
                    scratchBase = blockBase;
                    scratchActive = true;
                }

                const std::uint64_t bits =
                        loadBits(buffer.data() + offset, request.width);
                if (matches(bits)) {
                    const std::size_t byteOffset =
                            static_cast<std::size_t>(address - blockBase);
                    const std::size_t slot = byteOffset / request.width;
                    if (!scratch.set(request.plane, slot)) {
                        error = "ResultStore rejected a v2 shadow result slot";
                        return false;
                    }
                    nextStats.addressFingerprint = appendAddressFingerprint(
                            nextStats.addressFingerprint, address, request.plane);
                }

                if (address > std::numeric_limits<std::uintptr_t>::max() -
                                      request.width) {
                    break;
                }
                address += request.width;
            }

            chunkStart += chunkSize;
        }
    }

    if (!flushScratch()) {
        return false;
    }

    nextStats.typedMatches = next.typedCount();
    nextStats.uniqueAddresses = next.uniqueAddressCount();
    nextStats.blockCount = next.blockCount();
    nextStats.retainedBytes = next.retainedBytes();
    out = std::move(next);
    stats = nextStats;
    error.clear();
    return true;
}

} // namespace jlmem::v2
