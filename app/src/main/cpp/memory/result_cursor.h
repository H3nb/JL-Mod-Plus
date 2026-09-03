/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "result_store.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace jlmem::v2 {

constexpr std::size_t kResultCursorPageLimit = 100U;

struct ResultCursor {
    std::size_t blockIndex = 0U;
    std::uint16_t nextByteOffset = 0U;
};

struct ResultAddressRow {
    std::uintptr_t address = 0U;
    // One bit per ResultPlane. Auto aliases at the same raw address therefore occupy one row.
    std::uint8_t aliasMask = 0U;
};

struct ResultAddressPage {
    std::vector<ResultAddressRow> rows;
    ResultCursor next;
};

[[nodiscard]] constexpr std::uint8_t resultPlaneBit(ResultPlane plane) noexcept {
    return plane == ResultPlane::Count
                   ? 0U
                   : static_cast<std::uint8_t>(1U << planeIndex(plane));
}

// Enumerates address-ordered logical rows without materializing Candidate records. The cursor is
// revision-local: callers must discard it when the owning ResultStore revision changes.
[[nodiscard]] bool readAddressPage(const ResultStore &store,
                                   ResultCursor cursor,
                                   std::size_t limit,
                                   ResultAddressPage &page);

// Translate the legacy offset-style page contract to a ResultCursor without walking every prior
// result. Whole blocks are skipped using their unique-address counts; only the containing 4 KiB
// block needs bitmap enumeration. This is the transitional bridge for production Known paging.
[[nodiscard]] bool seekAddressOffset(const ResultStore &store,
                                     std::uint64_t addressOffset,
                                     ResultCursor &cursor);

[[nodiscard]] bool readAddressPageAtOffset(const ResultStore &store,
                                           std::uint64_t addressOffset,
                                           std::size_t limit,
                                           ResultAddressPage &page);

} // namespace jlmem::v2
