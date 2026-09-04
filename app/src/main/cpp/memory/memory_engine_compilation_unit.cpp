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

[[nodiscard]] jint refineKnownProduction(JNIEnv *env, jint predicate,
                                         jstring first, jstring second,
                                         bool revalidateIdentity) {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }
    if (context.state == nullptr || context.state->mode != StateMode::Candidates) {
        setMessage("No Known search session is available for Next Scan");
        return kNoSession;
    }
    if (revalidateIdentity &&
        context.state->candidates.size() > kRelocationTrackLimit) {
        // Identity revalidation deliberately remains bounded. A large Auto result can contain
        // hundreds of thousands or millions of typed aliases; issuing one identity syscall per
        // candidate after GC would recreate the long, surprising recovery behavior this path is
        // replacing. Preserve the previous revision and ask for a new search instead.
        setMessage("Java GC changed during a large result set; bounded identity revalidation is unavailable, so previous results were preserved");
        return kIdentityUnsafe;
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

    const std::uint64_t totalWork = knownRefineWorkBytes(*context.state);
    if (!context.state->candidates.empty() && totalWork == 0U) {
        setMessage("Known Next Scan work size could not be represented safely");
        return kResourceLimit;
    }
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(totalWork, std::memory_order_release);

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    next->candidates.reserve(context.state->candidates.size());

    CandidateValueReader reader(context.target,
                                context.state->candidates.size());
    std::uint64_t completedWork = 0U;
    for (const Candidate &stored : context.state->candidates) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }

        const Candidate &live = liveCandidate(stored, context.liveCandidates);
        Candidate bound = stored;
        bound.address = live.address;
        bound.relocationCount = live.relocationCount;
        bound.state = live.state;
        bound.identityHash = live.identityHash;
        bound.identityValid = live.identityValid;

        const std::size_t width = widthOf(bound.type);
        if (width == 0U) {
            setMessage("Known Next Scan encountered an invalid candidate type");
            return kInvalidRequest;
        }

        if (revalidateIdentity) {
            if (!bound.identityValid || bound.address == 0U) {
                setMessage("Java GC changed candidate identity; previous results were preserved");
                return kIdentityUnsafe;
            }
            std::uint64_t identityHash = 0U;
            if (!readIdentity(context.target, bound.address, bound.type,
                              identityHash) || identityHash != bound.identityHash) {
                setMessage("Java GC moved or replaced a candidate; previous results were preserved");
                return kIdentityUnsafe;
            }
        }

        std::uint64_t current = 0U;
        if (!reader.read(bound, current)) {
            setMessage("A candidate binding became unreadable during Next Scan; previous results were preserved");
            return kIdentityUnsafe;
        }

        Candidate updated = bound;
        updated.previousBits = stored.currentBits;
        updated.currentBits = current;
        updated.state = kStable;

        const Query *query = queriesByType[typeIndex(stored.type)];
        if (query == nullptr) {
            setMessage("Known Next Scan could not resolve a candidate query type");
            return kInvalidRequest;
        }
        if (matchesKnown(current, *query, predicate)) {
            next->candidates.push_back(updated);
        }

        completedWork += static_cast<std::uint64_t>(width);
        gScanBytesScanned.store(completedWork, std::memory_order_release);
    }

    // A zero-survivor refine is a valid transactional result. Do not reinterpret it as proof of
    // relocation. When GC changed before this pass, every old binding was already fingerprint-
    // checked above; when GC changes during the pass, the service's post-operation epoch guard
    // rolls this revision back.
    captureIdentities(context.target, next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;

    const std::size_t survivorCount = next->candidates.size();
    const jint result = commitOperation(context, std::move(next), 1U);
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
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownProduction(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second,
        jboolean revalidateIdentity) {
    return guardedOperation([&] {
        return refineKnownProduction(env, predicate, first, second,
                                     revalidateIdentity == JNI_TRUE);
    });
}
