/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// This file is intentionally included after memory_engine_compilation_unit.cpp. Reopening the
// translation unit's anonymous namespace lets the migration reuse the established parser/state
// model without adding a private exported ABI between legacy and v2 code.
namespace {

constexpr std::size_t kAutoCursorCheckpointStride = 256U;

struct StagedAutoResultStore {
    std::uint64_t revision = 0U;
    std::weak_ptr<const SearchState> legacyState;
    std::shared_ptr<const jlmem::v2::ResultStore> store;
    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    // Candidate-vector position corresponding to each ResultCursor checkpoint. Auto stores one
    // Candidate per typed alias but ResultCursor pages are address-unique, so this avoids rescanning
    // all prior aliases for a deep page request.
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
};

std::mutex gStagedAutoMutex;
StagedAutoResultStore gStagedAuto;

[[nodiscard]] std::uint64_t nextAutoRevision(std::uint64_t current) noexcept {
    return current == std::numeric_limits<std::uint64_t>::max() ? 1U : current + 1U;
}

void clearStagedAutoResultStore() noexcept {
    std::lock_guard<std::mutex> lock(gStagedAutoMutex);
    const std::uint64_t revision = nextAutoRevision(gStagedAuto.revision);
    gStagedAuto = {};
    gStagedAuto.revision = revision;
}

void clearStagedAutoRevision(std::uint64_t revision) noexcept {
    std::lock_guard<std::mutex> lock(gStagedAutoMutex);
    if (gStagedAuto.revision != revision) {
        return;
    }
    const std::uint64_t next = nextAutoRevision(revision);
    gStagedAuto = {};
    gStagedAuto.revision = next;
}

[[nodiscard]] std::optional<jlmem::v2::ResultPlane> candidatePlane(
        const Candidate &candidate) noexcept {
    return jlmem::v2::resultPlaneFromStableValueType(toJint(candidate.type));
}

[[nodiscard]] bool autoStageMatchesState(
        const StagedAutoResultStore &staged,
        const std::shared_ptr<const SearchState> &state) {
    if (state == nullptr || staged.store == nullptr || staged.checkpoints == nullptr ||
        staged.candidateOffsets == nullptr || state->mode != StateMode::Candidates ||
        state->candidateOrderDirty || state->requestedType != kTypeAuto ||
        staged.store->typedCount() != state->candidates.size() ||
        staged.store->uniqueAddressCount() != state->logicalCount ||
        staged.checkpoints->size() != staged.candidateOffsets->size()) {
        return false;
    }
    return staged.legacyState.lock() == state;
}

[[nodiscard]] bool verifyAndIndexAutoStore(
        const SearchState &state, const jlmem::v2::ResultStore &store,
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> &publishedCheckpoints,
        std::shared_ptr<const std::vector<std::size_t>> &publishedCandidateOffsets) {
    if (state.mode != StateMode::Candidates || state.requestedType != kTypeAuto ||
        state.candidateOrderDirty || store.typedCount() != state.candidates.size() ||
        store.uniqueAddressCount() != state.logicalCount) {
        return false;
    }

    auto checkpoints = std::make_shared<std::vector<jlmem::v2::ResultCursor>>();
    auto candidateOffsets = std::make_shared<std::vector<std::size_t>>();
    const std::size_t checkpointCount =
            state.logicalCount == 0U
                    ? 0U
                    : static_cast<std::size_t>(
                              (state.logicalCount - 1U) / kAutoCursorCheckpointStride + 1U);
    checkpoints->reserve(checkpointCount);
    candidateOffsets->reserve(checkpointCount);

    jlmem::v2::ResultCursor cursor;
    std::uint64_t uniqueIndex = 0U;
    std::size_t candidateIndex = 0U;
    while (uniqueIndex < state.logicalCount) {
        checkpoints->push_back(cursor);
        candidateOffsets->push_back(candidateIndex);
        const std::size_t limit = static_cast<std::size_t>(std::min<std::uint64_t>(
                kAutoCursorCheckpointStride, state.logicalCount - uniqueIndex));
        jlmem::v2::ResultAddressPage page;
        if (!jlmem::v2::readAddressPage(store, cursor, limit, page) ||
            page.rows.size() != limit) {
            return false;
        }
        for (const jlmem::v2::ResultAddressRow &row : page.rows) {
            if (candidateIndex >= state.candidates.size()) {
                return false;
            }
            const std::uintptr_t address = state.candidates[candidateIndex].address;
            if (address == 0U || row.address != address) {
                return false;
            }
            std::uint8_t aliasMask = 0U;
            do {
                const Candidate &candidate = state.candidates[candidateIndex];
                const auto plane = candidatePlane(candidate);
                if (!plane.has_value() || candidate.address != address) {
                    return false;
                }
                const std::uint8_t bit = jlmem::v2::resultPlaneBit(*plane);
                if ((aliasMask & bit) != 0U) {
                    return false;
                }
                aliasMask = static_cast<std::uint8_t>(aliasMask | bit);
                ++candidateIndex;
            } while (candidateIndex < state.candidates.size() &&
                     state.candidates[candidateIndex].address == address);
            if (aliasMask != row.aliasMask) {
                return false;
            }
        }
        cursor = page.next;
        uniqueIndex += page.rows.size();
    }

    jlmem::v2::ResultAddressPage endPage;
    if (candidateIndex != state.candidates.size() ||
        !jlmem::v2::readAddressPage(store, cursor, 1U, endPage) ||
        !endPage.rows.empty() || endPage.next.blockIndex != store.blockCount() ||
        endPage.next.nextByteOffset != 0U || checkpoints->size() != checkpointCount ||
        candidateOffsets->size() != checkpointCount) {
        return false;
    }
    publishedCheckpoints = std::move(checkpoints);
    publishedCandidateOffsets = std::move(candidateOffsets);
    return true;
}

[[nodiscard]] bool buildAutoStoreFromCandidates(
        const std::shared_ptr<const SearchState> &state,
        std::shared_ptr<const jlmem::v2::ResultStore> &publishedStore,
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> &publishedCheckpoints,
        std::shared_ptr<const std::vector<std::size_t>> &publishedCandidateOffsets) {
    if (state == nullptr || state->mode != StateMode::Candidates ||
        state->candidateOrderDirty || state->requestedType != kTypeAuto ||
        state->candidates.size() > kCandidateLimit) {
        return false;
    }

    jlmem::v2::ResultStore store;
    jlmem::v2::ResultBlockScratch scratch;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    const auto flush = [&]() -> bool {
        if (!scratchActive) {
            return true;
        }
        if (scratch.empty() || !store.appendNonEmptyBlock(scratchBase, scratch)) {
            return false;
        }
        scratch.reset();
        scratchBase = 0U;
        scratchActive = false;
        return true;
    };

    std::uintptr_t previousAddress = 0U;
    int previousPriority = -1;
    bool havePrevious = false;
    for (const Candidate &candidate : state->candidates) {
        const auto plane = candidatePlane(candidate);
        if (!plane.has_value() || candidate.address == 0U) {
            return false;
        }
        const std::size_t alignment = jlmem::v2::planeAlignment(*plane);
        if (alignment == 0U || candidate.address % alignment != 0U) {
            return false;
        }
        const int priority = displayTypePriority(candidate.type);
        if (havePrevious &&
            (candidate.address < previousAddress ||
             (candidate.address == previousAddress && priority <= previousPriority))) {
            return false;
        }
        if (!havePrevious || candidate.address != previousAddress) {
            previousPriority = -1;
        }
        havePrevious = true;
        previousAddress = candidate.address;
        previousPriority = priority;

        const std::uintptr_t blockBase =
                candidate.address &
                ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
                  std::uintptr_t{1U});
        if (!scratchActive || blockBase != scratchBase) {
            if (!flush()) {
                return false;
            }
            scratchBase = blockBase;
            scratchActive = true;
        }
        const std::size_t byteOffset =
                static_cast<std::size_t>(candidate.address - blockBase);
        if (!scratch.set(*plane, byteOffset / alignment)) {
            return false;
        }
    }
    if (scratchActive && !flush()) {
        return false;
    }
    if (store.typedCount() != state->candidates.size() ||
        store.uniqueAddressCount() != state->logicalCount ||
        store.retainedBytes() > kStagedKnownStoreByteLimit ||
        !verifyAndIndexAutoStore(
                *state, store, publishedCheckpoints, publishedCandidateOffsets)) {
        return false;
    }
    publishedStore = std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    return true;
}

[[nodiscard]] bool stageCurrentAutoResultStore() {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    if (state == nullptr || state->requestedType != kTypeAuto) {
        return false;
    }

    std::shared_ptr<const jlmem::v2::ResultStore> store;
    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
    if (!buildAutoStoreFromCandidates(state, store, checkpoints, candidateOffsets)) {
        return false;
    }

    std::lock_guard<std::mutex> stateLock(gMutex);
    if (gState != state) {
        return false;
    }
    std::lock_guard<std::mutex> stagedLock(gStagedAutoMutex);
    gStagedAuto.revision = nextAutoRevision(gStagedAuto.revision);
    gStagedAuto.legacyState = state;
    gStagedAuto.store = std::move(store);
    gStagedAuto.checkpoints = std::move(checkpoints);
    gStagedAuto.candidateOffsets = std::move(candidateOffsets);
    return true;
}

[[nodiscard]] bool hasCurrentAutoResultStore() {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    StagedAutoResultStore staged;
    {
        std::lock_guard<std::mutex> lock(gStagedAutoMutex);
        staged = gStagedAuto;
    }
    return autoStageMatchesState(staged, state);
}

[[nodiscard]] bool autoCursorForOffset(
        const StagedAutoResultStore &staged, const SearchState &state,
        std::size_t offset, jlmem::v2::ResultCursor &cursor,
        std::size_t &candidateIndex) {
    if (staged.store == nullptr || staged.checkpoints == nullptr ||
        staged.candidateOffsets == nullptr || offset >= state.logicalCount) {
        return false;
    }
    const std::size_t checkpointIndex = offset / kAutoCursorCheckpointStride;
    if (checkpointIndex >= staged.checkpoints->size() ||
        checkpointIndex >= staged.candidateOffsets->size()) {
        return false;
    }
    cursor = (*staged.checkpoints)[checkpointIndex];
    candidateIndex = (*staged.candidateOffsets)[checkpointIndex];
    std::size_t remaining = offset - checkpointIndex * kAutoCursorCheckpointStride;
    if (remaining == 0U) {
        return true;
    }
    jlmem::v2::ResultAddressPage skipped;
    if (!jlmem::v2::readAddressPage(*staged.store, cursor, remaining, skipped) ||
        skipped.rows.size() != remaining) {
        return false;
    }
    for (const jlmem::v2::ResultAddressRow &row : skipped.rows) {
        if (candidateIndex >= state.candidates.size() ||
            state.candidates[candidateIndex].address != row.address) {
            return false;
        }
        const std::uintptr_t address = row.address;
        do {
            ++candidateIndex;
        } while (candidateIndex < state.candidates.size() &&
                 state.candidates[candidateIndex].address == address);
    }
    cursor = skipped.next;
    return true;
}

[[nodiscard]] jlongArray materializeAutoCursorPage(
        JNIEnv *env, const jlmem::v2::ResultAddressPage &page,
        const SearchState &state, std::size_t candidateIndex,
        const std::unordered_map<uint64_t, Candidate> &liveCandidates) {
    std::size_t typedCount = 0U;
    std::size_t probeIndex = candidateIndex;
    for (const jlmem::v2::ResultAddressRow &row : page.rows) {
        if (probeIndex >= state.candidates.size() ||
            state.candidates[probeIndex].address != row.address) {
            return nullptr;
        }
        const std::uintptr_t address = row.address;
        std::uint8_t aliasMask = 0U;
        do {
            const Candidate &candidate = state.candidates[probeIndex];
            const auto plane = candidatePlane(candidate);
            if (!plane.has_value()) {
                return nullptr;
            }
            const std::uint8_t bit = jlmem::v2::resultPlaneBit(*plane);
            if ((aliasMask & bit) != 0U) {
                return nullptr;
            }
            aliasMask = static_cast<std::uint8_t>(aliasMask | bit);
            ++typedCount;
            ++probeIndex;
        } while (probeIndex < state.candidates.size() &&
                 state.candidates[probeIndex].address == address);
        if (aliasMask != row.aliasMask) {
            return nullptr;
        }
    }
    if (typedCount >
        (std::numeric_limits<std::size_t>::max() - 1U) / kResultStride) {
        return nullptr;
    }

    std::vector<jlong> output(1U + typedCount * kResultStride);
    output[0] = static_cast<jlong>(typedCount);
    std::size_t outputIndex = 0U;
    for (const jlmem::v2::ResultAddressRow &row : page.rows) {
        const std::uintptr_t sourceAddress = row.address;
        do {
            const Candidate &stored = state.candidates[candidateIndex++];
            const Candidate &candidate = liveCandidate(stored, liveCandidates);
            if (candidate.id != stored.id || candidate.type != stored.type ||
                candidate.address == 0U) {
                return nullptr;
            }
            const std::size_t base = 1U + outputIndex * kResultStride;
            output[base] = static_cast<jlong>(candidate.id);
            output[base + 1U] = static_cast<jlong>(candidate.address);
            output[base + 2U] = static_cast<jlong>(sourceAddress);
            output[base + 3U] = toJint(candidate.type);
            output[base + 4U] = candidate.state;
            output[base + 5U] = candidate.relocationCount;
            output[base + 6U] = static_cast<jlong>(candidate.initialBits);
            output[base + 7U] = static_cast<jlong>(candidate.previousBits);
            output[base + 8U] = static_cast<jlong>(candidate.currentBits);
            ++outputIndex;
        } while (candidateIndex < state.candidates.size() &&
                 state.candidates[candidateIndex].address == sourceAddress);
    }
    if (outputIndex != typedCount || output.size() >
            static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

[[nodiscard]] jlongArray stagedAutoResultPage(JNIEnv *env, jint offset, jint limit) {
    if (offset < 0 || limit <= 0 ||
        limit > static_cast<jint>(jlmem::v2::kResultCursorPageLimit)) {
        return nullptr;
    }
    StagedAutoResultStore staged;
    {
        std::lock_guard<std::mutex> lock(gStagedAutoMutex);
        staged = gStagedAuto;
    }
    if (staged.store == nullptr || staged.checkpoints == nullptr ||
        staged.candidateOffsets == nullptr) {
        return nullptr;
    }
    const auto state = staged.legacyState.lock();
    if (!autoStageMatchesState(staged, state)) {
        clearStagedAutoRevision(staged.revision);
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState != state) {
            clearStagedAutoRevision(staged.revision);
            return nullptr;
        }
    }

    const std::size_t start = std::min<std::size_t>(
            static_cast<std::size_t>(offset), static_cast<std::size_t>(state->logicalCount));
    const std::size_t expectedCount = std::min<std::size_t>(
            static_cast<std::size_t>(limit),
            static_cast<std::size_t>(state->logicalCount) - start);
    if (expectedCount == 0U) {
        std::vector<jlong> empty{0L};
        jlongArray result = env->NewLongArray(1);
        if (result != nullptr) {
            env->SetLongArrayRegion(result, 0, 1, empty.data());
        }
        return result;
    }

    jlmem::v2::ResultCursor cursor;
    std::size_t candidateIndex = 0U;
    jlmem::v2::ResultAddressPage page;
    if (!autoCursorForOffset(staged, *state, start, cursor, candidateIndex) ||
        !jlmem::v2::readAddressPage(*staged.store, cursor, expectedCount, page) ||
        page.rows.size() != expectedCount) {
        clearStagedAutoRevision(staged.revision);
        return nullptr;
    }

    std::size_t typedPageCount = 0U;
    std::size_t probeIndex = candidateIndex;
    for (const jlmem::v2::ResultAddressRow &row : page.rows) {
        if (probeIndex >= state->candidates.size() ||
            state->candidates[probeIndex].address != row.address) {
            clearStagedAutoRevision(staged.revision);
            return nullptr;
        }
        const std::uintptr_t address = row.address;
        do {
            ++typedPageCount;
            ++probeIndex;
        } while (probeIndex < state->candidates.size() &&
                 state->candidates[probeIndex].address == address);
    }

    std::unordered_map<uint64_t, Candidate> liveCandidates;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState != state) {
            clearStagedAutoRevision(staged.revision);
            return nullptr;
        }
        if (gLiveCandidates.size() + typedPageCount > kLiveOverlayLimit) {
            gLiveCandidates.clear();
        }
        for (std::size_t index = candidateIndex; index < probeIndex; ++index) {
            const Candidate &candidate = state->candidates[index];
            gLiveCandidates.try_emplace(candidate.id, candidate);
        }
        liveCandidates = gLiveCandidates;
    }

    jlongArray result =
            materializeAutoCursorPage(env, page, *state, candidateIndex, liveCandidates);
    if (result == nullptr) {
        clearStagedAutoRevision(staged.revision);
    }
    return result;
}

struct AuthoritativeAutoBuildContext {
    const OperationContext *operation = nullptr;
    std::shared_ptr<SearchState> next;
    bool candidateLimitReached = false;
};

[[nodiscard]] bool materializeAuthoritativeAutoMatch(
        void *opaque, const jlmem::v2::KnownScanMatchView &match) {
    auto *build = static_cast<AuthoritativeAutoBuildContext *>(opaque);
    if (build == nullptr || build->operation == nullptr || build->next == nullptr ||
        match.address == 0U || match.chunkBytes == nullptr || match.width == 0U ||
        match.chunkOffset > match.chunkSize || match.width > match.chunkSize - match.chunkOffset) {
        return false;
    }
    const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(match.plane);
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
            match.address, *type, match.bits, match.bits);
    candidate.identityValid = snapshotIdentity(
            match.chunkBytes, match.chunkSize, match.chunkOffset, match.width,
            candidate.identityHash);
    build->next->candidates.push_back(candidate);
    return true;
}

[[nodiscard]] jint commitAuthoritativeAutoRevision(
        const OperationContext &context, std::shared_ptr<SearchState> next,
        std::shared_ptr<const jlmem::v2::ResultStore> store,
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints,
        std::shared_ptr<const std::vector<std::size_t>> candidateOffsets,
        jint historyMode) {
    if (next == nullptr || store == nullptr || checkpoints == nullptr ||
        candidateOffsets == nullptr) {
        setMessage("Auto ResultStore publication metadata is incomplete");
        return kResourceLimit;
    }
    if (isCancelled(context)) {
        setMessage("Operation cancelled; previous results were preserved");
        return kCancelled;
    }
    normalizeCandidateResults(*next);
    const std::size_t nextBytes = next->retainedBytes();
    if (nextBytes > kRetainedStateByteLimit ||
        store->retainedBytes() > kStagedKnownStoreByteLimit ||
        store->typedCount() != next->candidates.size() ||
        store->uniqueAddressCount() != next->logicalCount) {
        setMessage("Completed Auto ResultStore revision exceeds the safe migration budget");
        return kResourceLimit;
    }

    std::lock_guard<std::mutex> stateLock(gMutex);
    if (gTarget.generation != context.target.generation ||
        gTarget.token != context.target.token) {
        gLastMessage = "MIDlet runtime changed during the operation";
        return kTargetLost;
    }
    if (gState != context.state) {
        gLastMessage = "Search revision changed during the Auto ResultStore operation";
        return kInvalidRequest;
    }
    if (historyMode > 0 && gState->mode != StateMode::Empty) {
        gHistory.push_back(gState);
    } else if (historyMode < 0) {
        gHistory.clear();
    }
    trimHistoryLocked(nextBytes);

    std::shared_ptr<const SearchState> publishedState = next;
    gState = std::move(next);
    gLiveCandidates.clear();
    gNextCandidateId = context.nextId;

    std::lock_guard<std::mutex> stagedLock(gStagedAutoMutex);
    gStagedAuto.revision = nextAutoRevision(gStagedAuto.revision);
    gStagedAuto.legacyState = publishedState;
    gStagedAuto.store = std::move(store);
    gStagedAuto.checkpoints = std::move(checkpoints);
    gStagedAuto.candidateOffsets = std::move(candidateOffsets);
    gLastMessage.clear();
    return kOk;
}

[[nodiscard]] jint scanAutoV2Authoritative(
        const OperationContext &context, jint predicate,
        const std::string &first, const std::string &second) {
    if (context.nextId > std::numeric_limits<std::uint64_t>::max() - kCandidateLimit) {
        setMessage("Candidate identifier space is exhausted for this runtime");
        return kResourceLimit;
    }
    std::vector<Query> queries;
    if (!buildQueries(kTypeAuto, predicate, first, second, false, queries)) {
        setMessage("Invalid Auto value or predicate");
        return kInvalidRequest;
    }
    std::vector<jlmem::v2::KnownScanRequest> plans;
    plans.reserve(queries.size());
    for (const Query &query : queries) {
        const std::uint64_t firstBits = canonicalKnownBits(query, false);
        const std::uint64_t secondBits =
                predicate == kBetween ? canonicalKnownBits(query, true) : 0U;
        const auto plan = jlmem::v2::knownQueryPlanFromStableValues(
                toJint(query.type), predicate, firstBits, secondBits);
        if (!plan.has_value()) {
            setMessage("Auto query could not be canonicalized safely");
            return kInvalidRequest;
        }
        plans.push_back(*plan);
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = kTypeAuto;
    next->watches = context.state->watches;
    AuthoritativeAutoBuildContext build{&context, next, false};
    const jlmem::v2::KnownScanObserver observer{
            &build, materializeAuthoritativeAutoMatch, advanceAuthoritativeKnownProgress};

    std::vector<jlmem::v2::ScanRange> ranges;
    ranges.reserve(context.target.ranges.size());
    for (const Range &range : context.target.ranges) {
        ranges.push_back({range.start, range.end});
    }

    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    beginScanProgress(context.target);
    const bool scanned = jlmem::v2::scanKnownAuto(
            ranges, plans,
            [&](std::uintptr_t address, void *buffer, std::size_t size) {
                return readExact(context.target.pid, address, buffer, size);
            },
            [&] { return isCancelled(context); }, store, stats, error, &observer);
    if (!scanned) {
        if (build.candidateLimitReached) {
            setMessage("Complete Auto ResultStore search exceeds the candidate compatibility limit");
            return kResourceLimit;
        }
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        if (error.find("Target range changed") != std::string::npos) {
            setMessage("A target range changed while Auto was being scanned");
            return kTargetLost;
        }
        setMessage(error.empty() ? "Auto ResultStore scan could not be completed safely"
                                 : error.c_str());
        return kResourceLimit;
    }
    if (stats.typedMatches != next->candidates.size() ||
        store.typedCount() != next->candidates.size() ||
        store.retainedBytes() > kStagedKnownStoreByteLimit) {
        setMessage("Auto ResultStore and compatibility Candidate mirror diverged");
        return kResourceLimit;
    }

    fillMissingIdentities(context.target, next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    normalizeCandidateResults(*next);
    if (stats.uniqueAddresses != next->logicalCount ||
        store.uniqueAddressCount() != next->logicalCount) {
        setMessage("Auto ResultStore unique-address accounting diverged");
        return kResourceLimit;
    }

    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    std::shared_ptr<const std::vector<std::size_t>> candidateOffsets;
    if (!verifyAndIndexAutoStore(*next, store, checkpoints, candidateOffsets)) {
        setMessage("Auto ResultStore failed Candidate compatibility verification");
        return kResourceLimit;
    }
    auto publishedStore =
            std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    OperationContext committed = context;
    committed.nextId += next->candidates.size();
    return commitAuthoritativeAutoRevision(
            committed, std::move(next), std::move(publishedStore),
            std::move(checkpoints), std::move(candidateOffsets), -1);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnownAutoV2Authoritative(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return scanAutoV2Authoritative(
                context, predicate, fromJString(env, first), fromJString(env, second));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_stageV2AutoResultStore(
        JNIEnv *env, jclass) {
    try {
        return stageCurrentAutoResultStore() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        clearStagedAutoResultStore();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_hasCurrentV2AutoResultStore(
        JNIEnv *env, jclass) {
    try {
        return hasCurrentAutoResultStore() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearV2AutoResultStore(
        JNIEnv *, jclass) {
    clearStagedAutoResultStore();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPageV2Auto(
        JNIEnv *env, jclass, jint offset, jint limit) {
    try {
        return stagedAutoResultPage(env, offset, limit);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return nullptr;
    }
}
