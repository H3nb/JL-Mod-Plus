/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transitional production compilation unit.
//
// memory_engine_entry.cpp already owns the intentional single-TU seam around the legacy engine.
// Include it here so narrowly scoped migration safety hooks can inspect the same immutable
// SearchState without exporting raw Candidate internals across translation units. Delete this
// wrapper when ResultStore becomes the production search-state owner.
#include "memory_engine_entry.cpp"

namespace {

constexpr char kLegacyZeroRefineRecoveryMessage[] =
        "Direct Next Scan found no candidates; refreshing resident ranges for relocation recovery";

[[nodiscard]] jint finalizeEmptyKnownRefineIfSafe() {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }
    if (context.state == nullptr || context.state->mode != StateMode::Candidates ||
        context.state->candidates.empty() ||
        context.state->candidates.size() > kRelocationTrackLimit) {
        return kIdentityUnsafe;
    }

    std::string message;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        message = gLastMessage;
    }
    if (message != kLegacyZeroRefineRecoveryMessage) {
        return kIdentityUnsafe;
    }

    // The legacy refine reached zero matches, but historically treated that as proof of
    // relocation. Zero matches is a valid result. Before publishing it, independently verify every
    // bounded CandidateId fingerprint at its current live binding. If even one binding cannot be
    // proven, preserve the previous revision and let the service report identity uncertainty.
    for (const Candidate &stored : context.state->candidates) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const Candidate &candidate = liveCandidate(stored, context.liveCandidates);
        if (!candidate.identityValid || candidate.address == 0U) {
            setMessage("Next Scan reached zero matches but candidate identity could not be verified; previous results were preserved");
            return kIdentityUnsafe;
        }
        std::uint64_t identityHash = 0U;
        if (!readIdentity(context.target, candidate.address, candidate.type,
                          identityHash) || identityHash != candidate.identityHash) {
            setMessage("Next Scan reached zero matches but candidate identity changed; previous results were preserved");
            return kIdentityUnsafe;
        }
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = 0U;
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;

    const jint result = commitOperation(context, std::move(next), 1U);
    if (result == kOk) {
        setMessage("Next Scan found no matching values");
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_finalizeEmptyKnownRefineIfSafe(
        JNIEnv *, jclass) {
    return guardedOperation([] { return finalizeEmptyKnownRefineIfSafe(); });
}
