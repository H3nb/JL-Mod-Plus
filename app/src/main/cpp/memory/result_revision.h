/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

#include "ordinary_result_store.h"
#include "result_cursor.h"
#include "result_store.h"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <vector>

namespace jlmem::v2 {

enum class ResultRevisionKind : std::uint8_t {
    Explicit,
    Auto,
};

// Immutable ownership object associated with one SearchState revision. Global staging caches remain
// only JNI/page acceleration; they are not intended to be the sole lifetime owner of authoritative
// ResultStore membership. ordinaryValues is the compact production value/ResultId sidecar; it is
// indexed by ResultAliasCursor typed ordinal and therefore never duplicates address/type membership.
struct ResultRevision final {
    ResultRevisionKind kind = ResultRevisionKind::Explicit;
    ResultPlane plane = ResultPlane::Count;
    std::shared_ptr<const ResultStore> store;
    std::shared_ptr<const std::vector<ResultCursor>> checkpoints;
    // Transitional Candidate-era Auto offset index. Compact revisions intentionally leave it null;
    // seekAliasAddressOffset()/seekAliasTypedOffset() derive ordinals from bitmap/header counts.
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
    std::shared_ptr<const OrdinaryResultStore> ordinaryValues;

    [[nodiscard]] bool valid() const noexcept {
        if (store == nullptr || checkpoints == nullptr ||
            (ordinaryValues != nullptr && ordinaryValues->size() != store->typedCount())) {
            return false;
        }
        if (kind == ResultRevisionKind::Explicit) {
            return plane != ResultPlane::Count && candidateOffsets == nullptr;
        }
        if (kind != ResultRevisionKind::Auto || plane != ResultPlane::Count) {
            return false;
        }
        // Legacy Auto staging needs Candidate offsets; compact Auto owns value ordinals directly.
        return candidateOffsets != nullptr || ordinaryValues != nullptr;
    }

    [[nodiscard]] bool compact() const noexcept {
        return valid() && ordinaryValues != nullptr;
    }

    [[nodiscard]] std::size_t retainedBytes() const noexcept {
        if (!valid()) {
            return 0U;
        }
        std::size_t result = sizeof(ResultRevision);
        const auto add = [&](std::size_t bytes) {
            if (bytes == std::numeric_limits<std::size_t>::max() ||
                result > std::numeric_limits<std::size_t>::max() - bytes) {
                return false;
            }
            result += bytes;
            return true;
        };
        if (!add(store->retainedBytes())) {
            return std::numeric_limits<std::size_t>::max();
        }
        const std::size_t checkpointBytes =
                checkpoints->capacity() >
                                std::numeric_limits<std::size_t>::max() /
                                        sizeof(ResultCursor)
                        ? std::numeric_limits<std::size_t>::max()
                        : checkpoints->capacity() * sizeof(ResultCursor);
        if (!add(checkpointBytes)) {
            return std::numeric_limits<std::size_t>::max();
        }
        if (candidateOffsets != nullptr) {
            const std::size_t offsetBytes =
                    candidateOffsets->capacity() >
                                    std::numeric_limits<std::size_t>::max() /
                                            sizeof(std::size_t)
                            ? std::numeric_limits<std::size_t>::max()
                            : candidateOffsets->capacity() * sizeof(std::size_t);
            if (!add(offsetBytes)) {
                return std::numeric_limits<std::size_t>::max();
            }
        }
        if (ordinaryValues != nullptr && !add(ordinaryValues->retainedBytes())) {
            return std::numeric_limits<std::size_t>::max();
        }
        return result;
    }
};

} // namespace jlmem::v2
