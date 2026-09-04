/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transitional production compilation unit.
//
// Search membership and tracked identity are deliberately separate here. Known/Auto Next Scan
// refines the immutable raw addresses from the committed search revision. The bounded live overlay
// remains available to Watch/Edit/Freeze/Inspector, but it must never change which addresses an
// ordinary search revision refines. Delete this seam when ResultStore becomes the production owner.
#include "memory_engine_entry.cpp"

namespace {

constexpr std::size_t kExpandedMaxTargetRuns = 16'384U;
constexpr std::size_t kInitialKnownRefineReserve = 16U * 1024U;
constexpr std::size_t kCandidateCompactionSlack = 4U * 1024U;

[[nodiscard]] jint configureTargetExpanded(JNIEnv *env, jint pid, jint pageSize,
                                           jlong token, jlongArray rawRuns) {
    try {
        if (pid <= 0 || pageSize <= 0 || (pageSize & (pageSize - 1)) != 0 ||
            token == 0 || rawRuns == nullptr) {
            setMessage("Invalid target configuration");
            return kInvalidRequest;
        }
        const jsize length = env->GetArrayLength(rawRuns);
        const std::size_t rawLength =
                length < 0 ? 0U : static_cast<std::size_t>(length);
        if (length < 4 || (length - 2) % 2 != 0 ||
            rawLength > 2U + kExpandedMaxTargetRuns * 2U) {
            setMessage("Invalid target range list");
            return kInvalidRequest;
        }

        std::array<jlong, 2> header{};
        env->GetLongArrayRegion(rawRuns, 0, 2, header.data());
        if (env->ExceptionCheck()) {
            return kInvalidRequest;
        }
        const jlong declaredRuns = header[0];
        if (header[1] != 0 || declaredRuns <= 0 ||
            static_cast<std::uint64_t>(declaredRuns) > kExpandedMaxTargetRuns ||
            declaredRuns != (length - 2) / 2) {
            setMessage("Incomplete target range list");
            return kResourceLimit;
        }

        std::vector<jlong> values(rawLength);
        env->GetLongArrayRegion(rawRuns, 0, length, values.data());
        if (env->ExceptionCheck() || values[1] != 0 || values[0] != declaredRuns) {
            setMessage("Incomplete target range list");
            return kResourceLimit;
        }

        Target target;
        target.pid = pid;
        target.pageSize = static_cast<std::size_t>(pageSize);
        target.token = token;
        target.ranges.reserve(static_cast<std::size_t>(declaredRuns));
        uintptr_t previousEnd = 0U;
        for (jsize index = 2; index < length; index += 2) {
            if (values[index] <= 0 || values[index + 1] <= values[index] ||
                static_cast<std::uint64_t>(values[index]) >
                        std::numeric_limits<uintptr_t>::max() ||
                static_cast<std::uint64_t>(values[index + 1]) >
                        std::numeric_limits<uintptr_t>::max()) {
                setMessage("Invalid target range bounds");
                return kInvalidRequest;
            }
            const uintptr_t start = static_cast<uintptr_t>(values[index]);
            const uintptr_t end = static_cast<uintptr_t>(values[index + 1]);
            if (start % target.pageSize != 0 || end % target.pageSize != 0 ||
                (!target.ranges.empty() && start < previousEnd)) {
                setMessage("Target ranges are unaligned or overlap");
                return kInvalidRequest;
            }
            target.ranges.push_back({start, end});
            previousEnd = end;
        }

        std::lock_guard<std::mutex> lock(gMutex);
        target.generation = gTarget.generation + 1U;
        const bool sameRuntime =
                gTarget.pid == target.pid && gTarget.token == target.token;
        gTarget = std::move(target);
        if (!sameRuntime) {
            gState = gEmptyState;
            gHistory.clear();
            gLiveCandidates.clear();
            gNextCandidateId = 1U;
        }
        gLastMessage.clear();
        return kOk;
    } catch (const std::bad_alloc &) {
        setMessage("Target configuration exceeds the engine memory budget");
        return kResourceLimit;
    } catch (...) {
        setMessage("Invalid target configuration");
        return kInvalidRequest;
    }
}

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

void compactKnownRefineCandidates(std::vector<Candidate> &candidates) {
    const std::size_t size = candidates.size();
    const std::size_t capacity = candidates.capacity();
    if (capacity <= size) {
        return;
    }
    const std::size_t tolerated =
            size > (std::numeric_limits<std::size_t>::max() -
                    kCandidateCompactionSlack) /
                           2U
                    ? std::numeric_limits<std::size_t>::max()
                    : size * 2U + kCandidateCompactionSlack;
    if (capacity <= tolerated) {
        return;
    }
    std::vector<Candidate> compact(candidates.begin(), candidates.end());
    candidates.swap(compact);
}

[[nodiscard]] jint refineKnownAddressSet(JNIEnv *env, jint predicate,
                                         jstring first, jstring second) {
    OperationContext context;
    if (!beginOperation(context)) {
        return kNoSession;
    }
    if (context.state == nullptr || context.state->mode != StateMode::Candidates) {
        setMessage("No Known search session is available for Next Scan");
        return kNoSession;
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
    next->candidates.reserve(std::min(
            context.state->candidates.size(), kInitialKnownRefineReserve));

    // The legacy reader is still useful as an I/O optimization: direct reads for a small set and
    // 256 KiB cache reuse for a large sorted set. What changes here is ownership semantics: only
    // the immutable SearchState address is supplied, never a relocated live-overlay address.
    CandidateValueReader reader(context.target,
                                context.state->candidates.size());
    std::uint64_t completedWork = 0U;
    std::size_t readableCount = 0U;
    std::size_t unreadableCount = 0U;

    for (const Candidate &stored : context.state->candidates) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }

        const std::size_t width = widthOf(stored.type);
        if (width == 0U) {
            setMessage("Known Next Scan encountered an invalid candidate type");
            return kInvalidRequest;
        }

        std::uint64_t current = 0U;
        if (!reader.read(stored, current)) {
            // Ordinary search results are raw addresses. A page that disappeared after GC or heap
            // trimming simply stops being a member of the next revision; it is not evidence that
            // millions of logical identities must be recovered. Tracked values use a separate,
            // bounded recovery path when the user watches, inspects, freezes, or edits them.
            ++unreadableCount;
            completedWork += static_cast<std::uint64_t>(width);
            gScanBytesScanned.store(completedWork, std::memory_order_release);
            continue;
        }
        ++readableCount;

        const Query *query = queriesByType[typeIndex(stored.type)];
        // Auto intentionally permits a threshold to be representable by only a subset of its
        // primitive aliases (for example 200 cannot be a signed Byte). Non-representable aliases
        // are filtered out instead of rejecting the whole Auto operation.
        if (query != nullptr && matchesKnown(current, *query, predicate)) {
            Candidate updated = stored;
            updated.previousBits = stored.currentBits;
            updated.currentBits = current;
            updated.state = kStable;

            // Search membership never consults identity metadata, but a fingerprint already
            // captured cheaply during the first chunk scan is still valuable later. Preserve it
            // passively so bounded Watch/Edit/Freeze/Inspector promotion can prove or relocate the
            // selected logical value after GC. We deliberately do not recapture fingerprints here.
            next->candidates.push_back(updated);
        }

        completedWork += static_cast<std::uint64_t>(width);
        gScanBytesScanned.store(completedWork, std::memory_order_release);
    }

    if (!context.state->candidates.empty() && readableCount == 0U) {
        setMessage("The previous search address set is no longer readable; previous results were preserved");
        return kInvalidRequest;
    }

    compactKnownRefineCandidates(next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;

    const std::size_t survivorCount = next->candidates.size();
    const jint result = commitOperation(context, std::move(next), 1U);
    if (result == kOk) {
        gScanBytesScanned.store(totalWork, std::memory_order_release);
        if (survivorCount == 0U) {
            setMessage("Next Scan found no matching values");
        } else if (unreadableCount > 0U) {
            setMessage(("Next Scan discarded " + std::to_string(unreadableCount) +
                        " unreadable address aliases")
                               .c_str());
        }
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTargetExpanded(
        JNIEnv *env, jclass, jint pid, jint pageSize, jlong token,
        jlongArray rawRuns) {
    return configureTargetExpanded(env, pid, pageSize, token, rawRuns);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownAddressSet(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        return refineKnownAddressSet(env, predicate, first, second);
    });
}
