/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

namespace {

[[nodiscard]] jint refreshPresentationCandidates(
        const OperationContext &context, const std::vector<std::uint64_t> &ids) {
    if ((context.state->mode != StateMode::Candidates && context.state->watches.empty()) ||
        ids.empty()) {
        setMessage("No materialized candidates are available for presentation refresh");
        return ids.empty() ? kInvalidRequest : kNoSession;
    }

    std::vector<Candidate> selected;
    selected.reserve(ids.size());
    std::unordered_set<std::uint64_t> unresolved(ids.begin(), ids.end());
    for (std::uint64_t id : ids) {
        const auto live = context.liveCandidates.find(id);
        if (live != context.liveCandidates.end()) {
            selected.push_back(live->second);
            unresolved.erase(id);
        }
    }
    const auto collect = [&](const Candidate &candidate) {
        if (unresolved.erase(candidate.id) != 0U) {
            selected.push_back(liveCandidate(candidate, context.liveCandidates));
        }
    };
    if (!unresolved.empty()) {
        for (const Candidate &candidate : context.state->candidates) {
            collect(candidate);
            if (unresolved.empty()) break;
        }
        for (const Candidate &watch : context.state->watches) {
            collect(watch);
            if (unresolved.empty()) break;
        }
    }
    if (!unresolved.empty()) {
        setMessage("One or more visible CandidateIds are no longer available");
        return kInvalidRequest;
    }

    for (Candidate &candidate : selected) {
        std::uint64_t current = 0U;
        if (!readCandidate(context.target, candidate, current)) {
            // Preserve the last validated value. A stale/unreadable binding is presentation state,
            // not permission to guess relocation during a 4 Hz visible-row refresh.
            candidate.state = kRelocating;
            continue;
        }
        if (!candidate.identityValid) {
            std::uint64_t hash = 0U;
            if (readIdentity(context.target, candidate.address, candidate.type, hash)) {
                candidate.identityHash = hash;
                candidate.identityValid = true;
            }
        }
        candidate.previousBits = candidate.currentBits;
        candidate.currentBits = current;
        candidate.state = kStable;
    }
    return publishLiveCandidates(context, selected);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refreshPresentation(
        JNIEnv *env, jclass, jlongArray rawIds) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return refreshPresentationCandidates(context, ids);
    });
}

extern "C" jint
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refresh(
        JNIEnv *, jclass, jlongArray, jboolean);

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refreshUnchecked(
        JNIEnv *env, jclass clazz, jlongArray rawIds, jboolean allowRecovery) {
    return Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refresh(
            env, clazz, rawIds, allowRecovery);
}
