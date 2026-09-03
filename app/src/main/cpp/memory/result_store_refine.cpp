/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_refine.h"

#include <bit>
#include <cstring>
#include <type_traits>
#include <utility>
#include <vector>

namespace jlmem::v2 {
namespace {

template <typename T>
[[nodiscard]] T valueFromBits(std::uint64_t bits) noexcept {
    static_assert(std::is_trivially_copyable_v<T>);
    static_assert(sizeof(T) <= sizeof(bits));
    T value{};
    std::memcpy(&value, &bits, sizeof(T));
    return value;
}

template <typename T, ResultPlane Plane>
[[nodiscard]] bool refineEqualTyped(const ResultStore &source,
                                    std::uint64_t expectedBits,
                                    const RemoteReadFn &read,
                                    const CancelledFn &cancelled,
                                    ResultStore &out,
                                    KnownScanStats &stats,
                                    std::string &error) {
    static_assert(sizeof(T) == planeAlignment(Plane));
    constexpr std::size_t kWidth = sizeof(T);
    const T expected = valueFromBits<T>(expectedBits);

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
            error = "V2 shadow refine cancelled";
            return false;
        }

        const std::uintptr_t blockBase = headers[blockIndex].baseAddress;
        if (!read(blockBase, blockBuffer.data(), blockBuffer.size())) {
            error = "Target block changed during v2 shadow refine";
            return false;
        }
        nextStats.bytesScanned += static_cast<std::uint64_t>(blockBuffer.size());

        auto words = next.planeWords(blockIndex, Plane);
        if (words.size() != planeWordCount(Plane)) {
            error = "ResultStore returned an invalid v2 refine bitmap";
            return false;
        }

        for (std::size_t wordIndex = 0U; wordIndex < words.size(); ++wordIndex) {
            const std::uint64_t original = words[wordIndex];
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
                if (!(actual == expected)) {
                    survivors &= ~(std::uint64_t{1U} << bit);
                }
                active &= active - std::uint64_t{1U};
            }
            words[wordIndex] = survivors;
        }

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

} // namespace

bool refineKnownEqualExplicit(const ResultStore &source,
                              const KnownEqualRefineRequest &request,
                              const RemoteReadFn &read,
                              const CancelledFn &cancelled,
                              ResultStore &out,
                              KnownScanStats &stats,
                              std::string &error) {
    if (!read || request.plane == ResultPlane::Count) {
        error = "Invalid explicit-type equality refine request";
        return false;
    }
    switch (request.plane) {
    case ResultPlane::Byte:
        return refineEqualTyped<std::int8_t, ResultPlane::Byte>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Short:
        return refineEqualTyped<std::int16_t, ResultPlane::Short>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Char:
        return refineEqualTyped<std::uint16_t, ResultPlane::Char>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Int:
        return refineEqualTyped<std::int32_t, ResultPlane::Int>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Float:
        return refineEqualTyped<float, ResultPlane::Float>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Long:
        return refineEqualTyped<std::int64_t, ResultPlane::Long>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Double:
        return refineEqualTyped<double, ResultPlane::Double>(
                source, request.expectedBits, read, cancelled, out, stats, error);
    case ResultPlane::Count:
        break;
    }
    error = "Invalid explicit-type equality refine request";
    return false;
}

} // namespace jlmem::v2
