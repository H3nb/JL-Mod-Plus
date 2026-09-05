/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Candidate-free production owner for Known/Auto and materialized Unknown results. This file is
// included after the transitional compact/predicate helpers in the same native translation unit.
// ResultStore owns address/type membership; OrdinaryResultStore owns stable ResultId/value history;
// heavyweight Candidate objects are materialized only for bounded tracked interactions.
namespace {

constexpr std::size_t kCompactOwnerInitialReserve = 16U * 1024U;
constexpr std::uint32_t kCompactOwnerOrdinalMask = (std::uint32_t{1U} << 29U) - 1U;
constexpr std::uint32_t kCompactOwnerPlaneShift = 29U;

[[nodiscard]] constexpr std::uint32_t compactOwnerPackOrdinalPlane(
        std::size_t ordinal, jlmem::v2::ResultPlane plane) noexcept {
    return static_cast<std::uint32_t>(ordinal) |
           (static_cast<std::uint32_t>(jlmem::v2::planeIndex(plane))
            << kCompactOwnerPlaneShift);
}

[[nodiscard]] constexpr std::size_t compactOwnerOrdinal(
        std::uint32_t packed) noexcept {
    return static_cast<std::size_t>(packed & kCompactOwnerOrdinalMask);
}

[[nodiscard]] constexpr jlmem::v2::ResultPlane compactOwnerPlane(
        std::uint32_t packed) noexcept {
    return static_cast<jlmem::v2::ResultPlane>(packed >> kCompactOwnerPlaneShift);
}

static_assert(kCandidateLimit <= static_cast<std::size_t>(kCompactOwnerOrdinalMask));
static_assert(jlmem::v2::kResultPlaneCount <= 7U);

[[nodiscard]] std::size_t compactOwnerAddBytes(std::size_t left,
                                               std::size_t right) noexcept {
    if (left == std::numeric_limits<std::size_t>::max() ||
        right == std::numeric_limits<std::size_t>::max() ||
        left > std::numeric_limits<std::size_t>::max() - right) {
        return std::numeric_limits<std::size_t>::max();
    }
    return left + right;
}

[[nodiscard]] std::size_t compactOwnerRevisionBytesLocked(
        const std::shared_ptr<const SearchState> &state) noexcept {
    if (state == nullptr) {
        return 0U;
    }
    for (auto it = gCompactRevisions.rbegin(); it != gCompactRevisions.rend(); ++it) {
        if (it->state.lock() == state && it->revision != nullptr &&
            it->revision->compact()) {
            return it->revision->retainedBytes();
        }
    }
    return 0U;
}

[[nodiscard]] std::size_t compactOwnerRetainedWithHistoryLocked(
        std::size_t nextStateBytes, std::size_t nextRevisionBytes) noexcept {
    std::size_t retained = compactOwnerAddBytes(nextStateBytes, nextRevisionBytes);
    for (const auto &state : gHistory) {
        retained = compactOwnerAddBytes(retained, state->retainedBytes());
        retained = compactOwnerAddBytes(retained,
                                        compactOwnerRevisionBytesLocked(state));
    }
    return retained;
}

void compactOwnerRememberLocked(
        const std::shared_ptr<const SearchState> &state,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision) {
    gCompactRevisions.erase(
            std::remove_if(
                    gCompactRevisions.begin(), gCompactRevisions.end(),
                    [&](const CompactRevisionEntry &entry) {
                        return entry.state.lock() == state;
                    }),
            gCompactRevisions.end());
    gCompactRevisions.push_back({state, revision});
}

[[nodiscard]] jint commitCompactOwnerRevision(
        const OperationContext &context, std::shared_ptr<SearchState> next,
        std::shared_ptr<const jlmem::v2::ResultRevision> revision,
        jint historyMode, std::uint64_t nextId, bool preserveLive = false) {
    if (next == nullptr || revision == nullptr || !revision->compact() ||
        revision->store == nullptr || revision->ordinaryValues == nullptr ||
        next->mode != StateMode::Candidates || !next->candidates.empty() ||
        next->candidateOrderDirty ||
        revision->store->typedCount() != revision->ordinaryValues->size() ||
        revision->store->uniqueAddressCount() != next->logicalCount) {
        setMessage("Compact ResultStore publication metadata is inconsistent");
        return kResourceLimit;
    }
    if (isCancelled(context)) {
        setMessage("Operation cancelled; previous results were preserved");
        return kCancelled;
    }
    const std::size_t stateBytes = next->retainedBytes();
    const std::size_t revisionBytes = revision->retainedBytes();
    if (compactOwnerAddBytes(stateBytes, revisionBytes) > kRetainedStateByteLimit) {
        setMessage("Completed compact revision exceeds the retained search budget");
        return kResourceLimit;
    }

    {
        // Compact revision lifetime and SearchState lifetime are published under one lock order.
        // History therefore never contains a candidate-free state whose ResultStore was evicted.
        std::lock_guard<std::mutex> stateLock(gMutex);
        if (gTarget.generation != context.target.generation ||
            gTarget.token != context.target.token) {
            gLastMessage = "MIDlet runtime changed during the compact operation";
            return kTargetLost;
        }
        if (gState != context.state) {
            gLastMessage = "Search revision changed during the compact operation";
            return kInvalidRequest;
        }
        std::lock_guard<std::mutex> compactLock(gCompactRevisionMutex);
        if (historyMode > 0 && gState->mode != StateMode::Empty) {
            gHistory.push_back(gState);
        } else if (historyMode < 0) {
            gHistory.clear();
        }
        while (!gHistory.empty() &&
               (gHistory.size() > kHistoryLimit ||
                compactOwnerRetainedWithHistoryLocked(stateBytes, revisionBytes) >
                        kRetainedStateByteLimit)) {
            gHistory.pop_front();
        }
        if (compactOwnerRetainedWithHistoryLocked(stateBytes, revisionBytes) >
            kRetainedStateByteLimit) {
            gLastMessage = "Compact search history exceeds the retained search budget";
            return kResourceLimit;
        }

        std::shared_ptr<const SearchState> published = next;
        gState = std::move(next);
        if (!preserveLive) {
            gLiveCandidates.clear();
        }
        gNextCandidateId = nextId;
        compactOwnerRememberLocked(published, revision);
        pruneCompactRevisionsLocked();
        gLastMessage.clear();
    }

    // Candidate-era paging stages are not owners of a candidate-free revision. Clear them after
    // atomic publication so Java cannot accidentally route a compact state through Candidate pages.
    clearStagedKnownResultStore();
    clearStagedAutoResultStore();
    return kOk;
}

struct CompactOwnerMissingIdentity {
    std::uint32_t ordinal = 0U;
    std::uintptr_t address = 0U;
    ValueType type = ValueType::Invalid;
};

struct CompactOwnerScanBuild {
    std::uint64_t firstId = 0U;
    jlmem::v2::OrdinaryResultStore ordinary;
    std::vector<CompactOwnerMissingIdentity> missingIdentity;
    bool limitReached = false;
};

[[nodiscard]] bool compactOwnerAppendScanRecord(
        CompactOwnerScanBuild &build, std::uintptr_t address, ValueType type,
        std::uint64_t initialBits, std::uint64_t currentBits,
        const std::uint8_t *identityBytes, std::size_t identitySize,
        std::size_t identityOffset, std::size_t width) {
    if (address == 0U || type == ValueType::Invalid || width != widthOf(type) ||
        build.ordinary.size() >= kCandidateLimit ||
        build.ordinary.size() > static_cast<std::size_t>(kCompactOwnerOrdinalMask) ||
        build.firstId == 0U ||
        build.firstId > std::numeric_limits<std::uint64_t>::max() -
                                build.ordinary.size()) {
        build.limitReached = true;
        return false;
    }
    const std::size_t ordinal = build.ordinary.size();
    std::uint64_t hash = 0U;
    const bool identityValid =
            identityBytes != nullptr &&
            snapshotIdentity(identityBytes, identitySize, identityOffset, width, hash);
    if (!build.ordinary.append(
                {build.firstId + ordinal, initialBits, initialBits, currentBits, hash},
                identityValid)) {
        build.limitReached = true;
        return false;
    }
    if (!identityValid) {
        if (ordinal > std::numeric_limits<std::uint32_t>::max()) {
            build.limitReached = true;
            return false;
        }
        try {
            build.missingIdentity.push_back(
                    {static_cast<std::uint32_t>(ordinal), address, type});
        } catch (...) {
            build.limitReached = true;
            return false;
        }
    }
    return true;
}

[[nodiscard]] bool compactOwnerKnownMatch(
        void *opaque, const jlmem::v2::KnownScanMatchView &match) {
    auto *build = static_cast<CompactOwnerScanBuild *>(opaque);
    if (build == nullptr || match.plane == jlmem::v2::ResultPlane::Count) {
        return false;
    }
    const auto type = valueTypeFromJint(
            jlmem::v2::stableValueTypeFromResultPlane(match.plane));
    return type.has_value() && compactOwnerAppendScanRecord(
            *build, match.address, *type, match.bits, match.bits,
            match.chunkBytes, match.chunkSize, match.chunkOffset, match.width);
}

[[nodiscard]] bool compactOwnerRelativeMatch(
        void *opaque, const jlmem::v2::RelativeScanMatchView &match) {
    auto *build = static_cast<CompactOwnerScanBuild *>(opaque);
    if (build == nullptr || match.plane == jlmem::v2::ResultPlane::Count) {
        return false;
    }
    const auto type = valueTypeFromJint(
            jlmem::v2::stableValueTypeFromResultPlane(match.plane));
    return type.has_value() && compactOwnerAppendScanRecord(
            *build, match.address, *type, match.initialBits, match.currentBits,
            match.baselineBytes, match.baselineSize, match.baselineOffset,
            match.width);
}

void compactOwnerAdvanceProgress(void *, std::size_t bytes) {
    advanceScanProgress(bytes);
}

void compactOwnerFillMissingIdentities(const Target &target,
                                       CompactOwnerScanBuild &build) {
    for (const CompactOwnerMissingIdentity &missing : build.missingIdentity) {
        std::uint64_t hash = 0U;
        const bool valid = readIdentity(target, missing.address, missing.type, hash);
        (void) build.ordinary.updateIdentity(missing.ordinal, hash, valid);
    }
    std::vector<CompactOwnerMissingIdentity>().swap(build.missingIdentity);
}

[[nodiscard]] std::shared_ptr<const jlmem::v2::ResultRevision>
compactOwnerMakeRevision(
        jint requestedType, jlmem::v2::ResultPlane explicitPlane,
        jlmem::v2::ResultStore store, jlmem::v2::OrdinaryResultStore ordinary) {
    if (!ordinary.finalizeIdIndex() || store.typedCount() != ordinary.size()) {
        return nullptr;
    }
    auto revision = std::make_shared<jlmem::v2::ResultRevision>();
    revision->kind = requestedType == kTypeAuto
                             ? jlmem::v2::ResultRevisionKind::Auto
                             : jlmem::v2::ResultRevisionKind::Explicit;
    revision->plane = requestedType == kTypeAuto
                              ? jlmem::v2::ResultPlane::Count
                              : explicitPlane;
    revision->store =
            std::make_shared<const jlmem::v2::ResultStore>(std::move(store));
    revision->checkpoints =
            std::make_shared<const std::vector<jlmem::v2::ResultCursor>>();
    revision->candidateOffsets.reset();
    revision->ordinaryValues =
            std::make_shared<const jlmem::v2::OrdinaryResultStore>(
                    std::move(ordinary));
    return revision->compact() ? revision : nullptr;
}

[[nodiscard]] jint compactOwnerScanKnown(
        const OperationContext &context, const Target &identityTarget,
        jint requestedType, jint predicate,
        const std::string &first, const std::string &second) {
    if (context.nextId == 0U ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() -
                                 kCandidateLimit) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }

    std::vector<jlmem::v2::ScanRange> ranges;
    ranges.reserve(context.target.ranges.size());
    for (const Range &range : context.target.ranges) {
        ranges.push_back({range.start, range.end});
    }

    CompactOwnerScanBuild build;
    build.firstId = context.nextId;
    if (!build.ordinary.reserve(kCompactOwnerInitialReserve)) {
        setMessage("Compact first-scan metadata could not be reserved");
        return kResourceLimit;
    }
    jlmem::v2::ResultStore store;
    jlmem::v2::KnownScanStats stats;
    std::string error;
    beginScanProgress(context.target);
    const jlmem::v2::KnownScanObserver observer{
            &build, compactOwnerKnownMatch, compactOwnerAdvanceProgress};

    jlmem::v2::ResultPlane explicitPlane = jlmem::v2::ResultPlane::Count;
    bool scanned = false;
    if (requestedType == kTypeAuto) {
        std::vector<Query> queries;
        if (!buildQueries(kTypeAuto, predicate, first, second, false, queries)) {
            setMessage("Invalid Auto value or predicate");
            return kInvalidRequest;
        }
        std::vector<jlmem::v2::KnownScanRequest> plans;
        plans.reserve(queries.size());
        for (const Query &query : queries) {
            const auto plan = jlmem::v2::knownQueryPlanFromStableValues(
                    toJint(query.type), predicate,
                    canonicalKnownBits(query, false),
                    predicate == kBetween ? canonicalKnownBits(query, true) : 0U);
            if (!plan.has_value()) {
                setMessage("Auto query could not be canonicalized safely");
                return kInvalidRequest;
            }
            plans.push_back(*plan);
        }
        scanned = jlmem::v2::scanKnownAuto(
                ranges, plans,
                [&](std::uintptr_t address, void *buffer, std::size_t size) {
                    return readExact(context.target.pid, address, buffer, size);
                },
                [&] { return isCancelled(context); }, store, stats, error,
                &observer);
    } else {
        const auto plan = parseCanonicalKnownPlan(
                requestedType, predicate, first, second);
        if (!plan.has_value()) {
            setMessage("Invalid value, type, or predicate");
            return kInvalidRequest;
        }
        explicitPlane = plan->plane;
        scanned = jlmem::v2::scanKnownExplicit(
                ranges, *plan,
                [&](std::uintptr_t address, void *buffer, std::size_t size) {
                    return readExact(context.target.pid, address, buffer, size);
                },
                [&] { return isCancelled(context); }, store, stats, error,
                &observer);
    }

    if (!scanned) {
        if (build.limitReached) {
            setMessage("Complete compact search exceeds the typed-result limit");
            return kResourceLimit;
        }
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        if (error.find("Target range changed") != std::string::npos) {
            setMessage("A target range changed while it was being scanned");
            return kTargetLost;
        }
        setMessage(error.empty() ? "Compact first scan could not be completed safely"
                                 : error.c_str());
        return kResourceLimit;
    }
    if (stats.typedMatches != build.ordinary.size() ||
        store.typedCount() != build.ordinary.size() ||
        stats.uniqueAddresses != store.uniqueAddressCount()) {
        setMessage("Compact first-scan membership and value metadata diverged");
        return kResourceLimit;
    }

    compactOwnerFillMissingIdentities(identityTarget, build);
    auto revision = compactOwnerMakeRevision(
            requestedType, explicitPlane, std::move(store),
            std::move(build.ordinary));
    if (revision == nullptr) {
        setMessage("Compact first-scan revision could not be finalized");
        return kResourceLimit;
    }
    if (context.nextId > std::numeric_limits<std::uint64_t>::max() -
                                 revision->ordinaryValues->size()) {
        setMessage("ResultId space is exhausted for this runtime");
        return kResourceLimit;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = requestedType;
    next->logicalCount = revision->store->uniqueAddressCount();
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    const std::uint64_t nextId =
            context.nextId + revision->ordinaryValues->size();
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), -1, nextId);
}

class CompactOwnerValueReader final {
  public:
    CompactOwnerValueReader(const Target &target, std::size_t resultCount)
        : target_(target), direct_(resultCount <= kDirectRefineLimit) {}

    [[nodiscard]] bool read(std::uintptr_t address, ValueType type,
                            std::uint64_t &bits) {
        const std::size_t width = widthOf(type);
        std::uintptr_t valueEnd = 0U;
        if (width == 0U || !checkedAddressAdd(address, width, valueEnd)) {
            return false;
        }
        if (direct_) {
            bits = 0U;
            return readExact(target_.pid, address, &bits, width);
        }
        if (address >= cachedStart_ && valueEnd <= cachedEnd_) {
            if (!cacheReadable_) {
                return false;
            }
            bits = loadBits(buffer_.data() + address - cachedStart_, width);
            return true;
        }
        const auto range = std::upper_bound(
                target_.ranges.begin(), target_.ranges.end(), address,
                [](std::uintptr_t value, const Range &item) {
                    return value < item.start;
                });
        if (range == target_.ranges.begin()) {
            return false;
        }
        const Range &containing = *std::prev(range);
        if (address < containing.start || valueEnd > containing.end) {
            return false;
        }
        const std::uintptr_t offset = address - containing.start;
        cachedStart_ = containing.start +
                       offset / kReadChunkSize * kReadChunkSize;
        const std::size_t chunkSize = static_cast<std::size_t>(
                std::min<std::uintptr_t>(kReadChunkSize,
                                         containing.end - cachedStart_));
        cachedEnd_ = cachedStart_ + chunkSize;
        buffer_.resize(chunkSize);
        if (!readExact(target_.pid, cachedStart_, buffer_.data(), chunkSize)) {
            cacheReadable_ = false;
            return false;
        }
        cacheReadable_ = true;
        bits = loadBits(buffer_.data() + address - cachedStart_, width);
        return true;
    }

  private:
    const Target &target_;
    bool direct_ = true;
    std::vector<std::uint8_t> buffer_;
    std::uintptr_t cachedStart_ = 0U;
    std::uintptr_t cachedEnd_ = 0U;
    bool cacheReadable_ = false;
};

struct CompactOwnerSourceMeta {
    std::uint64_t initialBits = 0U;
    std::uint64_t currentBits = 0U;
    std::uint64_t identityHash = 0U;
    std::uint16_t relocationCount = 0U;
    bool identityValid = false;
};

[[nodiscard]] CompactOwnerSourceMeta compactOwnerSourceMeta(
        const OperationContext &context,
        const jlmem::v2::OrdinaryResultStore &ordinary,
        std::size_t ordinal, const jlmem::v2::ResultAliasRow &row,
        bool requireSameAddressForLive) {
    CompactOwnerSourceMeta meta;
    const auto *record = ordinary.record(ordinal);
    if (record == nullptr) {
        return meta;
    }
    meta.initialBits = record->initialBits;
    meta.currentBits = record->currentBits;
    meta.identityHash = record->identityHash;
    meta.relocationCount = ordinary.relocationCount(ordinal);
    meta.identityValid = ordinary.identityValid(ordinal);
    const auto live = context.liveCandidates.find(record->id);
    if (live == context.liveCandidates.end()) {
        return meta;
    }
    const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(row.plane);
    if (toJint(live->second.type) != rawType ||
        (requireSameAddressForLive && live->second.address != row.address)) {
        return meta;
    }
    meta.initialBits = live->second.initialBits;
    meta.currentBits = live->second.currentBits;
    meta.identityHash = live->second.identityHash;
    meta.identityValid = live->second.identityValid;
    meta.relocationCount = static_cast<std::uint16_t>(std::min<std::uint32_t>(
            live->second.relocationCount,
            std::numeric_limits<std::uint16_t>::max()));
    return meta;
}

[[nodiscard]] std::uint64_t compactOwnerWorkBytes(
        const jlmem::v2::ResultStore &store) noexcept {
    std::uint64_t total = 0U;
    for (const jlmem::v2::ResultBlockHeader &header : store.headers()) {
        for (std::size_t index = 0U; index < jlmem::v2::kResultPlaneCount; ++index) {
            const auto plane = static_cast<jlmem::v2::ResultPlane>(index);
            const std::uint64_t bytes =
                    static_cast<std::uint64_t>(header.counts[index]) *
                    jlmem::v2::planeAlignment(plane);
            if (total > std::numeric_limits<std::uint64_t>::max() - bytes) {
                return std::numeric_limits<std::uint64_t>::max();
            }
            total += bytes;
        }
    }
    return total;
}

[[nodiscard]] bool compactOwnerClearMembership(
        jlmem::v2::ResultStore &store, std::size_t &blockHint,
        const jlmem::v2::ResultAliasRow &row) {
    const std::uintptr_t blockBase =
            row.address &
            ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
              std::uintptr_t{1U});
    const auto headers = store.headers();
    while (blockHint < headers.size() &&
           headers[blockHint].baseAddress < blockBase) {
        ++blockHint;
    }
    if (blockHint >= headers.size() ||
        headers[blockHint].baseAddress != blockBase) {
        return false;
    }
    const std::size_t alignment = jlmem::v2::planeAlignment(row.plane);
    const std::size_t byteOffset =
            static_cast<std::size_t>(row.address - blockBase);
    return alignment != 0U && byteOffset % alignment == 0U &&
           store.clearSlot(blockHint, row.plane, byteOffset / alignment);
}

[[nodiscard]] bool compactOwnerShouldRecoverMovement(
        const OperationContext &context,
        const jlmem::v2::ResultRevision &revision,
        bool gcEpochChanged) {
    if (!gcEpochChanged || revision.ordinaryValues == nullptr ||
        revision.store == nullptr ||
        revision.ordinaryValues->size() < kRelocationReconcileMinCandidates) {
        return false;
    }
    const std::size_t count = revision.ordinaryValues->size();
    const std::size_t requested = std::min(kRelocationProbeSamples, count);
    std::size_t checked = 0U;
    std::size_t mismatches = 0U;
    for (std::size_t sample = 0U; sample < requested; ++sample) {
        const std::size_t ordinal = requested == 1U
                                            ? 0U
                                            : sample * (count - 1U) /
                                                      (requested - 1U);
        if (!revision.ordinaryValues->identityValid(ordinal)) {
            continue;
        }
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPageAtTypedOffset(
                    *revision.store, ordinal, 1U, page) ||
            page.rows.size() != 1U) {
            continue;
        }
        const auto *record = revision.ordinaryValues->record(ordinal);
        const auto type = valueTypeFromJint(
                jlmem::v2::stableValueTypeFromResultPlane(page.rows.front().plane));
        if (record == nullptr || !type.has_value()) {
            continue;
        }
        std::uint64_t liveHash = 0U;
        if (!readIdentity(context.target, page.rows.front().address, *type, liveHash) ||
            liveHash != record->identityHash) {
            ++mismatches;
        }
        ++checked;
    }
    return relocationEvidenceIsStrong(count, gcEpochChanged, checked, mismatches);
}

struct CompactOwnerDirectSurvivor {
    std::uintptr_t address = 0U;
    std::uint64_t currentBits = 0U;
    std::uint32_t packed = 0U;
};

struct CompactOwnerRecoveryEntry {
    std::uintptr_t foundAddress = 0U;
    std::uint64_t foundBits = 0U;
    std::uint32_t packed = 0U;
    std::uint8_t foundCount = 0U;
};

static_assert(sizeof(CompactOwnerRecoveryEntry) <= 24U,
              "bulk relocation staging must remain much smaller than Candidate");

[[nodiscard]] bool compactOwnerRecoveryKeyLess(
        const CompactOwnerRecoveryEntry &entry,
        const std::pair<jlmem::v2::ResultPlane, std::uint64_t> &key,
        const jlmem::v2::OrdinaryResultStore &ordinary) {
    const auto plane = compactOwnerPlane(entry.packed);
    const std::size_t leftPlane = jlmem::v2::planeIndex(plane);
    const std::size_t rightPlane = jlmem::v2::planeIndex(key.first);
    if (leftPlane != rightPlane) {
        return leftPlane < rightPlane;
    }
    const auto *record = ordinary.record(compactOwnerOrdinal(entry.packed));
    return record != nullptr && record->identityHash < key.second;
}

[[nodiscard]] bool compactOwnerSameRecoveryKey(
        const CompactOwnerRecoveryEntry &left,
        const CompactOwnerRecoveryEntry &right,
        const jlmem::v2::OrdinaryResultStore &ordinary) {
    if (compactOwnerPlane(left.packed) != compactOwnerPlane(right.packed)) {
        return false;
    }
    const auto *leftRecord = ordinary.record(compactOwnerOrdinal(left.packed));
    const auto *rightRecord = ordinary.record(compactOwnerOrdinal(right.packed));
    return leftRecord != nullptr && rightRecord != nullptr &&
           leftRecord->identityHash == rightRecord->identityHash;
}

class CompactOwnerOutputBuilder final {
  public:
    explicit CompactOwnerOutputBuilder(std::size_t reserveCount) {
        valid_ = ordinary_.reserve(reserveCount);
    }

    [[nodiscard]] bool append(
            std::uintptr_t address, jlmem::v2::ResultPlane plane,
            const jlmem::v2::OrdinaryResultRecord &record, bool identityValid,
            std::uint16_t relocationCount) {
        if (!valid_ || address == 0U || plane == jlmem::v2::ResultPlane::Count) {
            return false;
        }
        const std::size_t priority = jlmem::v2::resultAliasDisplayPriority(plane);
        if (havePrevious_ &&
            (address < previousAddress_ ||
             (address == previousAddress_ && priority <= previousPriority_))) {
            return false;
        }
        const std::uintptr_t blockBase =
                address &
                ~(static_cast<std::uintptr_t>(jlmem::v2::kResultLogicalBlockSize) -
                  std::uintptr_t{1U});
        if (!scratchActive_ || blockBase != scratchBase_) {
            if (!flush()) {
                return false;
            }
            scratchBase_ = blockBase;
            scratchActive_ = true;
        }
        const std::size_t alignment = jlmem::v2::planeAlignment(plane);
        const std::size_t byteOffset = static_cast<std::size_t>(address - blockBase);
        if (alignment == 0U || byteOffset % alignment != 0U ||
            !scratch_.set(plane, byteOffset / alignment) ||
            !ordinary_.append(record, identityValid, relocationCount)) {
            valid_ = false;
            return false;
        }
        previousAddress_ = address;
        previousPriority_ = priority;
        havePrevious_ = true;
        return true;
    }

    [[nodiscard]] bool finish(jlmem::v2::ResultStore &store,
                              jlmem::v2::OrdinaryResultStore &ordinary) {
        if (!valid_ || !flush() || !ordinary_.finalizeIdIndex() ||
            store_.typedCount() != ordinary_.size()) {
            return false;
        }
        store = std::move(store_);
        ordinary = std::move(ordinary_);
        return true;
    }

  private:
    [[nodiscard]] bool flush() {
        if (!scratchActive_) {
            return true;
        }
        if (scratch_.empty() ||
            !store_.appendNonEmptyBlock(scratchBase_, scratch_)) {
            valid_ = false;
            return false;
        }
        scratch_.reset();
        scratchActive_ = false;
        scratchBase_ = 0U;
        return true;
    }

    bool valid_ = true;
    bool scratchActive_ = false;
    bool havePrevious_ = false;
    std::uintptr_t scratchBase_ = 0U;
    std::uintptr_t previousAddress_ = 0U;
    std::size_t previousPriority_ = 0U;
    jlmem::v2::ResultStore store_;
    jlmem::v2::OrdinaryResultStore ordinary_;
    jlmem::v2::ResultBlockScratch scratch_;
};

[[nodiscard]] jint compactOwnerRecoverKnown(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &source,
        jint predicate,
        const std::array<const Query *, kTypeSlotCount> &queriesByType,
        std::uint64_t candidateWork,
        std::shared_ptr<const jlmem::v2::ResultRevision> &published,
        std::uint64_t &logicalCount, std::size_t &relocatedCount) {
    std::vector<CompactOwnerRecoveryEntry> recovery;
    std::vector<CompactOwnerDirectSurvivor> direct;
    try {
        recovery.reserve(std::min(source->ordinaryValues->size(),
                                  kInitialRecoveryIndexReserve));
        direct.reserve(kInitialKnownRefineReserve);
    } catch (...) {
        return kResourceLimit;
    }

    CompactOwnerValueReader reader(context.target, source->ordinaryValues->size());
    jlmem::v2::ResultAliasCursor cursor;
    std::size_t ordinal = 0U;
    std::uint64_t completed = 0U;
    while (ordinal < source->ordinaryValues->size()) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                source->ordinaryValues->size() - ordinal);
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*source->store, cursor, limit, page) ||
            page.rows.size() != limit) {
            setMessage("Compact source cursor diverged during relocation staging");
            return kResourceLimit;
        }
        for (const auto &row : page.rows) {
            const auto *record = source->ordinaryValues->record(ordinal);
            const auto type = valueTypeFromJint(
                    jlmem::v2::stableValueTypeFromResultPlane(row.plane));
            if (record == nullptr || !type.has_value()) {
                return kResourceLimit;
            }
            const Query *query = queriesByType[typeIndex(*type)];
            if (query != nullptr) {
                if (source->ordinaryValues->identityValid(ordinal)) {
                    if (ordinal > kCompactOwnerOrdinalMask) {
                        return kResourceLimit;
                    }
                    recovery.push_back({0U, 0U,
                                        compactOwnerPackOrdinalPlane(ordinal, row.plane),
                                        0U});
                } else {
                    std::uint64_t current = 0U;
                    if (reader.read(row.address, *type, current) &&
                        matchesKnown(current, *query, predicate)) {
                        direct.push_back({
                                row.address, current,
                                compactOwnerPackOrdinalPlane(ordinal, row.plane)});
                    }
                }
            }
            completed += widthOf(*type);
            ++ordinal;
        }
        cursor = page.next;
        gScanBytesScanned.store(std::min(completed, candidateWork),
                                std::memory_order_release);
    }

    std::sort(recovery.begin(), recovery.end(),
              [&](const CompactOwnerRecoveryEntry &left,
                  const CompactOwnerRecoveryEntry &right) {
                  const auto leftPlane = compactOwnerPlane(left.packed);
                  const auto rightPlane = compactOwnerPlane(right.packed);
                  if (leftPlane != rightPlane) {
                      return jlmem::v2::planeIndex(leftPlane) <
                             jlmem::v2::planeIndex(rightPlane);
                  }
                  const auto *leftRecord =
                          source->ordinaryValues->record(compactOwnerOrdinal(left.packed));
                  const auto *rightRecord =
                          source->ordinaryValues->record(compactOwnerOrdinal(right.packed));
                  if (leftRecord == nullptr || rightRecord == nullptr) {
                      return left.packed < right.packed;
                  }
                  if (leftRecord->identityHash != rightRecord->identityHash) {
                      return leftRecord->identityHash < rightRecord->identityHash;
                  }
                  return compactOwnerOrdinal(left.packed) <
                         compactOwnerOrdinal(right.packed);
              });

    std::array<bool, kTypeSlotCount> wantedType{};
    for (const auto &entry : recovery) {
        const auto type = valueTypeFromJint(
                jlmem::v2::stableValueTypeFromResultPlane(
                        compactOwnerPlane(entry.packed)));
        if (type.has_value()) {
            wantedType[typeIndex(*type)] = true;
        }
    }

    const std::uint64_t heapWork = targetScanWorkBytes(context.target);
    std::uint64_t totalWork = candidateWork;
    if (heapWork == std::numeric_limits<std::uint64_t>::max() ||
        totalWork > std::numeric_limits<std::uint64_t>::max() - heapWork) {
        totalWork = std::numeric_limits<std::uint64_t>::max();
    } else {
        totalWork += heapWork;
    }
    gScanBytesTotal.store(totalWork, std::memory_order_release);

    const auto recordMatch = [&](ValueType type, jlmem::v2::ResultPlane plane,
                                 std::uintptr_t address,
                                 const std::vector<std::uint8_t> &buffer,
                                 std::size_t offset, std::size_t width,
                                 std::uint64_t bits) {
        const Query *query = queriesByType[typeIndex(type)];
        if (query == nullptr || !matchesKnown(bits, *query, predicate)) {
            return;
        }
        std::uint64_t hash = 0U;
        if (!snapshotIdentity(buffer.data(), buffer.size(), offset, width, hash) &&
            !readIdentity(context.target, address, type, hash)) {
            return;
        }
        const std::pair<jlmem::v2::ResultPlane, std::uint64_t> key{plane, hash};
        const auto found = std::lower_bound(
                recovery.begin(), recovery.end(), key,
                [&](const CompactOwnerRecoveryEntry &entry,
                    const auto &wanted) {
                    return compactOwnerRecoveryKeyLess(
                            entry, wanted, *source->ordinaryValues);
                });
        if (found == recovery.end() || compactOwnerPlane(found->packed) != plane) {
            return;
        }
        const auto *sourceRecord = source->ordinaryValues->record(
                compactOwnerOrdinal(found->packed));
        if (sourceRecord == nullptr || sourceRecord->identityHash != hash) {
            return;
        }
        const auto next = std::next(found);
        if ((found != recovery.begin() && compactOwnerSameRecoveryKey(
                                                   *std::prev(found), *found,
                                                   *source->ordinaryValues)) ||
            (next != recovery.end() && compactOwnerSameRecoveryKey(
                                               *found, *next,
                                               *source->ordinaryValues))) {
            return;
        }
        if (found->foundCount == 0U) {
            found->foundAddress = address;
            found->foundBits = bits;
            found->foundCount = 1U;
        } else if (found->foundAddress != address) {
            found->foundCount = 2U;
        }
    };

    std::vector<std::uint8_t> buffer;
    std::uint64_t scannedHeap = 0U;
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
            const bool readable =
                    readExact(context.target.pid, chunkStart, buffer.data(), chunkSize);
            if (readable) {
                for (std::size_t offset = 0U; offset < chunkSize; ++offset) {
                    const std::uintptr_t address = chunkStart + offset;
                    if ((wantedType[typeIndex(ValueType::Int)] ||
                         wantedType[typeIndex(ValueType::Float)]) &&
                        address % 4U == 0U && offset + 4U <= chunkSize) {
                        const std::uint64_t bits = loadBits(buffer.data() + offset, 4U);
                        if (wantedType[typeIndex(ValueType::Int)]) {
                            recordMatch(ValueType::Int, jlmem::v2::ResultPlane::Int,
                                        address, buffer, offset, 4U, bits);
                        }
                        if (wantedType[typeIndex(ValueType::Float)]) {
                            recordMatch(ValueType::Float, jlmem::v2::ResultPlane::Float,
                                        address, buffer, offset, 4U, bits);
                        }
                    }
                    if ((wantedType[typeIndex(ValueType::Long)] ||
                         wantedType[typeIndex(ValueType::Double)]) &&
                        address % 8U == 0U && offset + 8U <= chunkSize) {
                        const std::uint64_t bits = loadBits(buffer.data() + offset, 8U);
                        if (wantedType[typeIndex(ValueType::Long)]) {
                            recordMatch(ValueType::Long, jlmem::v2::ResultPlane::Long,
                                        address, buffer, offset, 8U, bits);
                        }
                        if (wantedType[typeIndex(ValueType::Double)]) {
                            recordMatch(ValueType::Double, jlmem::v2::ResultPlane::Double,
                                        address, buffer, offset, 8U, bits);
                        }
                    }
                    if ((wantedType[typeIndex(ValueType::Short)] ||
                         wantedType[typeIndex(ValueType::Char)]) &&
                        address % 2U == 0U && offset + 2U <= chunkSize) {
                        const std::uint64_t bits = loadBits(buffer.data() + offset, 2U);
                        if (wantedType[typeIndex(ValueType::Short)]) {
                            recordMatch(ValueType::Short, jlmem::v2::ResultPlane::Short,
                                        address, buffer, offset, 2U, bits);
                        }
                        if (wantedType[typeIndex(ValueType::Char)]) {
                            recordMatch(ValueType::Char, jlmem::v2::ResultPlane::Char,
                                        address, buffer, offset, 2U, bits);
                        }
                    }
                    if (wantedType[typeIndex(ValueType::Byte)]) {
                        recordMatch(ValueType::Byte, jlmem::v2::ResultPlane::Byte,
                                    address, buffer, offset, 1U, buffer[offset]);
                    }
                }
            }
            scannedHeap = scannedHeap > std::numeric_limits<std::uint64_t>::max() -
                                               chunkSize
                                  ? std::numeric_limits<std::uint64_t>::max()
                                  : scannedHeap + chunkSize;
            const std::uint64_t progress =
                    candidateWork > std::numeric_limits<std::uint64_t>::max() -
                                            scannedHeap
                            ? std::numeric_limits<std::uint64_t>::max()
                            : candidateWork + scannedHeap;
            gScanBytesScanned.store(std::min(progress, totalWork),
                                    std::memory_order_release);
            chunkStart += chunkSize;
        }
    }

    recovery.erase(
            std::remove_if(recovery.begin(), recovery.end(),
                           [](const CompactOwnerRecoveryEntry &entry) {
                               return entry.foundCount != 1U;
                           }),
            recovery.end());

    // Compare recovered bindings with their old membership without storing old addresses in every
    // temporary recovery record. One ordinal-ordered pass adds only a moved/not-moved bit.
    std::sort(recovery.begin(), recovery.end(),
              [](const CompactOwnerRecoveryEntry &left,
                 const CompactOwnerRecoveryEntry &right) {
                  return compactOwnerOrdinal(left.packed) <
                         compactOwnerOrdinal(right.packed);
              });
    jlmem::v2::ResultAliasCursor oldCursor;
    std::size_t oldOrdinal = 0U;
    std::size_t recoveryIndex = 0U;
    while (recoveryIndex < recovery.size()) {
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                source->ordinaryValues->size() - oldOrdinal);
        if (limit == 0U) {
            return kResourceLimit;
        }
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*source->store, oldCursor, limit, page) ||
            page.rows.size() != limit) {
            return kResourceLimit;
        }
        for (const auto &row : page.rows) {
            while (recoveryIndex < recovery.size() &&
                   compactOwnerOrdinal(recovery[recoveryIndex].packed) == oldOrdinal) {
                if (recovery[recoveryIndex].foundAddress != row.address) {
                    recovery[recoveryIndex].foundCount = 2U; // moved
                } else {
                    recovery[recoveryIndex].foundCount = 1U; // same address
                }
                ++recoveryIndex;
            }
            ++oldOrdinal;
        }
        oldCursor = page.next;
    }

    std::sort(recovery.begin(), recovery.end(),
              [](const CompactOwnerRecoveryEntry &left,
                 const CompactOwnerRecoveryEntry &right) {
                  if (left.foundAddress != right.foundAddress) {
                      return left.foundAddress < right.foundAddress;
                  }
                  return jlmem::v2::resultAliasDisplayPriority(
                                 compactOwnerPlane(left.packed)) <
                         jlmem::v2::resultAliasDisplayPriority(
                                 compactOwnerPlane(right.packed));
              });

    CompactOwnerOutputBuilder output(
            std::min<std::size_t>(source->ordinaryValues->size(), kCandidateLimit));
    std::size_t directIndex = 0U;
    std::size_t recoveredIndex = 0U;
    relocatedCount = 0U;
    std::uintptr_t previousAddress = 0U;
    jlmem::v2::ResultPlane previousPlane = jlmem::v2::ResultPlane::Count;
    bool havePrevious = false;

    const auto appendOne = [&](std::uintptr_t address,
                               jlmem::v2::ResultPlane plane,
                               std::size_t sourceOrdinal,
                               std::uint64_t currentBits,
                               bool moved) -> bool {
        const auto *record = source->ordinaryValues->record(sourceOrdinal);
        if (record == nullptr) {
            return false;
        }
        if (havePrevious && address == previousAddress && plane == previousPlane) {
            return false;
        }
        jlmem::v2::ResultAliasPage sourcePage;
        if (!jlmem::v2::readAliasPageAtTypedOffset(
                    *source->store, sourceOrdinal, 1U, sourcePage) ||
            sourcePage.rows.size() != 1U) {
            return false;
        }
        const CompactOwnerSourceMeta meta = compactOwnerSourceMeta(
                context, *source->ordinaryValues, sourceOrdinal,
                sourcePage.rows.front(), false);
        std::uint16_t relocationCount = meta.relocationCount;
        if (moved) {
            if (relocationCount != std::numeric_limits<std::uint16_t>::max()) {
                ++relocationCount;
            }
            ++relocatedCount;
        }
        const jlmem::v2::OrdinaryResultRecord updated{
                record->id, meta.initialBits, meta.currentBits, currentBits,
                meta.identityHash};
        if (!output.append(address, plane, updated, meta.identityValid,
                           relocationCount)) {
            return false;
        }
        previousAddress = address;
        previousPlane = plane;
        havePrevious = true;
        return true;
    };

    while (directIndex < direct.size() || recoveredIndex < recovery.size()) {
        bool takeDirect = recoveredIndex >= recovery.size();
        if (!takeDirect && directIndex < direct.size()) {
            const auto &directRow = direct[directIndex];
            const auto &recoveredRow = recovery[recoveredIndex];
            if (directRow.address != recoveredRow.foundAddress) {
                takeDirect = directRow.address < recoveredRow.foundAddress;
            } else {
                takeDirect = jlmem::v2::resultAliasDisplayPriority(
                                     compactOwnerPlane(directRow.packed)) <
                             jlmem::v2::resultAliasDisplayPriority(
                                     compactOwnerPlane(recoveredRow.packed));
            }
        }
        if (takeDirect) {
            const auto &item = direct[directIndex++];
            if (!appendOne(item.address, compactOwnerPlane(item.packed),
                           compactOwnerOrdinal(item.packed), item.currentBits,
                           false)) {
                setMessage("Compact relocation produced duplicate or unordered membership");
                return kResourceLimit;
            }
        } else {
            const auto &item = recovery[recoveredIndex++];
            if (!appendOne(item.foundAddress, compactOwnerPlane(item.packed),
                           compactOwnerOrdinal(item.packed), item.foundBits,
                           item.foundCount == 2U)) {
                setMessage("Compact relocation produced duplicate or unordered membership");
                return kResourceLimit;
            }
        }
    }

    jlmem::v2::ResultStore rebuiltStore;
    jlmem::v2::OrdinaryResultStore rebuiltOrdinary;
    if (!output.finish(rebuiltStore, rebuiltOrdinary)) {
        setMessage("Compact relocation revision could not be finalized");
        return kResourceLimit;
    }
    logicalCount = rebuiltStore.uniqueAddressCount();
    const jlmem::v2::ResultPlane explicitPlane =
            source->kind == jlmem::v2::ResultRevisionKind::Explicit
                    ? source->plane
                    : jlmem::v2::ResultPlane::Count;
    published = compactOwnerMakeRevision(
            context.state->requestedType, explicitPlane,
            std::move(rebuiltStore), std::move(rebuiltOrdinary));
    return published != nullptr ? kOk : kResourceLimit;
}

[[nodiscard]] jint compactOwnerRefineStable(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &source,
        jint predicate, jint compareTarget, bool relative,
        const std::array<const Query *, kTypeSlotCount> &queriesByType,
        std::shared_ptr<const jlmem::v2::ResultRevision> &published,
        std::uint64_t &logicalCount, std::size_t &unreadableCount) {
    jlmem::v2::ResultStore refined = *source->store;
    jlmem::v2::OrdinaryResultStore ordinary;
    if (!ordinary.reserve(std::min(source->ordinaryValues->size(), kCandidateLimit))) {
        return kResourceLimit;
    }
    CompactOwnerValueReader reader(context.target, source->ordinaryValues->size());
    jlmem::v2::ResultAliasCursor cursor;
    std::size_t ordinal = 0U;
    std::size_t blockHint = 0U;
    std::size_t attemptedReads = 0U;
    std::size_t readableCount = 0U;
    unreadableCount = 0U;
    const std::uint64_t totalWork = compactOwnerWorkBytes(*source->store);
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(totalWork, std::memory_order_release);
    std::uint64_t completed = 0U;

    while (ordinal < source->ordinaryValues->size()) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                source->ordinaryValues->size() - ordinal);
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*source->store, cursor, limit, page) ||
            page.rows.size() != limit) {
            return kResourceLimit;
        }
        for (const auto &row : page.rows) {
            const auto *record = source->ordinaryValues->record(ordinal);
            const auto type = valueTypeFromJint(
                    jlmem::v2::stableValueTypeFromResultPlane(row.plane));
            if (record == nullptr || !type.has_value()) {
                return kResourceLimit;
            }
            const Query *query = queriesByType[typeIndex(*type)];
            bool survives = false;
            std::uint64_t current = 0U;
            const CompactOwnerSourceMeta meta = compactOwnerSourceMeta(
                    context, *source->ordinaryValues, ordinal, row, true);
            if (query != nullptr) {
                ++attemptedReads;
                if (reader.read(row.address, *type, current)) {
                    ++readableCount;
                    if (relative) {
                        const std::uint64_t reference =
                                compareTarget == kCompareInitial
                                        ? meta.initialBits
                                        : meta.currentBits;
                        survives = matchesRelative(
                                current, reference, *query, predicate);
                    } else {
                        survives = matchesKnown(current, *query, predicate);
                    }
                } else {
                    ++unreadableCount;
                }
            }
            if (survives) {
                if (!ordinary.append(
                            {record->id, meta.initialBits, meta.currentBits,
                             current, meta.identityHash},
                            meta.identityValid, meta.relocationCount)) {
                    return kResourceLimit;
                }
            } else if (!compactOwnerClearMembership(refined, blockHint, row)) {
                return kResourceLimit;
            }
            completed = completed > std::numeric_limits<std::uint64_t>::max() -
                                          widthOf(*type)
                                ? std::numeric_limits<std::uint64_t>::max()
                                : completed + widthOf(*type);
            ++ordinal;
        }
        cursor = page.next;
        gScanBytesScanned.store(std::min(completed, totalWork),
                                std::memory_order_release);
    }

    if (attemptedReads != 0U && readableCount == 0U &&
        source->ordinaryValues->size() != 0U) {
        setMessage("The previous compact address set is no longer readable; previous results were preserved");
        return kInvalidRequest;
    }
    if (!ordinary.finalizeIdIndex() ||
        refined.typedCount() != ordinary.size()) {
        return kResourceLimit;
    }
    logicalCount = refined.uniqueAddressCount();
    const jlmem::v2::ResultPlane explicitPlane =
            source->kind == jlmem::v2::ResultRevisionKind::Explicit
                    ? source->plane
                    : jlmem::v2::ResultPlane::Count;
    published = compactOwnerMakeRevision(
            context.state->requestedType, explicitPlane,
            std::move(refined), std::move(ordinary));
    if (published == nullptr ||
        published->store->uniqueAddressCount() != logicalCount) {
        return kResourceLimit;
    }
    gScanBytesScanned.store(totalWork, std::memory_order_release);
    return kOk;
}

[[nodiscard]] jint compactOwnerRefineKnown(
        const OperationContext &context, jint predicate,
        const std::string &first, const std::string &second,
        bool allowRelocationReconcile) {
    std::shared_ptr<const jlmem::v2::ResultRevision> source;
    if (!compactRevisionForContext(context, source)) {
        return kNoSession;
    }
    std::vector<Query> queries;
    if (!buildQueries(context.state->requestedType, predicate, first, second,
                      false, queries)) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }

    const std::uint64_t candidateWork = compactOwnerWorkBytes(*source->store);
    gScanBytesScanned.store(0U, std::memory_order_release);
    gScanBytesTotal.store(candidateWork, std::memory_order_release);
    std::shared_ptr<const jlmem::v2::ResultRevision> nextRevision;
    std::uint64_t logicalCount = 0U;
    std::size_t relocatedCount = 0U;
    std::size_t unreadableCount = 0U;
    jint result = kOk;
    if (compactOwnerShouldRecoverMovement(
                context, *source, allowRelocationReconcile)) {
        result = compactOwnerRecoverKnown(
                context, source, predicate, queriesByType, candidateWork,
                nextRevision, logicalCount, relocatedCount);
    } else {
        result = compactOwnerRefineStable(
                context, source, predicate, kComparePrevious, false,
                queriesByType, nextRevision, logicalCount, unreadableCount);
    }
    if (result != kOk) {
        return result;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = logicalCount;
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    const std::size_t survivors = nextRevision->ordinaryValues->size();
    result = commitCompactOwnerRevision(
            context, std::move(next), std::move(nextRevision), 1,
            context.nextId);
    if (result == kOk) {
        const std::uint64_t finalTotal =
                gScanBytesTotal.load(std::memory_order_acquire);
        gScanBytesScanned.store(finalTotal, std::memory_order_release);
        if (relocatedCount != 0U) {
            setMessage(("Next Scan recovered " + std::to_string(relocatedCount) +
                        " uniquely relocated address aliases").c_str());
        } else if (survivors == 0U) {
            setMessage("Next Scan found no matching values");
        } else if (unreadableCount != 0U) {
            setMessage(("Next Scan discarded " + std::to_string(unreadableCount) +
                        " unreadable address aliases").c_str());
        }
    }
    return result;
}

[[nodiscard]] jint compactOwnerScanRelativeUnknown(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    if (context.state == nullptr || context.state->mode != StateMode::Unknown) {
        return kNoSession;
    }
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        return kInvalidRequest;
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
    ranges.reserve(context.state->snapshots.size());
    for (const SnapshotRun &snapshot : context.state->snapshots) {
        ranges.push_back({snapshot.start, snapshot.bytes});
    }
    if (ranges.empty()) {
        return kNoSession;
    }

    CompactOwnerScanBuild build;
    build.firstId = context.nextId;
    if (!build.ordinary.reserve(kCompactOwnerInitialReserve)) {
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
            return kResourceLimit;
        }
        if (isCancelled(context)) {
            return kCancelled;
        }
        if (error.find("Target range changed") != std::string::npos) {
            return kTargetLost;
        }
        setMessage(error.empty() ? "Compact relative scan could not be completed safely"
                                 : error.c_str());
        return kResourceLimit;
    }
    if (store.typedCount() != build.ordinary.size() ||
        stats.typedMatches != build.ordinary.size()) {
        return kResourceLimit;
    }
    compactOwnerFillMissingIdentities(context.target, build);
    const auto explicitPlane = context.state->requestedType == kTypeAuto
                                       ? jlmem::v2::ResultPlane::Count
                                       : plans.front().plane;
    auto revision = compactOwnerMakeRevision(
            context.state->requestedType, explicitPlane,
            std::move(store), std::move(build.ordinary));
    if (revision == nullptr ||
        context.nextId > std::numeric_limits<std::uint64_t>::max() -
                                 revision->ordinaryValues->size()) {
        return kResourceLimit;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = revision->store->uniqueAddressCount();
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), 1,
            context.nextId + revision->ordinaryValues->size());
}

[[nodiscard]] jint compactOwnerRefineRelative(
        const OperationContext &context, jint predicate, jint compareTarget,
        const std::string &first, const std::string &second) {
    if (compareTarget != kComparePrevious && compareTarget != kCompareInitial) {
        return kInvalidRequest;
    }
    std::shared_ptr<const jlmem::v2::ResultRevision> source;
    if (!compactRevisionForContext(context, source)) {
        return kNoSession;
    }
    std::vector<Query> queries;
    if (!buildQueries(context.state->requestedType, predicate, first, second,
                      true, queries)) {
        setMessage("Invalid relative value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }
    std::shared_ptr<const jlmem::v2::ResultRevision> nextRevision;
    std::uint64_t logicalCount = 0U;
    std::size_t unreadableCount = 0U;
    const jint refined = compactOwnerRefineStable(
            context, source, predicate, compareTarget, true,
            queriesByType, nextRevision, logicalCount, unreadableCount);
    if (refined != kOk) {
        return refined;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = logicalCount;
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    const std::size_t survivors = nextRevision->ordinaryValues->size();
    const jint result = commitCompactOwnerRevision(
            context, std::move(next), std::move(nextRevision), 1,
            context.nextId);
    if (result == kOk && survivors == 0U) {
        setMessage("Next Scan found no matching values");
    }
    return result;
}

[[nodiscard]] jint compactOwnerFilter(
        const OperationContext &context,
        const std::vector<std::uint64_t> &ids, bool keep) {
    if (ids.empty()) {
        return kInvalidRequest;
    }
    std::shared_ptr<const jlmem::v2::ResultRevision> source;
    if (!compactRevisionForContext(context, source)) {
        return kNoSession;
    }
    for (const std::uint64_t id : ids) {
        if (!source->ordinaryValues->findIndexById(id).has_value()) {
            setMessage("One or more ResultIds are not in this compact search");
            return kInvalidRequest;
        }
    }

    jlmem::v2::ResultStore filtered = *source->store;
    jlmem::v2::OrdinaryResultStore ordinary;
    if (!ordinary.reserve(source->ordinaryValues->size())) {
        return kResourceLimit;
    }
    jlmem::v2::ResultAliasCursor cursor;
    std::size_t blockHint = 0U;
    std::size_t ordinal = 0U;
    while (ordinal < source->ordinaryValues->size()) {
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                source->ordinaryValues->size() - ordinal);
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*source->store, cursor, limit, page) ||
            page.rows.size() != limit) {
            return kResourceLimit;
        }
        for (const auto &row : page.rows) {
            const auto *record = source->ordinaryValues->record(ordinal);
            if (record == nullptr) {
                return kResourceLimit;
            }
            const bool selected = std::binary_search(ids.begin(), ids.end(), record->id);
            const bool survives = keep ? selected : !selected;
            if (survives) {
                const CompactOwnerSourceMeta meta = compactOwnerSourceMeta(
                        context, *source->ordinaryValues, ordinal, row, true);
                if (!ordinary.append(
                            {record->id, meta.initialBits, record->previousBits,
                             meta.currentBits, meta.identityHash},
                            meta.identityValid, meta.relocationCount)) {
                    return kResourceLimit;
                }
            } else if (!compactOwnerClearMembership(filtered, blockHint, row)) {
                return kResourceLimit;
            }
            ++ordinal;
        }
        cursor = page.next;
    }
    if (!ordinary.finalizeIdIndex() || filtered.typedCount() != ordinary.size()) {
        return kResourceLimit;
    }
    const auto explicitPlane = source->kind == jlmem::v2::ResultRevisionKind::Explicit
                                       ? source->plane
                                       : jlmem::v2::ResultPlane::Count;
    auto revision = compactOwnerMakeRevision(
            context.state->requestedType, explicitPlane,
            std::move(filtered), std::move(ordinary));
    if (revision == nullptr) {
        return kResourceLimit;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->logicalCount = revision->store->uniqueAddressCount();
    next->candidateOrderDirty = false;
    next->watches = context.state->watches;
    return commitCompactOwnerRevision(
            context, std::move(next), std::move(revision), 1,
            context.nextId);
}

[[nodiscard]] bool compactOwnerCandidateForId(
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
        const auto type = valueTypeFromJint(
                jlmem::v2::stableValueTypeFromResultPlane(page.rows.front().plane));
        if (record == nullptr || !type.has_value()) {
            return false;
        }
        candidate.id = record->id;
        candidate.address = page.rows.front().address;
        candidate.initialBits = record->initialBits;
        candidate.previousBits = record->previousBits;
        candidate.currentBits = record->currentBits;
        candidate.identityHash = record->identityHash;
        candidate.relocationCount = revision->ordinaryValues->relocationCount(*ordinal);
        candidate.type = *type;
        candidate.state = kStable;
        candidate.identityValid = revision->ordinaryValues->identityValid(*ordinal);
        return true;
    }
    const auto watch = std::find_if(
            context.state->watches.begin(), context.state->watches.end(),
            [&](const Candidate &item) { return item.id == id; });
    if (watch != context.state->watches.end()) {
        candidate = liveCandidate(*watch, context.liveCandidates);
        return true;
    }
    return false;
}

// OrdinaryResultStore is immutable by design.  A wide write can nevertheless change the value
// observed by several compact aliases, so materialize every alias whose address falls inside the
// written span into the sparse live overlay.  Without this, resultPage() keeps serving the stale
// immutable value for an unselected sibling alias until that alias is explicitly refreshed.
void compactOwnerAppendTouchedAliasSeeds(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<WrittenSpan> &writes,
        std::vector<Candidate> &seeds) {
    if (revision == nullptr || revision->store == nullptr ||
        revision->ordinaryValues == nullptr) {
        return;
    }
    for (const WrittenSpan &write : writes) {
        if (write.address == 0U || write.width == 0U) {
            continue;
        }
        for (std::size_t offset = 0U; offset < write.width; ++offset) {
            std::uintptr_t address = 0U;
            if (!checkedAddressAdd(write.address, offset, address)) {
                break;
            }
            jlmem::v2::ResultAliasAddressLookup lookup;
            if (!jlmem::v2::lookupAliasAddress(
                        *revision->store, address, lookup)) {
                continue;
            }
            const std::size_t aliasCount = static_cast<std::size_t>(
                    std::popcount(lookup.row.aliasMask));
            if (aliasCount == 0U ||
                lookup.typedOffset >
                        static_cast<std::uint64_t>(
                                std::numeric_limits<std::size_t>::max())) {
                continue;
            }
            const std::size_t firstOrdinal =
                    static_cast<std::size_t>(lookup.typedOffset);
            if (firstOrdinal > revision->ordinaryValues->size() ||
                aliasCount > revision->ordinaryValues->size() - firstOrdinal) {
                continue;
            }
            jlmem::v2::ResultAliasPage page;
            if (!jlmem::v2::readAliasPageAtTypedOffset(
                        *revision->store, lookup.typedOffset, aliasCount, page) ||
                page.rows.size() != aliasCount) {
                continue;
            }
            for (std::size_t index = 0U; index < page.rows.size(); ++index) {
                if (page.rows[index].address != address) {
                    continue;
                }
                const auto *record = revision->ordinaryValues->record(
                        firstOrdinal + index);
                if (record == nullptr || record->id == 0U) {
                    continue;
                }
                Candidate alias{};
                if (compactOwnerCandidateForId(
                            context, revision, record->id, alias)) {
                    seeds.push_back(alias);
                }
            }
        }
    }
}

[[nodiscard]] bool compactOwnerCandidatesForIds(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids,
        std::vector<Candidate> &selected) {
    selected.clear();
    selected.reserve(ids.size());
    for (const auto id : ids) {
        Candidate candidate{};
        if (!compactOwnerCandidateForId(context, revision, id, candidate)) {
            return false;
        }
        selected.push_back(candidate);
    }
    return true;
}

[[nodiscard]] jlongArray compactOwnerResultPage(JNIEnv *env, jint offset,
                                                 jint limit) {
    jlongArray page = compactResultPage(env, offset, limit);
    if (page == nullptr) {
        return nullptr;
    }
    const jsize length = env->GetArrayLength(page);
    if (length <= 0) {
        return page;
    }
    std::vector<jlong> values(static_cast<std::size_t>(length));
    env->GetLongArrayRegion(page, 0, length, values.data());
    if (env->ExceptionCheck() || values.empty()) {
        return page;
    }
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    const auto revision = lookupCompactRevision(state);
    if (revision == nullptr || revision->ordinaryValues == nullptr) {
        return page;
    }
    const std::size_t count = values[0] < 0 ? 0U :
            static_cast<std::size_t>(values[0]);
    if (1U + count * kResultStride != values.size()) {
        return page;
    }
    for (std::size_t index = 0U; index < count; ++index) {
        const std::size_t base = 1U + index * kResultStride;
        const auto ordinal = revision->ordinaryValues->findIndexById(
                static_cast<std::uint64_t>(values[base]));
        if (ordinal.has_value()) {
            values[base + 5U] = std::max<jlong>(
                    values[base + 5U],
                    revision->ordinaryValues->relocationCount(*ordinal));
        }
    }
    env->SetLongArrayRegion(page, 0, length, values.data());
    return page;
}

[[nodiscard]] jint compactOwnerRefresh(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids, bool allowRecovery) {
    if (ids.empty()) {
        return kInvalidRequest;
    }
    std::vector<Candidate> selected;
    if (!compactOwnerCandidatesForIds(context, revision, ids, selected)) {
        return kInvalidRequest;
    }
    std::vector<std::size_t> recovery;
    for (std::size_t index = 0U; index < selected.size(); ++index) {
        Candidate &candidate = selected[index];
        std::uint64_t current = 0U;
        std::uint64_t hash = 0U;
        const bool readable = readCandidate(context.target, candidate, current);
        const bool identityReadable = readable &&
                readIdentity(context.target, candidate.address, candidate.type, hash);
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
        const bool identityMatches = identityReadable &&
                (!candidate.identityValid || candidate.identityHash == hash);
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

[[nodiscard]] jint compactOwnerEdit(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids,
        const std::string &replacement) {
    if (ids.empty() || ids.size() > kMultiWriteLimit) {
        return ids.empty() ? kInvalidRequest : kSafetyLimit;
    }
    std::vector<Candidate> selected;
    if (!compactOwnerCandidatesForIds(context, revision, ids, selected)) {
        return kInvalidRequest;
    }
    std::uint64_t ignored = 0U;
    for (const Candidate &candidate : selected) {
        if (!replacementBitsFor(candidate, replacement, ignored)) {
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
            return kInvalidRequest;
        }
        if (outcome > 0) {
            ++edited;
            writes.push_back(written);
        } else {
            ++skipped;
        }
    }
    std::vector<Candidate> seeds = selected;
    compactOwnerAppendTouchedAliasSeeds(context, revision, writes, seeds);
    const auto reconciled = reconcileTrackedLiveCandidatesAfterWrites(
            context, writes, seeds);
    const jint published = publishLiveCandidates(context, reconciled);
    if (published == kOk) {
        setMessage((std::to_string(edited) + " edited, " +
                    std::to_string(skipped) + " skipped safely").c_str());
    }
    return published;
}

[[nodiscard]] jint compactOwnerPin(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &ids, bool add) {
    if (ids.empty()) {
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>(*context.state);
    if (add) {
        std::vector<Candidate> promoted;
        if (!compactOwnerCandidatesForIds(context, revision, ids, promoted)) {
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
            return kResourceLimit;
        }
        for (const Candidate &candidate : promoted) {
            if (std::none_of(next->watches.begin(), next->watches.end(),
                             [&](const Candidate &watch) {
                                 return watch.id == candidate.id;
                             })) {
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
            return kInvalidRequest;
        }
    }
    return commitCompactOwnerRevision(
            context, std::move(next), revision, 0, context.nextId, true);
}

[[nodiscard]] jlongArray compactOwnerInspect(
        JNIEnv *env, const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        std::uint64_t id, jint radius) {
    if (id == 0U || radius <= 0 || radius > kMaxInspectRadius) {
        return inspectionResult(env, kInvalidRequest);
    }
    Candidate candidate{};
    if (!compactOwnerCandidateForId(context, revision, id, candidate)) {
        return inspectionResult(env, kInvalidRequest);
    }
    if (!verifyCandidateBinding(context.target, candidate)) {
        return inspectionResult(env, kIdentityUnsafe);
    }
    Range window{};
    if (!candidateWindow(context.target, candidate, radius, window)) {
        return inspectionResult(env, kTargetLost);
    }
    std::vector<std::uint8_t> bytes(
            static_cast<std::size_t>(window.end - window.start));
    if (!readExact(context.target.pid, window.start, bytes.data(), bytes.size())) {
        return inspectionResult(env, kTargetLost);
    }
    if (isCancelled(context)) {
        return inspectionResult(env, kCancelled);
    }
    return inspectionResult(env, kOk, window.start, candidate.address, &bytes);
}

[[nodiscard]] jlongArray compactOwnerExpandGroups(
        JNIEnv *env, const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        const std::vector<std::uint64_t> &sourceIds, jint requestedType) {
    if (requestedType != kTypeAuto && !valueTypeFromJint(requestedType).has_value()) {
        return nullptr;
    }
    std::vector<jlong> output;
    std::unordered_set<std::uint64_t> emitted;
    for (const auto sourceId : sourceIds) {
        Candidate sourceCandidate{};
        if (!compactOwnerCandidateForId(
                    context, revision, sourceId, sourceCandidate)) {
            return nullptr;
        }
        jlmem::v2::ResultAliasAddressLookup lookup;
        if (!jlmem::v2::lookupAliasAddress(
                    *revision->store, sourceCandidate.address, lookup)) {
            return nullptr;
        }
        std::size_t ordinal = static_cast<std::size_t>(lookup.typedOffset);
        for (const auto plane : kCompactDisplayOrder) {
            if ((lookup.row.aliasMask & jlmem::v2::resultPlaneBit(plane)) == 0U) {
                continue;
            }
            const auto *record = revision->ordinaryValues->record(ordinal++);
            const jint rawType = jlmem::v2::stableValueTypeFromResultPlane(plane);
            if (record != nullptr &&
                (requestedType == kTypeAuto || requestedType == rawType) &&
                emitted.insert(record->id).second) {
                output.push_back(static_cast<jlong>(record->id));
            }
        }
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr && !output.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                output.data());
    }
    return result;
}

[[nodiscard]] jint compactOwnerEditInspector(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &revision,
        std::uint64_t anchorId, jint relativeOffset, jint rawType,
        std::uint64_t expectedBits, const std::string &replacement) {
    const auto type = valueTypeFromJint(rawType);
    if (!type.has_value() ||
        std::abs(static_cast<std::int64_t>(relativeOffset)) > kMaxInspectRadius) {
        return kInvalidRequest;
    }
    Candidate anchor{};
    if (!compactOwnerCandidateForId(context, revision, anchorId, anchor) ||
        anchor.state != kStable || !anchor.identityValid) {
        return kIdentityUnsafe;
    }
    std::uintptr_t address = anchor.address;
    if (relativeOffset < 0) {
        const auto distance = static_cast<std::uintptr_t>(
                -static_cast<std::int64_t>(relativeOffset));
        if (address < distance) return kInvalidRequest;
        address -= distance;
    } else {
        const auto distance = static_cast<std::uintptr_t>(relativeOffset);
        if (address > std::numeric_limits<std::uintptr_t>::max() - distance) {
            return kInvalidRequest;
        }
        address += distance;
    }
    const std::size_t width = widthOf(*type);
    const bool inRange = std::any_of(
            context.target.ranges.begin(), context.target.ranges.end(),
            [&](const Range &range) {
                return address >= range.start && address <= range.end &&
                       width <= static_cast<std::size_t>(range.end - address);
            });
    if (!inRange || width == 0U || address % width != 0U) {
        return kInvalidRequest;
    }
    Candidate editable = anchor;
    editable.address = address;
    editable.type = *type;
    editable.initialBits = expectedBits;
    editable.previousBits = expectedBits;
    editable.currentBits = expectedBits;
    editable.identityValid = readIdentity(
            context.target, address, *type, editable.identityHash);
    if (!editable.identityValid) {
        return kIdentityUnsafe;
    }
    WrittenSpan written{};
    const int outcome = editOneCandidate(
            context.target, editable, replacement, &written);
    if (outcome < 0) return kInvalidRequest;
    if (outcome == 0) return kIdentityUnsafe;
    written.candidateId =
            editable.address == anchor.address && editable.type == anchor.type
                    ? anchor.id : 0U;
    std::vector<WrittenSpan> writes{written};
    std::vector<Candidate> seeds{anchor};
    compactOwnerAppendTouchedAliasSeeds(context, revision, writes, seeds);
    const auto reconciled = reconcileTrackedLiveCandidatesAfterWrites(
            context, writes, seeds);
    const jint published = publishLiveCandidates(context, reconciled);
    if (published == kOk) setMessage("Inspector value updated");
    return published;
}

[[nodiscard]] jint compactOwnerUndo() {
    std::shared_ptr<const SearchState> source;
    std::shared_ptr<const jlmem::v2::ResultRevision> revision;
    {
        std::lock_guard<std::mutex> stateLock(gMutex);
        if (gHistory.empty()) {
            gLastMessage = "No earlier search state is available";
            return kNoSession;
        }
        source = gHistory.back();
        std::lock_guard<std::mutex> compactLock(gCompactRevisionMutex);
        for (auto it = gCompactRevisions.rbegin();
             it != gCompactRevisions.rend(); ++it) {
            if (it->state.lock() == source && it->revision != nullptr &&
                it->revision->compact()) {
                revision = it->revision;
                break;
            }
        }
        if (revision == nullptr) {
            // Leave history untouched; the established Candidate-aware Undo handles this source.
            return std::numeric_limits<jint>::min();
        }
        auto restored = std::make_shared<SearchState>(*source);
        restored->watches = gState->watches;
        for (Candidate &watch : restored->watches) {
            watch = liveCandidate(watch, gLiveCandidates);
        }
        std::shared_ptr<const SearchState> published = restored;
        gState = std::move(restored);
        gLiveCandidates.clear();
        gHistory.pop_back();
        compactOwnerRememberLocked(published, revision);
        pruneCompactRevisionsLocked();
        gLastMessage.clear();
    }
    clearStagedKnownResultStore();
    clearStagedAutoResultStore();
    return kOk;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnownV2CompactOwner(
        JNIEnv *env, jclass, jint valueType, jint predicate, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        return compactOwnerScanKnown(
                context, context.target, valueType, predicate,
                fromJString(env, first), fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwner(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second,
        jboolean allowRelocationReconcile) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        return compactOwnerRefineKnown(
                context, predicate, fromJString(env, first), fromJString(env, second),
                allowRelocationReconcile == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelativeV2CompactOwner(
        JNIEnv *env, jclass, jint predicate, jint compareTarget, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        if (context.state->mode == StateMode::Unknown) {
            return compactOwnerScanRelativeUnknown(
                    context, predicate, compareTarget,
                    fromJString(env, first), fromJString(env, second));
        }
        return compactOwnerRefineRelative(
                context, predicate, compareTarget,
                fromJString(env, first), fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filterV2CompactOwner(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean keep) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        return compactOwnerFilter(context, ids, keep == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPageV2CompactOwner(
        JNIEnv *env, jclass, jint offset, jint limit) {
    try {
        return compactOwnerResultPage(env, offset, limit);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refreshV2CompactOwner(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean allowRecovery) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return compactOwnerRefresh(
                context, revision, ids, allowRecovery == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editV2CompactOwner(
        JNIEnv *env, jclass, jlongArray rawIds, jstring replacement) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return compactOwnerEdit(
                context, revision, ids, fromJString(env, replacement));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pinV2CompactOwner(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean add) {
    return guardedOperation([&] {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return compactOwnerPin(context, revision, ids, add == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspectV2CompactOwner(
        JNIEnv *env, jclass, jlong rawId, jint radius) {
    try {
        if (rawId <= 0) return inspectionResult(env, kInvalidRequest);
        OperationContext context;
        if (!beginOperation(context)) return inspectionResult(env, kNoSession);
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) {
            return inspectionResult(env, kNoSession);
        }
        return compactOwnerInspect(
                env, context, revision, static_cast<std::uint64_t>(rawId), radius);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return inspectionResult(env, kInvalidRequest);
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroupsV2CompactOwner(
        JNIEnv *env, jclass, jlongArray rawIds, jint requestedType) {
    try {
        std::vector<std::uint64_t> ids;
        if (!readIds(env, rawIds, ids)) return nullptr;
        OperationContext context;
        if (!beginOperation(context)) return nullptr;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return nullptr;
        return compactOwnerExpandGroups(env, context, revision, ids, requestedType);
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editInspectorValueV2CompactOwner(
        JNIEnv *env, jclass, jlong rawAnchorId, jint relativeOffset,
        jint rawType, jlong expectedBits, jstring replacement) {
    return guardedOperation([&] {
        if (rawAnchorId <= 0) return kInvalidRequest;
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        std::shared_ptr<const jlmem::v2::ResultRevision> revision;
        if (!compactRevisionForContext(context, revision)) return kNoSession;
        return compactOwnerEditInspector(
                context, revision, static_cast<std::uint64_t>(rawAnchorId),
                relativeOffset, rawType, static_cast<std::uint64_t>(expectedBits),
                fromJString(env, replacement));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearbyV2CompactOwner(
        JNIEnv *env, jclass, jlong rawAnchorId, jint radius, jint valueType,
        jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        if (rawAnchorId <= 0 || radius <= 0 || radius > kMaxNearbyRadius) {
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        const auto revision = lookupCompactRevision(context.state);
        Candidate anchor{};
        const bool resolved = revision != nullptr
                ? compactOwnerCandidateForId(
                          context, revision,
                          static_cast<std::uint64_t>(rawAnchorId), anchor)
                : resolveCandidateById(
                          context, static_cast<std::uint64_t>(rawAnchorId), anchor);
        if (!resolved || !verifyCandidateBinding(context.target, anchor)) {
            return kIdentityUnsafe;
        }
        Range window{};
        if (!candidateWindow(context.target, anchor, radius, window)) {
            return kTargetLost;
        }
        OperationContext nearby = context;
        nearby.target.ranges.clear();
        nearby.target.ranges.push_back(window);
        return compactOwnerScanKnown(
                nearby, context.target, valueType, predicate,
                fromJString(env, first), fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_undoV2CompactOwner(
        JNIEnv *, jclass) {
    return guardedOperation([] {
        const jint compact = compactOwnerUndo();
        return compact == std::numeric_limits<jint>::min()
                       ? undoWithRememberedResultRevision()
                       : compact;
    });
}
