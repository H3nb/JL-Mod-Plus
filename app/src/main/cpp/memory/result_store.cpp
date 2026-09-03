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
#include <utility>

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

[[nodiscard]] bool safeAdd(std::size_t left, std::size_t right,
                           std::size_t &result) noexcept {
    if (right > std::numeric_limits<std::size_t>::max() - left) {
        return false;
    }
    result = left + right;
    return true;
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
    if (appendWords == 0U || appendWords > kResultPayloadSlabWords) {
        return false;
    }

    std::size_t payloadOffset = payloadWordsUsed_;
    const std::size_t currentSlabOffset =
            payloadOffset % kResultPayloadSlabWords;
    if (currentSlabOffset + appendWords > kResultPayloadSlabWords) {
        const std::size_t padding =
                kResultPayloadSlabWords - currentSlabOffset;
        if (!safeAdd(payloadOffset, padding, payloadOffset)) {
            return false;
        }
    }
    std::size_t payloadEnd = 0U;
    if (!safeAdd(payloadOffset, appendWords, payloadEnd) || payloadEnd == 0U ||
        payloadEnd - 1U > std::numeric_limits<std::uint32_t>::max()) {
        return false;
    }

    const std::uint16_t unique = scratch.uniqueAddressCount();
    if (unique == 0U) {
        return false;
    }

    const std::size_t slabIndex = payloadOffset / kResultPayloadSlabWords;
    const std::size_t slabOffset = payloadOffset % kResultPayloadSlabWords;
    if (slabOffset + appendWords > kResultPayloadSlabWords) {
        return false;
    }

    // Reserve and allocate before semantic publication. A failed allocation never publishes a
    // partial block/result count. If this store was copied, appending into a shared tail slab first
    // detaches that slab so the older immutable revision remains untouched.
    headers_.reserve(headers_.size() + 1U);
    payloadSlabs_.reserve(slabIndex + 1U);
    while (payloadSlabs_.size() <= slabIndex) {
        payloadSlabs_.push_back(std::make_shared<PayloadSlab>());
    }
    if (!payloadSlabs_[slabIndex].unique()) {
        payloadSlabs_[slabIndex] =
                std::make_shared<PayloadSlab>(*payloadSlabs_[slabIndex]);
    }

    ResultBlockHeader header;
    header.baseAddress = baseAddress;
    header.payloadWordOffset = static_cast<std::uint32_t>(payloadOffset);
    header.counts = counts;
    header.uniqueAddressCount = unique;
    header.activeMask = activeMask;

    std::size_t writeOffset = slabOffset;
    PayloadSlab &slab = *payloadSlabs_[slabIndex];
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((activeMask & planeBit(plane)) == 0U) {
            continue;
        }
        const auto words = scratch.planeWords(plane);
        std::copy(words.begin(), words.end(),
                  slab.words.begin() + static_cast<std::ptrdiff_t>(writeOffset));
        writeOffset += words.size();
    }
    if (writeOffset != slabOffset + appendWords) {
        return false;
    }

    headers_.push_back(header);
    payloadWordsUsed_ = payloadEnd;
    typedCount_ += typed;
    uniqueAddressCount_ += unique;
    return true;
}

std::size_t ResultStore::payloadOffset(
        const ResultBlockHeader &header, ResultPlane plane) const noexcept {
    if (plane == ResultPlane::Count ||
        (header.activeMask & planeBit(plane)) == 0U) {
        return std::numeric_limits<std::size_t>::max();
    }
    std::size_t offset = header.payloadWordOffset;
    for (std::size_t index = 0U; index < planeIndex(plane); ++index) {
        const ResultPlane prior = planeFromIndex(index);
        if ((header.activeMask & planeBit(prior)) != 0U) {
            if (!safeAdd(offset, planeWordCount(prior), offset)) {
                return std::numeric_limits<std::size_t>::max();
            }
        }
    }
    return offset;
}

bool ResultStore::locatePayload(std::size_t offset, std::size_t count,
                                std::size_t &slabIndex,
                                std::size_t &slabOffset) const noexcept {
    if (offset == std::numeric_limits<std::size_t>::max() || count == 0U) {
        return false;
    }
    slabIndex = offset / kResultPayloadSlabWords;
    slabOffset = offset % kResultPayloadSlabWords;
    return slabIndex < payloadSlabs_.size() && payloadSlabs_[slabIndex] != nullptr &&
           slabOffset <= kResultPayloadSlabWords &&
           count <= kResultPayloadSlabWords - slabOffset;
}

std::span<const std::uint64_t> ResultStore::planeWords(
        std::size_t blockIndex, ResultPlane plane) const noexcept {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count) {
        return {};
    }
    const std::size_t count = planeWordCount(plane);
    std::size_t slabIndex = 0U;
    std::size_t slabOffset = 0U;
    if (!locatePayload(payloadOffset(headers_[blockIndex], plane), count,
                       slabIndex, slabOffset)) {
        return {};
    }
    const PayloadSlab &slab = *payloadSlabs_[slabIndex];
    return {slab.words.data() + slabOffset, count};
}

std::span<std::uint64_t> ResultStore::planeWords(
        std::size_t blockIndex, ResultPlane plane) {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count) {
        return {};
    }
    const std::size_t count = planeWordCount(plane);
    std::size_t slabIndex = 0U;
    std::size_t slabOffset = 0U;
    if (!locatePayload(payloadOffset(headers_[blockIndex], plane), count,
                       slabIndex, slabOffset)) {
        return {};
    }
    if (!payloadSlabs_[slabIndex].unique()) {
        payloadSlabs_[slabIndex] =
                std::make_shared<PayloadSlab>(*payloadSlabs_[slabIndex]);
    }
    PayloadSlab &slab = *payloadSlabs_[slabIndex];
    return {slab.words.data() + slabOffset, count};
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
        if (alignment == 0U || byteOffset % alignment != 0U) {
            continue;
        }
        const std::size_t slot = byteOffset / alignment;
        const auto words = planeWords(blockIndex, plane);
        if (!words.empty() && slot / 64U < words.size() &&
            (words[slot / 64U] &
             (std::uint64_t{1U} << (slot % 64U))) != 0U) {
            return true;
        }
    }
    return false;
}

bool ResultStore::clearSlot(std::size_t blockIndex, ResultPlane plane,
                            std::size_t slot) {
    if (blockIndex >= headers_.size() || plane == ResultPlane::Count ||
        slot >= planeSlotCount(plane)) {
        return false;
    }
    ResultBlockHeader &header = headers_[blockIndex];
    const std::size_t index = planeIndex(plane);
    if (header.counts[index] == 0U || typedCount_ == 0U) {
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
    --header.counts[index];
    --typedCount_;

    const std::size_t byteOffset = slot * planeAlignment(plane);
    if (!anyAliasAtAddress(blockIndex, byteOffset)) {
        if (header.uniqueAddressCount == 0U || uniqueAddressCount_ == 0U) {
            // Internal metadata is inconsistent. Restore the bit/counts rather than publishing a
            // partially mutated store and let the caller fail the operation safely.
            words[wordIndex] |= mask;
            ++header.counts[index];
            ++typedCount_;
            return false;
        }
        --header.uniqueAddressCount;
        --uniqueAddressCount_;
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
                if (byteOffset >= kResultLogicalBlockSize) {
                    return 0U;
                }
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
    const ResultStore &self = *this;
    for (const std::uint16_t count : header.counts) {
        oldTyped += count;
    }
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = planeFromIndex(index);
        if ((header.activeMask & planeBit(plane)) == 0U) {
            header.counts[index] = 0U;
            continue;
        }
        const auto words = self.planeWords(blockIndex, plane);
        if (words.size() != planeWordCount(plane)) {
            return false;
        }
        header.counts[index] = popcountWords(words);
        newTyped += header.counts[index];
    }

    const std::uint16_t oldUnique = header.uniqueAddressCount;
    const std::uint16_t newUnique = blockUniqueAddressCount(blockIndex);
    if ((newTyped != 0U && newUnique == 0U) || typedCount_ < oldTyped ||
        uniqueAddressCount_ < oldUnique) {
        return false;
    }
    header.uniqueAddressCount = newUnique;
    typedCount_ = typedCount_ - oldTyped + newTyped;
    uniqueAddressCount_ = uniqueAddressCount_ - oldUnique + newUnique;
    return true;
}

std::size_t ResultStore::retainedBytes() const noexcept {
    std::size_t result = sizeof(ResultStore);
    const auto addAllocation = [&](std::size_t count, std::size_t elementSize,
                                   std::size_t &value) {
        if (count > (std::numeric_limits<std::size_t>::max() - value) /
                            elementSize) {
            return false;
        }
        value += count * elementSize;
        return true;
    };
    if (!addAllocation(headers_.capacity(), sizeof(ResultBlockHeader), result) ||
        !addAllocation(payloadSlabs_.capacity(),
                       sizeof(std::shared_ptr<PayloadSlab>), result)) {
        return std::numeric_limits<std::size_t>::max();
    }
    // Count reachable slab bytes once per ResultStore. Across multiple immutable revisions the
    // physical allocation is shared; revision-set accounting can deduplicate shared_ptr identity
    // later when production history owns several stores simultaneously.
    if (!addAllocation(payloadSlabs_.size(), sizeof(PayloadSlab), result)) {
        return std::numeric_limits<std::size_t>::max();
    }
    return result;
}

void ResultStore::clear() noexcept {
    headers_.clear();
    payloadSlabs_.clear();
    payloadWordsUsed_ = 0U;
    typedCount_ = 0U;
    uniqueAddressCount_ = 0U;
}

} // namespace jlmem::v2
