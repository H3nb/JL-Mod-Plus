/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_refine.h"

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

constexpr std::uint64_t kDirectRefineMatchLimit = 4096U;
constexpr std::size_t kRefineReadChunkSize = 256U * 1024U;

[[nodiscard]] std::uint64_t activePlaneCount(const ResultStore &source,
                                             ResultPlane plane) noexcept {
    if (plane == ResultPlane::Count) {
        return 0U;
    }
    const std::size_t planeSlot = planeIndex(plane);
    std::uint64_t count = 0U;
    for (const ResultBlockHeader &header : source.headers()) {
        count += header.counts[planeSlot];
    }
    return count;
}

[[nodiscard]] std::uint64_t activePlaneBlockCount(
        const ResultStore &source, ResultPlane plane) noexcept {
    if (plane == ResultPlane::Count) {
        return 0U;
    }
    const std::size_t planeSlot = planeIndex(plane);
    std::uint64_t count = 0U;
    for (const ResultBlockHeader &header : source.headers()) {
        if (header.counts[planeSlot] != 0U) {
            ++count;
        }
    }
    return count;
}

template <typename T>
[[nodiscard]] std::uint64_t rawBits(T value) noexcept {
    if constexpr (std::is_floating_point_v<T>) {
        if constexpr (sizeof(T) == sizeof(std::uint32_t)) {
            return static_cast<std::uint64_t>(
                    std::bit_cast<std::uint32_t>(value));
        } else {
            return std::bit_cast<std::uint64_t>(value);
        }
    } else {
        using Unsigned = std::make_unsigned_t<T>;
        return static_cast<std::uint64_t>(static_cast<Unsigned>(value));
    }
}

template <typename T, ResultPlane Plane, KnownPredicate Predicate>
[[nodiscard]] bool refineKnownTyped(const ResultStore &source,
                                    std::uint64_t firstBits,
                                    std::uint64_t secondBits,
                                    const RemoteReadFn &read,
                                    const CancelledFn &cancelled,
                                    ResultStore &out,
                                    KnownScanStats &stats,
                                    std::string &error,
                                    const KnownRefineObserver *observer) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    constexpr std::size_t kWidth = sizeof(T);
    constexpr std::size_t kWordCount = planeWordCount(Plane);

    ResultStore next = source;
    KnownScanStats nextStats;
    const auto headers = source.headers();
    constexpr std::size_t kPlaneSlot = planeIndex(Plane);
    const std::uint64_t activeMatches = activePlaneCount(source, Plane);
    const bool directReads = activeMatches <= kDirectRefineMatchLimit;

    const auto processBlock = [&](std::size_t blockIndex,
                                  const auto &loadValue) -> bool {
        if (cancelled && cancelled()) {
            // Keep the established shadow diagnostic token while this implementation is shared by
            // shadow and production callers; the caller decides whether it is user-visible.
            error = "V2 shadow refine cancelled";
            return false;
        }
        if (blockIndex >= headers.size() ||
            headers[blockIndex].counts[kPlaneSlot] == 0U) {
            return true;
        }

        const std::uintptr_t blockBase = headers[blockIndex].baseAddress;
        const auto currentWords = source.planeWords(blockIndex, Plane);
        if (currentWords.size() != kWordCount) {
            error = "ResultStore returned an invalid v2 refine bitmap";
            return false;
        }

        std::array<std::uint64_t, kWordCount> survivorsByWord{};
        bool changed = false;
        for (std::size_t wordIndex = 0U; wordIndex < currentWords.size(); ++wordIndex) {
            const std::uint64_t original = currentWords[wordIndex];
            std::uint64_t active = original;
            std::uint64_t survivors = original;
            while (active != 0U) {
                const std::size_t bit =
                        static_cast<std::size_t>(std::countr_zero(active));
                const std::size_t slot = wordIndex * 64U + bit;
                if (slot >= planeSlotCount(Plane)) {
                    error = "ResultStore contains an out-of-range v2 refine slot";
                    return false;
                }
                const std::size_t byteOffset = slot * kWidth;
                T actual{};
                if (!loadValue(blockBase, byteOffset, actual)) {
                    return false;
                }
                const bool survives =
                        matchesKnownValue<T, Predicate>(actual, firstBits, secondBits);
                if (!survives) {
                    survivors &= ~(std::uint64_t{1U} << bit);
                } else if (observer != nullptr && observer->onMatch != nullptr) {
                    const KnownRefineMatchView match{
                            blockBase + byteOffset,
                            rawBits(actual),
                            kWidth,
                    };
                    if (!observer->onMatch(observer->opaque, match)) {
                        error = "ResultStore compatibility mirror rejected a v2 refine survivor";
                        return false;
                    }
                }
                active &= active - std::uint64_t{1U};
            }
            survivorsByWord[wordIndex] = survivors;
            changed = changed || survivors != original;
        }

        if (!changed) {
            // Keep the shared COW slab untouched when every candidate survives this block.
            return true;
        }
        auto mutableWords = next.planeWords(blockIndex, Plane);
        if (mutableWords.size() != kWordCount) {
            error = "ResultStore could not detach a v2 refine bitmap";
            return false;
        }
        std::copy(survivorsByWord.begin(), survivorsByWord.end(),
                  mutableWords.begin());
        if (!next.recountBlock(blockIndex)) {
            error = "ResultStore could not recount a v2 refine block";
            return false;
        }
        return true;
    };

    if (directReads) {
        // Once the result set is sparse, reading a whole 4 KiB logical block per survivor wastes
        // memory bandwidth and can make later scans slower than the compatibility Candidate path.
        // Keep bitmap membership authoritative, but fetch only the exact typed values still set.
        for (std::size_t blockIndex = 0U; blockIndex < headers.size(); ++blockIndex) {
            if (headers[blockIndex].counts[kPlaneSlot] == 0U) {
                continue;
            }
            const auto directLoader = [&](std::uintptr_t blockBase,
                                          std::size_t byteOffset,
                                          T &actual) -> bool {
                if (blockBase > std::numeric_limits<std::uintptr_t>::max() - byteOffset ||
                    !read(blockBase + byteOffset, &actual, kWidth)) {
                    error = "Target block changed during v2 known refine";
                    return false;
                }
                nextStats.bytesScanned += static_cast<std::uint64_t>(kWidth);
                return true;
            };
            if (!processBlock(blockIndex, directLoader)) {
                return false;
            }
        }
    } else {
        // Dense revisions amortize process_vm_readv overhead by coalescing only adjacent active
        // 4 KiB ResultStore blocks. Empty refined blocks break a run and are never reread.
        std::vector<std::uint8_t> chunkBuffer;
        chunkBuffer.reserve(kRefineReadChunkSize);
        std::size_t blockIndex = 0U;
        while (blockIndex < headers.size()) {
            while (blockIndex < headers.size() &&
                   headers[blockIndex].counts[kPlaneSlot] == 0U) {
                ++blockIndex;
            }
            if (blockIndex >= headers.size()) {
                break;
            }
            if (cancelled && cancelled()) {
                error = "V2 shadow refine cancelled";
                return false;
            }

            const std::size_t runFirst = blockIndex;
            std::size_t runEnd = runFirst + 1U;
            while (runEnd < headers.size() &&
                   headers[runEnd].counts[kPlaneSlot] != 0U &&
                   runEnd - runFirst < kRefineReadChunkSize / kResultLogicalBlockSize) {
                const std::uintptr_t previous = headers[runEnd - 1U].baseAddress;
                if (previous > std::numeric_limits<std::uintptr_t>::max() -
                                       kResultLogicalBlockSize ||
                    headers[runEnd].baseAddress != previous + kResultLogicalBlockSize) {
                    break;
                }
                ++runEnd;
            }

            const std::size_t runBytes =
                    (runEnd - runFirst) * kResultLogicalBlockSize;
            chunkBuffer.resize(runBytes);
            const std::uintptr_t runBase = headers[runFirst].baseAddress;
            if (!read(runBase, chunkBuffer.data(), runBytes)) {
                error = "Target block changed during v2 known refine";
                return false;
            }
            nextStats.bytesScanned += static_cast<std::uint64_t>(runBytes);

            for (std::size_t current = runFirst; current < runEnd; ++current) {
                const std::size_t blockOffset =
                        (current - runFirst) * kResultLogicalBlockSize;
                const auto chunkLoader = [&](std::uintptr_t,
                                             std::size_t byteOffset,
                                             T &actual) -> bool {
                    if (byteOffset > kResultLogicalBlockSize - kWidth ||
                        blockOffset > chunkBuffer.size() - kResultLogicalBlockSize) {
                        error = "ResultStore produced an invalid v2 refine read offset";
                        return false;
                    }
                    std::memcpy(&actual,
                                chunkBuffer.data() + blockOffset + byteOffset,
                                kWidth);
                    return true;
                };
                if (!processBlock(current, chunkLoader)) {
                    return false;
                }
            }
            blockIndex = runEnd;
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

template <typename T, ResultPlane Plane>
[[nodiscard]] bool dispatchKnownPredicate(const ResultStore &source,
                                          const KnownRefineRequest &request,
                                          const RemoteReadFn &read,
                                          const CancelledFn &cancelled,
                                          ResultStore &out,
                                          KnownScanStats &stats,
                                          std::string &error,
                                          const KnownRefineObserver *observer) {
    switch (request.predicate) {
    case KnownPredicate::Equal:
        return refineKnownTyped<T, Plane, KnownPredicate::Equal>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::NotEqual:
        return refineKnownTyped<T, Plane, KnownPredicate::NotEqual>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Greater:
        return refineKnownTyped<T, Plane, KnownPredicate::Greater>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Less:
        return refineKnownTyped<T, Plane, KnownPredicate::Less>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::GreaterOrEqual:
        return refineKnownTyped<T, Plane, KnownPredicate::GreaterOrEqual>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::LessOrEqual:
        return refineKnownTyped<T, Plane, KnownPredicate::LessOrEqual>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    case KnownPredicate::Between:
        return refineKnownTyped<T, Plane, KnownPredicate::Between>(
                source, request.firstBits, request.secondBits, read, cancelled,
                out, stats, error, observer);
    }
    error = "Invalid explicit-type known refine predicate";
    return false;
}

} // namespace

std::uint64_t estimateKnownRefineReadBytes(const ResultStore &source,
                                           ResultPlane plane) noexcept {
    if (plane == ResultPlane::Count) {
        return 0U;
    }
    const std::uint64_t matches = activePlaneCount(source, plane);
    if (matches == 0U) {
        return 0U;
    }
    const std::size_t width = planeAlignment(plane);
    if (matches <= kDirectRefineMatchLimit) {
        if (width == 0U ||
            matches > std::numeric_limits<std::uint64_t>::max() / width) {
            return std::numeric_limits<std::uint64_t>::max();
        }
        return matches * static_cast<std::uint64_t>(width);
    }
    const std::uint64_t blocks = activePlaneBlockCount(source, plane);
    if (blocks > std::numeric_limits<std::uint64_t>::max() /
                         kResultLogicalBlockSize) {
        return std::numeric_limits<std::uint64_t>::max();
    }
    return blocks * static_cast<std::uint64_t>(kResultLogicalBlockSize);
}

bool refineKnownExplicit(const ResultStore &source,
                         const KnownRefineRequest &request,
                         const RemoteReadFn &read,
                         const CancelledFn &cancelled,
                         ResultStore &out,
                         KnownScanStats &stats,
                         std::string &error,
                         const KnownRefineObserver *observer) {
    // Defense in depth: callers inside the native engine must obey the exact same canonical plan
    // contract as the JNI diagnostics boundary. This prevents a production integration from
    // accidentally bypassing width, finite-floating, or ordered-Between validation.
    if (!read || !validKnownQueryPlan(request)) {
        error = "Invalid explicit-type known refine request";
        return false;
    }
    switch (request.plane) {
    case ResultPlane::Byte:
        return dispatchKnownPredicate<std::int8_t, ResultPlane::Byte>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Short:
        return dispatchKnownPredicate<std::int16_t, ResultPlane::Short>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Char:
        return dispatchKnownPredicate<std::uint16_t, ResultPlane::Char>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Int:
        return dispatchKnownPredicate<std::int32_t, ResultPlane::Int>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Float:
        return dispatchKnownPredicate<float, ResultPlane::Float>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Long:
        return dispatchKnownPredicate<std::int64_t, ResultPlane::Long>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Double:
        return dispatchKnownPredicate<double, ResultPlane::Double>(
                source, request, read, cancelled, out, stats, error, observer);
    case ResultPlane::Count:
        break;
    }
    error = "Invalid explicit-type known refine request";
    return false;
}

bool refineKnownEqualExplicit(const ResultStore &source,
                              const KnownEqualRefineRequest &request,
                              const RemoteReadFn &read,
                              const CancelledFn &cancelled,
                              ResultStore &out,
                              KnownScanStats &stats,
                              std::string &error) {
    return refineKnownExplicit(
            source,
            {request.plane, KnownPredicate::Equal, request.expectedBits, 0U},
            read, cancelled, out, stats, error, nullptr);
}

} // namespace jlmem::v2
