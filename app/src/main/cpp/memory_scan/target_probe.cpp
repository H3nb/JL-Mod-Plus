#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

namespace {

constexpr int kScopeFast = 0;
constexpr int kScopeThorough = 1;
volatile uint64_t g_remote_probe = 0x4A4C4D50524F4245ULL;  // "JLMPROBE"

struct Region {
    uintptr_t start = 0;
    uintptr_t end = 0;
};

bool knownJavaHeap(const std::string &line) {
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

bool broadJavaHeap(const std::string &line) {
    if (knownJavaHeap(line)) return true;
    if (line.find("[anon:dalvik-") == std::string::npos) return false;
    if (line.find("space") == std::string::npos) return false;
    return line.find("zygote space") == std::string::npos;
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

std::vector<Region> collectJavaRegions(int scope) {
    std::ifstream maps("/proc/self/maps");
    std::vector<Region> regions;
    std::string line;
    while (std::getline(maps, line)) {
        const bool selected = scope == kScopeThorough ? broadJavaHeap(line) : knownJavaHeap(line);
        if (!selected) continue;
        Region region;
        char permissions[5] = {};
        if (!parseRegion(line, &region, permissions)) continue;
        if (permissions[0] != 'r' || permissions[1] != 'w') continue;
        regions.push_back(region);
    }
    return regions;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_probeAddress(JNIEnv *, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&g_remote_probe));
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_probeValue(JNIEnv *, jclass) {
    return static_cast<jlong>(g_remote_probe);
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_pageSize(JNIEnv *, jclass) {
    const long value = sysconf(_SC_PAGESIZE);
    return value > 0 ? static_cast<jint>(value) : 4096;
}

extern "C" JNIEXPORT jint JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeTargetProbe_fillResidentJavaRuns(
        JNIEnv *env, jclass, jlongArray output, jint scope, jint max_runs) {
    if (output == nullptr || max_runs <= 0 || (scope != kScopeFast && scope != kScopeThorough)) {
        return -1;
    }
    const jsize required = 2 + max_runs * 2;
    if (env->GetArrayLength(output) < required) return -1;

    const long page_long = sysconf(_SC_PAGESIZE);
    const size_t page_size = page_long > 0 ? static_cast<size_t>(page_long) : 4096u;
    std::vector<jlong> result(static_cast<size_t>(required), 0);
    int run_count = 0;
    bool truncated = false;

    for (const Region &region : collectJavaRegions(scope)) {
        const uintptr_t length = region.end - region.start;
        const size_t pages = static_cast<size_t>((length + page_size - 1) / page_size);
        if (pages == 0) continue;
        std::vector<unsigned char> residency;
        try {
            residency.resize(pages);
        } catch (...) {
            continue;
        }
        if (mincore(reinterpret_cast<void *>(region.start), static_cast<size_t>(length),
                residency.data()) != 0) continue;

        size_t page = 0;
        while (page < pages) {
            while (page < pages && (residency[page] & 1u) == 0u) ++page;
            if (page >= pages) break;
            const size_t first = page;
            while (page < pages && (residency[page] & 1u) != 0u) ++page;
            if (run_count >= max_runs) {
                truncated = true;
                break;
            }
            const uintptr_t start = region.start + static_cast<uintptr_t>(first) * page_size;
            const uintptr_t end = std::min<uintptr_t>(
                    region.end, region.start + static_cast<uintptr_t>(page) * page_size);
            const size_t index = 2 + static_cast<size_t>(run_count) * 2;
            result[index] = static_cast<jlong>(start);
            result[index + 1] = static_cast<jlong>(end);
            ++run_count;
        }
        if (truncated) break;
    }

    result[0] = run_count;
    result[1] = truncated ? 1 : 0;
    env->SetLongArrayRegion(output, 0, required, result.data());
    return env->ExceptionCheck() ? -1 : run_count;
}
