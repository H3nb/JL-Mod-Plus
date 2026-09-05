/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace jlmem::v2 {

// Ordinary search membership already lives in ResultStore, so this record intentionally does NOT
// duplicate address, primitive type, relocation state or relocation count. Those are obtained from
// ResultAliasCursor or promoted into sparse tracked-candidate state only when an identity-sensitive
// feature (Watch/Edit/Freeze/Inspector) actually needs them.
struct OrdinaryResultRecord {
    std::uint64_t id = 0U;
    std::uint64_t initialBits = 0U;
    std::uint64_t previousBits = 0U;
    std::uint64_t currentBits = 0U;
    std::uint64_t identityHash = 0U;
};

static_assert(sizeof(OrdinaryResultRecord) == 40U,
              "ordinary result metadata must stay compact");

class OrdinaryResultStore final {
  public:
    [[nodiscard]] bool reserve(std::size_t count) noexcept;
    [[nodiscard]] bool append(const OrdinaryResultRecord &record,
                              bool identityValid) noexcept;

    [[nodiscard]] std::size_t size() const noexcept { return records_.size(); }
    [[nodiscard]] bool empty() const noexcept { return records_.empty(); }
    [[nodiscard]] std::span<const OrdinaryResultRecord> records() const noexcept {
        return records_;
    }
    [[nodiscard]] const OrdinaryResultRecord *record(
            std::size_t index) const noexcept;
    [[nodiscard]] bool identityValid(std::size_t index) const noexcept;
    [[nodiscard]] std::size_t retainedBytes() const noexcept;

    void clear() noexcept;

  private:
    [[nodiscard]] static constexpr std::size_t identityWordIndex(
            std::size_t index) noexcept {
        return index / 64U;
    }
    [[nodiscard]] static constexpr std::uint64_t identityBit(
            std::size_t index) noexcept {
        return std::uint64_t{1U} << (index % 64U);
    }

    std::vector<OrdinaryResultRecord> records_;
    // One bit instead of a padded bool in every row. identityHash itself is retained because it is
    // useful both for cheap movement sampling and later promotion into TrackedCandidate.
    std::vector<std::uint64_t> identityValidBits_;
};

} // namespace jlmem::v2
