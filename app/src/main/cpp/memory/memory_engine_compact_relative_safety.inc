/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Move-safe first materialization for an Unknown baseline. This replaces the transitional entry
// point while the compact-owner file is being split up. The important invariant is that nextId is
// computed before revision ownership is moved into commitCompactOwnerRevision().
namespace {

[[nodiscard]] jint compactOwnerScanRelativeUnknownSafe(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Unknown) {
        return kNoSession;
    }
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        return kInvalidRequest;
    }
    if (context.nextId == 0U ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() - kCandidateLimit) {
        setMessage("ResultId space is exhausted for this runtime");
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
    try {
        ranges.reserve(context.state->snapshots.size());
        for (const SnapshotRun &snapshot : context.state->snapshots) {
            ranges.push_back({snapshot.start, snapshot.bytes});
        }
    } catch (...) {
        setMessage("Unknown baseline metadata exceeds the engine memory budget");
        return kResourceLimit;
    }
    if (ranges.empty()) {
        setMessage("Unknown baseline contains no captured ranges");
        return kNoSession;
    }

    CompactOwnerScanBuild build;
    build.firstId = context.nextId;
    if (!build.ordinary.reserve(kCompactOwnerInitialReserve)) {
        setMessage("Compact relative metadata could not be reserved");
        return kResourceLimit;
    }
    const jlmem::v2::RelativeScanObserver observer{
            &build, compactOwnerRelativeMatch, compactOwnerAdvanceProgress};
    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    beginScanProgress(context.target);
    const auto read = [&](std::uintptr_t address, void *buffer, std::size_t size) {
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
        if (build.limitReached) {
            setMessage("Complete compact relative search exceeds the typed-result limit");
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
        setMessage(error.empty() ? "Compact relative scan could not be completed safely"
                                 : error.c_str());
        return kResourceLimit;
    }
    if (store.typedCount() != build.ordinary.size() ||
        stats.typedMatches != build.ordinary.size() ||
        stats.uniqueAddresses != store.uniqueAddressCount()) {
        setMessage("Compact relative membership and value metadata diverged");
        return kResourceLimit;
    }

    compactOwnerFillMissingIdentities(context.target, build);
    const auto explicitPlane = context.state->requestedType == kTypeAuto
                                       ? jlmem::v2::ResultPlane::Count
                                       : plans.front().plane;
    auto revision = compactOwnerMakeRevision(
            context.state->requestedType, explicitPlane,
            std::move(store), std::move(build.ordinary));
    if (revision == nullptr) {
        setMessage("Compact relative revision could not be finalized");
        return kResourceLimit;
    }

    const std::size_t ordinaryCount = revision->ordinaryValues->size();
    if (context.nextId > std::numeric_limits<std::uint64_t>::max() - ordinaryCount) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }
    const std::uint64_t nextId = context.nextId + ordinaryCount;
    const std::uint64_t logicalCount = revision->store->uniqueAddressCount();

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = logicalCount;
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), 1, nextId);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelativeV2CompactOwnerSafe(
        JNIEnv *env, jclass, jint predicate, jint compareTarget, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        if (context.state->mode == StateMode::Unknown) {
            return compactOwnerScanRelativeUnknownSafe(
                    context, predicate, compareTarget,
                    fromJString(env, first), fromJString(env, second));
        }
        return compactOwnerRefineRelative(
                context, predicate, compareTarget,
                fromJString(env, first), fromJString(env, second));
    });
}
