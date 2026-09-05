/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_relative.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cstring>
#include <limits>
#include <type_traits>
#include <utility>
#include <vector>

namespace jlmem::v2 {
namespace {

constexpr std::size_t kRelativeReadChunkSize = 256U * 1024U;
constexpr std::size_t kRelativeReadOverlap = 7U;

[[nodiscard]] std::uintptr_t alignUp(std::uintptr_t value,
                                     std::size_t alignment) noexcept {
    if (alignment == 0U) {
        return std::numeric_limits<std::uintptr_t>::max();
    }
    const std::uintptr_t remainder = value % alignment;
    if (remainder == 0U) {
        return value;
    }
    const std::uintptr_t delta = alignment - remainder;
    return value > std::numeric_limits<std::uintptr_t>::max() - delta
                   ? std::numeric_limits<std::uintptr_t>::max()
                   : value + delta;
}

[[nodiscard]] bool rangeEnd(const RelativeBaselineRange &range,
                            std::uintptr_t &end) noexcept {
    if (range.baseline.empty() ||
        range.baseline.size() >
                std::numeric_limits<std::uintptr_t>::max() - range.start) {
        return false;
    }
    end = range.start + range.baseline.size();
    return end > range.start;
}

template <typename T>
[[nodiscard]] std::uint64_t rawBits(T value) noexcept {
    if constexpr (std::is_floating_point_v<T>) {
        if constexpr (sizeof(T) == sizeof(std::uint32_t)) {
            return static_cast<std::uint64_t>(std::bit_cast<std::uint32_t>(value));
        } else {
            return std::bit_cast<std::uint64_t>(value);
        }
    } else {
        using Unsigned = std::make_unsigned_t<T>;
        return static_cast<std::uint64_t>(static_cast<Unsigned>(value));
    }
}

template <typename T, ResultPlane Plane, RelativePredicate Predicate>
[[nodiscard]] bool scanRelativeTyped(
        const std::vector<RelativeBaselineRange> &ranges,
        const RelativeQueryPlan &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    constexpr std::size_t kWidth = sizeof(T);

    ResultStore next;
    KnownScanStats nextStats;
    ResultBlockScratch scratch;
    std::vector<std::uint8_t> current;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    std::uintptr_t previousRangeEnd = 0U;

    const auto flushScratch = [&]() -> bool {
        if (!scratchActive) {
            return true;
        }
        if (!scratch.empty() && !next.appendNonEmptyBlock(scratchBase, scratch)) {
            error = "ResultStore rejected an ordered relative result block";
            return false;
        }
        scratch.reset();
        scratchBase = 0U;
        scratchActive = false;
        return true;
    };

    for (const RelativeBaselineRange &range : ranges) {
        std::uintptr_t end = 0U;
        if (!rangeEnd(range, end)) {
            continue;
        }
        if (previousRangeEnd != 0U && range.start < previousRangeEnd) {
            error = "Relative baseline ranges are not address ordered";
            return false;
        }
        previousRangeEnd = end;

        for (std::size_t logicalOffset = 0U;
             logicalOffset < range.baseline.size();) {
            if (cancelled && cancelled()) {
                error = "Relative baseline scan cancelled";
                return false;
            }
            const std::size_t remaining = range.baseline.size() - logicalOffset;
            const std::size_t logicalSize =
                    std::min(remaining, kRelativeReadChunkSize);
            const std::size_t readSize = std::min(
                    remaining,
                    logicalSize + std::min(kRelativeReadOverlap,
                                           remaining - logicalSize));
            if (logicalOffset >
                std::numeric_limits<std::uintptr_t>::max() - range.start) {
                error = "Relative scan address overflow";
                return false;
            }
            const std::uintptr_t chunkStart = range.start + logicalOffset;
            current.resize(readSize);
            if (!read(chunkStart, current.data(), readSize)) {
                error = "Target range changed during relative baseline scan";
                return false;
            }
            nextStats.bytesScanned += static_cast<std::uint64_t>(logicalSize);

            std::uintptr_t address = alignUp(chunkStart, kWidth);
            const std::uintptr_t logicalEnd = chunkStart + logicalSize;
            while (address >= chunkStart && address < logicalEnd) {
                const std::size_t chunkOffset =
                        static_cast<std::size_t>(address - chunkStart);
                const std::size_t baselineOffset = logicalOffset + chunkOffset;
                if (chunkOffset > readSize || kWidth > readSize - chunkOffset ||
                    baselineOffset > range.baseline.size() ||
                    kWidth > range.baseline.size() - baselineOffset) {
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

                T initial{};
                T now{};
                std::memcpy(&initial, range.baseline.data() + baselineOffset,
                            kWidth);
                std::memcpy(&now, current.data() + chunkOffset, kWidth);
                if (matchesRelativeValue<T, Predicate>(
                            now, initial, request.firstBits,
                            request.secondBits)) {
                    const std::size_t byteOffset =
                            static_cast<std::size_t>(address - blockBase);
                    if (!scratch.set(Plane, byteOffset / kWidth)) {
                        error = "ResultStore rejected a relative result slot";
                        return false;
                    }
                    nextStats.addressFingerprint = appendAddressFingerprint(
                            nextStats.addressFingerprint, address, Plane);
                    if (observer != nullptr && observer->onMatch != nullptr &&
                        !observer->onMatch(
                                observer->opaque,
                                RelativeScanMatchView{
                                        address,
                                        rawBits(initial),
                                        rawBits(now),
                                        range.baseline.data(),
                                        range.baseline.size(),
                                        baselineOffset,
                                        kWidth,
                                        Plane,
                                })) {
                        error = "Relative compatibility observer rejected a match";
                        return false;
                    }
                }

                if (address >
                    std::numeric_limits<std::uintptr_t>::max() - kWidth) {
                    break;
                }
                address += kWidth;
            }

            if (observer != nullptr && observer->onProgress != nullptr) {
                observer->onProgress(observer->opaque, logicalSize);
            }
            logicalOffset += logicalSize;
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

template <typename T, ResultPlane Plane>
[[nodiscard]] bool dispatchRelativePredicate(
        const std::vector<RelativeBaselineRange> &ranges,
        const RelativeQueryPlan &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer) {
    switch (request.predicate) {
    case RelativePredicate::Changed:
        return scanRelativeTyped<T, Plane, RelativePredicate::Changed>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Unchanged:
        return scanRelativeTyped<T, Plane, RelativePredicate::Unchanged>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Increased:
        return scanRelativeTyped<T, Plane, RelativePredicate::Increased>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Decreased:
        return scanRelativeTyped<T, Plane, RelativePredicate::Decreased>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::IncreasedBy:
        return scanRelativeTyped<T, Plane, RelativePredicate::IncreasedBy>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::DecreasedBy:
        return scanRelativeTyped<T, Plane, RelativePredicate::DecreasedBy>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::ChangedBy:
        return scanRelativeTyped<T, Plane, RelativePredicate::ChangedBy>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::IncreasedByRange:
        return scanRelativeTyped<T, Plane, RelativePredicate::IncreasedByRange>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case RelativePredicate::DecreasedByRange:
        return scanRelativeTyped<T, Plane, RelativePredicate::DecreasedByRange>(
                ranges, request, read, cancelled, out, stats, error, observer);
    }
    error = "Invalid relative predicate";
    return false;
}

[[nodiscard]] bool prepareAutoRequests(
        const std::vector<RelativeQueryPlan> &requests,
        std::array<const RelativeQueryPlan *, kResultPlaneCount> &byPlane,
        RelativePredicate &predicate,
        std::string &error) {
    if (requests.empty() || requests.size() > kResultPlaneCount) {
        error = "Invalid fused Auto relative scan request";
        return false;
    }
    byPlane.fill(nullptr);
    predicate = requests.front().predicate;
    for (const RelativeQueryPlan &request : requests) {
        if (!validRelativeQueryPlan(request) || request.predicate != predicate ||
            request.plane == ResultPlane::Count) {
            error = "Invalid fused Auto relative scan request";
            return false;
        }
        const std::size_t index = planeIndex(request.plane);
        if (index >= byPlane.size() || byPlane[index] != nullptr) {
            error = "Fused Auto relative scan contains duplicate primitive planes";
            return false;
        }
        byPlane[index] = &request;
    }
    return true;
}

template <typename T, ResultPlane Plane, RelativePredicate Predicate>
[[nodiscard]] bool recordAutoMatch(
        const RelativeQueryPlan *request, T initial, T now,
        std::uintptr_t address, std::uintptr_t blockBase,
        const RelativeBaselineRange &range, std::size_t baselineOffset,
        ResultBlockScratch &scratch, KnownScanStats &stats,
        std::string &error, const RelativeScanObserver *observer) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    if (request == nullptr ||
        !matchesRelativeValue<T, Predicate>(
                now, initial, request->firstBits, request->secondBits)) {
        return true;
    }
    const std::size_t byteOffset =
            static_cast<std::size_t>(address - blockBase);
    if (!scratch.set(Plane, byteOffset / sizeof(T))) {
        error = "ResultStore rejected a fused relative result slot";
        return false;
    }
    stats.addressFingerprint =
            appendAddressFingerprint(stats.addressFingerprint, address, Plane);
    if (observer != nullptr && observer->onMatch != nullptr &&
        !observer->onMatch(
                observer->opaque,
                RelativeScanMatchView{
                        address,
                        rawBits(initial),
                        rawBits(now),
                        range.baseline.data(),
                        range.baseline.size(),
                        baselineOffset,
                        sizeof(T),
                        Plane,
                })) {
        error = "Fused relative compatibility observer rejected a match";
        return false;
    }
    return true;
}

template <RelativePredicate Predicate>
[[nodiscard]] bool scanRelativeAutoPredicate(
        const std::vector<RelativeBaselineRange> &ranges,
        const std::array<const RelativeQueryPlan *, kResultPlaneCount> &requests,
        const RemoteReadFn &read, const CancelledFn &cancelled,
        ResultStore &out, KnownScanStats &stats, std::string &error,
        const RelativeScanObserver *observer) {
    ResultStore next;
    KnownScanStats nextStats;
    ResultBlockScratch scratch;
    std::vector<std::uint8_t> current;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    std::uintptr_t previousRangeEnd = 0U;

    const auto flushScratch = [&]() -> bool {
        if (!scratchActive) {
            return true;
        }
        if (!scratch.empty() && !next.appendNonEmptyBlock(scratchBase, scratch)) {
            error = "ResultStore rejected an ordered fused relative result block";
            return false;
        }
        scratch.reset();
        scratchBase = 0U;
        scratchActive = false;
        return true;
    };

    const auto byteRequest = requests[planeIndex(ResultPlane::Byte)];
    const auto shortRequest = requests[planeIndex(ResultPlane::Short)];
    const auto charRequest = requests[planeIndex(ResultPlane::Char)];
    const auto intRequest = requests[planeIndex(ResultPlane::Int)];
    const auto floatRequest = requests[planeIndex(ResultPlane::Float)];
    const auto longRequest = requests[planeIndex(ResultPlane::Long)];
    const auto doubleRequest = requests[planeIndex(ResultPlane::Double)];

    for (const RelativeBaselineRange &range : ranges) {
        std::uintptr_t end = 0U;
        if (!rangeEnd(range, end)) {
            continue;
        }
        if (previousRangeEnd != 0U && range.start < previousRangeEnd) {
            error = "Relative baseline ranges are not address ordered";
            return false;
        }
        previousRangeEnd = end;

        for (std::size_t logicalOffset = 0U;
             logicalOffset < range.baseline.size();) {
            if (cancelled && cancelled()) {
                error = "Relative baseline scan cancelled";
                return false;
            }
            const std::size_t remaining = range.baseline.size() - logicalOffset;
            const std::size_t logicalSize =
                    std::min(remaining, kRelativeReadChunkSize);
            const std::size_t readSize = std::min(
                    remaining,
                    logicalSize + std::min(kRelativeReadOverlap,
                                           remaining - logicalSize));
            const std::uintptr_t chunkStart = range.start + logicalOffset;
            current.resize(readSize);
            if (!read(chunkStart, current.data(), readSize)) {
                error = "Target range changed during fused relative baseline scan";
                return false;
            }
            nextStats.bytesScanned += static_cast<std::uint64_t>(logicalSize);

            for (std::size_t offset = 0U; offset < logicalSize; ++offset) {
                const std::size_t baselineOffset = logicalOffset + offset;
                const std::uintptr_t address = chunkStart + offset;
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

                if ((intRequest != nullptr || floatRequest != nullptr) &&
                    address % 4U == 0U && 4U <= readSize - offset &&
                    4U <= range.baseline.size() - baselineOffset) {
                    std::uint32_t initialBits = 0U;
                    std::uint32_t currentBits = 0U;
                    std::memcpy(&initialBits,
                                range.baseline.data() + baselineOffset, 4U);
                    std::memcpy(&currentBits, current.data() + offset, 4U);
                    if (!recordAutoMatch<std::int32_t, ResultPlane::Int, Predicate>(
                                intRequest, std::bit_cast<std::int32_t>(initialBits),
                                std::bit_cast<std::int32_t>(currentBits), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<float, ResultPlane::Float, Predicate>(
                                floatRequest, std::bit_cast<float>(initialBits),
                                std::bit_cast<float>(currentBits), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if ((longRequest != nullptr || doubleRequest != nullptr) &&
                    address % 8U == 0U && 8U <= readSize - offset &&
                    8U <= range.baseline.size() - baselineOffset) {
                    std::uint64_t initialBits = 0U;
                    std::uint64_t currentBits = 0U;
                    std::memcpy(&initialBits,
                                range.baseline.data() + baselineOffset, 8U);
                    std::memcpy(&currentBits, current.data() + offset, 8U);
                    if (!recordAutoMatch<std::int64_t, ResultPlane::Long, Predicate>(
                                longRequest, std::bit_cast<std::int64_t>(initialBits),
                                std::bit_cast<std::int64_t>(currentBits), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<double, ResultPlane::Double, Predicate>(
                                doubleRequest, std::bit_cast<double>(initialBits),
                                std::bit_cast<double>(currentBits), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if ((shortRequest != nullptr || charRequest != nullptr) &&
                    address % 2U == 0U && 2U <= readSize - offset &&
                    2U <= range.baseline.size() - baselineOffset) {
                    std::uint16_t initialBits = 0U;
                    std::uint16_t currentBits = 0U;
                    std::memcpy(&initialBits,
                                range.baseline.data() + baselineOffset, 2U);
                    std::memcpy(&currentBits, current.data() + offset, 2U);
                    if (!recordAutoMatch<std::int16_t, ResultPlane::Short, Predicate>(
                                shortRequest, std::bit_cast<std::int16_t>(initialBits),
                                std::bit_cast<std::int16_t>(currentBits), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<std::uint16_t, ResultPlane::Char, Predicate>(
                                charRequest, initialBits, currentBits, address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if (byteRequest != nullptr) {
                    if (!recordAutoMatch<std::int8_t, ResultPlane::Byte, Predicate>(
                                byteRequest,
                                static_cast<std::int8_t>(range.baseline[baselineOffset]),
                                static_cast<std::int8_t>(current[offset]), address,
                                blockBase, range, baselineOffset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
            }

            if (observer != nullptr && observer->onProgress != nullptr) {
                observer->onProgress(observer->opaque, logicalSize);
            }
            logicalOffset += logicalSize;
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

[[nodiscard]] bool dispatchAutoPredicate(
        RelativePredicate predicate,
        const std::vector<RelativeBaselineRange> &ranges,
        const std::array<const RelativeQueryPlan *, kResultPlaneCount> &requests,
        const RemoteReadFn &read, const CancelledFn &cancelled,
        ResultStore &out, KnownScanStats &stats, std::string &error,
        const RelativeScanObserver *observer) {
    switch (predicate) {
    case RelativePredicate::Changed:
        return scanRelativeAutoPredicate<RelativePredicate::Changed>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Unchanged:
        return scanRelativeAutoPredicate<RelativePredicate::Unchanged>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Increased:
        return scanRelativeAutoPredicate<RelativePredicate::Increased>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::Decreased:
        return scanRelativeAutoPredicate<RelativePredicate::Decreased>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::IncreasedBy:
        return scanRelativeAutoPredicate<RelativePredicate::IncreasedBy>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::DecreasedBy:
        return scanRelativeAutoPredicate<RelativePredicate::DecreasedBy>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::ChangedBy:
        return scanRelativeAutoPredicate<RelativePredicate::ChangedBy>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::IncreasedByRange:
        return scanRelativeAutoPredicate<RelativePredicate::IncreasedByRange>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case RelativePredicate::DecreasedByRange:
        return scanRelativeAutoPredicate<RelativePredicate::DecreasedByRange>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    }
    error = "Invalid fused Auto relative predicate";
    return false;
}

} // namespace

bool scanRelativeExplicit(
        const std::vector<RelativeBaselineRange> &ranges,
        const RelativeQueryPlan &request,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer) {
    if (!read || !validRelativeQueryPlan(request)) {
        error = "Invalid explicit relative scan request";
        return false;
    }
    switch (request.plane) {
    case ResultPlane::Byte:
        return dispatchRelativePredicate<std::int8_t, ResultPlane::Byte>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Short:
        return dispatchRelativePredicate<std::int16_t, ResultPlane::Short>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Char:
        return dispatchRelativePredicate<std::uint16_t, ResultPlane::Char>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Int:
        return dispatchRelativePredicate<std::int32_t, ResultPlane::Int>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Float:
        return dispatchRelativePredicate<float, ResultPlane::Float>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Long:
        return dispatchRelativePredicate<std::int64_t, ResultPlane::Long>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Double:
        return dispatchRelativePredicate<double, ResultPlane::Double>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Count:
        break;
    }
    error = "Invalid explicit relative scan request";
    return false;
}

bool scanRelativeAuto(
        const std::vector<RelativeBaselineRange> &ranges,
        const std::vector<RelativeQueryPlan> &requests,
        const RemoteReadFn &read,
        const CancelledFn &cancelled,
        ResultStore &out,
        KnownScanStats &stats,
        std::string &error,
        const RelativeScanObserver *observer) {
    if (!read) {
        error = "Invalid fused Auto relative scan request";
        return false;
    }
    std::array<const RelativeQueryPlan *, kResultPlaneCount> byPlane{};
    RelativePredicate predicate = RelativePredicate::Changed;
    if (!prepareAutoRequests(requests, byPlane, predicate, error)) {
        return false;
    }
    return dispatchAutoPredicate(predicate, ranges, byPlane, read, cancelled,
                                 out, stats, error, observer);
}

} // namespace jlmem::v2
