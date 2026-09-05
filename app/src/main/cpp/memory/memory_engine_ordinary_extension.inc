/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Differential-only bridge for the Candidate -> compact ordinary metadata cutover. It performs no
// target reads and does not publish the sidecar. Tests/diagnostics can therefore prove that the
// committed Candidate mirror and ResultStore alias ordinal describe the same revision before normal
// paging/refine starts depending on the compact representation.
namespace {

constexpr std::size_t kOrdinaryParityProbeCandidateLimit = 256U * 1024U;

[[nodiscard]] jlong saturatingSizeToJlong(std::size_t value) noexcept {
    const auto maximum = static_cast<std::size_t>(std::numeric_limits<jlong>::max());
    return static_cast<jlong>(std::min(value, maximum));
}

[[nodiscard]] jlongArray makeOrdinaryParityResult(
        JNIEnv *env, jint status, std::uint64_t typedCount = 0U,
        std::size_t ordinaryBytes = 0U, std::size_t candidateBytes = 0U,
        std::uint64_t identityValidCount = 0U,
        std::uint64_t fingerprint = 0U) {
    const std::array<jlong, 8> values{
            status,
            static_cast<jlong>(typedCount),
            static_cast<jlong>(sizeof(jlmem::v2::OrdinaryResultRecord)),
            static_cast<jlong>(sizeof(Candidate)),
            saturatingSizeToJlong(ordinaryBytes),
            saturatingSizeToJlong(candidateBytes),
            static_cast<jlong>(identityValidCount),
            static_cast<jlong>(fingerprint),
    };
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

[[nodiscard]] jlongArray validateCurrentOrdinarySidecar(JNIEnv *env) {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    if (state == nullptr || state->mode != StateMode::Candidates ||
        state->candidateOrderDirty ||
        state->candidates.size() > kOrdinaryParityProbeCandidateLimit) {
        return makeOrdinaryParityResult(env, kResourceLimit);
    }

    const auto revision = currentStagedResultRevision(state);
    if (revision == nullptr || !revision->valid() || revision->store == nullptr ||
        revision->store->typedCount() != state->candidates.size() ||
        revision->store->uniqueAddressCount() != state->logicalCount) {
        return makeOrdinaryParityResult(env, kInvalidRequest);
    }

    jlmem::v2::OrdinaryResultStore ordinary;
    if (!ordinary.reserve(state->candidates.size())) {
        return makeOrdinaryParityResult(env, kResourceLimit);
    }

    jlmem::v2::ResultAliasCursor cursor;
    std::size_t candidateIndex = 0U;
    std::uint64_t validIdentities = 0U;
    std::uint64_t fingerprint = UINT64_C(1469598103934665603);
    while (candidateIndex < state->candidates.size()) {
        const std::size_t limit = std::min(
                jlmem::v2::kResultAliasCursorPageLimit,
                state->candidates.size() - candidateIndex);
        jlmem::v2::ResultAliasPage page;
        if (!jlmem::v2::readAliasPage(*revision->store, cursor, limit, page) ||
            page.rows.empty() || page.rows.size() > limit) {
            return makeOrdinaryParityResult(env, kInvalidRequest);
        }
        for (const jlmem::v2::ResultAliasRow &row : page.rows) {
            if (candidateIndex >= state->candidates.size()) {
                return makeOrdinaryParityResult(env, kInvalidRequest);
            }
            const Candidate &candidate = state->candidates[candidateIndex];
            const auto candidatePlane = jlmem::v2::resultPlaneFromStableValueType(
                    toJint(candidate.type));
            if (!candidatePlane.has_value() || candidate.address != row.address ||
                *candidatePlane != row.plane || candidate.id == 0U) {
                return makeOrdinaryParityResult(env, kInvalidRequest);
            }
            const jlmem::v2::OrdinaryResultRecord record{
                    candidate.id,
                    candidate.initialBits,
                    candidate.previousBits,
                    candidate.currentBits,
                    candidate.identityHash,
            };
            if (!ordinary.append(record, candidate.identityValid)) {
                return makeOrdinaryParityResult(env, kResourceLimit);
            }
            if (candidate.identityValid) {
                ++validIdentities;
            }
            fingerprint ^= static_cast<std::uint64_t>(row.address);
            fingerprint *= UINT64_C(1099511628211);
            fingerprint ^= static_cast<std::uint64_t>(toJint(candidate.type));
            fingerprint *= UINT64_C(1099511628211);
            ++candidateIndex;
        }
        cursor = page.next;
    }

    jlmem::v2::ResultAliasPage endPage;
    if (!jlmem::v2::readAliasPage(*revision->store, cursor, 1U, endPage) ||
        !endPage.rows.empty() || ordinary.size() != state->candidates.size()) {
        return makeOrdinaryParityResult(env, kInvalidRequest);
    }

    auto ordinaryOwner = std::make_shared<const jlmem::v2::OrdinaryResultStore>(
            std::move(ordinary));
    jlmem::v2::ResultRevision enriched = *revision;
    enriched.ordinaryValues = ordinaryOwner;
    if (!enriched.valid()) {
        return makeOrdinaryParityResult(env, kInvalidRequest);
    }

    const std::size_t candidateBytes =
            state->candidates.capacity() >
                            std::numeric_limits<std::size_t>::max() / sizeof(Candidate)
                    ? std::numeric_limits<std::size_t>::max()
                    : state->candidates.capacity() * sizeof(Candidate);
    return makeOrdinaryParityResult(
            env, kOk, static_cast<std::uint64_t>(ordinaryOwner->size()),
            ordinaryOwner->retainedBytes(), candidateBytes, validIdentities,
            fingerprint);
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_v2OrdinarySidecarParityStats(
        JNIEnv *env, jclass) {
    try {
        return validateCurrentOrdinarySidecar(env);
    } catch (...) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return makeOrdinaryParityResult(env, kResourceLimit);
    }
}
