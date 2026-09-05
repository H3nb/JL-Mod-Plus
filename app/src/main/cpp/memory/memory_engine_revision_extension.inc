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
std::atomic<std::uint64_t> gResultRevisionRestoreHits{0U};
std::atomic<std::uint64_t> gResultRevisionRestoreMisses{0U};

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

    // This catalog is an Undo acceleration layer, not a second independent history budget. COW
    // payloads can be shared by several revisions, but retainedBytes() intentionally accounts each
    // published revision conservatively. If the bound is crossed, evict the oldest acceleration
    // entry and let Undo fall back to verified Candidate->ResultStore restaging.
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
    gResultRevisionRestoreHits.store(0U, std::memory_order_release);
    gResultRevisionRestoreMisses.store(0U, std::memory_order_release);
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
    if (autoStageMatchesState(autoStage, state)) {
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
        // Search history and Watch List deliberately have different lifetimes. Preserve the live
        // Watch List exactly like legacy Undo while restoring only the immutable search revision.
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
        gResultRevisionRestoreHits.fetch_add(1U, std::memory_order_acq_rel);
    } else {
        gResultRevisionRestoreMisses.fetch_add(1U, std::memory_order_acq_rel);
        // A catalog miss is not an Undo failure. Clear stale stages so Java can either expose an
        // Unknown baseline immediately or rebuild a verified ResultStore from the restored
        // Candidate compatibility mirror on demand.
        clearStagedAutoResultStore();
        clearStagedKnownResultStore();
    }
    return kOk;
}

[[nodiscard]] jlongArray resultRevisionCatalogStats(JNIEnv *env) {
    std::size_t entries = 0U;
    std::size_t retainedBytes = 0U;
    {
        std::lock_guard<std::mutex> lock(gResultRevisionCatalogMutex);
        pruneExpiredResultRevisionsLocked();
        entries = gResultRevisionCatalog.size();
        retainedBytes = resultRevisionCatalogBytesLocked();
    }
    const auto clamp = [](std::size_t value) -> jlong {
        const auto maximum = static_cast<std::size_t>(std::numeric_limits<jlong>::max());
        return static_cast<jlong>(std::min(value, maximum));
    };
    const jlong values[] = {
            clamp(entries),
            clamp(retainedBytes),
            static_cast<jlong>(gResultRevisionRestoreHits.load(std::memory_order_acquire)),
            static_cast<jlong>(gResultRevisionRestoreMisses.load(std::memory_order_acquire)),
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 4, values);
    }
    return result;
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2RevisionCatalogStats(
        JNIEnv *env, jclass) {
    try {
        return resultRevisionCatalogStats(env);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_undoV2Aware(
        JNIEnv *, jclass) {
    return guardedOperation([] { return undoWithRememberedResultRevision(); });
}
