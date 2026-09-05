/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "ordinary_result_store.h"

#include <algorithm>
#include <limits>
#include <numeric>
#include <utility>

namespace jlmem::v2 {

bool OrdinaryResultStore::reserve(std::size_t count) noexcept {
    try {
        records_.reserve(count);
        const std::size_t wordCount = count / 64U + (count % 64U == 0U ? 0U : 1U);
        identityValidBits_.reserve(wordCount);
        if (!relocationCounts_.empty()) {
            relocationCounts_.reserve(count);
        }
        return true;
    } catch (...) {
        return false;
    }
}

bool OrdinaryResultStore::append(const OrdinaryResultRecord &record,
                                 bool identityValid,
                                 std::uint16_t relocationCount) noexcept {
    if (record.id == 0U || records_.size() == std::numeric_limits<std::size_t>::max() ||
        records_.size() >= static_cast<std::size_t>(
                                   std::numeric_limits<std::uint32_t>::max())) {
        return false;
    }
    if (!records_.empty() && record.id <= records_.back().id) {
        idsStrictlyIncreasing_ = false;
    }
    // Appending after a finalized non-monotonic index would make that immutable lookup stale.
    if (!idOrder_.empty()) {
        return false;
    }

    const std::size_t index = records_.size();
    const std::size_t wordIndex = identityWordIndex(index);
    try {
        const bool addedWord = wordIndex == identityValidBits_.size();
        if (addedWord) {
            identityValidBits_.push_back(0U);
        } else if (wordIndex > identityValidBits_.size()) {
            return false;
        }

        const bool createRelocationArray =
                relocationCount != 0U && relocationCounts_.empty();
        if (createRelocationArray) {
            relocationCounts_.assign(index, 0U);
        }
        if (!relocationCounts_.empty()) {
            try {
                relocationCounts_.push_back(relocationCount);
            } catch (...) {
                if (createRelocationArray) {
                    std::vector<std::uint16_t>().swap(relocationCounts_);
                }
                if (addedWord) {
                    identityValidBits_.pop_back();
                }
                return false;
            }
        }

        try {
            records_.push_back(record);
        } catch (...) {
            if (!relocationCounts_.empty() && relocationCounts_.size() == index + 1U) {
                relocationCounts_.pop_back();
                if (createRelocationArray) {
                    std::vector<std::uint16_t>().swap(relocationCounts_);
                }
            }
            if (addedWord) {
                identityValidBits_.pop_back();
            }
            return false;
        }
        if (identityValid) {
            identityValidBits_[wordIndex] |= identityBit(index);
        }
        return true;
    } catch (...) {
        return false;
    }
}

bool OrdinaryResultStore::finalizeIdIndex() noexcept {
    if (idsStrictlyIncreasing_) {
        std::vector<std::uint32_t>().swap(idOrder_);
        return true;
    }
    if (records_.size() > static_cast<std::size_t>(
                                  std::numeric_limits<std::uint32_t>::max())) {
        return false;
    }
    try {
        std::vector<std::uint32_t> order(records_.size());
        std::iota(order.begin(), order.end(), std::uint32_t{0U});
        std::sort(order.begin(), order.end(), [&](std::uint32_t left,
                                                  std::uint32_t right) {
            return records_[left].id < records_[right].id;
        });
        for (std::size_t index = 1U; index < order.size(); ++index) {
            if (records_[order[index - 1U]].id == records_[order[index]].id) {
                return false;
            }
        }
        idOrder_ = std::move(order);
        return true;
    } catch (...) {
        return false;
    }
}

bool OrdinaryResultStore::updateIdentity(std::size_t index,
                                         std::uint64_t identityHash,
                                         bool identityValid) noexcept {
    if (index >= records_.size()) {
        return false;
    }
    const std::size_t wordIndex = identityWordIndex(index);
    if (wordIndex >= identityValidBits_.size()) {
        return false;
    }
    records_[index].identityHash = identityHash;
    const std::uint64_t bit = identityBit(index);
    if (identityValid) {
        identityValidBits_[wordIndex] |= bit;
    } else {
        identityValidBits_[wordIndex] &= ~bit;
    }
    return true;
}

std::optional<std::size_t> OrdinaryResultStore::findIndexById(
        std::uint64_t id) const noexcept {
    if (id == 0U || records_.empty()) {
        return std::nullopt;
    }
    if (idsStrictlyIncreasing_) {
        const auto found = std::lower_bound(
                records_.begin(), records_.end(), id,
                [](const OrdinaryResultRecord &record, std::uint64_t wanted) {
                    return record.id < wanted;
                });
        if (found == records_.end() || found->id != id) {
            return std::nullopt;
        }
        return static_cast<std::size_t>(std::distance(records_.begin(), found));
    }
    if (idOrder_.size() != records_.size()) {
        return std::nullopt;
    }
    const auto found = std::lower_bound(
            idOrder_.begin(), idOrder_.end(), id,
            [&](std::uint32_t ordinal, std::uint64_t wanted) {
                return records_[ordinal].id < wanted;
            });
    if (found == idOrder_.end() || records_[*found].id != id) {
        return std::nullopt;
    }
    return static_cast<std::size_t>(*found);
}

const OrdinaryResultRecord *OrdinaryResultStore::record(
        std::size_t index) const noexcept {
    return index < records_.size() ? &records_[index] : nullptr;
}

bool OrdinaryResultStore::identityValid(std::size_t index) const noexcept {
    if (index >= records_.size()) {
        return false;
    }
    const std::size_t wordIndex = identityWordIndex(index);
    return wordIndex < identityValidBits_.size() &&
           (identityValidBits_[wordIndex] & identityBit(index)) != 0U;
}

std::uint16_t OrdinaryResultStore::relocationCount(std::size_t index) const noexcept {
    if (index >= records_.size() || relocationCounts_.empty()) {
        return 0U;
    }
    return index < relocationCounts_.size() ? relocationCounts_[index] : 0U;
}

std::size_t OrdinaryResultStore::retainedBytes() const noexcept {
    std::size_t result = sizeof(OrdinaryResultStore);
    const auto addCapacity = [&](std::size_t capacity, std::size_t elementSize) {
        if (capacity > std::numeric_limits<std::size_t>::max() / elementSize) {
            return false;
        }
        const std::size_t bytes = capacity * elementSize;
        if (result > std::numeric_limits<std::size_t>::max() - bytes) {
            return false;
        }
        result += bytes;
        return true;
    };
    if (!addCapacity(records_.capacity(), sizeof(OrdinaryResultRecord)) ||
        !addCapacity(identityValidBits_.capacity(), sizeof(std::uint64_t)) ||
        !addCapacity(relocationCounts_.capacity(), sizeof(std::uint16_t)) ||
        !addCapacity(idOrder_.capacity(), sizeof(std::uint32_t))) {
        return std::numeric_limits<std::size_t>::max();
    }
    return result;
}

void OrdinaryResultStore::clear() noexcept {
    std::vector<OrdinaryResultRecord>().swap(records_);
    std::vector<std::uint64_t>().swap(identityValidBits_);
    std::vector<std::uint16_t>().swap(relocationCounts_);
    std::vector<std::uint32_t>().swap(idOrder_);
    idsStrictlyIncreasing_ = true;
}

} // namespace jlmem::v2
