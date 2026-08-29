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
#include <atomic>
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
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr jint kOk = 0;
constexpr jint kCancelled = 1;
constexpr jint kInvalidRequest = 2;
constexpr jint kResourceLimit = 3;
constexpr jint kTargetLost = 5;
constexpr jint kNoSession = 6;

constexpr jint kTypeAuto = 0;
constexpr jint kTypeByte = 1;
constexpr jint kTypeShort = 2;
constexpr jint kTypeChar = 3;
constexpr jint kTypeInt = 4;
constexpr jint kTypeLong = 5;
constexpr jint kTypeFloat = 6;
constexpr jint kTypeDouble = 7;

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
// Candidate records are compact native data and never cross Binder in bulk. Two million typed
// aliases covers the dense searches seen in the prototype while retaining a deterministic bound
// on old API 23 devices. An incomplete set is never committed.
constexpr size_t kCandidateLimit = 2'000'000;
constexpr size_t kSnapshotByteLimit = 96U * 1024U * 1024U;
constexpr size_t kReadChunkSize = 256U * 1024U;
constexpr size_t kHistoryLimit = 8;
constexpr size_t kHistoryByteLimit = 192U * 1024U * 1024U;
constexpr size_t kResultStride = 7;

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
    uint64_t id;
    uintptr_t address;
    jint type;
    jint state;
    uint64_t initialBits;
    uint64_t previousBits;
    uint64_t currentBits;
};

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
    std::vector<SnapshotRun> snapshots;
    std::vector<Candidate> candidates;

    size_t retainedBytes() const {
        size_t result =
                sizeof(SearchState) + candidates.size() * sizeof(Candidate);
        for (const SnapshotRun &snapshot : snapshots) {
            if (result >
                std::numeric_limits<size_t>::max() - snapshot.bytes.size()) {
                return std::numeric_limits<size_t>::max();
            }
            result += snapshot.bytes.size();
        }
        return result;
    }
};

struct Query {
    jint type = 0;
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
uint64_t gNextCandidateId = 1;
std::atomic<bool> gCancelled{false};
std::string gLastMessage;

void setMessage(const char *message) {
    std::lock_guard<std::mutex> lock(gMutex);
    gLastMessage = message;
}

size_t widthOf(jint type) {
    switch (type) {
    case kTypeByte:
        return 1;
    case kTypeShort:
    case kTypeChar:
        return 2;
    case kTypeInt:
    case kTypeFloat:
        return 4;
    case kTypeLong:
    case kTypeDouble:
        return 8;
    default:
        return 0;
    }
}

std::vector<jint> expandedTypes(jint requestedType) {
    if (requestedType == kTypeAuto) {
        return {kTypeByte, kTypeShort, kTypeChar,  kTypeInt,
                kTypeLong, kTypeFloat, kTypeDouble};
    }
    if (widthOf(requestedType) == 0) {
        return {};
    }
    return {requestedType};
}

bool parseInteger(const std::string &text, jint type, int64_t &value) {
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
    case kTypeByte:
        minimum = std::numeric_limits<int8_t>::min();
        maximum = std::numeric_limits<int8_t>::max();
        break;
    case kTypeShort:
        minimum = std::numeric_limits<int16_t>::min();
        maximum = std::numeric_limits<int16_t>::max();
        break;
    case kTypeChar:
        minimum = 0;
        maximum = std::numeric_limits<uint16_t>::max();
        break;
    case kTypeInt:
        minimum = std::numeric_limits<int32_t>::min();
        maximum = std::numeric_limits<int32_t>::max();
        break;
    case kTypeLong:
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

bool parseDelta(const std::string &text, jint type, uint64_t &value) {
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
    case kTypeByte:
        maximum = std::numeric_limits<uint8_t>::max();
        break;
    case kTypeShort:
    case kTypeChar:
        maximum = std::numeric_limits<uint16_t>::max();
        break;
    case kTypeInt:
        maximum = std::numeric_limits<uint32_t>::max();
        break;
    case kTypeLong:
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

bool parseQuery(jint type, jint predicate, const std::string &first,
                const std::string &second, Query &query) {
    query.type = type;
    query.floating = type == kTypeFloat || type == kTypeDouble;
    const bool deltaPredicate = predicate >= kIncreasedBy;
    if (predicateNeedsFirst(predicate)) {
        if (query.floating) {
            if (!parseFloating(first, query.floatingFirst)) {
                return false;
            }
            if (deltaPredicate && query.floatingFirst < 0) {
                return false;
            }
            if (type == kTypeFloat) {
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
            if (type == kTypeFloat) {
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

uint64_t loadBits(const uint8_t *data, size_t width) {
    uint64_t result = 0;
    std::memcpy(&result, data, width);
    return result;
}

int64_t integerValue(jint type, uint64_t bits) {
    switch (type) {
    case kTypeByte:
        return static_cast<int8_t>(bits);
    case kTypeShort:
        return static_cast<int16_t>(bits);
    case kTypeChar:
        return static_cast<uint16_t>(bits);
    case kTypeInt:
        return static_cast<int32_t>(bits);
    case kTypeLong:
        return static_cast<int64_t>(bits);
    default:
        return 0;
    }
}

double floatingValue(jint type, uint64_t bits) {
    if (type == kTypeFloat) {
        uint32_t raw = static_cast<uint32_t>(bits);
        float value = 0;
        std::memcpy(&value, &raw, sizeof(value));
        return value;
    }
    double value = 0;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
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

bool readExact(pid_t pid, uintptr_t address, void *destination, size_t size) {
    auto *output = static_cast<uint8_t *>(destination);
    size_t completed = 0;
    while (completed < size) {
        iovec local{output + completed, size - completed};
        iovec remote{reinterpret_cast<void *>(address + completed),
                     size - completed};
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

bool safeAdd(uint64_t &value, uint64_t addition) {
    if (value > std::numeric_limits<uint64_t>::max() - addition) {
        value = std::numeric_limits<uint64_t>::max();
        return false;
    }
    value += addition;
    return true;
}

struct OperationContext {
    Target target;
    std::shared_ptr<const SearchState> state;
    uint64_t nextId;
};

bool beginOperation(OperationContext &context) {
    gCancelled.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lock(gMutex);
    if (gTarget.pid <= 0 || gTarget.token == 0 || gTarget.ranges.empty()) {
        gLastMessage = "No configured MIDlet runtime";
        return false;
    }
    context.target = gTarget;
    context.state = gState;
    context.nextId = gNextCandidateId;
    return true;
}

void trimHistoryLocked() {
    size_t retained = 0;
    for (const auto &state : gHistory) {
        const size_t bytes = state->retainedBytes();
        retained = retained > std::numeric_limits<size_t>::max() - bytes
                           ? std::numeric_limits<size_t>::max()
                           : retained + bytes;
    }
    while (!gHistory.empty() &&
           (gHistory.size() > kHistoryLimit || retained > kHistoryByteLimit)) {
        const size_t removed = gHistory.front()->retainedBytes();
        gHistory.pop_front();
        retained = removed > retained ? 0 : retained - removed;
    }
}

jint commitOperation(const OperationContext &context,
                     std::shared_ptr<SearchState> next, bool preserveHistory) {
    if (gCancelled.load(std::memory_order_acquire)) {
        setMessage("Operation cancelled; previous results were preserved");
        return kCancelled;
    }
    std::lock_guard<std::mutex> lock(gMutex);
    if (gTarget.generation != context.target.generation ||
        gTarget.token != context.target.token) {
        gLastMessage = "MIDlet runtime changed during the operation";
        return kTargetLost;
    }
    if (preserveHistory && gState->mode != StateMode::Empty) {
        gHistory.push_back(gState);
        trimHistoryLocked();
    } else if (!preserveHistory) {
        gHistory.clear();
    }
    gState = std::move(next);
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
    for (jint type : expandedTypes(requestedType)) {
        Query query;
        if (parseQuery(type, predicate, first, second, query)) {
            queries.push_back(query);
        } else if (requestedType != kTypeAuto) {
            return false;
        }
    }
    return !queries.empty();
}

jint scanKnown(const OperationContext &context, jint requestedType,
               jint predicate, const std::string &first,
               const std::string &second) {
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
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = requestedType;
    std::vector<uint8_t> buffer;

    for (const Range &range : context.target.ranges) {
        for (uintptr_t chunkStart = range.start; chunkStart < range.end;) {
            if (gCancelled.load(std::memory_order_acquire)) {
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
                    if (matchesKnown(bits, query, predicate)) {
                        if (next->candidates.size() >= kCandidateLimit) {
                            setMessage("Candidate limit reached; previous "
                                       "results were preserved");
                            return kResourceLimit;
                        }
                        next->candidates.push_back(
                                {context.nextId + next->candidates.size(),
                                 address, query.type, kStable, bits, bits,
                                 bits});
                    }
                    if (address >
                        std::numeric_limits<uintptr_t>::max() - width) {
                        break;
                    }
                    address += width;
                }
            }
            chunkStart += chunkSize;
        }
    }
    next->logicalCount = next->candidates.size();
    OperationContext committed = context;
    committed.nextId += next->candidates.size();
    return commitOperation(committed, std::move(next), false);
}

jint snapshotUnknown(const OperationContext &context, jint requestedType) {
    const std::vector<jint> types = expandedTypes(requestedType);
    if (types.empty()) {
        setMessage("Invalid value type");
        return kInvalidRequest;
    }
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Unknown;
    next->requestedType = requestedType;
    size_t retained = 0;
    for (const Range &range : context.target.ranges) {
        if (gCancelled.load(std::memory_order_acquire)) {
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
        for (jint type : types) {
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
    }
    return commitOperation(context, std::move(next), false);
}

jint captureCurrentImage(const Target &target,
                         std::vector<SnapshotRun> &snapshots) {
    size_t retained = 0;
    snapshots.clear();
    snapshots.reserve(target.ranges.size());
    for (const Range &range : target.ranges) {
        if (gCancelled.load(std::memory_order_acquire)) {
            setMessage("Operation cancelled; previous results were preserved");
            return kCancelled;
        }
        const size_t size = static_cast<size_t>(range.end - range.start);
        if (size > kSnapshotByteLimit - std::min(retained, kSnapshotByteLimit)) {
            setMessage("Refine snapshot exceeds the memory budget");
            return kResourceLimit;
        }
        SnapshotRun snapshot;
        snapshot.start = range.start;
        snapshot.bytes.resize(size);
        if (!readExact(target.pid, range.start, snapshot.bytes.data(), size)) {
            setMessage("A target range changed while it was being refined");
            return kTargetLost;
        }
        retained += size;
        snapshots.push_back(std::move(snapshot));
    }
    return kOk;
}

bool readImageBits(const std::vector<SnapshotRun> &snapshots,
                   const Candidate &candidate, uint64_t &bits) {
    const size_t width = widthOf(candidate.type);
    if (width == 0) {
        return false;
    }
    const auto run = std::upper_bound(
            snapshots.begin(), snapshots.end(), candidate.address,
            [](uintptr_t address, const SnapshotRun &item) {
                return address < item.start;
            });
    if (run == snapshots.begin()) {
        return false;
    }
    const SnapshotRun &snapshot = *std::prev(run);
    if (candidate.address < snapshot.start) {
        return false;
    }
    const size_t offset = static_cast<size_t>(candidate.address - snapshot.start);
    if (offset > snapshot.bytes.size() ||
        width > snapshot.bytes.size() - offset) {
        return false;
    }
    bits = loadBits(snapshot.bytes.data() + offset, width);
    return true;
}

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
    auto next = std::make_shared<SearchState>();
    next->mode = StateMode::Candidates;
    next->requestedType = context.state->requestedType;

    if (context.state->mode == StateMode::Unknown) {
        for (const SnapshotRun &snapshot : context.state->snapshots) {
            std::vector<uint8_t> current(snapshot.bytes.size());
            if (gCancelled.load(std::memory_order_acquire)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            if (!readExact(context.target.pid, snapshot.start, current.data(),
                           current.size())) {
                setMessage("A target range changed while it was being refined");
                return kTargetLost;
            }
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
                    const bool match =
                            relative ? matchesRelative(now, initial, query,
                                                       predicate)
                                     : matchesKnown(now, query, predicate);
                    if (match) {
                        if (next->candidates.size() >= kCandidateLimit) {
                            setMessage("Candidate limit reached; previous "
                                       "results were preserved");
                            return kResourceLimit;
                        }
                        next->candidates.push_back(
                                {context.nextId + next->candidates.size(),
                                 address, query.type, kStable, initial, initial,
                                 now});
                    }
                    if (address >
                        std::numeric_limits<uintptr_t>::max() - width) {
                        break;
                    }
                    address += width;
                }
            }
        }
    } else {
        // Reading one complete resident image turns a million-candidate refine from a million
        // process_vm_readv syscalls into sequential remote reads plus an in-process filter pass.
        // It also makes a published zero trustworthy: partial coverage aborts transactionally.
        std::vector<SnapshotRun> currentImage;
        const jint captureResult =
                captureCurrentImage(context.target, currentImage);
        if (captureResult != kOk) {
            return captureResult;
        }
        next->candidates.reserve(context.state->candidates.size());
        for (const Candidate &candidate : context.state->candidates) {
            if (gCancelled.load(std::memory_order_acquire)) {
                setMessage(
                        "Operation cancelled; previous results were preserved");
                return kCancelled;
            }
            Candidate updated = candidate;
            uint64_t current = 0;
            if (!readImageBits(currentImage, candidate, current)) {
                // The complete image was captured successfully, so an unresolved address is a
                // stale binding. Never keep it as a result that looks editable.
                continue;
            }
            updated.previousBits = candidate.currentBits;
            updated.currentBits = current;
            updated.state = kStable;
            auto query = std::find_if(queries.begin(), queries.end(),
                                      [&](const Query &item) {
                                          return item.type == candidate.type;
                                      });
            if (query == queries.end()) {
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
    }
    next->logicalCount = next->candidates.size();
    OperationContext committed = context;
    if (context.state->mode == StateMode::Unknown) {
        committed.nextId += next->candidates.size();
    }
    return commitOperation(committed, std::move(next), true);
}

std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) {
        return {};
    }
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
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
            gNextCandidateId = 1;
        }
        gCancelled.store(false, std::memory_order_release);
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
    gState = gHistory.back();
    gHistory.pop_back();
    gLastMessage = "";
    return kOk;
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
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_resultPage(
        JNIEnv *env, jclass, jint offset, jint limit) {
    if (offset < 0 || limit <= 0 || limit > 200) {
        return nullptr;
    }
    std::shared_ptr<const SearchState> state;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        state = gState;
    }
    const size_t start =
            std::min(static_cast<size_t>(offset), state->candidates.size());
    const size_t count = std::min(static_cast<size_t>(limit),
                                  state->candidates.size() - start);
    const size_t outputSize = 1U + count * kResultStride;
    std::vector<jlong> output(outputSize);
    output[0] = static_cast<jlong>(count);
    for (size_t index = 0; index < count; ++index) {
        const Candidate &candidate = state->candidates[start + index];
        const size_t base = 1U + index * kResultStride;
        output[base] = static_cast<jlong>(candidate.id);
        output[base + 1U] = static_cast<jlong>(candidate.address);
        output[base + 2U] = candidate.type;
        output[base + 3U] = candidate.state;
        output[base + 4U] = static_cast<jlong>(candidate.initialBits);
        output[base + 5U] = static_cast<jlong>(candidate.previousBits);
        output[base + 6U] = static_cast<jlong>(candidate.currentBits);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(outputSize));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(outputSize),
                                output.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_clear(JNIEnv *,
                                                                jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    ++gTarget.generation;
    gTarget.pid = 0;
    gTarget.pageSize = 0;
    gTarget.token = 0;
    gTarget.ranges.clear();
    gState = gEmptyState;
    gHistory.clear();
    gNextCandidateId = 1;
    gLastMessage = "";
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryEngine_cancel(JNIEnv *,
                                                                 jclass) {
    gCancelled.store(true, std::memory_order_release);
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
