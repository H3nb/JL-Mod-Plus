/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

#include "result_revision.h"

namespace {

constexpr std::size_t kResultRevisionCatalogByteLimit = 64U * 1024U * 1024U;

struct ResultRevisionCatalogEntry {
    std::weak_ptr<const SearchState> state;
    std::shared_ptr<const jlmem::v2::ResultRevision> revision;
};

std::mutex gResultRevisionCatalogMutex;
std::deque<ResultRevisionCatalogEntry> gResultRevisionCatalog;

void pruneExpiredResultRevisionsLocked() {
    gResultRevisionCatalog.erase(
            std::remove_if(
                    gResultRevisionCatalog.begin(), gResultRevisionCatalog.end(),
                    [](const ResultRevisionCatalogEntry &entry) {
                        return entry.revision == nullptr || entry.state.expired();
                    }),
            gResultRevisionCatalog.end());
}

[[nodiscard]] std::size_t resultRevisionCatalogBytesLocked() noexcept {
    std::size_t total = 0U;
    for (const ResultRevisionCatalogEntry &entry : gResultRevisionCatalog) {
        if (entry.revision == nullptr) {
            continue;
        }
        const std::size_t bytes = entry.revision->retainedBytes();
        if (bytes == std::numeric_limits<std::size_t>::max() ||
            total > std::numeric_limits<std::size_t>::max() - bytes) {
            return std::numeric_limits<std::size_t>::max();
        }
        total += bytes;
    }
    return total;
}

void rememberResultRevision(
        const std::shared_ptr<const SearchState> &state,
        std::shared_ptr<const jlmem::v2::ResultRevision> revision) {
    if (state == nullptr || revision == nullptr || !revision->valid() ||
        revision->retainedBytes() > kResultRevisionCatalogByteLimit) {
        return;
    }
    std::lock_guard<std::mutex> lock(gResultRevisionCatalogMutex);
    pruneExpiredResultRevisionsLocked();
    gResultRevisionCatalog.erase(
            std::remove_if(
                    gResultRevisionCatalog.begin(), gResultRevisionCatalog.end(),
                    [&](const ResultRevisionCatalogEntry &entry) {
                        return entry.state.lock() == state;
                    }),
            gResultRevisionCatalog.end());
    gResultRevisionCatalog.push_back({state, std::move(revision)});

    // The catalog is only an Undo acceleration layer. Drop the oldest retained revision rather
    // than letting COW history create an independent unbounded memory budget. Candidate history
    // remains authoritative and can always rebuild ResultStore if a catalog entry was evicted.
    while (gResultRevisionCatalog.size() > 1U &&
           resultRevisionCatalogBytesLocked() > kResultRevisionCatalogByteLimit) {
        gResultRevisionCatalog.pop_front();
    }
}

[[nodiscard]] std::shared_ptr<const jlmem::v2::ResultRevision>
lookupResultRevision(const std::shared_ptr<const SearchState> &state) {
    if (state == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gResultRevisionCatalogMutex);
    pruneExpiredResultRevisionsLocked();
    for (auto it = gResultRevisionCatalog.rbegin();
         it != gResultRevisionCatalog.rend(); ++it) {
        if (it->state.lock() == state && it->revision != nullptr &&
            it->revision->valid()) {
            return it->revision;
        }
    }
    return nullptr;
}

void clearResultRevisionCatalog() noexcept {
    std::lock_guard<std::mutex> lock(gResultRevisionCatalogMutex);
    gResultRevisionCatalog.clear();
}

[[nodiscard]] std::shared_ptr<const jlmem::v2::ResultRevision>
currentStagedResultRevision(const std::shared_ptr<const SearchState> &state) {
    if (state == nullptr || state->mode != StateMode::Candidates) {
        return nullptr;
    }

    StagedAutoResultStore autoStage;
    {
        std::lock_guard<std::mutex> lock(gStagedAutoMutex);
        autoStage = gStagedAuto;
    }
    if (stagedAutoMatchesState(autoStage, state)) {
        auto revision = std::make_shared<jlmem::v2::ResultRevision>();
        revision->kind = jlmem::v2::ResultRevisionKind::Auto;
        revision->plane = jlmem::v2::ResultPlane::Count;
        revision->store = autoStage.store;
        revision->checkpoints = autoStage.checkpoints;
        revision->candidateOffsets = autoStage.candidateOffsets;
        return revision->valid() ? revision : nullptr;
    }

    StagedKnownResultStore knownStage;
    {
        std::lock_guard<std::mutex> lock(gStagedKnownMutex);
        knownStage = gStagedKnown;
    }
    if (stagedKnownMatchesState(knownStage, state)) {
        auto revision = std::make_shared<jlmem::v2::ResultRevision>();
        revision->kind = jlmem::v2::ResultRevisionKind::Explicit;
        revision->plane = knownStage.plane;
        revision->store = knownStage.store;
        revision->checkpoints = knownStage.checkpoints;
        revision->candidateOffsets.reset();
        return revision->valid() ? revision : nullptr;
    }
    return nullptr;
}

[[nodiscard]] bool rememberCurrentStagedResultRevision() {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    auto revision = currentStagedResultRevision(state);
    if (revision == nullptr) {
        return false;
    }
    rememberResultRevision(state, std::move(revision));
    return true;
}

[[nodiscard]] bool publishRememberedRevisionForState(
        const std::shared_ptr<const SearchState> &state,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision) {
    if (state == nullptr || revision == nullptr || !revision->valid() ||
        state->mode != StateMode::Candidates || state->candidateOrderDirty ||
        revision->store->typedCount() != state->candidates.size() ||
        revision->store->uniqueAddressCount() != state->logicalCount) {
        return false;
    }

    if (revision->kind == jlmem::v2::ResultRevisionKind::Auto) {
        if (state->requestedType != kTypeAuto || revision->candidateOffsets == nullptr) {
            return false;
        }
        std::lock_guard<std::mutex> stagedLock(gStagedAutoMutex);
        gStagedAuto.revision = nextAutoRevision(gStagedAuto.revision);
        gStagedAuto.legacyState = state;
        gStagedAuto.store = revision->store;
        gStagedAuto.checkpoints = revision->checkpoints;
        gStagedAuto.candidateOffsets = revision->candidateOffsets;
        return true;
    }

    const auto expectedPlane =
            jlmem::v2::resultPlaneFromStableValueType(state->requestedType);
    if (state->requestedType == kTypeAuto || !expectedPlane.has_value() ||
        *expectedPlane != revision->plane) {
        return false;
    }
    std::lock_guard<std::mutex> stagedLock(gStagedKnownMutex);
    gStagedKnown.revision = nextStagedRevision(gStagedKnown.revision);
    gStagedKnown.legacyState = state;
    gStagedKnown.store = revision->store;
    gStagedKnown.checkpoints = revision->checkpoints;
    gStagedKnown.plane = revision->plane;
    return true;
}

[[nodiscard]] jint undoWithRememberedResultRevision() {
    std::shared_ptr<const SearchState> source;
    std::shared_ptr<const SearchState> restoredState;
    std::shared_ptr<const jlmem::v2::ResultRevision> revision;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gHistory.empty()) {
            gLastMessage = "No earlier search state is available";
            return kNoSession;
        }
        source = gHistory.back();
        revision = lookupResultRevision(source);
        auto restored = std::make_shared<SearchState>(*source);
        // Preserve the session Watch List exactly like the legacy Undo implementation.
        restored->watches = gState->watches;
        for (Candidate &watch : restored->watches) {
            watch = liveCandidate(watch, gLiveCandidates);
        }
        restoredState = restored;
        gState = std::move(restored);
        gLiveCandidates.clear();
        gHistory.pop_back();
        gLastMessage.clear();
    }

    if (revision != nullptr && publishRememberedRevisionForState(restoredState, revision)) {
        rememberResultRevision(restoredState, revision);
    } else {
        // Stale staging objects are harmless but clearing them makes the Java stage flag and the
        // next paging request converge immediately on the restored Unknown/legacy state.
        clearStagedAutoResultStore();
        clearStagedKnownResultStore();
    }
    return kOk;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_rememberCurrentV2Revision(
        JNIEnv *env, jclass) {
    try {
        return rememberCurrentStagedResultRevision() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearV2RevisionCatalog(
        JNIEnv *, jclass) {
    clearResultRevisionCatalog();
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_undoV2Aware(
        JNIEnv *, jclass) {
    return guardedOperation([] { return undoWithRememberedResultRevision(); });
}
