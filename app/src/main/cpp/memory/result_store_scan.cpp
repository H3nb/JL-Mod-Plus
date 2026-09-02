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
#include <type_traits>
#include <utility>
#include <vector>

namespace jlmem::v2 {
namespace {

constexpr std::size_t kRemoteReadChunkSize = 256U * 1024U;

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

template <typename T>
[[nodiscard]] T valueFromBits(std::uint64_t bits) noexcept {
    static_assert(std::is_trivially_copyable_v<T>);
    static_assert(sizeof(T) <= sizeof(bits));
    T value{};
    std::memcpy(&value, &bits, sizeof(T));
    return value;
}

template <typename T, ResultPlane Plane>
[[nodiscard]] bool scanEqualTyped(const std::vector<ScanRange> &ranges,
                                  std::uint64_t expectedBits,
                                  const RemoteReadFn &read,
                                  const CancelledFn &cancelled,
                                  ResultStore &out,
                                  KnownScanStats &stats,
                                  std::string &error) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    constexpr std::size_t kWidth = sizeof(T);
    const T expected = valueFromBits<T>(expectedBits);

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

            std::uintptr_t address = alignUp(chunkStart, kWidth);
            while (address >= chunkStart) {
                const std::size_t offset =
                        static_cast<std::size_t>(address - chunkStart);
                if (offset > chunkSize || kWidth > chunkSize - offset) {
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

                T actual{};
                std::memcpy(&actual, buffer.data() + offset, kWidth);
                if (actual == expected) {
                    const std::size_t byteOffset =
                            static_cast<std::size_t>(address - blockBase);
                    const std::size_t slot = byteOffset / kWidth;
                    if (!scratch.set(Plane, slot)) {
                        error = "ResultStore rejected a v2 shadow result slot";
                        return false;
                    }
                    nextStats.addressFingerprint = appendAddressFingerprint(
                            nextStats.addressFingerprint, address, Plane);
                }

                if (address >
                    std::numeric_limits<std::uintptr_t>::max() - kWidth) {
                    break;
                }
                address += kWidth;
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

} // namespace

bool scanKnownEqualExplicit(const std::vector<ScanRange> &ranges,
                            const KnownEqualScanRequest &request,
                            const RemoteReadFn &read,
                            const CancelledFn &cancelled,
                            ResultStore &out,
                            KnownScanStats &stats,
                            std::string &error) {
    if (!read || request.plane == ResultPlane::Count) {
        error = "Invalid explicit-type equality shadow request";
        return false;
    }
    switch (request.plane) {
    case ResultPlane::Byte:
        return scanEqualTyped<std::int8_t, ResultPlane::Byte>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Short:
        return scanEqualTyped<std::int16_t, ResultPlane::Short>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Char:
        return scanEqualTyped<std::uint16_t, ResultPlane::Char>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Int:
        return scanEqualTyped<std::int32_t, ResultPlane::Int>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Float:
        return scanEqualTyped<float, ResultPlane::Float>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Long:
        return scanEqualTyped<std::int64_t, ResultPlane::Long>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Double:
        return scanEqualTyped<double, ResultPlane::Double>(
                ranges, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Count:
        break;
    }
    error = "Invalid explicit-type equality shadow request";
    return false;
}

} // namespace jlmem::v2
