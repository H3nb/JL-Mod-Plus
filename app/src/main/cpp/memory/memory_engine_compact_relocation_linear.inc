/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Linear broad-movement rebuild. The transitional implementation reconstructed each source row by
// typed ordinal after recovery, making a rare large-GC path perform repeated cursor seeks. Carrying
// oldAddress in the transient 32-byte recovery row removes that second lookup without introducing a
// persistent address duplicate in OrdinaryResultStore.
namespace {

struct CompactLinearDirectSurvivor final {
    std::uintptr_t address = 0U;
    std::uint64_t currentBits = 0U;
    std::uint32_t packed = 0U;
};

struct CompactLinearRecoveryEntry final {
    std::uintptr_t oldAddress = 0U;
    std::uintptr_t foundAddress = 0U;
    std::uint64_t foundBits = 0U;
    std::uint32_t packed = 0U;
    std::uint8_t foundCount = 0U;
};

static_assert(sizeof(CompactLinearRecoveryEntry) <= 32U,
              "linear relocation staging must remain compact");

[[nodiscard]] bool compactLinearRecoveryKeyLess(
        const CompactLinearRecoveryEntry &entry,
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

[[nodiscard]] bool compactLinearSameRecoveryKey(
        const CompactLinearRecoveryEntry &left,
        const CompactLinearRecoveryEntry &right,
        const jlmem::v2::OrdinaryResultStore &ordinary) {
    if (compactOwnerPlane(left.packed) != compactOwnerPlane(right.packed)) {
        return false;
    }
    const auto *leftRecord = ordinary.record(compactOwnerOrdinal(left.packed));
    const auto *rightRecord = ordinary.record(compactOwnerOrdinal(right.packed));
    return leftRecord != nullptr && rightRecord != nullptr &&
           leftRecord->identityHash == rightRecord->identityHash;
}

[[nodiscard]] jint compactOwnerRecoverKnownLinear(
        const OperationContext &context,
        const std::shared_ptr<const jlmem::v2::ResultRevision> &source,
        jint predicate,
        const std::array<const Query *, kTypeSlotCount> &queriesByType,
        std::uint64_t candidateWork,
        std::shared_ptr<const jlmem::v2::ResultRevision> &published,
        std::uint64_t &logicalCount, std::size_t &relocatedCount) {
    std::vector<CompactLinearRecoveryEntry> recovery;
    std::vector<CompactLinearDirectSurvivor> direct;
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
                    try {
                        recovery.push_back({
                                row.address, 0U, 0U,
                                compactOwnerPackOrdinalPlane(ordinal, row.plane), 0U});
                    } catch (...) {
                        return kResourceLimit;
                    }
                } else {
                    std::uint64_t current = 0U;
                    if (reader.read(row.address, *type, current) &&
                        matchesKnown(current, *query, predicate)) {
                        try {
                            direct.push_back({
                                    row.address, current,
                                    compactOwnerPackOrdinalPlane(ordinal, row.plane)});
                        } catch (...) {
                            return kResourceLimit;
                        }
                    }
                }
            }
            const std::uint64_t width = widthOf(*type);
            completed = completed > std::numeric_limits<std::uint64_t>::max() - width
                                ? std::numeric_limits<std::uint64_t>::max()
                                : completed + width;
            ++ordinal;
        }
        cursor = page.next;
        gScanBytesScanned.store(std::min(completed, candidateWork),
                                std::memory_order_release);
    }

    std::sort(recovery.begin(), recovery.end(),
              [&](const CompactLinearRecoveryEntry &left,
                  const CompactLinearRecoveryEntry &right) {
                  const auto leftPlane = compactOwnerPlane(left.packed);
                  const auto rightPlane = compactOwnerPlane(right.packed);
                  if (leftPlane != rightPlane) {
                      return jlmem::v2::planeIndex(leftPlane) <
                             jlmem::v2::planeIndex(rightPlane);
                  }
                  const auto *leftRecord = source->ordinaryValues->record(
                          compactOwnerOrdinal(left.packed));
                  const auto *rightRecord = source->ordinaryValues->record(
                          compactOwnerOrdinal(right.packed));
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
                [&](const CompactLinearRecoveryEntry &entry, const auto &wanted) {
                    return compactLinearRecoveryKeyLess(
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
        if ((found != recovery.begin() &&
             compactLinearSameRecoveryKey(
                     *std::prev(found), *found, *source->ordinaryValues)) ||
            (next != recovery.end() &&
             compactLinearSameRecoveryKey(
                     *found, *next, *source->ordinaryValues))) {
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
            scannedHeap = scannedHeap > std::numeric_limits<std::uint64_t>::max() - chunkSize
                                  ? std::numeric_limits<std::uint64_t>::max()
                                  : scannedHeap + chunkSize;
            const std::uint64_t progress =
                    candidateWork > std::numeric_limits<std::uint64_t>::max() - scannedHeap
                            ? std::numeric_limits<std::uint64_t>::max()
                            : candidateWork + scannedHeap;
            gScanBytesScanned.store(std::min(progress, totalWork),
                                    std::memory_order_release);
            chunkStart += chunkSize;
        }
    }

    recovery.erase(
            std::remove_if(recovery.begin(), recovery.end(),
                           [](const CompactLinearRecoveryEntry &entry) {
                               return entry.foundCount != 1U;
                           }),
            recovery.end());

    std::sort(recovery.begin(), recovery.end(),
              [](const CompactLinearRecoveryEntry &left,
                 const CompactLinearRecoveryEntry &right) {
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
                               std::uintptr_t oldAddress,
                               jlmem::v2::ResultPlane plane,
                               std::size_t sourceOrdinal,
                               std::uint64_t currentBits) -> bool {
        const auto *record = source->ordinaryValues->record(sourceOrdinal);
        if (record == nullptr ||
            (havePrevious && address == previousAddress && plane == previousPlane)) {
            return false;
        }
        const jlmem::v2::ResultAliasRow sourceRow{oldAddress, jlmem::v2::resultPlaneBit(plane)};
        const CompactOwnerSourceMeta meta = compactOwnerSourceMeta(
                context, *source->ordinaryValues, sourceOrdinal,
                // compactOwnerSourceMeta needs only address + plane; aliasMask is irrelevant.
                {sourceRow.address, plane}, false);
        std::uint16_t relocationCount = meta.relocationCount;
        if (address != oldAddress) {
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
            if (!appendOne(item.address, item.address,
                           compactOwnerPlane(item.packed),
                           compactOwnerOrdinal(item.packed), item.currentBits)) {
                setMessage("Compact relocation produced duplicate or unordered membership");
                return kResourceLimit;
            }
        } else {
            const auto &item = recovery[recoveredIndex++];
            if (!appendOne(item.foundAddress, item.oldAddress,
                           compactOwnerPlane(item.packed),
                           compactOwnerOrdinal(item.packed), item.foundBits)) {
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

[[nodiscard]] jint compactOwnerRefineKnownLinear(
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
        result = compactOwnerRecoverKnownLinear(
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

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnownV2CompactOwnerLinear(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second,
        jboolean allowRelocationReconcile) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) return kNoSession;
        return compactOwnerRefineKnownLinear(
                context, predicate, fromJString(env, first), fromJString(env, second),
                allowRelocationReconcile == JNI_TRUE);
    });
}
