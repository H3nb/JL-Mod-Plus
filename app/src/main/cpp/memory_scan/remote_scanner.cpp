#include <jni.h>
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
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
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
constexpr int kFirstType = kTypeInt8;
constexpr int kLastType = kTypeFloat64;
constexpr size_t kTypeSlots = static_cast<size_t>(kLastType + 1);

constexpr int kResultOk = 0;
constexpr int kResultCancelled = 1;
constexpr int kResultInvalidQuery = 2;
constexpr int kResultResourceLimit = 3;
constexpr int kResultNoRanges = 4;
constexpr int kResultNoMatches = 5;

constexpr size_t kMaxCandidatesExplicit = 1'000'000;
constexpr size_t kMaxCandidatesPerTypeAuto = 250'000;
constexpr size_t kMaxReadChunk = 256 * 1024;
constexpr size_t kRelocationTrackLimit = 25'000;
constexpr size_t kContextHalfBytes = 32;
constexpr size_t kContextTargetSpan = 8;
constexpr size_t kContextLaneBytes = 4;
constexpr size_t kContextLanes = (kContextHalfBytes * 2) / kContextLaneBytes;
constexpr int kSnapshotStride = 4;
constexpr int kMovedMinContextMatches = 3;
constexpr int kSameAddressMinContextMatches = 2;
constexpr double kInt64MinAsDouble = -9223372036854775808.0;
constexpr double kInt64ExclusiveMaxAsDouble = 9223372036854775808.0;

struct MemoryRun {
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
using CandidateStore = std::array<CandidateBucket, kTypeSlots>;

struct AddressGroup {
    uintptr_t address = 0;
    uint32_t type_mask = 0;
    ContextSignature context;
};

struct ParsedValues {
    std::array<bool, kTypeSlots> valid{};
    int8_t i8 = 0;
    int16_t i16 = 0;
    uint16_t u16 = 0;
    int32_t i32 = 0;
    int64_t i64 = 0;
    float f32 = 0.0f;
    double f64 = 0.0;
};

struct Diagnostics {
    size_t page_size = 0;
    size_t resident_runs = 0;
    uint64_t resident_bytes = 0;
    uint64_t resident_pages = 0;
    uint64_t bytes_read = 0;
    uint64_t read_failures = 0;
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
    int scope = kScopeFast;
    int search_type = kTypeAuto;
    bool cancelled = false;
    bool resource_limit = false;
    bool relocation_tracking = false;
    bool relocation_attempted = false;
    bool runs_truncated = false;
};

std::mutex g_mutex;
std::atomic<bool> g_cancel{false};
pid_t g_target_pid = -1;
size_t g_page_size = 4096;
std::vector<MemoryRun> g_runs;
bool g_runs_truncated = false;
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

bool isCandidateType(int type) { return type >= kFirstType && type <= kLastType; }
uint32_t typeBit(int type) { return isCandidateType(type) ? (1u << static_cast<uint32_t>(type)) : 0u; }

size_t widthForType(int type) {
    switch (type) {
        case kTypeInt8: return 1;
        case kTypeInt16:
        case kTypeUInt16: return 2;
        case kTypeInt32:
        case kTypeFloat32: return 4;
        case kTypeInt64:
        case kTypeFloat64: return 8;
        default: return 0;
    }
}

size_t candidateCount(const CandidateStore &store) {
    size_t total = 0;
    for (int type = kFirstType; type <= kLastType; ++type) {
        total += store[static_cast<size_t>(type)].addresses.size();
    }
    return total;
}

uint64_t candidateBytes(const CandidateStore &store) {
    uint64_t total = 0;
    for (int type = kFirstType; type <= kLastType; ++type) {
        const auto &bucket = store[static_cast<size_t>(type)];
        total += bucket.addresses.size() * sizeof(uintptr_t);
        total += bucket.contexts.size() * sizeof(ContextSignature);
    }
    return total;
}

void clearStore(CandidateStore *store) {
    for (auto &bucket : *store) {
        std::vector<uintptr_t>().swap(bucket.addresses);
        std::vector<ContextSignature>().swap(bucket.contexts);
        bucket.matches_seen = 0;
        bucket.overflow = false;
    }
}

void releaseCandidates() { clearStore(&g_candidates); }

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

bool parseValues(const std::string &raw, int selected_type, ParsedValues *out, std::string *error) {
    if (selected_type != kTypeAuto && !isCandidateType(selected_type)) {
        *error = "Unsupported value type";
        return false;
    }
    const std::string value = trim(raw);
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
            out->valid[kTypeInt8] = true; out->i8 = static_cast<int8_t>(integer);
        }
        if (integer >= std::numeric_limits<int16_t>::min() && integer <= std::numeric_limits<int16_t>::max()) {
            out->valid[kTypeInt16] = true; out->i16 = static_cast<int16_t>(integer);
        }
        if (integer >= 0 && integer <= std::numeric_limits<uint16_t>::max()) {
            out->valid[kTypeUInt16] = true; out->u16 = static_cast<uint16_t>(integer);
        }
        if (integer >= std::numeric_limits<int32_t>::min() && integer <= std::numeric_limits<int32_t>::max()) {
            out->valid[kTypeInt32] = true; out->i32 = static_cast<int32_t>(integer);
        }
        out->valid[kTypeInt64] = true; out->i64 = integer;
    }
    if (has_float) {
        if (std::abs(floating) <= static_cast<double>(std::numeric_limits<float>::max())) {
            const float v = static_cast<float>(floating);
            if (std::isfinite(v)) { out->valid[kTypeFloat32] = true; out->f32 = v; }
        }
        out->valid[kTypeFloat64] = true; out->f64 = floating;
    }

    if (selected_type == kTypeAuto) {
        for (int type = kFirstType; type <= kLastType; ++type) {
            if (out->valid[static_cast<size_t>(type)]) return true;
        }
        *error = "Value cannot be represented by a supported primitive type";
        return false;
    }
    if (!out->valid[static_cast<size_t>(selected_type)]) {
        *error = std::string("Value cannot be represented as ") + typeName(selected_type);
        return false;
    }
    for (int type = kFirstType; type <= kLastType; ++type) {
        if (type != selected_type) out->valid[static_cast<size_t>(type)] = false;
    }
    return true;
}

ssize_t remoteRead(uintptr_t address, void *buffer, size_t size) {
    if (g_target_pid <= 0) { errno = ESRCH; return -1; }
    iovec local{buffer, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_readv(g_target_pid, &local, 1, &remote, 1, 0);
}

ssize_t remoteWrite(uintptr_t address, const void *buffer, size_t size) {
    if (g_target_pid <= 0) { errno = ESRCH; return -1; }
    iovec local{const_cast<void *>(buffer), size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_writev(g_target_pid, &local, 1, &remote, 1, 0);
}

bool readExact(uintptr_t address, void *buffer, size_t size) {
    return remoteRead(address, buffer, size) == static_cast<ssize_t>(size);
}

bool writableMapping(uintptr_t address, size_t width) {
    if (g_target_pid <= 0 || width == 0 || address > std::numeric_limits<uintptr_t>::max() - (width - 1)) return false;
    const uintptr_t last = address + width - 1;
    std::ifstream maps("/proc/" + std::to_string(g_target_pid) + "/maps");
    std::string line;
    while (std::getline(maps, line)) {
        unsigned long long start = 0, end = 0;
        char perms[5] = {};
        if (sscanf(line.c_str(), "%llx-%llx %4s", &start, &end, perms) != 3) continue;
        if (perms[0] == 'r' && perms[1] == 'w' && address >= start && last < end) return true;
    }
    return false;
}

template <typename T>
void readScalar(const uint8_t *data, size_t offset, T *out) { memcpy(out, data + offset, sizeof(T)); }

bool matches(int type, const uint8_t *data, size_t offset, const ParsedValues &expected) {
    switch (type) {
        case kTypeInt8: { int8_t v; readScalar(data, offset, &v); return v == expected.i8; }
        case kTypeInt16: { int16_t v; readScalar(data, offset, &v); return v == expected.i16; }
        case kTypeUInt16: { uint16_t v; readScalar(data, offset, &v); return v == expected.u16; }
        case kTypeInt32: { int32_t v; readScalar(data, offset, &v); return v == expected.i32; }
        case kTypeInt64: { int64_t v; readScalar(data, offset, &v); return v == expected.i64; }
        case kTypeFloat32: { float v; readScalar(data, offset, &v); return v == expected.f32; }
        case kTypeFloat64: { double v; readScalar(data, offset, &v); return v == expected.f64; }
        default: return false;
    }
}

uintptr_t alignUp(uintptr_t value, size_t alignment) {
    if (alignment <= 1) return value;
    const uintptr_t mask = alignment - 1;
    if (value > std::numeric_limits<uintptr_t>::max() - mask) return std::numeric_limits<uintptr_t>::max();
    return (value + mask) & ~mask;
}

bool appendCandidate(CandidateStore *store, int type, uintptr_t address, bool auto_mode) {
    auto &bucket = (*store)[static_cast<size_t>(type)];
    ++bucket.matches_seen;
    ++g_diag.matches_seen;
    const size_t cap = auto_mode ? kMaxCandidatesPerTypeAuto : kMaxCandidatesExplicit;
    if (bucket.addresses.size() >= cap) {
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

bool scanBuffer(uintptr_t base, const uint8_t *data, size_t length,
        const ParsedValues &expected, CandidateStore *store, bool auto_mode) {
    for (int type = kFirstType; type <= kLastType; ++type) {
        if (!expected.valid[static_cast<size_t>(type)]) continue;
        auto &bucket = (*store)[static_cast<size_t>(type)];
        if (auto_mode && bucket.overflow) continue;
        const size_t width = widthForType(type);
        if (length < width) continue;
        const uintptr_t end = base + length;
        uintptr_t address = alignUp(base, width);
        uint64_t iterations = 0;
        while (address <= end && width <= end - address) {
            if (matches(type, data, static_cast<size_t>(address - base), expected)) {
                if (!appendCandidate(store, type, address, auto_mode)) return false;
                if (auto_mode && bucket.overflow) break;
            }
            address += width;
            if ((++iterations & 0xffffu) == 0 && g_cancel.load(std::memory_order_relaxed)) return false;
        }
    }
    return !g_cancel.load(std::memory_order_relaxed);
}

void resetScanDiagnostics(int scope, int type) {
    g_diag = {};
    g_diag.page_size = g_page_size;
    g_diag.scope = scope;
    g_diag.search_type = type;
    g_diag.resident_runs = g_runs.size();
    g_diag.runs_truncated = g_runs_truncated;
    for (const auto &run : g_runs) {
        if (run.end <= run.start) continue;
        const uint64_t bytes = run.end - run.start;
        g_diag.resident_bytes += bytes;
        g_diag.resident_pages += (bytes + g_page_size - 1) / g_page_size;
    }
}

int scanForValue(const std::string &value, int scope, int type, CandidateStore *out) {
    clearStore(out);
    ParsedValues parsed;
    std::string error;
    if (!parseValues(value, type, &parsed, &error)) {
        g_last_error = error;
        return kResultInvalidQuery;
    }
    if (g_target_pid <= 0 || g_runs.empty()) {
        g_last_error = "Remote target has no resident Java runs";
        return kResultNoRanges;
    }
    resetScanDiagnostics(scope, type);
    g_cancel.store(false, std::memory_order_relaxed);
    const auto started = std::chrono::steady_clock::now();
    std::vector<uint8_t> buffer;
    try { buffer.resize(kMaxReadChunk); }
    catch (const std::bad_alloc &) {
        g_diag.resource_limit = true;
        g_last_error = "Unable to allocate remote scan buffer";
        return kResultResourceLimit;
    }

    const bool auto_mode = type == kTypeAuto;
    for (const MemoryRun &run : g_runs) {
        uintptr_t cursor = run.start;
        while (cursor < run.end) {
            if (g_cancel.load(std::memory_order_relaxed)) {
                g_diag.cancelled = true;
                g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - started).count());
                return kResultCancelled;
            }
            const size_t requested = static_cast<size_t>(std::min<uintptr_t>(run.end - cursor, buffer.size()));
            const ssize_t got = remoteRead(cursor, buffer.data(), requested);
            if (got <= 0) {
                ++g_diag.read_failures;
                cursor = std::min<uintptr_t>(run.end, ((cursor / g_page_size) + 1) * g_page_size);
                continue;
            }
            g_diag.bytes_read += static_cast<uint64_t>(got);
            if (!scanBuffer(cursor, buffer.data(), static_cast<size_t>(got), parsed, out, auto_mode)) {
                if (g_cancel.load(std::memory_order_relaxed)) {
                    g_diag.cancelled = true;
                    return kResultCancelled;
                }
                return kResultResourceLimit;
            }
            cursor += static_cast<uintptr_t>(got);
            if (static_cast<size_t>(got) < requested) {
                ++g_diag.read_failures;
                cursor = std::min<uintptr_t>(run.end, ((cursor / g_page_size) + 1) * g_page_size);
            }
        }
    }
    g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count());
    return kResultOk;
}

bool informativeLane(uint32_t value) {
    return value != 0u && value != std::numeric_limits<uint32_t>::max();
}

bool readContext(uintptr_t address, ContextSignature *out) {
    *out = {};
    if (address < kContextHalfBytes || address > std::numeric_limits<uintptr_t>::max() - kContextTargetSpan) return false;
    const uintptr_t right = address + kContextTargetSpan;
    if (right > std::numeric_limits<uintptr_t>::max() - kContextHalfBytes) return false;
    std::array<uint8_t, kContextHalfBytes * 2> bytes{};
    iovec local[2] = {{bytes.data(), kContextHalfBytes}, {bytes.data() + kContextHalfBytes, kContextHalfBytes}};
    iovec remote[2] = {
            {reinterpret_cast<void *>(address - kContextHalfBytes), kContextHalfBytes},
            {reinterpret_cast<void *>(right), kContextHalfBytes},
    };
    const ssize_t got = process_vm_readv(g_target_pid, local, 2, remote, 2, 0);
    if (got != static_cast<ssize_t>(bytes.size())) return false;
    for (size_t i = 0; i < kContextLanes; ++i) {
        memcpy(&out->lanes[i], bytes.data() + i * kContextLaneBytes, kContextLaneBytes);
    }
    out->valid = true;
    return true;
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
    const size_t total = candidateCount(*store);
    g_diag.relocation_tracking = false;
    g_diag.relocation_contexts = 0;
    for (auto &bucket : *store) std::vector<ContextSignature>().swap(bucket.contexts);
    if (total == 0 || total > kRelocationTrackLimit) return false;
    try {
        for (int type = kFirstType; type <= kLastType; ++type) {
            auto &bucket = (*store)[static_cast<size_t>(type)];
            bucket.contexts.resize(bucket.addresses.size());
            for (size_t i = 0; i < bucket.addresses.size(); ++i) {
                if (readContext(bucket.addresses[i], &bucket.contexts[i])) ++g_diag.relocation_contexts;
            }
        }
    } catch (const std::bad_alloc &) {
        for (auto &bucket : *store) std::vector<ContextSignature>().swap(bucket.contexts);
        return false;
    }
    for (int type = kFirstType; type <= kLastType; ++type) {
        const auto &bucket = (*store)[static_cast<size_t>(type)];
        if (bucket.contexts.size() != bucket.addresses.size()) return false;
    }
    g_diag.relocation_tracking = true;
    return true;
}

bool contextsReady(const CandidateStore &store) {
    const size_t total = candidateCount(store);
    if (total == 0 || total > kRelocationTrackLimit) return false;
    for (int type = kFirstType; type <= kLastType; ++type) {
        const auto &bucket = store[static_cast<size_t>(type)];
        if (bucket.contexts.size() != bucket.addresses.size()) return false;
    }
    return true;
}

bool buildGroups(const CandidateStore &store, std::vector<AddressGroup> *groups) {
    groups->clear();
    try {
        groups->reserve(candidateCount(store));
        for (int type = kFirstType; type <= kLastType; ++type) {
            const auto &bucket = store[static_cast<size_t>(type)];
            for (size_t i = 0; i < bucket.addresses.size(); ++i) {
                AddressGroup group;
                group.address = bucket.addresses[i];
                group.type_mask = typeBit(type);
                if (i < bucket.contexts.size()) group.context = bucket.contexts[i];
                groups->push_back(group);
            }
        }
        std::sort(groups->begin(), groups->end(), [](const auto &a, const auto &b) {
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
        g_last_error = "Unable to allocate grouped relocation view";
        return false;
    }
}

bool appendRecoveredMask(CandidateStore *out, uintptr_t address, uint32_t mask,
        const CandidateStore *fresh = nullptr) {
    for (int type = kFirstType; type <= kLastType; ++type) {
        if ((mask & typeBit(type)) == 0) continue;
        auto &bucket = (*out)[static_cast<size_t>(type)];
        try { bucket.addresses.push_back(address); }
        catch (const std::bad_alloc &) { return false; }
        if (fresh != nullptr) {
            bucket.matches_seen = (*fresh)[static_cast<size_t>(type)].matches_seen;
            bucket.overflow = (*fresh)[static_cast<size_t>(type)].overflow;
        }
    }
    return true;
}

int performSearch(const std::string &value, int scope, int type) {
    CandidateStore fresh;
    const int result = scanForValue(value, scope, type, &fresh);
    if (result != kResultOk) return result;
    captureContexts(&fresh);
    releaseCandidates();
    g_candidates.swap(fresh);
    g_search_scope = scope;
    g_search_type = type;
    g_diag.resource_limit = false;
    for (int t = kFirstType; t <= kLastType; ++t) {
        if (g_candidates[static_cast<size_t>(t)].overflow) g_diag.resource_limit = true;
    }
    g_last_error = g_runs_truncated
            ? "Resident-run bridge was truncated; search coverage may be incomplete"
            : "";
    return kResultOk;
}

int performDirectRefine(const std::string &value) {
    ParsedValues parsed;
    std::string error;
    if (!parseValues(value, g_search_type, &parsed, &error)) {
        g_last_error = error;
        return kResultInvalidQuery;
    }
    CandidateStore filtered;
    g_diag.relocation_attempted = false;
    g_diag.matches_seen = 0;
    g_diag.read_failures = 0;
    g_diag.bytes_read = 0;
    const auto started = std::chrono::steady_clock::now();
    for (int type = kFirstType; type <= kLastType; ++type) {
        const auto &source = g_candidates[static_cast<size_t>(type)].addresses;
        auto &dest = filtered[static_cast<size_t>(type)].addresses;
        try { dest.reserve(source.size()); } catch (const std::bad_alloc &) { return kResultResourceLimit; }
        const size_t width = widthForType(type);
        std::array<uint8_t, 8> bytes{};
        for (uintptr_t address : source) {
            if (g_cancel.load(std::memory_order_relaxed)) return kResultCancelled;
            const ssize_t got = remoteRead(address, bytes.data(), width);
            if (got != static_cast<ssize_t>(width)) { ++g_diag.read_failures; continue; }
            g_diag.bytes_read += width;
            if (parsed.valid[static_cast<size_t>(type)] && matches(type, bytes.data(), 0, parsed)) {
                dest.push_back(address);
                ++filtered[static_cast<size_t>(type)].matches_seen;
                ++g_diag.matches_seen;
            }
        }
    }
    g_diag.duration_ms = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count());
    if (candidateCount(filtered) == 0) {
        g_last_error = "Direct Next Scan found no candidates";
        return kResultNoMatches;
    }
    captureContexts(&filtered);
    releaseCandidates();
    g_candidates.swap(filtered);
    g_last_error.clear();
    return kResultOk;
}

int performRelocationRefine(const std::string &value) {
    const size_t original_count = candidateCount(g_candidates);
    g_diag.relocation_attempted = true;
    g_diag.relocation_original = original_count;
    if (original_count == 0) return kResultOk;
    if (!contextsReady(g_candidates)) {
        releaseCandidates();
        g_diag.relocation_tracking = false;
        g_last_error = "Next Scan complete: 0 candidates; relocation identity unavailable for previous set";
        return kResultOk;
    }

    std::vector<AddressGroup> old_groups;
    if (!buildGroups(g_candidates, &old_groups)) return kResultResourceLimit;
    CandidateStore fresh;
    const int scan_result = scanForValue(value, g_search_scope, g_search_type, &fresh);
    g_diag.relocation_attempted = true;
    g_diag.relocation_original = original_count;
    g_diag.relocation_original_groups = old_groups.size();
    g_diag.relocation_fresh_matches = candidateCount(fresh);
    if (scan_result != kResultOk) {
        releaseCandidates();
        g_last_error = "Next Scan complete: 0 candidates; remote relocation recovery could not build a fresh set";
        return scan_result == kResultCancelled ? scan_result : kResultOk;
    }
    if (candidateCount(fresh) == 0 || candidateCount(fresh) > kRelocationTrackLimit) {
        releaseCandidates();
        g_diag.relocation_tracking = false;
        g_last_error = candidateCount(fresh) == 0
                ? "GC/address-aware Next Scan complete: 0 candidates"
                : "Next Scan complete: 0 candidates; fresh relocation pool exceeded safe identity limit";
        return kResultOk;
    }
    captureContexts(&fresh);
    std::vector<AddressGroup> fresh_groups;
    if (!buildGroups(fresh, &fresh_groups)) return kResultResourceLimit;
    g_diag.relocation_fresh_groups = fresh_groups.size();

    const size_t none = std::numeric_limits<size_t>::max();
    std::vector<size_t> proposal(old_groups.size(), none);
    std::vector<int> proposal_score(old_groups.size(), -1);
    std::vector<int> fresh_claims(fresh_groups.size(), 0);
    const bool one_to_one = old_groups.size() == 1 && fresh_groups.size() == 1
            && (old_groups[0].type_mask & fresh_groups[0].type_mask) != 0;

    for (size_t i = 0; i < old_groups.size(); ++i) {
        int best = -1;
        int second = -1;
        size_t best_index = none;
        for (size_t j = 0; j < fresh_groups.size(); ++j) {
            if ((old_groups[i].type_mask & fresh_groups[j].type_mask) == 0) continue;
            const int score = contextSimilarity(old_groups[i].context, fresh_groups[j].context);
            const bool same_address = old_groups[i].address == fresh_groups[j].address;
            const int minimum = same_address ? kSameAddressMinContextMatches : kMovedMinContextMatches;
            if (!one_to_one && score < minimum) continue;
            if (score > best) { second = best; best = score; best_index = j; }
            else if (score > second) { second = score; }
        }
        if (one_to_one) { best_index = 0; best = 0; second = -1; }
        if (best_index == none) continue;
        if (!one_to_one && best == second) { ++g_diag.relocation_ambiguous; continue; }
        proposal[i] = best_index;
        proposal_score[i] = best;
        ++fresh_claims[best_index];
    }

    CandidateStore recovered;
    for (size_t i = 0; i < old_groups.size(); ++i) {
        const size_t j = proposal[i];
        if (j == none) continue;
        if (!one_to_one && fresh_claims[j] != 1) {
            ++g_diag.relocation_ambiguous;
            continue;
        }
        const uint32_t mask = old_groups[i].type_mask & fresh_groups[j].type_mask;
        if (mask == 0) continue;
        if (!appendRecoveredMask(&recovered, fresh_groups[j].address, mask, &fresh)) {
            g_last_error = "Unable to allocate recovered candidate set";
            return kResultResourceLimit;
        }
        ++g_diag.relocation_recovered_groups;
    }
    g_diag.relocation_recovered = candidateCount(recovered);
    if (g_diag.relocation_recovered == 0) {
        releaseCandidates();
        g_diag.relocation_tracking = false;
        g_last_error = "Address-aware Next Scan complete: 0 candidates; no previous address group matched confidently";
        return kResultOk;
    }
    captureContexts(&recovered);
    releaseCandidates();
    g_candidates.swap(recovered);
    g_last_error = std::string("Remote address relocation rebound ")
            + std::to_string(g_diag.relocation_recovered_groups) + " groups / "
            + std::to_string(g_diag.relocation_recovered) + " typed aliases";
    return kResultOk;
}

bool valueBits(uintptr_t address, int type, int64_t *bits) {
    uint64_t raw = 0;
    const size_t width = widthForType(type);
    if (width == 0 || !readExact(address, &raw, width)) return false;
    switch (type) {
        case kTypeInt8: *bits = static_cast<int8_t>(raw); break;
        case kTypeInt16: *bits = static_cast<int16_t>(raw); break;
        case kTypeUInt16: *bits = static_cast<uint16_t>(raw); break;
        case kTypeInt32: *bits = static_cast<int32_t>(raw); break;
        case kTypeInt64: *bits = static_cast<int64_t>(raw); break;
        case kTypeFloat32: *bits = static_cast<int64_t>(static_cast<uint32_t>(raw)); break;
        case kTypeFloat64: *bits = static_cast<int64_t>(raw); break;
        default: return false;
    }
    return true;
}

bool encodeValue(const ParsedValues &values, int type, uint8_t out[8], size_t *width) {
    *width = widthForType(type);
    switch (type) {
        case kTypeInt8: memcpy(out, &values.i8, *width); return true;
        case kTypeInt16: memcpy(out, &values.i16, *width); return true;
        case kTypeUInt16: memcpy(out, &values.u16, *width); return true;
        case kTypeInt32: memcpy(out, &values.i32, *width); return true;
        case kTypeInt64: memcpy(out, &values.i64, *width); return true;
        case kTypeFloat32: memcpy(out, &values.f32, *width); return true;
        case kTypeFloat64: memcpy(out, &values.f64, *width); return true;
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
    if (width == 0 || address > std::numeric_limits<uintptr_t>::max() - (width - 1)
            || (address / g_page_size) != ((address + width - 1) / g_page_size)) {
        return "Candidate crosses a page boundary or has invalid width";
    }
    if (!writableMapping(address, width)) return "Candidate is no longer in a target readable/writable mapping";
    uint8_t current[8] = {};
    if (!readExact(address, current, width)) return "Unable to re-read remote candidate before edit";
    if (!matches(type, current, 0, expected)) return "Candidate changed since the supplied live value";
    uint8_t replacement_bytes[8] = {};
    size_t replacement_width = 0;
    if (!encodeValue(replacement, type, replacement_bytes, &replacement_width) || replacement_width != width) {
        return "Unable to encode replacement value";
    }
    if (remoteWrite(address, replacement_bytes, width) != static_cast<ssize_t>(width)) {
        return std::string("remote process_vm_writev failed: errno ") + std::to_string(errno);
    }
    memset(current, 0, sizeof(current));
    if (!readExact(address, current, width)) return "Remote write completed but readback failed";
    if (!matches(type, current, 0, replacement)) return "Remote readback differs from requested replacement";
    return "OK";
}

std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJString(JNIEnv *env, const std::string &value) { return env->NewStringUTF(value.c_str()); }

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeConfigureTarget(
        JNIEnv *env, jclass, jint target_pid, jint page_size, jlongArray runs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (target_pid <= 0 || page_size <= 0 || runs == nullptr || env->GetArrayLength(runs) < 2) {
        return toJString(env, "invalid remote target configuration");
    }
    const jsize length = env->GetArrayLength(runs);
    std::vector<jlong> raw(static_cast<size_t>(length));
    env->GetLongArrayRegion(runs, 0, length, raw.data());
    if (env->ExceptionCheck()) return toJString(env, "unable to read resident-run payload");
    const int count = static_cast<int>(raw[0]);
    const bool truncated = raw[1] != 0;
    if (count < 0 || 2 + count * 2 > length) return toJString(env, "malformed resident-run payload");
    std::vector<MemoryRun> parsed;
    try { parsed.reserve(static_cast<size_t>(count)); }
    catch (const std::bad_alloc &) { return toJString(env, "resident-run allocation failed"); }
    for (int i = 0; i < count; ++i) {
        const uintptr_t start = static_cast<uintptr_t>(raw[2 + i * 2]);
        const uintptr_t end = static_cast<uintptr_t>(raw[3 + i * 2]);
        if (start == 0 || end <= start) continue;
        parsed.push_back({start, end});
    }
    if (g_target_pid != target_pid) releaseCandidates();
    g_target_pid = target_pid;
    g_page_size = static_cast<size_t>(page_size);
    g_runs.swap(parsed);
    g_runs_truncated = truncated;
    return toJString(env, "OK");
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeSearch(
        JNIEnv *env, jclass, jstring value, jint scope, jint type) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performSearch(fromJString(env, value), scope, type);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeRefine(
        JNIEnv *env, jclass, jstring value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performDirectRefine(fromJString(env, value));
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeRefineRelocating(
        JNIEnv *env, jclass, jstring value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return performRelocationRefine(fromJString(env, value));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeCanRelocate(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return contextsReady(g_candidates) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeCommitZero(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseCandidates();
    g_diag.relocation_tracking = false;
    g_last_error = "Next Scan complete: 0 candidates";
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeGetResultCount(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jlong>(candidateCount(g_candidates));
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeFillResultsPage(
        JNIEnv *env, jclass, jlongArray output, jint offset, jint limit) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (output == nullptr || offset < 0 || limit <= 0 || limit > 1000) return -1;
    const jsize required = 1 + limit * kSnapshotStride;
    if (env->GetArrayLength(output) < required) return -1;
    const size_t total = candidateCount(g_candidates);
    const size_t first = std::min<size_t>(static_cast<size_t>(offset), total);
    const size_t count = std::min<size_t>(static_cast<size_t>(limit), total - first);
    std::vector<jlong> page(static_cast<size_t>(required), 0);
    page[0] = static_cast<jlong>(count);
    size_t max_bucket = 0;
    for (int type = kFirstType; type <= kLastType; ++type) {
        max_bucket = std::max(max_bucket, g_candidates[static_cast<size_t>(type)].addresses.size());
    }
    size_t global = 0, out_index = 0;
    for (size_t row = 0; row < max_bucket && out_index < count; ++row) {
        for (int type = kFirstType; type <= kLastType && out_index < count; ++type) {
            const auto &addresses = g_candidates[static_cast<size_t>(type)].addresses;
            if (row >= addresses.size()) continue;
            const uintptr_t address = addresses[row];
            if (global++ < first) continue;
            int64_t bits = 0;
            const bool readable = valueBits(address, type, &bits);
            const size_t base = 1 + out_index * kSnapshotStride;
            page[base] = static_cast<jlong>(address);
            page[base + 1] = type;
            page[base + 2] = readable ? 1 : 0;
            page[base + 3] = static_cast<jlong>(bits);
            ++out_index;
        }
    }
    env->SetLongArrayRegion(output, 0, required, page.data());
    return env->ExceptionCheck() ? -1 : static_cast<jint>(out_index);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeEdit(
        JNIEnv *env, jclass, jlong address, jint type, jstring expected, jstring replacement) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return toJString(env, performEdit(static_cast<uintptr_t>(address), type,
            fromJString(env, expected), fromJString(env, replacement)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeGetDiagnostics(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::ostringstream out;
    out << "backend=remote-memory-engine\n"
        << "remoteEngine=true\n"
        << "remoteTargetPid=" << g_target_pid << '\n'
        << "scope=" << (g_diag.scope == kScopeThorough ? "Java Thorough" : "Java Fast") << '\n'
        << "operationType=" << typeName(g_diag.search_type) << '\n'
        << "sessionType=" << typeName(g_search_type) << '\n'
        << "pageSize=" << g_diag.page_size << '\n'
        << "residentRuns=" << g_diag.resident_runs << '\n'
        << "residentRunsTruncated=" << (g_diag.runs_truncated ? "true" : "false") << '\n'
        << "residentBytes=" << g_diag.resident_bytes << '\n'
        << "residentPages=" << g_diag.resident_pages << '\n'
        << "bytesRead=" << g_diag.bytes_read << '\n'
        << "readFailures=" << g_diag.read_failures << '\n'
        << "matchesSeen=" << g_diag.matches_seen << '\n'
        << "retained=" << candidateCount(g_candidates) << '\n'
        << "candidateBytes=" << candidateBytes(g_candidates) << '\n'
        << "durationMs=" << g_diag.duration_ms << '\n'
        << "cancelled=" << (g_diag.cancelled ? "true" : "false") << '\n'
        << "resourceLimit=" << (g_diag.resource_limit ? "true" : "false") << '\n'
        << "relocationTracking=" << (g_diag.relocation_tracking ? "true" : "false") << '\n'
        << "relocationTrackLimit=" << kRelocationTrackLimit << '\n'
        << "relocationContexts=" << g_diag.relocation_contexts << '\n'
        << "relocationAttempted=" << (g_diag.relocation_attempted ? "true" : "false") << '\n'
        << "relocationOriginal=" << g_diag.relocation_original << '\n'
        << "relocationFreshMatches=" << g_diag.relocation_fresh_matches << '\n'
        << "relocationRecovered=" << g_diag.relocation_recovered << '\n'
        << "relocationAmbiguous=" << g_diag.relocation_ambiguous << '\n'
        << "relocationOriginalGroups=" << g_diag.relocation_original_groups << '\n'
        << "relocationFreshGroups=" << g_diag.relocation_fresh_groups << '\n'
        << "relocationRecoveredGroups=" << g_diag.relocation_recovered_groups << '\n'
        << "gcDependency=false\n"
        << "residencySource=target-mincore";
    for (int type = kFirstType; type <= kLastType; ++type) {
        const auto &bucket = g_candidates[static_cast<size_t>(type)];
        out << '\n' << typeName(type) << "Seen=" << bucket.matches_seen
            << " retained=" << bucket.addresses.size()
            << " overflow=" << (bucket.overflow ? "true" : "false");
    }
    return toJString(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeGetLastError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return toJString(env, g_last_error);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeClear(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseCandidates();
    g_last_error.clear();
    g_diag = {};
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true, std::memory_order_relaxed);
}
