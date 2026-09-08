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

[[nodiscard]] bool compactOwnerUnknownCaptureAllows(
        jint captureType, ValueType candidateType) noexcept {
    if (captureType == kTypeAuto) {
        return true;
    }
    const auto captured = valueTypeFromJint(captureType);
    return captured.has_value() && *captured == candidateType;
}

[[nodiscard]] jint compactOwnerScanKnownUnknownSafe(
        const OperationContext &context, jint requestedType, jint predicate,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Unknown) {
        return kNoSession;
    }
    if (context.nextId == 0U ||
        context.nextId > kRawCandidateIdMax - kCandidateLimit) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }

    std::vector<Query> queries;
    if (!buildQueries(requestedType, predicate, first, second, false, queries)) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        if (compactOwnerUnknownCaptureAllows(
                    context.state->requestedType, query.type)) {
            queriesByType[typeIndex(query.type)] = &query;
        }
    }

    CompactOwnerScanBuild build;
    build.firstId = context.nextId;
    if (!build.ordinary.reserve(kCompactOwnerInitialReserve)) {
        setMessage("Compact absolute metadata could not be reserved");
        return kResourceLimit;
    }
    jlmem::v2::ResultStore store;
    jlmem::v2::ResultBlockScratch scratch;
    std::uintptr_t scratchBase = 0U;
    bool scratchActive = false;
    const auto flush = [&]() -> bool {
        if (!scratchActive) return true;
        if (scratch.empty() || !store.appendNonEmptyBlock(scratchBase, scratch)) {
            return false;
        }
        scratch.reset();
        scratchActive = false;
        scratchBase = 0U;
        return true;
    };
    const ValueType displayTypes[] = {
            ValueType::Int, ValueType::Float, ValueType::Long,
            ValueType::Double, ValueType::Short, ValueType::Char,
            ValueType::Byte};
    beginScanProgress(context.target);
    for (const SnapshotRun &snapshot : context.state->snapshots) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        std::vector<std::uint8_t> current(snapshot.bytes.size());
        if (!readExact(context.target.pid, snapshot.start, current.data(),
                       current.size())) {
            setMessage("A target range changed while the Unknown baseline was being refined");
            return kTargetLost;
        }
        for (std::size_t offset = 0U; offset < snapshot.bytes.size(); ++offset) {
            if ((offset & 0xfffU) == 0U && isCancelled(context)) {
                setMessage("Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            std::uintptr_t address = 0U;
            if (!checkedAddressAdd(snapshot.start, offset, address)) {
                setMessage("Unknown baseline address arithmetic overflowed safely");
                return kTargetLost;
            }
            for (const ValueType type : displayTypes) {
                const Query *query = queriesByType[typeIndex(type)];
                const std::size_t width = widthOf(type);
                if (query == nullptr || width == 0U ||
                    address % width != 0U ||
                    offset > snapshot.bytes.size() - width) {
                    continue;
                }
                const std::uint64_t initial =
                        loadBits(snapshot.bytes.data() + offset, width);
                const std::uint64_t now =
                        loadBits(current.data() + offset, width);
                if (!matchesKnown(now, *query, predicate)) {
                    continue;
                }
                const std::uintptr_t blockBase =
                        address & ~(static_cast<std::uintptr_t>(
                                            jlmem::v2::kResultLogicalBlockSize) -
                                    std::uintptr_t{1U});
                if (!scratchActive || scratchBase != blockBase) {
                    if (!flush()) {
                        setMessage("Compact absolute membership exceeded the memory budget");
                        return kResourceLimit;
                    }
                    scratchBase = blockBase;
                    scratchActive = true;
                }
                const auto plane = jlmem::v2::resultPlaneFromStableValueType(
                        toJint(type));
                const std::size_t alignment =
                        plane.has_value() ? jlmem::v2::planeAlignment(*plane) : 0U;
                const std::size_t byteOffset =
                        static_cast<std::size_t>(address - blockBase);
                if (!plane.has_value() || alignment == 0U ||
                    byteOffset % alignment != 0U ||
                    !scratch.set(*plane, byteOffset / alignment) ||
                    !compactOwnerAppendScanRecord(
                            build, address, type, initial, now,
                            snapshot.bytes.data(), snapshot.bytes.size(), offset,
                            width)) {
                    setMessage(build.limitReached
                                       ? "Complete compact absolute search exceeds the typed-result limit"
                                       : "Compact absolute membership could not be staged safely");
                    return build.limitReached ? kResourceLimit : kResourceLimit;
                }
            }
        }
        advanceScanProgress(snapshot.bytes.size());
    }
    if (!flush() || store.typedCount() != build.ordinary.size() ||
        store.uniqueAddressCount() > build.ordinary.size()) {
        setMessage("Compact absolute membership and value metadata diverged");
        return kResourceLimit;
    }
    compactOwnerFillMissingIdentities(context.target, build);
    const auto explicitPlane = requestedType == kTypeAuto
                                       ? jlmem::v2::ResultPlane::Count
                                       : jlmem::v2::resultPlaneFromStableValueType(requestedType)
                                                 .value_or(jlmem::v2::ResultPlane::Count);
    auto revision = compactOwnerMakeRevision(
            requestedType, explicitPlane, std::move(store),
            std::move(build.ordinary));
    if (revision == nullptr ||
        context.nextId > kRawCandidateIdMax - revision->ordinaryValues->size()) {
        setMessage("Compact absolute revision could not be finalized");
        return kResourceLimit;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = requestedType;
    next->logicalCount = revision->store->uniqueAddressCount();
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), 1,
            context.nextId + revision->ordinaryValues->size());
}

[[nodiscard]] jint compactOwnerScanRelativeUnknownSafe(
        const OperationContext &context, jint requestedType, jint predicate,
        jint compareTarget,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Unknown) {
        return kNoSession;
    }
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        return kInvalidRequest;
    }
    if (context.nextId == 0U ||
        context.nextId > kRawCandidateIdMax - kCandidateLimit) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }

    std::vector<Query> queries;
    std::vector<jlmem::v2::RelativeQueryPlan> plans;
    if (!buildCanonicalRelativePlans(
                requestedType, predicate, first, second,
                queries, plans)) {
        setMessage("Invalid relative value, type, or predicate");
        return kInvalidRequest;
    }

    std::vector<Query> capturedQueries;
    std::vector<jlmem::v2::RelativeQueryPlan> capturedPlans;
    capturedQueries.reserve(queries.size());
    capturedPlans.reserve(plans.size());
    for (std::size_t index = 0U; index < queries.size(); ++index) {
        if (compactOwnerUnknownCaptureAllows(
                    context.state->requestedType, queries[index].type)) {
            capturedQueries.push_back(queries[index]);
            capturedPlans.push_back(plans[index]);
        }
    }
    queries.swap(capturedQueries);
    plans.swap(capturedPlans);

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

    if (plans.empty()) {
        CompactOwnerScanBuild build;
        build.firstId = context.nextId;
        if (!build.ordinary.reserve(kCompactOwnerInitialReserve)) {
            setMessage("Compact relative metadata could not be reserved");
            return kResourceLimit;
        }
        const auto explicitPlane = requestedType == kTypeAuto
                                           ? jlmem::v2::ResultPlane::Count
                                           : jlmem::v2::resultPlaneFromStableValueType(requestedType)
                                                     .value_or(jlmem::v2::ResultPlane::Count);
        auto revision = compactOwnerMakeRevision(
                requestedType, explicitPlane, jlmem::v2::ResultStore{},
                std::move(build.ordinary));
        if (revision == nullptr) return kResourceLimit;
        auto next = std::make_shared<SearchState>();
        next->mode = StateMode::Candidates;
        next->requestedType = requestedType;
        next->logicalCount = 0U;
        next->candidateOrderDirty = false;
        next->watches = context.state->watches;
        return commitCompactOwnerRevision(
                context, std::move(next), std::move(revision), 1, context.nextId);
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
    const bool scanned = requestedType == kTypeAuto
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
    const auto explicitPlane = requestedType == kTypeAuto
                                       ? jlmem::v2::ResultPlane::Count
                                       : plans.front().plane;
    auto revision = compactOwnerMakeRevision(
            requestedType, explicitPlane,
            std::move(store), std::move(build.ordinary));
    if (revision == nullptr) {
        setMessage("Compact relative revision could not be finalized");
        return kResourceLimit;
    }

    const std::size_t ordinaryCount = revision->ordinaryValues->size();
    if (context.nextId > kRawCandidateIdMax - ordinaryCount) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }
    const std::uint64_t nextId = context.nextId + ordinaryCount;
    const std::uint64_t logicalCount = revision->store->uniqueAddressCount();

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = requestedType;
    next->logicalCount = logicalCount;
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), 1, nextId);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwnerTyped(
        JNIEnv *env, jclass, jint valueType, jint predicate, jstring first,
        jstring second, jboolean allowRelocationReconcile) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        if (context.state->mode == StateMode::Unknown) {
            return compactOwnerScanKnownUnknownSafe(
                    context, valueType, predicate, fromJString(env, first),
                    fromJString(env, second));
        }
        return compactOwnerRefineKnownLinear(
                context, valueType, predicate, fromJString(env, first),
                fromJString(env, second), allowRelocationReconcile == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelativeV2CompactOwnerSafe(
        JNIEnv *env, jclass, jint predicate, jint compareTarget, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        if (context.state->mode == StateMode::Unknown) {
            return compactOwnerScanRelativeUnknownSafe(
                    context, context.state->requestedType, predicate, compareTarget,
                    fromJString(env, first), fromJString(env, second));
        }
        return compactOwnerRefineRelative(
                context, context.state->requestedType, predicate, compareTarget,
                fromJString(env, first), fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelativeV2CompactOwnerSafeTyped(
        JNIEnv *env, jclass, jint valueType, jint predicate, jint compareTarget,
        jstring first, jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        if (context.state->mode == StateMode::Unknown) {
            return compactOwnerScanRelativeUnknownSafe(
                    context, valueType, predicate, compareTarget,
                    fromJString(env, first), fromJString(env, second));
        }
        return compactOwnerRefineRelative(
                context, valueType, predicate, compareTarget,
                fromJString(env, first), fromJString(env, second));
    });
}
