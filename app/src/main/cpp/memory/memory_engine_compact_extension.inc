/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_alias_cursor.h"
#include "result_revision.h"

// Transitional compact-owner layer. It is deliberately kept in the same translation unit as the
// proven engine so selected ordinary rows can be promoted into the existing strict Candidate
// mutation primitives without exporting a second parser, identity model or write ABI.
namespace {

constexpr std::size_t kCompactRevisionByteLimit = kRetainedStateByteLimit;

struct CompactRevisionEntry {
    std::weak_ptr<const SearchState> state;
    std::shared_ptr<const jlmem::v2::ResultRevision> revision;
};

std::mutex gCompactRevisionMutex;
std::deque<CompactRevisionEntry> gCompactRevisions;

constexpr std::array<jlmem::v2::ResultPlane, jlmem::v2::kResultPlaneCount>
        kCompactDisplayOrder{
                jlmem::v2::ResultPlane::Int,
                jlmem::v2::ResultPlane::Float,
                jlmem::v2::ResultPlane::Long,
                jlmem::v2::ResultPlane::Double,
                jlmem::v2::ResultPlane::Short,
                jlmem::v2::ResultPlane::Char,
                jlmem::v2::ResultPlane::Byte,
        };

void pruneCompactRevisionsLocked() {
    gCompactRevisions.erase(
            std::remove_if(
                    gCompactRevisions.begin(), gCompactRevisions.end(),
                    [](const CompactRevisionEntry &entry) {
                        return entry.revision == nullptr || entry.state.expired();
                    }),
            gCompactRevisions.end());
}

void clearCompactRevisions() noexcept {
    std::lock_guard<std::mutex> lock(gCompactRevisionMutex);
    gCompactRevisions.clear();
}

[[nodiscard]] std::shared_ptr<const jlmem::v2::ResultRevision>
lookupCompactRevision(const std::shared_ptr<const SearchState> &state) {
    if (state == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gCompactRevisionMutex);
    pruneCompactRevisionsLocked();
    for (auto it = gCompactRevisions.rbegin(); it != gCompactRevisions.rend(); ++it) {
        if (it->state.lock() == state && it->revision != nullptr &&
            it->revision->compact()) {
            return it->revision;
        }
    }
    return nullptr;
}

[[nodiscard]] bool rememberCompactRevision(
        const std::shared_ptr<const SearchState> &state,
        std::shared_ptr<const jlmem::v2::ResultRevision> revision) {
    if (state == nullptr || revision == nullptr || !revision->compact() ||
        revision->retainedBytes() > kCompactRevisionByteLimit) {
        return false;
    }
    std::lock_guard<std::mutex> lock(gCompactRevisionMutex);
    pruneCompactRevisionsLocked();
    gCompactRevisions.erase(
            std::remove_if(
                    gCompactRevisions.begin(), gCompactRevisions.end(),
                    [&](const CompactRevisionEntry &entry) {
                        return entry.state.lock() == state;
                    }),
            gCompactRevisions.end());
    gCompactRevisions.push_back({state, std::move(revision)});
    return true;
}

[[nodiscard]] bool buildCompactRevisionFromCandidates(
        const std::shared_ptr<const SearchState> &state,
        std::shared_ptr<const jlmem::v2::ResultRevision> &published) {
    if (state == nullptr || state->mode != StateMode::Candidates ||
        state->candidateOrderDirty || state->candidates.size() > kCandidateLimit) {
        return false;
    }
    const auto source = currentStagedResultRevision(state);
    if (source == nullptr || !source->valid() || source->store == nullptr ||
        source->store->typedCount() != state->candidates.size() ||
        source->store->uniqueAddressCount() != state->logicalCount) {
        return false;
    }

    jlmem::v2::OrdinaryResultStore ordinary;
    if (!ordinary.reserve(state->candidates.size())) {
        return false;
    }
    jlmem::v2::ResultAliasCursor cursor;
    std::size_t ordinal = 0U;
    while (ordinal < state->candidates.size()) {
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                state->candidates.size() - ordinal);
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*source->store, cursor, limit, page) ||
            page.rows.size() != limit) {
            return false;
        }
        for (const jlmem::v2::ResultAliasRow &row : page.rows) {
            const Candidate &candidate = state->candidates[ordinal];
            const auto plane = jlmem::v2::resultPlaneFromStableValueType(
                    toJint(candidate.type));
            if (!plane.has_value() || *plane != row.plane ||
                candidate.address != row.address || candidate.id == 0U ||
                !ordinary.append(
                        {candidate.id, candidate.initialBits, candidate.previousBits,
                         candidate.currentBits, candidate.identityHash},
                        candidate.identityValid)) {
                return false;
            }
            ++ordinal;
        }
        cursor = page.next;
    }
    jlmem::v2::ResultAliasPage endPage;
    if (!ordinary.finalizeIdIndex() ||
        !jlmem::v2::readAliasPage(*source->store, cursor, 1U, endPage) ||
        !endPage.rows.empty()) {
        return false;
    }

    auto ordinaryOwner = std::make_shared<const jlmem::v2::OrdinaryResultStore>(
            std::move(ordinary));
    auto revision = std::make_shared<jlmem::v2::ResultRevision>(*source);
    revision->ordinaryValues = std::move(ordinaryOwner);
    if (revision->kind == jlmem::v2::ResultRevisionKind::Auto) {
        // Deep page and ResultId resolution are now derived from bitmap/header counts.
        revision->candidateOffsets.reset();
    }
    if (!revision->compact()) {
        return false;
    }
    published = std::move(revision);
    return true;
}

[[nodiscard]] bool stageCurrentCompactRevision() {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    std::shared_ptr<const jlmem::v2::ResultRevision> revision;
    if (!buildCompactRevisionFromCandidates(state, revision)) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState != state) {
            return false;
        }
    }
    return rememberCompactRevision(state, std::move(revision));
}

[[nodiscard]] bool compactRevisionForContext(
        const OperationContext &context,
        std::shared_ptr<const jlmem::v2::ResultRevision> &revision) {
    revision = lookupCompactRevision(context.state);
    return revision != nullptr && revision->compact() && revision->store != nullptr &&
           revision->ordinaryValues != nullptr &&
           revision->store->typedCount() == revision->ordinaryValues->size() &&
           revision->store->uniqueAddressCount() == context.state->logicalCount;
}

[[nodiscard]] bool compactCandidateForId(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        std::uint64_t id, Candidate &candidate) {
    if (id == 0U || revision == nullptr || !revision->compact()) {
        return false;
    }
    const auto live = context.liveCandidates.find(id);
    if (live != context.liveCandidates.end()) {
        candidate = live->second;
        return true;
    }

    const auto ordinal = revision->ordinaryValues->findIndexById(id);
    if (ordinal.has_value()) {
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPageAtTypedOffset(
                    *revision->store, *ordinal, 1U, page) ||
            page.rows.size() != 1U) {
            return false;
        }
        const auto *record = revision->ordinaryValues->record(*ordinal);
        const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(
                page.rows.front().plane);
        const auto type = valueTypeFromJint(rawType);
        if (record == nullptr || !type.has_value() || record->id != id) {
            return false;
        }
        candidate.id = record->id;
        candidate.address = page.rows.front().address;
        candidate.initialBits = record->initialBits;
        candidate.previousBits = record->previousBits;
        candidate.currentBits = record->currentBits;
        candidate.identityHash = record->identityHash;
        candidate.relocationCount = 0U;
        candidate.type = *type;
        candidate.state = kStable;
        candidate.identityValid = revision->ordinaryValues->identityValid(*ordinal);
        return true;
    }

    // Watch entries survive New Search/Clear Search independently from ordinary membership.
    const auto watch = std::find_if(
            context.state->watches.begin(), context.state->watches.end(),
            [&](const Candidate &item) { return item.id == id; });
    if (watch != context.state->watches.end()) {
        candidate = liveCandidate(*watch, context.liveCandidates);
        return true;
    }
    return false;
}

[[nodiscard]] bool compactCandidatesForIds(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids, std::vector<Candidate> &selected) {
    selected.clear();
    selected.reserve(ids.size());
    for (const std::uint64_t id : ids) {
        Candidate candidate{};
        if (!compactCandidateForId(context, revision, id, candidate)) {
            return false;
        }
        selected.push_back(candidate);
    }
    return true;
}

[[nodiscard]] jlongArray compactResultPage(JNIEnv *env, jint offset, jint limit) {
    if (offset < 0 || limit <= 0 ||
        limit > static_cast<jint>(jlmem::v2::kResultCursorPageLimit)) {
        return nullptr;
    }
    std::shared_ptr<const SearchState> state;
    std::unordered_map<std::uint64_t, Candidate> liveCandidates;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
        liveCandidates = gLiveCandidates;
    }
    const auto revision = lookupCompactRevision(state);
    if (revision == nullptr || !revision->compact() || revision->store == nullptr ||
        revision->ordinaryValues == nullptr ||
        revision->store->uniqueAddressCount() != state->logicalCount) {
        return nullptr;
    }

    const std::uint64_t uniqueCount = revision->store->uniqueAddressCount();
    const std::uint64_t start = std::min<std::uint64_t>(
            static_cast<std::uint64_t>(offset), uniqueCount);
    const std::size_t addressCount = static_cast<std::size_t>(
            std::min<std::uint64_t>(static_cast<std::uint64_t>(limit),
                                    uniqueCount - start));
    if (addressCount == 0U) {
        jlongArray empty = env->NewLongArray(1);
        const jlong zero = 0L;
        if (empty != nullptr) {
            env->SetLongArrayRegion(empty, 0, 1, &zero);
        }
        return empty;
    }

    jlmem::v2::ResultAliasCursor aliasCursor;
    std::uint64_t typedOffset = 0U;
    jlmem::v2::ResultAddressPage addresses;
    if (!jlmem::v2::seekAliasAddressOffset(
                *revision->store, start, aliasCursor, typedOffset) ||
        !jlmem::v2::readAddressPage(
                *revision->store, aliasCursor.addressCursor, addressCount, addresses) ||
        addresses.rows.size() != addressCount) {
        return nullptr;
    }

    std::size_t typedCount = 0U;
    for (const jlmem::v2::ResultAddressRow &row : addresses.rows) {
        typedCount += static_cast<std::size_t>(std::popcount(row.aliasMask));
    }
    if (typedOffset > revision->ordinaryValues->size() ||
        typedCount > revision->ordinaryValues->size() -
                             static_cast<std::size_t>(typedOffset) ||
        typedCount > (std::numeric_limits<std::size_t>::max() - 1U) / kResultStride) {
        return nullptr;
    }

    std::vector<jlong> output(1U + typedCount * kResultStride);
    output[0] = static_cast<jlong>(typedCount);
    std::size_t outputIndex = 0U;
    std::size_t ordinal = static_cast<std::size_t>(typedOffset);
    for (const jlmem::v2::ResultAddressRow &row : addresses.rows) {
        for (const jlmem::v2::ResultPlane plane : kCompactDisplayOrder) {
            if ((row.aliasMask & jlmem::v2::resultPlaneBit(plane)) == 0U) {
                continue;
            }
            const auto *record = revision->ordinaryValues->record(ordinal++);
            const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(plane);
            const auto type = valueTypeFromJint(rawType);
            if (record == nullptr || !type.has_value() || record->id == 0U) {
                return nullptr;
            }
            Candidate visible{};
            visible.id = record->id;
            visible.address = row.address;
            visible.initialBits = record->initialBits;
            visible.previousBits = record->previousBits;
            visible.currentBits = record->currentBits;
            visible.identityHash = record->identityHash;
            visible.type = *type;
            visible.state = kStable;
            visible.identityValid = revision->ordinaryValues->identityValid(ordinal - 1U);
            const auto live = liveCandidates.find(record->id);
            if (live != liveCandidates.end()) {
                if (live->second.id != record->id || live->second.type != *type ||
                    live->second.address == 0U) {
                    return nullptr;
                }
                visible = live->second;
            }
            const std::size_t base = 1U + outputIndex * kResultStride;
            output[base] = static_cast<jlong>(visible.id);
            output[base + 1U] = static_cast<jlong>(visible.address);
            output[base + 2U] = static_cast<jlong>(row.address);
            output[base + 3U] = rawType;
            output[base + 4U] = visible.state;
            output[base + 5U] = visible.relocationCount;
            output[base + 6U] = static_cast<jlong>(visible.initialBits);
            output[base + 7U] = static_cast<jlong>(visible.previousBits);
            output[base + 8U] = static_cast<jlong>(visible.currentBits);
            ++outputIndex;
        }
    }
    if (outputIndex != typedCount ||
        output.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

[[nodiscard]] jint refreshCompactCandidates(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids, bool allowRecovery) {
    if (ids.empty()) {
        setMessage("Select at least one candidate to refresh");
        return kInvalidRequest;
    }
    std::vector<Candidate> selected;
    if (!compactCandidatesForIds(context, revision, ids, selected)) {
        setMessage("One or more compact ResultIds are no longer available");
        return kInvalidRequest;
    }

    std::vector<std::size_t> recovery;
    for (std::size_t index = 0U; index < selected.size(); ++index) {
        Candidate &candidate = selected[index];
        std::uint64_t current = 0U;
        std::uint64_t hash = 0U;
        const bool readable = readCandidate(context.target, candidate, current);
        const bool identityReadable =
                readable && readIdentity(context.target, candidate.address,
                                         candidate.type, hash);
        if (!allowRecovery) {
            if (!readable) {
                candidate.state = kRelocating;
                continue;
            }
            candidate.previousBits = candidate.currentBits;
            candidate.currentBits = current;
            candidate.state = kStable;
            if (identityReadable && !candidate.identityValid) {
                candidate.identityHash = hash;
                candidate.identityValid = true;
            }
            continue;
        }
        const bool identityMatches =
                identityReadable &&
                (!candidate.identityValid || hash == candidate.identityHash);
        if (!identityMatches) {
            candidate.state = kRelocating;
            recovery.push_back(index);
            continue;
        }
        candidate.identityHash = hash;
        candidate.identityValid = true;
        candidate.previousBits = candidate.currentBits;
        candidate.currentBits = current;
        candidate.state = kStable;
    }
    if (recovery.size() > kRecoveryLimit) {
        setMessage("Identity recovery is limited to 32 candidates per operation");
        return kResourceLimit;
    }
    std::size_t unsafe = 0U;
    const jint recovered = recoverCandidatesBatch(
            context.target, context.cancellationEpoch, selected, recovery, unsafe);
    if (recovered != kOk) {
        return recovered;
    }
    const jint published = publishLiveCandidates(context, selected);
    if (published == kOk && unsafe != 0U) {
        setMessage("Some candidates remain ambiguous or lost; writes stay paused");
    }
    return published;
}

[[nodiscard]] jint editCompactCandidates(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids,
        const std::string &replacement) {
    if (ids.empty()) {
        setMessage("Select at least one materialized candidate");
        return kInvalidRequest;
    }
    if (ids.size() > kMultiWriteLimit) {
        setMessage("The bounded multi-edit safety limit is 32 candidates");
        return kSafetyLimit;
    }
    std::vector<Candidate> selected;
    if (!compactCandidatesForIds(context, revision, ids, selected)) {
        setMessage("One or more compact ResultIds are no longer available");
        return kInvalidRequest;
    }
    std::uint64_t ignored = 0U;
    for (const Candidate &candidate : selected) {
        if (!replacementBitsFor(candidate, replacement, ignored)) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
    }

    std::vector<WrittenSpan> writes;
    writes.reserve(selected.size());
    std::size_t edited = 0U;
    std::size_t skipped = 0U;
    for (Candidate &candidate : selected) {
        WrittenSpan written{};
        const int outcome = editOneCandidate(
                context.target, candidate, replacement, &written);
        if (outcome < 0) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
        if (outcome > 0) {
            ++edited;
            writes.push_back(written);
        } else {
            ++skipped;
        }
    }
    const std::vector<Candidate> reconciled =
            reconcileTrackedLiveCandidatesAfterWrites(context, writes, selected);
    const jint published = publishLiveCandidates(context, reconciled);
    if (published == kOk) {
        setMessage((std::to_string(edited) + " edited, " +
                    std::to_string(skipped) + " skipped safely").c_str());
    }
    return published;
}

[[nodiscard]] jint pinCompactCandidates(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids, bool add) {
    if (ids.empty()) {
        setMessage("Select at least one materialized candidate");
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>(*context.state);
    std::vector<Candidate> promoted;
    if (add) {
        if (!compactCandidatesForIds(context, revision, ids, promoted)) {
            setMessage("One or more compact ResultIds are not in this search");
            return kInvalidRequest;
        }
        std::unordered_set<std::uint64_t> existing;
        for (const Candidate &watch : next->watches) {
            existing.insert(watch.id);
        }
        std::size_t additions = 0U;
        for (const Candidate &candidate : promoted) {
            if (existing.insert(candidate.id).second) {
                ++additions;
            }
        }
        if (next->watches.size() + additions > kWatchLimit) {
            setMessage("The session Watch List limit is 128 candidates");
            return kResourceLimit;
        }
        for (const Candidate &candidate : promoted) {
            if (std::none_of(
                        next->watches.begin(), next->watches.end(),
                        [&](const Candidate &watch) { return watch.id == candidate.id; })) {
                next->watches.push_back(candidate);
            }
        }
    } else {
        const std::size_t before = next->watches.size();
        next->watches.erase(
                std::remove_if(next->watches.begin(), next->watches.end(),
                               [&](const Candidate &watch) {
                                   return std::binary_search(ids.begin(), ids.end(), watch.id);
                               }),
                next->watches.end());
        if (before - next->watches.size() != ids.size()) {
            setMessage("One or more candidates are not in the Watch List");
            return kInvalidRequest;
        }
    }

    const jint result = commitOperation(context, std::move(next), 0, true);
    if (result != kOk) {
        return result;
    }
    std::shared_ptr<const SearchState> rebound;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        rebound = gState;
    }
    if (!rememberCompactRevision(rebound, revision)) {
        setMessage("Watch List changed but compact revision could not be rebound safely");
        return kResourceLimit;
    }
    return kOk;
}

[[nodiscard]] jlongArray inspectCompactCandidate(
        JNIEnv *env, const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        std::uint64_t id, jint radius) {
    if (id == 0U || radius <= 0 || radius > kMaxInspectRadius) {
        setMessage("Inspector radius or candidate is invalid");
        return inspectionResult(env, kInvalidRequest);
    }
    Candidate candidate{};
    if (!compactCandidateForId(context, revision, id, candidate)) {
        setMessage("Inspector candidate is no longer available");
        return inspectionResult(env, kInvalidRequest);
    }
    if (!verifyCandidateBinding(context.target, candidate)) {
        return inspectionResult(env, kIdentityUnsafe);
    }
    Range window{};
    if (!candidateWindow(context.target, candidate, static_cast<std::size_t>(radius),
                         window)) {
        setMessage("Inspector candidate is outside the current resident ranges");
        return inspectionResult(env, kTargetLost);
    }
    std::vector<std::uint8_t> bytes(
            static_cast<std::size_t>(window.end - window.start));
    if (!readExact(context.target.pid, window.start, bytes.data(), bytes.size())) {
        setMessage("Inspector window changed while it was being read");
        return inspectionResult(env, kTargetLost);
    }
    if (isCancelled(context)) {
        setMessage("Inspector read was cancelled");
        return inspectionResult(env, kCancelled);
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gTarget.generation != context.target.generation ||
            gTarget.token != context.target.token || gState != context.state) {
            gLastMessage = "MIDlet runtime or search changed during Inspector read";
            return inspectionResult(env, kTargetLost);
        }
        gLastMessage.clear();
    }
    return inspectionResult(env, kOk, window.start, candidate.address, &bytes);
}

[[nodiscard]] jlongArray expandCompactResultGroups(
        JNIEnv *env, const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &sourceIds, jint requestedType) {
    if (requestedType != kTypeAuto && !valueTypeFromJint(requestedType).has_value()) {
        return nullptr;
    }
    std::vector<jlong> output;
    std::unordered_set<std::uint64_t> emitted;
    for (const std::uint64_t sourceId : sourceIds) {
        Candidate source{};
        if (!compactCandidateForId(context, revision, sourceId, source)) {
            return nullptr;
        }
        jlmem::v2::ResultAliasAddressLookup lookup;
        if (!jlmem::v2::lookupAliasAddress(
                    *revision->store, source.address, lookup)) {
            return nullptr;
        }
        std::size_t ordinal = static_cast<std::size_t>(lookup.typedOffset);
        for (const jlmem::v2::ResultPlane plane : kCompactDisplayOrder) {
            if ((lookup.row.aliasMask & jlmem::v2::resultPlaneBit(plane)) == 0U) {
                continue;
            }
            const auto *record = revision->ordinaryValues->record(ordinal++);
            const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(plane);
            if (record == nullptr ||
                (requestedType != kTypeAuto && rawType != requestedType)) {
                continue;
            }
            if (emitted.insert(record->id).second) {
                output.push_back(static_cast<jlong>(record->id));
            }
        }
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr && !output.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_stageV2CompactRevision(
        JNIEnv *env, jclass) {
    try {
        return stageCurrentCompactRevision() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_hasCurrentV2CompactRevision(
        JNIEnv *env, jclass) {
    try {
        std::shared_ptr<const SearchState> state;
        {
            std::lock_guard<std::mutex> lock(gMutex);
            state = gState;
        }
        return lookupCompactRevision(state) != nullptr ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPageV2Compact(
        JNIEnv *env, jclass, jint offset, jint limit) {
    try {
        return compactResultPage(env, offset, limit);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refreshV2Compact(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean allowRecovery) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return refreshCompactCandidates(
                context, revision, ids, allowRecovery == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editV2Compact(
        JNIEnv *env, jclass, jlongArray rawIds, jstring replacement) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return editCompactCandidates(
                context, revision, ids, fromJString(env, replacement));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pinV2Compact(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean add) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return pinCompactCandidates(context, revision, ids, add == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspectV2Compact(
        JNIEnv *env, jclass, jlong rawId, jint radius) {
    try {
        if (rawId <= 0) return inspectionResult(env, kInvalidRequest);
        OperationContext context;
        if (!beginOperation(context)) return inspectionResult(env, kNoSession);
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) {
            return inspectionResult(env, kNoSession);
        }
        return inspectCompactCandidate(
                env, context, revision, static_cast<std::uint64_t>(rawId), radius);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return inspectionResult(env, kInvalidRequest);
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroupsV2Compact(
        JNIEnv *env, jclass, jlongArray rawIds, jint requestedType) {
    try {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return nullptr;
        OperationContext context;
        if (!beginOperation(context)) return nullptr;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return nullptr;
        return expandCompactResultGroups(env, context, revision, ids, requestedType);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearV2CompactRevisions(
        JNIEnv *, jclass) {
    clearCompactRevisions();
}
