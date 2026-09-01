/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <sys/uio.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iterator>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <span>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

constexpr jint kOk = 0;
constexpr jint kCancelled = 1;
constexpr jint kInvalidRequest = 2;
constexpr jint kResourceLimit = 3;
constexpr jint kTargetLost = 5;
constexpr jint kNoSession = 6;
constexpr jint kIdentityUnsafe = 7;
constexpr jint kSafetyLimit = 8;

constexpr jint kTypeAuto = 0;
constexpr jint kTypeByte = 1;
constexpr jint kTypeShort = 2;
constexpr jint kTypeChar = 3;
constexpr jint kTypeInt = 4;
constexpr jint kTypeLong = 5;
constexpr jint kTypeFloat = 6;
constexpr jint kTypeDouble = 7;

// JNI uses stable integer constants, but materialized candidates and parsed queries use a
// scoped type so width-sensitive operations cannot accidentally accept predicates or other ints.
enum class ValueType : uint8_t {
    Invalid = kTypeAuto,
    Byte = kTypeByte,
    Short = kTypeShort,
    Char = kTypeChar,
    Int = kTypeInt,
    Long = kTypeLong,
    Float = kTypeFloat,
    Double = kTypeDouble,
};

[[nodiscard]] constexpr jint toJint(ValueType type) noexcept {
    return static_cast<jint>(std::to_underlying(type));
}

[[nodiscard]] constexpr size_t typeIndex(ValueType type) noexcept {
    return static_cast<size_t>(std::to_underlying(type));
}

constexpr size_t kTypeSlotCount = typeIndex(ValueType::Double) + 1U;

[[nodiscard]] constexpr std::optional<ValueType> valueTypeFromJint(jint type) noexcept {
    switch (type) {
    case kTypeByte: return ValueType::Byte;
    case kTypeShort: return ValueType::Short;
    case kTypeChar: return ValueType::Char;
    case kTypeInt: return ValueType::Int;
    case kTypeLong: return ValueType::Long;
    case kTypeFloat: return ValueType::Float;
    case kTypeDouble: return ValueType::Double;
    default: return std::nullopt;
    }
}

[[nodiscard]] constexpr bool isFloating(ValueType type) noexcept {
    return type == ValueType::Float || type == ValueType::Double;
}

static_assert(sizeof(ValueType) == sizeof(uint8_t));
static_assert(toJint(ValueType::Invalid) == kTypeAuto);
static_assert(toJint(ValueType::Byte) == kTypeByte);
static_assert(toJint(ValueType::Short) == kTypeShort);
static_assert(toJint(ValueType::Char) == kTypeChar);
static_assert(toJint(ValueType::Int) == kTypeInt);
static_assert(toJint(ValueType::Long) == kTypeLong);
static_assert(toJint(ValueType::Float) == kTypeFloat);
static_assert(toJint(ValueType::Double) == kTypeDouble);

constexpr jint kEqual = 0;
constexpr jint kNotEqual = 1;
constexpr jint kGreater = 2;
constexpr jint kLess = 3;
constexpr jint kGreaterOrEqual = 4;
constexpr jint kLessOrEqual = 5;
constexpr jint kBetween = 6;
constexpr jint kChanged = 7;
constexpr jint kUnchanged = 8;
constexpr jint kIncreased = 9;
constexpr jint kDecreased = 10;
constexpr jint kIncreasedBy = 11;
constexpr jint kDecreasedBy = 12;
constexpr jint kChangedBy = 13;
constexpr jint kIncreasedByRange = 14;
constexpr jint kDecreasedByRange = 15;

constexpr jint kComparePrevious = 0;
constexpr jint kCompareInitial = 1;
constexpr jint kStable = 0;
constexpr jint kRelocating = 1;
constexpr jint kAmbiguous = 2;
constexpr jint kLost = 3;
// Candidate records are compact native data and never cross Binder in bulk. Two million typed
// aliases covers the dense searches seen in the prototype while retaining a deterministic bound
// on old API 23 devices. An incomplete set is never committed.
constexpr size_t kCandidateLimit = 2'000'000;
constexpr size_t kSnapshotByteLimit = 96U * 1024U * 1024U;
constexpr size_t kReadChunkSize = 256U * 1024U;
constexpr size_t kDirectRefineLimit = 4'096;
constexpr size_t kHistoryLimit = 8;
// Includes the current search state as well as undo snapshots. The active result may itself be
// large, so bounding only deque history still lets a low-memory device retain several hundred MB.
constexpr size_t kRetainedStateByteLimit = 192U * 1024U * 1024U;
constexpr size_t kResultStride = 9;
constexpr size_t kIdentityRadius = 8;
constexpr size_t kMultiWriteLimit = 32;
constexpr size_t kRecoveryLimit = 32;
constexpr size_t kRelocationTrackLimit = 25'000;
constexpr size_t kWatchLimit = 128;
constexpr size_t kLiveOverlayLimit = 2'048;
constexpr size_t kAddressCheckpointStride = 256;
constexpr jint kMaxInspectRadius = 256;
constexpr jint kMaxNearbyRadius = 4096;

struct Range {
    uintptr_t start;
    uintptr_t end;
};

struct Target {
    pid_t pid = 0;
    size_t pageSize = 0;
    jlong token = 0;
    uint64_t generation = 0;
    std::vector<Range> ranges;
};

struct Candidate {
    uint64_t id = 0;
    uintptr_t address = 0;
    uint64_t initialBits = 0;
    uint64_t previousBits = 0;
    uint64_t currentBits = 0;
    uint64_t identityHash = 0;
    uint32_t relocationCount = 0;
    ValueType type = ValueType::Invalid;
    uint8_t state = kStable;
    bool identityValid = false;
};

static_assert(sizeof(Candidate) <= 56,
              "Candidate storage must remain compact at multi-million scale");

struct SnapshotRun {
    uintptr_t start;
    std::vector<uint8_t> bytes;
};

enum class StateMode {
    Empty,
    Unknown,
    Candidates,
};

struct SearchState {
    StateMode mode = StateMode::Empty;
    jint requestedType = kTypeAuto;
    uint64_t logicalCount = 0;
    bool candidateOrderDirty = false;
    std::vector<SnapshotRun> snapshots;
    std::vector<Candidate> candidates;
    std::vector<Candidate> watches;
    // Candidate-vector offsets for every Nth unique raw address. This keeps deep result paging
    // bounded without duplicating the full result list or changing CandidateId semantics.
    std::vector<size_t> addressCheckpoints;

    size_t retainedBytes() const {
        size_t result = sizeof(SearchState);
        const auto addAllocation = [&](size_t count, size_t elementSize) {
            if (count > std::numeric_limits<size_t>::max() / elementSize ||
                result > std::numeric_limits<size_t>::max() - count * elementSize) {
                return false;
            }
            result += count * elementSize;
            return true;
        };
        // capacity(), not size(), is the heap retained by a published state. This matters after
        // a dense scan shrinks sharply and its state is kept only for Undo.
        if (!addAllocation(candidates.capacity(), sizeof(Candidate)) ||
            !addAllocation(watches.capacity(), sizeof(Candidate)) ||
            !addAllocation(addressCheckpoints.capacity(), sizeof(size_t)) ||
            !addAllocation(snapshots.capacity(), sizeof(SnapshotRun))) {
            return std::numeric_limits<size_t>::max();
        }
        for (const SnapshotRun &snapshot : snapshots) {
            if (result > std::numeric_limits<size_t>::max() -
                    snapshot.bytes.capacity()) {
                return std::numeric_limits<size_t>::max();
            }
            result += snapshot.bytes.capacity();
        }
        return result;
    }
};

struct Query {
    ValueType type = ValueType::Invalid;
    bool floating = false;
    int64_t integerFirst = 0;
    int64_t integerSecond = 0;
    uint64_t deltaFirst = 0;
    uint64_t deltaSecond = 0;
    double floatingFirst = 0;
    double floatingSecond = 0;
};

std::mutex gMutex;
Target gTarget;
const std::shared_ptr<const SearchState> gEmptyState =
        std::make_shared<SearchState>();
std::shared_ptr<const SearchState> gState = gEmptyState;
std::deque<std::shared_ptr<const SearchState>> gHistory;
std::unordered_map<uint64_t, Candidate> gLiveCandidates;
uint64_t gNextCandidateId = 1;
// The service serializes scan work, but cancellation arrives on a Binder thread. A boolean can
// lose a cancellation in the small interval between the service's preflight check and native
// operation setup. Generations make that hand-off monotonic instead.
std::atomic<uint64_t> gCancellationEpoch{0};
std::atomic<uint64_t> gPreparedCancellationEpoch{0};
std::atomic<uint64_t> gScanBytesScanned{0};
std::atomic<uint64_t> gScanBytesTotal{0};
std::string gLastMessage;

void setMessage(const char *message) {
    std::lock_guard<std::mutex> lock(gMutex);
    gLastMessage = message;
}

[[nodiscard]] constexpr size_t widthOf(ValueType type) noexcept {
    switch (type) {
    case ValueType::Invalid:
        return 0;
    case ValueType::Byte:
        return 1;
    case ValueType::Short:
    case ValueType::Char:
        return 2;
    case ValueType::Int:
    case ValueType::Float:
        return 4;
    case ValueType::Long:
    case ValueType::Double:
        return 8;
    }
    return 0;
}

static_assert(widthOf(ValueType::Invalid) == 0);
static_assert(widthOf(ValueType::Byte) == 1);
static_assert(widthOf(ValueType::Short) == 2);
static_assert(widthOf(ValueType::Char) == 2);
static_assert(widthOf(ValueType::Int) == 4);
static_assert(widthOf(ValueType::Long) == 8);
static_assert(widthOf(ValueType::Float) == 4);
static_assert(widthOf(ValueType::Double) == 8);

[[nodiscard]] size_t widthOf(jint rawType) noexcept {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() ? widthOf(*type) : 0U;
}

std::vector<ValueType> expandedTypes(jint requestedType) {
    if (requestedType == kTypeAuto) {
        // Prefer the representations most useful for J2ME gameplay before
        // noisy narrow aliases fill the first result pages.
        return {ValueType::Int, ValueType::Float, ValueType::Long,
                ValueType::Double, ValueType::Short, ValueType::Char,
                ValueType::Byte};
    }
    const auto type = valueTypeFromJint(requestedType);
    return type.has_value() ? std::vector<ValueType>{*type}
                            : std::vector<ValueType>{};
}

bool parseInteger(const std::string &text, ValueType type, int64_t &value) {
    if (text.empty()) {
        return false;
    }
    errno = 0;
    char *end = nullptr;
    const char *number = text.c_str();
    while (*number == ' ' || *number == '\t' || *number == '\r' ||
           *number == '\n') {
        ++number;
    }
    const char *prefix = *number == '+' || *number == '-' ? number + 1 : number;
    const int base = prefix[0] == '0' && (prefix[1] == 'x' || prefix[1] == 'X')
                             ? 16
                             : 10;
    const long long parsed = std::strtoll(text.c_str(), &end, base);
    while (end != nullptr &&
           (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n')) {
        ++end;
    }
    if (errno == ERANGE || end == text.c_str() || end == nullptr ||
        *end != '\0') {
        return false;
    }
    int64_t minimum = std::numeric_limits<int64_t>::min();
    int64_t maximum = std::numeric_limits<int64_t>::max();
    switch (type) {
    case ValueType::Byte:
        minimum = std::numeric_limits<int8_t>::min();
        maximum = std::numeric_limits<int8_t>::max();
        break;
    case ValueType::Short:
        minimum = std::numeric_limits<int16_t>::min();
        maximum = std::numeric_limits<int16_t>::max();
        break;
    case ValueType::Char:
        minimum = 0;
        maximum = std::numeric_limits<uint16_t>::max();
        break;
    case ValueType::Int:
        minimum = std::numeric_limits<int32_t>::min();
        maximum = std::numeric_limits<int32_t>::max();
        break;
    case ValueType::Long:
        break;
    default:
        return false;
    }
    value = static_cast<int64_t>(parsed);
    return value >= minimum && value <= maximum;
}

bool parseFloating(const std::string &text, double &value) {
    if (text.empty()) {
        return false;
    }
    errno = 0;
    char *end = nullptr;
    value = std::strtod(text.c_str(), &end);
    while (end != nullptr &&
           (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n')) {
        ++end;
    }
    return errno != ERANGE && end != text.c_str() && end != nullptr &&
           *end == '\0' && std::isfinite(value);
}

bool parseDelta(const std::string &text, ValueType type, uint64_t &value) {
    if (text.empty()) {
        return false;
    }
    const char *number = text.c_str();
    while (*number == ' ' || *number == '\t' || *number == '\r' ||
           *number == '\n') {
        ++number;
    }
    if (*number == '-') {
        return false;
    }
    if (*number == '+') {
        ++number;
    }
    const int base = number[0] == '0' && (number[1] == 'x' || number[1] == 'X')
                             ? 16
                             : 10;
    errno = 0;
    char *end = nullptr;
    const unsigned long long parsed = std::strtoull(text.c_str(), &end, base);
    while (end != nullptr &&
           (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n')) {
        ++end;
    }
    if (errno == ERANGE || end == text.c_str() || end == nullptr ||
        *end != '\0') {
        return false;
    }
    uint64_t maximum = 0;
    switch (type) {
    case ValueType::Byte:
        maximum = std::numeric_limits<uint8_t>::max();
        break;
    case ValueType::Short:
    case ValueType::Char:
        maximum = std::numeric_limits<uint16_t>::max();
        break;
    case ValueType::Int:
        maximum = std::numeric_limits<uint32_t>::max();
        break;
    case ValueType::Long:
        maximum = std::numeric_limits<uint64_t>::max();
        break;
    default:
        return false;
    }
    value = static_cast<uint64_t>(parsed);
    return value <= maximum;
}

bool predicateNeedsFirst(jint predicate) {
    return predicate <= kBetween || predicate >= kIncreasedBy;
}

bool predicateNeedsSecond(jint predicate) {
    return predicate == kBetween || predicate == kIncreasedByRange ||
           predicate == kDecreasedByRange;
}

bool parseQuery(ValueType type, jint predicate, const std::string &first,
                const std::string &second, Query &query) {
    query.type = type;
    query.floating = isFloating(type);
    const bool deltaPredicate = predicate >= kIncreasedBy;
    if (predicateNeedsFirst(predicate)) {
        if (query.floating) {
            if (!parseFloating(first, query.floatingFirst)) {
                return false;
            }
            if (deltaPredicate && query.floatingFirst < 0) {
                return false;
            }
            if (type == ValueType::Float) {
                const float rounded = static_cast<float>(query.floatingFirst);
                if (!std::isfinite(rounded)) {
                    return false;
                }
                query.floatingFirst = rounded;
            }
        } else if (deltaPredicate) {
            if (!parseDelta(first, type, query.deltaFirst)) {
                return false;
            }
        } else if (!parseInteger(first, type, query.integerFirst)) {
            return false;
        }
    }
    if (predicateNeedsSecond(predicate)) {
        if (query.floating) {
            if (!parseFloating(second, query.floatingSecond) ||
                query.floatingFirst > query.floatingSecond) {
                return false;
            }
            if (deltaPredicate && query.floatingSecond < 0) {
                return false;
            }
            if (type == ValueType::Float) {
                const float rounded = static_cast<float>(query.floatingSecond);
                if (!std::isfinite(rounded)) {
                    return false;
                }
                query.floatingSecond = rounded;
            }
        } else if (deltaPredicate) {
            if (!parseDelta(second, type, query.deltaSecond) ||
                query.deltaFirst > query.deltaSecond) {
                return false;
            }
        } else if (!parseInteger(second, type, query.integerSecond) ||
                   query.integerFirst > query.integerSecond) {
            return false;
        }
    }
    return true;
}

bool parseQuery(jint rawType, jint predicate, const std::string &first,
                const std::string &second, Query &query) {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() && parseQuery(*type, predicate, first, second, query);
}

[[nodiscard]] uint64_t loadBits(std::span<const uint8_t> bytes) noexcept {
    if (bytes.size() > sizeof(uint64_t)) {
        return 0;
    }
    uint64_t result = 0;
    std::memcpy(&result, bytes.data(), bytes.size());
    return result;
}

[[nodiscard]] uint64_t loadBits(const uint8_t *data, size_t width) noexcept {
    if (data == nullptr && width != 0U) {
        return 0;
    }
    return loadBits(std::span<const uint8_t>(data, width));
}

Candidate makeCandidate(uint64_t id, uintptr_t address, ValueType type,
                        uint64_t initialBits, uint64_t currentBits) {
    Candidate candidate{};
    candidate.id = id;
    candidate.address = address;
    candidate.initialBits = initialBits;
    candidate.previousBits = initialBits;
    candidate.currentBits = currentBits;
    candidate.type = type;
    return candidate;
}

[[nodiscard]] uint64_t identityHash(std::span<const uint8_t> before,
                                    std::span<const uint8_t> after) noexcept {
    if (before.size() != kIdentityRadius || after.size() != kIdentityRadius) {
        return 0;
    }
    uint64_t hash = UINT64_C(1469598103934665603);
    for (const uint8_t value : before) {
        hash ^= value;
        hash *= UINT64_C(1099511628211);
    }
    for (const uint8_t value : after) {
        hash ^= value;
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (width == 0 || offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
        return false;
    }
    const size_t valueEnd = offset + width;
    if (bytes.size() - valueEnd < kIdentityRadius) {
        return false;
    }
    hash = identityHash(bytes.subspan(offset - kIdentityRadius, kIdentityRadius),
                        bytes.subspan(valueEnd, kIdentityRadius));
    return true;
}

bool snapshotIdentity(const uint8_t *bytes, size_t size, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (bytes == nullptr && size != 0U) {
        return false;
    }
    return snapshotIdentity(std::span<const uint8_t>(bytes, size), offset, width,
                            hash);
}

int64_t integerValue(ValueType type, uint64_t bits) {
    switch (type) {
    case ValueType::Byte:
        return static_cast<int8_t>(bits);
    case ValueType::Short:
        return static_cast<int16_t>(bits);
    case ValueType::Char:
        return static_cast<uint16_t>(bits);
    case ValueType::Int:
        return static_cast<int32_t>(bits);
    case ValueType::Long:
        return static_cast<int64_t>(bits);
    default:
        return 0;
    }
}

double floatingValue(ValueType type, uint64_t bits) noexcept {
    if (type == ValueType::Float) {
        return std::bit_cast<float>(static_cast<uint32_t>(bits));
    }
    return std::bit_cast<double>(bits);
}

template <typename T>
bool matchesOrdered(T value, jint predicate, T first, T second) {
    switch (predicate) {
    case kEqual:
        return value == first;
    case kNotEqual:
        return value != first;
    case kGreater:
        return value > first;
    case kLess:
        return value < first;
    case kGreaterOrEqual:
        return value >= first;
    case kLessOrEqual:
        return value <= first;
    case kBetween:
        return value >= first && value <= second;
    default:
        return false;
    }
}

bool matchesKnown(uint64_t bits, const Query &query, jint predicate) {
    if (query.floating) {
        const double value = floatingValue(query.type, bits);
        return !std::isnan(value) &&
               matchesOrdered(value, predicate, query.floatingFirst,
                              query.floatingSecond);
    }
    return matchesOrdered(integerValue(query.type, bits), predicate,
                          query.integerFirst, query.integerSecond);
}

bool matchesRelative(uint64_t currentBits, uint64_t referenceBits,
                     const Query &query, jint predicate) {
    if (query.floating) {
        const double current = floatingValue(query.type, currentBits);
        const double reference = floatingValue(query.type, referenceBits);
        if (std::isnan(current) || std::isnan(reference)) {
            return false;
        }
        const double delta = current - reference;
        switch (predicate) {
        case kChanged:
            return current != reference;
        case kUnchanged:
            return current == reference;
        case kIncreased:
            return current > reference;
        case kDecreased:
            return current < reference;
        case kIncreasedBy:
            return delta == query.floatingFirst;
        case kDecreasedBy:
            return -delta == query.floatingFirst;
        case kChangedBy:
            return std::fabs(delta) == std::fabs(query.floatingFirst);
        case kIncreasedByRange:
            return delta >= query.floatingFirst &&
                   delta <= query.floatingSecond;
        case kDecreasedByRange:
            return -delta >= query.floatingFirst &&
                   -delta <= query.floatingSecond;
        default:
            return false;
        }
    }

    const int64_t current = integerValue(query.type, currentBits);
    const int64_t reference = integerValue(query.type, referenceBits);
    const bool increased = current >= reference;
    const uint64_t magnitude =
            increased ? static_cast<uint64_t>(current) -
                                static_cast<uint64_t>(reference)
                      : static_cast<uint64_t>(reference) -
                                static_cast<uint64_t>(current);
    switch (predicate) {
    case kChanged:
        return current != reference;
    case kUnchanged:
        return current == reference;
    case kIncreased:
        return current > reference;
    case kDecreased:
        return current < reference;
    case kIncreasedBy:
        return increased && magnitude == query.deltaFirst;
    case kDecreasedBy:
        return !increased && magnitude == query.deltaFirst;
    case kChangedBy:
        return magnitude == query.deltaFirst;
    case kIncreasedByRange:
        return increased && magnitude >= query.deltaFirst &&
               magnitude <= query.deltaSecond;
    case kDecreasedByRange:
        return !increased && magnitude >= query.deltaFirst &&
               magnitude <= query.deltaSecond;
    default:
        return false;
    }
}

[[nodiscard]] constexpr bool checkedAddressAdd(uintptr_t base, size_t offset,
                                               uintptr_t &result) noexcept {
    if (offset > std::numeric_limits<uintptr_t>::max() - base) {
        return false;
    }
    result = base + static_cast<uintptr_t>(offset);
    return true;
}

[[nodiscard]] constexpr bool isAddressSpanValid(uintptr_t address,
                                                size_t size) noexcept {
    if (size == 0U) {
        return true;
    }
    uintptr_t lastByte = 0;
    return checkedAddressAdd(address, size - 1U, lastByte);
}

bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    if ((destination == nullptr && size != 0U) ||
        !isAddressSpanValid(address, size)) {
        return false;
    }
    auto *output = static_cast<uint8_t *>(destination);
    size_t completed = 0;
    while (completed < size) {
        uintptr_t remoteAddress = 0;
        if (!checkedAddressAdd(address, completed, remoteAddress)) {
            return false;
        }
        iovec local{output + completed, size - completed};
        iovec remote{reinterpret_cast<void *>(remoteAddress), size - completed};
        const ssize_t read = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (read < 0 && errno == EINTR) {
            continue;
        }
        if (read <= 0) {
            return false;
        }
        completed += static_cast<size_t>(read);
    }
    return true;
}

bool writeExact(pid_t pid, uintptr_t address, const void *source, size_t size) {
    if ((source == nullptr && size != 0U) ||
        !isAddressSpanValid(address, size)) {
        return false;
    }
    const auto *input = static_cast<const uint8_t *>(source);
    size_t completed = 0;
    while (completed < size) {
        uintptr_t remoteAddress = 0;
        if (!checkedAddressAdd(address, completed, remoteAddress)) {
            return false;
        }
        iovec local{const_cast<uint8_t *>(input + completed), size - completed};
        iovec remote{reinterpret_cast<void *>(remoteAddress), size - completed};
        const ssize_t written =
                process_vm_writev(pid, &local, 1, &remote, 1, 0);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            return false;
        }
        completed += static_cast<size_t>(written);
    }
    return true;
}

bool readIdentity(const Target &target, uintptr_t address, ValueType type,
                  uint64_t &hash) {
    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (width == 0 || address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
        !checkedAddressAdd(valueEnd, kIdentityRadius, contextEnd)) {
        return false;
    }
    const uintptr_t contextStart = address - kIdentityRadius;
    const auto range = std::find_if(
            target.ranges.begin(), target.ranges.end(), [&](const Range &item) {
                return item.start <= contextStart && item.end >= contextEnd;
            });
    if (range == target.ranges.end()) {
        return false;
    }
    std::array<uint8_t, kIdentityRadius * 2U> context{};
    std::array<iovec, 2> local{{
            {context.data(), kIdentityRadius},
            {context.data() + kIdentityRadius, kIdentityRadius},
    }};
    std::array<iovec, 2> remote{{
            {reinterpret_cast<void *>(contextStart), kIdentityRadius},
            {reinterpret_cast<void *>(valueEnd), kIdentityRadius},
    }};
    ssize_t result;
    do {
        result = process_vm_readv(target.pid, local.data(), local.size(),
                                  remote.data(), remote.size(), 0);
    } while (result < 0 && errno == EINTR);
    if (result != static_cast<ssize_t>(context.size())) {
        return false;
    }
    hash = identityHash(std::span<const uint8_t>(context).first(kIdentityRadius),
                        std::span<const uint8_t>(context).last(kIdentityRadius));
    return true;
}

bool readIdentity(const Target &target, uintptr_t address, jint rawType,
                  uint64_t &hash) {
    const auto type = valueTypeFromJint(rawType);
    return type.has_value() && readIdentity(target, address, *type, hash);
}

void captureIdentities(const Target &target,
                       std::vector<Candidate> &candidates) {
    if (candidates.empty() || candidates.size() > kRelocationTrackLimit) {
        return;
    }
    for (Candidate &candidate : candidates) {
        candidate.identityValid = readIdentity(
                target, candidate.address, candidate.type,
                candidate.identityHash);
    }
}

void fillMissingIdentities(const Target &target,
                           std::vector<Candidate> &candidates) {
    if (candidates.empty() || candidates.size() > kRelocationTrackLimit) {
        return;
    }
    for (Candidate &candidate : candidates) {
        if (!candidate.identityValid) {
            candidate.identityValid = readIdentity(
                    target, candidate.address, candidate.type,
                    candidate.identityHash);
        }
    }
}

bool safeAdd(uint64_t &value, uint64_t addition) {
    if (value > std::numeric_limits<uint64_t>::max() - addition) {
        value = std::numeric_limits<uint64_t>::max();
        return false;
    }
    value += addition;
    return true;
}

void beginScanProgress(const Target &target) {
    uint64_t total = 0;
    for (const Range &range : target.ranges) {
        if (range.end < range.start ||
            !safeAdd(total, static_cast<uint64_t>(range.end - range.start))) {
            total = std::numeric_limits<uint64_t>::max();
            break;
        }
    }
    gScanBytesScanned.store(0, std::memory_order_release);
    gScanBytesTotal.store(total, std::memory_order_release);
}

void advanceScanProgress(size_t bytes) {
    const uint64_t total = gScanBytesTotal.load(std::memory_order_acquire);
    if (total == 0) {
        return;
    }
    uint64_t current = gScanBytesScanned.load(std::memory_order_relaxed);
    while (current < total &&
           !gScanBytesScanned.compare_exchange_weak(
                   current,
                   std::min(total, current + static_cast<uint64_t>(bytes)),
                   std::memory_order_release,
                   std::memory_order_relaxed)) {
    }
}

struct OperationContext {
    Target target;
    std::shared_ptr<const SearchState> state;
    std::unordered_map<uint64_t, Candidate> liveCandidates;
    uint64_t nextId;
    uint64_t cancellationEpoch;
};

bool resolveCandidateById(const OperationContext &context, uint64_t id,
                          Candidate &resolved);
bool verifyCandidateBinding(const Target &target, const Candidate &candidate);
bool candidateWindow(const Target &target, const Candidate &candidate,
                     size_t radius, Range &window);

bool isCancelled(uint64_t cancellationEpoch) {
    return gCancellationEpoch.load(std::memory_order_acquire) !=
           cancellationEpoch;
}

bool isCancelled(const OperationContext &context) {
    return isCancelled(context.cancellationEpoch);
}

bool beginOperation(OperationContext &context) {
    context.cancellationEpoch =
            gPreparedCancellationEpoch.load(std::memory_order_acquire);
    if (isCancelled(context)) {
        setMessage("Operation cancelled before it started");
        return false;
    }
    gScanBytesScanned.store(0, std::memory_order_release);
    gScanBytesTotal.store(0, std::memory_order_release);
    std::lock_guard<std::mutex> lock(gMutex);
    if (gTarget.pid <= 0 || gTarget.token == 0 || gTarget.ranges.empty()) {
        gLastMessage = "No configured MIDlet runtime";
        return false;
    }
    context.target = gTarget;
    context.state = gState;
    context.liveCandidates = gLiveCandidates;
    context.nextId = gNextCandidateId;
    return true;
}

const Candidate &liveCandidate(
        const Candidate &candidate,
        const std::unordered_map<uint64_t, Candidate> &liveCandidates) {
    const auto live = liveCandidates.find(candidate.id);
    return live == liveCandidates.end() ? candidate : live->second;
}

jint publishLiveCandidates(const OperationContext &context,
                           const std::vector<Candidate> &candidates) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gTarget.generation != context.target.generation ||
        gTarget.token != context.target.token || gState != context.state) {
        gLastMessage = "MIDlet runtime or search changed during live refresh";
        return kTargetLost;
    }
    if (gLiveCandidates.size() + candidates.size() > kLiveOverlayLimit) {
        gLiveCandidates.clear();
    }
    for (const Candidate &candidate : candidates) {
        gLiveCandidates.insert_or_assign(candidate.id, candidate);
    }
    gLastMessage = "";
    return kOk;
}

void trimHistoryLocked(size_t currentBytes) {
    size_t retained = currentBytes;
    for (const auto &state : gHistory) {
        const size_t bytes = state->retainedBytes();
        retained = retained > std::numeric_limits<size_t>::max() - bytes
                           ? std::numeric_limits<size_t>::max()
                           : retained + bytes;
    }
    while (!gHistory.empty() &&
           (gHistory.size() > kHistoryLimit || retained > kRetainedStateByteLimit)) {
        const size_t removed = gHistory.front()->retainedBytes();
        gHistory.pop_front();
        retained = removed > retained ? 0 : retained - removed;
    }
}

int displayTypePriority(ValueType type) {
    switch (type) {
    case ValueType::Invalid:
        return 7;
    case ValueType::Int:
        return 0;
    case ValueType::Float:
        return 1;
    case ValueType::Long:
        return 2;
    case ValueType::Double:
        return 3;
    case ValueType::Short:
        return 4;
    case ValueType::Char:
        return 5;
    case ValueType::Byte:
        return 6;
    return 7;
    }
}

bool candidateDisplayOrder(const Candidate &left, const Candidate &right) {
    if (left.address != right.address) {
        return left.address < right.address;
    }
    const int leftPriority = displayTypePriority(left.type);
    const int rightPriority = displayTypePriority(right.type);
    return leftPriority != rightPriority ? leftPriority < rightPriority
                                         : left.id < right.id;
}

void normalizeCandidateResults(SearchState &state) {
    if (state.mode != StateMode::Candidates) {
        state.addressCheckpoints.clear();
        return;
    }
    if (!state.candidateOrderDirty) {
        return;
    }
    if (!std::is_sorted(state.candidates.begin(), state.candidates.end(),
                        candidateDisplayOrder)) {
        std::sort(state.candidates.begin(), state.candidates.end(),
                  candidateDisplayOrder);
    }
    state.logicalCount = 0;
    state.addressCheckpoints.clear();
    state.addressCheckpoints.reserve(
            (state.candidates.size() + kAddressCheckpointStride - 1U) /
            kAddressCheckpointStride);
    size_t candidateIndex = 0;
    size_t addressIndex = 0;
    while (candidateIndex < state.candidates.size()) {
        if (addressIndex % kAddressCheckpointStride == 0) {
            state.addressCheckpoints.push_back(candidateIndex);
        }
        const uintptr_t address = state.candidates[candidateIndex].address;
        do {
            ++candidateIndex;
        } while (candidateIndex < state.candidates.size() &&
                 state.candidates[candidateIndex].address == address);
        ++state.logicalCount;
        ++addressIndex;
    }
    state.candidateOrderDirty = false;
}

jint commitOperation(const OperationContext &context,
                     std::shared_ptr<SearchState> next, jint historyMode,
                     bool preserveLive = false) {
    if (isCancelled(context)) {
        setMessage("Operation cancelled; previous results were preserved");
        return kCancelled;
    }
    normalizeCandidateResults(*next);
    const size_t nextBytes = next->retainedBytes();
    if (nextBytes > kRetainedStateByteLimit) {
        setMessage("Completed search state exceeds the safe memory budget; previous results were preserved");
        return kResourceLimit;
    }
    std::lock_guard<std::mutex> lock(gMutex);
    if (gTarget.generation != context.target.generation ||
        gTarget.token != context.target.token) {
        gLastMessage = "MIDlet runtime changed during the operation";
        return kTargetLost;
    }
    if (historyMode > 0 && gState->mode != StateMode::Empty) {
        // Search states are immutable after publication. Retaining the shared snapshot makes
        // Undo O(1) here instead of copying up to two million candidate records on every refine.
        gHistory.push_back(gState);
    } else if (historyMode < 0) {
        gHistory.clear();
    }
    trimHistoryLocked(nextBytes);
    gState = std::move(next);
    if (!preserveLive) {
        gLiveCandidates.clear();
    }
    gNextCandidateId = context.nextId;
    gLastMessage = "";
    return kOk;
}

bool buildQueries(jint requestedType, jint predicate, const std::string &first,
                  const std::string &second, bool relative,
                  std::vector<Query> &queries) {
    if ((!relative && (predicate < kEqual || predicate > kBetween)) ||
        (relative && (predicate < kChanged || predicate > kDecreasedByRange))) {
        return false;
    }
    for (ValueType type : expandedTypes(requestedType)) {
        Query query;
        if (parseQuery(type, predicate, first, second, query)) {
            queries.push_back(query);
        } else if (requestedType != kTypeAuto) {
            return false;
        }
    }
    return !queries.empty();
}

jint collectKnown(const OperationContext &context, jint requestedType,
                  jint predicate, const std::string &first,
                  const std::string &second,
                  std::shared_ptr<SearchState> &next) {
    if (context.nextId >
        std::numeric_limits<uint64_t>::max() - kCandidateLimit) {
        setMessage("Candidate identifier space is exhausted for this runtime");
        return kResourceLimit;
    }
    std::vector<Query> queries;
    if (!buildQueries(requestedType, predicate, first, second, false,
                      queries)) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }
    next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = requestedType;
    next->watches = context.state->watches;
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }
    const bool fusedAuto = requestedType == kTypeAuto && queries.size() > 1U;
    const Query *byteQuery = queriesByType[typeIndex(ValueType::Byte)];
    const Query *shortQuery = queriesByType[typeIndex(ValueType::Short)];
    const Query *charQuery = queriesByType[typeIndex(ValueType::Char)];
    const Query *intQuery = queriesByType[typeIndex(ValueType::Int)];
    const Query *longQuery = queriesByType[typeIndex(ValueType::Long)];
    const Query *floatQuery = queriesByType[typeIndex(ValueType::Float)];
    const Query *doubleQuery = queriesByType[typeIndex(ValueType::Double)];
    std::vector<uint8_t> buffer;
    beginScanProgress(context.target);

    for (const Range &range : context.target.ranges) {
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (isCancelled(context)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const size_t remaining =
                    static_cast<size_t>(range.end - chunkStart);
            const size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            if (!readExact(context.target.pid, chunkStart, buffer.data(),
                           chunkSize)) {
                setMessage("A target range changed while it was being scanned");
                return kTargetLost;
            }
            const auto materialize = [&](const Query *query, uintptr_t address,
                                         size_t offset, size_t width,
                                         uint64_t bits) -> bool {
                if (query == nullptr || !matchesKnown(bits, *query, predicate)) {
                    return true;
                }
                if (next->candidates.size() >= kCandidateLimit) {
                    setMessage("Candidate limit reached; previous results were preserved");
                    return false;
                }
                Candidate candidate = makeCandidate(
                        context.nextId + next->candidates.size(), address,
                        query->type, bits, bits);
                candidate.identityValid = snapshotIdentity(
                        buffer.data(), chunkSize, offset, width,
                        candidate.identityHash);
                next->candidates.push_back(candidate);
                return true;
            };
            if (fusedAuto) {
                // Auto evaluates each physical location once. The order below mirrors
                // candidateDisplayOrder, so no per-chunk sort is needed.
                for (size_t offset = 0; offset < chunkSize; ++offset) {
                    const uintptr_t address = chunkStart + offset;
                    if ((intQuery != nullptr || floatQuery != nullptr) &&
                        address % 4U == 0U && offset + 4U <= chunkSize) {
                        const uint64_t bits = loadBits(buffer.data() + offset, 4U);
                        if (!materialize(intQuery, address, offset, 4U, bits) ||
                            !materialize(floatQuery, address, offset, 4U, bits)) {
                            return kResourceLimit;
                        }
                    }
                    if ((longQuery != nullptr || doubleQuery != nullptr) &&
                        address % 8U == 0U && offset + 8U <= chunkSize) {
                        const uint64_t bits = loadBits(buffer.data() + offset, 8U);
                        if (!materialize(longQuery, address, offset, 8U, bits) ||
                            !materialize(doubleQuery, address, offset, 8U, bits)) {
                            return kResourceLimit;
                        }
                    }
                    if ((shortQuery != nullptr || charQuery != nullptr) &&
                        address % 2U == 0U && offset + 2U <= chunkSize) {
                        const uint64_t bits = loadBits(buffer.data() + offset, 2U);
                        if (!materialize(shortQuery, address, offset, 2U, bits) ||
                            !materialize(charQuery, address, offset, 2U, bits)) {
                            return kResourceLimit;
                        }
                    }
                    if (byteQuery != nullptr &&
                        !materialize(byteQuery, address, offset, 1U, buffer[offset])) {
                        return kResourceLimit;
                    }
                }
            } else {
                const size_t chunkCandidateStart = next->candidates.size();
                for (const Query &query : queries) {
                    const size_t width = widthOf(query.type);
                    uintptr_t address = chunkStart;
                    const size_t misalignment =
                            static_cast<size_t>(address % width);
                    if (misalignment != 0) {
                        address += width - misalignment;
                    }
                    while (address >= chunkStart &&
                           address <= chunkStart + chunkSize -
                                              std::min(width, chunkSize)) {
                        const size_t offset =
                                static_cast<size_t>(address - chunkStart);
                        if (offset + width > chunkSize) {
                            break;
                        }
                        const uint64_t bits =
                                loadBits(buffer.data() + offset, width);
                        if (!materialize(&query, address, offset, width, bits)) {
                            return kResourceLimit;
                        }
                        if (address >
                            std::numeric_limits<uintptr_t>::max() - width) {
                            break;
                        }
                        address += width;
                    }
                }
                std::sort(next->candidates.begin() +
                                  static_cast<std::ptrdiff_t>(chunkCandidateStart),
                          next->candidates.end(), candidateDisplayOrder);
            }
            advanceScanProgress(chunkSize);
            chunkStart += chunkSize;
        }
    }
    // Most identities were captured from the already-read scan chunk. Only chunk/range boundary
    // candidates need additional remote context reads.
    fillMissingIdentities(context.target, next->candidates);
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    return kOk;
}

jint scanKnown(const OperationContext &context, jint requestedType,
               jint predicate, const std::string &first,
               const std::string &second) {
    if (context.nextId >
        std::numeric_limits<uint64_t>::max() - kCandidateLimit) {
        setMessage("Candidate identifier space is exhausted for this runtime");
        return kResourceLimit;
    }
    std::shared_ptr<SearchState> next;
    const jint scanResult = collectKnown(context, requestedType, predicate,
                                         first, second, next);
    if (scanResult != kOk) {
        return scanResult;
    }
    OperationContext committed = context;
    committed.nextId += next->candidates.size();
    return commitOperation(committed, std::move(next), -1);
}

jint scanNearby(const OperationContext &context, uint64_t anchorId, jint radius,
                jint requestedType, jint predicate, const std::string &first,
                const std::string &second) {
    if (anchorId == 0 || radius <= 0 || radius > kMaxNearbyRadius) {
        setMessage("Nearby Search requires a valid candidate and a bounded radius");
        return kInvalidRequest;
    }
    Candidate anchor{};
    if (!resolveCandidateById(context, anchorId, anchor)) {
        setMessage("Nearby Search anchor is no longer available");
        return kInvalidRequest;
    }
    if (!verifyCandidateBinding(context.target, anchor)) {
        return kIdentityUnsafe;
    }
    Range window{};
    if (!candidateWindow(context.target, anchor, static_cast<size_t>(radius),
                         window)) {
        setMessage("Nearby Search anchor is outside the current resident ranges");
        return kTargetLost;
    }

    OperationContext nearby = context;
    nearby.target.ranges.clear();
    nearby.target.ranges.push_back(window);
    std::shared_ptr<SearchState> next;
    const jint scanResult = collectKnown(nearby, requestedType, predicate, first,
                                         second, next);
    if (scanResult != kOk) {
        return scanResult;
    }
    // The scan window is intentionally narrow, but relocation fingerprints may extend just
    // outside it. Fill those boundary identities against the full configured resident set.
    fillMissingIdentities(context.target, next->candidates);
    const size_t resultCount = next->candidates.size();
    OperationContext committed = context;
    committed.nextId += resultCount;
    const jint result = commitOperation(committed, std::move(next), -1);
    if (result == kOk) {
        setMessage(("Nearby Search found " + std::to_string(resultCount) +
                    " typed candidates in the bounded window")
                           .c_str());
    }
    return result;
}

struct GroupMatch {
    uintptr_t address;
    ValueType type;
    uint64_t bits;
};

jint scanGroup(const OperationContext &context, const std::vector<jint> &types,
               const std::vector<std::string> &values, jint maxDistance) {
    if (types.size() < 2 || types.size() > 8 || values.size() != types.size() ||
        maxDistance <= 0 || maxDistance > 4096 ||
        context.nextId >
                std::numeric_limits<uint64_t>::max() - kCandidateLimit) {
        setMessage(
                "Group Search requires 2-8 typed values within 1-4096 bytes");
        return kInvalidRequest;
    }
    std::vector<Query> queries;
    queries.reserve(types.size());
    for (size_t index = 0; index < types.size(); ++index) {
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

    std::vector<std::vector<GroupMatch>> matches(queries.size());
    size_t totalMatches = 0;
    std::vector<uint8_t> buffer;
    beginScanProgress(context.target);
    for (const Range &range : context.target.ranges) {
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (isCancelled(context)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const size_t remaining =
                    static_cast<size_t>(range.end - chunkStart);
            const size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            if (!readExact(context.target.pid, chunkStart, buffer.data(),
                           chunkSize)) {
                setMessage("A target range changed during Group Search");
                return kTargetLost;
            }
            for (size_t queryIndex = 0; queryIndex < queries.size();
                 ++queryIndex) {
                const Query &query = queries[queryIndex];
                const size_t width = widthOf(query.type);
                uintptr_t address = chunkStart;
                const size_t misalignment =
                        static_cast<size_t>(address % width);
                if (misalignment != 0) {
                    address += width - misalignment;
                }
                while (address >= chunkStart) {
                    const size_t offset =
                            static_cast<size_t>(address - chunkStart);
                    if (offset + width > chunkSize) {
                        break;
                    }
                    const uint64_t bits =
                            loadBits(buffer.data() + offset, width);
                    if (matchesKnown(bits, query, kEqual)) {
                        if (totalMatches++ >= kCandidateLimit) {
                            setMessage("Group Search intermediate matches "
                                       "exceed the resource limit");
                            return kResourceLimit;
                        }
                        matches[queryIndex].push_back(
                                {address, query.type, bits});
                    }
                    if (address >
                        std::numeric_limits<uintptr_t>::max() - width) {
                        break;
                    }
                    address += width;
                }
            }
            advanceScanProgress(chunkSize);
            chunkStart += chunkSize;
        }
    }

    size_t rarestTerm = 0;
    for (size_t index = 1; index < matches.size(); ++index) {
        if (matches[index].size() < matches[rarestTerm].size()) {
            rarestTerm = index;
        }
    }
    std::vector<GroupMatch> retained;
    const auto retainForFirstAnchor = [&](const GroupMatch &anchor) {
        std::vector<GroupMatch> group{anchor};
        const uintptr_t minimum =
                anchor.address < static_cast<uintptr_t>(maxDistance)
                        ? 0
                        : anchor.address - static_cast<uintptr_t>(maxDistance);
        const uintptr_t maximum =
                anchor.address > std::numeric_limits<uintptr_t>::max() -
                                         static_cast<uintptr_t>(maxDistance)
                        ? std::numeric_limits<uintptr_t>::max()
                        : anchor.address + static_cast<uintptr_t>(maxDistance);
        for (size_t term = 1; term < matches.size(); ++term) {
            const auto found = std::lower_bound(
                    matches[term].begin(), matches[term].end(), minimum,
                    [](const GroupMatch &match, uintptr_t address) {
                        return match.address < address;
                    });
            if (found == matches[term].end() || found->address > maximum) {
                return;
            }
            group.push_back(*found);
        }
        retained.insert(retained.end(), group.begin(), group.end());
    };
    if (rarestTerm == 0) {
        for (const GroupMatch &anchor : matches.front()) {
            retainForFirstAnchor(anchor);
        }
    } else {
        // The first term remains the semantic anchor. The rarest term is only a prefilter:
        // every valid first-term anchor must lie within maxDistance of at least one rare match.
        std::unordered_set<uintptr_t> visitedFirstAnchors;
        for (const GroupMatch &rare : matches[rarestTerm]) {
            const uintptr_t minimum =
                    rare.address < static_cast<uintptr_t>(maxDistance)
                            ? 0
                            : rare.address - static_cast<uintptr_t>(maxDistance);
            const uintptr_t maximum =
                    rare.address > std::numeric_limits<uintptr_t>::max() -
                                           static_cast<uintptr_t>(maxDistance)
                            ? std::numeric_limits<uintptr_t>::max()
                            : rare.address + static_cast<uintptr_t>(maxDistance);
            auto first = std::lower_bound(
                    matches.front().begin(), matches.front().end(), minimum,
                    [](const GroupMatch &match, uintptr_t address) {
                        return match.address < address;
                    });
            while (first != matches.front().end() && first->address <= maximum) {
                if (visitedFirstAnchors.insert(first->address).second) {
                    retainForFirstAnchor(*first);
                }
                ++first;
            }
        }
    }
    std::sort(retained.begin(), retained.end(),
              [](const GroupMatch &left, const GroupMatch &right) {
                  return left.address < right.address ||
                         (left.address == right.address &&
                          left.type < right.type);
              });
    retained.erase(
            std::unique(retained.begin(), retained.end(),
                        [](const GroupMatch &left, const GroupMatch &right) {
                            return left.address == right.address &&
                                   left.type == right.type;
                        }),
            retained.end());
    if (retained.size() > kCandidateLimit) {
        setMessage("Complete Group Search results exceed the candidate limit");
        return kResourceLimit;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = kTypeAuto;
    next->watches = context.state->watches;
    next->candidates.reserve(retained.size());
    for (const GroupMatch &match : retained) {
        next->candidates.push_back(makeCandidate(
                context.nextId + next->candidates.size(), match.address,
                match.type, match.bits, match.bits));
    }
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    OperationContext committed = context;
    committed.nextId += next->candidates.size();
    return commitOperation(committed, std::move(next), -1);
}

jint snapshotUnknown(const OperationContext &context, jint requestedType) {
    const std::vector<ValueType> types = expandedTypes(requestedType);
    if (types.empty()) {
        setMessage("Invalid value type");
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Unknown;
    next->requestedType = requestedType;
    next->watches = context.state->watches;
    size_t retained = 0;
    beginScanProgress(context.target);
    for (const Range &range : context.target.ranges) {
        if (isCancelled(context)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const size_t size = static_cast<size_t>(range.end - range.start);
        if (size >
            kSnapshotByteLimit - std::min(retained, kSnapshotByteLimit)) {
            setMessage("Unknown-value snapshot exceeds the memory budget");
            return kResourceLimit;
        }
        SnapshotRun snapshot;
        snapshot.start = range.start;
        snapshot.bytes.resize(size);
        if (!readExact(context.target.pid, range.start, snapshot.bytes.data(),
                       size)) {
            setMessage("A target range changed while it was being captured");
            return kTargetLost;
        }
        retained += size;
        for (ValueType type : types) {
            const size_t width = widthOf(type);
            const size_t adjustment =
                    range.start % width == 0 ? 0 : width - range.start % width;
            if (range.start >
                std::numeric_limits<uintptr_t>::max() - adjustment) {
                continue;
            }
            const uintptr_t aligned = range.start + adjustment;
            if (aligned < range.end &&
                static_cast<size_t>(range.end - aligned) >= width) {
                safeAdd(next->logicalCount,
                        1U + static_cast<uint64_t>(
                                     (range.end - aligned - width) / width));
            }
        }
        next->snapshots.push_back(std::move(snapshot));
        advanceScanProgress(size);
    }
    return commitOperation(context, std::move(next), -1);
}

bool readCandidate(const Target &target, const Candidate &candidate,
                   uint64_t &bits) {
    const size_t width = widthOf(candidate.type);
    return width != 0 && readExact(target.pid, candidate.address, &bits, width);
}

bool resolveCandidateById(const OperationContext &context, uint64_t id,
                          Candidate &resolved) {
    if (id == 0) {
        return false;
    }
    const auto live = context.liveCandidates.find(id);
    if (live != context.liveCandidates.end()) {
        resolved = live->second;
        return true;
    }
    const auto findIn = [&](const std::vector<Candidate> &items) {
        const auto found = std::find_if(items.begin(), items.end(),
                                        [&](const Candidate &candidate) {
                                            return candidate.id == id;
                                        });
        if (found == items.end()) {
            return false;
        }
        resolved = liveCandidate(*found, context.liveCandidates);
        return true;
    };
    return findIn(context.state->candidates) || findIn(context.state->watches);
}

bool verifyCandidateBinding(const Target &target, const Candidate &candidate) {
    if (candidate.state != kStable || !candidate.identityValid) {
        setMessage("Candidate identity is not stable enough for a bounded read");
        return false;
    }
    uint64_t current = 0;
    uint64_t hash = 0;
    if (!readCandidate(target, candidate, current) ||
        current != candidate.currentBits ||
        !readIdentity(target, candidate.address, candidate.type, hash) ||
        hash != candidate.identityHash) {
        setMessage("Candidate binding changed before the bounded read");
        return false;
    }
    return true;
}

bool candidateWindow(const Target &target, const Candidate &candidate,
                     size_t radius, Range &window) {
    const size_t width = widthOf(candidate.type);
    uintptr_t valueEnd = 0;
    if (width == 0 || radius == 0 ||
        !checkedAddressAdd(candidate.address, width, valueEnd)) {
        return false;
    }
    const auto containing = std::find_if(
            target.ranges.begin(), target.ranges.end(), [&](const Range &range) {
                return range.start <= candidate.address && range.end >= valueEnd;
            });
    if (containing == target.ranges.end()) {
        return false;
    }
    const uintptr_t requestedStart =
            candidate.address < radius ? 0 : candidate.address - radius;
    const uintptr_t requestedEnd =
            valueEnd > std::numeric_limits<uintptr_t>::max() - radius
                    ? std::numeric_limits<uintptr_t>::max()
                    : valueEnd + radius;
    window.start = std::max(containing->start, requestedStart);
    window.end = std::min(containing->end, requestedEnd);
    return window.end > window.start;
}

jlongArray inspectionResult(JNIEnv *env, jint code, uintptr_t start = 0,
                            uintptr_t anchor = 0,
                            const std::vector<uint8_t> *bytes = nullptr) {
    const size_t byteCount = bytes == nullptr ? 0 : bytes->size();
    if (byteCount > static_cast<size_t>(std::numeric_limits<jsize>::max()) - 4U) {
        return nullptr;
    }
    std::vector<jlong> output(4U + byteCount);
    output[0] = code;
    output[1] = static_cast<jlong>(start);
    output[2] = static_cast<jlong>(anchor);
    output[3] = static_cast<jlong>(byteCount);
    for (size_t index = 0; index < byteCount; ++index) {
        output[4U + index] = static_cast<jlong>((*bytes)[index]);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                output.data());
    }
    return result;
}

jlongArray inspectCandidateSnapshot(JNIEnv *env, const OperationContext &context,
                                    uint64_t candidateId, jint radius) {
    if (candidateId == 0 || radius <= 0 || radius > kMaxInspectRadius) {
        setMessage("Inspector radius or candidate is invalid");
        return inspectionResult(env, kInvalidRequest);
    }
    Candidate candidate{};
    if (!resolveCandidateById(context, candidateId, candidate)) {
        setMessage("Inspector candidate is no longer available");
        return inspectionResult(env, kInvalidRequest);
    }
    if (!verifyCandidateBinding(context.target, candidate)) {
        return inspectionResult(env, kIdentityUnsafe);
    }
    Range window{};
    if (!candidateWindow(context.target, candidate, static_cast<size_t>(radius),
                         window)) {
        setMessage("Inspector candidate is outside the current resident ranges");
        return inspectionResult(env, kTargetLost);
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(window.end - window.start));
    if (!readExact(context.target.pid, window.start, bytes.data(), bytes.size())) {
        setMessage("Inspector window changed while it was being read");
        return inspectionResult(env, kTargetLost);
    }
    if (isCancelled(context)) {
        setMessage("Inspector read was cancelled");
        return inspectionResult(env, kCancelled);
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gTarget.generation != context.target.generation ||
            gTarget.token != context.target.token || gState != context.state) {
            gLastMessage = "MIDlet runtime or search changed during Inspector read";
            return inspectionResult(env, kTargetLost);
        }
        gLastMessage = "";
    }
    return inspectionResult(env, kOk, window.start, candidate.address, &bytes);
}

class CandidateValueReader {
  public:
    CandidateValueReader(const Target &target, size_t candidateCount)
        : target_(target), direct_(candidateCount <= kDirectRefineLimit) {}

    bool read(const Candidate &candidate, uint64_t &bits) {
        if (direct_) {
            return readCandidate(target_, candidate, bits);
        }
        const size_t width = widthOf(candidate.type);
        uintptr_t valueEnd = 0;
        if (width == 0 || !checkedAddressAdd(candidate.address, width, valueEnd)) {
            return false;
        }
        if (candidate.address >= cachedStart_ && valueEnd <= cachedEnd_) {
            if (!cacheReadable_) {
                return false;
            }
            bits = loadBits(buffer_.data() + candidate.address - cachedStart_,
                            width);
            return true;
        }
        const auto range = std::upper_bound(
                target_.ranges.begin(), target_.ranges.end(), candidate.address,
                [](uintptr_t address, const Range &item) {
                    return address < item.start;
                });
        if (range == target_.ranges.begin()) {
            return false;
        }
        const Range &containing = *std::prev(range);
        if (candidate.address < containing.start ||
            valueEnd > containing.end) {
            return false;
        }
        const uintptr_t offset = candidate.address - containing.start;
        cachedStart_ = containing.start +
                       offset / kReadChunkSize * kReadChunkSize;
        const size_t chunkSize = static_cast<size_t>(std::min<uintptr_t>(
                kReadChunkSize, containing.end - cachedStart_));
        cachedEnd_ = cachedStart_ + chunkSize;
        buffer_.resize(chunkSize);
        if (!readExact(target_.pid, cachedStart_, buffer_.data(), chunkSize)) {
            cacheReadable_ = false;
            return false;
        }
        cacheReadable_ = true;
        bits = loadBits(buffer_.data() + candidate.address - cachedStart_, width);
        return true;
    }

  private:
    const Target &target_;
    const bool direct_;
    std::vector<uint8_t> buffer_;
    uintptr_t cachedStart_ = 0;
    uintptr_t cachedEnd_ = 0;
    bool cacheReadable_ = false;
};

jint refineCandidates(const OperationContext &context, jint predicate,
                      const std::string &first, const std::string &second,
                      bool relative, jint compareTarget) {
    if (context.state->mode == StateMode::Empty) {
        setMessage("No search session to refine");
        return kNoSession;
    }
    if (context.state->mode == StateMode::Unknown &&
        context.nextId >
                std::numeric_limits<uint64_t>::max() - kCandidateLimit) {
        setMessage("Candidate identifier space is exhausted for this runtime");
        return kResourceLimit;
    }
    if (relative && compareTarget != kComparePrevious &&
        compareTarget != kCompareInitial) {
        setMessage("Invalid comparison target");
        return kInvalidRequest;
    }
    std::vector<Query> queries;
    if (!buildQueries(context.state->requestedType, predicate, first, second,
                      relative, queries)) {
        setMessage("Invalid value, type, or predicate");
        return kInvalidRequest;
    }
    std::array<const Query *, kTypeSlotCount> queriesByType{};
    for (const Query &query : queries) {
        queriesByType[typeIndex(query.type)] = &query;
    }
    const bool fusedAuto = context.state->requestedType == kTypeAuto &&
                           queries.size() > 1U;
    const Query *byteQuery = queriesByType[typeIndex(ValueType::Byte)];
    const Query *shortQuery = queriesByType[typeIndex(ValueType::Short)];
    const Query *charQuery = queriesByType[typeIndex(ValueType::Char)];
    const Query *intQuery = queriesByType[typeIndex(ValueType::Int)];
    const Query *longQuery = queriesByType[typeIndex(ValueType::Long)];
    const Query *floatQuery = queriesByType[typeIndex(ValueType::Float)];
    const Query *doubleQuery = queriesByType[typeIndex(ValueType::Double)];
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;

    if (context.state->mode == StateMode::Unknown) {
        for (const SnapshotRun &snapshot : context.state->snapshots) {
            std::vector<uint8_t> current(snapshot.bytes.size());
            if (isCancelled(context)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            if (!readExact(context.target.pid, snapshot.start, current.data(),
                           current.size())) {
                setMessage("A target range changed while it was being refined");
                return kTargetLost;
            }
            const auto materialize = [&](const Query *query, uintptr_t address,
                                         size_t offset, size_t width,
                                         uint64_t initial, uint64_t now) -> bool {
                if (query == nullptr) {
                    return true;
                }
                const bool match = relative
                                           ? matchesRelative(now, initial, *query, predicate)
                                           : matchesKnown(now, *query, predicate);
                if (!match) {
                    return true;
                }
                if (next->candidates.size() >= kCandidateLimit) {
                    setMessage("Candidate limit reached; previous results were preserved");
                    return false;
                }
                Candidate candidate = makeCandidate(
                        context.nextId + next->candidates.size(), address,
                        query->type, initial, now);
                candidate.identityValid = snapshotIdentity(
                        snapshot.bytes.data(), snapshot.bytes.size(), offset,
                        width, candidate.identityHash);
                next->candidates.push_back(candidate);
                return true;
            };
            if (fusedAuto) {
                for (size_t offset = 0; offset < snapshot.bytes.size(); ++offset) {
                    const uintptr_t address = snapshot.start + offset;
                    if ((intQuery != nullptr || floatQuery != nullptr) &&
                        address % 4U == 0U && offset + 4U <= snapshot.bytes.size()) {
                        const uint64_t initial = loadBits(snapshot.bytes.data() + offset, 4U);
                        const uint64_t now = loadBits(current.data() + offset, 4U);
                        if (!materialize(intQuery, address, offset, 4U, initial, now) ||
                            !materialize(floatQuery, address, offset, 4U, initial, now)) {
                            return kResourceLimit;
                        }
                    }
                    if ((longQuery != nullptr || doubleQuery != nullptr) &&
                        address % 8U == 0U && offset + 8U <= snapshot.bytes.size()) {
                        const uint64_t initial = loadBits(snapshot.bytes.data() + offset, 8U);
                        const uint64_t now = loadBits(current.data() + offset, 8U);
                        if (!materialize(longQuery, address, offset, 8U, initial, now) ||
                            !materialize(doubleQuery, address, offset, 8U, initial, now)) {
                            return kResourceLimit;
                        }
                    }
                    if ((shortQuery != nullptr || charQuery != nullptr) &&
                        address % 2U == 0U && offset + 2U <= snapshot.bytes.size()) {
                        const uint64_t initial = loadBits(snapshot.bytes.data() + offset, 2U);
                        const uint64_t now = loadBits(current.data() + offset, 2U);
                        if (!materialize(shortQuery, address, offset, 2U, initial, now) ||
                            !materialize(charQuery, address, offset, 2U, initial, now)) {
                            return kResourceLimit;
                        }
                    }
                    if (byteQuery != nullptr &&
                        !materialize(byteQuery, address, offset, 1U,
                                     snapshot.bytes[offset], current[offset])) {
                        return kResourceLimit;
                    }
                }
            } else {
                const size_t snapshotCandidateStart = next->candidates.size();
                for (const Query &query : queries) {
                    const size_t width = widthOf(query.type);
                    uintptr_t address = snapshot.start;
                    const size_t misalignment =
                            static_cast<size_t>(address % width);
                    if (misalignment != 0) {
                        address += width - misalignment;
                    }
                    while (address >= snapshot.start) {
                        const size_t offset =
                                static_cast<size_t>(address - snapshot.start);
                        if (offset + width > snapshot.bytes.size()) {
                            break;
                        }
                        const uint64_t initial =
                                loadBits(snapshot.bytes.data() + offset, width);
                        const uint64_t now =
                                loadBits(current.data() + offset, width);
                        if (!materialize(&query, address, offset, width, initial, now)) {
                            return kResourceLimit;
                        }
                        if (address >
                            std::numeric_limits<uintptr_t>::max() - width) {
                            break;
                        }
                        address += width;
                    }
                }
                std::sort(next->candidates.begin() +
                                  static_cast<std::ptrdiff_t>(snapshotCandidateStart),
                          next->candidates.end(), candidateDisplayOrder);
            }
        }
        fillMissingIdentities(context.target, next->candidates);
    } else {
        // Small result sets use direct reads; large sorted sets reuse a 256 KiB chunk. This keeps
        // a 20-result refine proportional to 20 values while million-result passes remain
        // sequential without allocating a second full-heap image.
        CandidateValueReader reader(context.target,
                                    context.state->candidates.size());
        next->candidates.reserve(context.state->candidates.size());
        for (const Candidate &candidate : context.state->candidates) {
            if (isCancelled(context)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const Candidate &live =
                    liveCandidate(candidate, context.liveCandidates);
            Candidate bound = candidate;
            bound.address = live.address;
            bound.relocationCount = live.relocationCount;
            bound.state = live.state;
            bound.identityHash = live.identityHash;
            bound.identityValid = live.identityValid;
            Candidate updated = bound;
            uint64_t current = 0;
            if (!reader.read(bound, current)) {
                // A stale raw binding is excluded transactionally from the next state.
                continue;
            }
            updated.previousBits = candidate.currentBits;
            updated.currentBits = current;
            updated.state = kStable;
            const Query *query = queriesByType[typeIndex(candidate.type)];
            if (query == nullptr) {
                continue;
            }
            const uint64_t reference = compareTarget == kCompareInitial
                                               ? candidate.initialBits
                                               : candidate.currentBits;
            const bool match =
                    relative ? matchesRelative(current, reference, *query,
                                               predicate)
                             : matchesKnown(current, *query, predicate);
            if (match) {
                next->candidates.push_back(updated);
            }
        }
        // A successful explicit refine is allowed to establish a fresh recovery fingerprint for
        // its surviving raw bindings. Passive display refreshes below are deliberately stricter.
        captureIdentities(context.target, next->candidates);
    }
    if (!relative && context.state->mode == StateMode::Candidates &&
        !context.state->candidates.empty() && next->candidates.empty() &&
        context.state->candidates.size() <= kRelocationTrackLimit) {
        setMessage("Direct Next Scan found no candidates; refreshing resident ranges for relocation recovery");
        return kIdentityUnsafe;
    }
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    OperationContext committed = context;
    if (context.state->mode == StateMode::Unknown) {
        committed.nextId += next->candidates.size();
    }
    return commitOperation(committed, std::move(next), 1);
}

bool hasSingleUniqueAddress(const std::vector<Candidate> &candidates) {
    if (candidates.empty()) {
        return false;
    }
    const uintptr_t address = candidates.front().address;
    return std::all_of(candidates.begin(), candidates.end(),
                       [&](const Candidate &candidate) {
                           return candidate.address == address;
                       });
}

jint recoverKnownCandidates(const OperationContext &context, jint predicate,
                            const std::string &first,
                            const std::string &second) {
    if (context.state->mode != StateMode::Candidates ||
        context.state->candidates.empty() ||
        context.state->candidates.size() > kRelocationTrackLimit) {
        setMessage("No bounded candidate set is available for relocation recovery");
        return kNoSession;
    }

    std::shared_ptr<SearchState> fresh;
    const jint scanResult = collectKnown(
            context, context.state->requestedType, predicate, first, second,
            fresh);
    if (scanResult != kOk) {
        return scanResult;
    }

    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    const bool uniqueOneToOne =
            hasSingleUniqueAddress(context.state->candidates) &&
            hasSingleUniqueAddress(fresh->candidates);
    std::vector<bool> claimed(fresh->candidates.size(), false);
    std::array<std::unordered_set<uint64_t>, kTypeSlotCount> wantedIdentity{};
    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount>
            freshByIdentity{};
    if (!uniqueOneToOne) {
        for (const Candidate &old : context.state->candidates) {
            if (old.identityValid) {
                wantedIdentity[typeIndex(old.type)].insert(old.identityHash);
            }
        }
        for (size_t index = 0; index < fresh->candidates.size(); ++index) {
            const Candidate &candidate = fresh->candidates[index];
            if (candidate.identityValid &&
                wantedIdentity[typeIndex(candidate.type)].find(candidate.identityHash) !=
                        wantedIdentity[typeIndex(candidate.type)].end()) {
                freshByIdentity[typeIndex(candidate.type)][candidate.identityHash]
                        .push_back(index);
            }
        }
    }

    for (const Candidate &old : context.state->candidates) {
        size_t matchIndex = fresh->candidates.size();
        if (uniqueOneToOne) {
            size_t sameTypeMatches = 0;
            for (size_t index = 0; index < fresh->candidates.size(); ++index) {
                if (!claimed[index] && fresh->candidates[index].type == old.type) {
                    matchIndex = index;
                    ++sameTypeMatches;
                }
            }
            if (sameTypeMatches != 1) {
                continue;
            }
        } else {
            if (!old.identityValid) {
                continue;
            }
            const auto found =
                    freshByIdentity[typeIndex(old.type)].find(old.identityHash);
            if (found == freshByIdentity[typeIndex(old.type)].end() ||
                found->second.size() != 1U || claimed[found->second.front()]) {
                continue;
            }
            matchIndex = found->second.front();
        }
        claimed[matchIndex] = true;
        const Candidate &replacement = fresh->candidates[matchIndex];
        Candidate recovered = old;
        recovered.address = replacement.address;
        recovered.previousBits = old.currentBits;
        recovered.currentBits = replacement.currentBits;
        recovered.identityHash = replacement.identityHash;
        recovered.identityValid = replacement.identityValid;
        recovered.state = kStable;
        if (recovered.address != old.address) {
            ++recovered.relocationCount;
        }
        next->candidates.push_back(recovered);
    }

    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    const size_t recoveredCount = next->candidates.size();
    const jint result = commitOperation(context, std::move(next), 1);
    if (result == kOk && recoveredCount > 0) {
        setMessage("Next Scan safely rebound candidates after address relocation");
    }
    return result;
}

bool isSelected(const std::vector<uint64_t> &ids, uint64_t id) {
    return ids.empty() || std::binary_search(ids.begin(), ids.end(), id);
}

bool allIdsResolve(const SearchState &state, const std::vector<uint64_t> &ids,
                   bool watchesOnly, bool candidatesOnly) {
    std::unordered_set<uint64_t> unresolved(ids.begin(), ids.end());
    if (!watchesOnly) {
        for (const Candidate &candidate : state.candidates) {
            unresolved.erase(candidate.id);
            if (unresolved.empty()) {
                return true;
            }
        }
    }
    if (!candidatesOnly) {
        for (const Candidate &watch : state.watches) {
            unresolved.erase(watch.id);
            if (unresolved.empty()) {
                return true;
            }
        }
    }
    return unresolved.empty();
}

bool readIds(JNIEnv *env, jlongArray rawIds, std::vector<uint64_t> &ids) {
    if (rawIds == nullptr) {
        return false;
    }
    const jsize length = env->GetArrayLength(rawIds);
    std::vector<jlong> raw(static_cast<size_t>(length));
    if (length > 0) {
        env->GetLongArrayRegion(rawIds, 0, length, raw.data());
        if (env->ExceptionCheck()) {
            return false;
        }
    }
    ids.reserve(static_cast<size_t>(length));
    for (jlong value : raw) {
        if (value <= 0) {
            return false;
        }
        ids.push_back(static_cast<uint64_t>(value));
    }
    std::sort(ids.begin(), ids.end());
    return std::adjacent_find(ids.begin(), ids.end()) == ids.end();
}

jint recoverCandidatesBatch(const Target &target, uint64_t cancellationEpoch,
                            std::span<Candidate> candidates,
                            std::span<const size_t> recovery,
                            size_t &unsafeCount) {
    unsafeCount = 0;
    if (recovery.empty()) {
        return kOk;
    }
    if (std::any_of(recovery.begin(), recovery.end(),
                    [&](size_t index) { return index >= candidates.size(); })) {
        setMessage("Invalid relocation recovery index");
        return kInvalidRequest;
    }
    std::array<std::unordered_map<uint64_t, std::vector<size_t>>, kTypeSlotCount> wanted{};
    std::vector<bool> eligible(recovery.size(), false);
    std::vector<uintptr_t> foundAddress(recovery.size(), 0);
    std::vector<uint8_t> matchCount(recovery.size(), 0);
    size_t eligibleCount = 0;
    for (size_t index = 0; index < recovery.size(); ++index) {
        Candidate &candidate = candidates[recovery[index]];
        if (!candidate.identityValid || widthOf(candidate.type) == 0) {
            candidate.state = kLost;
            ++unsafeCount;
            continue;
        }
        eligible[index] = true;
        ++eligibleCount;
        wanted[typeIndex(candidate.type)][candidate.currentBits].push_back(index);
    }
    if (eligibleCount == 0) {
        return kOk;
    }

    std::vector<uint8_t> buffer;
    for (const Range &range : target.ranges) {
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (isCancelled(cancellationEpoch)) {
                setMessage("Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            const size_t remaining = static_cast<size_t>(range.end - chunkStart);
            const size_t chunkSize = std::min(remaining, kReadChunkSize);
            buffer.resize(chunkSize);
            if (!readExact(target.pid, chunkStart, buffer.data(), chunkSize)) {
                setMessage("A target range changed during relocation recovery");
                return kTargetLost;
            }
            for (jint type = kTypeByte; type <= kTypeDouble; ++type) {
                if (wanted[type].empty()) {
                    continue;
                }
                const size_t width = widthOf(type);
                uintptr_t address = chunkStart;
                const size_t misalignment = static_cast<size_t>(address % width);
                if (misalignment != 0) {
                    address += width - misalignment;
                }
                while (address >= chunkStart) {
                    const size_t offset = static_cast<size_t>(address - chunkStart);
                    if (offset + width > chunkSize) {
                        break;
                    }
                    const uint64_t bits = loadBits(buffer.data() + offset, width);
                    const auto interested = wanted[type].find(bits);
                    if (interested != wanted[type].end()) {
                        uint64_t hash = 0;
                        bool hasIdentity = snapshotIdentity(
                                buffer.data(), chunkSize, offset, width, hash);
                        if (!hasIdentity) {
                            hasIdentity = readIdentity(target, address, type, hash);
                        }
                        if (hasIdentity) {
                            for (size_t recoveryIndex : interested->second) {
                                if (!eligible[recoveryIndex] ||
                                    matchCount[recoveryIndex] > 1) {
                                    continue;
                                }
                                Candidate &candidate = candidates[recovery[recoveryIndex]];
                                if (hash != candidate.identityHash) {
                                    continue;
                                }
                                if (matchCount[recoveryIndex] == 0) {
                                    foundAddress[recoveryIndex] = address;
                                    matchCount[recoveryIndex] = 1;
                                } else if (foundAddress[recoveryIndex] != address) {
                                    matchCount[recoveryIndex] = 2;
                                }
                            }
                        }
                    }
                    if (address > std::numeric_limits<uintptr_t>::max() - width) {
                        break;
                    }
                    address += width;
                }
            }
            chunkStart += chunkSize;
        }
    }

    for (size_t index = 0; index < recovery.size(); ++index) {
        if (!eligible[index]) {
            continue;
        }
        Candidate &candidate = candidates[recovery[index]];
        if (matchCount[index] == 1) {
            if (candidate.address != foundAddress[index]) {
                ++candidate.relocationCount;
            }
            candidate.address = foundAddress[index];
            candidate.state = kStable;
        } else {
            candidate.state = matchCount[index] > 1 ? kAmbiguous : kLost;
            ++unsafeCount;
        }
    }
    return kOk;
}

jint refreshCandidates(const OperationContext &context,
                       const std::vector<uint64_t> &ids, bool allowRecovery) {
    if (context.state->mode != StateMode::Candidates &&
        context.state->watches.empty()) {
        setMessage("No materialized candidates are available");
        return kNoSession;
    }
    if (ids.empty()) {
        setMessage("Select at least one candidate to refresh");
        return kInvalidRequest;
    }
    std::vector<Candidate> selected;
    selected.reserve(ids.size());
    std::unordered_set<uint64_t> unresolved(ids.begin(), ids.end());
    for (uint64_t id : ids) {
        const auto live = context.liveCandidates.find(id);
        if (live != context.liveCandidates.end()) {
            selected.push_back(live->second);
            unresolved.erase(id);
        }
    }
    const auto collect = [&](const Candidate &candidate) {
        if (unresolved.erase(candidate.id) != 0U) {
            selected.push_back(liveCandidate(candidate, context.liveCandidates));
        }
    };
    if (!unresolved.empty()) {
        for (const Candidate &candidate : context.state->candidates) {
            collect(candidate);
            if (unresolved.empty()) break;
        }
        for (const Candidate &watch : context.state->watches) {
            collect(watch);
            if (unresolved.empty()) break;
        }
    }
    if (!unresolved.empty()) {
        setMessage("One or more candidate IDs are no longer available");
        return kInvalidRequest;
    }
    size_t unsafeCount = 0;
    std::vector<size_t> recovery;
    const auto refreshOne = [&](Candidate &candidate, size_t candidateIndex) {
        uint64_t current = 0;
        uint64_t hash = 0;
        const bool readable = readCandidate(context.target, candidate, current);
        const bool identityReadable =
                readable && readIdentity(context.target, candidate.address,
                                         candidate.type, hash);
        if (!allowRecovery) {
            if (!readable) {
                candidate.state = kRelocating;
                return;
            }
            candidate.previousBits = candidate.currentBits;
            candidate.currentBits = current;
            candidate.state = kStable;
            // Passive display/Freeze polling may establish a missing fingerprint, but it must not
            // silently replace an established logical identity just because the raw address is
            // still readable. Explicit write/recovery paths validate the existing fingerprint.
            if (identityReadable && !candidate.identityValid) {
                candidate.identityHash = hash;
                candidate.identityValid = true;
            }
            return;
        }
        const bool identityMatches =
                identityReadable &&
                (!candidate.identityValid || hash == candidate.identityHash);
        if (!identityMatches) {
            candidate.state = kRelocating;
            recovery.push_back(candidateIndex);
            return;
        }
        candidate.identityHash = hash;
        candidate.identityValid = true;
        candidate.previousBits = candidate.currentBits;
        candidate.currentBits = current;
        candidate.state = kStable;
    };
    for (size_t index = 0; index < selected.size(); ++index) {
        refreshOne(selected[index], index);
    }
    if (recovery.size() > kRecoveryLimit) {
        setMessage("Identity recovery is limited to 32 candidates per "
                   "operation; narrow the visible selection");
        return kResourceLimit;
    }
    size_t recoveryUnsafe = 0;
    const jint recoveryResult =
            recoverCandidatesBatch(context.target, context.cancellationEpoch, selected,
                                   recovery, recoveryUnsafe);
    if (recoveryResult != kOk) {
        return recoveryResult;
    }
    unsafeCount += recoveryUnsafe;
    const jint result = publishLiveCandidates(context, selected);
    if (result == kOk && unsafeCount > 0) {
        setMessage(
                "Some candidates remain ambiguous or lost; writes stay paused");
    }
    return result;
}

jint filterCandidates(const OperationContext &context,
                      const std::vector<uint64_t> &ids, bool keep) {
    if (context.state->mode != StateMode::Candidates || ids.empty()) {
        setMessage("No materialized candidates are available");
        return kNoSession;
    }
    if (!allIdsResolve(*context.state, ids, false, true)) {
        setMessage("One or more candidate IDs are not in this search");
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;
    next->watches = context.state->watches;
    next->candidates.reserve(context.state->candidates.size());
    for (const Candidate &candidate : context.state->candidates) {
        const bool selected = isSelected(ids, candidate.id);
        if ((keep && selected) || (!keep && !selected)) {
            next->candidates.push_back(candidate);
        }
    }
    next->logicalCount = next->candidates.size();
    next->candidateOrderDirty = true;
    return commitOperation(context, std::move(next), 1);
}

bool replacementBitsFor(const Candidate &candidate,
                        const std::string &replacement, uint64_t &bits) {
    Query query;
    if (!parseQuery(candidate.type, kEqual, replacement, "", query)) {
        return false;
    }
    bits = 0;
    if (query.floating) {
        if (candidate.type == ValueType::Float) {
            const float value = static_cast<float>(query.floatingFirst);
            bits = std::bit_cast<uint32_t>(value);
        } else {
            bits = std::bit_cast<uint64_t>(query.floatingFirst);
        }
    } else {
        bits = static_cast<uint64_t>(query.integerFirst);
        const size_t width = widthOf(candidate.type);
        if (width > 0 && width < sizeof(bits)) {
            bits &= (UINT64_C(1) << (width * 8U)) - UINT64_C(1);
        }
    }
    return true;
}

int editOneCandidate(const Target &target, Candidate &candidate,
                     const std::string &replacement) {
    if (candidate.state != kStable || !candidate.identityValid) {
        return 0;
    }
    uint64_t replacementBits = 0;
    if (!replacementBitsFor(candidate, replacement, replacementBits)) {
        return -1;
    }
    uint64_t actual = 0;
    uint64_t hash = 0;
    if (!readCandidate(target, candidate, actual) ||
        actual != candidate.currentBits ||
        !readIdentity(target, candidate.address, candidate.type, hash) ||
        hash != candidate.identityHash) {
        candidate.state = kLost;
        return 0;
    }
    const size_t width = widthOf(candidate.type);
    const size_t pageOffset = candidate.address % target.pageSize;
    if (candidate.address % width != 0 || pageOffset > target.pageSize - width) {
        candidate.state = kLost;
        return 0;
    }
    uint64_t readback = 0;
    if (!writeExact(target.pid, candidate.address, &replacementBits, width) ||
        !readExact(target.pid, candidate.address, &readback, width) ||
        loadBits(reinterpret_cast<const uint8_t *>(&readback), width) !=
                loadBits(reinterpret_cast<const uint8_t *>(&replacementBits),
                         width)) {
        writeExact(target.pid, candidate.address, &actual, width);
        candidate.state = kLost;
        return 0;
    }
    candidate.previousBits = candidate.currentBits;
    candidate.currentBits = replacementBits;
    return 1;
}

jint editCandidates(const OperationContext &context,
                    const std::vector<uint64_t> &ids,
                    const std::string &replacement) {
    if ((context.state->mode != StateMode::Candidates &&
         context.state->watches.empty()) ||
        ids.empty()) {
        setMessage("Select at least one materialized candidate");
        return kInvalidRequest;
    }
    if (ids.size() > kMultiWriteLimit) {
        setMessage("The bounded multi-edit safety limit is 32 candidates");
        return kSafetyLimit;
    }
    if (!allIdsResolve(*context.state, ids, false, false)) {
        setMessage("One or more candidate IDs are no longer available");
        return kInvalidRequest;
    }
    uint64_t ignoredBits = 0;
    for (const Candidate &candidate : context.state->candidates) {
        if (std::binary_search(ids.begin(), ids.end(), candidate.id) &&
            !replacementBitsFor(candidate, replacement, ignoredBits)) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
    }
    for (const Candidate &watch : context.state->watches) {
        if (std::binary_search(ids.begin(), ids.end(), watch.id) &&
            !replacementBitsFor(watch, replacement, ignoredBits)) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
    }
    auto next = std::make_shared<SearchState>(*context.state);
    size_t edited = 0;
    size_t skipped = 0;
    for (Candidate &candidate : next->candidates) {
        if (!std::binary_search(ids.begin(), ids.end(), candidate.id)) {
            continue;
        }
        candidate = liveCandidate(candidate, context.liveCandidates);
        const int outcome =
                editOneCandidate(context.target, candidate, replacement);
        if (outcome < 0) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
        if (outcome > 0) {
            ++edited;
        } else {
            ++skipped;
        }
    }
    for (Candidate &watch : next->watches) {
        if (!std::binary_search(ids.begin(), ids.end(), watch.id)) {
            continue;
        }
        const auto result =
                std::find_if(next->candidates.begin(), next->candidates.end(),
                             [&](const Candidate &candidate) {
                                 return candidate.id == watch.id;
                             });
        if (result != next->candidates.end()) {
            watch = *result;
            continue;
        }
        watch = liveCandidate(watch, context.liveCandidates);
        const int outcome =
                editOneCandidate(context.target, watch, replacement);
        if (outcome < 0) {
            setMessage("Replacement value does not fit every selected type");
            return kInvalidRequest;
        }
        if (outcome > 0) {
            ++edited;
        } else {
            ++skipped;
        }
    }
    const jint result = commitOperation(context, std::move(next), 0);
    if (result == kOk) {
        setMessage((std::to_string(edited) + " edited, " +
                    std::to_string(skipped) + " skipped safely")
                           .c_str());
    }
    return result;
}

jint pinCandidates(const OperationContext &context,
                   const std::vector<uint64_t> &ids, bool add) {
    if ((add && context.state->mode != StateMode::Candidates) || ids.empty()) {
        setMessage("Select at least one materialized candidate");
        return kInvalidRequest;
    }
    if (!allIdsResolve(*context.state, ids, !add, add)) {
        setMessage(add ? "One or more candidates are not in this search"
                       : "One or more candidates are not in the Watch List");
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>(*context.state);
    if (add) {
        std::unordered_set<uint64_t> pending(ids.begin(), ids.end());
        for (const Candidate &watch : next->watches) {
            pending.erase(watch.id);
        }
        if (next->watches.size() + pending.size() > kWatchLimit) {
            setMessage("The session Watch List limit is 128 candidates");
            return kResourceLimit;
        }
        for (const Candidate &candidate : next->candidates) {
            if (pending.erase(candidate.id) != 0U) {
                next->watches.push_back(candidate);
                if (pending.empty()) {
                    break;
                }
            }
        }
    } else {
        next->watches.erase(
                std::remove_if(next->watches.begin(), next->watches.end(),
                               [&](const Candidate &candidate) {
                                   return std::binary_search(ids.begin(),
                                                             ids.end(),
                                                             candidate.id);
                               }),
                next->watches.end());
    }
    return commitOperation(context, std::move(next), 0, true);
}

jint freezeCandidates(const OperationContext &context,
                      const std::vector<uint64_t> &ids, jint mode,
                      const std::string &first, const std::string &second) {
    if (ids.empty() || ids.size() > kMultiWriteLimit || mode < 0 || mode > 3) {
        setMessage("Freeze accepts 1-32 watched candidates and a valid mode");
        return kSafetyLimit;
    }
    if (!allIdsResolve(*context.state, ids, true, false)) {
        setMessage("Freeze accepts only current Watch List candidates");
        return kInvalidRequest;
    }
    std::vector<Candidate> selected;
    selected.reserve(ids.size());
    for (const Candidate &watch : context.state->watches) {
        if (!std::binary_search(ids.begin(), ids.end(), watch.id)) {
            continue;
        }
        selected.push_back(liveCandidate(watch, context.liveCandidates));
    }
    for (const Candidate &watch : selected) {
        Query query;
        const jint predicate = mode == 3 ? kBetween : kEqual;
        if (!parseQuery(watch.type, predicate, first, second, query)) {
            setMessage("Freeze bounds do not fit every selected type");
            return kInvalidRequest;
        }
    }

    size_t corrected = 0;
    size_t unsafe = 0;
    for (Candidate &watch : selected) {
        Query query;
        parseQuery(watch.type, mode == 3 ? kBetween : kEqual, first, second,
                   query);
        Query upper = query;
        upper.integerFirst = query.integerSecond;
        upper.floatingFirst = query.floatingSecond;
        if (query.floating &&
            std::isnan(floatingValue(watch.type, watch.currentBits))) {
            watch.state = kLost;
            ++unsafe;
            continue;
        }
        bool correct = false;
        std::string replacement;
        switch (mode) {
        case 0:
            correct = matchesKnown(watch.currentBits, query, kEqual);
            replacement = first;
            break;
        case 1:
            correct = !matchesKnown(watch.currentBits, query, kLess);
            replacement = first;
            break;
        case 2:
            correct = !matchesKnown(watch.currentBits, query, kGreater);
            replacement = first;
            break;
        case 3:
            if (matchesKnown(watch.currentBits, query, kLess)) {
                replacement = first;
            } else if (matchesKnown(watch.currentBits, upper, kGreater)) {
                replacement = second;
            } else {
                correct = true;
            }
            break;
        default:
            break;
        }
        if (correct) {
            continue;
        }
        const int outcome =
                editOneCandidate(context.target, watch, replacement);
        if (outcome > 0) {
            ++corrected;
        } else {
            ++unsafe;
        }
    }
    const jint committed = publishLiveCandidates(context, selected);
    if (committed != kOk) {
        return committed;
    }
    setMessage((std::to_string(corrected) + " corrected, " +
                std::to_string(unsafe) + " paused safely")
                       .c_str());
    return unsafe == 0 ? kOk : kIdentityUnsafe;
}

class ScopedUtfChars final {
  public:
    ScopedUtfChars(JNIEnv *env, jstring value) noexcept
        : env_(env), value_(value), chars_(value == nullptr ? nullptr
                                                            : env->GetStringUTFChars(value, nullptr)) {}
    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }
    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;

    [[nodiscard]] const char *get() const noexcept { return chars_; }

  private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const ScopedUtfChars characters(env, value);
    return characters.get() == nullptr ? std::string{} : std::string(characters.get());
}

jlongArray candidatePage(
        JNIEnv *env, const std::vector<Candidate> &candidates,
        const std::unordered_map<uint64_t, Candidate> &liveCandidates,
        size_t start, size_t limit) {
    start = std::min(start, candidates.size());
    const size_t count = std::min(limit, candidates.size() - start);
    const size_t outputSize = 1U + count * kResultStride;
    std::vector<jlong> output(outputSize);
    output[0] = static_cast<jlong>(count);
    for (size_t index = 0; index < count; ++index) {
        const Candidate &candidate =
                liveCandidate(candidates[start + index], liveCandidates);
        const size_t base = 1U + index * kResultStride;
        output[base] = static_cast<jlong>(candidate.id);
        output[base + 1U] = static_cast<jlong>(candidate.address);
        output[base + 2U] = static_cast<jlong>(candidate.address);
        const jint serializedType = toJint(candidate.type);
        if (serializedType < kTypeByte || serializedType > kTypeDouble) {
            return nullptr;
        }
        output[base + 3U] = serializedType;
        output[base + 4U] = candidate.state;
        output[base + 5U] = candidate.relocationCount;
        output[base + 6U] = static_cast<jlong>(candidate.initialBits);
        output[base + 7U] = static_cast<jlong>(candidate.previousBits);
        output[base + 8U] = static_cast<jlong>(candidate.currentBits);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(outputSize));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(outputSize),
                                output.data());
    }
    return result;
}

template <typename Operation> jint guardedOperation(Operation operation) {
    try {
        return operation();
    } catch (const std::bad_alloc &) {
        setMessage("Memory budget could not be reserved; previous results were "
                   "preserved");
        return kResourceLimit;
    } catch (...) {
        setMessage("The native engine rejected the operation safely");
        return kInvalidRequest;
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_configureTarget(
        JNIEnv *env, jclass, jint pid, jint pageSize, jlong token,
        jlongArray rawRuns) {
    try {
        if (pid <= 0 || pageSize <= 0 || (pageSize & (pageSize - 1)) != 0 ||
            token == 0 || rawRuns == nullptr) {
            setMessage("Invalid target configuration");
            return kInvalidRequest;
        }
        const jsize length = env->GetArrayLength(rawRuns);
        if (length < 4 || (length - 2) % 2 != 0) {
            setMessage("Invalid target range list");
            return kInvalidRequest;
        }
        std::vector<jlong> values(static_cast<size_t>(length));
        env->GetLongArrayRegion(rawRuns, 0, length, values.data());
        if (env->ExceptionCheck() || values[1] != 0 ||
            values[0] != (length - 2) / 2) {
            setMessage("Incomplete target range list");
            return kResourceLimit;
        }

        Target target;
        target.pid = pid;
        target.pageSize = static_cast<size_t>(pageSize);
        target.token = token;
        uintptr_t previousEnd = 0;
        for (jsize index = 2; index < length; index += 2) {
            if (values[index] <= 0 || values[index + 1] <= values[index] ||
                static_cast<uint64_t>(values[index]) >
                        std::numeric_limits<uintptr_t>::max() ||
                static_cast<uint64_t>(values[index + 1]) >
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
        target.generation = gTarget.generation + 1;
        const bool sameRuntime =
                gTarget.pid == target.pid && gTarget.token == target.token;
        gTarget = std::move(target);
        if (!sameRuntime) {
            gState = gEmptyState;
            gHistory.clear();
            gLiveCandidates.clear();
            gNextCandidateId = 1;
        }
        gLastMessage = "";
        return kOk;
    } catch (const std::bad_alloc &) {
        setMessage("Target configuration exceeds the engine memory budget");
        return kResourceLimit;
    } catch (...) {
        setMessage("Invalid target configuration");
        return kInvalidRequest;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_canReadTarget(
        JNIEnv *, jclass, jint pid, jlong address, jlong expectedBits) {
    if (pid <= 0 || address <= 0 ||
        static_cast<uint64_t>(address) >
                std::numeric_limits<uintptr_t>::max()) {
        return JNI_FALSE;
    }
    uint64_t actual = 0;
    return readExact(pid, static_cast<uintptr_t>(address), &actual,
                     sizeof(actual)) &&
                           actual == static_cast<uint64_t>(expectedBits)
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_canWriteTarget(
        JNIEnv *, jclass, jint pid, jlong address, jlong expectedBits) {
    if (pid <= 0 || address <= 0 ||
        static_cast<uint64_t>(address) >
                std::numeric_limits<uintptr_t>::max()) {
        return JNI_FALSE;
    }
    const uintptr_t targetAddress = static_cast<uintptr_t>(address);
    uint64_t before = 0;
    uint64_t after = 0;
    const uint64_t expected = static_cast<uint64_t>(expectedBits);
    return readExact(pid, targetAddress, &before, sizeof(before)) &&
                           before == expected &&
                           writeExact(pid, targetAddress, &expected,
                                      sizeof(expected)) &&
                           readExact(pid, targetAddress, &after,
                                     sizeof(after)) &&
                           after == expected
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startKnown(
        JNIEnv *env, jclass, jint type, jint predicate, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return scanKnown(context, type, predicate, fromJString(env, first),
                         fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startUnknown(
        JNIEnv *, jclass, jint type) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return snapshotUnknown(context, type);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startGroup(
        JNIEnv *env, jclass, jintArray rawTypes, jobjectArray rawValues,
        jint maxDistance) {
    return guardedOperation([&] {
        if (rawTypes == nullptr || rawValues == nullptr) {
            setMessage("Group Search values are missing");
            return kInvalidRequest;
        }
        const jsize count = env->GetArrayLength(rawTypes);
        if (count != env->GetArrayLength(rawValues) || count < 2 || count > 8) {
            setMessage("Group Search requires 2-8 typed values");
            return kInvalidRequest;
        }
        std::vector<jint> types(static_cast<size_t>(count));
        env->GetIntArrayRegion(rawTypes, 0, count, types.data());
        if (env->ExceptionCheck()) {
            return kInvalidRequest;
        }
        std::vector<std::string> values;
        values.reserve(static_cast<size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            auto value = static_cast<jstring>(
                    env->GetObjectArrayElement(rawValues, index));
            if (value == nullptr || env->ExceptionCheck()) {
                if (value != nullptr) {
                    env->DeleteLocalRef(value);
                }
                return kInvalidRequest;
            }
            values.push_back(fromJString(env, value));
            env->DeleteLocalRef(value);
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return scanGroup(context, types, values, maxDistance);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_startNearby(
        JNIEnv *env, jclass, jlong anchorCandidateId, jint radius, jint type,
        jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        if (anchorCandidateId <= 0) {
            setMessage("Nearby Search anchor is invalid");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return scanNearby(context, static_cast<uint64_t>(anchorCandidateId),
                          radius, type, predicate, fromJString(env, first),
                          fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineKnown(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return refineCandidates(context, predicate, fromJString(env, first),
                                fromJString(env, second), false,
                                kComparePrevious);
    });
}

jlongArray candidateAddressPage(JNIEnv *env,
                                const std::shared_ptr<const SearchState> &state,
                                size_t addressStart, size_t addressLimit) {
    const std::vector<Candidate> &candidates = state->candidates;
    size_t candidateStart = 0;
    size_t skippedAddresses = 0;
    if (!state->addressCheckpoints.empty()) {
        const size_t checkpoint = std::min(
                addressStart / kAddressCheckpointStride,
                state->addressCheckpoints.size() - 1U);
        candidateStart = state->addressCheckpoints[checkpoint];
        skippedAddresses = checkpoint * kAddressCheckpointStride;
    }
    while (candidateStart < candidates.size() &&
           skippedAddresses < addressStart) {
        const uintptr_t address = candidates[candidateStart].address;
        do {
            ++candidateStart;
        } while (candidateStart < candidates.size() &&
                 candidates[candidateStart].address == address);
        ++skippedAddresses;
    }

    size_t candidateEnd = candidateStart;
    size_t includedAddresses = 0;
    while (candidateEnd < candidates.size() &&
           includedAddresses < addressLimit) {
        const uintptr_t address = candidates[candidateEnd].address;
        do {
            ++candidateEnd;
        } while (candidateEnd < candidates.size() &&
                 candidates[candidateEnd].address == address);
        ++includedAddresses;
    }
    std::unordered_map<uint64_t, Candidate> liveCandidates;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (gState == state) {
            const size_t pageSize = candidateEnd - candidateStart;
            if (gLiveCandidates.size() + pageSize > kLiveOverlayLimit) {
                gLiveCandidates.clear();
            }
            for (size_t index = candidateStart; index < candidateEnd; ++index) {
                gLiveCandidates.try_emplace(candidates[index].id,
                                            candidates[index]);
            }
            liveCandidates = gLiveCandidates;
        }
    }
    return candidatePage(env, candidates, liveCandidates, candidateStart,
                         candidateEnd - candidateStart);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_recoverKnown(
        JNIEnv *env, jclass, jint predicate, jstring first, jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return recoverKnownCandidates(context, predicate,
                                      fromJString(env, first),
                                      fromJString(env, second));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refineRelative(
        JNIEnv *env, jclass, jint predicate, jint compareTarget, jstring first,
        jstring second) {
    return guardedOperation([&] {
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return refineCandidates(context, predicate, fromJString(env, first),
                                fromJString(env, second), true, compareTarget);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_undo(JNIEnv *,
                                                               jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gHistory.empty()) {
        gLastMessage = "No earlier search state is available";
        return kNoSession;
    }
    auto restored = std::make_shared<SearchState>(*gHistory.back());
    // Search history is independent from the session-scoped Watch List. A Watch added after a
    // refine/remove step must not disappear when that search step is undone.
    restored->watches = gState->watches;
    for (Candidate &watch : restored->watches) {
        watch = liveCandidate(watch, gLiveCandidates);
    }
    gState = std::move(restored);
    gLiveCandidates.clear();
    gHistory.pop_back();
    gLastMessage = "";
    return kOk;
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_refresh(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean allowRecovery) {
    return guardedOperation([&] {
        std::vector<uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            setMessage("Invalid candidate selection");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return refreshCandidates(context, ids, allowRecovery == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_filter(
        JNIEnv *env, jclass, jlongArray rawIds, jboolean keep) {
    return guardedOperation([&] {
        std::vector<uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            setMessage("Invalid candidate selection");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return filterCandidates(context, ids, keep == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_expandResultGroups(
        JNIEnv *env, jclass, jlongArray rawIds, jint requestedType) {
    try {
        std::vector<uint64_t> sourceIds;
        if (!readIds(env, rawIds, sourceIds) ||
            (requestedType != kTypeAuto &&
             !valueTypeFromJint(requestedType).has_value())) {
            setMessage("Invalid result selection");
            return nullptr;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return nullptr;
        }
        std::vector<uint64_t> expanded;
        std::unordered_set<uint64_t> seen;
        for (const uint64_t sourceId : sourceIds) {
            Candidate source{};
            if (!resolveCandidateById(context, sourceId, source)) {
                setMessage("Selected result is no longer available");
                return nullptr;
            }
            bool matched = false;
            for (const Candidate &stored : context.state->candidates) {
                const Candidate &candidate = liveCandidate(stored, context.liveCandidates);
                if (candidate.address != source.address ||
                    (requestedType != kTypeAuto &&
                     toJint(candidate.type) != requestedType)) {
                    continue;
                }
                matched = true;
                if (seen.insert(candidate.id).second) {
                    expanded.push_back(candidate.id);
                }
            }
            if (!matched) {
                setMessage("Selected result aliases are no longer available");
                return nullptr;
            }
        }
        std::vector<jlong> output(expanded.begin(), expanded.end());
        jlongArray result = env->NewLongArray(static_cast<jsize>(output.size()));
        if (result != nullptr && !output.empty()) {
            env->SetLongArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                    output.data());
        }
        return result;
    } catch (const std::bad_alloc &) {
        setMessage("Result selection could not be expanded safely");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_editInspectorValue(
        JNIEnv *env, jclass, jlong rawAnchorId, jint relativeOffset,
        jint rawType, jlong expectedBits, jstring replacement) {
    return guardedOperation([&] {
        const auto type = valueTypeFromJint(rawType);
        if (rawAnchorId <= 0 || !type.has_value() ||
            std::abs(static_cast<int64_t>(relativeOffset)) > kMaxInspectRadius) {
            setMessage("Inspector edit requires a bounded typed cell");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        Candidate anchor{};
        if (!resolveCandidateById(context, static_cast<uint64_t>(rawAnchorId), anchor) ||
            anchor.state != kStable || !anchor.identityValid) {
            setMessage("Inspector anchor is no longer safe to edit");
            return kIdentityUnsafe;
        }
        uintptr_t address = anchor.address;
        if (relativeOffset < 0) {
            const uintptr_t distance = static_cast<uintptr_t>(
                    -static_cast<int64_t>(relativeOffset));
            if (address < distance) {
                setMessage("Inspector cell is outside the target range");
                return kInvalidRequest;
            }
            address -= distance;
        } else {
            const uintptr_t distance = static_cast<uintptr_t>(relativeOffset);
            if (address > std::numeric_limits<uintptr_t>::max() - distance) {
                setMessage("Inspector cell is outside the target range");
                return kInvalidRequest;
            }
            address += distance;
        }
        if (address == 0) {
            setMessage("Inspector cell is outside the target range");
            return kInvalidRequest;
        }
        const size_t width = widthOf(*type);
        const bool inTargetRange = std::any_of(
                context.target.ranges.begin(), context.target.ranges.end(),
                [&](const Range &range) {
                    return address >= range.start && address <= range.end &&
                           width <= static_cast<size_t>(range.end - address);
                });
        if (!inTargetRange || address % width != 0) {
            setMessage("Inspector cell is not an aligned readable target value");
            return kInvalidRequest;
        }
        Candidate editable = anchor;
        editable.address = address;
        editable.type = *type;
        editable.initialBits = static_cast<uint64_t>(expectedBits);
        editable.previousBits = static_cast<uint64_t>(expectedBits);
        editable.currentBits = static_cast<uint64_t>(expectedBits);
        editable.state = kStable;
        editable.identityValid = readIdentity(
                context.target, editable.address, editable.type, editable.identityHash);
        if (!editable.identityValid) {
            setMessage("Inspector cell could not be identity-validated");
            return kIdentityUnsafe;
        }
        const int outcome = editOneCandidate(
                context.target, editable, fromJString(env, replacement));
        if (outcome > 0) {
            setMessage("Inspector value updated");
            return kOk;
        }
        if (outcome < 0) {
            setMessage("Replacement value does not fit the selected type");
            return kInvalidRequest;
        }
        setMessage("Inspector value changed before it could be written");
        return kIdentityUnsafe;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_edit(
        JNIEnv *env, jclass, jlongArray rawIds, jstring replacement) {
    return guardedOperation([&] {
        std::vector<uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            setMessage("Invalid candidate selection");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return editCandidates(context, ids, fromJString(env, replacement));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_freeze(
        JNIEnv *env, jclass, jlongArray rawIds, jint mode, jstring first,
        jstring second) {
    return guardedOperation([&] {
        std::vector<uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            setMessage("Invalid Freeze candidate selection");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return freezeCandidates(context, ids, mode, fromJString(env, first),
                                fromJString(env, second));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultCount(JNIEnv *,
                                                                      jclass) {
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    return state->logicalCount > static_cast<uint64_t>(
                                         std::numeric_limits<jlong>::max())
                   ? std::numeric_limits<jlong>::max()
                   : static_cast<jlong>(state->logicalCount);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_scanProgress(
        JNIEnv *env, jclass) {
    const std::array<jlong, 2> progress{
            static_cast<jlong>(gScanBytesScanned.load(std::memory_order_acquire)),
            static_cast<jlong>(gScanBytesTotal.load(std::memory_order_acquire)),
    };
    jlongArray result = env->NewLongArray(static_cast<jsize>(progress.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(progress.size()),
                                progress.data());
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_historyDepth(JNIEnv *,
                                                                       jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gHistory.size() > static_cast<size_t>(std::numeric_limits<jint>::max())
                   ? std::numeric_limits<jint>::max()
                   : static_cast<jint>(gHistory.size());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
        JNIEnv *env, jclass, jint offset, jint limit) {
    if (offset < 0 || limit <= 0 || limit > 100) {
        return nullptr;
    }
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    return candidateAddressPage(env, state,
                                static_cast<size_t>(offset),
                                static_cast<size_t>(limit));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_inspect(
        JNIEnv *env, jclass, jlong candidateId, jint radius) {
    try {
        OperationContext context;
        if (!beginOperation(context)) {
            return inspectionResult(env, kNoSession);
        }
        if (candidateId <= 0) {
            setMessage("Inspector candidate is invalid");
            return inspectionResult(env, kInvalidRequest);
        }
        return inspectCandidateSnapshot(
                env, context, static_cast<uint64_t>(candidateId), radius);
    } catch (const std::bad_alloc &) {
        setMessage("Inspector memory budget could not be reserved");
        return inspectionResult(env, kResourceLimit);
    } catch (...) {
        setMessage("Inspector rejected the request safely");
        return inspectionResult(env, kInvalidRequest);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_pin(JNIEnv *env,
                                                              jclass,
                                                              jlongArray rawIds,
                                                              jboolean add) {
    return guardedOperation([&] {
        std::vector<uint64_t> ids;
        if (!readIds(env, rawIds, ids)) {
            setMessage("Invalid candidate selection");
            return kInvalidRequest;
        }
        OperationContext context;
        if (!beginOperation(context)) {
            return kNoSession;
        }
        return pinCandidates(context, ids, add == JNI_TRUE);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_watchPage(JNIEnv *env,
                                                                    jclass) {
    std::shared_ptr<const SearchState> state;
    std::unordered_map<uint64_t, Candidate> liveCandidates;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
        if (gLiveCandidates.size() + state->watches.size() >
            kLiveOverlayLimit) {
            gLiveCandidates.clear();
        }
        for (const Candidate &watch : state->watches) {
            gLiveCandidates.try_emplace(watch.id, watch);
        }
        liveCandidates = gLiveCandidates;
    }
    return candidatePage(env, state->watches, liveCandidates, 0,
                         state->watches.size());
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearSearch(JNIEnv *,
                                                                      jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto next = std::make_shared<SearchState>();
    next->watches = gState->watches;
    for (Candidate &watch : next->watches) {
        watch = liveCandidate(watch, gLiveCandidates);
    }
    gState = std::move(next);
    gHistory.clear();
    gLiveCandidates.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clearTarget(JNIEnv *,
                                                                      jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    ++gTarget.generation;
    gTarget.pid = 0;
    gTarget.pageSize = 0;
    gTarget.token = 0;
    gTarget.ranges.clear();
    gState = gEmptyState;
    gHistory.clear();
    gLiveCandidates.clear();
    gNextCandidateId = 1;
    gLastMessage = "";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_prepareOperation(
        JNIEnv *, jclass, jlong rawEpoch) {
    if (rawEpoch < 0) {
        return JNI_FALSE;
    }
    const uint64_t epoch = static_cast<uint64_t>(rawEpoch);
    if (gCancellationEpoch.load(std::memory_order_acquire) != epoch) {
        return JNI_FALSE;
    }
    gPreparedCancellationEpoch.store(epoch, std::memory_order_release);
    return gCancellationEpoch.load(std::memory_order_acquire) == epoch
                   ? JNI_TRUE
                   : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_cancel(
        JNIEnv *, jclass, jlong rawEpoch) {
    if (rawEpoch < 0) {
        return;
    }
    const uint64_t epoch = static_cast<uint64_t>(rawEpoch);
    uint64_t observed = gCancellationEpoch.load(std::memory_order_acquire);
    while (observed < epoch &&
           !gCancellationEpoch.compare_exchange_weak(
                   observed, epoch, std::memory_order_release,
                   std::memory_order_acquire)) {
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_lastMessage(
        JNIEnv *env, jclass) {
    std::string message;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        message = gLastMessage;
    }
    return env->NewStringUTF(message.c_str());
}
