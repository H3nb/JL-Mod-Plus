/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store.h"

#include <algorithm>
#include <bit>
#include <limits>

namespace jlmem::v2 {
namespace {

[[nodiscard]] constexpr ResultPlane planeFromIndex(std::size_t index) noexcept {
    return static_cast<ResultPlane>(index);
}

[[nodiscard]] constexpr std::uint8_t planeBit(ResultPlane plane) noexcept {
    return static_cast<std::uint8_t>(1U << planeIndex(plane));
}

[[nodiscard]] std::uint16_t popcountWords(
        std::span<const std::uint64_t> words) noexcept {
    std::size_t count = 0U;
    for (const std::uint64_t word : words) {
        count += static_cast<std::size_t>(std::popcount(word));
    }
    return static_cast<std::uint16_t>(count);
}

} // namespace

bool ResultBlockScratch::set(ResultPlane plane, std::size_t slot) noexcept {
    if (plane == ResultPlane::Count || slot >= planeSlotCount(plane)) {
        return false;
    }
    const std::size_t offset = planeOffset(plane) + slot / 64U;
    const std::uint64_t mask = std::uint64_t{1U} << (slot % 64U);
    const bool changed = (words_[offset] & mask) == 0U;
    words_[offset] |= mask;
    return changed;
}

bool ResultBlockScratch::clear(ResultPlane plane, std::size_t slot) noexcept {
    if (plane == ResultPlane::Count || slot >= planeSlotCount(plane)) {
        return false;
    }
    const std::size_t offset = planeOffset(plane) + slot / 64U;
    const std::uint64_t mask = std::uint64_t{1U} << (slot % 64U);
    const bool changed = (words_[offset] & mask) != 0U;
    words_[offset] &= ~mask;
    return changed;
}

bool ResultBlockScratch::test(ResultPlane plane, std::size_t slot) const noexcept {
    if (plane == ResultPlane::Count || slot >= planeSlotCount(plane)) {
        return false;
    }
    const std::size_t offset = planeOffset(plane) + slot / 64U;
    return (words_[offset] & (std::uint64_t{1U} << (slot % 64U))) != 0U;
}

std::span<const std::uint64_t> ResultBlockScratch::planeWords(
        ResultPlane plane) const noexcept {
    if (plane == ResultPlane::Count) {
        return {};
    }
    const std::size_t offset = planeOffset(plane);
    return {words_.data() + offset, planeWordCount(plane)};
}

std::uint16_t ResultBlockScratch::count(ResultPlane plane) const noexcept {
    return popcountWords(planeWords(plane));
}

std::uint16_t ResultBlockScratch::uniqueAddressCount() const noexcept {
    std::array<std::uint64_t, kResultLogicalBlockSize / 64U> addresses{};
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        const std::size_t alignment = planeAlignment(plane);
        const auto words = planeWords(plane);
        for (std::size_t wordIndex = 0U; wordIndex < words.size(); ++wordIndex) {
            std::uint64_t word = words[wordIndex];
            while (word != 0U) {
                const std::size_t bit =
                        static_cast<std::size_t>(std::countr_zero(word));
                const std::size_t slot = wordIndex * 64U + bit;
                const std::size_t byteOffset = slot * alignment;
                addresses[byteOffset / 64U] |=
                        std::uint64_t{1U} << (byteOffset % 64U);
                word &= word - std::uint64_t{1U};
            }
        }
    }
    return popcountWords(addresses);
}

std::uint8_t ResultBlockScratch::activeMask() const noexcept {
    std::uint8_t mask = 0U;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if (count(plane) != 0U) {
            mask = static_cast<std::uint8_t>(mask | planeBit(plane));
        }
    }
    return mask;
}

bool ResultBlockScratch::empty() const noexcept {
    return std::all_of(words_.begin(), words_.end(),
                       [](std::uint64_t word) { return word == 0U; });
}

void ResultBlockScratch::reset() noexcept {
    words_.fill(0U);
}

bool ResultStore::appendNonEmptyBlock(
        std::uintptr_t baseAddress, const ResultBlockScratch &scratch) {
    if (baseAddress == 0U || baseAddress % kResultLogicalBlockSize != 0U ||
        scratch.empty()) {
        return false;
    }
    if (!headers_.empty() && baseAddress <= headers_.back().baseAddress) {
        return false;
    }

    const std::uint8_t activeMask = scratch.activeMask();
    if (activeMask == 0U) {
        return false;
    }

    std::array<std::uint16_t, kResultPlaneCount> counts{};
    std::size_t appendWords = 0U;
    std::uint64_t typed = 0U;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        counts[index] = scratch.count(plane);
        if (counts[index] != 0U) {
            appendWords += planeWordCount(plane);
            typed += counts[index];
        }
    }

    if (payload_.size() > std::numeric_limits<std::uint32_t>::max() ||
        appendWords >
                std::numeric_limits<std::uint32_t>::max() - payload_.size()) {
        return false;
    }
    const std::uint16_t unique = scratch.uniqueAddressCount();
    if (unique == 0U) {
        return false;
    }

    // Reserve both vectors before semantic mutation. A failed allocation may change capacity but
    // never publishes a partial block/result count.
    headers_.reserve(headers_.size() + 1U);
    payload_.reserve(payload_.size() + appendWords);

    ResultBlockHeader header;
    header.baseAddress = baseAddress;
    header.payloadWordOffset = static_cast<std::uint32_t>(payload_.size());
    header.counts = counts;
    header.uniqueAddressCount = unique;
    header.activeMask = activeMask;

    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((activeMask & planeBit(plane)) == 0U) {
            continue;
        }
        const auto words = scratch.planeWords(plane);
        payload_.insert(payload_.end(), words.begin(), words.end());
    }
    headers_.push_back(header);
    typedCount_ += typed;
    uniqueAddressCount_ += unique;
    return true;
}

std::size_t ResultStore::payloadOffset(
        const ResultBlockHeader &header, ResultPlane plane) const noexcept {
    if (plane == ResultPlane::Count ||
        (header.activeMask & planeBit(plane)) == 0U) {
        return payload_.size();
    }
    std::size_t offset = header.payloadWordOffset;
    for (std::size_t index = 0U; index < planeIndex(plane); ++index) {
        const ResultPlane prior = planeFromIndex(index);
        if ((header.activeMask & planeBit(prior)) != 0U) {
            offset += planeWordCount(prior);
        }
    }
    return offset;
}

std::span<const std::uint64_t> ResultStore::planeWords(
        std::size_t blockIndex, ResultPlane plane) const noexcept {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count) {
        return {};
    }
    const std::size_t offset = payloadOffset(headers_[blockIndex], plane);
    const std::size_t count = planeWordCount(plane);
    if (offset > payload_.size() || count > payload_.size() - offset) {
        return {};
    }
    return {payload_.data() + offset, count};
}

std::span<std::uint64_t> ResultStore::planeWords(
        std::size_t blockIndex, ResultPlane plane) noexcept {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count) {
        return {};
    }
    const std::size_t offset = payloadOffset(headers_[blockIndex], plane);
    const std::size_t count = planeWordCount(plane);
    if (offset > payload_.size() || count > payload_.size() - offset) {
        return {};
    }
    return {payload_.data() + offset, count};
}

bool ResultStore::anyAliasAtAddress(std::size_t blockIndex,
                                    std::size_t byteOffset) const noexcept {
    if (blockIndex >= headers_.size() || byteOffset >= kResultLogicalBlockSize) {
        return false;
    }
    const ResultBlockHeader &header = headers_[blockIndex];
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((header.activeMask & planeBit(plane)) == 0U) {
            continue;
        }
        const std::size_t alignment = planeAlignment(plane);
        if (byteOffset % alignment != 0U) {
            continue;
        }
        const std::size_t slot = byteOffset / alignment;
        const auto words = planeWords(blockIndex, plane);
        if (!words.empty() &&
            (words[slot / 64U] &
             (std::uint64_t{1U} << (slot % 64U))) != 0U) {
            return true;
        }
    }
    return false;
}

bool ResultStore::clearSlot(std::size_t blockIndex, ResultPlane plane,
                            std::size_t slot) noexcept {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count ||
        slot >= planeSlotCount(plane)) {
        return false;
    }
    auto words = planeWords(blockIndex, plane);
    if (words.empty()) {
        return false;
    }
    const std::size_t wordIndex = slot / 64U;
    const std::uint64_t mask = std::uint64_t{1U} << (slot % 64U);
    if ((words[wordIndex] & mask) == 0U) {
        return false;
    }

    words[wordIndex] &= ~mask;
    ResultBlockHeader &header = headers_[blockIndex];
    const std::size_t index = planeIndex(plane);
    if (header.counts[index] > 0U) {
        --header.counts[index];
    }
    if (typedCount_ > 0U) {
        --typedCount_;
    }

    const std::size_t byteOffset = slot * planeAlignment(plane);
    if (!anyAliasAtAddress(blockIndex, byteOffset)) {
        if (header.uniqueAddressCount > 0U) {
            --header.uniqueAddressCount;
        }
        if (uniqueAddressCount_ > 0U) {
            --uniqueAddressCount_;
        }
    }
    return true;
}

std::uint16_t ResultStore::blockUniqueAddressCount(
        std::size_t blockIndex) const noexcept {
    if (blockIndex >= headers_.size()) {
        return 0U;
    }
    std::array<std::uint64_t, kResultLogicalBlockSize / 64U> addresses{};
    const ResultBlockHeader &header = headers_[blockIndex];
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((header.activeMask & planeBit(plane)) == 0U) {
            continue;
        }
        const std::size_t alignment = planeAlignment(plane);
        const auto words = planeWords(blockIndex, plane);
        for (std::size_t wordIndex = 0U; wordIndex < words.size(); ++wordIndex) {
            std::uint64_t word = words[wordIndex];
            while (word != 0U) {
                const std::size_t bit =
                        static_cast<std::size_t>(std::countr_zero(word));
                const std::size_t slot = wordIndex * 64U + bit;
                const std::size_t byteOffset = slot * alignment;
                addresses[byteOffset / 64U] |=
                        std::uint64_t{1U} << (byteOffset % 64U);
                word &= word - std::uint64_t{1U};
            }
        }
    }
    return popcountWords(addresses);
}

bool ResultStore::recountBlock(std::size_t blockIndex) noexcept {
    if (blockIndex >= headers_.size()) {
        return false;
    }
    ResultBlockHeader &header = headers_[blockIndex];
    std::uint64_t oldTyped = 0U;
    std::uint64_t newTyped = 0U;
    for (const std::uint16_t count : header.counts) {
        oldTyped += count;
    }
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((header.activeMask & planeBit(plane)) == 0U) {
            header.counts[index] = 0U;
            continue;
        }
        header.counts[index] = popcountWords(planeWords(blockIndex, plane));
        newTyped += header.counts[index];
    }

    const std::uint16_t oldUnique = header.uniqueAddressCount;
    const std::uint16_t newUnique = blockUniqueAddressCount(blockIndex);
    header.uniqueAddressCount = newUnique;
    typedCount_ = typedCount_ >= oldTyped ? typedCount_ - oldTyped + newTyped
                                         : newTyped;
    uniqueAddressCount_ = uniqueAddressCount_ >= oldUnique
                                  ? uniqueAddressCount_ - oldUnique + newUnique
                                  : newUnique;
    return true;
}

std::size_t ResultStore::retainedBytes() const noexcept {
    std::size_t result = sizeof(ResultStore);
    if (headers_.capacity() >
        (std::numeric_limits<std::size_t>::max() - result) /
                sizeof(ResultBlockHeader)) {
        return std::numeric_limits<std::size_t>::max();
    }
    result += headers_.capacity() * sizeof(ResultBlockHeader);
    if (payload_.capacity() >
        (std::numeric_limits<std::size_t>::max() - result) /
                sizeof(std::uint64_t)) {
        return std::numeric_limits<std::size_t>::max();
    }
    return result + payload_.capacity() * sizeof(std::uint64_t);
}

void ResultStore::clear() noexcept {
    headers_.clear();
    payload_.clear();
    typedCount_ = 0U;
    uniqueAddressCount_ = 0U;
}

} // namespace jlmem::v2
