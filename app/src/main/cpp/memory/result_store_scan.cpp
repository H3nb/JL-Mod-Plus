/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_scan.h"

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

template <typename T, ResultPlane Plane, KnownPredicate Predicate>
[[nodiscard]] bool scanKnownTyped(const std::vector<ScanRange> &ranges,
                                  std::uint64_t firstBits,
                                  std::uint64_t secondBits,
                                  const RemoteReadFn &read,
                                  const CancelledFn &cancelled,
                                  ResultStore &out,
                                  KnownScanStats &stats,
                                  std::string &error,
                                  const KnownScanObserver *observer) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    constexpr std::size_t kWidth = sizeof(T);

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
            error = "ResultStore rejected an ordered v2 result block";
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
            error = "V2 scan ranges are not address ordered";
            return false;
        }
        previousRangeEnd = range.end;

        for (std::uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (cancelled && cancelled()) {
                // Keep the established shadow diagnostic token while this kernel is shared by
                // shadow and production callers; the caller decides whether it is visible.
                error = "V2 known scan cancelled";
                return false;
            }
            const std::size_t remaining =
                    static_cast<std::size_t>(range.end - chunkStart);
            const std::size_t chunkSize =
                    std::min(remaining, kRemoteReadChunkSize);
            buffer.resize(chunkSize);
            if (!read(chunkStart, buffer.data(), chunkSize)) {
                error = "Target range changed during v2 known scan";
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
                if (matchesKnownValue<T, Predicate>(actual, firstBits, secondBits)) {
                    const std::size_t byteOffset =
                            static_cast<std::size_t>(address - blockBase);
                    const std::size_t slot = byteOffset / kWidth;
                    if (!scratch.set(Plane, slot)) {
                        error = "ResultStore rejected a v2 result slot";
                        return false;
                    }
                    nextStats.addressFingerprint = appendAddressFingerprint(
                            nextStats.addressFingerprint, address, Plane);
                    if (observer != nullptr && observer->onMatch != nullptr &&
                        !observer->onMatch(
                                observer->opaque,
                                KnownScanMatchView{
                                        address,
                                        rawBits(actual),
                                        buffer.data(),
                                        chunkSize,
                                        offset,
                                        kWidth,
                                        Plane,
                                })) {
                        error = "V2 known match observer rejected result";
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
                observer->onProgress(observer->opaque, chunkSize);
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

template <typename T, ResultPlane Plane>
[[nodiscard]] bool dispatchKnownPredicate(const std::vector<ScanRange> &ranges,
                                          const KnownScanRequest &request,
                                          const RemoteReadFn &read,
                                          const CancelledFn &cancelled,
                                          ResultStore &out,
                                          KnownScanStats &stats,
                                          std::string &error,
                                          const KnownScanObserver *observer) {
    switch (request.predicate) {
    case KnownPredicate::Equal:
        return scanKnownTyped<T, Plane, KnownPredicate::Equal>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::NotEqual:
        return scanKnownTyped<T, Plane, KnownPredicate::NotEqual>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Greater:
        return scanKnownTyped<T, Plane, KnownPredicate::Greater>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Less:
        return scanKnownTyped<T, Plane, KnownPredicate::Less>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::GreaterOrEqual:
        return scanKnownTyped<T, Plane, KnownPredicate::GreaterOrEqual>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::LessOrEqual:
        return scanKnownTyped<T, Plane, KnownPredicate::LessOrEqual>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Between:
        return scanKnownTyped<T, Plane, KnownPredicate::Between>(
                ranges, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    }
    error = "Invalid explicit-type known predicate";
    return false;
}

[[nodiscard]] bool prepareAutoRequests(
        const std::vector<KnownScanRequest> &requests,
        std::array<const KnownScanRequest *, kResultPlaneCount> &byPlane,
        KnownPredicate &predicate, std::string &error) {
    if (requests.empty() || requests.size() > kResultPlaneCount) {
        error = "Invalid fused Auto known scan request";
        return false;
    }
    byPlane.fill(nullptr);
    predicate = requests.front().predicate;
    for (const KnownScanRequest &request : requests) {
        if (!validKnownQueryPlan(request) || request.predicate != predicate ||
            request.plane == ResultPlane::Count) {
            error = "Invalid fused Auto known scan request";
            return false;
        }
        const std::size_t index = planeIndex(request.plane);
        if (index >= byPlane.size() || byPlane[index] != nullptr) {
            error = "Fused Auto known scan contains duplicate primitive planes";
            return false;
        }
        byPlane[index] = &request;
    }
    return true;
}

template <typename T, ResultPlane Plane, KnownPredicate Predicate>
[[nodiscard]] bool recordAutoMatch(
        const KnownScanRequest *request, T actual,
        std::uintptr_t address, std::uintptr_t blockBase,
        const std::uint8_t *chunkBytes, std::size_t chunkSize,
        std::size_t chunkOffset, ResultBlockScratch &scratch,
        KnownScanStats &stats, std::string &error,
        const KnownScanObserver *observer) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    if (request == nullptr) {
        return true;
    }
    if (!matchesKnownValue<T, Predicate>(
                actual, request->firstBits, request->secondBits)) {
        return true;
    }
    const std::size_t byteOffset =
            static_cast<std::size_t>(address - blockBase);
    const std::size_t slot = byteOffset / sizeof(T);
    if (!scratch.set(Plane, slot)) {
        error = "ResultStore rejected a fused Auto result slot";
        return false;
    }
    stats.addressFingerprint =
            appendAddressFingerprint(stats.addressFingerprint, address, Plane);
    if (observer != nullptr && observer->onMatch != nullptr &&
        !observer->onMatch(
                observer->opaque,
                KnownScanMatchView{
                        address,
                        rawBits(actual),
                        chunkBytes,
                        chunkSize,
                        chunkOffset,
                        sizeof(T),
                        Plane,
                })) {
        error = "V2 fused Auto match observer rejected result";
        return false;
    }
    return true;
}

template <KnownPredicate Predicate>
[[nodiscard]] bool scanKnownAutoPredicate(
        const std::vector<ScanRange> &ranges,
        const std::array<const KnownScanRequest *, kResultPlaneCount> &requests,
        const RemoteReadFn &read, const CancelledFn &cancelled,
        ResultStore &out, KnownScanStats &stats, std::string &error,
        const KnownScanObserver *observer) {
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
            error = "ResultStore rejected an ordered fused Auto result block";
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

    for (const ScanRange &range : ranges) {
        if (range.end <= range.start) {
            continue;
        }
        if (previousRangeEnd != 0U && range.start < previousRangeEnd) {
            error = "V2 scan ranges are not address ordered";
            return false;
        }
        previousRangeEnd = range.end;

        for (std::uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (cancelled && cancelled()) {
                error = "V2 known scan cancelled";
                return false;
            }
            const std::size_t remaining =
                    static_cast<std::size_t>(range.end - chunkStart);
            const std::size_t chunkSize =
                    std::min(remaining, kRemoteReadChunkSize);
            buffer.resize(chunkSize);
            if (!read(chunkStart, buffer.data(), chunkSize)) {
                error = "Target range changed during v2 known scan";
                return false;
            }
            nextStats.bytesScanned += static_cast<std::uint64_t>(chunkSize);

            for (std::size_t offset = 0U; offset < chunkSize; ++offset) {
                if (chunkStart >
                    std::numeric_limits<std::uintptr_t>::max() - offset) {
                    error = "Fused Auto scan address overflow";
                    return false;
                }
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

                // Preserve the production Candidate display priority while sharing physical loads:
                // Int, Float, Long, Double, Short, Char, Byte.
                if ((intRequest != nullptr || floatRequest != nullptr) &&
                    address % 4U == 0U && offset <= chunkSize - 1U &&
                    4U <= chunkSize - offset) {
                    std::uint32_t bits = 0U;
                    std::memcpy(&bits, buffer.data() + offset, sizeof(bits));
                    const std::int32_t intValue = std::bit_cast<std::int32_t>(bits);
                    const float floatValue = std::bit_cast<float>(bits);
                    if (!recordAutoMatch<std::int32_t, ResultPlane::Int, Predicate>(
                                intRequest, intValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<float, ResultPlane::Float, Predicate>(
                                floatRequest, floatValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if ((longRequest != nullptr || doubleRequest != nullptr) &&
                    address % 8U == 0U && 8U <= chunkSize - offset) {
                    std::uint64_t bits = 0U;
                    std::memcpy(&bits, buffer.data() + offset, sizeof(bits));
                    const std::int64_t longValue = std::bit_cast<std::int64_t>(bits);
                    const double doubleValue = std::bit_cast<double>(bits);
                    if (!recordAutoMatch<std::int64_t, ResultPlane::Long, Predicate>(
                                longRequest, longValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<double, ResultPlane::Double, Predicate>(
                                doubleRequest, doubleValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if ((shortRequest != nullptr || charRequest != nullptr) &&
                    address % 2U == 0U && 2U <= chunkSize - offset) {
                    std::uint16_t bits = 0U;
                    std::memcpy(&bits, buffer.data() + offset, sizeof(bits));
                    const std::int16_t shortValue = std::bit_cast<std::int16_t>(bits);
                    if (!recordAutoMatch<std::int16_t, ResultPlane::Short, Predicate>(
                                shortRequest, shortValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer) ||
                        !recordAutoMatch<std::uint16_t, ResultPlane::Char, Predicate>(
                                charRequest, bits, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
                if (byteRequest != nullptr) {
                    const auto byteValue =
                            static_cast<std::int8_t>(buffer[offset]);
                    if (!recordAutoMatch<std::int8_t, ResultPlane::Byte, Predicate>(
                                byteRequest, byteValue, address, blockBase,
                                buffer.data(), chunkSize, offset, scratch,
                                nextStats, error, observer)) {
                        return false;
                    }
                }
            }

            if (observer != nullptr && observer->onProgress != nullptr) {
                observer->onProgress(observer->opaque, chunkSize);
            }
            chunkStart += chunkSize;
        }
        // A range boundary is a semantic discontinuity even if a synthetic caller provides two
        // slices from the same 4 KiB logical block. Never merge them into one block accidentally.
        if (!flushScratch()) {
            return false;
        }
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
        KnownPredicate predicate, const std::vector<ScanRange> &ranges,
        const std::array<const KnownScanRequest *, kResultPlaneCount> &requests,
        const RemoteReadFn &read, const CancelledFn &cancelled,
        ResultStore &out, KnownScanStats &stats, std::string &error,
        const KnownScanObserver *observer) {
    switch (predicate) {
    case KnownPredicate::Equal:
        return scanKnownAutoPredicate<KnownPredicate::Equal>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::NotEqual:
        return scanKnownAutoPredicate<KnownPredicate::NotEqual>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::Greater:
        return scanKnownAutoPredicate<KnownPredicate::Greater>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::Less:
        return scanKnownAutoPredicate<KnownPredicate::Less>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::GreaterOrEqual:
        return scanKnownAutoPredicate<KnownPredicate::GreaterOrEqual>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::LessOrEqual:
        return scanKnownAutoPredicate<KnownPredicate::LessOrEqual>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    case KnownPredicate::Between:
        return scanKnownAutoPredicate<KnownPredicate::Between>(
                ranges, requests, read, cancelled, out, stats, error, observer);
    }
    error = "Invalid fused Auto known predicate";
    return false;
}

} // namespace

bool scanKnownExplicit(const std::vector<ScanRange> &ranges,
                       const KnownScanRequest &request,
                       const RemoteReadFn &read,
                       const CancelledFn &cancelled,
                       ResultStore &out,
                       KnownScanStats &stats,
                       std::string &error,
                       const KnownScanObserver *observer) {
    // Validate at the kernel boundary as well as at JNI/service call sites. ResultStore is now a
    // production owner for explicit Known first scans, so internal callers must not be able to
    // bypass canonical primitive-width, finite-floating, or ordered-Between invariants.
    if (!read || !validKnownQueryPlan(request)) {
        error = "Invalid explicit-type known scan request";
        return false;
    }
    switch (request.plane) {
    case ResultPlane::Byte:
        return dispatchKnownPredicate<std::int8_t, ResultPlane::Byte>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Short:
        return dispatchKnownPredicate<std::int16_t, ResultPlane::Short>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Char:
        return dispatchKnownPredicate<std::uint16_t, ResultPlane::Char>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Int:
        return dispatchKnownPredicate<std::int32_t, ResultPlane::Int>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Float:
        return dispatchKnownPredicate<float, ResultPlane::Float>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Long:
        return dispatchKnownPredicate<std::int64_t, ResultPlane::Long>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Double:
        return dispatchKnownPredicate<double, ResultPlane::Double>(
                ranges, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Count:
        break;
    }
    error = "Invalid explicit-type known scan request";
    return false;
}

bool scanKnownAuto(const std::vector<ScanRange> &ranges,
                   const std::vector<KnownScanRequest> &requests,
                   const RemoteReadFn &read,
                   const CancelledFn &cancelled,
                   ResultStore &out,
                   KnownScanStats &stats,
                   std::string &error,
                   const KnownScanObserver *observer) {
    if (!read) {
        error = "Invalid fused Auto known scan request";
        return false;
    }
    std::array<const KnownScanRequest *, kResultPlaneCount> byPlane{};
    KnownPredicate predicate = KnownPredicate::Equal;
    if (!prepareAutoRequests(requests, byPlane, predicate, error)) {
        return false;
    }
    return dispatchAutoPredicate(predicate, ranges, byPlane, read, cancelled,
                                 out, stats, error, observer);
}

bool scanKnownEqualExplicit(const std::vector<ScanRange> &ranges,
                            const KnownEqualScanRequest &request,
                            const RemoteReadFn &read,
                            const CancelledFn &cancelled,
                            ResultStore &out,
                            KnownScanStats &stats,
                            std::string &error) {
    return scanKnownExplicit(
            ranges,
            {request.plane, KnownPredicate::Equal, request.expectedBits, 0U},
            read, cancelled, out, stats, error, nullptr);
}

} // namespace jlmem::v2
