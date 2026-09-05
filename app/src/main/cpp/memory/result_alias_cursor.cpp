/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_alias_cursor.h"

#include <array>
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

} // namespace jlmem::v2
