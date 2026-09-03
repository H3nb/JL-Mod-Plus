/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_cursor.h"

#include <array>
#include <bit>
#include <limits>
#include <utility>

namespace jlmem::v2 {
namespace {

using AddressUnion =
        std::array<std::uint64_t, kResultLogicalBlockSize / 64U>;

[[nodiscard]] std::uint8_t aliasMaskAt(const ResultStore &store,
                                       std::size_t blockIndex,
                                       std::size_t byteOffset) noexcept {
    if (blockIndex >= store.blockCount() ||
        byteOffset >= kResultLogicalBlockSize) {
        return 0U;
    }
    const auto headers = store.headers();
    const ResultBlockHeader &header = headers[blockIndex];
    std::uint8_t mask = 0U;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = static_cast<ResultPlane>(index);
        const std::uint8_t bit = resultPlaneBit(plane);
        if ((header.activeMask & bit) == 0U) {
            continue;
        }
        const std::size_t alignment = planeAlignment(plane);
        if (alignment == 0U || byteOffset % alignment != 0U) {
            continue;
        }
        const std::size_t slot = byteOffset / alignment;
        const auto words = store.planeWords(blockIndex, plane);
        if (slot / 64U < words.size() &&
            (words[slot / 64U] &
             (std::uint64_t{1U} << (slot % 64U))) != 0U) {
            mask = static_cast<std::uint8_t>(mask | bit);
        }
    }
    return mask;
}

void buildAddressUnion(const ResultStore &store, std::size_t blockIndex,
                       AddressUnion &addresses) noexcept {
    addresses.fill(0U);
    if (blockIndex >= store.blockCount()) {
        return;
    }
    const ResultBlockHeader &header = store.headers()[blockIndex];
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = static_cast<ResultPlane>(index);
        const std::uint8_t bit = resultPlaneBit(plane);
        if ((header.activeMask & bit) == 0U || header.counts[index] == 0U) {
            continue;
        }
        const std::size_t alignment = planeAlignment(plane);
        const auto words = store.planeWords(blockIndex, plane);
        for (std::size_t wordIndex = 0U; wordIndex < words.size(); ++wordIndex) {
            std::uint64_t word = words[wordIndex];
            while (word != 0U) {
                const std::size_t bitIndex =
                        static_cast<std::size_t>(std::countr_zero(word));
                const std::size_t slot = wordIndex * 64U + bitIndex;
                const std::size_t byteOffset = slot * alignment;
                if (byteOffset < kResultLogicalBlockSize) {
                    addresses[byteOffset / 64U] |=
                            std::uint64_t{1U} << (byteOffset % 64U);
                }
                word &= word - std::uint64_t{1U};
            }
        }
    }
}

[[nodiscard]] bool nthAddressOffset(const AddressUnion &addresses,
                                    std::uint64_t ordinal,
                                    std::uint16_t &byteOffset) noexcept {
    for (std::size_t wordIndex = 0U; wordIndex < addresses.size(); ++wordIndex) {
        std::uint64_t word = addresses[wordIndex];
        const std::uint64_t wordCount =
                static_cast<std::uint64_t>(std::popcount(word));
        if (ordinal >= wordCount) {
            ordinal -= wordCount;
            continue;
        }
        while (word != 0U) {
            const std::size_t bit =
                    static_cast<std::size_t>(std::countr_zero(word));
            if (ordinal == 0U) {
                const std::size_t offset = wordIndex * 64U + bit;
                if (offset >= kResultLogicalBlockSize) {
                    return false;
                }
                byteOffset = static_cast<std::uint16_t>(offset);
                return true;
            }
            --ordinal;
            word &= word - std::uint64_t{1U};
        }
        return false;
    }
    return false;
}

} // namespace

bool readAddressPage(const ResultStore &store, ResultCursor cursor,
                     std::size_t limit, ResultAddressPage &page) {
    if (limit == 0U || limit > kResultCursorPageLimit ||
        cursor.blockIndex > store.blockCount() ||
        cursor.nextByteOffset >= kResultLogicalBlockSize) {
        return false;
    }

    ResultAddressPage next;
    next.rows.reserve(limit);
    next.next = cursor;
    if (cursor.blockIndex == store.blockCount()) {
        next.next.nextByteOffset = 0U;
        page = std::move(next);
        return true;
    }

    AddressUnion addresses{};
    for (std::size_t blockIndex = cursor.blockIndex;
         blockIndex < store.blockCount(); ++blockIndex) {
        buildAddressUnion(store, blockIndex, addresses);
        const std::size_t startByte =
                blockIndex == cursor.blockIndex ? cursor.nextByteOffset : 0U;
        std::size_t wordIndex = startByte / 64U;
        std::uint64_t word = addresses[wordIndex];
        const std::size_t firstBit = startByte % 64U;
        if (firstBit != 0U) {
            word &= ~((std::uint64_t{1U} << firstBit) - std::uint64_t{1U});
        }

        while (wordIndex < addresses.size()) {
            while (word != 0U) {
                const std::size_t bit =
                        static_cast<std::size_t>(std::countr_zero(word));
                const std::size_t byteOffset = wordIndex * 64U + bit;
                const std::uint8_t aliases =
                        aliasMaskAt(store, blockIndex, byteOffset);
                if (aliases == 0U) {
                    return false;
                }
                const std::uintptr_t baseAddress =
                        store.headers()[blockIndex].baseAddress;
                if (baseAddress >
                    std::numeric_limits<std::uintptr_t>::max() - byteOffset) {
                    return false;
                }
                next.rows.push_back({baseAddress + byteOffset, aliases});
                word &= word - std::uint64_t{1U};

                if (next.rows.size() == limit) {
                    const std::size_t following = byteOffset + 1U;
                    if (following < kResultLogicalBlockSize) {
                        next.next = {blockIndex,
                                     static_cast<std::uint16_t>(following)};
                    } else {
                        next.next = {blockIndex + 1U, 0U};
                    }
                    page = std::move(next);
                    return true;
                }
            }
            ++wordIndex;
            if (wordIndex >= addresses.size()) {
                break;
            }
            word = addresses[wordIndex];
        }
    }

    next.next = {store.blockCount(), 0U};
    page = std::move(next);
    return true;
}

bool seekAddressOffset(const ResultStore &store, std::uint64_t addressOffset,
                       ResultCursor &cursor) {
    if (addressOffset > store.uniqueAddressCount()) {
        return false;
    }
    if (addressOffset == store.uniqueAddressCount()) {
        cursor = {store.blockCount(), 0U};
        return true;
    }

    std::uint64_t remaining = addressOffset;
    const auto headers = store.headers();
    for (std::size_t blockIndex = 0U; blockIndex < headers.size(); ++blockIndex) {
        const std::uint64_t blockCount = headers[blockIndex].uniqueAddressCount;
        if (remaining >= blockCount) {
            remaining -= blockCount;
            continue;
        }
        AddressUnion addresses{};
        buildAddressUnion(store, blockIndex, addresses);
        std::uint16_t byteOffset = 0U;
        if (!nthAddressOffset(addresses, remaining, byteOffset) ||
            aliasMaskAt(store, blockIndex, byteOffset) == 0U) {
            return false;
        }
        cursor = {blockIndex, byteOffset};
        return true;
    }
    return false;
}

bool readAddressPageAtOffset(const ResultStore &store,
                             std::uint64_t addressOffset,
                             std::size_t limit,
                             ResultAddressPage &page) {
    ResultCursor cursor;
    return seekAddressOffset(store, addressOffset, cursor) &&
           readAddressPage(store, cursor, limit, page);
}

} // namespace jlmem::v2
