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

#include <cmath>
#include <cstdint>
#include <optional>
#include <type_traits>

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

[[nodiscard]] constexpr int stableValueTypeFromResultPlane(
        ResultPlane plane) noexcept {
    switch (plane) {
    case ResultPlane::Byte: return 1;
    case ResultPlane::Short: return 2;
    case ResultPlane::Char: return 3;
    case ResultPlane::Int: return 4;
    case ResultPlane::Long: return 5;
    case ResultPlane::Float: return 6;
    case ResultPlane::Double: return 7;
    case ResultPlane::Count: return 0;
    }
    return 0;
}

// ResultCursor aliases use compact internal plane bits (0..6), while Binder/UI aliases use
// `1 << stableValueType` (1..7). Never expose the internal mask directly across IPC.
[[nodiscard]] constexpr std::uint32_t stableAliasMaskFromResultPlaneMask(
        std::uint8_t planeMask) noexcept {
    std::uint32_t stableMask = 0U;
    for (std::size_t index = 0U; index < kResultPlaneCount; ++index) {
        const std::uint8_t internalBit =
                static_cast<std::uint8_t>(1U << index);
        if ((planeMask & internalBit) == 0U) {
            continue;
        }
        const int stableType =
                stableValueTypeFromResultPlane(static_cast<ResultPlane>(index));
        if (stableType > 0) {
            stableMask |= std::uint32_t{1U} << stableType;
        }
    }
    return stableMask;
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

// The production parser emits zero-extended raw primitive bits. Reject non-canonical debug/JNI
// inputs rather than silently ignoring high bits in a narrow value and accidentally validating a
// query representation production could never produce.
[[nodiscard]] constexpr bool hasCanonicalKnownWidth(ResultPlane plane,
                                                     std::uint64_t bits) noexcept {
    switch (plane) {
    case ResultPlane::Byte:
        return bits <= UINT8_MAX;
    case ResultPlane::Short:
    case ResultPlane::Char:
        return bits <= UINT16_MAX;
    case ResultPlane::Int:
    case ResultPlane::Float:
        return bits <= UINT32_MAX;
    case ResultPlane::Long:
    case ResultPlane::Double:
        return true;
    case ResultPlane::Count:
        return false;
    }
    return false;
}

template <typename T>
[[nodiscard]] bool validKnownBounds(const KnownQueryPlan &plan) noexcept {
    const T first = knownValueFromBits<T>(plan.firstBits);
    if constexpr (std::is_floating_point_v<T>) {
        if (!std::isfinite(first)) {
            return false;
        }
    }
    if (plan.predicate != KnownPredicate::Between) {
        // A single-threshold canonical plan has no hidden second operand.
        return plan.secondBits == 0U;
    }
    const T second = knownValueFromBits<T>(plan.secondBits);
    if constexpr (std::is_floating_point_v<T>) {
        if (!std::isfinite(second)) {
            return false;
        }
    }
    return first <= second;
}

// Header-defined because every specialized scan/refine translation unit validates at its own
// entry boundary. Keep it inline so the ODR remains valid when the same guard is emitted in all
// native objects.
[[nodiscard]] inline bool validKnownQueryPlan(const KnownQueryPlan &plan) noexcept {
    if (plan.plane == ResultPlane::Count ||
        !hasCanonicalKnownWidth(plan.plane, plan.firstBits) ||
        !hasCanonicalKnownWidth(plan.plane, plan.secondBits)) {
        return false;
    }
    switch (plan.predicate) {
    case KnownPredicate::Equal:
    case KnownPredicate::NotEqual:
    case KnownPredicate::Greater:
    case KnownPredicate::Less:
    case KnownPredicate::GreaterOrEqual:
    case KnownPredicate::LessOrEqual:
    case KnownPredicate::Between:
        break;
    default:
        return false;
    }
    switch (plan.plane) {
    case ResultPlane::Byte:
        return validKnownBounds<std::int8_t>(plan);
    case ResultPlane::Short:
        return validKnownBounds<std::int16_t>(plan);
    case ResultPlane::Char:
        return validKnownBounds<std::uint16_t>(plan);
    case ResultPlane::Int:
        return validKnownBounds<std::int32_t>(plan);
    case ResultPlane::Float:
        return validKnownBounds<float>(plan);
    case ResultPlane::Long:
        return validKnownBounds<std::int64_t>(plan);
    case ResultPlane::Double:
        return validKnownBounds<double>(plan);
    case ResultPlane::Count:
        return false;
    }
    return false;
}

[[nodiscard]] inline std::optional<KnownQueryPlan> knownQueryPlanFromStableValues(
        int valueType, int predicate, std::uint64_t firstBits,
        std::uint64_t secondBits) noexcept {
    const auto plane = resultPlaneFromStableValueType(valueType);
    const auto knownPredicate = knownPredicateFromStableValue(predicate);
    if (!plane.has_value() || !knownPredicate.has_value()) {
        return std::nullopt;
    }
    const KnownQueryPlan plan{*plane, *knownPredicate, firstBits, secondBits};
    return validKnownQueryPlan(plan) ? std::optional<KnownQueryPlan>{plan}
                                     : std::nullopt;
}

static_assert(resultPlaneFromStableValueType(1) == ResultPlane::Byte);
static_assert(resultPlaneFromStableValueType(4) == ResultPlane::Int);
static_assert(resultPlaneFromStableValueType(5) == ResultPlane::Long);
static_assert(resultPlaneFromStableValueType(6) == ResultPlane::Float);
static_assert(resultPlaneFromStableValueType(7) == ResultPlane::Double);
static_assert(!resultPlaneFromStableValueType(0).has_value());
static_assert(stableValueTypeFromResultPlane(ResultPlane::Int) == 4);
static_assert(stableValueTypeFromResultPlane(ResultPlane::Long) == 5);
static_assert(stableValueTypeFromResultPlane(ResultPlane::Float) == 6);
static_assert(stableAliasMaskFromResultPlaneMask(
                      static_cast<std::uint8_t>(1U << planeIndex(ResultPlane::Int))) ==
              (std::uint32_t{1U} << 4));
static_assert(stableAliasMaskFromResultPlaneMask(
                      static_cast<std::uint8_t>(1U << planeIndex(ResultPlane::Float))) ==
              (std::uint32_t{1U} << 6));
static_assert(stableAliasMaskFromResultPlaneMask(0x7fU) == 0xfeU);
static_assert(knownPredicateFromStableValue(0) == KnownPredicate::Equal);
static_assert(knownPredicateFromStableValue(6) == KnownPredicate::Between);
static_assert(!knownPredicateFromStableValue(7).has_value());
static_assert(hasCanonicalKnownWidth(ResultPlane::Byte, 0xffU));
static_assert(!hasCanonicalKnownWidth(ResultPlane::Byte, 0x100U));
static_assert(hasCanonicalKnownWidth(ResultPlane::Float, 0xffffffffU));
static_assert(!hasCanonicalKnownWidth(ResultPlane::Float, UINT64_C(0x100000000)));

} // namespace jlmem::v2
