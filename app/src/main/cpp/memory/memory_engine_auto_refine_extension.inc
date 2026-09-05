/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

namespace {

[[nodiscard]] bool snapshotCurrentAutoResultStore(
        const OperationContext &context, StagedAutoResultStore &staged) {
    {
        std::lock_guard<std::mutex> lock(gStagedAutoMutex);
        staged = gStagedAuto;
    }
    return autoStageMatchesState(staged, context.state);
}

[[nodiscard]] bool sourceAutoMembershipContains(
        const jlmem::v2::ResultStore &store, std::size_t blockIndex,
        const Candidate &candidate, jlmem::v2::ResultPlane plane,
        std::size_t &slot) noexcept {
    if (blockIndex >= store.blockCount()) {
        return false;
    }
    const auto headers = store.headers();
    const std::uintptr_t blockBase =
            candidate.address &
            ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
              std::uintptr_t{1U});
    if (headers[blockIndex].baseAddress != blockBase) {
        return false;
    }
    const std::size_t alignment = jlmem::v2::planeAlignment(plane);
    const std::size_t byteOffset =
            static_cast<std::size_t>(candidate.address - blockBase);
    if (alignment == 0U || byteOffset % alignment != 0U) {
        return false;
    }
    slot = byteOffset / alignment;
    const auto words = store.planeWords(blockIndex, plane);
    return slot / 64U < words.size() &&
           (words[slot / 64U] &
            (std::uint64_t{1U} << (slot % 64U))) != 0U;
}

[[nodiscard]] jint refineAutoV2Authoritative(
        JNIEnv *env, jint predicate, jstring first, jstring second,
        bool allowRelocationReconcile) {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }

    const auto fallback = [&]() -> jint {
        const jint result = refineKnownAddressSet(
                env, predicate, first, second, allowRelocationReconcile);
        if (result == kOk) {
            (void) stageCurrentAutoResultStore();
        }
        return result;
    };

    if (context.state == nullptr || context.state->mode != StateMode::Candidates ||
        context.state->requestedType != kTypeAuto) {
        return kNoSession;
    }
    StagedAutoResultStore staged;
    if (!snapshotCurrentAutoResultStore(context, staged)) {
        return fallback();
    }
    if (allowRelocationReconcile && shouldAttemptRelocation(context, true)) {
        return fallback();
    }

    const std::string firstText = fromJString(env, first);
    const std::string secondText = fromJString(env, second);
    std::vector<Query> queries;
    if (!buildQueries(kTypeAuto, predicate, firstText, secondText, false, queries)) {
        setMessage("Invalid Auto value or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = kTypeAuto;
    next->watches = context.state->watches;
    next->candidates.reserve(std::min(context.state->candidates.size(), kCandidateLimit));

    jlmem::v2::ResultStore refined = *staged.store;
    const auto headers = staged.store->headers();
    CandidateValueReader reader(context.target, context.state->candidates.size());
    std::size_t blockIndex = 0U;
    const std::uint64_t totalWork = knownRefineWorkBytes(*context.state);
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(totalWork, std::memory_order_release);
    std::uint64_t completedWork = 0U;

    for (const Candidate &stored : context.state->candidates) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const auto plane = candidatePlane(stored);
        if (!plane.has_value()) {
            setMessage("Auto ResultStore contains an invalid Candidate type");
            return kResourceLimit;
        }
        const std::uintptr_t blockBase =
                stored.address &
                ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
                  std::uintptr_t{1U});
        while (blockIndex < headers.size() && headers[blockIndex].baseAddress < blockBase) {
            ++blockIndex;
        }
        std::size_t slot = 0U;
        if (!sourceAutoMembershipContains(
                    *staged.store, blockIndex, stored, *plane, slot)) {
            setMessage("Auto ResultStore and Candidate mirror diverged before Next Scan");
            return kResourceLimit;
        }

        const Query *query = queriesByType[typeIndex(stored.type)];
        std::uint64_t currentBits = stored.currentBits;
        bool survives = false;
        if (query != nullptr) {
            if (!reader.read(stored, currentBits)) {
                setMessage("A ResultStore address became unreadable; previous results were preserved");
                return kInvalidRequest;
            }
            survives = matchesKnown(currentBits, *query, predicate);
        }

        if (survives) {
            Candidate updated = stored;
            updated.previousBits = stored.currentBits;
            updated.currentBits = currentBits;
            updated.state = kStable;
            next->candidates.push_back(updated);
        } else if (!refined.clearSlot(blockIndex, *plane, slot)) {
            setMessage("Auto ResultStore could not clear a failed membership bit");
            return kResourceLimit;
        }

        const std::size_t width = widthOf(stored.type);
        if (completedWork <=
            std::numeric_limits<std::uint64_t>::max() -
                    static_cast<std::uint64_t>(width)) {
            completedWork += static_cast<std::uint64_t>(width);
        } else {
            completedWork = std::numeric_limits<std::uint64_t>::max();
        }
        gScanBytesScanned.store(
                std::min(completedWork, totalWork), std::memory_order_release);
    }

    compactKnownRefineCandidates(next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    normalizeCandidateResults(*next);
    if (refined.typedCount() != next->candidates.size() ||
        refined.uniqueAddressCount() != next->logicalCount ||
        refined.retainedBytes() > kStagedKnownStoreByteLimit) {
        setMessage("Refined Auto ResultStore count diverged from Candidate compatibility mirror");
        return kResourceLimit;
    }

    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
    if (!verifyAndIndexAutoStore(*next, refined, checkpoints, candidateOffsets)) {
        setMessage("Refined Auto ResultStore failed compatibility verification");
        return kResourceLimit;
    }
    auto publishedStore =
            std::make_shared<jlmem::v2::ResultStore>(std::move(refined));
    const std::size_t survivorCount = next->candidates.size();
    const jint result = commitAuthoritativeAutoRevision(
            context, std::move(next), std::move(publishedStore),
            std::move(checkpoints), std::move(candidateOffsets), 1);
    if (result == kOk) {
        gScanBytesScanned.store(totalWork, std::memory_order_release);
        if (survivorCount == 0U) {
            setMessage("Next Scan found no matching values");
        }
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownAutoV2Authoritative(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second,
        jboolean allowRelocationReconcile) {
    return guardedOperation([&] {
        return refineAutoV2Authoritative(
                env, predicate, first, second,
                allowRelocationReconcile == JNI_TRUE);
    });
}
