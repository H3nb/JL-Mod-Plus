/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transitional compilation seam for the v2 migration.
//
// Keep the proven production engine source byte-for-byte intact while allowing small migration
// helpers to reuse its authoritative parser and immutable SearchState in the *same translation
// unit*. This is deliberately not a second parser or a second mutation model. Once ResultStore
// becomes the production owner, this seam can be folded into the final native module and
// memory_engine.cpp can become a normal implementation unit again.
#include "known_query_plan.h"
#include "result_cursor.h"
#include "result_store_scan.h"
#include "memory_engine.cpp"

#include <algorithm>
#include <memory>
#include <mutex>
#include <vector>

namespace {

constexpr std::size_t kStagedKnownCandidateLimit = 250'000U;
constexpr std::size_t kStagedKnownStoreByteLimit = 32U * 1024U * 1024U;
constexpr std::size_t kStagedKnownCursorCheckpointStride = 256U;

struct StagedKnownResultStore {
    std::uint64_t revision = 0U;
    std::weak_ptr<const SearchState> legacyState;
    std::shared_ptr<const jlmem::v2::ResultStore> store;
    // Cursor checkpoints are revision-local and map implicit logical offsets
    // 0, 256, 512, ... to exact bitmap positions. Keeping them behind one shared object avoids
    // copying an index on every Binder page request.
    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    jlmem::v2::ResultPlane plane = jlmem::v2::ResultPlane::Count;
};

std::mutex gStagedKnownMutex;
StagedKnownResultStore gStagedKnown;

[[nodiscard]] std::uint64_t nextStagedRevision(std::uint64_t current) noexcept {
    return current == std::numeric_limits<std::uint64_t>::max()
                   ? 1U
                   : current + 1U;
}

void clearStagedKnownResultStore() noexcept {
    std::lock_guard<std::mutex> lock(gStagedKnownMutex);
    const std::uint64_t revision = nextStagedRevision(gStagedKnown.revision);
    gStagedKnown = {};
    gStagedKnown.revision = revision;
}

void clearStagedKnownRevision(std::uint64_t revision) noexcept {
    std::lock_guard<std::mutex> lock(gStagedKnownMutex);
    if (gStagedKnown.revision != revision) {
        return;
    }
    const std::uint64_t next = nextStagedRevision(revision);
    gStagedKnown = {};
    gStagedKnown.revision = next;
}

[[nodiscard]] std::uint64_t canonicalIntegerBits(ValueType type,
                                                 std::int64_t value) noexcept {
    switch (type) {
    case ValueType::Byte:
        return static_cast<std::uint8_t>(static_cast<std::int8_t>(value));
    case ValueType::Short:
        return static_cast<std::uint16_t>(static_cast<std::int16_t>(value));
    case ValueType::Char:
        return static_cast<std::uint16_t>(value);
    case ValueType::Int:
        return static_cast<std::uint32_t>(static_cast<std::int32_t>(value));
    case ValueType::Long:
        return static_cast<std::uint64_t>(value);
    case ValueType::Invalid:
    case ValueType::Float:
    case ValueType::Double:
        return 0U;
    }
    return 0U;
}

[[nodiscard]] std::uint64_t canonicalKnownBits(const Query &query,
                                               bool second) noexcept {
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
    return canonicalIntegerBits(
            query.type, second ? query.integerSecond : query.integerFirst);
}

[[nodiscard]] std::optional<jlmem::v2::KnownQueryPlan> parseCanonicalKnownPlan(
        jint valueType, jint predicate, const std::string &first,
        const std::string &second) {
    if (valueType == kTypeAuto || predicate < kEqual || predicate > kBetween) {
        return std::nullopt;
    }
    Query query;
    if (!parseQuery(valueType, predicate, first, second, query)) {
        return std::nullopt;
    }
    const std::uint64_t firstBits = canonicalKnownBits(query, false);
    const std::uint64_t secondBits =
            predicate == kBetween ? canonicalKnownBits(query, true) : 0U;
    return jlmem::v2::knownQueryPlanFromStableValues(
            valueType, predicate, firstBits, secondBits);
}

[[nodiscard]] bool verifyAndIndexStagedKnownStore(
        const SearchState &state, const jlmem::v2::ResultStore &store,
        jlmem::v2::ResultPlane plane,
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> &published) {
    const std::uint8_t expectedAlias = jlmem::v2::resultPlaneBit(plane);
    auto checkpoints =
            std::make_shared<std::vector<jlmem::v2::ResultCursor>>();
    const std::size_t checkpointCount =
            state.candidates.empty()
                    ? 0U
                    : (state.candidates.size() - 1U) /
                                      kStagedKnownCursorCheckpointStride +
                              1U;
    checkpoints->reserve(checkpointCount);

    jlmem::v2::ResultCursor cursor;
    std::size_t verified = 0U;
    while (verified < state.candidates.size()) {
        if (verified % kStagedKnownCursorCheckpointStride == 0U) {
            checkpoints->push_back(cursor);
        }
        const std::size_t untilCheckpoint =
                kStagedKnownCursorCheckpointStride -
                verified % kStagedKnownCursorCheckpointStride;
        const std::size_t limit = std::min(
                {jlmem::v2::kResultCursorPageLimit,
                 state.candidates.size() - verified, untilCheckpoint});
        if (limit == 0U) {
            return false;
        }

        jlmem::v2::ResultAddressPage page;
        if (!jlmem::v2::readAddressPage(store, cursor, limit, page) ||
            page.rows.size() != limit) {
            return false;
        }
        for (std::size_t index = 0U; index < page.rows.size(); ++index) {
            const Candidate &candidate = state.candidates[verified + index];
            if (page.rows[index].address != candidate.address ||
                page.rows[index].aliasMask != expectedAlias) {
                return false;
            }
        }
        cursor = page.next;
        verified += page.rows.size();
    }

    // Sequential verification must finish at the canonical end cursor. This catches a bitmap that
    // contains hidden extra addresses even if the prefix happened to match every compatibility row.
    jlmem::v2::ResultAddressPage endPage;
    if (!jlmem::v2::readAddressPage(store, cursor, 1U, endPage) ||
        !endPage.rows.empty() ||
        endPage.next.blockIndex != store.blockCount() ||
        endPage.next.nextByteOffset != 0U) {
        return false;
    }
    if (checkpoints->size() != checkpointCount) {
        return false;
    }
    published = std::move(checkpoints);
    return true;
}

[[nodiscard]] bool buildStagedKnownStore(
        const std::shared_ptr<const SearchState> &state,
        jlmem::v2::ResultPlane plane,
        std::shared_ptr<const jlmem::v2::ResultStore> &publishedStore,
        std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>>
                &publishedCheckpoints) {
    if (state == nullptr || state->mode != StateMode::Candidates ||
        state->candidateOrderDirty || state->requestedType == kTypeAuto ||
        state->candidates.size() > kStagedKnownCandidateLimit ||
        state->logicalCount != state->candidates.size()) {
        return false;
    }

    const auto expectedPlane =
            jlmem::v2::resultPlaneFromStableValueType(state->requestedType);
    if (!expectedPlane.has_value() || *expectedPlane != plane) {
        return false;
    }
    const auto expectedType = valueTypeFromJint(state->requestedType);
    if (!expectedType.has_value()) {
        return false;
    }

    jlmem::v2::ResultStore store;
    jlmem::v2::ResultBlockScratch scratch;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    std::uintptr_t previousAddress = 0U;
    bool havePrevious = false;

    const auto flush = [&]() -> bool {
        if (!scratchActive) {
            return true;
        }
        if (scratch.empty() ||
            !store.appendNonEmptyBlock(scratchBase, scratch)) {
            return false;
        }
        scratch.reset();
        scratchBase = 0U;
        scratchActive = false;
        return true;
    };

    const std::size_t alignment = jlmem::v2::planeAlignment(plane);
    if (alignment == 0U) {
        return false;
    }
    for (const Candidate &candidate : state->candidates) {
        if (candidate.type != *expectedType || candidate.address == 0U ||
            candidate.address % alignment != 0U ||
            (havePrevious && candidate.address <= previousAddress)) {
            return false;
        }
        havePrevious = true;
        previousAddress = candidate.address;

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
        const std::size_t slot = byteOffset / alignment;
        // Duplicate membership is an invariant violation for an explicit-type production search.
        if (!scratch.set(plane, slot)) {
            return false;
        }
    }
    if (scratchActive && !flush()) {
        return false;
    }

    if (store.typedCount() != state->candidates.size() ||
        store.uniqueAddressCount() != state->logicalCount ||
        store.retainedBytes() > kStagedKnownStoreByteLimit ||
        !verifyAndIndexStagedKnownStore(
                *state, store, plane, publishedCheckpoints)) {
        return false;
    }

    publishedStore =
            std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    return true;
}

[[nodiscard]] bool stageCurrentKnownResultStore() {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    if (state == nullptr || state->requestedType == kTypeAuto) {
        clearStagedKnownResultStore();
        return false;
    }
    const auto plane =
            jlmem::v2::resultPlaneFromStableValueType(state->requestedType);
    if (!plane.has_value()) {
        clearStagedKnownResultStore();
        return false;
    }

    std::shared_ptr<const jlmem::v2::ResultStore> store;
    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    if (!buildStagedKnownStore(state, *plane, store, checkpoints)) {
        clearStagedKnownResultStore();
        return false;
    }

    // Publish under the same gMutex->staging-lock order used by stale-state invalidation so a
    // concurrent search/undo cannot slip between the ownership recheck and staged publication.
    std::lock_guard<std::mutex> stateLock(gMutex);
    if (gState != state) {
        return false;
    }
    std::lock_guard<std::mutex> stagedLock(gStagedKnownMutex);
    const std::uint64_t revision = nextStagedRevision(gStagedKnown.revision);
    gStagedKnown.revision = revision;
    gStagedKnown.legacyState = state;
    gStagedKnown.store = std::move(store);
    gStagedKnown.checkpoints = std::move(checkpoints);
    gStagedKnown.plane = *plane;
    return true;
}

[[nodiscard]] bool cursorForStagedOffset(
        const StagedKnownResultStore &staged, std::size_t offset,
        jlmem::v2::ResultCursor &cursor) {
    if (staged.store == nullptr || staged.checkpoints == nullptr ||
        offset >= staged.store->uniqueAddressCount()) {
        return false;
    }
    const std::size_t checkpointIndex =
            offset / kStagedKnownCursorCheckpointStride;
    if (checkpointIndex >= staged.checkpoints->size()) {
        return false;
    }
    cursor = (*staged.checkpoints)[checkpointIndex];
    std::size_t remaining =
            offset - checkpointIndex * kStagedKnownCursorCheckpointStride;
    while (remaining != 0U) {
        const std::size_t step =
                std::min(remaining, jlmem::v2::kResultCursorPageLimit);
        jlmem::v2::ResultAddressPage skipped;
        if (!jlmem::v2::readAddressPage(*staged.store, cursor, step, skipped) ||
            skipped.rows.size() != step) {
            return false;
        }
        cursor = skipped.next;
        remaining -= step;
    }
    return true;
}

[[nodiscard]] jlongArray stagedKnownResultPage(JNIEnv *env, jint offset,
                                               jint limit) {
    if (offset < 0 || limit <= 0 ||
        limit > static_cast<jint>(jlmem::v2::kResultCursorPageLimit)) {
        return nullptr;
    }

    StagedKnownResultStore staged;
    {
        std::lock_guard<std::mutex> lock(gStagedKnownMutex);
        staged = gStagedKnown;
    }
    if (staged.store == nullptr || staged.checkpoints == nullptr ||
        staged.plane == jlmem::v2::ResultPlane::Count) {
        return nullptr;
    }
    const auto state = staged.legacyState.lock();
    if (state == nullptr) {
        clearStagedKnownRevision(staged.revision);
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState != state) {
            clearStagedKnownRevision(staged.revision);
            return nullptr;
        }
    }
    if (state->mode != StateMode::Candidates || state->candidateOrderDirty ||
        state->logicalCount != state->candidates.size() ||
        staged.store->uniqueAddressCount() != state->logicalCount) {
        clearStagedKnownRevision(staged.revision);
        return nullptr;
    }

    const std::size_t start = std::min<std::size_t>(
            static_cast<std::size_t>(offset), state->candidates.size());
    const std::size_t expectedCount = std::min<std::size_t>(
            static_cast<std::size_t>(limit), state->candidates.size() - start);
    if (expectedCount == 0U) {
        std::unordered_map<uint64_t, Candidate> liveCandidates;
        return candidatePage(env, state->candidates, liveCandidates, start, 0U);
    }

    jlmem::v2::ResultCursor cursor;
    jlmem::v2::ResultAddressPage page;
    if (!cursorForStagedOffset(staged, start, cursor) ||
        !jlmem::v2::readAddressPage(
                *staged.store, cursor, expectedCount, page) ||
        page.rows.size() != expectedCount) {
        clearStagedKnownRevision(staged.revision);
        return nullptr;
    }

    const std::uint8_t expectedAlias = jlmem::v2::resultPlaneBit(staged.plane);
    const jint expectedType =
            jlmem::v2::stableValueTypeFromResultPlane(staged.plane);
    for (std::size_t index = 0U; index < page.rows.size(); ++index) {
        const Candidate &candidate = state->candidates[start + index];
        if (page.rows[index].address != candidate.address ||
            page.rows[index].aliasMask != expectedAlias ||
            toJint(candidate.type) != expectedType) {
            clearStagedKnownRevision(staged.revision);
            return nullptr;
        }
    }

    std::unordered_map<uint64_t, Candidate> liveCandidates;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState != state) {
            clearStagedKnownRevision(staged.revision);
            return nullptr;
        }
        if (gLiveCandidates.size() + expectedCount > kLiveOverlayLimit) {
            gLiveCandidates.clear();
        }
        for (std::size_t index = 0U; index < expectedCount; ++index) {
            const Candidate &candidate = state->candidates[start + index];
            gLiveCandidates.try_emplace(candidate.id, candidate);
        }
        liveCandidates = gLiveCandidates;
    }
    return candidatePage(env, state->candidates, liveCandidates, start,
                         expectedCount);
}

struct AuthoritativeKnownBuildContext {
    const OperationContext *operation = nullptr;
    std::shared_ptr<SearchState> next;
    ValueType type = ValueType::Invalid;
    bool candidateLimitReached = false;
};

[[nodiscard]] bool materializeAuthoritativeKnownMatch(
        void *opaque, const jlmem::v2::KnownScanMatchView &match) {
    auto *build = static_cast<AuthoritativeKnownBuildContext *>(opaque);
    if (build == nullptr || build->operation == nullptr || build->next == nullptr ||
        build->type == ValueType::Invalid || match.address == 0U ||
        match.chunkBytes == nullptr || match.width != widthOf(build->type) ||
        match.chunkOffset > match.chunkSize ||
        match.width > match.chunkSize - match.chunkOffset) {
        return false;
    }
    if (build->next->candidates.size() >= kCandidateLimit) {
        build->candidateLimitReached = true;
        return false;
    }
    Candidate candidate = makeCandidate(
            build->operation->nextId + build->next->candidates.size(),
            match.address, build->type, match.bits, match.bits);
    candidate.identityValid = snapshotIdentity(
            match.chunkBytes, match.chunkSize, match.chunkOffset, match.width,
            candidate.identityHash);
    build->next->candidates.push_back(candidate);
    return true;
}

void advanceAuthoritativeKnownProgress(void *, std::size_t bytes) {
    advanceScanProgress(bytes);
}

[[nodiscard]] jint scanKnownV2Authoritative(
        const OperationContext &context, jint valueType, jint predicate,
        const std::string &first, const std::string &second) {
    if (valueType == kTypeAuto ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() -
                                 kCandidateLimit) {
        setMessage(valueType == kTypeAuto
                           ? "Authoritative ResultStore first scan requires an explicit type"
                           : "Candidate identifier space is exhausted for this runtime");
        return valueType == kTypeAuto ? kInvalidRequest : kResourceLimit;
    }
    const auto type = valueTypeFromJint(valueType);
    const auto plan = parseCanonicalKnownPlan(valueType, predicate, first, second);
    if (!type.has_value() || !plan.has_value()) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = valueType;
    next->watches = context.state->watches;

    AuthoritativeKnownBuildContext build{&context, next, *type, false};
    const jlmem::v2::KnownScanObserver observer{
            &build,
            materializeAuthoritativeKnownMatch,
            advanceAuthoritativeKnownProgress,
    };
    std::vector<jlmem::v2::ScanRange> ranges;
    ranges.reserve(context.target.ranges.size());
    for (const Range &range : context.target.ranges) {
        ranges.push_back({range.start, range.end});
    }

    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    beginScanProgress(context.target);
    const bool scanned = jlmem::v2::scanKnownExplicit(
            ranges, *plan,
            [&](std::uintptr_t address, void *buffer, std::size_t size) {
                return readExact(context.target.pid, address, buffer, size);
            },
            [&] { return isCancelled(context); }, store, stats, error, &observer);
    if (!scanned) {
        if (build.candidateLimitReached) {
            setMessage("Complete ResultStore search exceeds the candidate compatibility limit");
            return kResourceLimit;
        }
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        if (error.find("Target range changed") != std::string::npos) {
            setMessage("A target range changed while it was being scanned");
            return kTargetLost;
        }
        setMessage(error.empty()
                           ? "ResultStore first scan could not be completed safely"
                           : error.c_str());
        return kResourceLimit;
    }

    // The v2 bitmap is the membership owner. Candidate rows are only a compatibility mirror for
    // existing mutation/Watch/Inspector code until TrackedCandidate promotion is complete.
    if (store.typedCount() != build.next->candidates.size() ||
        store.uniqueAddressCount() != build.next->candidates.size() ||
        store.retainedBytes() > kStagedKnownStoreByteLimit) {
        setMessage("Authoritative ResultStore revision exceeds migration invariants or budget");
        return kResourceLimit;
    }
    fillMissingIdentities(context.target, build.next->candidates);
    build.next->logicalCount = build.next->candidates.size();
    build.next->candidateOrderDirty = true;
    normalizeCandidateResults(*build.next);

    std::shared_ptr<const std::vector<jlmem::v2::ResultCursor>> checkpoints;
    if (!verifyAndIndexStagedKnownStore(
                *build.next, store, plan->plane, checkpoints)) {
        setMessage("ResultStore and compatibility Candidate mirror diverged before publication");
        return kResourceLimit;
    }

    auto publishedStore =
            std::make_shared<jlmem::v2::ResultStore>(std::move(store));
    std::shared_ptr<const SearchState> publishedState = build.next;
    OperationContext committed = context;
    committed.nextId += build.next->candidates.size();
    const jint result = commitOperation(committed, std::move(build.next), -1);
    if (result != kOk) {
        return result;
    }

    // Java does not enable v2 paging until this JNI call returns, so publication here can safely
    // follow the SearchState commit while still presenting one externally atomic operation.
    std::lock_guard<std::mutex> stateLock(gMutex);
    if (gState != publishedState ||
        gTarget.generation != context.target.generation ||
        gTarget.token != context.target.token) {
        clearStagedKnownResultStore();
        gLastMessage = "MIDlet runtime or search changed before ResultStore publication";
        return kTargetLost;
    }
    std::lock_guard<std::mutex> stagedLock(gStagedKnownMutex);
    const std::uint64_t revision = nextStagedRevision(gStagedKnown.revision);
    gStagedKnown.revision = revision;
    gStagedKnown.legacyState = publishedState;
    gStagedKnown.store = std::move(publishedStore);
    gStagedKnown.checkpoints = std::move(checkpoints);
    gStagedKnown.plane = plan->plane;
    gLastMessage.clear();
    return kOk;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_canonicalKnownPlan(
        JNIEnv *env, jclass, jint valueType, jint predicate, jstring first,
        jstring second) {
    try {
        const auto plan = parseCanonicalKnownPlan(
                valueType, predicate, fromJString(env, first),
                fromJString(env, second));
        if (!plan.has_value()) {
            return nullptr;
        }
        const std::array<jlong, 4> values{
                static_cast<jlong>(valueType),
                static_cast<jlong>(predicate),
                static_cast<jlong>(plan->firstBits),
                static_cast<jlong>(plan->secondBits),
        };
        jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
        if (result != nullptr) {
            env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                    values.data());
        }
        return result;
    } catch (...) {
        // Diagnostics/migration metadata must never destabilize the production engine or replace
        // its user-visible lastMessage. Invalid/failed canonicalization is simply unavailable.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnownV2Authoritative(
        JNIEnv *env, jclass, jint valueType, jint predicate, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return scanKnownV2Authoritative(
                context, valueType, predicate, fromJString(env, first),
                fromJString(env, second));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_stageV2KnownResultStore(
        JNIEnv *env, jclass) {
    try {
        return stageCurrentKnownResultStore() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        // Production staging is opportunistic. Allocation/invariant failure must never turn a
        // successful Candidate-owned refine into a user-visible failure.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        clearStagedKnownResultStore();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearV2KnownResultStore(
        JNIEnv *, jclass) {
    clearStagedKnownResultStore();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPageV2Known(
        JNIEnv *env, jclass, jint offset, jint limit) {
    try {
        return stagedKnownResultPage(env, offset, limit);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return nullptr;
    }
}

extern "C" jlongArray
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
        JNIEnv *, jclass, jint, jint);

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPageUnchecked(
        JNIEnv *env, jclass clazz, jint offset, jint limit) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
            env, clazz, offset, limit);
}
