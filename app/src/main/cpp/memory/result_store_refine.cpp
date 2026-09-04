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
#include <type_traits>
#include <utility>
#include <vector>

namespace jlmem::v2 {
namespace {

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
    std::vector<std::uint8_t> blockBuffer(kResultLogicalBlockSize);
    const auto headers = next.headers();
    const std::size_t plane = planeIndex(Plane);

    for (std::size_t blockIndex = 0U; blockIndex < headers.size(); ++blockIndex) {
        if (headers[blockIndex].counts[plane] == 0U) {
            continue;
        }
        if (cancelled && cancelled()) {
            // Keep the established shadow diagnostic token while this implementation is shared by
            // shadow and production callers; the caller decides whether it is user-visible.
            error = "V2 shadow refine cancelled";
            return false;
        }

        const std::uintptr_t blockBase = headers[blockIndex].baseAddress;
        if (!read(blockBase, blockBuffer.data(), blockBuffer.size())) {
            error = "Target block changed during v2 known refine";
            return false;
        }
        nextStats.bytesScanned += static_cast<std::uint64_t>(blockBuffer.size());

        const ResultStore &readOnly = next;
        const auto currentWords = readOnly.planeWords(blockIndex, Plane);
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
                std::memcpy(&actual, blockBuffer.data() + byteOffset, kWidth);
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
            continue;
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
