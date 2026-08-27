#include <jni.h>

#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr int kTypeInt8 = 1;
constexpr int kTypeInt16 = 2;
constexpr int kTypeUInt16 = 3;
constexpr int kTypeInt32 = 4;
constexpr int kTypeInt64 = 5;
constexpr int kTypeFloat32 = 6;
constexpr int kTypeFloat64 = 7;
constexpr int kFirstType = kTypeInt8;
constexpr int kLastType = kTypeFloat64;

constexpr int kRawStride = 4;
constexpr int kLiveStride = 8;
constexpr size_t kMaxVisibleTyped = 100;
constexpr size_t kMaxTrackedGroups = 32;
constexpr size_t kHalfBytes = 64;
constexpr size_t kTargetSkipBytes = 8;
constexpr size_t kLaneBytes = 4;
constexpr size_t kLanesPerHalf = kHalfBytes / kLaneBytes;
constexpr size_t kLaneCount = kLanesPerHalf * 2;
constexpr size_t kMaxRecoveryAnchors = 6;
constexpr int kMaxCandidateTestsPerTrack = 512;

constexpr int kTrackUntracked = 0;
constexpr int kTrackStable = 1;
constexpr int kTrackRelocated = 2;
constexpr int kTrackSuspect = 3;
constexpr int kTrackAmbiguous = 4;
constexpr int kTrackLost = 5;

struct Region {
    uintptr_t start = 0;
    uintptr_t end = 0;
};

struct Signature {
    std::array<uint32_t, kLaneCount> values{};
    uint64_t active_mask = 0;
    bool valid = false;
};

struct Track {
    uint64_t id = 0;
    uintptr_t source_address = 0;
    uintptr_t current_address = 0;
    uintptr_t previous_address = 0;
    uint32_t type_mask = 0;
    Signature signature;
    int state = kTrackUntracked;
    int confidence = 0;
    uint32_t relocations = 0;
    uint64_t last_seen_epoch = 0;
};

struct PageGroup {
    uintptr_t raw_address = 0;
    uint32_t type_mask = 0;
    int track_index = -1;
};

struct AnchorRef {
    size_t track_index = 0;
    int lane = -1;
};

struct RecoveryState {
    uintptr_t best_address = 0;
    int best_score = -1;
    uintptr_t second_address = 0;
    int second_score = -1;
    int tested = 0;
    bool overflow = false;
};

std::mutex g_live_mutex;
std::vector<Track> g_tracks;
uint64_t g_next_id = 1;
uint64_t g_epoch = 0;
uint64_t g_validation_reads = 0;
uint64_t g_recovery_scans = 0;
uint64_t g_rebinds = 0;
uint64_t g_ambiguous = 0;
uint64_t g_lost = 0;

bool isType(int type) {
    return type >= kFirstType && type <= kLastType;
}

uint32_t typeBit(int type) {
    return isType(type) ? (1u << static_cast<uint32_t>(type)) : 0u;
}

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

size_t alignmentForMask(uint32_t mask) {
    size_t alignment = 1;
    for (int type = kFirstType; type <= kLastType; ++type) {
        if ((mask & typeBit(type)) != 0) alignment = std::max(alignment, widthForType(type));
    }
    return alignment;
}

bool readSelf(uintptr_t address, void *buffer, size_t size) {
    if (address == 0 || buffer == nullptr || size == 0) return false;
    iovec local{buffer, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    const ssize_t read = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    return read == static_cast<ssize_t>(size);
}

bool informative(uint32_t value) {
    return value != 0u && value != 0xFFFFFFFFu;
}

int laneQuality(uint32_t value) {
    if (!informative(value)) return -1;
    int quality = 0;
    if (value > 0xFFFFu) quality += 2;
    if ((value & 0xFFFFu) != 0u && (value >> 16u) != 0u) quality += 1;
    const uint8_t b0 = static_cast<uint8_t>(value);
    const uint8_t b1 = static_cast<uint8_t>(value >> 8u);
    const uint8_t b2 = static_cast<uint8_t>(value >> 16u);
    const uint8_t b3 = static_cast<uint8_t>(value >> 24u);
    if (!(b0 == b1 && b1 == b2 && b2 == b3)) quality += 1;
    return quality;
}

intptr_t laneOffset(int lane) {
    if (lane < static_cast<int>(kLanesPerHalf)) {
        return -static_cast<intptr_t>(kHalfBytes)
                + static_cast<intptr_t>(lane) * static_cast<intptr_t>(kLaneBytes);
    }
    return static_cast<intptr_t>(kTargetSkipBytes)
            + static_cast<intptr_t>(lane - static_cast<int>(kLanesPerHalf))
                    * static_cast<intptr_t>(kLaneBytes);
}

int activeCount(const Signature &signature) {
    return __builtin_popcountll(signature.active_mask);
}

bool captureSignature(uintptr_t address, Signature *out) {
    if (out == nullptr || address < kHalfBytes) return false;
    std::array<uint8_t, kHalfBytes> before{};
    std::array<uint8_t, kHalfBytes> after{};
    if (!readSelf(address - kHalfBytes, before.data(), before.size())
            || address > std::numeric_limits<uintptr_t>::max() - kTargetSkipBytes
            || !readSelf(address + kTargetSkipBytes, after.data(), after.size())) {
        out->valid = false;
        out->active_mask = 0;
        return false;
    }

    out->active_mask = 0;
    for (size_t i = 0; i < kLanesPerHalf; ++i) {
        uint32_t value = 0;
        memcpy(&value, before.data() + i * kLaneBytes, sizeof(value));
        out->values[i] = value;
        if (informative(value)) out->active_mask |= (1ull << i);
    }
    for (size_t i = 0; i < kLanesPerHalf; ++i) {
        uint32_t value = 0;
        memcpy(&value, after.data() + i * kLaneBytes, sizeof(value));
        const size_t lane = kLanesPerHalf + i;
        out->values[lane] = value;
        if (informative(value)) out->active_mask |= (1ull << lane);
    }
    out->valid = activeCount(*out) >= 4;
    return out->valid;
}

int signatureScore(const Signature &expected, const Signature &observed) {
    if (!expected.valid || !observed.valid) return 0;
    int score = 0;
    for (size_t i = 0; i < kLaneCount; ++i) {
        const uint64_t bit = 1ull << i;
        if ((expected.active_mask & bit) == 0) continue;
        if (expected.values[i] == observed.values[i]) ++score;
    }
    return score;
}

int requiredScore(const Signature &signature, bool moved) {
    const int active = activeCount(signature);
    if (active < 4) return active + 1;
    const int floor = moved ? 4 : 3;
    return std::min(active, std::max(floor, active / 4));
}

void learnStableLanes(Signature *expected, const Signature &observed, int score) {
    if (expected == nullptr || !expected->valid || !observed.valid) return;
    const int active = activeCount(*expected);
    if (active < 4 || score * 2 < active) return;
    for (size_t i = 0; i < kLaneCount; ++i) {
        const uint64_t bit = 1ull << i;
        if ((expected->active_mask & bit) == 0) continue;
        if (expected->values[i] != observed.values[i]) expected->active_mask &= ~bit;
    }
    expected->valid = activeCount(*expected) >= 4;
}

bool isKnownJavaHeapLine(const std::string &line) {
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
    return line.find("[anon:dalvik-") != std::string::npos
            && line.find("space") != std::string::npos
            && line.find("zygote space") == std::string::npos;
}

bool parseRegion(const std::string &line, Region *region, char permissions[5]) {
    unsigned long long start = 0;
    unsigned long long end = 0;
    if (sscanf(line.c_str(), "%llx-%llx %4s", &start, &end, permissions) != 3) return false;
    if (start >= end || end > static_cast<unsigned long long>(
            std::numeric_limits<uintptr_t>::max())) return false;
    region->start = static_cast<uintptr_t>(start);
    region->end = static_cast<uintptr_t>(end);
    return true;
}

std::vector<Region> collectJavaRegions() {
    std::ifstream maps("/proc/self/maps");
    std::vector<Region> regions;
    std::string line;
    while (std::getline(maps, line)) {
        if (!isKnownJavaHeapLine(line)) continue;
        Region region;
        char permissions[5] = {};
        if (!parseRegion(line, &region, permissions)) continue;
        if (permissions[0] != 'r' || permissions[1] != 'w') continue;
        regions.push_back(region);
    }
    return regions;
}

bool inRegions(const std::vector<Region> &regions, uintptr_t address) {
    for (const auto &region : regions) {
        if (address >= region.start && address < region.end) return true;
    }
    return false;
}

int findTrack(uintptr_t rawAddress) {
    for (size_t i = 0; i < g_tracks.size(); ++i) {
        if (g_tracks[i].source_address == rawAddress || g_tracks[i].current_address == rawAddress) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

int ensureTrack(uintptr_t address, uint32_t typeMask) {
    int existing = findTrack(address);
    if (existing >= 0) {
        g_tracks[static_cast<size_t>(existing)].type_mask |= typeMask;
        g_tracks[static_cast<size_t>(existing)].last_seen_epoch = g_epoch;
        return existing;
    }
    if (g_tracks.size() >= kMaxTrackedGroups) return -1;

    Track track;
    track.id = g_next_id++;
    track.source_address = address;
    track.current_address = address;
    track.type_mask = typeMask;
    track.last_seen_epoch = g_epoch;
    if (captureSignature(address, &track.signature)) {
        track.state = kTrackStable;
        track.confidence = 100;
    } else {
        track.state = kTrackUntracked;
        track.confidence = 0;
    }
    g_tracks.push_back(track);
    return static_cast<int>(g_tracks.size() - 1);
}

bool validateTrack(Track *track) {
    if (track == nullptr || !track->signature.valid || track->current_address == 0) return false;
    Signature observed;
    ++g_validation_reads;
    if (!captureSignature(track->current_address, &observed)) {
        track->state = kTrackSuspect;
        track->confidence = 0;
        return false;
    }
    const int score = signatureScore(track->signature, observed);
    const int active = activeCount(track->signature);
    track->confidence = active == 0 ? 0 : std::min(100, score * 100 / active);
    if (score < requiredScore(track->signature, false)) {
        track->state = kTrackSuspect;
        return false;
    }
    learnStableLanes(&track->signature, observed, score);
    track->state = kTrackStable;
    return true;
}

std::vector<int> bestAnchorLanes(const Signature &signature) {
    struct RankedLane { int lane; int quality; };
    std::vector<RankedLane> ranked;
    ranked.reserve(kLaneCount);
    for (size_t i = 0; i < kLaneCount; ++i) {
        if ((signature.active_mask & (1ull << i)) == 0) continue;
        int quality = laneQuality(signature.values[i]);
        if (quality >= 0) ranked.push_back({static_cast<int>(i), quality});
    }
    std::sort(ranked.begin(), ranked.end(), [](const RankedLane &a, const RankedLane &b) {
        return a.quality > b.quality;
    });

    std::vector<int> result;
    result.reserve(kMaxRecoveryAnchors);
    for (const auto &entry : ranked) {
        bool duplicateValue = false;
        for (int lane : result) {
            if (signature.values[static_cast<size_t>(lane)]
                    == signature.values[static_cast<size_t>(entry.lane)]) {
                duplicateValue = true;
                break;
            }
        }
        if (duplicateValue) continue;
        result.push_back(entry.lane);
        if (result.size() >= kMaxRecoveryAnchors) break;
    }
    return result;
}

void considerCandidate(size_t trackIndex, uintptr_t candidate,
        const std::vector<Region> &regions, RecoveryState *state) {
    if (state == nullptr || state->overflow || trackIndex >= g_tracks.size()) return;
    Track &track = g_tracks[trackIndex];
    if (candidate == 0 || candidate == track.current_address || !inRegions(regions, candidate)) return;
    const size_t alignment = alignmentForMask(track.type_mask);
    if (alignment > 1 && candidate % alignment != 0) return;
    if (++state->tested > kMaxCandidateTestsPerTrack) {
        state->overflow = true;
        return;
    }

    Signature observed;
    if (!captureSignature(candidate, &observed)) return;
    const int score = signatureScore(track.signature, observed);
    if (score < requiredScore(track.signature, true)) return;

    if (candidate == state->best_address) {
        state->best_score = std::max(state->best_score, score);
        return;
    }
    if (candidate == state->second_address) {
        state->second_score = std::max(state->second_score, score);
        return;
    }
    if (score > state->best_score) {
        state->second_address = state->best_address;
        state->second_score = state->best_score;
        state->best_address = candidate;
        state->best_score = score;
    } else if (score > state->second_score) {
        state->second_address = candidate;
        state->second_score = score;
    }
}

void recoverTracks(const std::vector<size_t> &suspects) {
    if (suspects.empty()) return;
    const std::vector<Region> regions = collectJavaRegions();
    if (regions.empty()) {
        for (size_t index : suspects) {
            g_tracks[index].state = kTrackLost;
            g_tracks[index].confidence = 0;
            ++g_lost;
        }
        return;
    }

    std::unordered_map<uint32_t, std::vector<AnchorRef>> anchorIndex;
    std::vector<RecoveryState> recovery(g_tracks.size());
    for (size_t trackIndex : suspects) {
        const auto lanes = bestAnchorLanes(g_tracks[trackIndex].signature);
        for (int lane : lanes) {
            anchorIndex[g_tracks[trackIndex].signature.values[static_cast<size_t>(lane)]]
                    .push_back({trackIndex, lane});
        }
    }
    if (anchorIndex.empty()) {
        for (size_t index : suspects) {
            g_tracks[index].state = kTrackLost;
            g_tracks[index].confidence = 0;
            ++g_lost;
        }
        return;
    }

    ++g_recovery_scans;
    const long pageLong = sysconf(_SC_PAGESIZE);
    const size_t pageSize = pageLong > 0 ? static_cast<size_t>(pageLong) : 4096u;
    std::vector<uint8_t> page(pageSize);
    for (const auto &region : regions) {
        uintptr_t pageAddress = region.start - (region.start % pageSize);
        for (; pageAddress < region.end; pageAddress += pageSize) {
            unsigned char residency = 0;
            if (mincore(reinterpret_cast<void *>(pageAddress), pageSize, &residency) != 0
                    || (residency & 1u) == 0u) continue;
            const uintptr_t readStart = std::max(pageAddress, region.start);
            const uintptr_t readEnd = std::min(pageAddress + pageSize, region.end);
            const size_t bytes = static_cast<size_t>(readEnd - readStart);
            if (bytes < sizeof(uint32_t) || !readSelf(readStart, page.data(), bytes)) continue;

            for (size_t offset = 0; offset + sizeof(uint32_t) <= bytes; ++offset) {
                uint32_t value = 0;
                memcpy(&value, page.data() + offset, sizeof(value));
                auto found = anchorIndex.find(value);
                if (found == anchorIndex.end()) continue;
                const uintptr_t anchorAddress = readStart + offset;
                for (const AnchorRef &ref : found->second) {
                    if (ref.track_index >= recovery.size() || recovery[ref.track_index].overflow) continue;
                    const intptr_t relative = laneOffset(ref.lane);
                    uintptr_t candidate = 0;
                    if (relative < 0) {
                        const uintptr_t add = static_cast<uintptr_t>(-relative);
                        if (anchorAddress > std::numeric_limits<uintptr_t>::max() - add) continue;
                        candidate = anchorAddress + add;
                    } else {
                        const uintptr_t subtract = static_cast<uintptr_t>(relative);
                        if (anchorAddress < subtract) continue;
                        candidate = anchorAddress - subtract;
                    }
                    considerCandidate(ref.track_index, candidate, regions,
                            &recovery[ref.track_index]);
                }
            }
        }
    }

    for (size_t trackIndex : suspects) {
        Track &track = g_tracks[trackIndex];
        RecoveryState &result = recovery[trackIndex];
        const int required = requiredScore(track.signature, true);
        if (result.overflow) {
            track.state = kTrackAmbiguous;
            track.confidence = 0;
            ++g_ambiguous;
            continue;
        }
        if (result.best_address == 0 || result.best_score < required) {
            track.state = kTrackLost;
            track.confidence = 0;
            ++g_lost;
            continue;
        }
        if (result.second_address != 0 && result.second_score >= required
                && result.best_score < result.second_score + 2) {
            track.state = kTrackAmbiguous;
            track.confidence = 0;
            ++g_ambiguous;
            continue;
        }

        Signature rebound;
        if (!captureSignature(result.best_address, &rebound)) {
            track.state = kTrackLost;
            track.confidence = 0;
            ++g_lost;
            continue;
        }
        const int active = activeCount(track.signature);
        track.previous_address = track.current_address;
        track.current_address = result.best_address;
        track.state = kTrackRelocated;
        track.confidence = active == 0 ? 0
                : std::min(100, result.best_score * 100 / active);
        ++track.relocations;
        ++g_rebinds;
        learnStableLanes(&track.signature, rebound, result.best_score);
    }
}

uint64_t readValueBits(uintptr_t address, int type, bool *readable) {
    uint64_t bits = 0;
    const size_t width = widthForType(type);
    *readable = width > 0 && readSelf(address, &bits, width);
    return bits;
}

void pruneOldTracks() {
    g_tracks.erase(std::remove_if(g_tracks.begin(), g_tracks.end(), [](const Track &track) {
        return track.last_seen_epoch + 2 < g_epoch;
    }), g_tracks.end());
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeRefreshVisibleCandidates(
        JNIEnv *env, jclass, jlongArray raw_page, jint count, jlongArray output) {
    if (raw_page == nullptr || output == nullptr || count < 0
            || count > static_cast<jint>(kMaxVisibleTyped)) return -1;
    const jsize rawLength = env->GetArrayLength(raw_page);
    const jsize outputLength = env->GetArrayLength(output);
    if (rawLength < 1 + count * kRawStride || outputLength < 1 + count * kLiveStride) return -1;

    std::array<jlong, 1 + kMaxVisibleTyped * kRawStride> raw{};
    std::array<jlong, 1 + kMaxVisibleTyped * kLiveStride> live{};
    env->GetLongArrayRegion(raw_page, 0, 1 + count * kRawStride, raw.data());
    if (env->ExceptionCheck()) return -1;

    std::lock_guard<std::mutex> lock(g_live_mutex);
    ++g_epoch;

    std::array<PageGroup, kMaxVisibleTyped> groups{};
    size_t groupCount = 0;
    for (int i = 0; i < count; ++i) {
        const size_t index = 1 + static_cast<size_t>(i) * kRawStride;
        const uintptr_t address = static_cast<uintptr_t>(raw[index]);
        const int type = static_cast<int>(raw[index + 1]);
        if (address == 0 || !isType(type)) continue;
        size_t group = 0;
        for (; group < groupCount; ++group) {
            if (groups[group].raw_address == address) break;
        }
        if (group == groupCount && groupCount < groups.size()) {
            groups[groupCount].raw_address = address;
            ++groupCount;
        }
        if (group < groupCount) groups[group].type_mask |= typeBit(type);
    }

    std::vector<size_t> suspects;
    suspects.reserve(kMaxTrackedGroups);
    for (size_t group = 0; group < groupCount; ++group) {
        if (group >= kMaxTrackedGroups) break;
        const int trackIndex = ensureTrack(groups[group].raw_address, groups[group].type_mask);
        groups[group].track_index = trackIndex;
        if (trackIndex < 0) continue;
        Track &track = g_tracks[static_cast<size_t>(trackIndex)];
        track.last_seen_epoch = g_epoch;
        if (track.signature.valid && !validateTrack(&track)) {
            suspects.push_back(static_cast<size_t>(trackIndex));
        }
    }
    recoverTracks(suspects);

    live[0] = count;
    for (int i = 0; i < count; ++i) {
        const size_t rawIndex = 1 + static_cast<size_t>(i) * kRawStride;
        const size_t liveIndex = 1 + static_cast<size_t>(i) * kLiveStride;
        const uintptr_t rawAddress = static_cast<uintptr_t>(raw[rawIndex]);
        const int type = static_cast<int>(raw[rawIndex + 1]);
        uintptr_t address = rawAddress;
        uintptr_t previous = 0;
        int state = kTrackUntracked;
        int confidence = 0;
        uint32_t relocations = 0;

        for (size_t group = 0; group < groupCount; ++group) {
            if (groups[group].raw_address != rawAddress) continue;
            const int trackIndex = groups[group].track_index;
            if (trackIndex >= 0 && static_cast<size_t>(trackIndex) < g_tracks.size()) {
                const Track &track = g_tracks[static_cast<size_t>(trackIndex)];
                address = track.current_address;
                previous = track.previous_address;
                state = track.state;
                confidence = track.confidence;
                relocations = track.relocations;
            }
            break;
        }

        bool readable = false;
        uint64_t valueBits = 0;
        if (state != kTrackAmbiguous && state != kTrackLost && isType(type)) {
            valueBits = readValueBits(address, type, &readable);
        }
        live[liveIndex] = static_cast<jlong>(address);
        live[liveIndex + 1] = type;
        live[liveIndex + 2] = readable ? 1 : 0;
        live[liveIndex + 3] = static_cast<jlong>(valueBits);
        live[liveIndex + 4] = state;
        live[liveIndex + 5] = static_cast<jlong>(previous);
        live[liveIndex + 6] = confidence;
        live[liveIndex + 7] = relocations;
    }

    pruneOldTracks();
    env->SetLongArrayRegion(output, 0, 1 + count * kLiveStride, live.data());
    return env->ExceptionCheck() ? -1 : count;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeResetVisibleTracking(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_live_mutex);
    g_tracks.clear();
    g_epoch = 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeMemoryAgent_nativeGetVisibleTrackingDiagnostics(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_live_mutex);
    uint64_t stable = 0;
    uint64_t relocated = 0;
    uint64_t suspect = 0;
    uint64_t ambiguous = 0;
    uint64_t lost = 0;
    uint64_t untracked = 0;
    for (const Track &track : g_tracks) {
        switch (track.state) {
            case kTrackStable: ++stable; break;
            case kTrackRelocated: ++relocated; break;
            case kTrackSuspect: ++suspect; break;
            case kTrackAmbiguous: ++ambiguous; break;
            case kTrackLost: ++lost; break;
            default: ++untracked; break;
        }
    }
    std::ostringstream out;
    out << "liveTrackedGroups=" << g_tracks.size()
        << "\nliveTrackLimit=" << kMaxTrackedGroups
        << "\nliveStable=" << stable
        << "\nliveRelocated=" << relocated
        << "\nliveSuspect=" << suspect
        << "\nliveAmbiguous=" << ambiguous
        << "\nliveLost=" << lost
        << "\nliveUntracked=" << untracked
        << "\nliveValidationReads=" << g_validation_reads
        << "\nliveRecoveryScans=" << g_recovery_scans
        << "\nliveRebinds=" << g_rebinds
        << "\nliveAmbiguousTotal=" << g_ambiguous
        << "\nliveLostTotal=" << g_lost;
    const std::string text = out.str();
    return env->NewStringUTF(text.c_str());
}
