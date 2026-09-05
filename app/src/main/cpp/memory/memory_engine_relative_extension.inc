/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_store_relative.h"

namespace {

[[nodiscard]] std::uint64_t canonicalRelativeMagnitudeBits(
        const Query &query, bool second) noexcept {
    if (query.floating) {
        const double value = second ? query.floatingSecond : query.floatingFirst;
        if (query.type == ValueType::Float) {
            return static_cast<std::uint64_t>(
                    std::bit_cast<std::uint32_t>(static_cast<float>(value)));
        }
        if (query.type == ValueType::Double) {
            return std::bit_cast<std::uint64_t>(value);
        }
        return 0U;
    }
    return second ? query.deltaSecond : query.deltaFirst;
}

[[nodiscard]] std::optional<jlmem::v2::RelativeQueryPlan>
relativePlanFromParsedQuery(const Query &query, jint predicate) noexcept {
    const auto relativePredicate =
            jlmem::v2::relativePredicateFromStableValue(predicate);
    if (!relativePredicate.has_value()) {
        return std::nullopt;
    }
    const bool needsMagnitude =
            jlmem::v2::relativePredicateNeedsMagnitude(*relativePredicate);
    const bool needsSecond =
            jlmem::v2::relativePredicateNeedsSecondMagnitude(*relativePredicate);
    const std::uint64_t firstBits =
            needsMagnitude ? canonicalRelativeMagnitudeBits(query, false) : 0U;
    const std::uint64_t secondBits =
            needsSecond ? canonicalRelativeMagnitudeBits(query, true) : 0U;
    return jlmem::v2::relativeQueryPlanFromStableValues(
            toJint(query.type), predicate, firstBits, secondBits);
}

[[nodiscard]] bool buildCanonicalRelativePlans(
        jint requestedType, jint predicate, const std::string &first,
        const std::string &second, std::vector<Query> &queries,
        std::vector<jlmem::v2::RelativeQueryPlan> &plans) {
    if (!buildQueries(requestedType, predicate, first, second, true, queries)) {
        return false;
    }
    plans.reserve(queries.size());
    for (const Query &query : queries) {
        const auto plan = relativePlanFromParsedQuery(query, predicate);
        if (!plan.has_value()) {
            return false;
        }
        plans.push_back(*plan);
    }
    return !plans.empty();
}

struct AuthoritativeRelativeBuildContext {
    const OperationContext *operation = nullptr;
    std::shared_ptr<SearchState> next;
    bool candidateLimitReached = false;
};

[[nodiscard]] bool materializeAuthoritativeRelativeMatch(
        void *opaque, const jlmem::v2::RelativeScanMatchView &match) {
    auto *build = static_cast<AuthoritativeRelativeBuildContext *>(opaque);
    if (build == nullptr || build->operation == nullptr || build->next == nullptr ||
        match.address == 0U || match.baselineBytes == nullptr || match.width == 0U ||
        match.baselineOffset > match.baselineSize ||
        match.width > match.baselineSize - match.baselineOffset) {
        return false;
    }
    const jint rawType =
            jlmem::v2::stableValueTypeFromResultPlane(match.plane);
    const auto type = valueTypeFromJint(rawType);
    if (!type.has_value() || widthOf(*type) != match.width) {
        return false;
    }
    if (build->next->candidates.size() >= kCandidateLimit) {
        build->candidateLimitReached = true;
        return false;
    }
    Candidate candidate = makeCandidate(
            build->operation->nextId + build->next->candidates.size(),
            match.address, *type, match.initialBits, match.currentBits);
    candidate.identityValid = snapshotIdentity(
            match.baselineBytes, match.baselineSize, match.baselineOffset,
            match.width, candidate.identityHash);
    build->next->candidates.push_back(candidate);
    return true;
}

void advanceAuthoritativeRelativeProgress(void *, std::size_t bytes) {
    advanceScanProgress(bytes);
}

[[nodiscard]] jint commitRelativeResultStoreRevision(
        const OperationContext &context, std::shared_ptr<SearchState> next,
        jlmem::v2::ResultStore store, jint historyMode) {
    if (next == nullptr || store.typedCount() != next->candidates.size() ||
        store.retainedBytes() > kStagedKnownStoreByteLimit) {
        setMessage("Relative ResultStore and Candidate compatibility mirror diverged");
        return kResourceLimit;
    }

    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    normalizeCandidateResults(*next);
    if (store.uniqueAddressCount() != next->logicalCount) {
        setMessage("Relative ResultStore unique-address accounting diverged");
        return kResourceLimit;
    }

    auto publishedStore =
            std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    if (next->requestedType == kTypeAuto) {
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
        std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
        if (!verifyAndIndexAutoStore(
                    *next, *publishedStore, checkpoints, candidateOffsets)) {
            setMessage("Relative Auto ResultStore failed compatibility verification");
            return kResourceLimit;
        }
        return commitAuthoritativeAutoRevision(
                context, std::move(next), std::move(publishedStore),
                std::move(checkpoints), std::move(candidateOffsets), historyMode);
    }

    const auto plane = jlmem::v2::resultPlaneFromStableValueType(
            next->requestedType);
    if (!plane.has_value()) {
        setMessage("Relative ResultStore has an invalid explicit type");
        return kInvalidRequest;
    }
    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    if (!verifyAndIndexStagedKnownStore(
                *next, *publishedStore, *plane, checkpoints)) {
        setMessage("Relative explicit ResultStore failed compatibility verification");
        return kResourceLimit;
    }
    return commitAuthoritativeKnownRevision(
            context, std::move(next), std::move(publishedStore),
            std::move(checkpoints), *plane, historyMode);
}

[[nodiscard]] jint scanRelativeUnknownV2Authoritative(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Unknown) {
        return kNoSession;
    }
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        setMessage("Invalid comparison target");
        return kInvalidRequest;
    }
    if (context.nextId >
        std::numeric_limits<std::uint64_t>::max() - kCandidateLimit) {
        setMessage("Candidate identifier space is exhausted for this runtime");
        return kResourceLimit;
    }

    std::vector<Query> queries;
    std::vector<jlmem::v2::RelativeQueryPlan> plans;
    if (!buildCanonicalRelativePlans(
                context.state->requestedType, predicate, first, second,
                queries, plans)) {
        setMessage("Invalid relative value, type, or predicate");
        return kInvalidRequest;
    }

    std::vector<jlmem::v2::RelativeBaselineRange> ranges;
    ranges.reserve(context.state->snapshots.size());
    for (const SnapshotRun &snapshot : context.state->snapshots) {
        ranges.push_back({snapshot.start, snapshot.bytes});
    }
    if (ranges.empty()) {
        setMessage("Unknown baseline contains no captured ranges");
        return kNoSession;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    AuthoritativeRelativeBuildContext build{&context, next, false};
    const jlmem::v2::RelativeScanObserver observer{
            &build,
            materializeAuthoritativeRelativeMatch,
            advanceAuthoritativeRelativeProgress,
    };

    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    beginScanProgress(context.target);
    const auto read = [&](std::uintptr_t address, void *buffer,
                          std::size_t size) {
        return readExact(context.target.pid, address, buffer, size);
    };
    const auto cancelled = [&] { return isCancelled(context); };
    const bool scanned = context.state->requestedType == kTypeAuto
                                 ? jlmem::v2::scanRelativeAuto(
                                           ranges, plans, read, cancelled,
                                           store, stats, error, &observer)
                                 : plans.size() == 1U &&
                                           jlmem::v2::scanRelativeExplicit(
                                                   ranges, plans.front(), read,
                                                   cancelled, store, stats,
                                                   error, &observer);
    if (!scanned) {
        if (build.candidateLimitReached) {
            setMessage("Complete relative ResultStore exceeds the candidate compatibility limit");
            return kResourceLimit;
        }
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        if (error.find("Target range changed") != std::string::npos) {
            setMessage("A target range changed while the Unknown baseline was being refined");
            return kTargetLost;
        }
        setMessage(error.empty()
                           ? "Relative ResultStore scan could not be completed safely"
                           : error.c_str());
        return kResourceLimit;
    }
    if (stats.typedMatches != next->candidates.size() ||
        store.typedCount() != next->candidates.size() ||
        store.retainedBytes() > kStagedKnownStoreByteLimit) {
        setMessage("Relative ResultStore membership diverged before publication");
        return kResourceLimit;
    }

    // Preserve the current tracked-candidate compatibility contract. Most fingerprints were
    // captured from baseline bytes; only range/chunk edge candidates need a bounded context read.
    fillMissingIdentities(context.target, next->candidates);

    OperationContext committed = context;
    committed.nextId += next->candidates.size();
    return commitRelativeResultStoreRevision(
            committed, std::move(next), std::move(store), 1);
}

[[nodiscard]] bool sourceRelativeMembershipContains(
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

[[nodiscard]] jint fallbackRelativeCandidateRefine(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    const jint result = refineCandidates(
            context, predicate, first, second, true, compareTarget);
    if (result == kOk) {
        if (context.state->requestedType == kTypeAuto) {
            (void) stageCurrentAutoResultStore();
        } else {
            (void) stageCurrentKnownResultStore();
        }
    }
    return result;
}

[[nodiscard]] jint refineRelativeCandidatesV2Authoritative(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Candidates) {
        return kNoSession;
    }
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        setMessage("Invalid comparison target");
        return kInvalidRequest;
    }

    std::vector<Query> queries;
    std::vector<jlmem::v2::RelativeQueryPlan> plans;
    if (!buildCanonicalRelativePlans(
                context.state->requestedType, predicate, first, second,
                queries, plans)) {
        setMessage("Invalid relative value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const jlmem::v2::RelativeQueryPlan *, kTypeSlotCount> plansByType{};
    for (const jlmem::v2::RelativeQueryPlan &plan : plans) {
        const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(plan.plane);
        const auto type = valueTypeFromJint(rawType);
        if (!type.has_value()) {
            setMessage("Relative query plan has an invalid primitive type");
            return kInvalidRequest;
        }
        plansByType[typeIndex(*type)] = &plan;
    }

    std::shared_ptr<const jlmem::v2::ResultStore> sourceStore;
    if (context.state->requestedType == kTypeAuto) {
        StagedAutoResultStore staged;
        if (!snapshotCurrentAutoResultStore(context, staged)) {
            return fallbackRelativeCandidateRefine(
                    context, predicate, compareTarget, first, second);
        }
        sourceStore = staged.store;
    } else {
        StagedKnownResultStore staged;
        if (!snapshotCurrentKnownResultStore(context, staged)) {
            return fallbackRelativeCandidateRefine(
                    context, predicate, compareTarget, first, second);
        }
        sourceStore = staged.store;
    }
    if (sourceStore == nullptr) {
        return fallbackRelativeCandidateRefine(
                context, predicate, compareTarget, first, second);
    }

    // A read-only recovery may have rebound a visible CandidateId in the live overlay while the
    // immutable search membership still points at its old address. COW cannot move a bitmap slot;
    // delegate exactly this revision to the proven Candidate path, then re-import the committed
    // addresses into ResultStore. Normal stable-address relative scans never enter this path.
    for (const Candidate &stored : context.state->candidates) {
        const Candidate &live = liveCandidate(stored, context.liveCandidates);
        if (live.address != stored.address ||
            live.relocationCount != stored.relocationCount) {
            return fallbackRelativeCandidateRefine(
                    context, predicate, compareTarget, first, second);
        }
    }

    jlmem::v2::ResultStore refined = *sourceStore;
    const auto headers = sourceStore->headers();
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    next->candidates.reserve(std::min(
            context.state->candidates.size(), kCandidateLimit));

    const std::uint64_t totalWork = knownRefineWorkBytes(*context.state);
    if (!context.state->candidates.empty() && totalWork == 0U) {
        setMessage("Relative ResultStore work size could not be represented safely");
        return kResourceLimit;
    }
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(totalWork, std::memory_order_release);
    std::uint64_t completedWork = 0U;
    std::size_t blockIndex = 0U;
    CandidateValueReader reader(context.target, context.state->candidates.size());

    for (const Candidate &stored : context.state->candidates) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const auto plane = candidatePlane(stored);
        const jlmem::v2::RelativeQueryPlan *plan =
                plansByType[typeIndex(stored.type)];
        if (!plane.has_value()) {
            setMessage("Relative ResultStore contains an invalid Candidate type");
            return kResourceLimit;
        }
        const std::uintptr_t blockBase =
                stored.address &
                ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
                  std::uintptr_t{1U});
        while (blockIndex < headers.size() &&
               headers[blockIndex].baseAddress < blockBase) {
            ++blockIndex;
        }
        std::size_t slot = 0U;
        if (!sourceRelativeMembershipContains(
                    *sourceStore, blockIndex, stored, *plane, slot)) {
            setMessage("Relative ResultStore and Candidate mirror diverged before Next Scan");
            return kResourceLimit;
        }

        std::uint64_t currentBits = 0U;
        const bool readable = plan != nullptr && reader.read(stored, currentBits);
        const std::uint64_t referenceBits =
                compareTarget == kCompareInitial
                        ? stored.initialBits
                        : stored.currentBits;
        const bool survives = readable &&
                jlmem::v2::matchesRelativePlanBits(
                        *plan, currentBits, referenceBits);
        if (survives) {
            Candidate updated = stored;
            updated.previousBits = stored.currentBits;
            updated.currentBits = currentBits;
            updated.state = kStable;
            next->candidates.push_back(updated);
        } else if (!refined.clearSlot(blockIndex, *plane, slot)) {
            setMessage("Relative ResultStore could not clear a failed membership bit");
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

    // Preserve legacy tracked identity freshness for small relative result sets. This is temporary
    // compatibility metadata; membership has already been decided exclusively by ResultStore.
    captureIdentities(context.target, next->candidates);
    compactKnownRefineCandidates(next->candidates);
    const std::size_t survivorCount = next->candidates.size();
    const jint result = commitRelativeResultStoreRevision(
            context, std::move(next), std::move(refined), 1);
    if (result == kOk) {
        gScanBytesScanned.store(totalWork, std::memory_order_release);
        if (survivorCount == 0U) {
            setMessage("Next Scan found no matching values");
        }
    }
    return result;
}

[[nodiscard]] jint refineRelativeV2Authoritative(
        jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }
    if (context.state == nullptr || context.state->mode == StateMode::Empty) {
        setMessage("No search session to refine");
        return kNoSession;
    }
    if (context.state->mode == StateMode::Unknown) {
        return scanRelativeUnknownV2Authoritative(
                context, predicate, compareTarget, first, second);
    }
    return refineRelativeCandidatesV2Authoritative(
            context, predicate, compareTarget, first, second);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelativeV2Authoritative(
        JNIEnv *env, jclass, jint predicate, jint compareTarget,
        jstring first, jstring second) {
    return guardedOperation([&] {
        return refineRelativeV2Authoritative(
                predicate, compareTarget, fromJString(env, first),
                fromJString(env, second));
    });
}
