#include "result_store_scan.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <cmath>
#include <cstring>
#include <limits>
#include <span>
#include <type_traits>

namespace jlmem::v2 {
namespace {

constexpr size_t kReadChunkSize = 256U * 1024U;
constexpr int kEqual = 0;
constexpr int kNotEqual = 1;
constexpr int kGreater = 2;
constexpr int kLess = 3;
constexpr int kGreaterOrEqual = 4;
constexpr int kLessOrEqual = 5;
constexpr int kBetween = 6;

[[nodiscard]] size_t widthOf(ResultType type) noexcept {
    switch (type) {
    case ResultType::Byte: return 1U;
    case ResultType::Short:
    case ResultType::Char: return 2U;
    case ResultType::Int:
    case ResultType::Float: return 4U;
    case ResultType::Long:
    case ResultType::Double: return 8U;
    }
    return 0U;
}

[[nodiscard]] bool isFloating(ResultType type) noexcept {
    return type == ResultType::Float || type == ResultType::Double;
}

[[nodiscard]] uint64_t loadBits(const uint8_t *data, size_t width) noexcept {
    uint64_t bits = 0;
    std::memcpy(&bits, data, width);
    return bits;
}

template <typename T>
[[nodiscard]] bool parseIntegral(const std::string &text, T &value) {
    static_assert(std::is_integral_v<T>);
    if (text.empty()) return false;
    const char *first = text.data();
    const char *last = first + text.size();
    if constexpr (std::is_signed_v<T>) {
        long long parsed = 0;
        const auto result = std::from_chars(first, last, parsed, 0);
        if (result.ec != std::errc{} || result.ptr != last ||
            parsed < static_cast<long long>(std::numeric_limits<T>::min()) ||
            parsed > static_cast<long long>(std::numeric_limits<T>::max())) {
            return false;
        }
        value = static_cast<T>(parsed);
    } else {
        unsigned long long parsed = 0;
        const auto result = std::from_chars(first, last, parsed, 0);
        if (result.ec != std::errc{} || result.ptr != last ||
            parsed > static_cast<unsigned long long>(std::numeric_limits<T>::max())) {
            return false;
        }
        value = static_cast<T>(parsed);
    }
    return true;
}

[[nodiscard]] bool parseFloating(const std::string &text, double &value) {
    if (text.empty()) return false;
    char *end = nullptr;
    errno = 0;
    value = std::strtod(text.c_str(), &end);
    return errno != ERANGE && end != text.c_str() && end != nullptr && *end == '\0';
}

struct Query {
    ResultType type = ResultType::Int;
    int64_t integerFirst = 0;
    int64_t integerSecond = 0;
    double floatingFirst = 0.0;
    double floatingSecond = 0.0;
};

[[nodiscard]] bool parseQuery(const KnownScanRequest &request, Query &query) {
    if (request.predicate < kEqual || request.predicate > kBetween ||
        widthOf(request.type) == 0U) {
        return false;
    }
    query.type = request.type;
    if (isFloating(request.type)) {
        if (!parseFloating(request.firstValue, query.floatingFirst)) return false;
        if (request.predicate == kBetween &&
            !parseFloating(request.secondValue, query.floatingSecond)) return false;
        return true;
    }
    switch (request.type) {
    case ResultType::Byte: {
        int8_t first = 0;
        int8_t second = 0;
        if (!parseIntegral(request.firstValue, first)) return false;
        if (request.predicate == kBetween && !parseIntegral(request.secondValue, second)) return false;
        query.integerFirst = first;
        query.integerSecond = second;
        return true;
    }
    case ResultType::Short: {
        int16_t first = 0;
        int16_t second = 0;
        if (!parseIntegral(request.firstValue, first)) return false;
        if (request.predicate == kBetween && !parseIntegral(request.secondValue, second)) return false;
        query.integerFirst = first;
        query.integerSecond = second;
        return true;
    }
    case ResultType::Char: {
        uint16_t first = 0;
        uint16_t second = 0;
        if (!parseIntegral(request.firstValue, first)) return false;
        if (request.predicate == kBetween && !parseIntegral(request.secondValue, second)) return false;
        query.integerFirst = first;
        query.integerSecond = second;
        return true;
    }
    case ResultType::Int: {
        int32_t first = 0;
        int32_t second = 0;
        if (!parseIntegral(request.firstValue, first)) return false;
        if (request.predicate == kBetween && !parseIntegral(request.secondValue, second)) return false;
        query.integerFirst = first;
        query.integerSecond = second;
        return true;
    }
    case ResultType::Long: {
        int64_t first = 0;
        int64_t second = 0;
        if (!parseIntegral(request.firstValue, first)) return false;
        if (request.predicate == kBetween && !parseIntegral(request.secondValue, second)) return false;
        query.integerFirst = first;
        query.integerSecond = second;
        return true;
    }
    case ResultType::Float:
    case ResultType::Double:
        break;
    }
    return false;
}

[[nodiscard]] bool compareInteger(int64_t value, const Query &query, int predicate) noexcept {
    switch (predicate) {
    case kEqual: return value == query.integerFirst;
    case kNotEqual: return value != query.integerFirst;
    case kGreater: return value > query.integerFirst;
    case kLess: return value < query.integerFirst;
    case kGreaterOrEqual: return value >= query.integerFirst;
    case kLessOrEqual: return value <= query.integerFirst;
    case kBetween:
        return value >= std::min(query.integerFirst, query.integerSecond) &&
               value <= std::max(query.integerFirst, query.integerSecond);
    default: return false;
    }
}

[[nodiscard]] bool compareFloating(double value, const Query &query, int predicate) noexcept {
    switch (predicate) {
    case kEqual: return value == query.floatingFirst;
    case kNotEqual: return value != query.floatingFirst;
    case kGreater: return value > query.floatingFirst;
    case kLess: return value < query.floatingFirst;
    case kGreaterOrEqual: return value >= query.floatingFirst;
    case kLessOrEqual: return value <= query.floatingFirst;
    case kBetween:
        return value >= std::min(query.floatingFirst, query.floatingSecond) &&
               value <= std::max(query.floatingFirst, query.floatingSecond);
    default: return false;
    }
}

[[nodiscard]] bool matches(uint64_t bits, const Query &query, int predicate) noexcept {
    switch (query.type) {
    case ResultType::Byte: return compareInteger(static_cast<int8_t>(bits), query, predicate);
    case ResultType::Short: return compareInteger(static_cast<int16_t>(bits), query, predicate);
    case ResultType::Char: return compareInteger(static_cast<uint16_t>(bits), query, predicate);
    case ResultType::Int: return compareInteger(static_cast<int32_t>(bits), query, predicate);
    case ResultType::Long: return compareInteger(static_cast<int64_t>(bits), query, predicate);
    case ResultType::Float: {
        const uint32_t raw = static_cast<uint32_t>(bits);
        float value = 0.0F;
        std::memcpy(&value, &raw, sizeof(value));
        return compareFloating(value, query, predicate);
    }
    case ResultType::Double: {
        double value = 0.0;
        std::memcpy(&value, &bits, sizeof(value));
        return compareFloating(value, query, predicate);
    }
    }
    return false;
}

[[nodiscard]] uintptr_t alignUp(uintptr_t value, size_t alignment) noexcept {
    const uintptr_t remainder = value % alignment;
    if (remainder == 0U) return value;
    const uintptr_t delta = alignment - remainder;
    return value > std::numeric_limits<uintptr_t>::max() - delta
                   ? std::numeric_limits<uintptr_t>::max()
                   : value + delta;
}

} // namespace

bool scanKnownExplicit(const std::vector<ScanRange> &ranges,
                       const KnownScanRequest &request,
                       const RemoteReadFn &read,
                       const CancelledFn &cancelled,
                       ResultStore &out,
                       KnownScanStats &stats,
                       std::string &error) {
    Query query;
    if (!parseQuery(request, query)) {
        error = "Invalid explicit-type v2 query";
        return false;
    }
    const size_t width = widthOf(request.type);
    ResultStore next;
    KnownScanStats nextStats;
    std::vector<uint8_t> buffer;

    for (const ScanRange &range : ranges) {
        if (range.end <= range.start) continue;
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (cancelled && cancelled()) {
                error = "Operation cancelled";
                return false;
            }
            const size_t remaining = static_cast<size_t>(range.end - chunkStart);
            const size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            if (!read(chunkStart, buffer.data(), chunkSize)) {
                error = "Target range changed during v2 shadow scan";
                return false;
            }

            uintptr_t address = alignUp(chunkStart, width);
            while (address >= chunkStart) {
                const size_t offset = static_cast<size_t>(address - chunkStart);
                if (offset + width > chunkSize) break;
                const uint64_t bits = loadBits(buffer.data() + offset, width);
                if (matches(bits, query, request.predicate)) {
                    const uintptr_t blockBase = address & ~(static_cast<uintptr_t>(kLogicalBlockSize) - 1U);
                    ResultBlockBuilder builder(blockBase, typeMask(request.type));
                    const size_t slot = static_cast<size_t>((address - blockBase) / width);
                    if (!builder.setSlot(request.type, slot)) {
                        error = "ResultStore rejected v2 result slot";
                        return false;
                    }
                    if (!next.append(builder.finish())) {
                        error = "ResultStore rejected v2 result block ordering";
                        return false;
                    }
                }
                if (address > std::numeric_limits<uintptr_t>::max() - width) break;
                address += width;
            }
            nextStats.bytesScanned += chunkSize;
            chunkStart += chunkSize;
        }
    }

    nextStats.typedMatches = next.typedCount();
    nextStats.uniqueAddresses = next.uniqueAddressCount();
    out = std::move(next);
    stats = nextStats;
    error.clear();
    return true;
}

} // namespace jlmem::v2
