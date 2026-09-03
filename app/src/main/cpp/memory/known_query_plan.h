/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "result_store.h"
#include "result_store_predicate.h"

#include <cstdint>
#include <optional>

namespace jlmem::v2 {

/**
 * Canonical parsed Known-query representation consumed by every v2 first-scan/refine kernel.
 *
 * The legacy parser remains authoritative. Production integration should translate its already
 * parsed primitive thresholds into this structure exactly once; v2 must never reparse the user's
 * query strings independently.
 */
struct KnownQueryPlan {
    ResultPlane plane = ResultPlane::Int;
    KnownPredicate predicate = KnownPredicate::Equal;
    std::uint64_t firstBits = 0U;
    std::uint64_t secondBits = 0U;
};

// Stable Java/AIDL value-type constants. Keep the explicit mapping here because ResultPlane's
// internal order intentionally groups Float with Int and therefore does not match the wire values.
[[nodiscard]] constexpr std::optional<ResultPlane> resultPlaneFromStableValueType(
        int valueType) noexcept {
    switch (valueType) {
    case 1: return ResultPlane::Byte;
    case 2: return ResultPlane::Short;
    case 3: return ResultPlane::Char;
    case 4: return ResultPlane::Int;
    case 5: return ResultPlane::Long;
    case 6: return ResultPlane::Float;
    case 7: return ResultPlane::Double;
    default: return std::nullopt;
    }
}

[[nodiscard]] constexpr std::optional<KnownPredicate> knownPredicateFromStableValue(
        int predicate) noexcept {
    switch (predicate) {
    case 0: return KnownPredicate::Equal;
    case 1: return KnownPredicate::NotEqual;
    case 2: return KnownPredicate::Greater;
    case 3: return KnownPredicate::Less;
    case 4: return KnownPredicate::GreaterOrEqual;
    case 5: return KnownPredicate::LessOrEqual;
    case 6: return KnownPredicate::Between;
    default: return std::nullopt;
    }
}

[[nodiscard]] constexpr bool validKnownQueryPlan(const KnownQueryPlan &plan) noexcept {
    return plan.plane != ResultPlane::Count;
}

[[nodiscard]] constexpr std::optional<KnownQueryPlan> knownQueryPlanFromStableValues(
        int valueType, int predicate, std::uint64_t firstBits,
        std::uint64_t secondBits) noexcept {
    const auto plane = resultPlaneFromStableValueType(valueType);
    const auto knownPredicate = knownPredicateFromStableValue(predicate);
    if (!plane.has_value() || !knownPredicate.has_value()) {
        return std::nullopt;
    }
    return KnownQueryPlan{*plane, *knownPredicate, firstBits, secondBits};
}

static_assert(resultPlaneFromStableValueType(1) == ResultPlane::Byte);
static_assert(resultPlaneFromStableValueType(4) == ResultPlane::Int);
static_assert(resultPlaneFromStableValueType(5) == ResultPlane::Long);
static_assert(resultPlaneFromStableValueType(6) == ResultPlane::Float);
static_assert(resultPlaneFromStableValueType(7) == ResultPlane::Double);
static_assert(!resultPlaneFromStableValueType(0).has_value());
static_assert(knownPredicateFromStableValue(0) == KnownPredicate::Equal);
static_assert(knownPredicateFromStableValue(6) == KnownPredicate::Between);
static_assert(!knownPredicateFromStableValue(7).has_value());

} // namespace jlmem::v2
