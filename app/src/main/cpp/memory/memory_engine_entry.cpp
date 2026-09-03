/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// Transitional compilation seam for the v2 migration.
//
// Keep the proven production engine source byte-for-byte intact while allowing small migration
// helpers to reuse its authoritative parser in the *same translation unit*. This is deliberately
// not a second parser: parseQuery(), ValueType and Query below are the exact implementations used
// by startKnown()/refineKnown(). Once ResultStore becomes the production owner, this seam can be
// folded into the final native module and memory_engine.cpp can become a normal implementation
// unit again.
#include "known_query_plan.h"
#include "memory_engine.cpp"

namespace {

[[nodiscard]] std::uint64_t canonicalIntegerBits(ValueType type,
                                                 std::int64_t value) noexcept {
    switch (type) {
    case ValueType::Byte:
        return static_cast<std::uint8_t>(static_cast<std::int8_t>(value));
    case ValueType::Short:
        return static_cast<std::uint16_t>(static_cast<std::int16_t>(value));
    case ValueType::Char:
        return static_cast<std::uint16_t>(value);
    case ValueType::Int:
        return static_cast<std::uint32_t>(static_cast<std::int32_t>(value));
    case ValueType::Long:
        return static_cast<std::uint64_t>(value);
    case ValueType::Invalid:
    case ValueType::Float:
    case ValueType::Double:
        return 0U;
    }
    return 0U;
}

[[nodiscard]] std::uint64_t canonicalKnownBits(const Query &query,
                                               bool second) noexcept {
    if (query.floating) {
        const double value = second ? query.floatingSecond : query.floatingFirst;
        if (query.type == ValueType::Float) {
            return static_cast<std::uint64_t>(
                    std::bit_cast<std::uint32_t>(static_cast<float>(value)));
        }
        if (query.type == ValueType::Double) {
            return std::bit_cast<std::uint64_t>(value);
        }
        return 0U;
    }
    return canonicalIntegerBits(
            query.type, second ? query.integerSecond : query.integerFirst);
}

[[nodiscard]] std::optional<jlmem::v2::KnownQueryPlan> parseCanonicalKnownPlan(
        jint valueType, jint predicate, const std::string &first,
        const std::string &second) {
    if (valueType == kTypeAuto || predicate < kEqual || predicate > kBetween) {
        return std::nullopt;
    }
    Query query;
    if (!parseQuery(valueType, predicate, first, second, query)) {
        return std::nullopt;
    }
    const std::uint64_t firstBits = canonicalKnownBits(query, false);
    const std::uint64_t secondBits =
            predicate == kBetween ? canonicalKnownBits(query, true) : 0U;
    return jlmem::v2::knownQueryPlanFromStableValues(
            valueType, predicate, firstBits, secondBits);
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_canonicalKnownPlan(
        JNIEnv *env, jclass, jint valueType, jint predicate, jstring first,
        jstring second) {
    try {
        const auto plan = parseCanonicalKnownPlan(
                valueType, predicate, fromJString(env, first),
                fromJString(env, second));
        if (!plan.has_value()) {
            return nullptr;
        }
        const std::array<jlong, 4> values{
                static_cast<jlong>(valueType),
                static_cast<jlong>(predicate),
                static_cast<jlong>(plan->firstBits),
                static_cast<jlong>(plan->secondBits),
        };
        jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
        if (result != nullptr) {
            env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()),
                                    values.data());
        }
        return result;
    } catch (...) {
        // Diagnostics/migration metadata must never destabilize the production engine or replace
        // its user-visible lastMessage. Invalid/failed canonicalization is simply unavailable.
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return nullptr;
    }
}
