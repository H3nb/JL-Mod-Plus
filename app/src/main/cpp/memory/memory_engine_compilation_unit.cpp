/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transitional production compilation unit.
//
// Search membership and tracked identity are deliberately separate here. Known/Auto Next Scan is
// address-first while the previous context fingerprints remain stable. A bounded fingerprint probe
// detects address-space movement without attaching identity checks to millions of hot-loop reads.
// Once movement is observed, fingerprinted rows are rebuilt by one streaming pass so a stale but
// still-readable raw address cannot survive merely because another object now stores the requested
// value there. Strict write safety remains in the bounded Watch/Edit/Freeze/Inspector path. Delete
// this seam when ResultStore becomes the production search-state owner.
#include "memory_engine_entry.cpp"

namespace {

constexpr std::size_t kExpandedMaxTargetRuns = 16'384U;
constexpr std::size_t kInitialKnownRefineReserve = 16U * 1024U;
constexpr std::size_t kInitialRecoveryIndexReserve = 16U * 1024U;
constexpr std::size_t kCandidateCompactionSlack = 4U * 1024U;
constexpr std::size_t kRelocationProbeSamples = 64U;

struct RecoveryKey {
    ValueType type = ValueType::Invalid;
    std::uint64_t identityHash = 0U;
};

[[nodiscard]] jint configureTargetExpanded(JNIEnv *env, jint pid, jint pageSize,
                                           jlong token, jlongArray rawRuns) {
    try {
        if (pid <= 0 || pageSize <= 0 || (pageSize & (pageSize - 1)) != 0 ||
            token == 0 || rawRuns == nullptr) {
            setMessage("Invalid target configuration");
            return kInvalidRequest;
        }
        const jsize length = env->GetArrayLength(rawRuns);
        const std::size_t rawLength =
                length < 0 ? 0U : static_cast<std::size_t>(length);
        if (length < 4 || (length - 2) % 2 != 0 ||
            rawLength > 2U + kExpandedMaxTargetRuns * 2U) {
            setMessage("Invalid target range list");
            return kInvalidRequest;
        }

        std::array<jlong, 2> header{};
        env->GetLongArrayRegion(rawRuns, 0, 2, header.data());
        if (env->ExceptionCheck()) {
            return kInvalidRequest;
        }
        const jlong declaredRuns = header[0];
        if (header[1] != 0 || declaredRuns <= 0 ||
            static_cast<std::uint64_t>(declaredRuns) > kExpandedMaxTargetRuns ||
            declaredRuns != (length - 2) / 2) {
            setMessage("Incomplete target range list");
            return kResourceLimit;
        }

        std::vector<jlong> values(rawLength);
        env->GetLongArrayRegion(rawRuns, 0, length, values.data());
        if (env->ExceptionCheck() || values[1] != 0 || values[0] != declaredRuns) {
            setMessage("Incomplete target range list");
            return kResourceLimit;
        }

        Target target;
        target.pid = pid;
        target.pageSize = static_cast<std::size_t>(pageSize);
        target.token = token;
        target.ranges.reserve(static_cast<std::size_t>(declaredRuns));
        uintptr_t previousEnd = 0U;
        for (jsize index = 2; index < length; index += 2) {
            if (values[index] <= 0 || values[index + 1] <= values[index] ||
                static_cast<std::uint64_t>(values[index]) >
                        std::numeric_limits<uintptr_t>::max() ||
                static_cast<std::uint64_t>(values[index + 1]) >
                        std::numeric_limits<uintptr_t>::max()) {
                setMessage("Invalid target range bounds");
                return kInvalidRequest;
            }
            const uintptr_t start = static_cast<uintptr_t>(values[index]);
            const uintptr_t end = static_cast<uintptr_t>(values[index + 1]);
            if (start % target.pageSize != 0 || end % target.pageSize != 0 ||
                (!target.ranges.empty() && start < previousEnd)) {
                setMessage("Target ranges are unaligned or overlap");
                return kInvalidRequest;
            }
            target.ranges.push_back({start, end});
            previousEnd = end;
        }

        std::lock_guard<std::mutex> lock(gMutex);
        target.generation = gTarget.generation + 1U;
        const bool sameRuntime =
                gTarget.pid == target.pid && gTarget.token == target.token;
        gTarget = std::move(target);
        if (!sameRuntime) {
            gState = gEmptyState;
            gHistory.clear();
            gLiveCandidates.clear();
            gNextCandidateId = 1U;
        }
        gLastMessage.clear();
        return kOk;
    } catch (const std::bad_alloc &) {
        setMessage("Target configuration exceeds the engine memory budget");
        return kResourceLimit;
    } catch (...) {
        setMessage("Invalid target configuration");
        return kInvalidRequest;
    }
}

[[nodiscard]] std::uint64_t knownRefineWorkBytes(
        const SearchState &state) noexcept {
    std::uint64_t total = 0U;
    for (const Candidate &candidate : state.candidates) {
        const std::size_t width = widthOf(candidate.type);
        if (width == 0U ||
            total > std::numeric_limits<std::uint64_t>::max() - width) {
            return 0U;
        }
        total += static_cast<std::uint64_t>(width);
    }
    return total;
}

[[nodiscard]] std::uint64_t targetScanWorkBytes(const Target &target) noexcept {
    std::uint64_t total = 0U;
    for (const Range &range : target.ranges) {
        if (range.end < range.start) {
            return 0U;
        }
        const std::uint64_t bytes =
                static_cast<std::uint64_t>(range.end - range.start);
        if (total > std::numeric_limits<std::uint64_t>::max() - bytes) {
            return std::numeric_limits<std::uint64_t>::max();
        }
        total += bytes;
    }
    return total;
}

void compactKnownRefineCandidates(std::vector<Candidate> &candidates) {
    const std::size_t size = candidates.size();
    const std::size_t capacity = candidates.capacity();
    if (capacity <= size) {
        return;
    }
    const std::size_t tolerated =
            size > (std::numeric_limits<std::size_t>::max() -
                    kCandidateCompactionSlack) /
                           2U
                    ? std::numeric_limits<std::size_t>::max()
                    : size * 2U + kCandidateCompactionSlack;
    if (capacity <= tolerated) {
        return;
    }
    std::vector<Candidate> compact(candidates.begin(), candidates.end());
    candidates.swap(compact);
}

[[nodiscard]] bool candidateMatchesRecoveryKey(const Candidate &candidate,
                                               const RecoveryKey &key) noexcept {
    return candidate.type == key.type && candidate.identityValid &&
           candidate.identityHash == key.identityHash;
}

[[nodiscard]] bool shouldAttemptRelocation(const OperationContext &context) {
    const auto &candidates = context.state->candidates;
    if (candidates.empty()) {
        return false;
    }
    const std::size_t requestedSamples =
            std::min(kRelocationProbeSamples, candidates.size());
    std::size_t checked = 0U;
    std::size_t mismatches = 0U;
    for (std::size_t sample = 0U; sample < requestedSamples; ++sample) {
        const std::size_t position = requestedSamples == 1U
                                             ? 0U
                                             : sample * (candidates.size() - 1U) /
                                                       (requestedSamples - 1U);
        const Candidate &candidate = candidates[position];
        if (!candidate.identityValid) {
            continue;
        }
        std::uint64_t liveHash = 0U;
        if (!readIdentity(context.target, candidate.address, candidate.type,
                          liveHash) ||
            liveHash != candidate.identityHash) {
            ++mismatches;
        }
        ++checked;
    }
    // The fingerprint excludes the value bytes themselves. A normal 3 -> 4 gameplay change does
    // not alter it. Therefore even one sampled context mismatch is meaningful enough to prefer the
    // streaming reconciliation path; ambiguity remains fail-closed later rather than guessed here.
    return checked > 0U && mismatches > 0U;
}

[[nodiscard]] std::optional<std::size_t> findUniqueRecoveryPosition(
        const SearchState &state,
        const std::vector<std::uint32_t> &sortedRecoveryIndices,
        const RecoveryKey &key) {
    const auto lessThanKey = [&](std::uint32_t candidateIndex,
                                 const RecoveryKey &wanted) {
        const Candidate &candidate = state.candidates[candidateIndex];
        const jint candidateType = toJint(candidate.type);
        const jint wantedType = toJint(wanted.type);
        return candidateType < wantedType ||
               (candidateType == wantedType &&
                candidate.identityHash < wanted.identityHash);
    };
    const auto first = std::lower_bound(sortedRecoveryIndices.begin(),
                                        sortedRecoveryIndices.end(), key,
                                        lessThanKey);
    if (first == sortedRecoveryIndices.end() ||
        !candidateMatchesRecoveryKey(state.candidates[*first], key)) {
        return std::nullopt;
    }
    const auto second = std::next(first);
    if (second != sortedRecoveryIndices.end() &&
        candidateMatchesRecoveryKey(state.candidates[*second], key)) {
        // The old revision itself contains more than one logical candidate with this fingerprint.
        // It is not safe to guess which one a new address belongs to.
        return std::nullopt;
    }
    return static_cast<std::size_t>(first - sortedRecoveryIndices.begin());
}

[[nodiscard]] jint recoverRelocatedKnownCandidates(
        const OperationContext &context, jint predicate,
        const std::array<const Query *, kTypeSlotCount> &queriesByType,
        std::vector<std::uint32_t> recoveryIndices,
        std::uint64_t completedCandidateWork,
        std::vector<Candidate> &survivors,
        std::size_t &relocatedCount) {
    relocatedCount = 0U;
    if (recoveryIndices.empty()) {
        return kOk;
    }

    const SearchState &state = *context.state;
    std::sort(recoveryIndices.begin(), recoveryIndices.end(),
              [&](std::uint32_t leftIndex, std::uint32_t rightIndex) {
                  const Candidate &left = state.candidates[leftIndex];
                  const Candidate &right = state.candidates[rightIndex];
                  const jint leftType = toJint(left.type);
                  const jint rightType = toJint(right.type);
                  if (leftType != rightType) {
                      return leftType < rightType;
                  }
                  if (left.identityHash != right.identityHash) {
                      return left.identityHash < right.identityHash;
                  }
                  return leftIndex < rightIndex;
              });

    std::array<bool, kTypeSlotCount> wantedType{};
    for (std::uint32_t candidateIndex : recoveryIndices) {
        wantedType[typeIndex(state.candidates[candidateIndex].type)] = true;
    }

    std::vector<uintptr_t> foundAddress(recoveryIndices.size(), 0U);
    std::vector<std::uint64_t> foundBits(recoveryIndices.size(), 0U);
    std::vector<std::uint8_t> foundCount(recoveryIndices.size(), 0U);
    const std::uint64_t heapWork = targetScanWorkBytes(context.target);
    std::uint64_t totalWork = completedCandidateWork;
    if (heapWork == std::numeric_limits<std::uint64_t>::max() ||
        totalWork > std::numeric_limits<std::uint64_t>::max() - heapWork) {
        totalWork = std::numeric_limits<std::uint64_t>::max();
    } else {
        totalWork += heapWork;
    }
    gScanBytesTotal.store(totalWork, std::memory_order_release);

    const auto recordMatch = [&](ValueType type, uintptr_t address,
                                 const std::vector<std::uint8_t> &buffer,
                                 std::size_t offset, std::size_t width,
                                 std::uint64_t bits) {
        const Query *query = queriesByType[typeIndex(type)];
        if (query == nullptr || !matchesKnown(bits, *query, predicate)) {
            return;
        }
        std::uint64_t hash = 0U;
        if (!snapshotIdentity(buffer.data(), buffer.size(), offset, width, hash) &&
            !readIdentity(context.target, address, type, hash)) {
            return;
        }
        const auto position = findUniqueRecoveryPosition(
                state, recoveryIndices, RecoveryKey{type, hash});
        if (!position.has_value()) {
            return;
        }
        const std::size_t index = *position;
        if (foundCount[index] == 0U) {
            foundAddress[index] = address;
            foundBits[index] = bits;
            foundCount[index] = 1U;
        } else if (foundAddress[index] != address) {
            // Multiple new addresses share the same old fingerprint and satisfy the new query.
            // Mark the relocation ambiguous and keep it out of this revision.
            foundCount[index] = 2U;
        }
    };

    std::vector<std::uint8_t> buffer;
    std::uint64_t scannedHeap = 0U;
    for (const Range &range : context.target.ranges) {
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (isCancelled(context)) {
                setMessage("Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const std::size_t remaining =
                    static_cast<std::size_t>(range.end - chunkStart);
            const std::size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            const bool readable =
                    readExact(context.target.pid, chunkStart, buffer.data(), chunkSize);
            if (readable) {
                for (std::size_t offset = 0U; offset < chunkSize; ++offset) {
                    const uintptr_t address = chunkStart + offset;
                    if ((wantedType[typeIndex(ValueType::Int)] ||
                         wantedType[typeIndex(ValueType::Float)]) &&
                        address % 4U == 0U && offset + 4U <= chunkSize) {
                        const std::uint64_t bits =
                                loadBits(buffer.data() + offset, 4U);
                        if (wantedType[typeIndex(ValueType::Int)]) {
                            recordMatch(ValueType::Int, address, buffer, offset, 4U,
                                        bits);
                        }
                        if (wantedType[typeIndex(ValueType::Float)]) {
                            recordMatch(ValueType::Float, address, buffer, offset,
                                        4U, bits);
                        }
                    }
                    if ((wantedType[typeIndex(ValueType::Long)] ||
                         wantedType[typeIndex(ValueType::Double)]) &&
                        address % 8U == 0U && offset + 8U <= chunkSize) {
                        const std::uint64_t bits =
                                loadBits(buffer.data() + offset, 8U);
                        if (wantedType[typeIndex(ValueType::Long)]) {
                            recordMatch(ValueType::Long, address, buffer, offset,
                                        8U, bits);
                        }
                        if (wantedType[typeIndex(ValueType::Double)]) {
                            recordMatch(ValueType::Double, address, buffer, offset,
                                        8U, bits);
                        }
                    }
                    if ((wantedType[typeIndex(ValueType::Short)] ||
                         wantedType[typeIndex(ValueType::Char)]) &&
                        address % 2U == 0U && offset + 2U <= chunkSize) {
                        const std::uint64_t bits =
                                loadBits(buffer.data() + offset, 2U);
                        if (wantedType[typeIndex(ValueType::Short)]) {
                            recordMatch(ValueType::Short, address, buffer, offset,
                                        2U, bits);
                        }
                        if (wantedType[typeIndex(ValueType::Char)]) {
                            recordMatch(ValueType::Char, address, buffer, offset,
                                        2U, bits);
                        }
                    }
                    if (wantedType[typeIndex(ValueType::Byte)]) {
                        recordMatch(ValueType::Byte, address, buffer, offset, 1U,
                                    buffer[offset]);
                    }
                }
            }

            if (scannedHeap > std::numeric_limits<std::uint64_t>::max() -
                                      static_cast<std::uint64_t>(chunkSize)) {
                scannedHeap = std::numeric_limits<std::uint64_t>::max();
            } else {
                scannedHeap += static_cast<std::uint64_t>(chunkSize);
            }
            const std::uint64_t progress =
                    completedCandidateWork >
                                    std::numeric_limits<std::uint64_t>::max() -
                                            scannedHeap
                            ? std::numeric_limits<std::uint64_t>::max()
                            : completedCandidateWork + scannedHeap;
            gScanBytesScanned.store(std::min(progress, totalWork),
                                    std::memory_order_release);
            chunkStart += chunkSize;
        }
    }

    for (std::size_t position = 0U; position < recoveryIndices.size(); ++position) {
        if (foundCount[position] != 1U) {
            continue;
        }
        const Candidate &stored = state.candidates[recoveryIndices[position]];
        const RecoveryKey key{stored.type, stored.identityHash};
        if ((position > 0U && candidateMatchesRecoveryKey(
                                    state.candidates[recoveryIndices[position - 1U]], key)) ||
            (position + 1U < recoveryIndices.size() &&
             candidateMatchesRecoveryKey(
                     state.candidates[recoveryIndices[position + 1U]], key))) {
            continue;
        }
        Candidate recovered = stored;
        recovered.address = foundAddress[position];
        recovered.previousBits = stored.currentBits;
        recovered.currentBits = foundBits[position];
        recovered.state = kStable;
        if (recovered.address != stored.address) {
            if (recovered.relocationCount !=
                std::numeric_limits<std::uint32_t>::max()) {
                ++recovered.relocationCount;
            }
            ++relocatedCount;
        }
        survivors.push_back(recovered);
    }
    return kOk;
}

[[nodiscard]] jint refineKnownAddressSet(JNIEnv *env, jint predicate,
                                         jstring first, jstring second) {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }
    if (context.state == nullptr || context.state->mode != StateMode::Candidates) {
        setMessage("No Known search session is available for Next Scan");
        return kNoSession;
    }
    if (context.state->candidates.size() >
        static_cast<std::size_t>(std::numeric_limits<std::uint32_t>::max())) {
        setMessage("Known Next Scan candidate index space is exhausted");
        return kResourceLimit;
    }

    std::vector<Query> queries;
    if (!buildQueries(context.state->requestedType, predicate,
                      fromJString(env, first), fromJString(env, second), false,
                      queries)) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }

    const std::uint64_t candidateWork = knownRefineWorkBytes(*context.state);
    if (!context.state->candidates.empty() && candidateWork == 0U) {
        setMessage("Known Next Scan work size could not be represented safely");
        return kResourceLimit;
    }
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(candidateWork, std::memory_order_release);

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    next->candidates.reserve(std::min(
            context.state->candidates.size(), kInitialKnownRefineReserve));

    const bool relocationLikely = shouldAttemptRelocation(context);
    std::vector<std::uint32_t> recoveryIndices;
    if (relocationLikely) {
        recoveryIndices.reserve(std::min(context.state->candidates.size(),
                                         kInitialRecoveryIndexReserve));
    }

    CandidateValueReader reader(context.target,
                                context.state->candidates.size());
    std::uint64_t completedWork = 0U;
    std::size_t readableCount = 0U;
    std::size_t unreadableCount = 0U;

    for (std::size_t candidateIndex = 0U;
         candidateIndex < context.state->candidates.size(); ++candidateIndex) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }

        const Candidate &stored = context.state->candidates[candidateIndex];
        const std::size_t width = widthOf(stored.type);
        if (width == 0U) {
            setMessage("Known Next Scan encountered an invalid candidate type");
            return kInvalidRequest;
        }
        const Query *query = queriesByType[typeIndex(stored.type)];

        if (query != nullptr && relocationLikely && stored.identityValid) {
            // Once movement is observed, a readable old address is not proof of identity. Rebuild
            // every fingerprinted row from the streaming pass instead of retaining stale-address
            // false positives. This vector stores only compact indices, not duplicate Candidates.
            recoveryIndices.push_back(
                    static_cast<std::uint32_t>(candidateIndex));
        } else if (query != nullptr) {
            std::uint64_t current = 0U;
            const bool readable = reader.read(stored, current);
            if (readable) {
                ++readableCount;
                if (matchesKnown(current, *query, predicate)) {
                    Candidate updated = stored;
                    updated.previousBits = stored.currentBits;
                    updated.currentBits = current;
                    updated.state = kStable;
                    next->candidates.push_back(updated);
                }
            } else {
                ++unreadableCount;
            }
        }

        completedWork += static_cast<std::uint64_t>(width);
        gScanBytesScanned.store(completedWork, std::memory_order_release);
    }

    std::size_t relocatedCount = 0U;
    if (relocationLikely) {
        const jint recoveryResult = recoverRelocatedKnownCandidates(
                context, predicate, queriesByType, std::move(recoveryIndices),
                completedWork, next->candidates, relocatedCount);
        if (recoveryResult != kOk) {
            return recoveryResult;
        }
    }

    // With no movement evidence, an entirely unreadable raw set indicates stale target ranges or
    // runtime loss rather than a trustworthy zero-result refine, so preserve the prior revision.
    // Once streaming reconciliation ran, zero matches is an ordinary transactional result.
    if (!context.state->candidates.empty() && readableCount == 0U &&
        !relocationLikely) {
        setMessage("The previous search address set is no longer readable; previous results were preserved");
        return kInvalidRequest;
    }

    compactKnownRefineCandidates(next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;

    const std::size_t survivorCount = next->candidates.size();
    const jint result = commitOperation(context, std::move(next), 1U);
    if (result == kOk) {
        const std::uint64_t finalTotal =
                gScanBytesTotal.load(std::memory_order_acquire);
        gScanBytesScanned.store(finalTotal, std::memory_order_release);
        if (relocatedCount > 0U) {
            setMessage(("Next Scan recovered " + std::to_string(relocatedCount) +
                        " uniquely relocated address aliases")
                               .c_str());
        } else if (survivorCount == 0U) {
            setMessage("Next Scan found no matching values");
        } else if (unreadableCount > 0U) {
            setMessage(("Next Scan discarded " + std::to_string(unreadableCount) +
                        " unreadable address aliases")
                               .c_str());
        }
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTargetExpanded(
        JNIEnv *env, jclass, jint pid, jint pageSize, jlong token,
        jlongArray rawRuns) {
    return configureTargetExpanded(env, pid, pageSize, token, rawRuns);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownAddressSet(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        return refineKnownAddressSet(env, predicate, first, second);
    });
}
