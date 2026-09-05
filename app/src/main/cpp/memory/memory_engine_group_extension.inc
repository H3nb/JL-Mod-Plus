/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Candidate-free Group Search. A positive distance preserves the established any-order semantics;
// a negative distance is the internal encoding for ordered `::distance` syntax. The Binder shape
// remains unchanged and user-entered distances are always positive.
namespace {

struct CompactGroupMatch final {
    std::uintptr_t address = 0U;
    std::uint64_t bits = 0U;
    std::uint64_t identityHash = 0U;
    ValueType type = ValueType::Invalid;
    bool identityValid = false;
};

[[nodiscard]] bool compactGroupDecodeDistance(jint encodedDistance,
                                              bool &ordered,
                                              jint &maxDistance) noexcept {
    if (encodedDistance == std::numeric_limits<jint>::min() || encodedDistance == 0) {
        return false;
    }
    ordered = encodedDistance < 0;
    maxDistance = ordered ? -encodedDistance : encodedDistance;
    return maxDistance > 0 && maxDistance <= kMaxNearbyRadius;
}

[[nodiscard]] bool compactGroupDisplayLess(const CompactGroupMatch &left,
                                           const CompactGroupMatch &right) noexcept {
    if (left.address != right.address) {
        return left.address < right.address;
    }
    const int leftPriority = displayTypePriority(left.type);
    const int rightPriority = displayTypePriority(right.type);
    if (leftPriority != rightPriority) {
        return leftPriority < rightPriority;
    }
    return toJint(left.type) < toJint(right.type);
}

[[nodiscard]] bool compactGroupSameTypedAddress(const CompactGroupMatch &left,
                                                const CompactGroupMatch &right) noexcept {
    return left.address == right.address && left.type == right.type;
}

[[nodiscard]] jint compactOwnerScanGroup(
        const OperationContext &context, const std::vector<jint> &types,
        const std::vector<std::string> &values, jint encodedDistance) {
    bool ordered = false;
    jint maxDistance = 0;
    if (!compactGroupDecodeDistance(encodedDistance, ordered, maxDistance) ||
        types.size() < 2U || types.size() > 8U || values.size() != types.size() ||
        context.nextId == 0U ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() - kCandidateLimit) {
        setMessage("Group Search requires 2-8 typed values within 1-4096 bytes");
        return kInvalidRequest;
    }

    std::vector<Query> queries;
    queries.reserve(types.size());
    for (std::size_t index = 0U; index < types.size(); ++index) {
        if (types[index] == kTypeAuto) {
            setMessage("Group Search requires an exact type for every value");
            return kInvalidRequest;
        }
        Query query;
        if (!parseQuery(types[index], kEqual, values[index], "", query)) {
            setMessage("A Group Search value does not fit its selected type");
            return kInvalidRequest;
        }
        queries.push_back(query);
    }

    // Intermediate matches are lightweight and transactional. They intentionally do not allocate
    // CandidateIds or heavyweight tracked state; only final group members enter ResultStore.
    std::vector<std::vector<CompactGroupMatch>> matches(queries.size());
    std::size_t totalMatches = 0U;
    std::vector<std::uint8_t> buffer;
    beginScanProgress(context.target);
    for (const Range &range : context.target.ranges) {
        for (std::uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (isCancelled(context)) {
                setMessage("Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const std::size_t remaining =
                    static_cast<std::size_t>(range.end - chunkStart);
            const std::size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            if (!readExact(context.target.pid, chunkStart, buffer.data(), chunkSize)) {
                setMessage("A target range changed during Group Search");
                return kTargetLost;
            }

            for (std::size_t queryIndex = 0U; queryIndex < queries.size(); ++queryIndex) {
                const Query &query = queries[queryIndex];
                const std::size_t width = widthOf(query.type);
                std::uintptr_t address = chunkStart;
                const std::size_t misalignment =
                        static_cast<std::size_t>(address % width);
                if (misalignment != 0U) {
                    if (address > std::numeric_limits<std::uintptr_t>::max() -
                                          (width - misalignment)) {
                        continue;
                    }
                    address += width - misalignment;
                }
                while (address >= chunkStart) {
                    const std::size_t offset =
                            static_cast<std::size_t>(address - chunkStart);
                    if (offset + width > chunkSize) {
                        break;
                    }
                    const std::uint64_t bits = loadBits(buffer.data() + offset, width);
                    if (matchesKnown(bits, query, kEqual)) {
                        if (totalMatches >= kCandidateLimit) {
                            setMessage("Group Search intermediate matches exceed the resource limit");
                            return kResourceLimit;
                        }
                        ++totalMatches;
                        std::uint64_t hash = 0U;
                        const bool identityValid = snapshotIdentity(
                                buffer.data(), chunkSize, offset, width, hash);
                        try {
                            matches[queryIndex].push_back(
                                    {address, bits, hash, query.type, identityValid});
                        } catch (...) {
                            setMessage("Group Search intermediate state exceeds the memory budget");
                            return kResourceLimit;
                        }
                    }
                    if (address > std::numeric_limits<std::uintptr_t>::max() - width) {
                        break;
                    }
                    address += width;
                }
            }
            advanceScanProgress(chunkSize);
            chunkStart += chunkSize;
        }
    }

    if (std::any_of(matches.begin(), matches.end(),
                    [](const auto &term) { return term.empty(); })) {
        // A missing term makes the complete group result empty. Publish an empty compact revision
        // rather than retaining stale results or special-casing the UI.
        CompactOwnerOutputBuilder emptyOutput(0U);
        jlmem::v2::ResultStore emptyStore;
        jlmem::v2::OrdinaryResultStore emptyOrdinary;
        if (!emptyOutput.finish(emptyStore, emptyOrdinary)) {
            return kResourceLimit;
        }
        auto revision = compactOwnerMakeRevision(
                kTypeAuto, jlmem::v2::ResultPlane::Count,
                std::move(emptyStore), std::move(emptyOrdinary));
        if (revision == nullptr) {
            return kResourceLimit;
        }
        auto next = std::make_shared<SearchState>();
        next->mode = StateMode::Candidates;
        next->requestedType = kTypeAuto;
        next->logicalCount = 0U;
        next->candidateOrderDirty = false;
        next->watches = context.state->watches;
        return commitCompactOwnerRevision(
                context, std::move(next), std::move(revision), -1,
                context.nextId);
    }

    std::vector<CompactGroupMatch> retained;
    bool retainedLimitReached = false;
    const auto retainGroup = [&](const std::vector<CompactGroupMatch> &group) {
        if (retainedLimitReached) {
            return;
        }
        if (group.size() > kCandidateLimit - retained.size()) {
            retainedLimitReached = true;
            return;
        }
        try {
            retained.insert(retained.end(), group.begin(), group.end());
        } catch (...) {
            retainedLimitReached = true;
        }
    };

    if (ordered) {
        // Ordered groups are anchored by term 0. Every later term must occur strictly after the
        // previous term and no farther than maxDistance bytes from the first anchor. Values need
        // not be contiguous; same-address aliases cannot satisfy two ordered positions.
        for (const CompactGroupMatch &anchor : matches.front()) {
            if (retainedLimitReached) {
                break;
            }
            const std::uintptr_t maximum =
                    anchor.address > std::numeric_limits<std::uintptr_t>::max() -
                                             static_cast<std::uintptr_t>(maxDistance)
                            ? std::numeric_limits<std::uintptr_t>::max()
                            : anchor.address + static_cast<std::uintptr_t>(maxDistance);
            std::vector<CompactGroupMatch> group;
            try {
                group.reserve(matches.size());
                group.push_back(anchor);
            } catch (...) {
                retainedLimitReached = true;
                break;
            }
            std::uintptr_t previous = anchor.address;
            bool complete = true;
            for (std::size_t term = 1U; term < matches.size(); ++term) {
                const auto found = std::upper_bound(
                        matches[term].begin(), matches[term].end(), previous,
                        [](std::uintptr_t address, const CompactGroupMatch &match) {
                            return address < match.address;
                        });
                if (found == matches[term].end() || found->address > maximum) {
                    complete = false;
                    break;
                }
                group.push_back(*found);
                previous = found->address;
            }
            if (complete) {
                retainGroup(group);
            }
        }
    } else {
        // Preserve the established any-order behavior exactly: term 0 is the semantic anchor and
        // every other term may occur anywhere in a symmetric +/- maxDistance window. The rarest
        // term only prefilters anchors so common first-term matches do not dominate CPU time.
        std::size_t rarestTerm = 0U;
        for (std::size_t index = 1U; index < matches.size(); ++index) {
            if (matches[index].size() < matches[rarestTerm].size()) {
                rarestTerm = index;
            }
        }
        const auto retainForFirstAnchor = [&](const CompactGroupMatch &anchor) {
            if (retainedLimitReached) {
                return;
            }
            std::vector<CompactGroupMatch> group;
            try {
                group.reserve(matches.size());
                group.push_back(anchor);
            } catch (...) {
                retainedLimitReached = true;
                return;
            }
            const std::uintptr_t minimum =
                    anchor.address < static_cast<std::uintptr_t>(maxDistance)
                            ? 0U
                            : anchor.address - static_cast<std::uintptr_t>(maxDistance);
            const std::uintptr_t maximum =
                    anchor.address > std::numeric_limits<std::uintptr_t>::max() -
                                             static_cast<std::uintptr_t>(maxDistance)
                            ? std::numeric_limits<std::uintptr_t>::max()
                            : anchor.address + static_cast<std::uintptr_t>(maxDistance);
            for (std::size_t term = 1U; term < matches.size(); ++term) {
                const auto found = std::lower_bound(
                        matches[term].begin(), matches[term].end(), minimum,
                        [](const CompactGroupMatch &match, std::uintptr_t address) {
                            return match.address < address;
                        });
                if (found == matches[term].end() || found->address > maximum) {
                    return;
                }
                group.push_back(*found);
            }
            retainGroup(group);
        };

        if (rarestTerm == 0U) {
            for (const CompactGroupMatch &anchor : matches.front()) {
                retainForFirstAnchor(anchor);
                if (retainedLimitReached) {
                    break;
                }
            }
        } else {
            std::unordered_set<std::uintptr_t> visitedFirstAnchors;
            for (const CompactGroupMatch &rare : matches[rarestTerm]) {
                const std::uintptr_t minimum =
                        rare.address < static_cast<std::uintptr_t>(maxDistance)
                                ? 0U
                                : rare.address - static_cast<std::uintptr_t>(maxDistance);
                const std::uintptr_t maximum =
                        rare.address > std::numeric_limits<std::uintptr_t>::max() -
                                               static_cast<std::uintptr_t>(maxDistance)
                                ? std::numeric_limits<std::uintptr_t>::max()
                                : rare.address + static_cast<std::uintptr_t>(maxDistance);
                auto first = std::lower_bound(
                        matches.front().begin(), matches.front().end(), minimum,
                        [](const CompactGroupMatch &match, std::uintptr_t address) {
                            return match.address < address;
                        });
                while (first != matches.front().end() && first->address <= maximum) {
                    if (visitedFirstAnchors.insert(first->address).second) {
                        retainForFirstAnchor(*first);
                        if (retainedLimitReached) {
                            break;
                        }
                    }
                    ++first;
                }
                if (retainedLimitReached) {
                    break;
                }
            }
        }
    }

    if (retainedLimitReached) {
        setMessage("Complete Group Search results exceed the resource limit");
        return kResourceLimit;
    }

    std::sort(retained.begin(), retained.end(), compactGroupDisplayLess);
    // Merge duplicate typed membership from overlapping valid groups while keeping the strongest
    // already-captured passive identity fingerprint.
    std::vector<CompactGroupMatch> unique;
    try {
        unique.reserve(retained.size());
        for (const CompactGroupMatch &match : retained) {
            if (!unique.empty() && compactGroupSameTypedAddress(unique.back(), match)) {
                if (!unique.back().identityValid && match.identityValid) {
                    unique.back().identityHash = match.identityHash;
                    unique.back().identityValid = true;
                }
                continue;
            }
            unique.push_back(match);
        }
    } catch (...) {
        setMessage("Group Search result materialization exceeds the memory budget");
        return kResourceLimit;
    }
    std::vector<CompactGroupMatch>().swap(retained);

    if (unique.size() > kCandidateLimit ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() - unique.size()) {
        setMessage("Complete Group Search results exceed the resource limit");
        return kResourceLimit;
    }

    CompactOwnerOutputBuilder output(unique.size());
    std::size_t ordinal = 0U;
    for (CompactGroupMatch &match : unique) {
        const auto plane = jlmem::v2::resultPlaneFromStableValueType(toJint(match.type));
        if (!plane.has_value()) {
            return kInvalidRequest;
        }
        if (!match.identityValid) {
            match.identityValid = readIdentity(
                    context.target, match.address, match.type, match.identityHash);
        }
        const jlmem::v2::OrdinaryResultRecord record{
                context.nextId + ordinal,
                match.bits,
                match.bits,
                match.bits,
                match.identityHash,
        };
        if (!output.append(match.address, *plane, record, match.identityValid, 0U)) {
            setMessage("Group Search ResultStore could not be materialized safely");
            return kResourceLimit;
        }
        ++ordinal;
    }

    jlmem::v2::ResultStore store;
    jlmem::v2::OrdinaryResultStore ordinary;
    if (!output.finish(store, ordinary)) {
        setMessage("Group Search ResultStore could not be finalized safely");
        return kResourceLimit;
    }
    auto revision = compactOwnerMakeRevision(
            kTypeAuto, jlmem::v2::ResultPlane::Count,
            std::move(store), std::move(ordinary));
    if (revision == nullptr) {
        setMessage("Group Search compact revision could not be published safely");
        return kResourceLimit;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = kTypeAuto;
    next->logicalCount = revision->store->uniqueAddressCount();
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    const jint committed = commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), -1,
            context.nextId + unique.size());
    if (committed == kOk) {
        setMessage(ordered ? "Ordered Group Search completed"
                           : "Group Search completed");
    }
    return committed;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startGroupV2CompactOwner(
        JNIEnv *env, jclass, jintArray rawTypes, jobjectArray rawValues,
        jint encodedDistance) {
    return guardedOperation([&] {
        if (rawTypes == nullptr || rawValues == nullptr) {
            setMessage("Group Search values are missing");
            return kInvalidRequest;
        }
        const jsize count = env->GetArrayLength(rawTypes);
        if (env->ExceptionCheck() || count != env->GetArrayLength(rawValues) ||
            env->ExceptionCheck() || count < 2 || count > 8) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            setMessage("Group Search requires 2-8 typed values");
            return kInvalidRequest;
        }
        std::vector<jint> types(static_cast<std::size_t>(count));
        env->GetIntArrayRegion(rawTypes, 0, count, types.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return kInvalidRequest;
        }
        std::vector<std::string> values;
        try {
            values.reserve(static_cast<std::size_t>(count));
        } catch (...) {
            return kResourceLimit;
        }
        for (jsize index = 0; index < count; ++index) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(rawValues, index));
            if (value == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (value != nullptr) env->DeleteLocalRef(value);
                return kInvalidRequest;
            }
            values.push_back(fromJString(env, value));
            env->DeleteLocalRef(value);
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return compactOwnerScanGroup(context, types, values, encodedDistance);
    });
}
