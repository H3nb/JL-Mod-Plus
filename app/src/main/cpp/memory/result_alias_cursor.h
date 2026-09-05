/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "result_cursor.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace jlmem::v2 {

// One address can expose every primitive interpretation. A 100-address UI page therefore needs at
// most 700 typed rows. This cursor is also the canonical typed ordinal used by compact ordinary
// metadata, so no Candidate vector is needed to expand Auto aliases.
constexpr std::size_t kResultAliasCursorPageLimit =
        kResultCursorPageLimit * kResultPlaneCount;

struct ResultAliasCursor {
    // Always points to the address following pendingAddress. If pendingAliasMask is non-zero, the
    // next typed row comes from that pending address before addressCursor is advanced again.
    ResultCursor addressCursor;
    std::uintptr_t pendingAddress = 0U;
    std::uint8_t pendingAliasMask = 0U;
};

struct ResultAliasRow {
    std::uintptr_t address = 0U;
    ResultPlane plane = ResultPlane::Count;
};

struct ResultAliasPage {
    std::vector<ResultAliasRow> rows;
    ResultAliasCursor next;
};

// Stable typed display order inherited from the transitional production UI. Keep it explicit: the
// internal ResultPlane enum is layout-oriented (Byte first) and intentionally has a different order.
[[nodiscard]] constexpr std::size_t resultAliasDisplayPriority(
        ResultPlane plane) noexcept {
    switch (plane) {
    case ResultPlane::Int: return 0U;
    case ResultPlane::Float: return 1U;
    case ResultPlane::Long: return 2U;
    case ResultPlane::Double: return 3U;
    case ResultPlane::Short: return 4U;
    case ResultPlane::Char: return 5U;
    case ResultPlane::Byte: return 6U;
    case ResultPlane::Count: return kResultPlaneCount;
    }
    return kResultPlaneCount;
}

// Enumerate typed aliases in (address ascending, stable display-type order). Page boundaries may
// split aliases at one address; pendingAliasMask makes continuation lossless and deterministic.
[[nodiscard]] bool readAliasPage(const ResultStore &store,
                                 ResultAliasCursor cursor,
                                 std::size_t limit,
                                 ResultAliasPage &page);

} // namespace jlmem::v2
