/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_alias_cursor.h"

#include <algorithm>
#include <array>
#include <bit>
#include <limits>
#include <utility>

namespace jlmem::v2 {
namespace {

constexpr std::array<ResultPlane, kResultPlaneCount> kDisplayOrder{
        ResultPlane::Int,
        ResultPlane::Float,
        ResultPlane::Long,
        ResultPlane::Double,
        ResultPlane::Short,
        ResultPlane::Char,
        ResultPlane::Byte,
};

[[nodiscard]] constexpr std::uint8_t allResultPlaneBits() noexcept {
    return static_cast<std::uint8_t>((std::uint16_t{1U} << kResultPlaneCount) - 1U);
}

[[nodiscard]] ResultPlane takeNextDisplayPlane(std::uint8_t &mask) noexcept {
    for (const ResultPlane plane : kDisplayOrder) {
        const std::uint8_t bit = resultPlaneBit(plane);
        if ((mask & bit) == 0U) {
            continue;
        }
        mask = static_cast<std::uint8_t>(mask & static_cast<std::uint8_t>(~bit));
        return plane;
    }
    return ResultPlane::Count;
}

[[nodiscard]] std::uint64_t setBitsBeforeSlot(
        std::span<const std::uint64_t> words, std::size_t slotLimit) noexcept {
    const std::size_t maximumSlots = words.size() * 64U;
    slotLimit = std::min(slotLimit, maximumSlots);
    const std::size_t fullWords = slotLimit / 64U;
    const std::size_t tailBits = slotLimit % 64U;
    std::uint64_t count = 0U;
    for (std::size_t index = 0U; index < fullWords; ++index) {
        count += static_cast<std::uint64_t>(std::popcount(words[index]));
    }
    if (tailBits != 0U && fullWords < words.size()) {
        const std::uint64_t mask =
                (std::uint64_t{1U} << tailBits) - std::uint64_t{1U};
        count += static_cast<std::uint64_t>(std::popcount(words[fullWords] & mask));
    }
    return count;
}

[[nodiscard]] bool addChecked(std::uint64_t &total,
                              std::uint64_t addition) noexcept {
    if (total > std::numeric_limits<std::uint64_t>::max() - addition) {
        return false;
    }
    total += addition;
    return true;
}

[[nodiscard]] std::uint64_t blockTypedCount(
        const ResultBlockHeader &header) noexcept {
    std::uint64_t result = 0U;
    for (const std::uint16_t count : header.counts) {
        result += count;
    }
    return result;
}

static_assert(resultAliasDisplayPriority(ResultPlane::Int) == 0U);
static_assert(resultAliasDisplayPriority(ResultPlane::Byte) == 6U);
static_assert(allResultPlaneBits() == 0x7fU);

} // namespace

bool readAliasPage(const ResultStore &store, ResultAliasCursor cursor,
                   std::size_t limit, ResultAliasPage &page) {
    if (limit == 0U || limit > kResultAliasCursorPageLimit ||
        cursor.addressCursor.blockIndex > store.blockCount() ||
        cursor.addressCursor.nextByteOffset >= kResultLogicalBlockSize ||
        (cursor.pendingAliasMask & static_cast<std::uint8_t>(~allResultPlaneBits())) != 0U ||
        (cursor.pendingAliasMask == 0U && cursor.pendingAddress != 0U) ||
        (cursor.pendingAliasMask != 0U && cursor.pendingAddress == 0U)) {
        return false;
    }

    ResultAliasPage next;
    next.rows.reserve(limit);
    next.next = cursor;

    while (next.rows.size() < limit) {
        if (next.next.pendingAliasMask == 0U) {
            ResultAddressPage addressPage;
            if (!readAddressPage(store, next.next.addressCursor, 1U, addressPage) ||
                addressPage.rows.size() > 1U) {
                return false;
            }
            next.next.addressCursor = addressPage.next;
            if (addressPage.rows.empty()) {
                next.next.pendingAddress = 0U;
                page = std::move(next);
                return true;
            }
            const ResultAddressRow &row = addressPage.rows.front();
            if (row.address == 0U || row.aliasMask == 0U ||
                (row.aliasMask & static_cast<std::uint8_t>(~allResultPlaneBits())) != 0U) {
                return false;
            }
            next.next.pendingAddress = row.address;
            next.next.pendingAliasMask = row.aliasMask;
        }

        const ResultPlane plane = takeNextDisplayPlane(next.next.pendingAliasMask);
        if (plane == ResultPlane::Count) {
            return false;
        }
        next.rows.push_back({next.next.pendingAddress, plane});
        if (next.next.pendingAliasMask == 0U) {
            next.next.pendingAddress = 0U;
        }
    }

    page = std::move(next);
    return true;
}

bool seekAliasAddressOffset(const ResultStore &store,
                            std::uint64_t addressOffset,
                            ResultAliasCursor &cursor,
                            std::uint64_t &typedOffset) {
    if (addressOffset > store.uniqueAddressCount()) {
        return false;
    }
    if (addressOffset == store.uniqueAddressCount()) {
        cursor = {{store.blockCount(), 0U}, 0U, 0U};
        typedOffset = store.typedCount();
        return true;
    }

    ResultCursor addressCursor;
    if (!seekAddressOffset(store, addressOffset, addressCursor) ||
        addressCursor.blockIndex >= store.blockCount()) {
        return false;
    }

    std::uint64_t prefix = 0U;
    const auto headers = store.headers();
    for (std::size_t block = 0U; block < addressCursor.blockIndex; ++block) {
        if (!addChecked(prefix, blockTypedCount(headers[block]))) {
            return false;
        }
    }

    const std::size_t byteOffset = addressCursor.nextByteOffset;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const ResultPlane plane = static_cast<ResultPlane>(index);
        const std::size_t alignment = planeAlignment(plane);
        if (alignment == 0U) {
            return false;
        }
        // Number of aligned plane slots whose byte address is strictly before byteOffset.
        const std::size_t slotLimit =
                (byteOffset + alignment - 1U) / alignment;
        const std::uint64_t count =
                setBitsBeforeSlot(store.planeWords(addressCursor.blockIndex, plane),
                                  slotLimit);
        if (!addChecked(prefix, count)) {
            return false;
        }
    }
    if (prefix > store.typedCount()) {
        return false;
    }

    cursor = {addressCursor, 0U, 0U};
    typedOffset = prefix;
    return true;
}

bool seekAliasTypedOffset(const ResultStore &store,
                          std::uint64_t typedOffset,
                          ResultAliasCursor &cursor) {
    if (typedOffset > store.typedCount()) {
        return false;
    }
    if (typedOffset == store.typedCount()) {
        cursor = {{store.blockCount(), 0U}, 0U, 0U};
        return true;
    }

    const auto headers = store.headers();
    std::uint64_t remaining = typedOffset;
    std::size_t blockIndex = 0U;
    for (; blockIndex < headers.size(); ++blockIndex) {
        const std::uint64_t count = blockTypedCount(headers[blockIndex]);
        if (remaining < count) {
            break;
        }
        remaining -= count;
    }
    if (blockIndex >= headers.size()) {
        return false;
    }

    ResultAliasCursor working{{blockIndex, 0U}, 0U, 0U};
    while (remaining != 0U) {
        const std::size_t step = static_cast<std::size_t>(std::min<std::uint64_t>(
                remaining, kResultAliasCursorPageLimit));
        ResultAliasPage skipped;
        if (step == 0U || !readAliasPage(store, working, step, skipped) ||
            skipped.rows.size() != step) {
            return false;
        }
        working = skipped.next;
        remaining -= step;
    }
    cursor = working;
    return true;
}

bool readAliasPageAtTypedOffset(const ResultStore &store,
                                std::uint64_t typedOffset,
                                std::size_t limit,
                                ResultAliasPage &page) {
    ResultAliasCursor cursor;
    return seekAliasTypedOffset(store, typedOffset, cursor) &&
           readAliasPage(store, cursor, limit, page);
}

} // namespace jlmem::v2
