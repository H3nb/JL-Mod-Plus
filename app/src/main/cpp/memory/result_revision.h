/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#pragma once

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
// ResultStore membership. The transitional registry can therefore restore exact COW state across
// Undo without rebuilding a bitmap from the Candidate compatibility mirror.
struct ResultRevision final {
    ResultRevisionKind kind = ResultRevisionKind::Explicit;
    ResultPlane plane = ResultPlane::Count;
    std::shared_ptr<const ResultStore> store;
    std::shared_ptr<const std::vector<ResultCursor>> checkpoints;
    // Auto only: Candidate-vector offset corresponding to each cursor checkpoint. Empty for an
    // explicit single-plane revision, whose candidate ordinal equals its unique address ordinal.
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;

    [[nodiscard]] bool valid() const noexcept {
        if (store == nullptr || checkpoints == nullptr) {
            return false;
        }
        if (kind == ResultRevisionKind::Explicit) {
            return plane != ResultPlane::Count && candidateOffsets == nullptr;
        }
        return kind == ResultRevisionKind::Auto &&
               plane == ResultPlane::Count && candidateOffsets != nullptr;
    }

    [[nodiscard]] std::size_t retainedBytes() const noexcept {
        if (!valid()) {
            return 0U;
        }
        std::size_t result = sizeof(ResultRevision);
        const auto add = [&](std::size_t bytes) {
            if (result > std::numeric_limits<std::size_t>::max() - bytes) {
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
        if (checkpointBytes == std::numeric_limits<std::size_t>::max() ||
            !add(checkpointBytes)) {
            return std::numeric_limits<std::size_t>::max();
        }
        if (candidateOffsets != nullptr) {
            const std::size_t offsetBytes =
                    candidateOffsets->capacity() >
                                    std::numeric_limits<std::size_t>::max() /
                                            sizeof(std::size_t)
                            ? std::numeric_limits<std::size_t>::max()
                            : candidateOffsets->capacity() * sizeof(std::size_t);
            if (offsetBytes == std::numeric_limits<std::size_t>::max() ||
                !add(offsetBytes)) {
                return std::numeric_limits<std::size_t>::max();
            }
        }
        return result;
    }
};

} // namespace jlmem::v2
