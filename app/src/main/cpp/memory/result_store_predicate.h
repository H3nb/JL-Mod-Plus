/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <type_traits>

namespace jlmem::v2 {

// Keep these values aligned with MemoryEngineContract's stable Known predicate constants.
enum class KnownPredicate : std::uint8_t {
    Equal = 0U,
    NotEqual = 1U,
    Greater = 2U,
    Less = 3U,
    GreaterOrEqual = 4U,
    LessOrEqual = 5U,
    Between = 6U,
};

template <typename T>
[[nodiscard]] T knownValueFromBits(std::uint64_t bits) noexcept {
    static_assert(std::is_trivially_copyable_v<T>);
    static_assert(sizeof(T) <= sizeof(bits));
    T value{};
    std::memcpy(&value, &bits, sizeof(T));
    return value;
}

// Predicate is a template argument so scan/refine dispatch it once before entering the hot slot
// loop. Floating-point behavior intentionally matches the validated legacy backend: every known
// predicate rejects NaN target values, while +/-0 compare numerically equal. Between is inclusive.
template <typename T, KnownPredicate Predicate>
[[nodiscard]] bool matchesKnownValue(T value,
                                     std::uint64_t firstBits,
                                     std::uint64_t secondBits) noexcept {
    if constexpr (std::is_floating_point_v<T>) {
        if (std::isnan(value)) {
            return false;
        }
    }

    const T first = knownValueFromBits<T>(firstBits);
    if constexpr (Predicate == KnownPredicate::Equal) {
        return value == first;
    } else if constexpr (Predicate == KnownPredicate::NotEqual) {
        return value != first;
    } else if constexpr (Predicate == KnownPredicate::Greater) {
        return value > first;
    } else if constexpr (Predicate == KnownPredicate::Less) {
        return value < first;
    } else if constexpr (Predicate == KnownPredicate::GreaterOrEqual) {
        return value >= first;
    } else if constexpr (Predicate == KnownPredicate::LessOrEqual) {
        return value <= first;
    } else if constexpr (Predicate == KnownPredicate::Between) {
        const T second = knownValueFromBits<T>(secondBits);
        return value >= first && value <= second;
    }
}

} // namespace jlmem::v2
