#include <jni.h>

#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr int kScopeFast = 0;
constexpr int kScopeThorough = 1;

constexpr int kTypeAuto = 0;
constexpr int kTypeInt8 = 1;
constexpr int kTypeInt16 = 2;
constexpr int kTypeUInt16 = 3;
constexpr int kTypeInt32 = 4;
constexpr int kTypeInt64 = 5;
constexpr int kTypeFloat32 = 6;
constexpr int kTypeFloat64 = 7;
constexpr int kFirstValueType = kTypeInt8;
constexpr int kLastValueType = kTypeFloat64;
constexpr size_t kValueTypeSlots = static_cast<size_t>(kLastValueType + 1);

constexpr size_t kMaxCandidatesExplicit = 1'000'000;
constexpr size_t kMaxCandidatesPerTypeAuto = 250'000;
constexpr size_t kMaxReadChunk = 256 * 1024;
constexpr int kSnapshotStride = 4;
constexpr size_t kRelocationTrackLimit = 25'000;
constexpr size_t kContextHalfBytes = 32;
constexpr size_t kContextTargetSpan = 8;
constexpr size_t kContextLaneBytes = 4;
constexpr size_t kContextLanes = (kContextHalfBytes * 2) / kContextLaneBytes;
constexpr int kSameAddressMinContextMatches = 2;
constexpr int kExactMultiAliasMinContextMatches = 2;
constexpr int kMultiAliasMinContextMatches = 3;
constexpr int kSingleAliasMinContextMatches = 4;
constexpr double kInt64MinAsDouble = -9223372036854775808.0;
constexpr double kInt64ExclusiveMaxAsDouble = 9223372036854775808.0;

constexpr int kResultOk = 0;
constexpr int kResultCancelled = 1;
constexpr int kResultInvalidQuery = 2;
constexpr int kResultResourceLimit = 3;
constexpr int kResultNoRanges = 4;
constexpr int kResultNoMatches = 5;

struct MemoryRegion {
    uintptr_t start = 0;
    uintptr_t end = 0;
};

struct ContextSignature {
    std::array<uint32_t, kContextLanes> lanes{};
    bool valid = false;
};

struct CandidateBucket {
    std::vector<uintptr_t> addresses;
    std::vector<ContextSignature> contexts;
    uint64_t matches_seen = 0;
    bool overflow = false;
};

using CandidateStore = std::array<CandidateBucket, kValueTypeSlots>;

struct AddressGroup {
    uintptr_t address = 0;
    uint32_t type_mask = 0;
    ContextSignature context;
};

struct UniqueIndex {
    size_t index = 0;
    bool duplicate = false;
};

struct Diagnostics {
    size_t page_size = 0;
    size_t regions_selected = 0;
    uint64_t virtual_bytes_selected = 0;
    uint64_t resident_pages = 0;
    uint64_t pages_skipped = 0;
    uint64_t bytes_read = 0;
    uint64_t read_failures = 0;
    uint64_t mincore_failures = 0;
    uint64_t matches_seen = 0;
    uint64_t duration_ms = 0;
    uint64_t relocation_contexts = 0;
    uint64_t relocation_original = 0;
    uint64_t relocation_fresh_matches = 0;
    uint64_t relocation_recovered = 0;
    uint64_t relocation_ambiguous = 0;
    uint64_t relocation_original_groups = 0;
    uint64_t relocation_fresh_groups = 0;
    uint64_t relocation_recovered_groups = 0;
    uint64_t relocation_unique_rebinds = 0;
    uint64_t relocation_context_rebinds = 0;
    int scope = kScopeFast;
    int search_type = kTypeAuto;
    bool cancelled = false;
    bool resource_limit = false;
    bool relocation_tracking = false;
    bool relocation_attempted = false;
};

struct ParsedValues {
    std::array<bool, kValueTypeSlots> valid{};
    int8_t int8_value = 0;
    int16_t int16_value = 0;
    uint16_t uint16_value = 0;
    int32_t int32_value = 0;
    int64_t int64_value = 0;
    float float32_value = 0.0f;
    double float64_value = 0.0;
};

std::mutex g_mutex;
std::atomic<bool> g_cancel{false};
CandidateStore g_candidates;
Diagnostics g_diag;
std::string g_last_error;
int g_search_type = kTypeAuto;
int g_search_scope = kScopeFast;

const char *typeName(int type) {
    switch (type) {
        case kTypeAuto: return "Auto";
        case kTypeInt8: return "Int8";
        case kTypeInt16: return "Int16";
        case kTypeUInt16: return "UInt16";
        case kTypeInt32: return "Int32";
        case kTypeInt64: return "Int64";
        case kTypeFloat32: return "Float32";
        case kTypeFloat64: return "Float64";
        default: return "Unknown";
    }
}

bool isCandidateType(int type) {
    return type >= kFirstValueType && type <= kLastValueType;
}

uint32_t typeBit(int type) {
    return isCandidateType(type) ? (1u << static_cast<uint32_t>(type)) : 0u;
}

int bitCount(uint32_t value) {
    return __builtin_popcount(value);
}

size_t widthForType(int type) {
    switch (type) {
        case kTypeInt8: return sizeof(int8_t);
        case kTypeInt16: return sizeof(int16_t);
        case kTypeUInt16: return sizeof(uint16_t);
        case kTypeInt32: return sizeof(int32_t);
        case kTypeInt64: return sizeof(int64_t);
        case kTypeFloat32: return sizeof(float);
        case kTypeFloat64: return sizeof(double);
        default: return 0;
    }
}

size_t alignmentForType(int type) {
    return widthForType(type);
}

size_t pageSize() {
    const long value = sysconf(_SC_PAGESIZE);
    return value > 0 ? static_cast<size_t>(value) : 4096u;
}

size_t candidateCount(const CandidateStore &store) {
    size_t total = 0;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        total += store[static_cast<size_t>(type)].addresses.size();
    }
    return total;
}

uint64_t candidateStorageBytes(const CandidateStore &store) {
    uint64_t total = 0;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        const auto &bucket = store[static_cast<size_t>(type)];
        total += static_cast<uint64_t>(bucket.addresses.size()) * sizeof(uintptr_t);
        total += static_cast<uint64_t>(bucket.contexts.size()) * sizeof(ContextSignature);
    }
    return total;
}

uint64_t validContextCount(const CandidateStore &store) {
    uint64_t total = 0;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        for (const auto &context : store[static_cast<size_t>(type)].contexts) {
            if (context.valid) ++total;
        }
    }
    return total;
}

bool hasOverflow(const CandidateStore &store) {
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        if (store[static_cast<size_t>(type)].overflow) return true;
    }
    return false;
}

bool contextsReady(const CandidateStore &store) {
    const size_t total = candidateCount(store);
    if (total == 0 || total > kRelocationTrackLimit) return false;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        const auto &bucket = store[static_cast<size_t>(type)];
        if (bucket.contexts.size() != bucket.addresses.size()) return false;
    }
    return true;
}

void clearContexts(CandidateStore *store) {
    for (auto &bucket : *store) {
        std::vector<ContextSignature>().swap(bucket.contexts);
    }
}

void releaseCandidatesLocked() {
    for (auto &bucket : g_candidates) {
        std::vector<uintptr_t>().swap(bucket.addresses);
        std::vector<ContextSignature>().swap(bucket.contexts);
        bucket.matches_seen = 0;
        bucket.overflow = false;
    }
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

bool parseInt64Strict(const std::string &text, int64_t *out) {
    errno = 0;
    char *end = nullptr;
    const long long parsed = strtoll(text.c_str(), &end, 10);
    if (errno == ERANGE || end == text.c_str() || *end != '\0') return false;
    *out = static_cast<int64_t>(parsed);
    return true;
}

bool parseFiniteDouble(const std::string &text, double *out) {
    errno = 0;
    char *end = nullptr;
    const double parsed = strtod(text.c_str(), &end);
    if (errno == ERANGE || end == text.c_str() || *end != '\0' || !std::isfinite(parsed)) return false;
    *out = parsed;
    return true;
}

bool parseValues(const std::string &text, int selected_type, ParsedValues *out, std::string *error) {
    if (selected_type != kTypeAuto && !isCandidateType(selected_type)) {
        *error = "Unsupported value type";
        return false;
    }

    const std::string value = trim(text);
    if (value.empty()) {
        *error = "Enter a numeric value";
        return false;
    }

    int64_t integer = 0;
    bool has_integer = parseInt64Strict(value, &integer);
    double floating = 0.0;
    const bool has_float = parseFiniteDouble(value, &floating);
    if (!has_integer && has_float && std::trunc(floating) == floating
            && floating >= kInt64MinAsDouble && floating < kInt64ExclusiveMaxAsDouble) {
        integer = static_cast<int64_t>(floating);
        has_integer = true;
    }

    if (has_integer) {
        if (integer >= std::numeric_limits<int8_t>::min() && integer <= std::numeric_limits<int8_t>::max()) {
            out->valid[kTypeInt8] = true;
            out->int8_value = static_cast<int8_t>(integer);
        }
        if (integer >= std::numeric_limits<int16_t>::min() && integer <= std::numeric_limits<int16_t>::max()) {
            out->valid[kTypeInt16] = true;
            out->int16_value = static_cast<int16_t>(integer);
        }
        if (integer >= 0 && integer <= std::numeric_limits<uint16_t>::max()) {
            out->valid[kTypeUInt16] = true;
            out->uint16_value = static_cast<uint16_t>(integer);
        }
        if (integer >= std::numeric_limits<int32_t>::min() && integer <= std::numeric_limits<int32_t>::max()) {
            out->valid[kTypeInt32] = true;
            out->int32_value = static_cast<int32_t>(integer);
        }
        out->valid[kTypeInt64] = true;
        out->int64_value = integer;
    }

    if (has_float) {
        if (std::abs(floating) <= static_cast<double>(std::numeric_limits<float>::max())) {
            const float value32 = static_cast<float>(floating);
            if (std::isfinite(value32)) {
                out->valid[kTypeFloat32] = true;
                out->float32_value = value32;
            }
        }
        out->valid[kTypeFloat64] = true;
        out->float64_value = floating;
    }

    if (selected_type == kTypeAuto) {
        for (int type = kFirstValueType; type <= kLastValueType; ++type) {
            if (out->valid[static_cast<size_t>(type)]) return true;
        }
        *error = "Value cannot be represented by a supported primitive type";
        return false;
    }

    if (!out->valid[static_cast<size_t>(selected_type)]) {
        *error = std::string("Value cannot be represented as ") + typeName(selected_type);
        return false;
    }
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        if (type != selected_type) out->valid[static_cast<size_t>(type)] = false;
    }
    return true;
}

bool isKnownJavaHeapRegion(const std::string &line) {
    static constexpr const char *names[] = {
            "dalvik-main space",
            "dalvik-region space",
            "dalvik-large object space",
            "dalvik-free list large object space",
            "dalvik-non moving space",
            "dalvik-rosalloc space",
            "dalvik-alloc space",
    };
    for (const char *name : names) {
        if (line.find(name) != std::string::npos) return true;
    }
    return false;
}

bool isBroadJavaHeapRegion(const std::string &line) {
    if (isKnownJavaHeapRegion(line)) return true;
    if (line.find("[anon:dalvik-") == std::string::npos) return false;
    if (line.find("space") == std::string::npos) return false;
    if (line.find("zygote space") != std::string::npos) return false;
    return true;
}

bool parseMapRange(const std::string &line, MemoryRegion *region, char permissions[5]) {
    unsigned long long start = 0;
    unsigned long long end = 0;
    if (sscanf(line.c_str(), "%llx-%llx %4s", &start, &end, permissions) != 3) return false;
    if (start >= end || end > static_cast<unsigned long long>(std::numeric_limits<uintptr_t>::max())) {
        return false;
    }
    region->start = static_cast<uintptr_t>(start);
    region->end = static_cast<uintptr_t>(end);
    return true;
}

std::vector<MemoryRegion> collectTargetRegions(int scope) {
    std::ifstream maps("/proc/self/maps");
    std::vector<MemoryRegion> regions;
    std::string line;
    while (std::getline(maps, line)) {
        const bool selected = scope == kScopeThorough
                ? isBroadJavaHeapRegion(line)
                : isKnownJavaHeapRegion(line);
        if (!selected) continue;
        MemoryRegion region;
        char permissions[5] = {};
        if (!parseMapRange(line, &region, permissions)) continue;
        if (permissions[0] != 'r' || permissions[1] != 'w') continue;
        regions.push_back(region);
    }
    return regions;
}

bool findWritableMapping(uintptr_t address, size_t width) {
    if (width == 0 || address > std::numeric_limits<uintptr_t>::max() - (width - 1)) return false;
    const uintptr_t last = address + width - 1;
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        MemoryRegion region;
        char permissions[5] = {};
        if (!parseMapRange(line, &region, permissions)) continue;
        if (permissions[0] == 'r' && permissions[1] == 'w'
                && address >= region.start && last < region.end) return true;
    }
    return false;
}

ssize_t readMemory(uintptr_t address, void *buffer, size_t size) {
    iovec local{buffer, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
}

ssize_t writeMemory(uintptr_t address, const void *buffer, size_t size) {
    iovec local{const_cast<void *>(buffer), size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_writev(getpid(), &local, 1, &remote, 1, 0);
}

bool readExact(uintptr_t address, void *buffer, size_t size) {
    return readMemory(address, buffer, size) == static_cast<ssize_t>(size);
}

bool readContextSignature(uintptr_t address, int, ContextSignature *out) {
    *out = {};
    if (address < kContextHalfBytes) return false;
    if (address > std::numeric_limits<uintptr_t>::max() - kContextTargetSpan) return false;
    const uintptr_t right = address + kContextTargetSpan;
    if (right > std::numeric_limits<uintptr_t>::max() - kContextHalfBytes) return false;

    std::array<uint8_t, kContextHalfBytes * 2> bytes{};
    iovec local[2] = {
            {bytes.data(), kContextHalfBytes},
            {bytes.data() + kContextHalfBytes, kContextHalfBytes},
    };
    iovec remote[2] = {
            {reinterpret_cast<void *>(address - kContextHalfBytes), kContextHalfBytes},
            {reinterpret_cast<void *>(right), kContextHalfBytes},
    };
    const ssize_t read = process_vm_readv(getpid(), local, 2, remote, 2, 0);
    if (read != static_cast<ssize_t>(bytes.size())) return false;

    for (size_t i = 0; i < kContextLanes; ++i) {
        memcpy(&out->lanes[i], bytes.data() + i * kContextLaneBytes, kContextLaneBytes);
    }
    out->valid = true;
    return true;
}

bool informativeLane(uint32_t value) {
    return value != 0u && value != std::numeric_limits<uint32_t>::max();
}

int contextSimilarity(const ContextSignature &a, const ContextSignature &b) {
    if (!a.valid || !b.valid) return 0;
    int score = 0;
    for (size_t i = 0; i < kContextLanes; ++i) {
        if (informativeLane(a.lanes[i]) && a.lanes[i] == b.lanes[i]) ++score;
    }
    return score;
}

bool captureContexts(CandidateStore *store) {
    clearContexts(store);
    const size_t total = candidateCount(*store);
    g_diag.relocation_tracking = false;
    g_diag.relocation_contexts = 0;
    if (total == 0 || total > kRelocationTrackLimit) return false;

    try {
        for (int type = kFirstValueType; type <= kLastValueType; ++type) {
            auto &bucket = (*store)[static_cast<size_t>(type)];
            bucket.contexts.resize(bucket.addresses.size());
        }
    } catch (const std::bad_alloc &) {
        clearContexts(store);
        if (g_last_error.empty()) {
            g_last_error = "Relocation tracking disabled: native context allocation failed";
        }
        return false;
    }

    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        auto &bucket = (*store)[static_cast<size_t>(type)];
        for (size_t i = 0; i < bucket.addresses.size(); ++i) {
            if (readContextSignature(bucket.addresses[i], type, &bucket.contexts[i])) {
                ++g_diag.relocation_contexts;
            }
        }
    }
    g_diag.relocation_tracking = contextsReady(*store);
    return g_diag.relocation_tracking;
}

bool buildAddressGroups(const CandidateStore &store, std::vector<AddressGroup> *groups) {
    groups->clear();
    const size_t total = candidateCount(store);
    if (total == 0) return true;
    try {
        groups->reserve(total);
        for (int type = kFirstValueType; type <= kLastValueType; ++type) {
            const auto &bucket = store[static_cast<size_t>(type)];
            for (size_t i = 0; i < bucket.addresses.size(); ++i) {
                AddressGroup group;
                group.address = bucket.addresses[i];
                group.type_mask = typeBit(type);
                if (i < bucket.contexts.size()) group.context = bucket.contexts[i];
                groups->push_back(group);
            }
        }
        std::sort(groups->begin(), groups->end(), [](const AddressGroup &a, const AddressGroup &b) {
            if (a.address != b.address) return a.address < b.address;
            return a.type_mask < b.type_mask;
        });

        size_t write = 0;
        for (size_t read = 0; read < groups->size(); ++read) {
            if (write > 0 && (*groups)[write - 1].address == (*groups)[read].address) {
                (*groups)[write - 1].type_mask |= (*groups)[read].type_mask;
                if (!(*groups)[write - 1].context.valid && (*groups)[read].context.valid) {
                    (*groups)[write - 1].context = (*groups)[read].context;
                }
            } else {
                if (write != read) (*groups)[write] = (*groups)[read];
                ++write;
            }
        }
        groups->resize(write);
        return true;
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate grouped relocation view";
        groups->clear();
        return false;
    }
}

uintptr_t alignUp(uintptr_t value, size_t alignment) {
    if (alignment <= 1) return value;
    const uintptr_t mask = static_cast<uintptr_t>(alignment - 1);
    if (value > std::numeric_limits<uintptr_t>::max() - mask) return std::numeric_limits<uintptr_t>::max();
    return (value + mask) & ~mask;
}

template <typename T>
void readScalar(const uint8_t *data, size_t offset, T *out) {
    memcpy(out, data + offset, sizeof(T));
}

bool matchesParsedValue(int type, const uint8_t *data, size_t offset, const ParsedValues &expected) {
    switch (type) {
        case kTypeInt8: {
            int8_t actual = 0;
            readScalar(data, offset, &actual);
            return actual == expected.int8_value;
        }
        case kTypeInt16: {
            int16_t actual = 0;
            readScalar(data, offset, &actual);
            return actual == expected.int16_value;
        }
        case kTypeUInt16: {
            uint16_t actual = 0;
            readScalar(data, offset, &actual);
            return actual == expected.uint16_value;
        }
        case kTypeInt32: {
            int32_t actual = 0;
            readScalar(data, offset, &actual);
            return actual == expected.int32_value;
        }
        case kTypeInt64: {
            int64_t actual = 0;
            readScalar(data, offset, &actual);
            return actual == expected.int64_value;
        }
        case kTypeFloat32: {
            float actual = 0.0f;
            readScalar(data, offset, &actual);
            return actual == expected.float32_value;
        }
        case kTypeFloat64: {
            double actual = 0.0;
            readScalar(data, offset, &actual);
            return actual == expected.float64_value;
        }
        default:
            return false;
    }
}

bool appendCandidate(CandidateStore *out, int type, uintptr_t address, bool auto_mode) {
    auto &bucket = (*out)[static_cast<size_t>(type)];
    ++bucket.matches_seen;
    ++g_diag.matches_seen;
    const size_t cap = auto_mode ? kMaxCandidatesPerTypeAuto : kMaxCandidatesExplicit;
    if (bucket.addresses.size() >= cap) {
        if (!bucket.overflow && auto_mode && g_last_error.empty()) {
            g_last_error = "Auto candidate quota reached for one or more types; see diagnostics";
        }
        bucket.overflow = true;
        g_diag.resource_limit = true;
        if (auto_mode) return true;
        g_last_error = std::string("More than ") + std::to_string(cap) + " " + typeName(type)
                + " matches; use Auto or a less common value";
        return false;
    }
    try {
        bucket.addresses.push_back(address);
        return true;
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Native candidate allocation failed";
        return false;
    }
}

bool scanTypeInBuffer(uintptr_t base, const uint8_t *data, size_t length, int type,
        const ParsedValues &expected, CandidateStore *out, bool auto_mode) {
    if (!expected.valid[static_cast<size_t>(type)]) return true;
    auto &bucket = (*out)[static_cast<size_t>(type)];
    if (auto_mode && bucket.overflow) return true;

    const size_t width = widthForType(type);
    const size_t alignment = alignmentForType(type);
    if (width == 0 || length < width) return true;

    const uintptr_t end = base + length;
    uintptr_t address = alignUp(base, alignment);
    uint64_t iterations = 0;
    while (address <= end && width <= end - address) {
        const size_t offset = static_cast<size_t>(address - base);
        if (matchesParsedValue(type, data, offset, expected)) {
            if (!appendCandidate(out, type, address, auto_mode)) return false;
            if (auto_mode && bucket.overflow) return true;
        }
        address += width;
        if ((++iterations & 0xffffu) == 0 && g_cancel.load(std::memory_order_relaxed)) return false;
    }
    return !g_cancel.load(std::memory_order_relaxed);
}

bool scanBuffer(uintptr_t base, const uint8_t *data, size_t length, const ParsedValues &expected,
        CandidateStore *out, bool auto_mode) {
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        if (!scanTypeInBuffer(base, data, length, type, expected, out, auto_mode)) return false;
    }
    return true;
}

bool scanReadableRun(uintptr_t start, uintptr_t end, const ParsedValues &expected,
        CandidateStore *out, bool auto_mode, std::vector<uint8_t> *buffer) {
    uintptr_t cursor = start;
    while (cursor < end) {
        if (g_cancel.load(std::memory_order_relaxed)) return false;
        const size_t requested = static_cast<size_t>(std::min<uintptr_t>(end - cursor, buffer->size()));
        const ssize_t read = readMemory(cursor, buffer->data(), requested);
        if (read <= 0) {
            ++g_diag.read_failures;
            const size_t ps = g_diag.page_size;
            cursor = std::min<uintptr_t>(end, ((cursor / ps) + 1) * ps);
            continue;
        }
        g_diag.bytes_read += static_cast<uint64_t>(read);
        if (!scanBuffer(cursor, buffer->data(), static_cast<size_t>(read), expected, out, auto_mode)) {
            return false;
        }
        cursor += static_cast<uintptr_t>(read);
        if (static_cast<size_t>(read) < requested) {
            ++g_diag.read_failures;
            const size_t ps = g_diag.page_size;
            cursor = std::min<uintptr_t>(end, ((cursor / ps) + 1) * ps);
        }
    }
    return true;
}

bool scanResidentRegion(const MemoryRegion &region, const ParsedValues &expected,
        CandidateStore *out, bool auto_mode, std::vector<uint8_t> *buffer) {
    const size_t ps = g_diag.page_size;
    const uintptr_t length = region.end - region.start;
    const size_t pages = static_cast<size_t>((length + ps - 1) / ps);
    std::vector<unsigned char> residency;
    try {
        residency.resize(pages);
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate resident-page bitmap";
        return false;
    }

    if (mincore(reinterpret_cast<void *>(region.start), static_cast<size_t>(length), residency.data()) != 0) {
        ++g_diag.mincore_failures;
        return true;
    }

    size_t page = 0;
    while (page < pages) {
        while (page < pages && (residency[page] & 1u) == 0) {
            ++g_diag.pages_skipped;
            ++page;
        }
        if (page >= pages) break;
        const size_t first = page;
        while (page < pages && (residency[page] & 1u) != 0) ++page;
        const size_t count = page - first;
        g_diag.resident_pages += count;
        const uintptr_t run_start = region.start + static_cast<uintptr_t>(first) * ps;
        const uintptr_t run_end = std::min<uintptr_t>(
                region.end, region.start + static_cast<uintptr_t>(page) * ps);
        if (!scanReadableRun(run_start, run_end, expected, out, auto_mode, buffer)) return false;
    }
    return true;
}

void resetScanDiagnostics(int scope, int value_type) {
    g_diag = {};
    g_diag.page_size = pageSize();
    g_diag.scope = scope;
    g_diag.search_type = value_type;
    g_cancel.store(false, std::memory_order_relaxed);
    g_last_error.clear();
}

int scanForValue(const std::string &value, int scope, int value_type, CandidateStore *found) {
    resetScanDiagnostics(scope, value_type);

    if (scope != kScopeFast && scope != kScopeThorough) {
        g_last_error = "Unsupported scan scope";
        return kResultInvalidQuery;
    }

    ParsedValues expected;
    if (!parseValues(value, value_type, &expected, &g_last_error)) return kResultInvalidQuery;

    const auto regions = collectTargetRegions(scope);
    g_diag.regions_selected = regions.size();
    for (const auto &region : regions) {
        g_diag.virtual_bytes_selected += static_cast<uint64_t>(region.end - region.start);
    }
    if (regions.empty()) {
        g_last_error = "No supported readable/writable ART heap mappings were found";
        return kResultNoRanges;
    }

    std::vector<uint8_t> scan_buffer;
    try {
        scan_buffer.resize(kMaxReadChunk);
        if (value_type == kTypeAuto) {
            for (int type = kFirstValueType; type <= kLastValueType; ++type) {
                if (expected.valid[static_cast<size_t>(type)]) {
                    (*found)[static_cast<size_t>(type)].addresses.reserve(4096);
                }
            }
        } else {
            (*found)[static_cast<size_t>(value_type)].addresses.reserve(4096);
        }
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate native search working set";
        return kResultResourceLimit;
    }

    const bool auto_mode = value_type == kTypeAuto;
    const auto started = std::chrono::steady_clock::now();
    for (const auto &region : regions) {
        if (!scanResidentRegion(region, expected, found, auto_mode, &scan_buffer)) {
            g_diag.cancelled = g_cancel.load(std::memory_order_relaxed);
            g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - started).count());
            if (g_diag.cancelled) {
                g_last_error = "Search cancelled; previous completed search was retained";
                return kResultCancelled;
            }
            return kResultResourceLimit;
        }
    }
    g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count());

    if (candidateCount(*found) == 0) {
        if (g_diag.mincore_failures == regions.size()) {
            g_last_error = "All selected ART mappings rejected mincore; search was not broadened into cold virtual pages";
        } else {
            g_last_error = "Search found 0 matches";
        }
        return kResultNoMatches;
    }
    return kResultOk;
}

int performSearch(const std::string &value, int scope, int value_type) {
    CandidateStore found;
    const int result = scanForValue(value, scope, value_type, &found);
    if (result != kResultOk) return result;

    captureContexts(&found);
    g_candidates.swap(found);
    g_search_type = value_type;
    g_search_scope = scope;
    return kResultOk;
}

bool reserveRefineStore(CandidateStore *kept) {
    try {
        for (int type = kFirstValueType; type <= kLastValueType; ++type) {
            const auto &source = g_candidates[static_cast<size_t>(type)];
            if (!source.addresses.empty()) {
                (*kept)[static_cast<size_t>(type)].addresses.reserve(source.addresses.size());
            }
        }
        return true;
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate transactional refine store";
        return false;
    }
}

bool refineBucket(int type, const ParsedValues &expected, CandidateStore *kept,
        std::vector<uint8_t> *page_buffer, size_t ps) {
    const auto &source_bucket = g_candidates[static_cast<size_t>(type)];
    const auto &source = source_bucket.addresses;
    if (source.empty() || !expected.valid[static_cast<size_t>(type)]) return true;

    auto &target = (*kept)[static_cast<size_t>(type)];
    target.overflow = source_bucket.overflow;
    const size_t width = widthForType(type);
    size_t i = 0;
    while (i < source.size()) {
        if (g_cancel.load(std::memory_order_relaxed)) return false;
        const uintptr_t page_base = (source[i] / ps) * ps;
        size_t j = i;
        while (j < source.size() && (source[j] / ps) * ps == page_base) ++j;

        const ssize_t read = readMemory(page_base, page_buffer->data(), ps);
        if (read == static_cast<ssize_t>(ps)) {
            g_diag.bytes_read += ps;
            for (size_t n = i; n < j; ++n) {
                const uintptr_t address = source[n];
                const size_t offset = static_cast<size_t>(address - page_base);
                if (offset <= ps - width && matchesParsedValue(type, page_buffer->data(), offset, expected)) {
                    target.addresses.push_back(address);
                    ++target.matches_seen;
                    ++g_diag.matches_seen;
                }
            }
        } else {
            ++g_diag.read_failures;
            if (read > 0) g_diag.bytes_read += static_cast<uint64_t>(read);
        }
        i = j;
    }
    return true;
}

void resetRefineDiagnostics() {
    g_diag.bytes_read = 0;
    g_diag.read_failures = 0;
    g_diag.matches_seen = 0;
    g_diag.duration_ms = 0;
    g_diag.cancelled = false;
    g_diag.resource_limit = false;
    g_diag.search_type = g_search_type;
    g_diag.relocation_contexts = validContextCount(g_candidates);
    g_diag.relocation_original = 0;
    g_diag.relocation_fresh_matches = 0;
    g_diag.relocation_recovered = 0;
    g_diag.relocation_ambiguous = 0;
    g_diag.relocation_original_groups = 0;
    g_diag.relocation_fresh_groups = 0;
    g_diag.relocation_recovered_groups = 0;
    g_diag.relocation_unique_rebinds = 0;
    g_diag.relocation_context_rebinds = 0;
    g_diag.relocation_tracking = contextsReady(g_candidates);
    g_diag.relocation_attempted = false;
    g_cancel.store(false, std::memory_order_relaxed);
    g_last_error.clear();
}

int performRefine(const std::string &value) {
    resetRefineDiagnostics();

    if (candidateCount(g_candidates) == 0) {
        g_last_error = "Next Scan already has 0 candidates";
        return kResultOk;
    }

    ParsedValues expected;
    if (!parseValues(value, g_search_type, &expected, &g_last_error)) return kResultInvalidQuery;

    CandidateStore kept;
    if (!reserveRefineStore(&kept)) return kResultResourceLimit;

    const size_t ps = g_diag.page_size == 0 ? pageSize() : g_diag.page_size;
    std::vector<uint8_t> page_buffer;
    try {
        page_buffer.resize(ps);
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate refine page buffer";
        return kResultResourceLimit;
    }

    const auto started = std::chrono::steady_clock::now();
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        if (!refineBucket(type, expected, &kept, &page_buffer, ps)) {
            g_diag.cancelled = true;
            g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - started).count());
            g_last_error = "Next Scan cancelled; previous candidates were retained";
            return kResultCancelled;
        }
    }
    g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count());
    g_diag.resource_limit = hasOverflow(kept);

    if (candidateCount(kept) == 0) {
        g_last_error = "Next Scan found 0 direct matches; trying relocation recovery";
        return kResultNoMatches;
    }

    captureContexts(&kept);
    g_candidates.swap(kept);
    return kResultOk;
}

int minimumContextMatches(const AddressGroup &old_group, const AddressGroup &fresh_group) {
    const uint32_t overlap = old_group.type_mask & fresh_group.type_mask;
    const int overlap_count = bitCount(overlap);
    if (old_group.address == fresh_group.address) return kSameAddressMinContextMatches;
    if (old_group.type_mask == fresh_group.type_mask && overlap_count >= 2) {
        return kExactMultiAliasMinContextMatches;
    }
    if (overlap_count >= 2) return kMultiAliasMinContextMatches;
    return kSingleAliasMinContextMatches;
}

bool appendRecoveredGroup(const AddressGroup &old_group, const AddressGroup &fresh_group,
        const CandidateStore &source, const CandidateStore &fresh, CandidateStore *recovered) {
    const uint32_t retained_mask = old_group.type_mask & fresh_group.type_mask;
    if (retained_mask == 0u) return true;
    try {
        for (int type = kFirstValueType; type <= kLastValueType; ++type) {
            if ((retained_mask & typeBit(type)) == 0u) continue;
            auto &target = (*recovered)[static_cast<size_t>(type)];
            target.addresses.push_back(fresh_group.address);
            ++target.matches_seen;
            target.overflow = source[static_cast<size_t>(type)].overflow
                    || fresh[static_cast<size_t>(type)].overflow;
        }
        return true;
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate recovered address groups";
        return false;
    }
}

bool recoverAddressGroups(const CandidateStore &source, const std::vector<AddressGroup> &old_groups,
        const CandidateStore &fresh, const std::vector<AddressGroup> &fresh_groups,
        CandidateStore *recovered) {
    if (old_groups.empty() || fresh_groups.empty()) return true;

    const size_t none = std::numeric_limits<size_t>::max();
    try {
        std::vector<size_t> old_for_fresh(fresh_groups.size(), none);
        std::vector<bool> old_matched(old_groups.size(), false);

        auto acceptPair = [&](size_t old_index, size_t fresh_index, bool unique_rebind) {
            if (old_index >= old_groups.size() || fresh_index >= fresh_groups.size()) return false;
            if (old_matched[old_index] || old_for_fresh[fresh_index] != none) return false;
            if ((old_groups[old_index].type_mask & fresh_groups[fresh_index].type_mask) == 0u) return false;
            old_matched[old_index] = true;
            old_for_fresh[fresh_index] = old_index;
            if (unique_rebind) ++g_diag.relocation_unique_rebinds;
            else ++g_diag.relocation_context_rebinds;
            return true;
        };

        if (old_groups.size() == 1 && fresh_groups.size() == 1
                && (old_groups[0].type_mask & fresh_groups[0].type_mask) != 0u) {
            acceptPair(0, 0, true);
        } else {
            std::array<UniqueIndex, 256> old_masks{};
            std::array<uint16_t, 256> fresh_mask_counts{};
            std::array<bool, 256> old_mask_seen{};
            for (size_t i = 0; i < old_groups.size(); ++i) {
                const uint32_t mask = old_groups[i].type_mask & 0xffu;
                if (!old_mask_seen[mask]) {
                    old_masks[mask] = {i, false};
                    old_mask_seen[mask] = true;
                } else if (old_masks[mask].index != i) {
                    old_masks[mask].duplicate = true;
                }
            }
            for (const auto &group : fresh_groups) {
                const uint32_t mask = group.type_mask & 0xffu;
                if (fresh_mask_counts[mask] < std::numeric_limits<uint16_t>::max()) {
                    ++fresh_mask_counts[mask];
                }
            }
            for (size_t j = 0; j < fresh_groups.size(); ++j) {
                const uint32_t mask = fresh_groups[j].type_mask & 0xffu;
                if (bitCount(mask) < 2 || fresh_mask_counts[mask] != 1 || !old_mask_seen[mask]
                        || old_masks[mask].duplicate) {
                    continue;
                }
                acceptPair(old_masks[mask].index, j, true);
            }

            for (size_t j = 0; j < fresh_groups.size(); ++j) {
                if (old_for_fresh[j] != none) continue;
                const auto it = std::lower_bound(old_groups.begin(), old_groups.end(), fresh_groups[j].address,
                        [](const AddressGroup &group, uintptr_t address) { return group.address < address; });
                if (it == old_groups.end() || it->address != fresh_groups[j].address) continue;
                const size_t i = static_cast<size_t>(it - old_groups.begin());
                if (old_matched[i]) continue;
                if ((it->type_mask & fresh_groups[j].type_mask) == 0u) continue;
                if (contextSimilarity(it->context, fresh_groups[j].context) >= kSameAddressMinContextMatches) {
                    acceptPair(i, j, false);
                }
            }

            std::array<std::unordered_map<uint32_t, UniqueIndex>, kContextLanes> indexes;
            for (size_t i = 0; i < old_groups.size(); ++i) {
                if (old_matched[i] || !old_groups[i].context.valid) continue;
                for (size_t lane = 0; lane < kContextLanes; ++lane) {
                    const uint32_t value = old_groups[i].context.lanes[lane];
                    if (!informativeLane(value)) continue;
                    auto [it, inserted] = indexes[lane].emplace(value, UniqueIndex{i, false});
                    if (!inserted && it->second.index != i) it->second.duplicate = true;
                }
            }

            std::vector<size_t> proposed_old(fresh_groups.size(), none);
            std::vector<uint16_t> proposal_count(old_groups.size(), 0);
            for (size_t j = 0; j < fresh_groups.size(); ++j) {
                if (old_for_fresh[j] != none || !fresh_groups[j].context.valid) continue;
                std::array<size_t, kContextLanes> candidate_ids{};
                std::array<int, kContextLanes> candidate_lane_scores{};
                size_t candidate_count = 0;

                for (size_t lane = 0; lane < kContextLanes; ++lane) {
                    const uint32_t value = fresh_groups[j].context.lanes[lane];
                    if (!informativeLane(value)) continue;
                    const auto it = indexes[lane].find(value);
                    if (it == indexes[lane].end() || it->second.duplicate) continue;
                    const size_t old_index = it->second.index;
                    if (old_matched[old_index]
                            || (old_groups[old_index].type_mask & fresh_groups[j].type_mask) == 0u) {
                        continue;
                    }
                    size_t slot = 0;
                    while (slot < candidate_count && candidate_ids[slot] != old_index) ++slot;
                    if (slot == candidate_count) {
                        candidate_ids[candidate_count] = old_index;
                        candidate_lane_scores[candidate_count] = 0;
                        ++candidate_count;
                    }
                    ++candidate_lane_scores[slot];
                }

                int best_composite = -1;
                size_t best_index = none;
                bool tied = false;
                for (size_t slot = 0; slot < candidate_count; ++slot) {
                    const size_t old_index = candidate_ids[slot];
                    const auto &old_group = old_groups[old_index];
                    const int lane_score = candidate_lane_scores[slot];
                    if (lane_score < minimumContextMatches(old_group, fresh_groups[j])) continue;
                    const uint32_t overlap = old_group.type_mask & fresh_groups[j].type_mask;
                    const int overlap_count = bitCount(overlap);
                    const bool exact_mask = old_group.type_mask == fresh_groups[j].type_mask;
                    const int composite = lane_score * 16 + overlap_count * 3
                            + (exact_mask ? 4 : 0)
                            + (old_group.address == fresh_groups[j].address ? 2 : 0);
                    if (composite > best_composite) {
                        best_composite = composite;
                        best_index = old_index;
                        tied = false;
                    } else if (composite == best_composite && composite >= 0) {
                        tied = true;
                    }
                }

                if (best_index == none || tied) {
                    if (tied) ++g_diag.relocation_ambiguous;
                    continue;
                }
                proposed_old[j] = best_index;
                if (proposal_count[best_index] < std::numeric_limits<uint16_t>::max()) {
                    ++proposal_count[best_index];
                }
            }

            for (size_t j = 0; j < fresh_groups.size(); ++j) {
                const size_t old_index = proposed_old[j];
                if (old_index == none) continue;
                if (proposal_count[old_index] == 1 && !old_matched[old_index]) {
                    acceptPair(old_index, j, false);
                } else if (proposal_count[old_index] > 1) {
                    ++g_diag.relocation_ambiguous;
                }
            }
        }

        for (size_t j = 0; j < fresh_groups.size(); ++j) {
            const size_t old_index = old_for_fresh[j];
            if (old_index == none) continue;
            if (!appendRecoveredGroup(old_groups[old_index], fresh_groups[j], source, fresh, recovered)) {
                return false;
            }
            ++g_diag.relocation_recovered_groups;
        }
        return true;
    } catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate grouped relocation matcher";
        return false;
    }
}

int performRelocationRefine(const std::string &value) {
    const size_t original_count = candidateCount(g_candidates);
    if (original_count == 0) {
        resetRefineDiagnostics();
        g_diag.relocation_attempted = true;
        g_last_error = "Next Scan already has 0 candidates";
        return kResultOk;
    }
    if (!contextsReady(g_candidates)) {
        resetRefineDiagnostics();
        g_diag.relocation_attempted = true;
        g_diag.relocation_original = original_count;
        g_last_error = std::string("GC relocation tracking unavailable for ")
                + std::to_string(original_count) + " typed candidates (limit "
                + std::to_string(kRelocationTrackLimit) + "); previous candidates retained";
        return kResultResourceLimit;
    }

    std::vector<AddressGroup> old_groups;
    if (!buildAddressGroups(g_candidates, &old_groups)) return kResultResourceLimit;

    const auto started = std::chrono::steady_clock::now();
    CandidateStore fresh;
    const int scan_result = scanForValue(value, g_search_scope, g_search_type, &fresh);
    g_diag.relocation_attempted = true;
    g_diag.relocation_original = original_count;
    g_diag.relocation_original_groups = old_groups.size();
    g_diag.relocation_fresh_matches = candidateCount(fresh);

    if (scan_result == kResultNoMatches) {
        g_diag.relocation_fresh_groups = 0;
        g_diag.relocation_recovered = 0;
        g_diag.relocation_recovered_groups = 0;
        releaseCandidatesLocked();
        g_last_error = "GC-aware Next Scan complete: 0 candidates";
        g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count());
        return kResultOk;
    }
    if (scan_result != kResultOk) {
        const std::string detail = g_last_error;
        g_last_error = std::string("GC relocation recovery could not build a fresh candidate set: ")
                + detail + "; previous candidates retained";
        g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count());
        return scan_result;
    }

    if (candidateCount(fresh) > kRelocationTrackLimit) {
        g_last_error = std::string("Fresh relocation set has ") + std::to_string(candidateCount(fresh))
                + " typed candidates, above the safe tracking limit " + std::to_string(kRelocationTrackLimit)
                + "; previous candidates retained";
        g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count());
        return kResultResourceLimit;
    }

    captureContexts(&fresh);
    std::vector<AddressGroup> fresh_groups;
    if (!buildAddressGroups(fresh, &fresh_groups)) return kResultResourceLimit;
    g_diag.relocation_fresh_groups = fresh_groups.size();

    CandidateStore recovered;
    if (!recoverAddressGroups(g_candidates, old_groups, fresh, fresh_groups, &recovered)) {
        g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started).count());
        return kResultResourceLimit;
    }

    const size_t recovered_count = candidateCount(recovered);
    g_diag.relocation_recovered = recovered_count;
    g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count());

    if (recovered_count == 0) {
        releaseCandidatesLocked();
        g_diag.relocation_tracking = false;
        g_diag.relocation_contexts = 0;
        g_last_error = "GC-aware Next Scan complete: 0 candidates; no previous address group matched confidently";
        return kResultOk;
    }

    g_diag.resource_limit = hasOverflow(recovered);
    captureContexts(&recovered);
    g_candidates.swap(recovered);
    g_last_error = std::string("GC relocation rebound ")
            + std::to_string(g_diag.relocation_recovered_groups) + " address groups / "
            + std::to_string(recovered_count) + " typed aliases"
            + (g_diag.relocation_ambiguous == 0
                    ? ""
                    : std::string("; skipped ") + std::to_string(g_diag.relocation_ambiguous)
                            + " ambiguous groups");
    return kResultOk;
}

bool readValueBits(uintptr_t address, int type, int64_t *bits) {
    switch (type) {
        case kTypeInt8: {
            int8_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = value;
            return true;
        }
        case kTypeInt16: {
            int16_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = value;
            return true;
        }
        case kTypeUInt16: {
            uint16_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = value;
            return true;
        }
        case kTypeInt32: {
            int32_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = value;
            return true;
        }
        case kTypeInt64: {
            int64_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = value;
            return true;
        }
        case kTypeFloat32: {
            uint32_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = static_cast<int64_t>(value);
            return true;
        }
        case kTypeFloat64: {
            uint64_t value = 0;
            if (!readExact(address, &value, sizeof(value))) return false;
            *bits = static_cast<int64_t>(value);
            return true;
        }
        default:
            return false;
    }
}

bool extractValueBytes(const ParsedValues &values, int type, uint8_t out[8], size_t *width) {
    *width = widthForType(type);
    switch (type) {
        case kTypeInt8: memcpy(out, &values.int8_value, *width); return true;
        case kTypeInt16: memcpy(out, &values.int16_value, *width); return true;
        case kTypeUInt16: memcpy(out, &values.uint16_value, *width); return true;
        case kTypeInt32: memcpy(out, &values.int32_value, *width); return true;
        case kTypeInt64: memcpy(out, &values.int64_value, *width); return true;
        case kTypeFloat32: memcpy(out, &values.float32_value, *width); return true;
        case kTypeFloat64: memcpy(out, &values.float64_value, *width); return true;
        default: return false;
    }
}

std::string performEdit(uintptr_t address, int type, const std::string &expected_text,
        const std::string &replacement_text) {
    if (!isCandidateType(type)) return "Invalid candidate type";

    ParsedValues expected;
    ParsedValues replacement;
    std::string error;
    if (!parseValues(expected_text, type, &expected, &error)) return "Invalid expected value: " + error;
    if (!parseValues(replacement_text, type, &replacement, &error)) return "Invalid replacement value: " + error;

    const size_t width = widthForType(type);
    if (address > std::numeric_limits<uintptr_t>::max() - (width - 1)) return "Candidate address overflows scalar width";
    const size_t ps = pageSize();
    if ((address / ps) != ((address + width - 1) / ps)) return "Candidate crosses a page boundary";
    if (!findWritableMapping(address, width)) return "Candidate is no longer in a readable/writable mapping";

    uint8_t current[8] = {};
    if (!readExact(address, current, width)) return "Unable to re-read candidate before edit";
    if (!matchesParsedValue(type, current, 0, expected)) return "Candidate changed since the supplied live value";

    uint8_t replacement_bytes[8] = {};
    size_t replacement_width = 0;
    if (!extractValueBytes(replacement, type, replacement_bytes, &replacement_width)
            || replacement_width != width) {
        return "Unable to encode replacement value";
    }
    if (writeMemory(address, replacement_bytes, width) != static_cast<ssize_t>(width)) {
        return std::string("process_vm_writev did not complete the ") + std::to_string(width) + "-byte write";
    }

    memset(current, 0, sizeof(current));
    if (!readExact(address, current, width)) return "Write completed but independent readback failed";
    if (!matchesParsedValue(type, current, 0, replacement)) {
        return "Readback differs from requested replacement value";
    }
    return "OK";
}

std::string jstringToString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeSelfTest(JNIEnv *env, jclass) {
    int32_t probe = 0x13572468;
    int32_t readback = 0;
    if (!readExact(reinterpret_cast<uintptr_t>(&probe), &readback, sizeof(readback)) || readback != probe) {
        return toJString(env, "process_vm_readv(self) failed");
    }
    const int32_t replacement = 0x24681357;
    if (writeMemory(reinterpret_cast<uintptr_t>(&probe), &replacement, sizeof(replacement))
            != static_cast<ssize_t>(sizeof(replacement))) {
        return toJString(env, "process_vm_writev(self) failed");
    }
    if (!readExact(reinterpret_cast<uintptr_t>(&probe), &readback, sizeof(readback))
            || readback != replacement) {
        return toJString(env, "self-write readback failed");
    }
    return toJString(env, "OK");
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeSearch(
        JNIEnv *env, jclass, jstring value, jint scope, jint value_type) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performSearch(jstringToString(env, value), scope, value_type);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefine(
        JNIEnv *env, jclass, jstring value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performRefine(jstringToString(env, value));
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefineRelocating(
        JNIEnv *env, jclass, jstring value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performRelocationRefine(jstringToString(env, value));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeGetResultCount(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(candidateCount(g_candidates));
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeFillResultsPage(
        JNIEnv *env, jclass, jlongArray output, jint offset, jint limit) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (output == nullptr || offset < 0 || limit <= 0 || limit > 1000) return -1;
    const jsize required = static_cast<jsize>(1 + static_cast<size_t>(limit) * kSnapshotStride);
    if (env->GetArrayLength(output) < required) return -1;

    const size_t total = candidateCount(g_candidates);
    const size_t first = std::min<size_t>(static_cast<size_t>(offset), total);
    const size_t count = std::min<size_t>(static_cast<size_t>(limit), total - first);

    jlong *page = env->GetLongArrayElements(output, nullptr);
    if (page == nullptr) return -1;
    page[0] = static_cast<jlong>(count);

    size_t max_bucket_size = 0;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        max_bucket_size = std::max(max_bucket_size,
                g_candidates[static_cast<size_t>(type)].addresses.size());
    }

    size_t global_index = 0;
    size_t output_index = 0;
    for (size_t row = 0; row < max_bucket_size && output_index < count; ++row) {
        for (int type = kFirstValueType; type <= kLastValueType && output_index < count; ++type) {
            const auto &addresses = g_candidates[static_cast<size_t>(type)].addresses;
            if (row >= addresses.size()) continue;
            const uintptr_t address = addresses[row];
            if (global_index++ < first) continue;

            int64_t bits = 0;
            const bool readable = readValueBits(address, type, &bits);
            const size_t base = 1 + output_index * kSnapshotStride;
            page[base] = static_cast<jlong>(address);
            page[base + 1] = static_cast<jlong>(type);
            page[base + 2] = readable ? 1 : 0;
            page[base + 3] = static_cast<jlong>(bits);
            ++output_index;
        }
    }
    env->ReleaseLongArrayElements(output, page, 0);
    return static_cast<jint>(output_index);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeEdit(
        JNIEnv *env, jclass, jlong address, jint type, jstring expected, jstring replacement) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return toJString(env, performEdit(static_cast<uintptr_t>(address), type,
            jstringToString(env, expected), jstringToString(env, replacement)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeGetDiagnostics(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::ostringstream out;
    out << "scope=" << (g_diag.scope == kScopeThorough ? "Java Thorough (broad resident ART)" : "Java Fast") << '\n'
        << "operationType=" << typeName(g_diag.search_type) << '\n'
        << "sessionType=" << typeName(g_search_type) << '\n'
        << "pageSize=" << g_diag.page_size << '\n'
        << "regions=" << g_diag.regions_selected << '\n'
        << "virtualBytes=" << g_diag.virtual_bytes_selected << '\n'
        << "residentPages=" << g_diag.resident_pages << '\n'
        << "pagesSkipped=" << g_diag.pages_skipped << '\n'
        << "bytesRead=" << g_diag.bytes_read << '\n'
        << "readFailures=" << g_diag.read_failures << '\n'
        << "mincoreFailures=" << g_diag.mincore_failures << '\n'
        << "matchesSeen=" << g_diag.matches_seen << '\n'
        << "retained=" << candidateCount(g_candidates) << '\n'
        << "candidateBytes=" << candidateStorageBytes(g_candidates) << '\n'
        << "durationMs=" << g_diag.duration_ms << '\n'
        << "cancelled=" << (g_diag.cancelled ? "true" : "false") << '\n'
        << "resourceLimit=" << (g_diag.resource_limit ? "true" : "false") << '\n'
        << "relocationTracking=" << (g_diag.relocation_tracking ? "true" : "false") << '\n'
        << "relocationTrackLimit=" << kRelocationTrackLimit << '\n'
        << "relocationContexts=" << g_diag.relocation_contexts << '\n'
        << "relocationAttempted=" << (g_diag.relocation_attempted ? "true" : "false") << '\n'
        << "relocationOriginalTyped=" << g_diag.relocation_original << '\n'
        << "relocationOriginalGroups=" << g_diag.relocation_original_groups << '\n'
        << "relocationFreshTyped=" << g_diag.relocation_fresh_matches << '\n'
        << "relocationFreshGroups=" << g_diag.relocation_fresh_groups << '\n'
        << "relocationRecoveredTyped=" << g_diag.relocation_recovered << '\n'
        << "relocationRecoveredGroups=" << g_diag.relocation_recovered_groups << '\n'
        << "relocationAmbiguousGroups=" << g_diag.relocation_ambiguous << '\n'
        << "relocationUniqueRebinds=" << g_diag.relocation_unique_rebinds << '\n'
        << "relocationContextRebinds=" << g_diag.relocation_context_rebinds;
    for (int type = kFirstValueType; type <= kLastValueType; ++type) {
        const auto &bucket = g_candidates[static_cast<size_t>(type)];
        if (!bucket.addresses.empty() || bucket.overflow) {
            out << '\n' << typeName(type)
                << "Seen=" << bucket.matches_seen
                << " retained=" << bucket.addresses.size()
                << " overflow=" << (bucket.overflow ? "true" : "false");
        }
    }
    return toJString(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeGetLastError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return toJString(env, g_last_error);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeClear(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false, std::memory_order_relaxed);
    releaseCandidatesLocked();
    g_diag = {};
    g_diag.page_size = pageSize();
    g_last_error.clear();
    g_search_type = kTypeAuto;
    g_search_scope = kScopeFast;
}
