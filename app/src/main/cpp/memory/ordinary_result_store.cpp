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
#include <utility>

namespace jlmem::v2 {

bool OrdinaryResultStore::reserve(std::size_t count) noexcept {
    try {
        records_.reserve(count);
        const std::size_t wordCount = count / 64U + (count % 64U == 0U ? 0U : 1U);
        identityValidBits_.reserve(wordCount);
        return true;
    } catch (...) {
        return false;
    }
}

bool OrdinaryResultStore::append(const OrdinaryResultRecord &record,
                                 bool identityValid) noexcept {
    if (record.id == 0U || records_.size() == std::numeric_limits<std::size_t>::max() ||
        (!records_.empty() && record.id <= records_.back().id)) {
        return false;
    }
    const std::size_t index = records_.size();
    const std::size_t wordIndex = identityWordIndex(index);
    try {
        // Grow the validity vector first. If record insertion later fails, roll this newly-created
        // word back so the two containers remain transactionally aligned.
        const bool addedWord = wordIndex == identityValidBits_.size();
        if (addedWord) {
            identityValidBits_.push_back(0U);
        } else if (wordIndex > identityValidBits_.size()) {
            return false;
        }
        try {
            records_.push_back(record);
        } catch (...) {
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
        !addCapacity(identityValidBits_.capacity(), sizeof(std::uint64_t))) {
        return std::numeric_limits<std::size_t>::max();
    }
    return result;
}

void OrdinaryResultStore::clear() noexcept {
    std::vector<OrdinaryResultRecord>().swap(records_);
    std::vector<std::uint64_t>().swap(identityValidBits_);
}

} // namespace jlmem::v2
