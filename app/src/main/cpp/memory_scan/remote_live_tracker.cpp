#include <jni.h>
#include <sys/uio.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <sstream>
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
constexpr size_t kRecoveryReadChunk = 64 * 1024;
constexpr uint64_t kRecoveryBackoffEpochs = 4;

constexpr int kTrackUntracked = 0;
constexpr int kTrackStable = 1;
constexpr int kTrackRelocated = 2;
constexpr int kTrackSuspect = 3;
constexpr int kTrackAmbiguous = 4;
constexpr int kTrackLost = 5;

struct MemoryRun {
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
    uint64_t next_recovery_epoch = 0;
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

std::mutex g_mutex;
pid_t g_target_pid = -1;
size_t g_page_size = 4096;
std::vector<MemoryRun> g_runs;
std::vector<Track> g_tracks;
uint64_t g_next_id = 1;
uint64_t g_epoch = 0;
uint64_t g_validation_reads = 0;
uint64_t g_recovery_scans = 0;
uint64_t g_recovery_bytes = 0;
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

bool remoteRead(uintptr_t address, void *buffer, size_t size) {
    if (g_target_pid <= 0 || address == 0 || buffer == nullptr || size == 0) return false;
    iovec local{buffer, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_readv(g_target_pid, &local, 1, &remote, 1, 0)
            == static_cast<ssize_t>(size);
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
    if (out == nullptr || address < kHalfBytes
            || address > std::numeric_limits<uintptr_t>::max() - kTargetSkipBytes) return false;
    std::array<uint8_t, kHalfBytes> before{};
    std::array<uint8_t, kHalfBytes> after{};
    if (!remoteRead(address - kHalfBytes, before.data(), before.size())
            || !remoteRead(address + kTargetSkipBytes, after.data(), after.size())) {
        *out = {};
        return false;
    }

    *out = {};
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
        if ((expected.active_mask & bit) != 0 && expected.values[i] == observed.values[i]) ++score;
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
        if ((expected->active_mask & bit) != 0 && expected->values[i] != observed.values[i]) {
            expected->active_mask &= ~bit;
        }
    }
    expected->valid = activeCount(*expected) >= 4;
}

bool inRuns(uintptr_t address) {
    for (const auto &run : g_runs) {
        if (address >= run.start && address < run.end) return true;
    }
    return false;
}

int findTrack(uintptr_t address) {
    for (size_t i = 0; i < g_tracks.size(); ++i) {
        if (g_tracks[i].source_address == address || g_tracks[i].current_address == address) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

int ensureTrack(uintptr_t address, uint32_t type_mask) {
    const int existing = findTrack(address);
    if (existing >= 0) {
        Track &track = g_tracks[static_cast<size_t>(existing)];
        track.type_mask |= type_mask;
        track.last_seen_epoch = g_epoch;
        return existing;
    }
    if (g_tracks.size() >= kMaxTrackedGroups) return -1;

    Track track;
    track.id = g_next_id++;
    track.source_address = address;
    track.current_address = address;
    track.type_mask = type_mask;
    track.last_seen_epoch = g_epoch;
    if (captureSignature(address, &track.signature)) {
        track.state = kTrackStable;
        track.confidence = 100;
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
    struct Ranked { int lane; int quality; };
    std::vector<Ranked> ranked;
    ranked.reserve(kLaneCount);
    for (size_t i = 0; i < kLaneCount; ++i) {
        if ((signature.active_mask & (1ull << i)) == 0) continue;
        const int quality = laneQuality(signature.values[i]);
        if (quality >= 0) ranked.push_back({static_cast<int>(i), quality});
    }
    std::sort(ranked.begin(), ranked.end(), [](const Ranked &a, const Ranked &b) {
        return a.quality > b.quality;
    });
    std::vector<int> result;
    result.reserve(kMaxRecoveryAnchors);
    for (const auto &entry : ranked) {
        bool duplicate = false;
        for (int lane : result) {
            if (signature.values[static_cast<size_t>(lane)]
                    == signature.values[static_cast<size_t>(entry.lane)]) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) result.push_back(entry.lane);
        if (result.size() >= kMaxRecoveryAnchors) break;
    }
    return result;
}

void considerCandidate(size_t track_index, uintptr_t candidate, RecoveryState *state) {
    if (state == nullptr || state->overflow || track_index >= g_tracks.size()) return;
    Track &track = g_tracks[track_index];
    if (candidate == 0 || candidate == track.current_address || !inRuns(candidate)) return;
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
    if (suspects.empty() || g_target_pid <= 0 || g_runs.empty()) return;
    std::unordered_map<uint32_t, std::vector<AnchorRef>> anchors;
    std::vector<RecoveryState> recovery(g_tracks.size());
    for (size_t track_index : suspects) {
        if (track_index >= g_tracks.size()) continue;
        const auto lanes = bestAnchorLanes(g_tracks[track_index].signature);
        for (int lane : lanes) {
            anchors[g_tracks[track_index].signature.values[static_cast<size_t>(lane)]]
                    .push_back({track_index, lane});
        }
    }
    if (anchors.empty()) {
        for (size_t index : suspects) {
            if (index >= g_tracks.size()) continue;
            Track &track = g_tracks[index];
            track.state = kTrackLost;
            track.confidence = 0;
            track.next_recovery_epoch = g_epoch + kRecoveryBackoffEpochs;
            ++g_lost;
        }
        return;
    }

    ++g_recovery_scans;
    std::vector<uint8_t> buffer(kRecoveryReadChunk);
    for (const auto &run : g_runs) {
        uintptr_t cursor = run.start;
        while (cursor < run.end) {
            const size_t requested = static_cast<size_t>(
                    std::min<uintptr_t>(run.end - cursor, buffer.size()));
            if (!remoteRead(cursor, buffer.data(), requested)) {
                cursor += std::min<uintptr_t>(run.end - cursor, g_page_size);
                continue;
            }
            g_recovery_bytes += requested;
            for (size_t offset = 0; offset + sizeof(uint32_t) <= requested; ++offset) {
                uint32_t value = 0;
                memcpy(&value, buffer.data() + offset, sizeof(value));
                auto found = anchors.find(value);
                if (found == anchors.end()) continue;
                const uintptr_t anchor_address = cursor + offset;
                for (const AnchorRef &ref : found->second) {
                    if (ref.track_index >= recovery.size() || recovery[ref.track_index].overflow) continue;
                    const intptr_t relative = laneOffset(ref.lane);
                    uintptr_t candidate = 0;
                    if (relative < 0) {
                        const uintptr_t add = static_cast<uintptr_t>(-relative);
                        if (anchor_address > std::numeric_limits<uintptr_t>::max() - add) continue;
                        candidate = anchor_address + add;
                    } else {
                        const uintptr_t subtract = static_cast<uintptr_t>(relative);
                        if (anchor_address < subtract) continue;
                        candidate = anchor_address - subtract;
                    }
                    considerCandidate(ref.track_index, candidate, &recovery[ref.track_index]);
                }
            }
            cursor += requested;
        }
    }

    for (size_t track_index : suspects) {
        if (track_index >= g_tracks.size()) continue;
        Track &track = g_tracks[track_index];
        RecoveryState &result = recovery[track_index];
        const int required = requiredScore(track.signature, true);
        if (result.overflow) {
            track.state = kTrackAmbiguous;
            track.confidence = 0;
            track.next_recovery_epoch = g_epoch + kRecoveryBackoffEpochs;
            ++g_ambiguous;
            continue;
        }
        if (result.best_address == 0 || result.best_score < required) {
            track.state = kTrackLost;
            track.confidence = 0;
            track.next_recovery_epoch = g_epoch + kRecoveryBackoffEpochs;
            ++g_lost;
            continue;
        }
        if (result.second_address != 0 && result.second_score >= required
                && result.best_score < result.second_score + 2) {
            track.state = kTrackAmbiguous;
            track.confidence = 0;
            track.next_recovery_epoch = g_epoch + kRecoveryBackoffEpochs;
            ++g_ambiguous;
            continue;
        }

        Signature rebound;
        if (!captureSignature(result.best_address, &rebound)) {
            track.state = kTrackLost;
            track.confidence = 0;
            track.next_recovery_epoch = g_epoch + kRecoveryBackoffEpochs;
            ++g_lost;
            continue;
        }
        const int old_active = activeCount(track.signature);
        track.previous_address = track.current_address;
        track.current_address = result.best_address;
        track.state = kTrackRelocated;
        track.confidence = old_active == 0 ? 0
                : std::min(100, result.best_score * 100 / old_active);
        ++track.relocations;
        ++g_rebinds;
        // Rebase identity after a unique move. ART references surrounding the primitive may have
        // legitimately changed during compaction; keeping the old signature causes oscillation.
        track.signature = rebound;
        track.next_recovery_epoch = 0;
    }
}

uint64_t readValueBits(uintptr_t address, int type, bool *readable) {
    uint64_t bits = 0;
    const size_t width = widthForType(type);
    *readable = width > 0 && remoteRead(address, &bits, width);
    return bits;
}

void pruneOldTracks() {
    g_tracks.erase(std::remove_if(g_tracks.begin(), g_tracks.end(), [](const Track &track) {
        return track.last_seen_epoch + 2 < g_epoch;
    }), g_tracks.end());
}

void resetTrackingLocked() {
    g_tracks.clear();
    g_epoch = 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeConfigureVisibleTarget(
        JNIEnv *env, jclass, jint target_pid, jint page_size, jlongArray runs) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (target_pid <= 0 || page_size <= 0 || runs == nullptr || env->GetArrayLength(runs) < 2) {
        return env->NewStringUTF("invalid live target configuration");
    }
    const jsize length = env->GetArrayLength(runs);
    std::vector<jlong> raw(static_cast<size_t>(length));
    env->GetLongArrayRegion(runs, 0, length, raw.data());
    if (env->ExceptionCheck()) return env->NewStringUTF("unable to read live resident runs");
    const int count = static_cast<int>(raw[0]);
    if (count < 0 || 2 + count * 2 > length) return env->NewStringUTF("malformed live resident runs");
    std::vector<MemoryRun> parsed;
    parsed.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const uintptr_t start = static_cast<uintptr_t>(raw[2 + i * 2]);
        const uintptr_t end = static_cast<uintptr_t>(raw[3 + i * 2]);
        if (start != 0 && end > start) parsed.push_back({start, end});
    }
    if (g_target_pid != target_pid) resetTrackingLocked();
    g_target_pid = static_cast<pid_t>(target_pid);
    g_page_size = static_cast<size_t>(page_size);
    g_runs.swap(parsed);
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeRefreshVisibleCandidates(
        JNIEnv *env, jclass, jlongArray raw_page, jint count, jlongArray output) {
    if (raw_page == nullptr || output == nullptr || count < 0
            || count > static_cast<jint>(kMaxVisibleTyped)) return -1;
    if (env->GetArrayLength(raw_page) < 1 + count * kRawStride
            || env->GetArrayLength(output) < 1 + count * kLiveStride) return -1;

    std::array<jlong, 1 + kMaxVisibleTyped * kRawStride> raw{};
    std::array<jlong, 1 + kMaxVisibleTyped * kLiveStride> live{};
    env->GetLongArrayRegion(raw_page, 0, 1 + count * kRawStride, raw.data());
    if (env->ExceptionCheck()) return -1;

    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_epoch;
    std::array<PageGroup, kMaxVisibleTyped> groups{};
    size_t group_count = 0;
    for (int i = 0; i < count; ++i) {
        const size_t index = 1 + static_cast<size_t>(i) * kRawStride;
        const uintptr_t address = static_cast<uintptr_t>(raw[index]);
        const int type = static_cast<int>(raw[index + 1]);
        if (address == 0 || !isType(type)) continue;
        size_t group = 0;
        for (; group < group_count; ++group) {
            if (groups[group].raw_address == address) break;
        }
        if (group == group_count && group_count < groups.size()) {
            groups[group_count].raw_address = address;
            ++group_count;
        }
        if (group < group_count) groups[group].type_mask |= typeBit(type);
    }

    std::vector<size_t> suspects;
    suspects.reserve(kMaxTrackedGroups);
    for (size_t group = 0; group < group_count && group < kMaxTrackedGroups; ++group) {
        const int index = ensureTrack(groups[group].raw_address, groups[group].type_mask);
        groups[group].track_index = index;
        if (index < 0) continue;
        Track &track = g_tracks[static_cast<size_t>(index)];
        track.last_seen_epoch = g_epoch;
        if (track.signature.valid && !validateTrack(&track)
                && g_epoch >= track.next_recovery_epoch) {
            suspects.push_back(static_cast<size_t>(index));
        }
    }
    recoverTracks(suspects);

    live[0] = count;
    for (int i = 0; i < count; ++i) {
        const size_t raw_index = 1 + static_cast<size_t>(i) * kRawStride;
        const size_t live_index = 1 + static_cast<size_t>(i) * kLiveStride;
        const uintptr_t raw_address = static_cast<uintptr_t>(raw[raw_index]);
        const int type = static_cast<int>(raw[raw_index + 1]);
        uintptr_t address = raw_address;
        uintptr_t previous = 0;
        int state = kTrackUntracked;
        int confidence = 0;
        uint32_t relocations = 0;
        for (size_t group = 0; group < group_count; ++group) {
            if (groups[group].raw_address != raw_address) continue;
            const int index = groups[group].track_index;
            if (index >= 0 && static_cast<size_t>(index) < g_tracks.size()) {
                const Track &track = g_tracks[static_cast<size_t>(index)];
                address = track.current_address;
                previous = track.previous_address;
                state = track.state;
                confidence = track.confidence;
                relocations = track.relocations;
            }
            break;
        }
        bool readable = false;
        uint64_t value_bits = 0;
        if (state != kTrackAmbiguous && state != kTrackLost && isType(type)) {
            value_bits = readValueBits(address, type, &readable);
        }
        live[live_index] = static_cast<jlong>(address);
        live[live_index + 1] = type;
        live[live_index + 2] = readable ? 1 : 0;
        live[live_index + 3] = static_cast<jlong>(value_bits);
        live[live_index + 4] = state;
        live[live_index + 5] = static_cast<jlong>(previous);
        live[live_index + 6] = confidence;
        live[live_index + 7] = relocations;
    }
    pruneOldTracks();
    env->SetLongArrayRegion(output, 0, 1 + count * kLiveStride, live.data());
    return env->ExceptionCheck() ? -1 : count;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeResetVisibleTracking(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    resetTrackingLocked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteScanner_nativeGetVisibleTrackingDiagnostics(
        JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    uint64_t stable = 0, relocated = 0, suspect = 0, ambiguous = 0, lost = 0, untracked = 0;
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
    out << "liveAddressBackend=remote-memory-engine"
        << "\nliveTrackedGroups=" << g_tracks.size()
        << "\nliveTrackLimit=" << kMaxTrackedGroups
        << "\nliveStable=" << stable
        << "\nliveRelocated=" << relocated
        << "\nliveSuspect=" << suspect
        << "\nliveAmbiguous=" << ambiguous
        << "\nliveLost=" << lost
        << "\nliveUntracked=" << untracked
        << "\nliveValidationReads=" << g_validation_reads
        << "\nliveRecoveryScans=" << g_recovery_scans
        << "\nliveRecoveryBytes=" << g_recovery_bytes
        << "\nliveRebinds=" << g_rebinds
        << "\nliveAmbiguousTotal=" << g_ambiguous
        << "\nliveLostTotal=" << g_lost;
    const std::string text = out.str();
    return env->NewStringUTF(text.c_str());
}
