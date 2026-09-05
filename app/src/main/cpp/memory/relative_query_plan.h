/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "known_query_plan.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>
#include <type_traits>
#include <utility>

namespace jlmem::v2 {

enum class RelativePredicate : std::uint8_t {
    Changed = 7U,
    Unchanged = 8U,
    Increased = 9U,
    Decreased = 10U,
    IncreasedBy = 11U,
    DecreasedBy = 12U,
    ChangedBy = 13U,
    IncreasedByRange = 14U,
    DecreasedByRange = 15U,
};

struct RelativeQueryPlan {
    ResultPlane plane = ResultPlane::Int;
    RelativePredicate predicate = RelativePredicate::Changed;
    // Integer planes store an unsigned magnitude in the primitive width. Floating planes store
    // the exact canonical Float/Double bits emitted by the established production parser.
    std::uint64_t firstBits = 0U;
    std::uint64_t secondBits = 0U;
};

[[nodiscard]] constexpr std::optional<RelativePredicate>
relativePredicateFromStableValue(int predicate) noexcept {
    switch (predicate) {
    case 7: return RelativePredicate::Changed;
    case 8: return RelativePredicate::Unchanged;
    case 9: return RelativePredicate::Increased;
    case 10: return RelativePredicate::Decreased;
    case 11: return RelativePredicate::IncreasedBy;
    case 12: return RelativePredicate::DecreasedBy;
    case 13: return RelativePredicate::ChangedBy;
    case 14: return RelativePredicate::IncreasedByRange;
    case 15: return RelativePredicate::DecreasedByRange;
    default: return std::nullopt;
    }
}

[[nodiscard]] constexpr bool validRelativePredicate(
        RelativePredicate predicate) noexcept {
    switch (predicate) {
    case RelativePredicate::Changed:
    case RelativePredicate::Unchanged:
    case RelativePredicate::Increased:
    case RelativePredicate::Decreased:
    case RelativePredicate::IncreasedBy:
    case RelativePredicate::DecreasedBy:
    case RelativePredicate::ChangedBy:
    case RelativePredicate::IncreasedByRange:
    case RelativePredicate::DecreasedByRange:
        return true;
    }
    return false;
}

[[nodiscard]] constexpr bool relativePredicateNeedsMagnitude(
        RelativePredicate predicate) noexcept {
    return predicate == RelativePredicate::IncreasedBy ||
           predicate == RelativePredicate::DecreasedBy ||
           predicate == RelativePredicate::ChangedBy ||
           predicate == RelativePredicate::IncreasedByRange ||
           predicate == RelativePredicate::DecreasedByRange;
}

[[nodiscard]] constexpr bool relativePredicateNeedsSecondMagnitude(
        RelativePredicate predicate) noexcept {
    return predicate == RelativePredicate::IncreasedByRange ||
           predicate == RelativePredicate::DecreasedByRange;
}

template <typename T>
[[nodiscard]] bool validRelativeMagnitudeBits(
        const RelativeQueryPlan &plan) noexcept {
    if constexpr (std::is_floating_point_v<T>) {
        const T first = knownValueFromBits<T>(plan.firstBits);
        if (!std::isfinite(first) || first < T{0}) {
            return false;
        }
        if (!relativePredicateNeedsSecondMagnitude(plan.predicate)) {
            return plan.secondBits == 0U;
        }
        const T second = knownValueFromBits<T>(plan.secondBits);
        return std::isfinite(second) && second >= T{0} && first <= second;
    } else {
        using Unsigned = std::make_unsigned_t<T>;
        const Unsigned first = knownValueFromBits<Unsigned>(plan.firstBits);
        if (!relativePredicateNeedsSecondMagnitude(plan.predicate)) {
            return plan.secondBits == 0U;
        }
        const Unsigned second = knownValueFromBits<Unsigned>(plan.secondBits);
        return first <= second;
    }
}

[[nodiscard]] inline bool validRelativeQueryPlan(
        const RelativeQueryPlan &plan) noexcept {
    if (!validRelativePredicate(plan.predicate) ||
        plan.plane == ResultPlane::Count ||
        !hasCanonicalKnownWidth(plan.plane, plan.firstBits) ||
        !hasCanonicalKnownWidth(plan.plane, plan.secondBits)) {
        return false;
    }
    if (!relativePredicateNeedsMagnitude(plan.predicate)) {
        return plan.firstBits == 0U && plan.secondBits == 0U;
    }
    switch (plan.plane) {
    case ResultPlane::Byte:
        return validRelativeMagnitudeBits<std::int8_t>(plan);
    case ResultPlane::Short:
        return validRelativeMagnitudeBits<std::int16_t>(plan);
    case ResultPlane::Char:
        return validRelativeMagnitudeBits<std::uint16_t>(plan);
    case ResultPlane::Int:
        return validRelativeMagnitudeBits<std::int32_t>(plan);
    case ResultPlane::Float:
        return validRelativeMagnitudeBits<float>(plan);
    case ResultPlane::Long:
        return validRelativeMagnitudeBits<std::int64_t>(plan);
    case ResultPlane::Double:
        return validRelativeMagnitudeBits<double>(plan);
    case ResultPlane::Count:
        return false;
    }
    return false;
}

[[nodiscard]] inline std::optional<RelativeQueryPlan>
relativeQueryPlanFromStableValues(int valueType, int predicate,
                                  std::uint64_t firstBits,
                                  std::uint64_t secondBits) noexcept {
    const auto plane = resultPlaneFromStableValueType(valueType);
    const auto relativePredicate = relativePredicateFromStableValue(predicate);
    if (!plane.has_value() || !relativePredicate.has_value()) {
        return std::nullopt;
    }
    const RelativeQueryPlan plan{
            *plane, *relativePredicate, firstBits, secondBits};
    return validRelativeQueryPlan(plan)
                   ? std::optional<RelativeQueryPlan>{plan}
                   : std::nullopt;
}

template <typename T>
[[nodiscard]] constexpr auto unsignedMagnitude(T current, T reference) noexcept {
    using Unsigned = std::make_unsigned_t<T>;
    const bool increased = current >= reference;
    const Unsigned currentUnsigned = static_cast<Unsigned>(current);
    const Unsigned referenceUnsigned = static_cast<Unsigned>(reference);
    return std::pair<bool, Unsigned>{
            increased,
            increased ? static_cast<Unsigned>(currentUnsigned - referenceUnsigned)
                      : static_cast<Unsigned>(referenceUnsigned - currentUnsigned),
    };
}

template <typename T, RelativePredicate Predicate>
[[nodiscard]] bool matchesRelativeValue(
        T current, T reference, std::uint64_t firstBits,
        std::uint64_t secondBits) noexcept {
    if constexpr (std::is_floating_point_v<T>) {
        if (std::isnan(current) || std::isnan(reference)) {
            return false;
        }
        const double currentWide = static_cast<double>(current);
        const double referenceWide = static_cast<double>(reference);
        const double delta = currentWide - referenceWide;
        const double first = static_cast<double>(knownValueFromBits<T>(firstBits));
        const double second = static_cast<double>(knownValueFromBits<T>(secondBits));
        if constexpr (Predicate == RelativePredicate::Changed) {
            return currentWide != referenceWide;
        } else if constexpr (Predicate == RelativePredicate::Unchanged) {
            return currentWide == referenceWide;
        } else if constexpr (Predicate == RelativePredicate::Increased) {
            return currentWide > referenceWide;
        } else if constexpr (Predicate == RelativePredicate::Decreased) {
            return currentWide < referenceWide;
        } else if constexpr (Predicate == RelativePredicate::IncreasedBy) {
            return delta == first;
        } else if constexpr (Predicate == RelativePredicate::DecreasedBy) {
            return -delta == first;
        } else if constexpr (Predicate == RelativePredicate::ChangedBy) {
            return std::fabs(delta) == std::fabs(first);
        } else if constexpr (Predicate == RelativePredicate::IncreasedByRange) {
            return delta >= first && delta <= second;
        } else if constexpr (Predicate == RelativePredicate::DecreasedByRange) {
            return -delta >= first && -delta <= second;
        }
    } else {
        using Unsigned = std::make_unsigned_t<T>;
        const auto [increased, magnitude] = unsignedMagnitude(current, reference);
        const Unsigned first = knownValueFromBits<Unsigned>(firstBits);
        const Unsigned second = knownValueFromBits<Unsigned>(secondBits);
        if constexpr (Predicate == RelativePredicate::Changed) {
            return current != reference;
        } else if constexpr (Predicate == RelativePredicate::Unchanged) {
            return current == reference;
        } else if constexpr (Predicate == RelativePredicate::Increased) {
            return current > reference;
        } else if constexpr (Predicate == RelativePredicate::Decreased) {
            return current < reference;
        } else if constexpr (Predicate == RelativePredicate::IncreasedBy) {
            return increased && magnitude == first;
        } else if constexpr (Predicate == RelativePredicate::DecreasedBy) {
            return !increased && magnitude == first;
        } else if constexpr (Predicate == RelativePredicate::ChangedBy) {
            return magnitude == first;
        } else if constexpr (Predicate == RelativePredicate::IncreasedByRange) {
            return increased && magnitude >= first && magnitude <= second;
        } else if constexpr (Predicate == RelativePredicate::DecreasedByRange) {
            return !increased && magnitude >= first && magnitude <= second;
        }
    }
    return false;
}

template <typename T>
[[nodiscard]] bool dispatchRelativePredicateBits(
        const RelativeQueryPlan &plan, T current, T reference) noexcept {
    switch (plan.predicate) {
    case RelativePredicate::Changed:
        return matchesRelativeValue<T, RelativePredicate::Changed>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::Unchanged:
        return matchesRelativeValue<T, RelativePredicate::Unchanged>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::Increased:
        return matchesRelativeValue<T, RelativePredicate::Increased>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::Decreased:
        return matchesRelativeValue<T, RelativePredicate::Decreased>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::IncreasedBy:
        return matchesRelativeValue<T, RelativePredicate::IncreasedBy>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::DecreasedBy:
        return matchesRelativeValue<T, RelativePredicate::DecreasedBy>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::ChangedBy:
        return matchesRelativeValue<T, RelativePredicate::ChangedBy>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::IncreasedByRange:
        return matchesRelativeValue<T, RelativePredicate::IncreasedByRange>(
                current, reference, plan.firstBits, plan.secondBits);
    case RelativePredicate::DecreasedByRange:
        return matchesRelativeValue<T, RelativePredicate::DecreasedByRange>(
                current, reference, plan.firstBits, plan.secondBits);
    }
    return false;
}

[[nodiscard]] inline bool matchesRelativePlanBits(
        const RelativeQueryPlan &plan, std::uint64_t currentBits,
        std::uint64_t referenceBits) noexcept {
    if (!validRelativeQueryPlan(plan)) {
        return false;
    }
    switch (plan.plane) {
    case ResultPlane::Byte:
        return dispatchRelativePredicateBits<std::int8_t>(
                plan, knownValueFromBits<std::int8_t>(currentBits),
                knownValueFromBits<std::int8_t>(referenceBits));
    case ResultPlane::Short:
        return dispatchRelativePredicateBits<std::int16_t>(
                plan, knownValueFromBits<std::int16_t>(currentBits),
                knownValueFromBits<std::int16_t>(referenceBits));
    case ResultPlane::Char:
        return dispatchRelativePredicateBits<std::uint16_t>(
                plan, knownValueFromBits<std::uint16_t>(currentBits),
                knownValueFromBits<std::uint16_t>(referenceBits));
    case ResultPlane::Int:
        return dispatchRelativePredicateBits<std::int32_t>(
                plan, knownValueFromBits<std::int32_t>(currentBits),
                knownValueFromBits<std::int32_t>(referenceBits));
    case ResultPlane::Float:
        return dispatchRelativePredicateBits<float>(
                plan, knownValueFromBits<float>(currentBits),
                knownValueFromBits<float>(referenceBits));
    case ResultPlane::Long:
        return dispatchRelativePredicateBits<std::int64_t>(
                plan, knownValueFromBits<std::int64_t>(currentBits),
                knownValueFromBits<std::int64_t>(referenceBits));
    case ResultPlane::Double:
        return dispatchRelativePredicateBits<double>(
                plan, knownValueFromBits<double>(currentBits),
                knownValueFromBits<double>(referenceBits));
    case ResultPlane::Count:
        return false;
    }
    return false;
}

static_assert(relativePredicateFromStableValue(7) == RelativePredicate::Changed);
static_assert(relativePredicateFromStableValue(15) ==
              RelativePredicate::DecreasedByRange);
static_assert(!relativePredicateFromStableValue(6).has_value());
static_assert(!validRelativePredicate(static_cast<RelativePredicate>(6U)));
static_assert(unsignedMagnitude<std::int8_t>(127, -128).second == UINT8_MAX);
static_assert(unsignedMagnitude<std::int32_t>(
                      std::numeric_limits<std::int32_t>::max(),
                      std::numeric_limits<std::int32_t>::min())
                      .second == UINT32_MAX);

} // namespace jlmem::v2
